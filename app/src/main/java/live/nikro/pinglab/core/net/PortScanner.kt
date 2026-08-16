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
import live.nikro.pinglab.core.model.PortProbe
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Concurrent TCP port sweep with a bounded worker pool.
 *
 * Android limits how many sockets an app may hold open, so probes run behind a
 * [Semaphore]; 24 in flight keeps a full 1000-port sweep under ~20 s on Wi-Fi without
 * tripping `EMFILE`.
 */
class PortScanner {

    data class Config(
        val timeoutMs: Int = 700,
        val concurrency: Int = 24,
        val grabBanner: Boolean = true,
    )

    sealed interface Event {
        data class Progress(val completed: Int, val total: Int) : Event
        data class Found(val probe: PortProbe) : Event
        data class Done(val openPorts: Int, val scannedPorts: Int, val elapsedMs: Long) : Event
    }

    fun scan(host: String, ports: List<Int>, config: Config = Config()): Flow<Event> = channelFlow {
        val startMs = System.currentTimeMillis()
        val semaphore = Semaphore(config.concurrency.coerceIn(1, 64))
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val open = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            for (port in ports) {
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val probe = probePort(host, port, config)
                        if (probe.isOpen) {
                            open.incrementAndGet()
                            send(Event.Found(probe))
                        }
                        send(Event.Progress(completed.incrementAndGet(), ports.size))
                    }
                }
            }
        }

        send(Event.Done(open.get(), ports.size, System.currentTimeMillis() - startMs))
    }.buffer(Channel.BUFFERED).flowOn(Dispatchers.IO)

    suspend fun probePort(host: String, port: Int, config: Config = Config()): PortProbe =
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            val startNs = System.nanoTime()
            try {
                socket = Socket()
                socket.soTimeout = config.timeoutMs
                socket.connect(InetSocketAddress(host, port), config.timeoutMs)
                val rttMs = (System.nanoTime() - startNs) / 1_000_000.0
                val banner = if (config.grabBanner) readBanner(socket) else null
                PortProbe(
                    port = port,
                    isOpen = true,
                    serviceName = WELL_KNOWN_PORTS[port],
                    rttMs = rttMs,
                    banner = banner,
                )
            } catch (e: Exception) {
                PortProbe(port = port, isOpen = false, serviceName = WELL_KNOWN_PORTS[port])
            } finally {
                runCatching { socket?.close() }
            }
        }

    /** Reads whatever the service volunteers in the first few hundred milliseconds. */
    private fun readBanner(socket: Socket): String? = runCatching {
        socket.soTimeout = BANNER_TIMEOUT_MS
        val buffer = ByteArray(160)
        val read = socket.getInputStream().read(buffer)
        if (read <= 0) return null
        String(buffer, 0, read, Charsets.ISO_8859_1)
            .trim()
            .replace(Regex("[\\p{Cntrl}]"), " ")
            .take(120)
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
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
            631 to "ipp", 636 to "ldaps", 993 to "imaps", 995 to "pop3s", 1080 to "socks",
            1194 to "openvpn", 1433 to "mssql", 1521 to "oracle", 1723 to "pptp",
            1883 to "mqtt", 2049 to "nfs", 2082 to "cpanel", 2083 to "cpanel-ssl",
            3000 to "dev-http", 3128 to "squid", 3306 to "mysql", 3389 to "rdp",
            4444 to "metasploit", 5000 to "upnp", 5060 to "sip", 5222 to "xmpp",
            5432 to "postgres", 5555 to "adb", 5601 to "kibana", 5672 to "amqp",
            5900 to "vnc", 6379 to "redis", 6667 to "irc", 7547 to "tr-069",
            8000 to "http-alt", 8008 to "http-alt", 8080 to "http-proxy", 8081 to "http-alt",
            8086 to "influxdb", 8123 to "home-assistant", 8443 to "https-alt",
            8888 to "http-alt", 9000 to "php-fpm", 9090 to "prometheus", 9100 to "jetdirect",
            9200 to "elasticsearch", 11211 to "memcached", 27017 to "mongodb",
            32400 to "plex", 51820 to "wireguard",
        )

        private const val BANNER_TIMEOUT_MS = 350

        fun rangeOf(from: Int, to: Int): List<Int> {
            val start = from.coerceIn(1, 65_535)
            val end = to.coerceIn(start, 65_535)
            return (start..end).toList()
        }
    }
}
