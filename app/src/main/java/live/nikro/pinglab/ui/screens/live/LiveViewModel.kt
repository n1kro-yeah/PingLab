package live.nikro.pinglab.ui.screens.live

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.nikro.pinglab.core.model.LatencyStats
import live.nikro.pinglab.core.model.MonitoredHost
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.core.model.QualityAssessment
import live.nikro.pinglab.core.model.SessionSummary
import live.nikro.pinglab.core.util.HostValidator
import live.nikro.pinglab.core.util.TargetValidation
import live.nikro.pinglab.data.export.ExportManager
import live.nikro.pinglab.data.prefs.AppSettings
import live.nikro.pinglab.di.ServiceLocator
import live.nikro.pinglab.domain.monitor.LivePingSession
import live.nikro.pinglab.domain.quality.QualityEvaluator
import live.nikro.pinglab.domain.stats.LatencyStatistics

/** Everything the live screen renders, in one immutable snapshot. */
data class LiveUiState(
    val target: String = "",
    val protocol: Protocol = Protocol.ICMP,
    val port: String = "",
    val intervalMs: Long = 1_000L,
    val isRunning: Boolean = false,
    val results: List<ProbeResult> = emptyList(),
    val stats: LatencyStats = LatencyStats.EMPTY,
    val quality: QualityAssessment = QualityAssessment.UNKNOWN,
    val settings: AppSettings = AppSettings(),
    val validationMessage: String? = null,
    val message: String? = null,
    val pendingExport: ExportManager.Export? = null,
) {
    /** Nulls mark lost packets so the chart can draw a real gap. */
    val chartSamples: List<Double?> get() = results.map { it.rttMs }

    val resolvedAddress: String?
        get() = results.lastOrNull { it.resolvedAddress != null }?.resolvedAddress

    val transportLabel: String
        get() {
            val transport = results.lastOrNull()?.transport?.label.orEmpty()
            val protocolLabel = protocol.label
            return if (transport.isEmpty()) protocolLabel else protocolLabel + " via " + transport
        }
}

/**
 * Owns the live probe loop. The loop itself lives in [LivePingSession]; this class only
 * decides when it runs and folds the resulting stream into UI state.
 */
class LiveViewModel : ViewModel() {

    private val session: LivePingSession = ServiceLocator.livePingSession
    private val settingsRepository = ServiceLocator.settingsRepository
    private val hostRepository = ServiceLocator.hostRepository
    private val sampleRepository = ServiceLocator.sampleRepository
    private val exportManager = ServiceLocator.exportManager

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private var probeJob: Job? = null
    private var sessionStartedAt: Long = 0L
    private var userTouchedInterval = false

    init {
        settingsRepository.settings
            .onEach { settings ->
                _state.update { current ->
                    current.copy(
                        settings = settings,
                        protocol = if (current.results.isEmpty() && !current.isRunning) {
                            settings.defaultProtocol
                        } else {
                            current.protocol
                        },
                        intervalMs = if (userTouchedInterval) current.intervalMs else settings.defaultIntervalMs,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun onTargetChange(value: String) {
        _state.update { it.copy(target = value, validationMessage = null) }
    }

    fun onProtocolChange(protocol: Protocol) {
        _state.update { current ->
            val port = when {
                current.port.isNotBlank() -> current.port
                protocol.defaultPort != null -> protocol.defaultPort.toString()
                else -> ""
            }
            current.copy(protocol = protocol, port = port)
        }
    }

    fun onPortChange(value: String) {
        if (value.all { it.isDigit() } && value.length <= 5) {
            _state.update { it.copy(port = value) }
        }
    }

    /** Cycles through the intervals people actually use. */
    fun cycleInterval() {
        userTouchedInterval = true
        _state.update { current ->
            val next = when (current.intervalMs) {
                250L -> 500L
                500L -> 1_000L
                1_000L -> 2_000L
                2_000L -> 5_000L
                5_000L -> 250L
                else -> 1_000L
            }
            current.copy(intervalMs = next)
        }
        if (_state.value.isRunning) restart()
    }

    fun toggle() {
        if (_state.value.isRunning) stop() else start()
    }

    private fun restart() {
        stop()
        start()
    }

    fun start() {
        val current = _state.value
        when (val validation = HostValidator.validate(current.target)) {
            is TargetValidation.Invalid -> {
                _state.update { it.copy(validationMessage = HostValidator.describe(validation.reason)) }
                return
            }

            is TargetValidation.Valid -> {
                val parsed = validation.target
                val port = current.port.toIntOrNull() ?: parsed.port ?: current.protocol.defaultPort
                val config = LivePingSession.Config(
                    target = parsed.host,
                    protocol = current.protocol,
                    port = port,
                    intervalMs = current.intervalMs,
                    timeoutMs = current.settings.defaultTimeoutMs,
                    payloadSize = current.settings.defaultPayloadSize,
                    preferIpv6 = current.settings.preferIpv6,
                )

                sessionStartedAt = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        isRunning = true,
                        validationMessage = null,
                        results = emptyList(),
                        stats = LatencyStats.EMPTY,
                        quality = QualityAssessment.UNKNOWN,
                    )
                }

                probeJob?.cancel()
                probeJob = session.stream(config)
                    .onEach { result -> append(result) }
                    .launchIn(viewModelScope)
            }
        }
    }

    fun stop() {
        probeJob?.cancel()
        probeJob = null
        _state.update { it.copy(isRunning = false) }
        persistSession()
    }

    fun clear() {
        _state.update {
            it.copy(
                results = emptyList(),
                stats = LatencyStats.EMPTY,
                quality = QualityAssessment.UNKNOWN,
            )
        }
    }

    private fun append(result: ProbeResult) {
        _state.update { current ->
            // Cap the buffer: an all-night session must not grow without bound.
            val results = (current.results + result).let { list ->
                if (list.size > MAX_BUFFER) list.takeLast(MAX_BUFFER) else list
            }
            val stats = LatencyStatistics.compute(results)
            current.copy(
                results = results,
                stats = stats,
                quality = QualityEvaluator.evaluate(stats),
            )
        }
    }

    private fun persistSession() {
        val current = _state.value
        if (current.results.size < MIN_SESSION_SAMPLES) return
        val startedAt = sessionStartedAt.takeIf { it > 0L } ?: return
        viewModelScope.launch {
            sampleRepository.saveSession(
                SessionSummary(
                    target = current.target,
                    label = current.target,
                    protocol = current.protocol,
                    startedAt = startedAt,
                    endedAt = System.currentTimeMillis(),
                    stats = current.stats,
                    grade = current.quality.grade,
                )
            )
        }
    }

    fun saveAsMonitoredHost() {
        val current = _state.value
        val validation = HostValidator.validate(current.target)
        if (validation !is TargetValidation.Valid) {
            _state.update { it.copy(validationMessage = "Enter a valid host first") }
            return
        }
        viewModelScope.launch {
            hostRepository.upsert(
                MonitoredHost(
                    label = validation.target.host,
                    target = validation.target.host,
                    protocol = current.protocol,
                    port = current.port.toIntOrNull() ?: validation.target.port,
                    intervalMs = current.intervalMs.coerceAtLeast(5_000L),
                    timeoutMs = current.settings.defaultTimeoutMs,
                    payloadSize = current.settings.defaultPayloadSize,
                )
            )
            _state.update { it.copy(message = "Added to monitored hosts") }
        }
    }

    fun exportCsv() {
        val current = _state.value
        if (current.results.isEmpty()) return
        viewModelScope.launch {
            runCatching { exportManager.exportCsv(current.target, current.results) }
                .onSuccess { export -> _state.update { it.copy(pendingExport = export) } }
                .onFailure { error ->
                    _state.update { it.copy(message = "Export failed: " + (error.message ?: "unknown")) }
                }
        }
    }

    fun exportJson() {
        val current = _state.value
        if (current.results.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                exportManager.exportJson(
                    host = null,
                    target = current.target,
                    results = current.results,
                    stats = current.stats,
                    quality = current.quality,
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

    override fun onCleared() {
        probeJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_BUFFER = 600
        const val MIN_SESSION_SAMPLES = 5
    }
}
