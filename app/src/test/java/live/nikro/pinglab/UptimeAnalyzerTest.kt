package live.nikro.pinglab

import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.domain.stats.HostUptime
import live.nikro.pinglab.domain.stats.UptimeAnalyzer
import live.nikro.pinglab.domain.stats.UptimeDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Availability is the number people quote in incident reports, so the arithmetic behind it is
 * pinned down here: where an outage starts, when it is considered over, and what a phone level
 * network failure must not do to the result.
 */
class UptimeAnalyzerTest {

    private val start = 1_700_000_000_000L
    private val step = 15_000L

    private fun sample(
        seq: Int,
        rtt: Double?,
        status: ProbeStatus = if (rtt != null) ProbeStatus.SUCCESS else ProbeStatus.TIMEOUT,
    ): ProbeResult = ProbeResult(
        sequence = seq,
        timestampMs = start + seq * step,
        protocol = Protocol.ICMP,
        status = status,
        rttMs = rtt,
        hostname = "example.com",
    )

    @Test
    fun `empty input has no data`() {
        val report = UptimeAnalyzer.analyze(emptyList())
        assertFalse(report.hasData)
        assertEquals(0, report.sent)
        assertEquals(0, report.outageCount)
        assertEquals(0L, report.downtimeMs)
        assertNull(report.meanTimeToRecoveryMs)
    }

    @Test
    fun `a clean window is one hundred percent available`() {
        val results = (0..9).map { sample(it, 20.0) }
        val report = UptimeAnalyzer.analyze(results)

        assertTrue(report.hasData)
        assertEquals(10, report.sent)
        assertEquals(10, report.received)
        assertEquals(0, report.outageCount)
        assertEquals(100.0, report.availabilityPercent, 0.001)
        assertEquals(100.0, report.probeSuccessPercent, 0.001)
        assertFalse(report.isDownNow)
    }

    @Test
    fun `a closed outage is measured from first failure to recovery`() {
        val results = (0..9).map { seq ->
            if (seq == 3 || seq == 4) sample(seq, null) else sample(seq, 20.0)
        }
        val report = UptimeAnalyzer.analyze(results)

        assertEquals(1, report.outageCount)
        val outage = report.outages.single()
        assertFalse(outage.ongoing)
        assertEquals(2, outage.probeCount)
        assertEquals(ProbeStatus.TIMEOUT, outage.reason)
        // Failures at 45s and 60s, first success again at 75s.
        assertEquals(30_000L, outage.durationMs)
        assertEquals(30_000L, report.downtimeMs)
        assertEquals(30_000L, report.longestOutageMs)
        assertEquals(30_000L, report.meanTimeToRecoveryMs)
        // Window spans 135s, of which 30s were down.
        assertEquals(77.777, report.availabilityPercent, 0.01)
        assertEquals(80.0, report.probeSuccessPercent, 0.001)
        assertEquals(2, report.lost)
    }

    @Test
    fun `an outage that never recovered is still open`() {
        val results = (0..6).map { seq ->
            if (seq >= 5) sample(seq, null) else sample(seq, 20.0)
        }
        val report = UptimeAnalyzer.analyze(results)

        assertTrue(report.isDownNow)
        assertNotNull(report.ongoingOutage)
        assertTrue(report.outages.single().ongoing)
        // Two failed probes, extended by one interval because the target is still silent.
        assertEquals(30_000L, report.outages.single().durationMs)
        assertNull(report.meanTimeToRecoveryMs)
        assertEquals(66.666, report.availabilityPercent, 0.01)
    }

    @Test
    fun `phone side failures do not count as target downtime`() {
        val results = listOf(
            sample(0, 20.0),
            sample(1, null, ProbeStatus.NETWORK_UNAVAILABLE),
            sample(2, null, ProbeStatus.PERMISSION_DENIED),
            sample(3, 20.0),
        )
        val report = UptimeAnalyzer.analyze(results)

        assertEquals(2, report.skipped)
        assertEquals(2, report.sent)
        assertEquals(2, report.received)
        assertEquals(0, report.outageCount)
        assertEquals(100.0, report.availabilityPercent, 0.001)
    }

    @Test
    fun `a single lost probe can be ignored`() {
        val results = (0..4).map { seq -> if (seq == 2) sample(seq, null) else sample(seq, 20.0) }

        val strict = UptimeAnalyzer.analyze(results, minConsecutiveFailures = 2)
        assertEquals(0, strict.outageCount)
        assertEquals(0L, strict.downtimeMs)
        assertEquals(100.0, strict.availabilityPercent, 0.001)
        assertEquals(80.0, strict.probeSuccessPercent, 0.001)

        val sensitive = UptimeAnalyzer.analyze(results)
        assertEquals(1, sensitive.outageCount)
        assertEquals(15_000L, sensitive.downtimeMs)
    }

    @Test
    fun `mean time to recovery averages closed outages`() {
        val failures = setOf(2, 5, 6)
        val results = (0..11).map { seq ->
            if (seq in failures) sample(seq, null) else sample(seq, 20.0)
        }
        val report = UptimeAnalyzer.analyze(results)

        assertEquals(2, report.outageCount)
        assertEquals(45_000L, report.downtimeMs)
        assertEquals(30_000L, report.longestOutageMs)
        assertEquals(22_500L, report.meanTimeToRecoveryMs)
        assertEquals(72.727, report.availabilityPercent, 0.01)
    }

    @Test
    fun `digest averages hosts and points at the worst one`() {
        val clean = UptimeAnalyzer.analyze((0..9).map { sample(it, 20.0) })
        val flaky = UptimeAnalyzer.analyze(
            (0..9).map { seq -> if (seq == 3 || seq == 4) sample(seq, null) else sample(seq, 20.0) },
        )
        val silent = UptimeAnalyzer.analyze(emptyList())

        val digest = UptimeDigest(
            windowMs = 24L * 60L * 60L * 1_000L,
            hosts = listOf(
                HostUptime(hostId = 1L, label = "router", report = clean),
                HostUptime(hostId = 2L, label = "vpn", report = flaky),
                HostUptime(hostId = 3L, label = "never probed", report = silent),
            ),
        )

        assertTrue(digest.hasData)
        // Hosts without a single probe must not drag the average down.
        assertEquals(2, digest.hostCount)
        assertEquals(20, digest.sent)
        assertEquals(1, digest.outageCount)
        assertEquals(30_000L, digest.downtimeMs)
        assertEquals(30_000L, digest.longestOutageMs)
        assertEquals(30_000L, digest.meanTimeToRecoveryMs)
        assertEquals(88.888, digest.availabilityPercent, 0.01)
        assertEquals("vpn", digest.worst?.label)
        assertTrue(digest.downNow.isEmpty())
        assertFalse(digest.perfect)
    }
}
