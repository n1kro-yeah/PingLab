package live.nikro.pinglab.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import live.nikro.pinglab.core.model.ProbeStatus
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Fallback tier that shells out to the platform `ping` binary.
 *
 * Why this exists: unprivileged ICMP datagram sockets (`SOCK_DGRAM`/`IPPROTO_ICMP`) are
 * available on most modern Android kernels, but some vendors leave the
 * `net.ipv4.ping_group_range` sysctl closed, in which case `socket()` fails with EACCES.
 * `/system/bin/ping` is setuid-capable and still works there, so it is a genuine second
 * chance rather than a stub.
 *
 * Every invocation is bounded twice: `-W` tells ping to give up, and
 * [withTimeoutOrNull] plus [Process.destroyForcibly] guarantees we never leak a process if
 * the binary ignores it.
 */
class SystemPingBinary {

    data class Shot(
        val status: ProbeStatus,
        val rttMs: Double?,
        val fromAddress: String?,
        val ttl: Int? = null,
        val bytes: Int? = null,
        val detail: String? = null,
        val rawOutput: String = "",
    )

    /** Resolved once: probing the filesystem on every ping would be wasteful. */
    val isAvailable: Boolean by lazy { binaryFor(ipv6 = false) != null }

    fun supportsIpv6(): Boolean = binaryFor(ipv6 = true) != null

    /**
     * One echo request. [sequence] is only used for logging; the binary manages its own
     * sequence numbers.
     */
    suspend fun probe(
        host: String,
        sequence: Int = 1,
        payloadSize: Int = 32,
        timeoutMs: Int = 2_000,
        ttl: Int = 64,
        ipv6: Boolean = false,
    ): Shot {
        val binary = binaryFor(ipv6) ?: return Shot(
            status = ProbeStatus.PERMISSION_DENIED,
            rttMs = null,
            fromAddress = null,
            detail = "ping binary not available",
        )
        val command = buildList {
            add(binary)
            add("-n")
            add("-c")
            add("1")
            add("-W")
            add(seconds(timeoutMs).toString())
            add("-s")
            add(payloadSize.coerceIn(0, 1_472).toString())
            if (!ipv6) {
                add("-t")
                add(ttl.coerceIn(1, 255).toString())
            }
            add(host)
        }
        return interpret(execute(command, timeoutMs + PROCESS_GRACE_MS), expectedTtlExpiry = false)
    }

    /**
     * Traceroute helper: one probe with a fixed TTL, where a `Time to live exceeded` reply is
     * the *expected* outcome rather than an error.
     */
    suspend fun probeHop(
        host: String,
        ttl: Int,
        timeoutMs: Int = 1_500,
        ipv6: Boolean = false,
    ): Shot {
        val binary = binaryFor(ipv6) ?: return Shot(
            status = ProbeStatus.PERMISSION_DENIED,
            rttMs = null,
            fromAddress = null,
            detail = "ping binary not available",
        )
        val ttlFlag = if (ipv6) "-t" else "-t"
        val command = listOf(
            binary,
            "-n",
            "-c", "1",
            "-W", seconds(timeoutMs).toString(),
            ttlFlag, ttl.coerceIn(1, 255).toString(),
            host,
        )
        return interpret(execute(command, timeoutMs + PROCESS_GRACE_MS), expectedTtlExpiry = true)
    }

    /** Multi-packet run used by the quick-test action; returns the parsed summary block. */
    suspend fun burst(
        host: String,
        count: Int,
        intervalMs: Long = 200L,
        timeoutMs: Int = 2_000,
        ipv6: Boolean = false,
    ): PingOutputParser.Summary? {
        val binary = binaryFor(ipv6) ?: return null
        val interval = (intervalMs / 1000.0).coerceAtLeast(0.2)
        val command = listOf(
            binary,
            "-n",
            "-c", count.coerceIn(1, 100).toString(),
            "-i", String.format("%.1f", interval),
            "-W", seconds(timeoutMs).toString(),
            host,
        )
        val output = execute(command, timeoutMs.toLong() + count * intervalMs + PROCESS_GRACE_MS)
        return PingOutputParser.parseSummary(output)
    }

    private fun interpret(output: String, expectedTtlExpiry: Boolean): Shot {
        if (output.isBlank()) {
            return Shot(ProbeStatus.TIMEOUT, null, null, detail = "no output from ping")
        }

        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val resolved = PingOutputParser.parseResolvedAddress(output)

        // A successful reply always wins, even if later lines contain noise.
        lines.firstNotNullOfOrNull { PingOutputParser.parseSample(it) }?.let { sample ->
            return Shot(
                status = ProbeStatus.SUCCESS,
                rttMs = sample.timeMs,
                fromAddress = sample.fromAddress ?: resolved,
                ttl = sample.ttl,
                bytes = sample.bytes,
                rawOutput = output,
            )
        }

        val failure = PingOutputParser.classifyFailure(output)
        val source = lines.firstNotNullOfOrNull { PingOutputParser.extractSourceAddress(it) }

        return when (failure) {
            PingOutputParser.Failure.TTL_EXCEEDED -> Shot(
                status = ProbeStatus.TTL_EXPIRED,
                rttMs = null,
                fromAddress = source ?: resolved,
                detail = if (expectedTtlExpiry) null else "TTL expired in transit",
                rawOutput = output,
            )

            PingOutputParser.Failure.UNREACHABLE -> Shot(
                status = ProbeStatus.UNREACHABLE,
                rttMs = null,
                fromAddress = source ?: resolved,
                detail = "destination unreachable",
                rawOutput = output,
            )

            PingOutputParser.Failure.UNKNOWN_HOST -> Shot(
                status = ProbeStatus.DNS_FAILURE,
                rttMs = null,
                fromAddress = null,
                detail = "unknown host",
                rawOutput = output,
            )

            PingOutputParser.Failure.NETWORK_UNREACHABLE -> Shot(
                status = ProbeStatus.NETWORK_UNAVAILABLE,
                rttMs = null,
                fromAddress = null,
                detail = "network is unreachable",
                rawOutput = output,
            )

            PingOutputParser.Failure.PERMISSION_DENIED -> Shot(
                status = ProbeStatus.PERMISSION_DENIED,
                rttMs = null,
                fromAddress = null,
                detail = "ping denied by the system",
                rawOutput = output,
            )

            PingOutputParser.Failure.NONE -> Shot(
                status = ProbeStatus.TIMEOUT,
                rttMs = null,
                fromAddress = source ?: resolved,
                detail = null,
                rawOutput = output,
            )
        }
    }

    private suspend fun execute(command: List<String>, budgetMs: Long): String =
        withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                withTimeoutOrNull(budgetMs) {
                    val started = ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                    process = started
                    val text = started.inputStream.bufferedReader().use { it.readText() }
                    // Reap the child so we do not accumulate zombies during long sessions.
                    started.waitFor(1, TimeUnit.SECONDS)
                    text
                } ?: ""
            } catch (e: Exception) {
                "ping failed: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                process?.let { runCatching { it.destroyForcibly() } }
            }
        }

    private fun seconds(timeoutMs: Int): Int =
        ceil(timeoutMs / 1000.0).toInt().coerceIn(1, 30)

    private fun binaryFor(ipv6: Boolean): String? {
        val candidates = if (ipv6) IPV6_BINARIES else IPV4_BINARIES
        return candidates.firstOrNull { path ->
            runCatching { File(path).canExecute() }.getOrDefault(false)
        }
    }

    companion object {
        private const val PROCESS_GRACE_MS = 1_200L

        private val IPV4_BINARIES = listOf(
            "/system/bin/ping",
            "/system/xbin/ping",
            "/bin/ping",
            "/usr/bin/ping",
        )

        private val IPV6_BINARIES = listOf(
            "/system/bin/ping6",
            "/system/xbin/ping6",
            "/system/bin/ping",
            "/bin/ping6",
        )
    }
}
