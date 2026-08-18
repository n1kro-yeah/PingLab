package live.nikro.pinglab.ui.screens.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.nikro.pinglab.data.prefs.AppSettings
import live.nikro.pinglab.data.prefs.ThemeMode
import live.nikro.pinglab.data.prefs.ThemePalette
import live.nikro.pinglab.di.ServiceLocator

data class ThemeUiState(
    val settings: AppSettings = AppSettings(),
)

/**
 * State holder for the Theme tab.
 *
 * Nothing is cached locally: every switch renders from the settings Flow, so the preview on
 * this screen and the rest of the app can never disagree about which palette is active.
 */
class ThemeViewModel : ViewModel() {

    private val settingsRepository = ServiceLocator.settingsRepository

    private val _state = MutableStateFlow(ThemeUiState())
    val state: StateFlow<ThemeUiState> = _state.asStateFlow()

    init {
        settingsRepository.settings
            .onEach { settings -> _state.update { it.copy(settings = settings) } }
            .launchIn(viewModelScope)
    }

    /** Also clears dynamic colour, otherwise the wallpaper would keep overriding the choice. */
    fun selectPalette(palette: ThemePalette) {
        viewModelScope.launch { settingsRepository.selectPalette(palette) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setAmoledBlack(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAmoledBlack(enabled) }
    }
}
