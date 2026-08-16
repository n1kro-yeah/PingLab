package live.nikro.pinglab.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import live.nikro.pinglab.R
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.data.prefs.ChartStyle
import live.nikro.pinglab.data.prefs.ThemeMode
import live.nikro.pinglab.ui.components.LabeledValue
import live.nikro.pinglab.ui.components.ProtocolSelector
import live.nikro.pinglab.ui.components.SectionCard
import live.nikro.pinglab.ui.components.VSpace

/** Every persisted preference, grouped the way Material 3 settings pages are laid out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "appearance") {
                SectionCard(title = stringResource(R.string.settings_appearance)) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VSpace(6)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val modes = ThemeMode.entries
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    VSpace(12)
                    Text(
                        text = "Chart style",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VSpace(6)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val styles = ChartStyle.entries
                        styles.forEachIndexed { index, style ->
                            SegmentedButton(
                                selected = settings.chartStyle == style,
                                onClick = { viewModel.setChartStyle(style) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = styles.size),
                                label = { Text(style.label) },
                            )
                        }
                    }
                    VSpace(6)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
                            title = "Dynamic color",
                            description = "Use Material You colors from the wallpaper",
                            checked = settings.useDynamicColor,
                            onCheckedChange = viewModel::setDynamicColor,
                        )
                    }
                    SwitchRow(
                        title = "Chart grid",
                        description = "Show horizontal guide lines",
                        checked = settings.showGrid,
                        onCheckedChange = viewModel::setShowGrid,
                    )
                    SwitchRow(
                        title = "Animate charts",
                        description = "Animate new samples as they arrive",
                        checked = settings.animateCharts,
                        onCheckedChange = viewModel::setAnimateCharts,
                    )
                    SwitchRow(
                        title = "Keep screen on",
                        description = "Prevent sleep while a live test is running",
                        checked = settings.keepScreenOn,
                        onCheckedChange = viewModel::setKeepScreenOn,
                    )
                }
            }

            item(key = "probing") {
                SectionCard(title = stringResource(R.string.settings_probing)) {
                    Text(
                        text = "Default protocol",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VSpace(6)
                    ProtocolSelector(
                        selected = settings.defaultProtocol,
                        onSelect = viewModel::setDefaultProtocol,
                    )
                    VSpace(12)
                    SliderRow(
                        title = "Interval",
                        value = settings.defaultIntervalMs / 1000f,
                        valueLabel = Formatters.interval(settings.defaultIntervalMs),
                        range = 0.25f..10f,
                        steps = 38,
                        onValueChange = { viewModel.setDefaultInterval((it * 1000f).toLong()) },
                    )
                    SliderRow(
                        title = "Timeout",
                        value = settings.defaultTimeoutMs.toFloat(),
                        valueLabel = settings.defaultTimeoutMs.toString() + " ms",
                        range = 250f..10_000f,
                        steps = 38,
                        onValueChange = { viewModel.setDefaultTimeout(it.toInt()) },
                    )
                    SliderRow(
                        title = "Payload size",
                        value = settings.defaultPayloadSize.toFloat(),
                        valueLabel = settings.defaultPayloadSize.toString() + " bytes",
                        range = 0f..1_400f,
                        steps = 27,
                        onValueChange = { viewModel.setDefaultPayloadSize(it.toInt()) },
                    )
                    SliderRow(
                        title = "Live chart window",
                        value = settings.liveWindowSize.toFloat(),
                        valueLabel = settings.liveWindowSize.toString() + " samples",
                        range = 30f..600f,
                        steps = 18,
                        onValueChange = { viewModel.setLiveWindowSize(it.toInt()) },
                    )
                    SwitchRow(
                        title = "Prefer IPv6",
                        description = "Resolve AAAA records first when available",
                        checked = settings.preferIpv6,
                        onCheckedChange = viewModel::setPreferIpv6,
                    )
                }
            }

            item(key = "alerts") {
                SectionCard(title = stringResource(R.string.settings_alerts)) {
                    SwitchRow(
                        title = "Notifications",
                        description = "Alert when a monitored host goes down or recovers",
                        checked = settings.notificationsEnabled,
                        onCheckedChange = viewModel::setNotificationsEnabled,
                    )
                    SwitchRow(
                        title = "Alert sound",
                        description = "Play a sound with down alerts",
                        checked = settings.alertSound,
                        onCheckedChange = viewModel::setAlertSound,
                    )
                    SwitchRow(
                        title = "Auto start monitoring",
                        description = "Start background checks when the app opens",
                        checked = settings.autoStartMonitoring,
                        onCheckedChange = viewModel::setAutoStartMonitoring,
                    )
                }
            }

            item(key = "storage") {
                SectionCard(title = stringResource(R.string.settings_storage)) {
                    LabeledValue(
                        label = "Stored samples",
                        value = Formatters.count(state.storedSamples),
                        monospace = true,
                    )
                    LabeledValue(
                        label = "Monitored hosts",
                        value = Formatters.count(state.hostCount),
                        monospace = true,
                    )
                    VSpace(8)
                    SliderRow(
                        title = "Retention",
                        value = settings.retentionDays.toFloat(),
                        valueLabel = settings.retentionDays.toString() + " days",
                        range = 1f..90f,
                        steps = 88,
                        onValueChange = { viewModel.setRetentionDays(it.toInt()) },
                    )
                    SliderRow(
                        title = "Max samples per host",
                        value = settings.maxSamplesPerHost.toFloat(),
                        valueLabel = Formatters.count(settings.maxSamplesPerHost),
                        range = 1_000f..100_000f,
                        steps = 98,
                        onValueChange = { viewModel.setMaxSamplesPerHost(it.toInt()) },
                    )
                    VSpace(10)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.clearSamples() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Clear samples")
                        }
                        OutlinedButton(
                            onClick = { viewModel.clearSessions() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Clear sessions")
                        }
                    }
                }
            }

            item(key = "about") {
                SectionCard(title = stringResource(R.string.settings_about)) {
                    LabeledValue(label = "Application", value = stringResource(R.string.app_name))
                    LabeledValue(label = "Package", value = context.packageName, monospace = true)
                    LabeledValue(label = "Version", value = "1.0.0", monospace = true)
                    LabeledValue(label = "Design", value = "Material 3 (Material You)")
                    VSpace(6)
                    Text(
                        text = "PingLab probes hosts with ICMP datagram sockets, the system ping binary, " +
                            "TCP connect timing, HTTP(S) timing and DNS queries, then scores the result " +
                            "with an E-model based MOS estimate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VSpace(12)
                    Button(
                        onClick = { viewModel.resetToDefaults() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Reset to defaults")
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}
