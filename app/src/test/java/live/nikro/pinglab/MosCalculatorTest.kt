package live.nikro.pinglab

import live.nikro.pinglab.domain.quality.MosCalculator
import org.junit.Assert.assertTrue
import org.junit.Test

class MosCalculatorTest {

    @Test
    fun `perfect link approaches the codec ceiling`() {
        val rating = MosCalculator.rate(avgRttMs = 5.0, jitterMs = 0.5, lossPercent = 0.0)
        assertTrue(rating.mos > 4.3)
        assertTrue(rating.mos <= 4.41)
        assertTrue(rating.rFactor > 90.0)
    }

    @Test
    fun `mos never drops below one`() {
        val rating = MosCalculator.rate(avgRttMs = 2_000.0, jitterMs = 800.0, lossPercent = 90.0)
        assertTrue(rating.mos >= 1.0)
    }

    @Test
    fun `loss degrades the rating faster than latency`() {
        val latencyHit = MosCalculator.rate(avgRttMs = 200.0, jitterMs = 5.0, lossPercent = 0.0)
        val lossHit = MosCalculator.rate(avgRttMs = 20.0, jitterMs = 5.0, lossPercent = 10.0)
        assertTrue(lossHit.mos < latencyHit.mos)
    }

    @Test
    fun `jitter reduces the score`() {
        val calm = MosCalculator.rate(avgRttMs = 40.0, jitterMs = 1.0, lossPercent = 0.0)
        val jittery = MosCalculator.rate(avgRttMs = 40.0, jitterMs = 60.0, lossPercent = 0.0)
        assertTrue(jittery.mos < calm.mos)
    }

    @Test
    fun `rating is monotonic in latency`() {
        var previous = Double.MAX_VALUE
        listOf(10.0, 50.0, 120.0, 250.0, 400.0).forEach { latency ->
            val mos = MosCalculator.rate(latency, 5.0, 0.0).mos
            assertTrue(mos <= previous + 0.0001)
            previous = mos
        }
    }

    @Test
    fun `every mos value has a description`() {
        listOf(4.4, 4.0, 3.6, 3.1, 2.5, 1.0).forEach { mos ->
            assertTrue(MosCalculator.describeMos(mos).isNotBlank())
        }
    }
}
