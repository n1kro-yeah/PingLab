package live.nikro.pinglab.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import live.nikro.pinglab.core.model.MonitoredHost
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.data.db.HostDao
import live.nikro.pinglab.data.db.HostEntity

/**
 * Single source of truth for the user's host list.
 *
 * Keeps Room types out of the rest of the app: everything above this layer speaks
 * [MonitoredHost].
 */
class HostRepository(private val hostDao: HostDao) {

    val hosts: Flow<List<MonitoredHost>> =
        hostDao.observeAll().map { list -> list.map(HostEntity::toDomain) }

    val enabledHosts: Flow<List<MonitoredHost>> =
        hostDao.observeEnabled().map { list -> list.map(HostEntity::toDomain) }

    fun observe(id: Long): Flow<MonitoredHost?> =
        hostDao.observeById(id).map { it?.toDomain() }

    suspend fun byId(id: Long): MonitoredHost? = hostDao.byId(id)?.toDomain()

    suspend fun all(): List<MonitoredHost> = hostDao.allHosts().map(HostEntity::toDomain)

    suspend fun enabled(): List<MonitoredHost> = hostDao.enabledHosts().map(HostEntity::toDomain)

    suspend fun upsert(host: MonitoredHost): Long {
        val prepared = if (host.id == 0L && host.sortOrder == 0) {
            host.copy(sortOrder = hostDao.nextSortOrder())
        } else {
            host
        }
        return if (prepared.id == 0L) {
            hostDao.insert(HostEntity.fromDomain(prepared))
        } else {
            hostDao.update(HostEntity.fromDomain(prepared))
            prepared.id
        }
    }

    suspend fun delete(id: Long) = hostDao.deleteById(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = hostDao.setEnabled(id, enabled)

    suspend fun reorder(orderedIds: List<Long>) = hostDao.reorder(orderedIds)

    suspend fun count(): Int = hostDao.count()

    /**
     * First-run content. An empty dashboard teaches the user nothing, so we seed a set of
     * well-known endpoints that between them exercise every protocol the app supports.
     */
    suspend fun seedDefaultsIfEmpty(): Boolean {
        if (hostDao.count() > 0) return false
        hostDao.insertAll(DEFAULT_HOSTS.mapIndexed { index, host ->
            HostEntity.fromDomain(host.copy(sortOrder = index))
        })
        return true
    }

    companion object {
        val DEFAULT_HOSTS: List<MonitoredHost> = listOf(
            MonitoredHost(
                label = "Google DNS",
                target = "8.8.8.8",
                protocol = Protocol.ICMP,
                intervalMs = 5_000L,
                tag = "Internet",
                degradedLatencyMs = 120,
            ),
            MonitoredHost(
                label = "Cloudflare DNS",
                target = "1.1.1.1",
                protocol = Protocol.ICMP,
                intervalMs = 5_000L,
                tag = "Internet",
                degradedLatencyMs = 120,
            ),
            MonitoredHost(
                label = "Cloudflare (HTTPS)",
                target = "cloudflare.com",
                protocol = Protocol.HTTPS,
                port = 443,
                intervalMs = 15_000L,
                tag = "Web",
                degradedLatencyMs = 600,
            ),
            MonitoredHost(
                label = "Quad9 resolver",
                target = "9.9.9.9",
                protocol = Protocol.DNS,
                port = 53,
                intervalMs = 15_000L,
                tag = "DNS",
                enabled = false,
                degradedLatencyMs = 200,
            ),
        )
    }
}
