package live.nikro.pinglab.domain.quality

/**
 * ITU-T G.107 "E-model", simplified for the numbers a ping client can actually observe.
 *
 * The E-model produces a transmission rating factor **R** (0..100) which is then mapped to a
 * **MOS** (Mean Opinion Score, 1..5) \u2014 the same 1-to-5 scale used in subjective call-quality
 * surveys. It is the industry-standard way to turn latency/jitter/loss into a single number
 * a human can reason about.
 *
 * The classic Cisco simplification is used:
 *
 * ```
 * effectiveLatency = oneWayDelay + 2 * jitter + 10   (10 ms = codec/packetisation delay)
 * R = 93.2 - effectiveLatency / 40                    (for effectiveLatency < 160 ms)
 * R = 93.2 - (effectiveLatency - 120) / 10            (above that the penalty steepens)
 * R = R - 2.5 * packetLossPercent                     (G.711 without PLC)
 * MOS = 1 + 0.035 R + R (R - 60)(100 - R) * 7.10e-6
 * ```
 *
 * Round-trip time is halved to approximate one-way delay.
 */
object MosCalculator {

    const val MAX_MOS = 4.41 // the ceiling of the formula for a perfect G.711 link
    const val MIN_MOS = 1.0

    data class Rating(val rFactor: Double, val mos: Double)

    fun rate(avgRttMs: Double, jitterMs: Double, lossPercent: Double): Rating {
        val effectiveLatency = (avgRttMs / 2.0) + (jitterMs * 2.0) + CODEC_DELAY_MS

        var r = if (effectiveLatency < DELAY_KNEE_MS) {
            93.2 - (effectiveLatency / 40.0)
        } else {
            93.2 - ((effectiveLatency - 120.0) / 10.0)
        }

        r -= lossPercent.coerceIn(0.0, 100.0) * LOSS_PENALTY
        r = r.coerceIn(0.0, 100.0)

        val mos = if (r < 1.0) {
            MIN_MOS
        } else {
            (1.0 + (0.035 * r) + (r * (r - 60.0) * (100.0 - r) * 7.10e-6))
                .coerceIn(MIN_MOS, MAX_MOS)
        }

        return Rating(rFactor = r, mos = mos)
    }

    /** Plain-language interpretation of a MOS value, as used by telecom operators. */
    fun describeMos(mos: Double): String = when {
        mos >= 4.3 -> "Best possible \u2014 indistinguishable from a local call"
        mos >= 4.0 -> "High \u2014 most users are satisfied"
        mos >= 3.6 -> "Medium \u2014 some users are dissatisfied"
        mos >= 3.1 -> "Low \u2014 many users are dissatisfied"
        mos >= 2.6 -> "Poor \u2014 nearly all users are dissatisfied"
        else -> "Unusable for real-time voice"
    }

    private const val CODEC_DELAY_MS = 10.0
    private const val DELAY_KNEE_MS = 160.0
    private const val LOSS_PENALTY = 2.5
}
