package live.nikro.pinglab.core.net

import live.nikro.pinglab.core.model.ProbeRequest
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A single reachability measurement mechanism.
 *
 * Implementations must be safe to call from any dispatcher, must never throw for
 * ordinary network failures (they return a failed [ProbeResult] instead) and must
 * respect [ProbeRequest.timeoutMs] as a hard upper bound.
 */
interface PingEngine {

    /** Protocols this engine can measure. */
    val supportedProtocols: Set<Protocol>

    /** Runs exactly one probe. */
    suspend fun probe(request: ProbeRequest): ProbeResult

    /** Frees any cached sockets/processes. Called when a session stops. */
    fun release() = Unit
}

/**
 * Resolves a hostname once and caches it for the duration of a session so that repeated
 * probes measure the network, not the DNS cache.
 */
class AddressResolver(private val cacheTtlMs: Long = 60_000L) {

    private data class Entry(val address: InetAddress, val resolvedAtMs: Long, val durationMs: Double)

    private val cache = HashMap<String, Entry>()
    private val lock = Any()

    data class Resolution(
        val address: InetAddress?,
        val durationMs: Double,
        val fromCache: Boolean,
        val error: String? = null,
    )

    fun resolve(host: String, preferIpv6: Boolean = false): Resolution {
        val key = "$host|$preferIpv6"
        val now = System.currentTimeMillis()

        synchronized(lock) {
            cache[key]?.let { cached ->
                if (now - cached.resolvedAtMs < cacheTtlMs) {
                    return Resolution(cached.address, cached.durationMs, fromCache = true)
                }
            }
        }

        val startNs = System.nanoTime()
        return try {
            val all = InetAddress.getAllByName(host)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000.0
            val chosen = pickAddress(all.toList(), preferIpv6)
                ?: return Resolution(null, durationMs, false, "No address records")
            synchronized(lock) { cache[key] = Entry(chosen, now, durationMs) }
            Resolution(chosen, durationMs, fromCache = false)
        } catch (e: UnknownHostException) {
            Resolution(null, (System.nanoTime() - startNs) / 1_000_000.0, false, e.message ?: "Unknown host")
        } catch (e: SecurityException) {
            Resolution(null, 0.0, false, e.message ?: "Blocked by policy")
        }
    }

    fun invalidate(host: String? = null) = synchronized(lock) {
        if (host == null) cache.clear() else cache.keys.removeAll { it.startsWith("$host|") }
    }

    private fun pickAddress(addresses: List<InetAddress>, preferIpv6: Boolean): InetAddress? {
        if (addresses.isEmpty()) return null
        val v6 = addresses.firstOrNull { it is java.net.Inet6Address }
        val v4 = addresses.firstOrNull { it is java.net.Inet4Address }
        return if (preferIpv6) v6 ?: v4 else v4 ?: v6
    }
}

/**
 * Maps low-level exceptions onto the app's [ProbeStatus] vocabulary so that the UI can
 * show one consistent set of failure reasons across every protocol.
 */
object ProbeErrorMapper {

    fun statusFor(throwable: Throwable): ProbeStatus = when (throwable) {
        is UnknownHostException -> ProbeStatus.DNS_FAILURE
        is java.net.SocketTimeoutException -> ProbeStatus.TIMEOUT
        is java.net.NoRouteToHostException -> ProbeStatus.UNREACHABLE
        is java.net.PortUnreachableException -> ProbeStatus.UNREACHABLE
        is java.net.ConnectException -> ProbeStatus.UNREACHABLE
        is SecurityException -> ProbeStatus.PERMISSION_DENIED
        is IOException -> {
            val message = throwable.message.orEmpty().lowercase()
            when {
                "permission denied" in message -> ProbeStatus.PERMISSION_DENIED
                "network is unreachable" in message -> ProbeStatus.NETWORK_UNAVAILABLE
                "timed out" in message || "timeout" in message -> ProbeStatus.TIMEOUT
                "unreachable" in message -> ProbeStatus.UNREACHABLE
                else -> ProbeStatus.ERROR
            }
        }

        else -> ProbeStatus.ERROR
    }

    fun detailFor(throwable: Throwable): String =
        throwable.message?.take(160) ?: throwable::class.java.simpleName
}

/**
 * Chooses the right engine per protocol and keeps one shared resolver so DNS timing is
 * consistent across engines.
 */
class PingEngineFactory(
    private val resolver: AddressResolver = AddressResolver(),
) {

    private val icmp: PingEngine by lazy { IcmpPingEngine(resolver) }
    private val tcp: PingEngine by lazy { TcpPingEngine(resolver) }
    private val http: PingEngine by lazy { HttpPingEngine(resolver) }
    private val dns: PingEngine by lazy { DnsPingEngine(resolver) }

    fun engineFor(protocol: Protocol): PingEngine = when (protocol) {
        Protocol.ICMP -> icmp
        Protocol.TCP -> tcp
        Protocol.HTTP, Protocol.HTTPS -> http
        Protocol.DNS -> dns
    }

    suspend fun probe(request: ProbeRequest): ProbeResult =
        engineFor(request.protocol).probe(request)

    fun releaseAll() {
        listOf(icmp, tcp, http, dns).forEach { runCatching { it.release() } }
        resolver.invalidate()
    }

    fun sharedResolver(): AddressResolver = resolver
}
