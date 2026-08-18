package live.nikro.pinglab.ui.screens.detail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import live.nikro.pinglab.R
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.ui.components.JitterChart
import live.nikro.pinglab.ui.components.LatencyChart
import live.nikro.pinglab.ui.components.LatencyHistogramChart
import live.nikro.pinglab.ui.components.LossDonut
import live.nikro.pinglab.ui.components.MonoTag
import live.nikro.pinglab.ui.components.PingLogList
import live.nikro.pinglab.ui.components.QualityGauge
import live.nikro.pinglab.ui.components.SectionCard
import live.nikro.pinglab.ui.components.StatGrid
import live.nikro.pinglab.ui.components.VSpace
import live.nikro.pinglab.ui.theme.statusPalette

/** Full history for one monitored host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailScreen(
    hostId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HostDetailViewModel = viewModel(
        factory = HostDetailViewModel.factory(hostId),
        key = "host-detail-" + hostId,
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = statusPalette()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.pendingExport) {
        val export = state.pendingExport ?: return@LaunchedEffect
        context.startActivity(Intent.createChooser(viewModel.shareIntentFor(export), export.displayName))
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
                title = {
                    Text(
                        text = state.host?.label ?: "Host",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
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
                    }
                    IconButton(
                        onClick = {
                            viewModel.deleteHost(onDeleted = onBack)
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                SectionCard(
                    title = state.host?.displayTarget ?: "",
                    subtitle = state.host?.protocol?.label,
                    trailing = {
                        Switch(
                            checked = state.host?.enabled == true,
                            onCheckedChange = { viewModel.toggleEnabled() },
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HistoryRange.entries.forEach { range ->
                            FilterChip(
                                selected = state.range == range,
                                onClick = { viewModel.selectRange(range) },
                                label = { Text(range.label) },
                            )
                        }
                    }
                    VSpace(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonoTag(text = Formatters.count(state.sampleCount) + " samples")
                        MonoTag(text = "trend " + state.trend.name.lowercase())
                        state.host?.let { MonoTag(text = "every " + Formatters.interval(it.intervalMs)) }
                    }
                }
            }

            item(key = "chart") {
                SectionCard(title = "Latency", subtitle = state.quality.headline) {
                    LatencyChart(
                        samples = state.chartSamples,
                        averageMs = state.stats.avgMs,
                        thresholdMs = state.host?.degradedLatencyMs?.toDouble(),
                        maxPoints = state.range.points,
                        emptyLabel = "No samples in this window",
                    )
                }
            }

            item(key = "quality") {
                SectionCard(title = "Quality") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QualityGauge(
                            score = state.quality.score,
                            gradeLabel = state.quality.grade.label,
                        )
                        LossDonut(
                            lossPercent = state.stats.lossPercent,
                            caption = "packet loss",
                        )
                    }
                    VSpace(10)
                    state.quality.details.forEach { detail ->
                        Text(
                            text = "- " + detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.quality.useCases.isNotEmpty()) {
                        VSpace(10)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.quality.useCases.take(3).forEach { rating ->
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(rating.useCase.label + ": " + rating.grade.shortLabel)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "stats") {
                SectionCard(title = "Statistics") {
                    StatGrid(
                        stats = listOf(
                            Triple(stringResource(R.string.stat_avg), Formatters.latency(state.stats.avgMs), palette.chartLine),
                            Triple(stringResource(R.string.stat_min), Formatters.latency(state.stats.minMs), palette.up),
                            Triple(stringResource(R.string.stat_max), Formatters.latency(state.stats.maxMs), palette.degraded),
                            Triple(stringResource(R.string.stat_median), Formatters.latency(state.stats.medianMs), null),
                            Triple(stringResource(R.string.stat_p95), Formatters.latency(state.stats.p95Ms), null),
                            Triple(stringResource(R.string.stat_p99), Formatters.latency(state.stats.p99Ms), null),
                            Triple(stringResource(R.string.stat_jitter), Formatters.latency(state.stats.rfc3550JitterMs), null),
                            Triple(stringResource(R.string.stat_stddev), Formatters.latency(state.stats.stdDevMs), null),
                            Triple(stringResource(R.string.stat_uptime), Formatters.percent(state.stats.uptimePercent), palette.up),
                            Triple(stringResource(R.string.stat_sent), Formatters.count(state.stats.sent), null),
                            Triple(stringResource(R.string.stat_received), Formatters.count(state.stats.received), null),
                            Triple(stringResource(R.string.stat_mos), Formatters.mos(state.stats.mos), null),
                        ),
                    )
                }
            }

            if (state.histogram.isNotEmpty()) {
                item(key = "histogram") {
                    SectionCard(title = "Distribution", subtitle = "How often each latency range occurred") {
                        LatencyHistogramChart(buckets = state.histogram)
                    }
                }
            }

            item(key = "jitter") {
                SectionCard(title = "Jitter", subtitle = "Difference between consecutive replies") {
                    JitterChart(samples = state.chartSamples)
                }
            }

            item(key = "log") {
                SectionCard(title = "Recent probes") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 340.dp)
                    ) {
                        PingLogList(results = state.recent, autoScroll = false)
                    }
                    VSpace(6)
                    TextButton(onClick = { viewModel.clearHistory() }) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }
        }
    }
}
