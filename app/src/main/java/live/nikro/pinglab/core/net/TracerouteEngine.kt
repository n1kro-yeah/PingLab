package live.nikro.pinglab.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.TracerouteHop
import java.net.InetAddress

/**
 * Traceroute built on incrementing IP TTLs.
 *
 * For every TTL we send `queriesPerHop` echo requests. A router that decrements the TTL to
 * zero answers with ICMP *Time Exceeded* and thereby reveals itself; when the destination
 * itself answers with an echo reply the trace is complete.
 *
 * Two mechanisms are tried, in order:
 *  1. The unprivileged ICMP datagram socket with `IP_TTL` set \u2014 no process spawn, precise timing.
 *  2. `/system/bin/ping -t <ttl>` \u2014 works on kernels that do not surface ICMP errors on ping sockets.
 *
 * Results stream out hop by hop so the UI fills in while the trace is still running.
 */
class TracerouteEngine(
    private val resolver: AddressResolver,
    private val datagramPinger: IcmpDatagramPinger = IcmpDatagramPinger(),
    private val systemPing: SystemPingBinary = SystemPingBinary(),
) {

    data class Config(
        val maxHops: Int = 30,
        val queriesPerHop: Int = 3,
        val timeoutMs: Int = 1_500,
        val resolveHostnames: Boolean = true,
        val payloadSize: Int = 32,
    )

    sealed interface Event {
        data class Started(val target: String, val resolvedAddress: String) : Event
        data class Hop(val hop: TracerouteHop) : Event
        data class Finished(val reachedDestination: Boolean, val totalHops: Int) : Event
        data class Failed(val reason: String) : Event
    }

    fun trace(target: String, config: Config = Config()): Flow<Event> = flow {
        val resolution = resolver.resolve(target)
        val destination = resolution.address
        if (destination == null) {
            emit(Event.Failed(resolution.error ?: "Could not resolve $target"))
            return@flow
        }
        val destinationAddress = destination.hostAddress ?: target
        emit(Event.Started(target, destinationAddress))

        val useSocket = datagramPinger.isSupported()
        if (!useSocket && !systemPing.isAvailable) {
            emit(Event.Failed("This device exposes neither ICMP sockets nor a ping binary"))
            return@flow
        }

        var reached = false
        var hopsWalked = 0
        var consecutiveSilentHops = 0

        for (ttl in 1..config.maxHops) {
            currentCoroutineContext().ensureActive()
            hopsWalked = ttl

            val timings = ArrayList<Double?>(config.queriesPerHop)
            var responder: String? = null
            var destinationAnswered = false

            repeat(config.queriesPerHop) { attempt ->
                currentCoroutineContext().ensureActive()
                val probe = singleHopProbe(
                    destination = destination,
                    ttl = ttl,
                    sequence = ttl * 100 + attempt,
                    config = config,
                    useSocket = useSocket,
                )
                timings += probe.rttMs
                if (probe.address != null && responder == null) responder = probe.address
                if (probe.isDestination) destinationAnswered = true
            }

            val hostname = if (config.resolveHostnames && responder != null) {
                reverseLookup(responder)
            } else {
                null
            }

            emit(
                Event.Hop(
                    TracerouteHop(
                        ttl = ttl,
                        address = responder,
                        hostname = hostname,
                        rttsMs = timings,
                        isDestination = destinationAnswered || responder == destinationAddress,
                    ),
                ),
            )

            if (destinationAnswered || responder == destinationAddress) {
                reached = true
                break
            }
            consecutiveSilentHops = if (responder == null) consecutiveSilentHops + 1 else 0
            if (consecutiveSilentHops >= MAX_SILENT_HOPS) break
        }

        emit(Event.Finished(reached, hopsWalked))
    }.flowOn(Dispatchers.IO)

    private data class HopProbe(
        val address: String?,
        val rttMs: Double?,
        val isDestination: Boolean,
    )

    private suspend fun singleHopProbe(
        destination: InetAddress,
        ttl: Int,
        sequence: Int,
        config: Config,
        useSocket: Boolean,
    ): HopProbe = withContext(Dispatchers.IO) {
        if (useSocket) {
            val reply = datagramPinger.ping(
                address = destination,
                sequence = sequence,
                payloadSize = config.payloadSize,
                timeoutMs = config.timeoutMs,
                ttl = ttl,
            )
            when (reply.status) {
                ProbeStatus.SUCCESS -> return@withContext HopProbe(
                    reply.fromAddress ?: destination.hostAddress, reply.rttMs, true,
                )

                ProbeStatus.TTL_EXPIRED -> return@withContext HopProbe(reply.fromAddress, reply.rttMs, false)
                ProbeStatus.UNREACHABLE -> return@withContext HopProbe(reply.fromAddress, reply.rttMs, false)
                ProbeStatus.TIMEOUT -> {
                    // Kernels that hide ICMP errors from ping sockets look like a timeout;
                    // retry through the binary before declaring the hop silent.
                    if (!systemPing.isAvailable) return@withContext HopProbe(null, null, false)
                }

                else -> Unit
            }
        }

        if (!systemPing.isAvailable) return@withContext HopProbe(null, null, false)

        val shot = systemPing.probeHop(
            host = destination.hostAddress ?: return@withContext HopProbe(null, null, false),
            ttl = ttl,
            timeoutMs = config.timeoutMs,
            ipv6 = destination is java.net.Inet6Address,
        )
        when (shot.status) {
            ProbeStatus.SUCCESS -> HopProbe(shot.fromAddress ?: destination.hostAddress, shot.rttMs, true)
            ProbeStatus.TTL_EXPIRED -> HopProbe(shot.fromAddress, shot.rttMs, false)
            ProbeStatus.UNREACHABLE -> HopProbe(shot.fromAddress, shot.rttMs, false)
            else -> HopProbe(null, null, false)
        }
    }

    private fun reverseLookup(address: String): String? = runCatching {
        val inet = InetAddress.getByName(address)
        inet.canonicalHostName.takeIf { it != address }
    }.getOrNull()

    companion object {
        private const val MAX_SILENT_HOPS = 5
    }
}
