package live.nikro.pinglab.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import live.nikro.pinglab.core.model.Protocol
import java.io.IOException

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class ChartStyle(val label: String) {
    LINE("Line"),
    AREA("Area"),
    BARS("Bars"),
}

/** Everything the user can tune. Immutable snapshot, emitted as a single stream. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val chartStyle: ChartStyle = ChartStyle.AREA,
    val showGrid: Boolean = true,
    val animateCharts: Boolean = true,
    val defaultProtocol: Protocol = Protocol.ICMP,
    val defaultIntervalMs: Long = 1_000L,
    val defaultTimeoutMs: Int = 2_000,
    val defaultPayloadSize: Int = 32,
    val preferIpv6: Boolean = false,
    val keepScreenOn: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val alertSound: Boolean = false,
    val autoStartMonitoring: Boolean = false,
    val retentionDays: Int = 7,
    val maxSamplesPerHost: Int = 20_000,
    val liveWindowSize: Int = 120,
    val hasSeededHosts: Boolean = false,
) {
    val retentionMs: Long get() = retentionDays.toLong() * 24L * 60L * 60L * 1_000L
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pinglab_settings")

/**
 * DataStore-backed settings.
 *
 * Chosen over SharedPreferences because every read is a Flow \u2014 changing the theme or the
 * chart style updates Compose immediately, with no listener plumbing \u2014 and because writes
 * are transactional and never block the main thread.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { throwable ->
            // A corrupted preferences file must not take the app down.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toAppSettings() }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setChartStyle(style: ChartStyle) = edit { it[Keys.CHART_STYLE] = style.name }

    suspend fun setShowGrid(enabled: Boolean) = edit { it[Keys.SHOW_GRID] = enabled }

    suspend fun setAnimateCharts(enabled: Boolean) = edit { it[Keys.ANIMATE_CHARTS] = enabled }

    suspend fun setDefaultProtocol(protocol: Protocol) = edit { it[Keys.PROTOCOL] = protocol.name }

    suspend fun setDefaultInterval(intervalMs: Long) =
        edit { it[Keys.INTERVAL] = intervalMs.coerceIn(200L, 3_600_000L) }

    suspend fun setDefaultTimeout(timeoutMs: Int) =
        edit { it[Keys.TIMEOUT] = timeoutMs.coerceIn(200, 30_000) }

    suspend fun setDefaultPayloadSize(size: Int) =
        edit { it[Keys.PAYLOAD] = size.coerceIn(0, 1_472) }

    suspend fun setPreferIpv6(enabled: Boolean) = edit { it[Keys.PREFER_IPV6] = enabled }

    suspend fun setKeepScreenOn(enabled: Boolean) = edit { it[Keys.KEEP_SCREEN_ON] = enabled }

    suspend fun setNotificationsEnabled(enabled: Boolean) =
        edit { it[Keys.NOTIFICATIONS] = enabled }

    suspend fun setAlertSound(enabled: Boolean) = edit { it[Keys.ALERT_SOUND] = enabled }

    suspend fun setAutoStartMonitoring(enabled: Boolean) = edit { it[Keys.AUTO_START] = enabled }

    suspend fun setRetentionDays(days: Int) =
        edit { it[Keys.RETENTION_DAYS] = days.coerceIn(1, 365) }

    suspend fun setMaxSamplesPerHost(count: Int) =
        edit { it[Keys.MAX_SAMPLES] = count.coerceIn(1_000, 500_000) }

    suspend fun setLiveWindowSize(size: Int) =
        edit { it[Keys.LIVE_WINDOW] = size.coerceIn(30, 1_000) }

    suspend fun markHostsSeeded() = edit { it[Keys.SEEDED] = true }

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = this[Keys.THEME_MODE]?.let { name ->
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: defaults.themeMode,
            useDynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.useDynamicColor,
            chartStyle = this[Keys.CHART_STYLE]?.let { name ->
                ChartStyle.entries.firstOrNull { it.name == name }
            } ?: defaults.chartStyle,
            showGrid = this[Keys.SHOW_GRID] ?: defaults.showGrid,
            animateCharts = this[Keys.ANIMATE_CHARTS] ?: defaults.animateCharts,
            defaultProtocol = Protocol.fromNameOrDefault(this[Keys.PROTOCOL], defaults.defaultProtocol),
            defaultIntervalMs = this[Keys.INTERVAL] ?: defaults.defaultIntervalMs,
            defaultTimeoutMs = this[Keys.TIMEOUT] ?: defaults.defaultTimeoutMs,
            defaultPayloadSize = this[Keys.PAYLOAD] ?: defaults.defaultPayloadSize,
            preferIpv6 = this[Keys.PREFER_IPV6] ?: defaults.preferIpv6,
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            notificationsEnabled = this[Keys.NOTIFICATIONS] ?: defaults.notificationsEnabled,
            alertSound = this[Keys.ALERT_SOUND] ?: defaults.alertSound,
            autoStartMonitoring = this[Keys.AUTO_START] ?: defaults.autoStartMonitoring,
            retentionDays = this[Keys.RETENTION_DAYS] ?: defaults.retentionDays,
            maxSamplesPerHost = this[Keys.MAX_SAMPLES] ?: defaults.maxSamplesPerHost,
            liveWindowSize = this[Keys.LIVE_WINDOW] ?: defaults.liveWindowSize,
            hasSeededHosts = this[Keys.SEEDED] ?: defaults.hasSeededHosts,
        )
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val CHART_STYLE = stringPreferencesKey("chart_style")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val ANIMATE_CHARTS = booleanPreferencesKey("animate_charts")
        val PROTOCOL = stringPreferencesKey("default_protocol")
        val INTERVAL = longPreferencesKey("default_interval")
        val TIMEOUT = intPreferencesKey("default_timeout")
        val PAYLOAD = intPreferencesKey("default_payload")
        val PREFER_IPV6 = booleanPreferencesKey("prefer_ipv6")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val ALERT_SOUND = booleanPreferencesKey("alert_sound")
        val AUTO_START = booleanPreferencesKey("auto_start_monitoring")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val MAX_SAMPLES = intPreferencesKey("max_samples_per_host")
        val LIVE_WINDOW = intPreferencesKey("live_window")
        val SEEDED = booleanPreferencesKey("hosts_seeded")
    }
}
