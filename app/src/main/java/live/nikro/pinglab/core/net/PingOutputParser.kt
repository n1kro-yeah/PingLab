package live.nikro.pinglab.core.net

/**
 * Pure parser for the output of the busybox/toybox `ping` binary shipped on Android.
 *
 * Kept free of Android imports so it can be covered by fast JVM unit tests \u2014 parsing
 * regressions here would silently break every ICMP measurement on devices where the
 * datagram-socket path is unavailable.
 *
 * Recognised shapes:
 * ```
 * 64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=23.4 ms
 * 64 bytes from dns.google (8.8.8.8): icmp_seq=1 ttl=117 time=23.4 ms
 * From 10.0.0.1 icmp_seq=1 Time to live exceeded
 * From 10.0.0.1 icmp_seq=1 Destination Host Unreachable
 * ping: unknown host nope.invalid
 * rtt min/avg/max/mdev = 21.1/23.4/26.0/1.902 ms
 * 5 packets transmitted, 5 received, 0% packet loss, time 4006ms
 * ```
 */
object PingOutputParser {

    data class Sample(
        val bytes: Int?,
        val fromAddress: String?,
        val sequence: Int?,
        val ttl: Int?,
        val timeMs: Double,
    )

    data class Summary(
        val transmitted: Int,
        val received: Int,
        val lossPercent: Double,
        val minMs: Double?,
        val avgMs: Double?,
        val maxMs: Double?,
        val mdevMs: Double?,
    )

    enum class Failure {
        TTL_EXCEEDED,
        UNREACHABLE,
        UNKNOWN_HOST,
        NETWORK_UNREACHABLE,
        PERMISSION_DENIED,
        NONE,
    }

    private val SAMPLE_REGEX = Regex(
        """(?:(\d+)\s+bytes\s+from\s+)([^:\s]+)(?:\s+\(([^)]+)\))?:\s*(?:icmp_)?seq=(\d+)(?:\s+ttl=(\d+))?\s+time[=<]\s*([\d.]+)\s*ms""",
        RegexOption.IGNORE_CASE,
    )

    private val LOOSE_TIME_REGEX = Regex("""time[=<]\s*([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)
    private val TTL_REGEX = Regex("""ttl=(\d+)""", RegexOption.IGNORE_CASE)
    private val SEQ_REGEX = Regex("""(?:icmp_)?seq=(\d+)""", RegexOption.IGNORE_CASE)
    private val FROM_REGEX = Regex("""[Ff]rom\s+([0-9a-fA-F:.]+)""")
    private val FROM_NAMED_REGEX = Regex("""[Ff]rom\s+\S+\s+\(([0-9a-fA-F:.]+)\)""")

    private val SUMMARY_COUNTS_REGEX = Regex(
        """(\d+)\s+packets?\s+transmitted,\s*(\d+)\s+(?:packets\s+)?received""",
        RegexOption.IGNORE_CASE,
    )
    private val SUMMARY_LOSS_REGEX = Regex("""([\d.]+)%\s+packet\s+loss""", RegexOption.IGNORE_CASE)
    private val SUMMARY_RTT_REGEX = Regex(
        """(?:rtt|round-trip)\s+min/avg/max(?:/mdev|/stddev)?\s*=\s*([\d.]+)/([\d.]+)/([\d.]+)(?:/([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )

    /** Parses a single reply line, returning null when the line is not a reply. */
    fun parseSample(line: String): Sample? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.contains("time", ignoreCase = true)) return null

        SAMPLE_REGEX.find(trimmed)?.let { match ->
            val groups = match.groupValues
            val named = groups[3].takeIf { it.isNotBlank() }
            return Sample(
                bytes = groups[1].toIntOrNull(),
                fromAddress = named ?: groups[2].takeIf { it.isNotBlank() },
                sequence = groups[4].toIntOrNull(),
                ttl = groups[5].toIntOrNull(),
                timeMs = groups[6].toDoubleOrNull() ?: return null,
            )
        }

        // Fallback for exotic builds that reorder the fields.
        val time = LOOSE_TIME_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        if (!trimmed.contains("bytes from", ignoreCase = true)) return null
        return Sample(
            bytes = Regex("""^(\d+)\s+bytes""").find(trimmed)?.groupValues?.get(1)?.toIntOrNull(),
            fromAddress = extractSourceAddress(trimmed),
            sequence = SEQ_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull(),
            ttl = TTL_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull(),
            timeMs = time,
        )
    }

    /** Classifies an error line emitted instead of a reply. */
    fun classifyFailure(output: String): Failure {
        val lower = output.lowercase()
        return when {
            "time to live exceeded" in lower || "hop limit exceeded" in lower -> Failure.TTL_EXCEEDED
            "unknown host" in lower || "bad address" in lower ||
                "name or service not known" in lower ||
                "temporary failure in name resolution" in lower -> Failure.UNKNOWN_HOST

            "network is unreachable" in lower -> Failure.NETWORK_UNREACHABLE
            "permission denied" in lower || "operation not permitted" in lower -> Failure.PERMISSION_DENIED
            "unreachable" in lower -> Failure.UNREACHABLE
            else -> Failure.NONE
        }
    }

    /** Extracts the responding router address from a `From x.x.x.x ...` line. */
    fun extractSourceAddress(line: String): String? =
        FROM_NAMED_REGEX.find(line)?.groupValues?.get(1)
            ?: FROM_REGEX.find(line)?.groupValues?.get(1)

    /** Parses the trailing statistics block of a multi-packet run. */
    fun parseSummary(output: String): Summary? {
        val counts = SUMMARY_COUNTS_REGEX.find(output) ?: return null
        val transmitted = counts.groupValues[1].toIntOrNull() ?: return null
        val received = counts.groupValues[2].toIntOrNull() ?: return null
        val loss = SUMMARY_LOSS_REGEX.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: if (transmitted == 0) 0.0 else (transmitted - received) * 100.0 / transmitted
        val rtt = SUMMARY_RTT_REGEX.find(output)
        return Summary(
            transmitted = transmitted,
            received = received,
            lossPercent = loss,
            minMs = rtt?.groupValues?.get(1)?.toDoubleOrNull(),
            avgMs = rtt?.groupValues?.get(2)?.toDoubleOrNull(),
            maxMs = rtt?.groupValues?.get(3)?.toDoubleOrNull(),
            mdevMs = rtt?.groupValues?.get(4)?.toDoubleOrNull(),
        )
    }

    /** Pulls the resolved address out of the `PING host (1.2.3.4) 56(84) bytes of data.` banner. */
    fun parseResolvedAddress(output: String): String? =
        Regex("""PING\s+\S+\s+\(([0-9a-fA-F:.]+)\)""").find(output)?.groupValues?.get(1)
            ?: Regex("""PING\s+([0-9]{1,3}(?:\.[0-9]{1,3}){3})""").find(output)?.groupValues?.get(1)
}
