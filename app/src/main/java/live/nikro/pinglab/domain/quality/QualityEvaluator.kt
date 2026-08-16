package live.nikro.pinglab.domain.quality

import live.nikro.pinglab.core.model.LatencyStats
import live.nikro.pinglab.core.model.QualityAssessment
import live.nikro.pinglab.core.model.QualityGrade
import live.nikro.pinglab.core.model.UseCase
import live.nikro.pinglab.core.model.UseCaseRating
import kotlin.math.roundToInt

/**
 * Turns raw statistics into a verdict a person can act on.
 *
 * The overall score is a weighted blend rather than a single threshold, because a link with
 * 20 ms latency and 8% loss is *worse* than one with 90 ms latency and 0% loss \u2014 something a
 * naive "latency < 50 ms = good" rule gets backwards.
 *
 * Weights: packet loss 45%, latency 30%, jitter 25%. Loss dominates because a lost packet is
 * unrecoverable, whereas latency merely delays.
 */
object QualityEvaluator {

    fun evaluate(stats: LatencyStats): QualityAssessment {
        if (stats.sent == 0) return QualityAssessment.UNKNOWN

        if (stats.received == 0) {
            return QualityAssessment(
                grade = QualityGrade.BAD,
                score = 0,
                headline = "Host is not responding",
                details = listOf(
                    "${stats.sent} probes sent, none answered",
                    "Longest outage: ${stats.longestOutage} consecutive probes",
                ),
                useCases = UseCase.entries.map {
                    UseCaseRating(it, QualityGrade.BAD, "No connectivity to the target")
                },
            )
        }

        val latency = stats.avgMs ?: 0.0
        val jitter = stats.rfc3550JitterMs ?: stats.meanDeviationJitterMs ?: 0.0
        val loss = stats.lossPercent

        val lossScore = scoreLoss(loss)
        val latencyScore = scoreLatency(latency)
        val jitterScore = scoreJitter(jitter)
        val score = (lossScore * 0.45 + latencyScore * 0.30 + jitterScore * 0.25).roundToInt()

        val grade = gradeForScore(score)
        return QualityAssessment(
            grade = grade,
            score = score,
            headline = headlineFor(grade, latency, loss),
            details = buildDetails(stats, latency, jitter, loss),
            useCases = rateUseCases(latency, jitter, loss),
        )
    }

    /** 0% loss = 100 points; the curve is deliberately brutal above 2%. */
    fun scoreLoss(lossPercent: Double): Double = when {
        lossPercent <= 0.0 -> 100.0
        lossPercent < 0.5 -> 95.0 - lossPercent * 10.0
        lossPercent < 2.0 -> 90.0 - (lossPercent - 0.5) * 20.0
        lossPercent < 5.0 -> 60.0 - (lossPercent - 2.0) * 10.0
        lossPercent < 10.0 -> 30.0 - (lossPercent - 5.0) * 4.0
        else -> (10.0 - (lossPercent - 10.0) * 0.5).coerceAtLeast(0.0)
    }

    fun scoreLatency(latencyMs: Double): Double = when {
        latencyMs <= 20.0 -> 100.0
        latencyMs <= 50.0 -> 95.0 - (latencyMs - 20.0) * 0.33
        latencyMs <= 100.0 -> 85.0 - (latencyMs - 50.0) * 0.5
        latencyMs <= 200.0 -> 60.0 - (latencyMs - 100.0) * 0.25
        latencyMs <= 400.0 -> 35.0 - (latencyMs - 200.0) * 0.1
        else -> (15.0 - (latencyMs - 400.0) * 0.02).coerceAtLeast(0.0)
    }

    fun scoreJitter(jitterMs: Double): Double = when {
        jitterMs <= 2.0 -> 100.0
        jitterMs <= 10.0 -> 95.0 - (jitterMs - 2.0) * 2.0
        jitterMs <= 30.0 -> 79.0 - (jitterMs - 10.0) * 1.5
        jitterMs <= 60.0 -> 49.0 - (jitterMs - 30.0) * 1.0
        else -> (19.0 - (jitterMs - 60.0) * 0.2).coerceAtLeast(0.0)
    }

    fun gradeForScore(score: Int): QualityGrade = when {
        score >= 90 -> QualityGrade.EXCELLENT
        score >= 75 -> QualityGrade.GOOD
        score >= 55 -> QualityGrade.FAIR
        score >= 35 -> QualityGrade.POOR
        else -> QualityGrade.BAD
    }

    private fun headlineFor(grade: QualityGrade, latencyMs: Double, lossPercent: Double): String =
        when (grade) {
            QualityGrade.EXCELLENT -> "Excellent connection"
            QualityGrade.GOOD -> "Good connection"
            QualityGrade.FAIR -> if (lossPercent > 1.0) "Usable, but packets are being lost" else "Usable, latency is elevated"
            QualityGrade.POOR -> if (lossPercent > 3.0) "Unstable \u2014 significant packet loss" else "Slow \u2014 high latency"
            QualityGrade.BAD -> if (lossPercent > 10.0) "Severe packet loss" else "Unusable latency (${latencyMs.roundToInt()} ms)"
            QualityGrade.UNKNOWN -> "Not enough data"
        }

    private fun buildDetails(
        stats: LatencyStats,
        latencyMs: Double,
        jitterMs: Double,
        lossPercent: Double,
    ): List<String> = buildList {
        add("Average ${format(latencyMs)} ms over ${stats.received} of ${stats.sent} replies")
        stats.p95Ms?.let { add("95% of probes returned within ${format(it)} ms") }
        add("Jitter ${format(jitterMs)} ms (RFC 3550)")
        if (lossPercent > 0.0) {
            add("Packet loss ${format(lossPercent)}%")
        } else {
            add("No packet loss observed")
        }
        stats.mos?.let { add("MOS ${String.format("%.2f", it)} \u2014 ${MosCalculator.describeMos(it)}") }
        if (stats.longestOutage > 1) {
            add("Longest gap: ${stats.longestOutage} consecutive probes lost")
        }
        stats.minMs?.let { min ->
            stats.maxMs?.let { max ->
                add("Spread ${format(min)}\u2013${format(max)} ms")
            }
        }
    }

    /**
     * Per-activity verdicts. Thresholds come from published requirements:
     * competitive gaming wants <50 ms and near-zero jitter, VoIP tolerates ~150 ms one-way
     * (ITU-T G.114), while streaming only really cares about sustained loss.
     */
    fun rateUseCases(latencyMs: Double, jitterMs: Double, lossPercent: Double): List<UseCaseRating> =
        listOf(
            rateGaming(latencyMs, jitterMs, lossPercent),
            rateVoice(latencyMs, jitterMs, lossPercent),
            rateVideo(latencyMs, jitterMs, lossPercent),
            rateStreaming(latencyMs, jitterMs, lossPercent),
            rateBrowsing(latencyMs, jitterMs, lossPercent),
        )

    private fun rateGaming(latency: Double, jitter: Double, loss: Double): UseCaseRating {
        val grade = when {
            loss > 3.0 -> QualityGrade.BAD
            latency <= 30.0 && jitter <= 5.0 && loss < 0.5 -> QualityGrade.EXCELLENT
            latency <= 60.0 && jitter <= 12.0 && loss < 1.0 -> QualityGrade.GOOD
            latency <= 100.0 && jitter <= 25.0 && loss < 2.0 -> QualityGrade.FAIR
            latency <= 180.0 -> QualityGrade.POOR
            else -> QualityGrade.BAD
        }
        val reason = when (grade) {
            QualityGrade.EXCELLENT -> "Competitive-ready: ${format(latency)} ms, jitter ${format(jitter)} ms"
            QualityGrade.GOOD -> "Fine for most online games"
            QualityGrade.FAIR -> "Playable, expect occasional rubber-banding"
            QualityGrade.POOR -> "Noticeable lag in fast-paced titles"
            else -> "Not viable for real-time multiplayer"
        }
        return UseCaseRating(UseCase.GAMING, grade, reason)
    }

    private fun rateVoice(latency: Double, jitter: Double, loss: Double): UseCaseRating {
        val mos = MosCalculator.rate(latency, jitter, loss).mos
        val grade = when {
            mos >= 4.2 -> QualityGrade.EXCELLENT
            mos >= 4.0 -> QualityGrade.GOOD
            mos >= 3.6 -> QualityGrade.FAIR
            mos >= 3.1 -> QualityGrade.POOR
            else -> QualityGrade.BAD
        }
        return UseCaseRating(UseCase.VOICE, grade, "MOS ${String.format("%.2f", mos)} \u2014 ${MosCalculator.describeMos(mos)}")
    }

    private fun rateVideo(latency: Double, jitter: Double, loss: Double): UseCaseRating {
        val grade = when {
            loss > 5.0 -> QualityGrade.BAD
            latency <= 100.0 && jitter <= 20.0 && loss < 1.0 -> QualityGrade.EXCELLENT
            latency <= 200.0 && jitter <= 40.0 && loss < 2.0 -> QualityGrade.GOOD
            latency <= 300.0 && loss < 3.0 -> QualityGrade.FAIR
            else -> QualityGrade.POOR
        }
        val reason = when (grade) {
            QualityGrade.EXCELLENT -> "Smooth HD video calls"
            QualityGrade.GOOD -> "Good calls, rare frame drops"
            QualityGrade.FAIR -> "Expect resolution downgrades"
            QualityGrade.POOR -> "Frequent freezing likely"
            else -> "Video calls will not hold up"
        }
        return UseCaseRating(UseCase.VIDEO, grade, reason)
    }

    private fun rateStreaming(latency: Double, jitter: Double, loss: Double): UseCaseRating {
        val grade = when {
            loss > 8.0 -> QualityGrade.BAD
            loss < 1.0 -> QualityGrade.EXCELLENT
            loss < 2.5 -> QualityGrade.GOOD
            loss < 5.0 -> QualityGrade.FAIR
            else -> QualityGrade.POOR
        }
        val reason = when (grade) {
            QualityGrade.EXCELLENT -> "Buffering is unlikely at any bitrate"
            QualityGrade.GOOD -> "Stable playback after the initial buffer"
            QualityGrade.FAIR -> "Occasional quality drops"
            QualityGrade.POOR -> "Frequent re-buffering"
            else -> "Streaming will stall constantly"
        }
        return UseCaseRating(UseCase.STREAMING, grade, reason)
    }

    private fun rateBrowsing(latency: Double, jitter: Double, loss: Double): UseCaseRating {
        val grade = when {
            loss > 10.0 -> QualityGrade.BAD
            latency <= 80.0 && loss < 2.0 -> QualityGrade.EXCELLENT
            latency <= 200.0 && loss < 4.0 -> QualityGrade.GOOD
            latency <= 500.0 -> QualityGrade.FAIR
            latency <= 1_000.0 -> QualityGrade.POOR
            else -> QualityGrade.BAD
        }
        val reason = when (grade) {
            QualityGrade.EXCELLENT -> "Pages load instantly"
            QualityGrade.GOOD -> "Comfortable browsing"
            QualityGrade.FAIR -> "Pages feel sluggish"
            QualityGrade.POOR -> "Long waits on every request"
            else -> "Browsing is impractical"
        }
        return UseCaseRating(UseCase.BROWSING, grade, reason)
    }

    private fun format(value: Double): String = when {
        value >= 100.0 -> value.roundToInt().toString()
        value >= 10.0 -> String.format("%.1f", value)
        else -> String.format("%.2f", value)
    }
}
