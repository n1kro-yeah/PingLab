package live.nikro.pinglab

import live.nikro.pinglab.core.model.LatencyStats
import live.nikro.pinglab.core.model.QualityGrade
import live.nikro.pinglab.domain.quality.QualityEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityEvaluatorTest {

    private fun stats(
        sent: Int,
        received: Int,
        avg: Double?,
        jitter: Double?,
    ): LatencyStats = LatencyStats(
        sent = sent,
        received = received,
        minMs = avg?.let { it * 0.8 },
        maxMs = avg?.let { it * 1.4 },
        avgMs = avg,
        medianMs = avg,
        p90Ms = avg?.let { it * 1.2 },
        p95Ms = avg?.let { it * 1.3 },
        p99Ms = avg?.let { it * 1.4 },
        stdDevMs = jitter,
        meanDeviationJitterMs = jitter,
        rfc3550JitterMs = jitter,
    )

    @Test
    fun `no samples yields the unknown assessment`() {
        val assessment = QualityEvaluator.evaluate(LatencyStats.EMPTY)
        assertEquals(QualityGrade.UNKNOWN, assessment.grade)
        assertEquals(0, assessment.score)
    }

    @Test
    fun `total loss is the worst possible result`() {
        val assessment = QualityEvaluator.evaluate(stats(sent = 20, received = 0, avg = null, jitter = null))
        assertEquals(QualityGrade.BAD, assessment.grade)
        assertEquals(0, assessment.score)
        assertTrue(assessment.headline.isNotBlank())
    }

    @Test
    fun `a fast stable link scores high`() {
        val assessment = QualityEvaluator.evaluate(stats(sent = 100, received = 100, avg = 12.0, jitter = 1.5))
        assertTrue(assessment.score >= 80)
        assertNotEquals(QualityGrade.UNKNOWN, assessment.grade)
    }

    @Test
    fun `latency and loss push the score down`() {
        val good = QualityEvaluator.evaluate(stats(sent = 100, received = 100, avg = 15.0, jitter = 2.0))
        val slow = QualityEvaluator.evaluate(stats(sent = 100, received = 100, avg = 320.0, jitter = 45.0))
        val lossy = QualityEvaluator.evaluate(stats(sent = 100, received = 70, avg = 15.0, jitter = 2.0))

        assertTrue(slow.score < good.score)
        assertTrue(lossy.score < good.score)
    }

    @Test
    fun `score always stays inside 0 to 100`() {
        val cases = listOf(
            stats(sent = 10, received = 10, avg = 1.0, jitter = 0.0),
            stats(sent = 10, received = 1, avg = 900.0, jitter = 400.0),
            stats(sent = 10, received = 9, avg = 60.0, jitter = 12.0),
        )
        cases.forEach { candidate ->
            val score = QualityEvaluator.evaluate(candidate).score
            assertTrue(score in 0..100)
        }
    }

    @Test
    fun `use case ratings cover every use case`() {
        val assessment = QualityEvaluator.evaluate(stats(sent = 50, received = 49, avg = 28.0, jitter = 4.0))
        assertTrue(assessment.useCases.isNotEmpty())
        assessment.useCases.forEach { rating ->
            assertTrue(rating.reason.isNotBlank())
            assertNotEquals(QualityGrade.UNKNOWN, rating.grade)
        }
    }

    @Test
    fun `gaming is graded worse than browsing on a jittery link`() {
        val ratings = QualityEvaluator.rateUseCases(latencyMs = 140.0, jitterMs = 60.0, lossPercent = 4.0)
        val gaming = ratings.first { it.useCase.name == "GAMING" }
        val browsing = ratings.first { it.useCase.name == "BROWSING" }

        assertTrue(gaming.grade.ordinal >= browsing.grade.ordinal)
    }
}
