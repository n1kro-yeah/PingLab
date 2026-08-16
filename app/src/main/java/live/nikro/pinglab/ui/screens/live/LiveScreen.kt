package live.nikro.pinglab.ui.screens.live

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import live.nikro.pinglab.R
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.ui.components.ChartLegend
import live.nikro.pinglab.ui.components.EmptyState
import live.nikro.pinglab.ui.components.LatencyChart
import live.nikro.pinglab.ui.components.MonoTag
import live.nikro.pinglab.ui.components.PingLogList
import live.nikro.pinglab.ui.components.ProtocolSelector
import live.nikro.pinglab.ui.components.QualityBadge
import live.nikro.pinglab.ui.components.SectionCard
import live.nikro.pinglab.ui.components.StatGrid
import live.nikro.pinglab.ui.components.VSpace
import live.nikro.pinglab.ui.theme.statusPalette

/**
 * Live ping screen: type a target, hit start, watch latency arrive once per interval.
 * This is the screen people open first, so the input row stays reachable with one thumb
 * and the chart sits directly under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    modifier: Modifier = Modifier,
    initialTarget: String? = null,
    viewModel: LiveViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = statusPalette()

    LaunchedEffect(initialTarget) {
        if (!initialTarget.isNullOrBlank()) viewModel.onTargetChange(initialTarget)
    }

    // Export finished -> hand the file to the system share sheet.
    LaunchedEffect(state.pendingExport) {
        val export = state.pendingExport ?: return@LaunchedEffect
        val intent = viewModel.shareIntentFor(export)
        context.startActivity(Intent.createChooser(intent, export.displayName))
        viewModel.consumeExport()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.nav_live)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { viewModel.clear() }, enabled = state.results.isNotEmpty()) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_clear))
                    }
                    IconButton(
                        onClick = { menuOpen = true },
                        enabled = state.results.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_export))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Export CSV") },
                            onClick = {
                                menuOpen = false
                                viewModel.exportCsv()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export JSON") },
                            onClick = {
                                menuOpen = false
                                viewModel.exportJson()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_add_host)) },
                            leadingIcon = { Icon(Icons.Rounded.Bookmark, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                viewModel.saveAsMonitoredHost()
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = state.target,
                onValueChange = viewModel::onTargetChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.live_target_hint)) },
                supportingText = {
                    Text(state.validationMessage ?: stringResource(R.string.live_target_helper))
                },
                isError = state.validationMessage != null,
                singleLine = true,
                enabled = !state.isRunning,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        keyboard?.hide()
                        viewModel.toggle()
                    },
                ),
            )

            VSpace(10)

            ProtocolSelector(
                selected = state.protocol,
                onSelect = viewModel::onProtocolChange,
            )

            if (state.protocol.needsPort) {
                VSpace(8)
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Port") },
                    singleLine = true,
                    enabled = !state.isRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            VSpace(12)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isRunning) {
                    Button(
                        onClick = { viewModel.toggle() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Text(
                            text = stringResource(R.string.action_stop),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            keyboard?.hide()
                            viewModel.toggle()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Text(
                            text = stringResource(R.string.action_start),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                FilledTonalButton(onClick = { viewModel.cycleInterval() }) {
                    Icon(Icons.Rounded.Speed, contentDescription = null)
                    Text(
                        text = Formatters.interval(state.intervalMs),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            VSpace(14)

            if (state.results.isEmpty() && !state.isRunning) {
                EmptyState(
                    icon = Icons.Rounded.Speed,
                    title = stringResource(R.string.live_empty_title),
                    body = stringResource(R.string.live_empty_body),
                )
            } else {
                SectionCard(
                    title = state.resolvedAddress ?: state.target,
                    subtitle = state.transportLabel,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            QualityBadge(grade = state.quality.grade)
                        }
                    },
                ) {
                    LatencyChart(
                        samples = state.chartSamples,
                        style = state.settings.chartStyle,
                        showGrid = state.settings.showGrid,
                        animate = state.settings.animateCharts,
                        averageMs = state.stats.avgMs,
                        maxPoints = state.settings.liveWindowSize,
                    )
                    VSpace(8)
                    ChartLegend(
                        entries = listOf(
                            "RTT" to palette.chartLine,
                            "Average" to palette.chartAverage,
                            "Loss" to palette.chartLoss,
                        ),
                    )
                }

                VSpace(12)

                SectionCard(title = "Statistics", subtitle = state.quality.headline) {
                    StatGrid(
                        stats = listOf(
                            Triple(
                                stringResource(R.string.stat_last),
                                Formatters.latency(state.results.lastOrNull()?.rttMs),
                                palette.chartLine,
                            ),
                            Triple(stringResource(R.string.stat_avg), Formatters.latency(state.stats.avgMs), null),
                            Triple(stringResource(R.string.stat_jitter), Formatters.latency(state.stats.rfc3550JitterMs), null),
                            Triple(stringResource(R.string.stat_min), Formatters.latency(state.stats.minMs), palette.up),
                            Triple(stringResource(R.string.stat_max), Formatters.latency(state.stats.maxMs), palette.degraded),
                            Triple(stringResource(R.string.stat_median), Formatters.latency(state.stats.medianMs), null),
                            Triple(stringResource(R.string.stat_p95), Formatters.latency(state.stats.p95Ms), null),
                            Triple(
                                stringResource(R.string.stat_loss),
                                Formatters.percent(state.stats.lossPercent),
                                if (state.stats.lossPercent > 0.0) palette.down else palette.up,
                            ),
                            Triple(stringResource(R.string.stat_mos), Formatters.mos(state.stats.mos), null),
                        ),
                    )
                    VSpace(6)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonoTag(text = "sent " + state.stats.sent)
                        MonoTag(text = "recv " + state.stats.received)
                        state.resolvedAddress?.let { MonoTag(text = it) }
                    }
                }

                VSpace(12)

                SectionCard(title = "Log") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 320.dp)
                    ) {
                        PingLogList(
                            results = state.results,
                            autoScroll = state.isRunning,
                        )
                    }
                }
            }

            Box(modifier = Modifier.height(24.dp))
        }
    }
}
