package live.nikro.pinglab.domain.monitor

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import live.nikro.pinglab.core.model.ProbeRequest
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.core.net.PingEngineFactory

/**
 * A single interactive ping run \u2014 the engine behind the "Live" screen.
 *
 * Two details that matter in practice:
 *
 * **Drift correction.** A naive `probe(); delay(interval)` loop drifts, because the probe
 * itself takes time. Over a 10-minute run at 1 s intervals that can cost dozens of packets.
 * We therefore schedule against an absolute timeline anchored at the start.
 *
 * **Back-pressure safety.** If a probe overruns its slot (say a 5 s timeout on a 1 s interval)
 * we skip the missed slots instead of firing a burst to catch up, which would look like a
 * DoS to the target.
 */
class LivePingSession(
    private val engines: PingEngineFactory,
) {

    data class Config(
        val target: String,
        val protocol: Protocol = Protocol.ICMP,
        val port: Int? = null,
        val intervalMs: Long = 1_000L,
        val timeoutMs: Int = 2_000,
        val payloadSize: Int = 32,
        val ttl: Int = 64,
        val count: Int? = null,
        val preferIpv6: Boolean = false,
        val httpPath: String = "/",
    ) {
        val isContinuous: Boolean get() = count == null
    }

    /**
     * Cold flow of results. Cancelling the collector stops the run; nothing else is needed.
     */
    fun stream(config: Config): Flow<ProbeResult> = flow {
        val startNs = System.nanoTime()
        var sequence = 1

        while (currentCoroutineContext().isActive) {
            val request = ProbeRequest(
                target = config.target,
                protocol = config.protocol,
                port = config.port,
                timeoutMs = config.timeoutMs,
                payloadSize = config.payloadSize,
                ttl = config.ttl,
                sequence = sequence,
                preferIpv6 = config.preferIpv6,
                httpPath = config.httpPath,
            )

            val result = engines.probe(request)
            emit(result)

            val limit = config.count
            if (limit != null && sequence >= limit) break

            sequence++
            val nextSlotNs = startNs + (sequence - 1).toLong() * config.intervalMs * 1_000_000L
            var waitMs = (nextSlotNs - System.nanoTime()) / 1_000_000L

            if (waitMs < 0) {
                // We overran. Jump forward to the next slot in the future rather than
                // firing a catch-up burst at the target.
                val missedSlots = (-waitMs / config.intervalMs) + 1
                sequence += missedSlots.toInt()
                val adjustedNs = startNs + (sequence - 1).toLong() * config.intervalMs * 1_000_000L
                waitMs = ((adjustedNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            }
            if (waitMs > 0) delay(waitMs)
        }
    }

    /** Runs a bounded burst and returns everything at once (used by the tools screen). */
    suspend fun burst(config: Config, count: Int): List<ProbeResult> {
        val collected = ArrayList<ProbeResult>(count)
        stream(config.copy(count = count)).collect { collected += it }
        return collected
    }
}
