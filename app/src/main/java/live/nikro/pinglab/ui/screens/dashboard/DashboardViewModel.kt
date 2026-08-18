package live.nikro.pinglab.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.nikro.pinglab.core.model.HostSnapshot
import live.nikro.pinglab.core.model.HostState
import live.nikro.pinglab.core.model.LatencyStats
import live.nikro.pinglab.core.model.MonitoredHost
import live.nikro.pinglab.core.model.NetworkDetail
import live.nikro.pinglab.core.model.NetworkStatus
import live.nikro.pinglab.data.prefs.AppSettings
import live.nikro.pinglab.di.ServiceLocator
import live.nikro.pinglab.domain.stats.HostUptime
import live.nikro.pinglab.domain.stats.LatencyStatistics
import live.nikro.pinglab.domain.stats.UptimeAnalyzer
import live.nikro.pinglab.domain.stats.UptimeDigest
import live.nikro.pinglab.service.PingMonitorService

/** One row on the dashboard: the configured host plus whatever we know about it right now. */
data class HostCardState(
    val host: MonitoredHost,
    val state: HostState = HostState.IDLE,
    val stats: LatencyStats = LatencyStats.EMPTY,
    val samples: List<Double?> = emptyList(),
    val lastCheckMs: Long = 0L,
) {
    val isLive: Boolean get() = state == HostState.UP || state == HostState.DEGRADED
}

data class DashboardUiState(
    val cards: List<HostCardState> = emptyList(),
    val monitoring: Boolean = false,
    val network: NetworkStatus = NetworkStatus.OFFLINE,
    val networkDetail: NetworkDetail = NetworkDetail.NONE,
    val settings: AppSettings = AppSettings(),
    val storedSamples: Int = 0,
    val uptime: UptimeDigest = UptimeDigest(),
    val loading: Boolean = true,
) {
    val upCount: Int get() = cards.count { it.state == HostState.UP }
    val downCount: Int get() = cards.count { it.state == HostState.DOWN }
    val degradedCount: Int get() = cards.count { it.state == HostState.DEGRADED }
}

/**
 * Dashboard state. Two sources feed it: the database (history that survives restarts) and,
 * when the foreground service runs, live snapshots from the monitor engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel : ViewModel() {

    private val hostRepository = ServiceLocator.hostRepository
    private val sampleRepository = ServiceLocator.sampleRepository
    private val settingsRepository = ServiceLocator.settingsRepository
    private val networkInspector = ServiceLocator.networkInspector

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var lastUptimeAtMs = 0L

    init {
        hostRepository.hosts
            .onEach { hosts -> loadFromDatabase(hosts) }
            .launchIn(viewModelScope)

        settingsRepository.settings
            .onEach { settings -> _state.update { it.copy(settings = settings) } }
            .launchIn(viewModelScope)

        networkInspector.observe()
            .onEach { status -> _state.update { it.copy(network = status) } }
            .launchIn(viewModelScope)

        // Which radio is doing the work: LTE/5G technology, operator and bars on mobile data,
        // band and link speed on Wi-Fi. Separate stream because it also listens to telephony.
        networkInspector.observeDetail()
            .onEach { detail -> _state.update { it.copy(networkDetail = detail) } }
            .launchIn(viewModelScope)

        PingMonitorService.isRunning
            .onEach { running -> _state.update { it.copy(monitoring = running) } }
            .launchIn(viewModelScope)

        // Live snapshots override the stored numbers while the service is up.
        PingMonitorService.isRunning
            .flatMapLatest { running ->
                val engine = PingMonitorService.activeEngine
                if (running && engine != null) engine.snapshots else flowOf(emptyMap())
            }
            .onEach { snapshots -> applySnapshots(snapshots) }
            .launchIn(viewModelScope)

        // Slow refresh so idle hosts still show fresh history without hammering the DB.
        viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                loadFromDatabase(_state.value.cards.map { it.host })
            }
        }
    }

    private suspend fun loadFromDatabase(hosts: List<MonitoredHost>) {
        if (hosts.isEmpty()) {
            _state.update { it.copy(cards = emptyList(), loading = false) }
            return
        }
        val live = PingMonitorService.activeEngine?.snapshots?.value.orEmpty()
        val cards = hosts.map { host ->
            val snapshot = live[host.id]
            if (snapshot != null) {
                snapshot.toCard()
            } else {
                val recent = sampleRepository.recent(host.id, host.target, SPARKLINE_POINTS)
                HostCardState(
                    host = host,
                    state = if (host.enabled) HostState.IDLE else HostState.IDLE,
                    stats = LatencyStatistics.compute(recent),
                    samples = recent.map { it.rttMs },
                    lastCheckMs = recent.lastOrNull()?.timestampMs ?: 0L,
                )
            }
        }
        val stored = runCatching { sampleRepository.totalSamples() }.getOrDefault(0)
        _state.update { it.copy(cards = cards, storedSamples = stored, loading = false) }
        refreshUptime(hosts)
    }

    /**
     * Availability is derived from a full day of samples per host, which is far too much work to
     * redo on every 15 second tick, so it is recomputed at most once a minute - and immediately
     * when the user asks for a refresh.
     */
    private suspend fun refreshUptime(hosts: List<MonitoredHost>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUptimeAtMs < UPTIME_REFRESH_MS) return
        lastUptimeAtMs = now
        val since = now - UPTIME_WINDOW_MS
        val reports = hosts.map { host ->
            val samples = runCatching { sampleRepository.since(host.id, host.target, since) }
                .getOrDefault(emptyList())
            HostUptime(
                hostId = host.id,
                label = host.label,
                report = UptimeAnalyzer.analyze(
                    results = samples,
                    minConsecutiveFailures = MIN_FAILURES_FOR_OUTAGE,
                ),
            )
        }
        _state.update {
            it.copy(uptime = UptimeDigest(windowMs = UPTIME_WINDOW_MS, hosts = reports))
        }
    }

    private fun applySnapshots(snapshots: Map<Long, HostSnapshot>) {
        if (snapshots.isEmpty()) return
        _state.update { current ->
            current.copy(
                cards = current.cards.map { card ->
                    snapshots[card.host.id]?.toCard() ?: card
                },
            )
        }
    }

    private fun HostSnapshot.toCard(): HostCardState = HostCardState(
        host = host,
        state = state,
        stats = stats,
        samples = recent.map { it.rttMs },
        lastCheckMs = lastResult?.timestampMs ?: 0L,
    )

    fun toggleMonitoring(context: Context) {
        if (_state.value.monitoring) {
            PingMonitorService.stop(context)
        } else {
            PingMonitorService.start(context)
        }
    }

    fun setHostEnabled(hostId: Long, enabled: Boolean) {
        viewModelScope.launch { hostRepository.setEnabled(hostId, enabled) }
    }

    fun refreshNow() {
        viewModelScope.launch {
            val hosts = _state.value.cards.map { it.host }
            loadFromDatabase(hosts)
            refreshUptime(hosts, force = true)
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 15_000L
        const val SPARKLINE_POINTS = 60
        const val UPTIME_WINDOW_MS = 24L * 60L * 60L * 1_000L
        const val UPTIME_REFRESH_MS = 60_000L

        /** One lost probe is noise; two in a row at monitoring cadence is an outage. */
        const val MIN_FAILURES_FOR_OUTAGE = 2
    }
}
