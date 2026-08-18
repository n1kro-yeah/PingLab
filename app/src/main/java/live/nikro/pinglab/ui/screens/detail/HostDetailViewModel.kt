package live.nikro.pinglab.ui.screens.detail

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.nikro.pinglab.core.model.LatencyStats
import live.nikro.pinglab.core.model.MonitoredHost
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.QualityAssessment
import live.nikro.pinglab.data.export.ExportManager
import live.nikro.pinglab.di.ServiceLocator
import live.nikro.pinglab.domain.quality.QualityEvaluator
import live.nikro.pinglab.domain.stats.LatencyStatistics

/** Time window selector for the detail charts. */
enum class HistoryRange(val label: String, val millis: Long, val points: Int) {
    HOUR("1h", 60L * 60L * 1_000L, 90),
    SIX_HOURS("6h", 6L * 60L * 60L * 1_000L, 110),
    DAY("24h", 24L * 60L * 60L * 1_000L, 130),
    WEEK("7d", 7L * 24L * 60L * 60L * 1_000L, 150),
}

data class HostDetailUiState(
    val host: MonitoredHost? = null,
    val range: HistoryRange = HistoryRange.HOUR,
    val chartSamples: List<Double?> = emptyList(),
    val recent: List<ProbeResult> = emptyList(),
    val stats: LatencyStats = LatencyStats.EMPTY,
    val quality: QualityAssessment = QualityAssessment.UNKNOWN,
    val histogram: List<LatencyStatistics.HistogramBucket> = emptyList(),
    val trend: LatencyStatistics.Trend = LatencyStatistics.Trend.UNKNOWN,
    val sampleCount: Int = 0,
    val loading: Boolean = true,
    val message: String? = null,
    val pendingExport: ExportManager.Export? = null,
)

/**
 * Detail view for a single monitored host: long-window history from Room, condensed into
 * a chart-sized series, plus the same statistics the live screen shows.
 */
class HostDetailViewModel(private val hostId: Long) : ViewModel() {

    private val hostRepository = ServiceLocator.hostRepository
    private val sampleRepository = ServiceLocator.sampleRepository
    private val exportManager = ServiceLocator.exportManager

    private val _state = MutableStateFlow(HostDetailUiState())
    val state: StateFlow<HostDetailUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        hostRepository.observe(hostId)
            .onEach { host ->
                _state.update { it.copy(host = host) }
                if (host != null) reload()
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_MS)
                reload()
            }
        }
    }

    fun selectRange(range: HistoryRange) {
        _state.update { it.copy(range = range) }
        reload()
    }

    private fun reload() {
        val host = _state.value.host ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val range = _state.value.range
            val since = System.currentTimeMillis() - range.millis
            val results = sampleRepository.since(host.id, host.target, since)
            val recent = sampleRepository.recent(host.id, host.target, RECENT_LOG_SIZE)
            val stats = LatencyStatistics.compute(results)
            val successful = results.mapNotNull { it.rttMs }

            _state.update {
                it.copy(
                    chartSamples = downsample(results, range.points),
                    recent = recent,
                    stats = stats,
                    quality = QualityEvaluator.evaluate(stats),
                    histogram = if (successful.size >= 5) {
                        LatencyStatistics.histogram(successful)
                    } else {
                        emptyList()
                    },
                    trend = LatencyStatistics.trend(successful),
                    sampleCount = results.size,
                    loading = false,
                )
            }
        }
    }

    /**
     * Averages the window into at most [targetPoints] buckets. A bucket that contains only
     * failures stays `null` so the chart keeps showing the outage instead of smoothing it away.
     */
    private fun downsample(results: List<ProbeResult>, targetPoints: Int): List<Double?> {
        if (results.isEmpty()) return emptyList()
        if (results.size <= targetPoints) return results.map { it.rttMs }

        val bucketSize = (results.size + targetPoints - 1) / targetPoints
        return results.chunked(bucketSize).map { chunk ->
            val values = chunk.mapNotNull { it.rttMs }
            if (values.isEmpty()) null else values.average()
        }
    }

    fun toggleEnabled() {
        val host = _state.value.host ?: return
        viewModelScope.launch { hostRepository.setEnabled(host.id, !host.enabled) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            sampleRepository.clearHost(hostId)
            _state.update { it.copy(message = "History cleared") }
            reload()
        }
    }

    fun deleteHost(onDeleted: () -> Unit) {
        viewModelScope.launch {
            hostRepository.delete(hostId)
            onDeleted()
        }
    }

    fun exportCsv() {
        val host = _state.value.host ?: return
        viewModelScope.launch {
            val since = System.currentTimeMillis() - _state.value.range.millis
            val results = sampleRepository.since(host.id, host.target, since)
            if (results.isEmpty()) {
                _state.update { it.copy(message = "Nothing to export yet") }
                return@launch
            }
            runCatching { exportManager.exportCsv(host.target, results) }
                .onSuccess { export -> _state.update { it.copy(pendingExport = export) } }
                .onFailure { error ->
                    _state.update { it.copy(message = "Export failed: " + (error.message ?: "unknown")) }
                }
        }
    }

    fun exportJson() {
        val host = _state.value.host ?: return
        viewModelScope.launch {
            val since = System.currentTimeMillis() - _state.value.range.millis
            val results = sampleRepository.since(host.id, host.target, since)
            if (results.isEmpty()) {
                _state.update { it.copy(message = "Nothing to export yet") }
                return@launch
            }
            runCatching {
                exportManager.exportJson(
                    host = host,
                    target = host.target,
                    results = results,
                    stats = _state.value.stats,
                    quality = _state.value.quality,
                )
            }
                .onSuccess { export -> _state.update { it.copy(pendingExport = export) } }
                .onFailure { error ->
                    _state.update { it.copy(message = "Export failed: " + (error.message ?: "unknown")) }
                }
        }
    }

    fun shareIntentFor(export: ExportManager.Export): Intent = exportManager.shareIntent(export)

    fun consumeExport() {
        _state.update { it.copy(pendingExport = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        private const val REFRESH_MS = 10_000L
        private const val RECENT_LOG_SIZE = 120

        fun factory(hostId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HostDetailViewModel(hostId) as T
        }
    }
}
