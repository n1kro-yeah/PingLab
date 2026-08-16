package live.nikro.pinglab.domain.stats

import live.nikro.pinglab.core.model.LatencyStats
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.domain.quality.MosCalculator
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * All latency maths in one testable place.
 *
 * Two different jitter definitions are produced because they answer different questions:
 *  - **Mean deviation** (`mdev`, what `ping` prints): average distance from the mean. Good for
 *    describing how spread out a sample set is.
 *  - **RFC 3550** (what RTP/VoIP stacks use): an exponentially weighted average of the
 *    *consecutive* inter-arrival differences, so a single late packet is visible instead of
 *    being smoothed away. This is the number that predicts call quality.
 */
object LatencyStatistics {

    data class HistogramBucket(
        val fromMs: Double,
        val toMs: Double,
        val count: Int,
    ) {
        val label: String get() = "${fromMs.roundToInt()}-${toMs.roundToInt()}"
    }

    enum class Trend { IMPROVING, STABLE, DEGRADING, UNKNOWN }

    /** Builds the full statistics block for a window of probe results. */
    fun compute(results: List<ProbeResult>): LatencyStats {
        if (results.isEmpty()) return LatencyStats.EMPTY

        val samples = results.mapNotNull { it.rttMs?.takeIf { _ -> it.isSuccess } }
        val sent = results.size
        val received = samples.size

        if (received == 0) {
            return LatencyStats(
                sent = sent,
                received = 0,
                consecutiveFailures = trailingFailures(results),
                longestOutage = longestFailureRun(results),
                firstTimestampMs = results.first().timestampMs,
                lastTimestampMs = results.last().timestampMs,
            )
        }

        val sorted = samples.sorted()
        val mean = samples.average()
        val stdDev = standardDeviation(samples, mean)
        val mdev = meanDeviationJitter(samples, mean)
        val rfcJitter = rfc3550Jitter(samples)
        val lossPercent = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent
        val rating = MosCalculator.rate(mean, rfcJitter ?: mdev ?: 0.0, lossPercent)

        return LatencyStats(
            sent = sent,
            received = received,
            minMs = sorted.first(),
            maxMs = sorted.last(),
            avgMs = mean,
            medianMs = percentile(sorted, 50.0),
            p90Ms = percentile(sorted, 90.0),
            p95Ms = percentile(sorted, 95.0),
            p99Ms = percentile(sorted, 99.0),
            stdDevMs = stdDev,
            meanDeviationJitterMs = mdev,
            rfc3550JitterMs = rfcJitter,
            rFactor = rating.rFactor,
            mos = rating.mos,
            consecutiveFailures = trailingFailures(results),
            longestOutage = longestFailureRun(results),
            firstTimestampMs = results.first().timestampMs,
            lastTimestampMs = results.last().timestampMs,
        )
    }

    /**
     * Linear-interpolated percentile over an already-sorted list.
     * Matches the "exclusive/inclusive R-7" definition used by NumPy and Excel's PERCENTILE.INC.
     */
    fun percentile(sortedSamples: List<Double>, percentile: Double): Double? {
        if (sortedSamples.isEmpty()) return null
        if (sortedSamples.size == 1) return sortedSamples.first()
        val p = percentile.coerceIn(0.0, 100.0) / 100.0
        val position = p * (sortedSamples.size - 1)
        val lowerIndex = position.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(sortedSamples.size - 1)
        val weight = position - lowerIndex
        return sortedSamples[lowerIndex] * (1 - weight) + sortedSamples[upperIndex] * weight
    }

    fun standardDeviation(samples: List<Double>, mean: Double = samples.average()): Double? {
        if (samples.size < 2) return 0.0.takeIf { samples.isNotEmpty() }
        val variance = samples.sumOf { val d = it - mean; d * d } / samples.size
        return sqrt(variance)
    }

    /** `mdev` as printed by iputils ping: the mean absolute deviation from the mean. */
    fun meanDeviationJitter(samples: List<Double>, mean: Double = samples.average()): Double? {
        if (samples.isEmpty()) return null
        if (samples.size == 1) return 0.0
        return samples.sumOf { abs(it - mean) } / samples.size
    }

    /**
     * RFC 3550 section 6.4.1 interarrival jitter:
     * `J(i) = J(i-1) + (|D(i-1, i)| - J(i-1)) / 16`
     */
    fun rfc3550Jitter(samples: List<Double>): Double? {
        if (samples.size < 2) return if (samples.isEmpty()) null else 0.0
        var jitter = 0.0
        for (index in 1 until samples.size) {
            val difference = abs(samples[index] - samples[index - 1])
            jitter += (difference - jitter) / 16.0
        }
        return jitter
    }

    /** Number of failures at the tail of the window \u2014 drives the "host is down" decision. */
    fun trailingFailures(results: List<ProbeResult>): Int {
        var count = 0
        for (index in results.indices.reversed()) {
            if (results[index].isSuccess) break
            count++
        }
        return count
    }

    /** Longest uninterrupted run of failures anywhere in the window. */
    fun longestFailureRun(results: List<ProbeResult>): Int {
        var best = 0
        var current = 0
        for (result in results) {
            if (result.isSuccess) {
                current = 0
            } else {
                current++
                if (current > best) best = current
            }
        }
        return best
    }

    /** Distribution of latencies, used by the histogram chart. */
    fun histogram(samples: List<Double>, bucketCount: Int = 12): List<HistogramBucket> {
        if (samples.isEmpty()) return emptyList()
        val min = samples.min()
        val max = samples.max()
        if (max - min < 0.0001) {
            return listOf(HistogramBucket(min, max, samples.size))
        }
        val buckets = bucketCount.coerceIn(4, 40)
        val width = (max - min) / buckets
        val counts = IntArray(buckets)
        for (sample in samples) {
            val index = (((sample - min) / width).toInt()).coerceIn(0, buckets - 1)
            counts[index]++
        }
        return (0 until buckets).map { index ->
            HistogramBucket(
                fromMs = min + width * index,
                toMs = min + width * (index + 1),
                count = counts[index],
            )
        }
    }

    /**
     * Compares the most recent third of the window against the oldest third.
     * A 15% shift is required before we call it a trend, to avoid flapping labels.
     */
    fun trend(samples: List<Double>): Trend {
        if (samples.size < 6) return Trend.UNKNOWN
        val slice = samples.size / 3
        val oldest = samples.take(slice).average()
        val newest = samples.takeLast(slice).average()
        if (oldest <= 0.0) return Trend.UNKNOWN
        val change = (newest - oldest) / oldest
        return when {
            change > 0.15 -> Trend.DEGRADING
            change < -0.15 -> Trend.IMPROVING
            else -> Trend.STABLE
        }
    }

    /** Simple moving average, used to draw the smoothed overlay on the latency chart. */
    fun movingAverage(samples: List<Double?>, window: Int = 5): List<Double?> {
        if (samples.isEmpty() || window <= 1) return samples
        val output = ArrayList<Double?>(samples.size)
        val buffer = ArrayDeque<Double>(window)
        for (sample in samples) {
            if (sample == null) {
                output += null
                continue
            }
            if (buffer.size == window) buffer.removeFirst()
            buffer.addLast(sample)
            output += buffer.average()
        }
        return output
    }

    /** Availability across a window expressed as a percentage with one decimal. */
    fun availability(sent: Int, received: Int): Double =
        if (sent <= 0) 0.0 else (received * 100.0 / sent)

    /** Merges two stat blocks (used when combining per-host windows into a global view). */
    fun merge(first: LatencyStats, second: LatencyStats): LatencyStats {
        if (first.sent == 0) return second
        if (second.sent == 0) return first
        val sent = first.sent + second.sent
        val received = first.received + second.received
        val weightedAvg = listOfNotNull(
            first.avgMs?.let { it * first.received },
            second.avgMs?.let { it * second.received },
        ).sum().takeIf { received > 0 }?.div(received)

        return LatencyStats(
            sent = sent,
            received = received,
            minMs = listOfNotNull(first.minMs, second.minMs).minOrNull(),
            maxMs = listOfNotNull(first.maxMs, second.maxMs).maxOrNull(),
            avgMs = weightedAvg,
            medianMs = listOfNotNull(first.medianMs, second.medianMs).average().takeIf { !it.isNaN() },
            p95Ms = listOfNotNull(first.p95Ms, second.p95Ms).maxOrNull(),
            p99Ms = listOfNotNull(first.p99Ms, second.p99Ms).maxOrNull(),
            stdDevMs = listOfNotNull(first.stdDevMs, second.stdDevMs).average().takeIf { !it.isNaN() },
            rfc3550JitterMs = listOfNotNull(first.rfc3550JitterMs, second.rfc3550JitterMs)
                .average().takeIf { !it.isNaN() },
            consecutiveFailures = second.consecutiveFailures,
            longestOutage = maxOf(first.longestOutage, second.longestOutage),
            firstTimestampMs = first.firstTimestampMs ?: second.firstTimestampMs,
            lastTimestampMs = second.lastTimestampMs ?: first.lastTimestampMs,
        )
    }
}
