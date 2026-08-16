package live.nikro.pinglab.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.data.prefs.AppSettings
import live.nikro.pinglab.data.prefs.ChartStyle
import live.nikro.pinglab.data.prefs.ThemeMode
import live.nikro.pinglab.di.ServiceLocator

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val storedSamples: Int = 0,
    val hostCount: Int = 0,
    val message: String? = null,
)

/**
 * Thin binding layer over [live.nikro.pinglab.data.prefs.SettingsRepository].
 *
 * Every setter fires and forgets: DataStore writes are transactional, and the UI re-renders
 * from the settings Flow rather than from local state, so a failed write can never leave the
 * switch and the stored preference out of sync.
 */
class SettingsViewModel : ViewModel() {

    private val settingsRepository = ServiceLocator.settingsRepository
    private val sampleRepository = ServiceLocator.sampleRepository
    private val hostRepository = ServiceLocator.hostRepository

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        settingsRepository.settings
            .onEach { settings -> _state.update { it.copy(settings = settings) } }
            .launchIn(viewModelScope)

        hostRepository.hosts
            .onEach { hosts -> _state.update { it.copy(hostCount = hosts.size) } }
            .launchIn(viewModelScope)

        refreshStorageStats()
    }

    private fun refreshStorageStats() {
        viewModelScope.launch {
            val total = runCatching { sampleRepository.totalSamples() }.getOrDefault(0)
            _state.update { it.copy(storedSamples = total) }
        }
    }

    // ---------------------------------------------------------------- appearance

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun setChartStyle(style: ChartStyle) {
        viewModelScope.launch { settingsRepository.setChartStyle(style) }
    }

    fun setShowGrid(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowGrid(enabled) }
    }

    fun setAnimateCharts(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAnimateCharts(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenOn(enabled) }
    }

    // ------------------------------------------------------------------- probing

    fun setDefaultProtocol(protocol: Protocol) {
        viewModelScope.launch { settingsRepository.setDefaultProtocol(protocol) }
    }

    fun setDefaultInterval(intervalMs: Long) {
        viewModelScope.launch { settingsRepository.setDefaultInterval(intervalMs) }
    }

    fun setDefaultTimeout(timeoutMs: Int) {
        viewModelScope.launch { settingsRepository.setDefaultTimeout(timeoutMs) }
    }

    fun setDefaultPayloadSize(size: Int) {
        viewModelScope.launch { settingsRepository.setDefaultPayloadSize(size) }
    }

    fun setLiveWindowSize(size: Int) {
        viewModelScope.launch { settingsRepository.setLiveWindowSize(size) }
    }

    fun setPreferIpv6(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPreferIpv6(enabled) }
    }

    // -------------------------------------------------------------------- alerts

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }
    }

    fun setAlertSound(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAlertSound(enabled) }
    }

    fun setAutoStartMonitoring(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoStartMonitoring(enabled) }
    }

    // ------------------------------------------------------------------- storage

    fun setRetentionDays(days: Int) {
        viewModelScope.launch { settingsRepository.setRetentionDays(days) }
    }

    fun setMaxSamplesPerHost(count: Int) {
        viewModelScope.launch { settingsRepository.setMaxSamplesPerHost(count) }
    }

    /** Applies the current retention window immediately instead of waiting for the service. */
    fun applyRetentionNow() {
        viewModelScope.launch {
            val settings = _state.value.settings
            val cutoff = System.currentTimeMillis() - settings.retentionMs
            val hostIds = runCatching { hostRepository.all().map { it.id } }.getOrDefault(emptyList())
            val removed = runCatching {
                sampleRepository.applyRetention(cutoff, settings.maxSamplesPerHost, hostIds)
            }.getOrDefault(0)
            _state.update { it.copy(message = "Pruned " + removed + " samples") }
            refreshStorageStats()
        }
    }

    fun clearSamples() {
        viewModelScope.launch {
            runCatching { sampleRepository.clearAll() }
                .onSuccess {
                    _state.update { it.copy(message = "Stored samples deleted") }
                    refreshStorageStats()
                }
                .onFailure { error ->
                    _state.update { it.copy(message = "Delete failed: " + (error.message ?: "unknown")) }
                }
        }
    }

    fun clearSessions() {
        viewModelScope.launch {
            runCatching { sampleRepository.clearSessions() }
                .onSuccess { _state.update { it.copy(message = "Saved sessions deleted") } }
                .onFailure { error ->
                    _state.update { it.copy(message = "Delete failed: " + (error.message ?: "unknown")) }
                }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            settingsRepository.resetToDefaults()
            _state.update { it.copy(message = "Settings restored to defaults") }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
