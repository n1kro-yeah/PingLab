package live.nikro.pinglab.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import live.nikro.pinglab.core.model.LatencyStats
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.SessionSummary
import live.nikro.pinglab.data.db.HistoryBucket
import live.nikro.pinglab.data.db.HostAggregate
import live.nikro.pinglab.data.db.SampleDao
import live.nikro.pinglab.data.db.SampleEntity
import live.nikro.pinglab.data.db.SessionDao
import live.nikro.pinglab.data.db.SessionEntity
import live.nikro.pinglab.domain.stats.LatencyStatistics

/**
 * Persistence for latency samples and finished sessions.
 *
 * Writes are deliberately cheap: monitoring 10 hosts at 1 s produces ~864k rows/day, so the
 * repository batches inserts and enforces both a time-based and a per-host row cap.
 */
class SampleRepository(
    private val sampleDao: SampleDao,
    private val sessionDao: SessionDao,
) {

    /** Buffered writes: a monitoring tick should not wait on the disk. */
    private val pending = ArrayList<SampleEntity>(BATCH_SIZE)
    private val lock = Any()

    suspend fun record(hostId: Long, result: ProbeResult, flush: Boolean = false) {
        val entity = SampleEntity.fromDomain(hostId, result)
        val batch = synchronized(lock) {
            pending += entity
            if (pending.size >= BATCH_SIZE || flush) {
                val copy = pending.toList()
                pending.clear()
                copy
            } else {
                null
            }
        }
        if (batch != null) sampleDao.insertAll(batch)
    }

    /** Forces any buffered samples to disk. Called when monitoring stops. */
    suspend fun flush() {
        val batch = synchronized(lock) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        sampleDao.insertAll(batch)
    }

    fun observeRecent(hostId: Long, hostname: String, limit: Int = 200): Flow<List<ProbeResult>> =
        sampleDao.observeRecent(hostId, limit).map { rows ->
            rows.asReversed().map { it.toDomain(hostname) }
        }

    suspend fun recent(hostId: Long, hostname: String, limit: Int = 200): List<ProbeResult> =
        sampleDao.recent(hostId, limit).asReversed().map { it.toDomain(hostname) }

    suspend fun since(hostId: Long, hostname: String, sinceMs: Long): List<ProbeResult> =
        sampleDao.since(hostId, sinceMs).map { it.toDomain(hostname) }

    suspend fun between(hostId: Long, hostname: String, fromMs: Long, toMs: Long): List<ProbeResult> =
        sampleDao.between(hostId, fromMs, toMs).map { it.toDomain(hostname) }

    suspend fun statsSince(hostId: Long, hostname: String, sinceMs: Long): LatencyStats =
        LatencyStatistics.compute(since(hostId, hostname, sinceMs))

    suspend fun aggregate(hostId: Long, sinceMs: Long): HostAggregate =
        sampleDao.aggregate(hostId, sinceMs)

    /**
     * Downsampled series for the history chart. The bucket size adapts to the range so the
     * chart always gets roughly [targetPoints] points regardless of the zoom level.
     */
    suspend fun history(
        hostId: Long,
        sinceMs: Long,
        targetPoints: Int = 120,
    ): List<HistoryBucket> {
        val span = (System.currentTimeMillis() - sinceMs).coerceAtLeast(1_000L)
        val bucketMs = (span / targetPoints.coerceAtLeast(10)).coerceAtLeast(1_000L)
        return sampleDao.buckets(hostId, sinceMs, bucketMs)
    }

    suspend fun countForHost(hostId: Long): Int = sampleDao.countForHost(hostId)

    suspend fun totalSamples(): Int = sampleDao.totalCount()

    suspend fun clearHost(hostId: Long) = sampleDao.deleteForHost(hostId)

    suspend fun clearAll() {
        synchronized(lock) { pending.clear() }
        sampleDao.deleteAll()
    }

    /**
     * Retention sweep: drop anything older than the cutoff, then cap each host.
     * Returns how many rows disappeared, which the settings screen displays.
     */
    suspend fun applyRetention(cutoffMs: Long, maxPerHost: Int, hostIds: List<Long>): Int {
        var removed = sampleDao.deleteOlderThan(cutoffMs)
        hostIds.forEach { hostId ->
            removed += sampleDao.trimHost(hostId, maxPerHost)
        }
        return removed
    }

    // --- Sessions -------------------------------------------------------------------

    fun observeSessions(limit: Int = 50): Flow<List<SessionSummary>> =
        sessionDao.observeRecent(limit).map { rows -> rows.map(SessionEntity::toDomain) }

    suspend fun saveSession(summary: SessionSummary): Long =
        sessionDao.insert(SessionEntity.fromDomain(summary))

    suspend fun sessions(limit: Int = 50): List<SessionSummary> =
        sessionDao.recent(limit).map(SessionEntity::toDomain)

    suspend fun deleteSession(id: Long) = sessionDao.deleteById(id)

    suspend fun clearSessions() = sessionDao.deleteAll()

    companion object {
        private const val BATCH_SIZE = 20
    }
}
