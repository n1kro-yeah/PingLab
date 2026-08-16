package live.nikro.pinglab.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.nikro.pinglab.core.model.ProbeRequest
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.ProbeTransport
import live.nikro.pinglab.core.model.Protocol
import java.net.InetAddress

/**
 * Real ICMP echo, with three tiers tried in order of accuracy:
 *
 * 1. **Unprivileged ICMP datagram socket** ([IcmpDatagramPinger]). Microsecond-accurate,
 *    no child process, reports the responder address and TTL. Requires the kernel to allow
 *    `SOCK_DGRAM`/`IPPROTO_ICMP` for the app's GID.
 * 2. **`/system/bin/ping`** ([SystemPingBinary]). Works where the sysctl is closed. Costs a
 *    process fork per probe and adds a few ms of overhead.
 * 3. **`InetAddress.isReachable`**. Last resort only: the JDK tries ICMP and silently falls
 *    back to a TCP connection on port 7, so a "reply" may not be ICMP at all. The transport
 *    is reported honestly as [ProbeTransport.INET_REACHABLE] so the UI can show it.
 *
 * The winning tier is remembered, so a device that cannot use raw sockets pays the discovery
 * cost exactly once instead of on every single ping.
 */
class IcmpPingEngine(
    private val resolver: AddressResolver,
    private val datagramPinger: IcmpDatagramPinger = IcmpDatagramPinger(),
    private val systemPing: SystemPingBinary = SystemPingBinary(),
) : PingEngine {

    private enum class Tier { SOCKET, BINARY, REACHABLE }

    @Volatile
    private var preferredTier: Tier? = null

    override val supportedProtocols: Set<Protocol> = setOf(Protocol.ICMP)

    override suspend fun probe(request: ProbeRequest): ProbeResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()

        val resolution = resolver.resolve(request.target, request.preferIpv6)
        val address = resolution.address
            ?: return@withContext ProbeResult.failure(
                request = request,
                status = ProbeStatus.DNS_FAILURE,
                detail = resolution.error ?: ("Could not resolve " + request.target),
                transport = ProbeTransport.NONE,
                timestampMs = startedAt,
            ).copy(dnsMs = resolution.durationMs)

        val dnsMs = resolution.durationMs.takeIf { !resolution.fromCache }
        val order = tierOrder()

        var lastResult: ProbeResult? = null
        for (tier in order) {
            val result = when (tier) {
                Tier.SOCKET -> socketProbe(request, address, startedAt)
                Tier.BINARY -> binaryProbe(request, address, startedAt)
                Tier.REACHABLE -> reachableProbe(request, address, startedAt)
            } ?: continue

            // An environmental failure means "this tier is unusable here", not "host is down".
            if (result.status.isEnvironmental) {
                lastResult = result
                continue
            }

            preferredTier = tier
            return@withContext result.copy(dnsMs = dnsMs ?: result.dnsMs)
        }

        (lastResult ?: ProbeResult.failure(
            request = request,
            status = ProbeStatus.PERMISSION_DENIED,
            detail = "No ICMP transport available on this device",
            transport = ProbeTransport.NONE,
            resolvedAddress = address.hostAddress,
            timestampMs = startedAt,
        )).copy(dnsMs = dnsMs)
    }

    /** Preferred tier first, then the rest as fallbacks. */
    private fun tierOrder(): List<Tier> {
        val preferred = preferredTier
        val all = listOf(Tier.SOCKET, Tier.BINARY, Tier.REACHABLE)
        return if (preferred == null) all else listOf(preferred) + all.filter { it != preferred }
    }

    private fun socketProbe(
        request: ProbeRequest,
        address: InetAddress,
        startedAt: Long,
    ): ProbeResult? {
        val reply = runCatching {
            datagramPinger.ping(
                address = address,
                sequence = request.sequence,
                payloadSize = request.payloadSize,
                timeoutMs = request.timeoutMs,
                ttl = request.ttl,
            )
        }.getOrElse {
            return ProbeResult.failure(
                request = request,
                status = ProbeStatus.PERMISSION_DENIED,
                detail = it.message?.take(120) ?: "ICMP socket unavailable",
                transport = ProbeTransport.ICMP_SOCKET,
                resolvedAddress = address.hostAddress,
                timestampMs = startedAt,
            )
        }

        return ProbeResult(
            sequence = request.sequence,
            timestampMs = startedAt,
            protocol = Protocol.ICMP,
            status = reply.status,
            rttMs = reply.rttMs,
            hostname = request.target,
            resolvedAddress = reply.fromAddress ?: address.hostAddress,
            ttl = reply.ttl,
            payloadBytes = reply.payloadBytes,
            transport = ProbeTransport.ICMP_SOCKET,
            detail = reply.detail,
        )
    }

    private suspend fun binaryProbe(
        request: ProbeRequest,
        address: InetAddress,
        startedAt: Long,
    ): ProbeResult? {
        if (!systemPing.isAvailable) return null
        val host = address.hostAddress ?: request.target
        val shot = systemPing.probe(
            host = host,
            sequence = request.sequence,
            payloadSize = request.payloadSize,
            timeoutMs = request.timeoutMs,
            ttl = request.ttl,
            ipv6 = address is java.net.Inet6Address,
        )
        return ProbeResult(
            sequence = request.sequence,
            timestampMs = startedAt,
            protocol = Protocol.ICMP,
            status = shot.status,
            rttMs = shot.rttMs,
            hostname = request.target,
            resolvedAddress = shot.fromAddress ?: address.hostAddress,
            ttl = shot.ttl,
            payloadBytes = shot.bytes ?: (request.payloadSize + IcmpDatagramPinger.ICMP_HEADER_SIZE),
            transport = ProbeTransport.ICMP_BINARY,
            detail = shot.detail,
        )
    }

    private fun reachableProbe(
        request: ProbeRequest,
        address: InetAddress,
        startedAt: Long,
    ): ProbeResult {
        val startNs = System.nanoTime()
        val reachable = runCatching { address.isReachable(request.timeoutMs) }
            .getOrElse { false }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        return ProbeResult(
            sequence = request.sequence,
            timestampMs = startedAt,
            protocol = Protocol.ICMP,
            status = if (reachable) ProbeStatus.SUCCESS else ProbeStatus.TIMEOUT,
            rttMs = elapsedMs.takeIf { reachable },
            hostname = request.target,
            resolvedAddress = address.hostAddress,
            ttl = null,
            payloadBytes = null,
            transport = ProbeTransport.INET_REACHABLE,
            detail = "measured with isReachable (may fall back to TCP echo)",
        )
    }

    override fun release() {
        preferredTier = null
    }
}
