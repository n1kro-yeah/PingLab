package live.nikro.pinglab.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import live.nikro.pinglab.core.model.LatencyStats
import live.nikro.pinglab.core.model.MonitoredHost
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.ProbeTransport
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.core.model.QualityGrade
import live.nikro.pinglab.core.model.SessionSummary

@Entity(
    tableName = "hosts",
    indices = [Index(value = ["sortOrder"]), Index(value = ["enabled"])],
)
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val label: String,
    val target: String,
    val protocol: Protocol,
    val port: Int?,
    val intervalMs: Long,
    val timeoutMs: Int,
    val payloadSize: Int,
    val enabled: Boolean,
    val notifyOnDown: Boolean,
    val notifyOnRecovery: Boolean,
    val failureThreshold: Int,
    val degradedLatencyMs: Int,
    val tag: String?,
    val sortOrder: Int,
    val createdAt: Long,
) {
    fun toDomain(): MonitoredHost = MonitoredHost(
        id = id,
        label = label,
        target = target,
        protocol = protocol,
        port = port,
        intervalMs = intervalMs,
        timeoutMs = timeoutMs,
        payloadSize = payloadSize,
        enabled = enabled,
        notifyOnDown = notifyOnDown,
        notifyOnRecovery = notifyOnRecovery,
        failureThreshold = failureThreshold,
        degradedLatencyMs = degradedLatencyMs,
        tag = tag,
        sortOrder = sortOrder,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(host: MonitoredHost): HostEntity = HostEntity(
            id = host.id,
            label = host.label,
            target = host.target,
            protocol = host.protocol,
            port = host.port,
            intervalMs = host.intervalMs,
            timeoutMs = host.timeoutMs,
            payloadSize = host.payloadSize,
            enabled = host.enabled,
            notifyOnDown = host.notifyOnDown,
            notifyOnRecovery = host.notifyOnRecovery,
            failureThreshold = host.failureThreshold,
            degradedLatencyMs = host.degradedLatencyMs,
            tag = host.tag,
            sortOrder = host.sortOrder,
            createdAt = host.createdAt,
        )
    }
}

/**
 * One stored latency sample.
 *
 * Indexed on `(hostId, timestampMs)` because every query is "the last N samples for this
 * host" \u2014 without the composite index Room would scan the whole table on each chart redraw.
 * Rows are pruned by [live.nikro.pinglab.data.repo.SampleRepository] according to the
 * retention setting.
 */
@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["hostId", "timestampMs"]), Index(value = ["timestampMs"])],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hostId: Long,
    val sequence: Int,
    val timestampMs: Long,
    val protocol: Protocol,
    val status: ProbeStatus,
    @ColumnInfo(name = "rttMs") val rttMs: Double?,
    val resolvedAddress: String?,
    val ttl: Int?,
    val transport: ProbeTransport,
    val detail: String?,
) {
    fun toDomain(hostname: String): ProbeResult = ProbeResult(
        sequence = sequence,
        timestampMs = timestampMs,
        protocol = protocol,
        status = status,
        rttMs = rttMs,
        hostname = hostname,
        resolvedAddress = resolvedAddress,
        ttl = ttl,
        transport = transport,
        detail = detail,
    )

    companion object {
        fun fromDomain(hostId: Long, result: ProbeResult): SampleEntity = SampleEntity(
            hostId = hostId,
            sequence = result.sequence,
            timestampMs = result.timestampMs,
            protocol = result.protocol,
            status = result.status,
            rttMs = result.rttMs,
            resolvedAddress = result.resolvedAddress,
            ttl = result.ttl,
            transport = result.transport,
            detail = result.detail,
        )
    }
}

/** A finished measurement run, kept for the history screen. */
@Entity(tableName = "sessions", indices = [Index(value = ["startedAt"])])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val target: String,
    val label: String?,
    val protocol: Protocol,
    val startedAt: Long,
    val endedAt: Long,
    val sent: Int,
    val received: Int,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val medianMs: Double?,
    val p95Ms: Double?,
    val jitterMs: Double?,
    val stdDevMs: Double?,
    val mos: Double?,
    val longestOutage: Int,
    val grade: QualityGrade,
) {
    fun toDomain(): SessionSummary = SessionSummary(
        id = id,
        target = target,
        label = label,
        protocol = protocol,
        startedAt = startedAt,
        endedAt = endedAt,
        stats = LatencyStats(
            sent = sent,
            received = received,
            minMs = minMs,
            maxMs = maxMs,
            avgMs = avgMs,
            medianMs = medianMs,
            p95Ms = p95Ms,
            stdDevMs = stdDevMs,
            rfc3550JitterMs = jitterMs,
            mos = mos,
            longestOutage = longestOutage,
            firstTimestampMs = startedAt,
            lastTimestampMs = endedAt,
        ),
        grade = grade,
    )

    companion object {
        fun fromDomain(summary: SessionSummary): SessionEntity = SessionEntity(
            id = summary.id,
            target = summary.target,
            label = summary.label,
            protocol = summary.protocol,
            startedAt = summary.startedAt,
            endedAt = summary.endedAt,
            sent = summary.stats.sent,
            received = summary.stats.received,
            minMs = summary.stats.minMs,
            avgMs = summary.stats.avgMs,
            maxMs = summary.stats.maxMs,
            medianMs = summary.stats.medianMs,
            p95Ms = summary.stats.p95Ms,
            jitterMs = summary.stats.rfc3550JitterMs,
            stdDevMs = summary.stats.stdDevMs,
            mos = summary.stats.mos,
            longestOutage = summary.stats.longestOutage,
            grade = summary.grade,
        )
    }
}

/** Lightweight aggregate returned by the history queries. */
data class HostAggregate(
    val hostId: Long,
    val sampleCount: Int,
    val successCount: Int,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val lastTimestampMs: Long?,
)

/** One point of the downsampled history chart. */
data class HistoryBucket(
    val bucketStartMs: Long,
    val avgMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val total: Int,
    val failures: Int,
)
