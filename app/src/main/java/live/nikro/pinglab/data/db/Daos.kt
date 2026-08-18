package live.nikro.pinglab.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {

    @Query("SELECT * FROM hosts ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC")
    fun observeEnabled(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun enabledHosts(): List<HostEntity>

    @Query("SELECT * FROM hosts ORDER BY sortOrder ASC, id ASC")
    suspend fun allHosts(): List<HostEntity>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun byId(id: Long): HostEntity?

    @Query("SELECT * FROM hosts WHERE id = :id")
    fun observeById(id: Long): Flow<HostEntity?>

    @Query("SELECT COUNT(*) FROM hosts")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM hosts")
    suspend fun nextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: HostEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(hosts: List<HostEntity>): List<Long>

    @Update
    suspend fun update(host: HostEntity)

    @Delete
    suspend fun delete(host: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE hosts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE hosts SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Transaction
    suspend fun reorder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> setSortOrder(id, index) }
    }
}

@Dao
interface SampleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sample: SampleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(samples: List<SampleEntity>)

    @Query(
        "SELECT * FROM samples WHERE hostId = :hostId ORDER BY timestampMs DESC LIMIT :limit",
    )
    fun observeRecent(hostId: Long, limit: Int): Flow<List<SampleEntity>>

    @Query(
        "SELECT * FROM samples WHERE hostId = :hostId ORDER BY timestampMs DESC LIMIT :limit",
    )
    suspend fun recent(hostId: Long, limit: Int): List<SampleEntity>

    @Query(
        "SELECT * FROM samples WHERE hostId = :hostId AND timestampMs >= :sinceMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun since(hostId: Long, sinceMs: Long): List<SampleEntity>

    @Query(
        "SELECT * FROM samples WHERE hostId = :hostId AND timestampMs BETWEEN :fromMs AND :toMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun between(hostId: Long, fromMs: Long, toMs: Long): List<SampleEntity>

    /**
     * Server-side aggregation. Pulling 50k rows into Kotlin just to average them would be
     * both slow and memory hungry, so SQLite does the work.
     */
    @Query(
        "SELECT :hostId AS hostId, " +
            "COUNT(*) AS sampleCount, " +
            "COALESCE(SUM(CASE WHEN rttMs IS NOT NULL THEN 1 ELSE 0 END), 0) AS successCount, " +
            "MIN(rttMs) AS minMs, AVG(rttMs) AS avgMs, MAX(rttMs) AS maxMs, " +
            "MAX(timestampMs) AS lastTimestampMs " +
            "FROM samples WHERE hostId = :hostId AND timestampMs >= :sinceMs",
    )
    suspend fun aggregate(hostId: Long, sinceMs: Long): HostAggregate

    /**
     * Downsamples history into fixed time buckets so a week of data still draws in one frame.
     */
    @Query(
        "SELECT (timestampMs / :bucketMs) * :bucketMs AS bucketStartMs, " +
            "AVG(rttMs) AS avgMs, MIN(rttMs) AS minMs, MAX(rttMs) AS maxMs, " +
            "COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN rttMs IS NULL THEN 1 ELSE 0 END), 0) AS failures " +
            "FROM samples WHERE hostId = :hostId AND timestampMs >= :sinceMs " +
            "GROUP BY bucketStartMs ORDER BY bucketStartMs ASC",
    )
    suspend fun buckets(hostId: Long, sinceMs: Long, bucketMs: Long): List<HistoryBucket>

    @Query("SELECT COUNT(*) FROM samples")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM samples WHERE hostId = :hostId")
    suspend fun countForHost(hostId: Long): Int

    @Query("DELETE FROM samples WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM samples WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: Long)

    @Query("DELETE FROM samples")
    suspend fun deleteAll()

    /**
     * Keeps only the newest [keep] rows per host. Runs after every retention sweep so a very
     * chatty host cannot crowd out the others.
     */
    @Query(
        "DELETE FROM samples WHERE hostId = :hostId AND id NOT IN " +
            "(SELECT id FROM samples WHERE hostId = :hostId ORDER BY timestampMs DESC LIMIT :keep)",
    )
    suspend fun trimHost(hostId: Long, keep: Int): Int
}

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
