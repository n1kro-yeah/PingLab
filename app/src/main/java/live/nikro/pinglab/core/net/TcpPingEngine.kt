package live.nikro.pinglab.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.nikro.pinglab.core.model.ProbeRequest
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.ProbeTransport
import live.nikro.pinglab.core.model.Protocol
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP handshake timing \u2014 the most reliable way to measure a server that filters ICMP.
 *
 * We time a full `connect()` (SYN -> SYN/ACK -> ACK completes in the kernel, the call
 * returns after SYN/ACK) and immediately close the socket with `SO_LINGER 0` so we send a
 * RST instead of a FIN and never leave the remote side with a half-open connection.
 *
 * A `ConnectionRefused` still proves the host is alive, so it is reported as a *successful*
 * reachability check with the flag surfaced in the detail line \u2014 exactly what `tcping` does.
 */
class TcpPingEngine(
    private val resolver: AddressResolver,
) : PingEngine {

    override val supportedProtocols: Set<Protocol> = setOf(Protocol.TCP)

    override suspend fun probe(request: ProbeRequest): ProbeResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val port = request.effectivePort ?: DEFAULT_PORT

        val resolution = resolver.resolve(request.target, request.preferIpv6)
        val address = resolution.address ?: return@withContext ProbeResult.failure(
            request = request,
            status = ProbeStatus.DNS_FAILURE,
            detail = resolution.error ?: "Could not resolve ${request.target}",
            transport = ProbeTransport.TCP_CONNECT,
            timestampMs = startedAt,
        ).copy(dnsMs = resolution.durationMs)

        val dnsMs = resolution.durationMs.takeUnless { resolution.fromCache }
        var socket: Socket? = null
        val startNs = System.nanoTime()

        try {
            socket = Socket()
            socket.tcpNoDelay = true
            socket.reuseAddress = true
            socket.soTimeout = request.timeoutMs
            socket.connect(InetSocketAddress(address, port), request.timeoutMs)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            runCatching { socket.setSoLinger(true, 0) }

            ProbeResult(
                sequence = request.sequence,
                timestampMs = startedAt,
                protocol = Protocol.TCP,
                status = ProbeStatus.SUCCESS,
                rttMs = elapsedMs,
                hostname = request.target,
                resolvedAddress = address.hostAddress,
                transport = ProbeTransport.TCP_CONNECT,
                detail = "port $port open",
                dnsMs = dnsMs,
                connectMs = elapsedMs,
            )
        } catch (e: java.net.SocketTimeoutException) {
            ProbeResult.failure(
                request = request,
                status = ProbeStatus.TIMEOUT,
                detail = "No SYN/ACK from port $port within ${request.timeoutMs} ms",
                transport = ProbeTransport.TCP_CONNECT,
                resolvedAddress = address.hostAddress,
                timestampMs = startedAt,
            ).copy(dnsMs = dnsMs)
        } catch (e: java.net.ConnectException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            val refused = e.message.orEmpty().contains("refused", ignoreCase = true)
            if (refused) {
                // The host answered with RST: it is up, the port is simply closed.
                ProbeResult(
                    sequence = request.sequence,
                    timestampMs = startedAt,
                    protocol = Protocol.TCP,
                    status = ProbeStatus.SUCCESS,
                    rttMs = elapsedMs,
                    hostname = request.target,
                    resolvedAddress = address.hostAddress,
                    transport = ProbeTransport.TCP_CONNECT,
                    detail = "port $port closed (RST) \u2014 host is up",
                    dnsMs = dnsMs,
                    connectMs = elapsedMs,
                )
            } else {
                ProbeResult.failure(
                    request = request,
                    status = ProbeStatus.UNREACHABLE,
                    detail = e.message?.take(120),
                    transport = ProbeTransport.TCP_CONNECT,
                    resolvedAddress = address.hostAddress,
                    timestampMs = startedAt,
                ).copy(dnsMs = dnsMs)
            }
        } catch (e: Exception) {
            ProbeResult.failure(
                request = request,
                status = ProbeErrorMapper.statusFor(e),
                detail = ProbeErrorMapper.detailFor(e),
                transport = ProbeTransport.TCP_CONNECT,
                resolvedAddress = address.hostAddress,
                timestampMs = startedAt,
            ).copy(dnsMs = dnsMs)
        } finally {
            runCatching { socket?.close() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 443
    }
}
