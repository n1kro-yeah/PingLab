package live.nikro.pinglab.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import live.nikro.pinglab.core.model.PortEvidence
import live.nikro.pinglab.core.model.PortProbe
import live.nikro.pinglab.core.model.PortState
import live.nikro.pinglab.core.model.ScanTrust
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/**
 * TCP port scanner that deliberately refuses to trust a bare `connect()`.
 *
 * A naive scanner reports a port as open the moment the three-way handshake completes.
 * That is wrong often enough to be useless on real networks:
 *
 *  - Carrier and corporate transparent proxies terminate the handshake themselves and
 *    only afterwards try to reach the backend, so *every* port answers SYN-ACK.
 *  - Load balancers, DDoS scrubbers and tarpits accept connections for ports that have
 *    nothing behind them.
 *  - Some firewalls forge RST packets, which makes live ports look closed.
 *
 * So this scanner behaves like a diagnostic tool instead of a guess machine:
 *
 *  1. **Control probe.** Before the sweep it connects to a handful of random high ports
 *     that realistically cannot be listening. If those answer, the path in front of the
 *     host accepts everything and the whole sweep is flagged as untrustworthy.
 *  2. **Honest classification.** `ECONNREFUSED`/RST means closed, silence means filtered,
 *     `EHOSTUNREACH` means unreachable. Filtered ports are retried once with a doubled
 *     timeout before the verdict is recorded.
 *  3. **Behavioural verification.** Every accepted port has to prove a service is really
 *     there: a banner, an HTTP response, a completed TLS handshake or at least some bytes.
 *     Ports that accept and then stay silent or drop the connection are reported as
 *     ACCEPTED rather than OPEN when the control probe says the path is suspicious.
 *
 * It is slower than a naive sweep - two or three connections per interesting port - and
 * that is the point: the result is something you can act on.
 */
class PortScanner {

    data class Config(
        val connectTimeoutMs: Int = 1_500,
        val readTimeoutMs: Int = 900,
        val concurrency: Int = 16,
        val verify: Boolean = true,
        val retryFiltered: Boolean = true,
        val controlSamples: Int = 4,
    )

    /** Which phase of the scan a progress update belongs to. */
    enum class Stage { CONTROL, SWEEP }

    data class Summary(
        val open: Int,
        val accepted: Int,
        val closed: Int,
        val filtered: Int,
        val scanned: Int,
        val elapsedMs: Long,
        val trust: ScanTrust,
    )

    sealed interface Event {
        data class Resolved(val host: String, val address: String) : Event
        data class Control(
            val trust: ScanTrust,
            val accepted: Int,
            val samples: Int,
            val probedPorts: List<Int>,
        ) : Event

        data class Progress(val completed: Int, val total: Int, val stage: Stage) : Event
        data class Result(val probe: PortProbe) : Event
        data class Done(val summary: Summary) : Event
        data class Failed(val reason: String) : Event
    }

    fun scan(host: String, ports: List<Int>, config: Config = Config()): Flow<Event> = channelFlow {
        val startMs = System.currentTimeMillis()
        val target = host.trim()

        // Resolve once. Doing it per port would add a DNS lookup to every probe and, worse,
        // a sweep could silently hop between addresses of a round-robin record.
        val address = withContext(Dispatchers.IO) {
            runCatching { InetAddress.getByName(target) }.getOrNull()
        }
        if (address == null) {
            send(Event.Failed("cannot resolve " + target))
            return@channelFlow
        }
        send(Event.Resolved(target, address.hostAddress ?: target))

        // ---------------------------------------------------------------- control probe
        val controlList = controlPorts(ports, config.controlSamples)
        var acceptedControl = 0
        controlList.forEachIndexed { index, port ->
            val outcome = withContext(Dispatchers.IO) {
                connect(address, port, config.connectTimeoutMs)
            }
            if (outcome.kind == ConnectKind.ACCEPTED) acceptedControl++
            send(Event.Progress(index + 1, controlList.size, Stage.CONTROL))
        }
        val trust = when {
            controlList.isEmpty() -> ScanTrust.UNKNOWN
            acceptedControl >= 2 -> ScanTrust.ACCEPT_ALL
            acceptedControl == 1 -> ScanTrust.SUSPICIOUS
            else -> ScanTrust.TRUSTED
        }
        send(Event.Control(trust, acceptedControl, controlList.size, controlList))

        // ---------------------------------------------------------------- sweep
        val semaphore = Semaphore(config.concurrency.coerceIn(1, 32))
        val completed = AtomicInteger(0)
        val openCount = AtomicInteger(0)
        val acceptedCount = AtomicInteger(0)
        val closedCount = AtomicInteger(0)
        val filteredCount = AtomicInteger(0)

        coroutineScope {
            ports.forEach { port ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val probe = probe(address, target, port, config, trust)
                        when (probe.state) {
                            PortState.OPEN -> openCount.incrementAndGet()
                            PortState.ACCEPTED -> acceptedCount.incrementAndGet()
                            PortState.CLOSED -> closedCount.incrementAndGet()
                            else -> filteredCount.incrementAndGet()
                        }
                        if (probe.state == PortState.OPEN || probe.state == PortState.ACCEPTED) {
                            send(Event.Result(probe))
                        }
                        send(Event.Progress(completed.incrementAndGet(), ports.size, Stage.SWEEP))
                    }
                }
            }
        }

        send(
            Event.Done(
                Summary(
                    open = openCount.get(),
                    accepted = acceptedCount.get(),
                    closed = closedCount.get(),
                    filtered = filteredCount.get(),
                    scanned = ports.size,
                    elapsedMs = System.currentTimeMillis() - startMs,
                    trust = trust,
                ),
            ),
        )
    }.buffer(Channel.BUFFERED).flowOn(Dispatchers.IO)

    /** Resolves [host] and probes a single port. Handy for host:port monitoring. */
    suspend fun probeSingle(host: String, port: Int, config: Config = Config()): PortProbe? =
        withContext(Dispatchers.IO) {
            val address = runCatching { InetAddress.getByName(host.trim()) }.getOrNull()
                ?: return@withContext null
            probe(address, host.trim(), port, config, ScanTrust.UNKNOWN)
        }

    /** Connect, retry, verify, classify - the whole pipeline for one port. */
    suspend fun probe(
        address: InetAddress,
        host: String,
        port: Int,
        config: Config = Config(),
        trust: ScanTrust = ScanTrust.UNKNOWN,
    ): PortProbe = withContext(Dispatchers.IO) {
        var outcome = connect(address, port, config.connectTimeoutMs)
        // Mobile links drop packets; one silent attempt is not proof of a firewall.
        if (outcome.kind == ConnectKind.TIMEOUT && config.retryFiltered) {
            outcome = connect(address, port, config.connectTimeoutMs * 2)
        }
        val service = WELL_KNOWN_PORTS[port]
        when (outcome.kind) {
            ConnectKind.REFUSED -> PortProbe(
                port = port,
                state = PortState.CLOSED,
                serviceName = service,
                rttMs = outcome.rttMs,
                evidence = PortEvidence.RESET,
            )

            ConnectKind.TIMEOUT -> PortProbe(
                port = port,
                state = PortState.FILTERED,
                serviceName = service,
                evidence = PortEvidence.NO_REPLY,
            )

            ConnectKind.UNREACHABLE -> PortProbe(
                port = port,
                state = PortState.UNREACHABLE,
                serviceName = service,
                evidence = PortEvidence.NO_REPLY,
                detail = outcome.detail,
            )

            ConnectKind.ERROR -> PortProbe(
                port = port,
                state = PortState.FILTERED,
                serviceName = service,
                evidence = PortEvidence.NO_REPLY,
                detail = outcome.detail,
            )

            ConnectKind.ACCEPTED -> {
                val verification = if (config.verify) {
                    verify(address, host, port, config)
                } else {
                    Verification(PortEvidence.NOT_CHECKED, null, null)
                }
                val proven = verification.evidence == PortEvidence.BANNER ||
                    verification.evidence == PortEvidence.HTTP ||
                    verification.evidence == PortEvidence.TLS ||
                    verification.evidence == PortEvidence.RESPONSE
                // A silent port is normal (SMB, RDP, VNC...), so on a clean path it still
                // counts as open. When the control probe caught an accept-everything path,
                // silence is exactly what a fake port looks like, so it is downgraded.
                val state = when {
                    proven -> PortState.OPEN
                    !config.verify -> PortState.OPEN
                    trust == ScanTrust.TRUSTED || trust == ScanTrust.UNKNOWN -> PortState.OPEN
                    else -> PortState.ACCEPTED
                }
                PortProbe(
                    port = port,
                    state = state,
                    serviceName = service,
                    rttMs = outcome.rttMs,
                    banner = verification.banner,
                    evidence = verification.evidence,
                    detail = verification.detail,
                )
            }
        }
    }

    // ------------------------------------------------------------------ connect stage

    private enum class ConnectKind { ACCEPTED, REFUSED, TIMEOUT, UNREACHABLE, ERROR }

    private class ConnectOutcome(
        val kind: ConnectKind,
        val rttMs: Double? = null,
        val detail: String? = null,
    )

    private fun connect(address: InetAddress, port: Int, timeoutMs: Int): ConnectOutcome {
        val socket = Socket()
        val startNs = System.nanoTime()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            ConnectOutcome(ConnectKind.ACCEPTED, elapsedMs(startNs))
        } catch (e: SocketTimeoutException) {
            ConnectOutcome(ConnectKind.TIMEOUT, null, e.message?.take(120))
        } catch (e: IOException) {
            classify(e)
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Android puts the raw errno in the exception message, and that is the only dependable
     * way to separate "the host said no" from "nobody answered at all".
     */
    private fun classify(error: Throwable): ConnectOutcome {
        val message = error.message ?: error.toString()
        val kind = when {
            message.contains("ECONNREFUSED") -> ConnectKind.REFUSED
            message.contains("Connection refused") -> ConnectKind.REFUSED
            message.contains("ECONNRESET") -> ConnectKind.REFUSED
            message.contains("Connection reset") -> ConnectKind.REFUSED
            message.contains("ETIMEDOUT") -> ConnectKind.TIMEOUT
            message.contains("timed out", ignoreCase = true) -> ConnectKind.TIMEOUT
            message.contains("EHOSTUNREACH") -> ConnectKind.UNREACHABLE
            message.contains("ENETUNREACH") -> ConnectKind.UNREACHABLE
            message.contains("unreachable", ignoreCase = true) -> ConnectKind.UNREACHABLE
            error is ConnectException -> ConnectKind.REFUSED
            else -> ConnectKind.ERROR
        }
        return ConnectOutcome(kind, null, message.take(120))
    }

    // ------------------------------------------------------------------ verify stage

    private class Verification(
        val evidence: PortEvidence,
        val banner: String?,
        val detail: String?,
    )

    private class ReadOutcome(val bytes: Int, val text: String?, val closed: Boolean)

    /**
     * Asks the port to behave like a service. A fresh connection is used on purpose: the
     * probe socket is already gone, and reconnecting keeps the state machine trivial.
     */
    private fun verify(address: InetAddress, host: String, port: Int, config: Config): Verification {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), config.connectTimeoutMs)
            socket.soTimeout = config.readTimeoutMs

            // Many services greet first: SSH, FTP, SMTP, POP3, IMAP, MySQL, IRC...
            val greeting = readSome(socket)
            if (greeting.bytes > 0) {
                return Verification(
                    if (greeting.text != null) PortEvidence.BANNER else PortEvidence.RESPONSE,
                    greeting.text,
                    greeting.bytes.toString() + " bytes greeting",
                )
            }
            if (greeting.closed) {
                return Verification(PortEvidence.DROPPED, null, "closed without sending anything")
            }

            // A completed TLS handshake is the strongest possible proof of a real service.
            if (TLS_PORTS.contains(port)) {
                val tls = tlsHandshake(address, host, port, config)
                if (tls != null) return Verification(PortEvidence.TLS, tls, null)
            }

            socket.getOutputStream().apply {
                write(httpRequest(host).toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val answer = readSome(socket)
            val text = answer.text
            return when {
                text != null && text.startsWith("HTTP/") ->
                    Verification(PortEvidence.HTTP, text.take(120), null)

                answer.bytes > 0 -> Verification(PortEvidence.RESPONSE, text, null)
                answer.closed -> Verification(PortEvidence.DROPPED, null, "dropped after first byte")
                else -> Verification(PortEvidence.SILENT, null, "accepted but never answered")
            }
        } catch (e: IOException) {
            // It accepted a moment ago and refuses now: the hallmark of a middlebox that
            // finishes the handshake before it knows whether the backend is alive.
            return Verification(PortEvidence.DROPPED, null, (e.message ?: "io error").take(120))
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readSome(socket: Socket): ReadOutcome {
        val buffer = ByteArray(512)
        return try {
            val read = socket.getInputStream().read(buffer)
            if (read <= 0) ReadOutcome(0, null, true) else ReadOutcome(read, sanitize(buffer, read), false)
        } catch (e: SocketTimeoutException) {
            ReadOutcome(0, null, false)
        } catch (e: IOException) {
            ReadOutcome(0, null, true)
        }
    }

    private fun sanitize(buffer: ByteArray, length: Int): String? =
        String(buffer, 0, length, Charsets.ISO_8859_1)
            .replace(Regex("[\\p{Cntrl}]"), " ")
            .trim()
            .take(140)
            .takeIf { it.isNotBlank() }

    /**
     * Handshake-only TLS probe. The permissive trust manager exists so that self-signed
     * and expired certificates still prove that a TLS server is listening; no application
     * data is ever exchanged over this socket.
     */
    private fun tlsHandshake(address: InetAddress, host: String, port: Int, config: Config): String? {
        var plain: Socket? = null
        var tls: SSLSocket? = null
        return try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(PermissiveTrustManager()), SecureRandom())
            plain = Socket()
            plain.connect(InetSocketAddress(address, port), config.connectTimeoutMs)
            plain.soTimeout = config.readTimeoutMs
            tls = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            tls.startHandshake()
            val session = tls.session
            "TLS " + session.protocol + " / " + session.cipherSuite
        } catch (e: Exception) {
            null
        } finally {
            runCatching { tls?.close() }
            runCatching { plain?.close() }
        }
    }

    /**
     * Picks random high ports that no sane host listens on. If these answer, something in
     * the path is answering for the host and the sweep cannot be trusted.
     */
    private fun controlPorts(requested: List<Int>, samples: Int): List<Int> {
        if (samples <= 0) return emptyList()
        val requestedSet = requested.toHashSet()
        val random = Random(System.nanoTime())
        val picked = linkedSetOf<Int>()
        var guard = 0
        while (picked.size < samples && guard < samples * 40) {
            guard++
            val candidate = random.nextInt(CONTROL_PORT_FROM, CONTROL_PORT_TO)
            if (!requestedSet.contains(candidate)) picked.add(candidate)
        }
        return picked.toList()
    }

    private fun elapsedMs(startNs: Long): Double = (System.nanoTime() - startNs) / 1_000_000.0

    private fun httpRequest(host: String): String =
        "GET / HTTP/1.0\r\nHost: " + host + "\r\nUser-Agent: PingLab\r\nAccept: */*\r\nConnection: close\r\n\r\n"

    /** Inspection only - see [tlsHandshake]. */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class PermissiveTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val CONTROL_PORT_FROM = 49_200
        private const val CONTROL_PORT_TO = 65_500

        /** Ports where a TLS handshake is the natural way to prove a service is alive. */
        val TLS_PORTS: Set<Int> = setOf(
            443, 465, 563, 587, 636, 853, 989, 990, 993, 995, 1443, 2083, 2087, 2096,
            4433, 5061, 5671, 6697, 8443, 8883, 9443, 10000, 15672,
        )

        /** The default sweep: services people actually care about on a home or office LAN. */
        val COMMON_PORTS: List<Int> = listOf(
            20, 21, 22, 23, 25, 53, 67, 68, 69, 80, 110, 123, 135, 137, 138, 139, 143, 161,
            389, 443, 445, 465, 514, 515, 587, 631, 636, 993, 995, 1080, 1194, 1433, 1521,
            1723, 1883, 2049, 2082, 2083, 3000, 3128, 3306, 3389, 4444, 5000, 5060, 5222,
            5432, 5555, 5601, 5672, 5900, 6379, 6667, 7547, 8000, 8008, 8080, 8081, 8086,
            8123, 8443, 8888, 9000, 9090, 9100, 9200, 11211, 27017, 32400, 51820,
        )

        val WELL_KNOWN_PORTS: Map<Int, String> = mapOf(
            20 to "ftp-data", 21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp",
            53 to "dns", 67 to "dhcp", 68 to "dhcp", 69 to "tftp", 80 to "http",
            110 to "pop3", 123 to "ntp", 135 to "msrpc", 137 to "netbios", 138 to "netbios",
            139 to "netbios", 143 to "imap", 161 to "snmp", 389 to "ldap", 443 to "https",
            445 to "smb", 465 to "smtps", 514 to "syslog", 515 to "printer", 587 to "submission",
            631 to "ipp", 636 to "ldaps", 853 to "dns-over-tls", 993 to "imaps", 995 to "pop3s",
            1080 to "socks", 1194 to "openvpn", 1433 to "mssql", 1521 to "oracle",
            1723 to "pptp", 1883 to "mqtt", 2049 to "nfs", 2082 to "cpanel",
            2083 to "cpanel-ssl", 3000 to "dev-http", 3128 to "squid", 3306 to "mysql",
            3389 to "rdp", 4444 to "metasploit", 5000 to "upnp", 5060 to "sip",
            5222 to "xmpp", 5432 to "postgres", 5555 to "adb", 5601 to "kibana",
            5672 to "amqp", 5900 to "vnc", 6379 to "redis", 6667 to "irc", 7547 to "tr-069",
            8000 to "http-alt", 8008 to "http-alt", 8080 to "http-proxy", 8081 to "http-alt",
            8086 to "influxdb", 8123 to "home-assistant", 8443 to "https-alt",
            8883 to "mqtt-tls", 8888 to "http-alt", 9000 to "php-fpm", 9090 to "prometheus",
            9100 to "jetdirect", 9200 to "elasticsearch", 11211 to "memcached",
            27017 to "mongodb", 32400 to "plex", 51820 to "wireguard",
        )

        fun rangeOf(from: Int, to: Int): List<Int> {
            val start = from.coerceIn(1, 65_535)
            val end = to.coerceIn(start, 65_535)
            return (start..end).toList()
        }
    }
}
