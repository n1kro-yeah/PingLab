package live.nikro.pinglab

import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.domain.stats.LatencyStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyStatisticsTest {

    private fun sample(
        seq: Int,
        rtt: Double?,
        status: ProbeStatus = if (rtt != null) ProbeStatus.SUCCESS else ProbeStatus.TIMEOUT,
    ): ProbeResult = ProbeResult(
        sequence = seq,
        timestampMs = 1_700_000_000_000L + seq * 1_000L,
        protocol = Protocol.ICMP,
        status = status,
        rttMs = rtt,
        hostname = "example.com",
    )

    @Test
    fun `empty input produces empty stats`() {
        val stats = LatencyStatistics.compute(emptyList())
        assertEquals(0, stats.sent)
        assertEquals(0, stats.received)
        assertNull(stats.avgMs)
        assertTrue(!stats.hasData)
    }

    @Test
    fun `basic aggregates match the sample set`() {
        val results = listOf(
            sample(1, 10.0),
            sample(2, 20.0),
            sample(3, 30.0),
            sample(4, 40.0),
        )
        val stats = LatencyStatistics.compute(results)

        assertEquals(4, stats.sent)
        assertEquals(4, stats.received)
        assertEquals(10.0, stats.minMs!!, 0.001)
        assertEquals(40.0, stats.maxMs!!, 0.001)
        assertEquals(25.0, stats.avgMs!!, 0.001)
        assertEquals(0.0, stats.lossPercent, 0.001)
        assertEquals(100.0, stats.uptimePercent, 0.001)
    }

    @Test
    fun `packet loss is counted from failures`() {
        val results = listOf(
            sample(1, 10.0),
            sample(2, null),
            sample(3, 30.0),
            sample(4, null),
        )
        val stats = LatencyStatistics.compute(results)

        assertEquals(4, stats.sent)
        assertEquals(2, stats.received)
        assertEquals(2, stats.lost)
        assertEquals(50.0, stats.lossPercent, 0.001)
    }

    @Test
    fun `median sits between min and max`() {
        val results = (1..9).map { sample(it, it * 10.0) }
        val stats = LatencyStatistics.compute(results)
        val median = stats.medianMs!!

        assertTrue(median >= stats.minMs!!)
        assertTrue(median <= stats.maxMs!!)
        assertEquals(50.0, median, 0.001)
    }

    @Test
    fun `percentiles are ordered`() {
        val samples = (1..100).map { it.toDouble() }
        val p50 = LatencyStatistics.percentile(samples, 50.0)!!
        val p90 = LatencyStatistics.percentile(samples, 90.0)!!
        val p99 = LatencyStatistics.percentile(samples, 99.0)!!

        assertTrue(p50 <= p90)
        assertTrue(p90 <= p99)
        assertTrue(p99 <= 100.0)
    }

    @Test
    fun `standard deviation is zero for a flat series`() {
        val samples = List(10) { 25.0 }
        assertEquals(0.0, LatencyStatistics.standardDeviation(samples)!!, 0.0001)
    }

    @Test
    fun `rfc3550 jitter grows with variance`() {
        val steady = LatencyStatistics.rfc3550Jitter(List(20) { 30.0 })!!
        val noisy = LatencyStatistics.rfc3550Jitter(List(20) { if (it % 2 == 0) 10.0 else 90.0 })!!

        assertTrue(steady < noisy)
        assertEquals(0.0, steady, 0.0001)
    }

    @Test
    fun `trailing failures counts only the tail`() {
        val results = listOf(
            sample(1, null),
            sample(2, 10.0),
            sample(3, null),
            sample(4, null),
        )
        assertEquals(2, LatencyStatistics.trailingFailures(results))
    }

    @Test
    fun `longest failure run finds the worst outage`() {
        val results = listOf(
            sample(1, null),
            sample(2, null),
            sample(3, null),
            sample(4, 12.0),
            sample(5, null),
        )
        assertEquals(3, LatencyStatistics.longestFailureRun(results))
    }

    @Test
    fun `histogram keeps every sample`() {
        val samples = (1..60).map { it.toDouble() }
        val buckets = LatencyStatistics.histogram(samples, bucketCount = 6)

        assertTrue(buckets.isNotEmpty())
        assertEquals(samples.size, buckets.sumOf { it.count })
        buckets.forEach { assertTrue(it.toMs >= it.fromMs) }
    }

    @Test
    fun `trend detects degradation and improvement`() {
        val rising = (1..30).map { 10.0 + it * 3 }
        val falling = rising.reversed()

        assertEquals(LatencyStatistics.Trend.DEGRADING, LatencyStatistics.trend(rising))
        assertEquals(LatencyStatistics.Trend.IMPROVING, LatencyStatistics.trend(falling))
        assertEquals(LatencyStatistics.Trend.UNKNOWN, LatencyStatistics.trend(listOf(1.0, 2.0)))
    }

    @Test
    fun `moving average smooths spikes and keeps length`() {
        val samples = listOf<Double?>(10.0, 10.0, 200.0, 10.0, 10.0, 10.0)
        val smoothed = LatencyStatistics.movingAverage(samples, window = 3)

        assertEquals(samples.size, smoothed.size)
        val peak = smoothed.filterNotNull().max()
        assertTrue(peak < 200.0)
    }

    @Test
    fun `availability is a percentage`() {
        assertEquals(100.0, LatencyStatistics.availability(10, 10), 0.001)
        assertEquals(50.0, LatencyStatistics.availability(10, 5), 0.001)
        assertEquals(0.0, LatencyStatistics.availability(0, 0), 0.001)
    }

    @Test
    fun `merge adds counters from both windows`() {
        val first = LatencyStatistics.compute(listOf(sample(1, 10.0), sample(2, 20.0)))
        val second = LatencyStatistics.compute(listOf(sample(3, 30.0), sample(4, null)))
        val merged = LatencyStatistics.merge(first, second)

        assertEquals(4, merged.sent)
        assertEquals(3, merged.received)
        assertNotNull(merged.avgMs)
        assertTrue(merged.maxMs!! >= 30.0)
    }

    @Test
    fun `mos is estimated for a healthy link`() {
        val results = (1..30).map { sample(it, 22.0 + (it % 3)) }
        val stats = LatencyStatistics.compute(results)

        assertNotNull(stats.mos)
        assertTrue(stats.mos!! > 3.5)
        assertTrue(stats.mos!! <= 4.5)
    }
}
