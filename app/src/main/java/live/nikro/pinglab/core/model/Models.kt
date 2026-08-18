package live.nikro.pinglab.core.model

import kotlinx.serialization.Serializable

/**
 * Probe protocols supported by the app.
 *
 * ICMP is the "classic" ping. TCP/HTTP are useful when ICMP is filtered
 * (very common on cloud providers and mobile carriers).
 */
enum class Protocol(val label: String, val defaultPort: Int?, val needsPort: Boolean) {
    ICMP("ICMP", null, false),
    TCP("TCP", 443, true),
    HTTP("HTTP", 80, true),
    HTTPS("HTTPS", 443, true),
    DNS("DNS", 53, false),
    ;

    val isHttp: Boolean get() = this == HTTP || this == HTTPS

    companion object {
        fun fromNameOrDefault(name: String?, fallback: Protocol = ICMP): Protocol =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback
    }
}

/** How a single probe finished. */
enum class ProbeStatus {
    SUCCESS,
    TIMEOUT,
    UNREACHABLE,
    DNS_FAILURE,
    TTL_EXPIRED,
    PERMISSION_DENIED,
    NETWORK_UNAVAILABLE,
    PROTOCOL_ERROR,
    ERROR,
    ;

    val isFailure: Boolean get() = this != SUCCESS

    /** Failures that mean "target is probably fine, we just cannot measure". */
    val isEnvironmental: Boolean
        get() = this == PERMISSION_DENIED || this == NETWORK_UNAVAILABLE
}

/** The concrete mechanism that produced a measurement. Surfaced in the UI for transparency. */
enum class ProbeTransport(val label: String) {
    ICMP_SOCKET("ICMP socket"),
    ICMP_BINARY("system ping"),
    INET_REACHABLE("isReachable"),
    TCP_CONNECT("TCP connect"),
    HTTP_REQUEST("HTTP"),
    DNS_QUERY("DNS"),
    NONE(""),
}

/** Everything a [live.nikro.pinglab.core.net.PingEngine] needs for one probe. */
data class ProbeRequest(
    val target: String,
    val protocol: Protocol = Protocol.ICMP,
    val port: Int? = null,
    val timeoutMs: Int = 2_000,
    val payloadSize: Int = 32,
    val ttl: Int = 64,
    val sequence: Int = 1,
    val preferIpv6: Boolean = false,
    val httpPath: String = "/",
    val validateTls: Boolean = true,
) {
    val effectivePort: Int?
        get() = port ?: protocol.defaultPort
}

/**
 * A single latency sample.
 *
 * [rttMs] is null for every non-successful status; UI code must treat null as a gap in charts,
 * never as zero.
 */
data class ProbeResult(
    val sequence: Int,
    val timestampMs: Long,
    val protocol: Protocol,
    val status: ProbeStatus,
    val rttMs: Double?,
    val hostname: String,
    val resolvedAddress: String? = null,
    val ttl: Int? = null,
    val payloadBytes: Int? = null,
    val transport: ProbeTransport = ProbeTransport.NONE,
    val detail: String? = null,
    val dnsMs: Double? = null,
    val connectMs: Double? = null,
    val tlsMs: Double? = null,
    val firstByteMs: Double? = null,
    val httpStatusCode: Int? = null,
) {
    val isSuccess: Boolean get() = status == ProbeStatus.SUCCESS && rttMs != null

    /** Short line for the console-style log, similar to what `ping` prints. */
    fun toLogLine(): String = when {
        isSuccess -> buildString {
            append(payloadBytes ?: 0)
            append(" bytes from ")
            append(resolvedAddress ?: hostname)
            append(": seq=").append(sequence)
            ttl?.let { append(" ttl=").append(it) }
            append(" time=").append(String.format("%.1f", rttMs)).append(" ms")
        }

        else -> buildString {
            append("seq=").append(sequence).append(' ')
            append(
                when (status) {
                    ProbeStatus.TIMEOUT -> "Request timed out"
                    ProbeStatus.UNREACHABLE -> "Destination unreachable"
                    ProbeStatus.DNS_FAILURE -> "Unknown host"
                    ProbeStatus.TTL_EXPIRED -> "TTL expired in transit"
                    ProbeStatus.PERMISSION_DENIED -> "Permission denied"
                    ProbeStatus.NETWORK_UNAVAILABLE -> "Network unavailable"
                    ProbeStatus.PROTOCOL_ERROR -> "Protocol error"
                    else -> "Failed"
                },
            )
            detail?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
        }
    }

    companion object {
        fun failure(
            request: ProbeRequest,
            status: ProbeStatus,
            detail: String? = null,
            transport: ProbeTransport = ProbeTransport.NONE,
            resolvedAddress: String? = null,
            timestampMs: Long = System.currentTimeMillis(),
        ): ProbeResult = ProbeResult(
            sequence = request.sequence,
            timestampMs = timestampMs,
            protocol = request.protocol,
            status = status,
            rttMs = null,
            hostname = request.target,
            resolvedAddress = resolvedAddress,
            transport = transport,
            detail = detail,
        )
    }
}

/** Aggregated statistics over a window of [ProbeResult]s. */
data class LatencyStats(
    val sent: Int = 0,
    val received: Int = 0,
    val minMs: Double? = null,
    val maxMs: Double? = null,
    val avgMs: Double? = null,
    val medianMs: Double? = null,
    val p90Ms: Double? = null,
    val p95Ms: Double? = null,
    val p99Ms: Double? = null,
    val stdDevMs: Double? = null,
    val meanDeviationJitterMs: Double? = null,
    val rfc3550JitterMs: Double? = null,
    val rFactor: Double? = null,
    val mos: Double? = null,
    val consecutiveFailures: Int = 0,
    val longestOutage: Int = 0,
    val firstTimestampMs: Long? = null,
    val lastTimestampMs: Long? = null,
) {
    val lost: Int get() = (sent - received).coerceAtLeast(0)

    val lossPercent: Double
        get() = if (sent == 0) 0.0 else lost * 100.0 / sent

    val uptimePercent: Double
        get() = if (sent == 0) 0.0 else received * 100.0 / sent

    val hasData: Boolean get() = received > 0

    val durationMs: Long
        get() {
            val first = firstTimestampMs ?: return 0L
            val last = lastTimestampMs ?: return 0L
            return (last - first).coerceAtLeast(0L)
        }

    companion object {
        val EMPTY = LatencyStats()
    }
}

/** Human readable verdict buckets. Ordered from best to worst. */
enum class QualityGrade(val label: String, val shortLabel: String) {
    EXCELLENT("Excellent", "A"),
    GOOD("Good", "B"),
    FAIR("Fair", "C"),
    POOR("Poor", "D"),
    BAD("Bad", "E"),
    UNKNOWN("Unknown", "?"),
}

enum class UseCase(val label: String) {
    GAMING("Gaming"),
    VOICE("Voice calls"),
    VIDEO("Video calls"),
    STREAMING("Streaming"),
    BROWSING("Browsing"),
}

data class UseCaseRating(
    val useCase: UseCase,
    val grade: QualityGrade,
    val reason: String,
)

data class QualityAssessment(
    val grade: QualityGrade,
    val score: Int,
    val headline: String,
    val details: List<String>,
    val useCases: List<UseCaseRating>,
) {
    companion object {
        val UNKNOWN = QualityAssessment(
            grade = QualityGrade.UNKNOWN,
            score = 0,
            headline = "Not enough samples",
            details = emptyList(),
            useCases = emptyList(),
        )
    }
}

/** Live status of a monitored host. */
enum class HostState {
    IDLE,
    CHECKING,
    UP,
    DEGRADED,
    DOWN,
}

/** A user-configured host that can be monitored continuously. */
data class MonitoredHost(
    val id: Long = 0L,
    val label: String,
    val target: String,
    val protocol: Protocol = Protocol.ICMP,
    val port: Int? = null,
    val intervalMs: Long = 5_000L,
    val timeoutMs: Int = 2_000,
    val payloadSize: Int = 32,
    val enabled: Boolean = true,
    val notifyOnDown: Boolean = true,
    val notifyOnRecovery: Boolean = true,
    val failureThreshold: Int = 3,
    val degradedLatencyMs: Int = 150,
    val tag: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val displayTarget: String
        get() = when {
            port != null && protocol.needsPort -> "$target:$port"
            else -> target
        }

    fun toRequest(sequence: Int): ProbeRequest = ProbeRequest(
        target = target,
        protocol = protocol,
        port = port,
        timeoutMs = timeoutMs,
        payloadSize = payloadSize,
        sequence = sequence,
    )
}

/** Everything the dashboard needs to draw one host card. */
data class HostSnapshot(
    val host: MonitoredHost,
    val state: HostState = HostState.IDLE,
    val lastResult: ProbeResult? = null,
    val stats: LatencyStats = LatencyStats.EMPTY,
    val recent: List<ProbeResult> = emptyList(),
    val quality: QualityAssessment = QualityAssessment.UNKNOWN,
    val lastChangeMs: Long = 0L,
)

/** One hop of a traceroute run. */
data class TracerouteHop(
    val ttl: Int,
    val address: String? = null,
    val hostname: String? = null,
    val rttsMs: List<Double?> = emptyList(),
    val isDestination: Boolean = false,
) {
    val isTimeout: Boolean get() = address == null

    val bestRttMs: Double? get() = rttsMs.filterNotNull().minOrNull()

    val avgRttMs: Double?
        get() = rttsMs.filterNotNull().takeIf { it.isNotEmpty() }?.average()

    val displayName: String
        get() = when {
            hostname != null && address != null && hostname != address -> "$hostname ($address)"
            address != null -> address
            else -> "* * *"
        }
}

/** Result of a forward/reverse DNS lookup, including timing. */
data class DnsLookupResult(
    val query: String,
    val addresses: List<String> = emptyList(),
    val canonicalName: String? = null,
    val reverseName: String? = null,
    val durationMs: Double = 0.0,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null && addresses.isNotEmpty()
}

/** What a port scan managed to establish about a single port. */
enum class PortState {
    /** A service proved itself, or the path is trustworthy: really open. */
    OPEN,

    /** The handshake completed, but nothing behind it behaved like a service. */
    ACCEPTED,

    /** The host actively refused the connection (RST). */
    CLOSED,

    /** Nothing came back at all - something is dropping the probe. */
    FILTERED,

    /** The network reported that the host cannot be reached. */
    UNREACHABLE,
}

/** How a port state was proven, so the UI can be honest about confidence. */
enum class PortEvidence {
    BANNER, HTTP, TLS, RESPONSE, SILENT, DROPPED, RESET, NO_REPLY, NOT_CHECKED
}

/** Whether the scan as a whole can be believed. */
enum class ScanTrust {
    /** Random high ports were refused or dropped, so verdicts are reliable. */
    TRUSTED,

    /** One random port answered: could be a real service, could be a middlebox. */
    SUSPICIOUS,

    /** Random high ports answered, so something in the path accepts everything. */
    ACCEPT_ALL,

    UNKNOWN,
}

/** One port from a port-scan sweep. */
data class PortProbe(
    val port: Int,
    val state: PortState,
    val serviceName: String? = null,
    val rttMs: Double? = null,
    val banner: String? = null,
    val evidence: PortEvidence = PortEvidence.NOT_CHECKED,
    val detail: String? = null,
) {
    val isOpen: Boolean get() = state == PortState.OPEN
}

/** Everything a TLS/HTTP endpoint check managed to learn. */
data class TlsReport(
    val host: String,
    val port: Int,
    val address: String? = null,
    val protocol: String? = null,
    val cipherSuite: String? = null,
    val subject: String? = null,
    val issuer: String? = null,
    val sans: List<String> = emptyList(),
    val validFrom: Long? = null,
    val validTo: Long? = null,
    val daysLeft: Long? = null,
    val selfSigned: Boolean = false,
    val hostnameMatches: Boolean? = null,
    val chainLength: Int = 0,
    val chainTrusted: Boolean? = null,
    val handshakeMs: Double? = null,
    val httpStatus: Int? = null,
    val httpServer: String? = null,
    val ttfbMs: Double? = null,
    val redirect: String? = null,
    val hsts: String? = null,
    val error: String? = null,
) {
    val expired: Boolean get() = daysLeft != null && daysLeft < 0
    val expiringSoon: Boolean get() = daysLeft != null && daysLeft in 0..14
}

/** Summary of a completed measurement session, persisted to history. */
data class SessionSummary(
    val id: Long = 0L,
    val target: String,
    val label: String?,
    val protocol: Protocol,
    val startedAt: Long,
    val endedAt: Long,
    val stats: LatencyStats,
    val grade: QualityGrade,
)

/** Snapshot of the active network transport, shown in the dashboard header. */
@Serializable
data class NetworkStatus(
    val isConnected: Boolean = false,
    val transportLabel: String = "Offline",
    val isMetered: Boolean = false,
    val isVpn: Boolean = false,
    val localAddress: String? = null,
    val gateway: String? = null,
    val dnsServers: List<String> = emptyList(),
    val linkDownstreamKbps: Int = 0,
    val linkUpstreamKbps: Int = 0,
) {
    companion object {
        val OFFLINE = NetworkStatus()
    }
}
