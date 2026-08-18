package live.nikro.pinglab.domain.stats

import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus

/**
 * One continuous stretch of failed probes for a single host.
 *
 * [startMs] is the timestamp of the first failing probe and [endMs] is the timestamp of the probe
 * that recovered the host. When the host never recovered inside the analysed window the outage is
 * [ongoing] and [endMs] is extrapolated one probe interval past the last failure, which is the most
 * we can honestly claim to have observed.
 */
data class Outage(
    val startMs: Long,
    val endMs: Long,
    val probeCount: Int,
    val reason: ProbeStatus,
    val ongoing: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Availability of a single host over the analysed samples.
 *
 * Two different numbers on purpose:
 * - [probeSuccessPercent] counts probes, which is what a ping tool traditionally reports.
 * - [availabilityPercent] is time-weighted, so a five minute outage looks the same no matter how
 *   often we happened to be polling. This is the number an SLA is written against.
 */
data class UptimeReport(
    val windowStartMs: Long = 0L,
    val windowEndMs: Long = 0L,
    val sent: Int = 0,
    val received: Int = 0,
    val skipped: Int = 0,
    val downtimeMs: Long = 0L,
    val intervalMs: Long = 0L,
    val outages: List<Outage> = emptyList(),
) {
    val hasData: Boolean get() = sent > 0

    val observedMs: Long get() = (windowEndMs - windowStartMs).coerceAtLeast(0L)

    val uptimeMs: Long get() = (observedMs - downtimeMs).coerceAtLeast(0L)

    val outageCount: Int get() = outages.size

    val ongoingOutage: Outage? get() = outages.lastOrNull()?.takeIf { it.ongoing }

    val isDownNow: Boolean get() = ongoingOutage != null

    val longestOutageMs: Long get() = outages.maxOfOrNull { it.durationMs } ?: 0L

    /** Mean time to recovery across outages that actually ended. Null while nothing recovered. */
    val meanTimeToRecoveryMs: Long?
        get() {
            val closed = outages.filter { !it.ongoing }
            if (closed.isEmpty()) return null
            return closed.sumOf { it.durationMs } / closed.size
        }

    val lost: Int get() = (sent - received).coerceAtLeast(0)

    val probeSuccessPercent: Double
        get() = if (sent == 0) 0.0 else received * 100.0 / sent

    val availabilityPercent: Double
        get() = if (observedMs <= 0L) {
            probeSuccessPercent
        } else {
            ((observedMs - downtimeMs).toDouble() * 100.0 / observedMs).coerceIn(0.0, 100.0)
        }
}

/** A single host inside a cross-host digest. */
data class HostUptime(
    val hostId: Long,
    val label: String,
    val report: UptimeReport,
)

/** Everything the dashboard needs to summarise "how did my targets behave lately". */
data class UptimeDigest(
    val windowMs: Long = 0L,
    val hosts: List<HostUptime> = emptyList(),
) {
    private val measured: List<HostUptime> get() = hosts.filter { it.report.hasData }

    val hasData: Boolean get() = measured.isNotEmpty()

    val hostCount: Int get() = measured.size

    val sent: Int get() = measured.sumOf { it.report.sent }

    val received: Int get() = measured.sumOf { it.report.received }

    /** Packet level success across every measured host, shown next to the time based figure. */
    val probeSuccessPercent: Double
        get() = if (sent == 0) 0.0 else received * 100.0 / sent

    val outageCount: Int get() = measured.sumOf { it.report.outageCount }

    val downtimeMs: Long get() = measured.sumOf { it.report.downtimeMs }

    val longestOutageMs: Long get() = measured.maxOfOrNull { it.report.longestOutageMs } ?: 0L

    /** Mean of per-host availability: one flaky host out of ten should not vanish in the average. */
    val availabilityPercent: Double
        get() {
            val list = measured
            if (list.isEmpty()) return 0.0
            return list.sumOf { it.report.availabilityPercent } / list.size
        }

    val meanTimeToRecoveryMs: Long?
        get() {
            val closed = measured.flatMap { it.report.outages }.filter { !it.ongoing }
            if (closed.isEmpty()) return null
            return closed.sumOf { it.durationMs } / closed.size
        }

    /** Host with the worst availability, i.e. the one worth looking at first. */
    val worst: HostUptime?
        get() = measured.minByOrNull { it.report.availabilityPercent }

    val downNow: List<HostUptime> get() = measured.filter { it.report.isDownNow }

    val perfect: Boolean get() = hasData && outageCount == 0
}

/**
 * Turns raw probe history into outages and availability.
 *
 * Environmental failures (no network on *our* side, missing permission) are dropped instead of
 * being blamed on the target: a phone in a lift is not an outage of example.com. They are still
 * reported through [UptimeReport.skipped] so the UI can be honest about coverage.
 */
object UptimeAnalyzer {

    const val DEFAULT_INTERVAL_MS = 15_000L
    private const val MIN_INTERVAL_MS = 500L
    private const val MAX_INTERVAL_MS = 10L * 60L * 1_000L

    fun analyze(results: List<ProbeResult>, minConsecutiveFailures: Int = 1): UptimeReport {
        if (results.isEmpty()) return UptimeReport()

        val skipped = results.count { it.status.isEnvironmental }
        val timeline = results
            .filterNot { it.status.isEnvironmental }
            .sortedBy { it.timestampMs }
        if (timeline.isEmpty()) return UptimeReport(skipped = skipped)

        val interval = estimateIntervalMs(timeline)
        val minRun = minConsecutiveFailures.coerceAtLeast(1)
        val outages = ArrayList<Outage>()

        var runStartMs = 0L
        var runLastMs = 0L
        var runCount = 0
        var runReason = ProbeStatus.ERROR

        fun closeRun(recoveredAtMs: Long?) {
            if (runCount >= minRun) {
                outages += Outage(
                    startMs = runStartMs,
                    endMs = recoveredAtMs ?: (runLastMs + interval),
                    probeCount = runCount,
                    reason = runReason,
                    ongoing = recoveredAtMs == null,
                )
            }
            runCount = 0
        }

        timeline.forEach { probe ->
            if (probe.status.isFailure) {
                if (runCount == 0) {
                    runStartMs = probe.timestampMs
                    runReason = probe.status
                }
                runLastMs = probe.timestampMs
                runCount++
            } else if (runCount > 0) {
                closeRun(probe.timestampMs)
            }
        }
        if (runCount > 0) closeRun(null)

        return UptimeReport(
            windowStartMs = timeline.first().timestampMs,
            windowEndMs = timeline.last().timestampMs,
            sent = timeline.size,
            received = timeline.count { it.isSuccess },
            skipped = skipped,
            downtimeMs = outages.sumOf { it.durationMs },
            intervalMs = interval,
            outages = outages,
        )
    }

    /**
     * Median gap between probes. Median rather than mean because monitoring is routinely paused,
     * and a single multi-hour gap would otherwise inflate every outage in the window.
     */
    private fun estimateIntervalMs(timeline: List<ProbeResult>): Long {
        if (timeline.size < 2) return DEFAULT_INTERVAL_MS
        val deltas = ArrayList<Long>(timeline.size - 1)
        for (index in 1 until timeline.size) {
            val delta = timeline[index].timestampMs - timeline[index - 1].timestampMs
            if (delta > 0L) deltas += delta
        }
        if (deltas.isEmpty()) return DEFAULT_INTERVAL_MS
        deltas.sort()
        val middle = deltas.size / 2
        val median = if (deltas.size % 2 == 1) {
            deltas[middle]
        } else {
            (deltas[middle - 1] + deltas[middle]) / 2L
        }
        return median.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }
}
