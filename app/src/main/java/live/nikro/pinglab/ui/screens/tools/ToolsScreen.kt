package live.nikro.pinglab.ui.screens.tools

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import live.nikro.pinglab.R
import live.nikro.pinglab.core.model.TracerouteHop
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.ui.components.HopLatencyBars
import live.nikro.pinglab.ui.components.LabeledValue
import live.nikro.pinglab.ui.components.MonoTag
import live.nikro.pinglab.ui.components.ProgressStrip
import live.nikro.pinglab.ui.components.SectionCard
import live.nikro.pinglab.ui.components.VSpace
import live.nikro.pinglab.ui.theme.statusPalette

/** Traceroute, DNS lookup and a TCP port scanner, one tab each. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    modifier: Modifier = Modifier,
    initialTarget: String? = null,
    viewModel: ToolsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(initialTarget) {
        if (!initialTarget.isNullOrBlank()) viewModel.prefillFromTarget(initialTarget)
    }

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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_tools)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                ToolTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }

            when (state.tab) {
                ToolTab.TRACEROUTE -> TracerouteTab(state, viewModel)
                ToolTab.DNS -> DnsTab(state, viewModel)
                ToolTab.PORTS -> PortsTab(state, viewModel)
            }
        }
    }
}

@Composable
private fun TracerouteTab(state: ToolsUiState, viewModel: ToolsViewModel) {
    val trace = state.traceroute

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "input") {
            SectionCard {
                OutlinedTextField(
                    value = trace.target,
                    onValueChange = viewModel::onTracerouteTargetChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Host or IP") },
                    singleLine = true,
                    enabled = !trace.running,
                )
                VSpace(10)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.toggleTraceroute() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = if (trace.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                        )
                        Text(
                            text = if (trace.running) {
                                stringResource(R.string.action_stop)
                            } else {
                                "Trace"
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.exportTraceroute() },
                        enabled = trace.hops.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_export))
                    }
                }
                if (trace.status != null) {
                    VSpace(8)
                    Text(
                        text = trace.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(items = trace.hops, key = { it.ttl }) { hop ->
            HopRow(hop = hop)
        }
    }
}

@Composable
private fun HopRow(hop: TracerouteHop) {
    val palette = statusPalette()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hop.ttl.toString(),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hop.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (hop.isTimeout) "no reply" else Formatters.latency(hop.avgRttMs) + " avg",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hop.isTimeout) palette.down else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hop.isDestination) MonoTag(text = "target")
        }
        VSpace(8)
        HopLatencyBars(values = hop.rttsMs)
    }
}


@Composable
private fun DnsTab(state: ToolsUiState, viewModel: ToolsViewModel) {
    val dns = state.dns
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard {
            OutlinedTextField(
                value = dns.host,
                onValueChange = viewModel::onDnsHostChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Hostname") },
                singleLine = true,
            )
            VSpace(10)
            Button(
                onClick = { viewModel.runDnsLookup() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !dns.running,
            ) {
                Text(if (dns.running) "Resolving..." else "Resolve")
            }
        }

        val result = dns.result
        if (result != null) {
            SectionCard(title = result.query, subtitle = Formatters.latency(result.durationMs.toDouble())) {
                if (!result.isSuccess) {
                    Text(
                        text = result.error ?: "Lookup failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    result.addresses.forEachIndexed { index, address ->
                        LabeledValue(
                            label = "A/AAAA " + (index + 1),
                            value = address,
                            monospace = true,
                        )
                    }
                    result.canonicalName?.let { LabeledValue(label = "CNAME", value = it, monospace = true) }
                    result.reverseName?.let { LabeledValue(label = "PTR", value = it, monospace = true) }
                }
            }
        }
    }
}

@Composable
private fun PortsTab(state: ToolsUiState, viewModel: ToolsViewModel) {
    val ports = state.ports
    val palette = statusPalette()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "input") {
            SectionCard {
                OutlinedTextField(
                    value = ports.host,
                    onValueChange = viewModel::onPortHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Host or IP") },
                    singleLine = true,
                    enabled = !ports.running,
                )
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = ports.preset == preset,
                            onClick = { viewModel.onPresetChange(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
                if (ports.preset == PortPreset.CUSTOM) {
                    VSpace(10)
                    OutlinedTextField(
                        value = ports.customRange,
                        onValueChange = viewModel::onCustomRangeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Ports, e.g. 22,80,443 or 1-1024") },
                        singleLine = true,
                    )
                }
                VSpace(10)
                Button(
                    onClick = { viewModel.togglePortScan() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (ports.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                    Text(
                        text = if (ports.running) stringResource(R.string.action_stop) else "Scan",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (ports.running || ports.finished) {
                    VSpace(10)
                    ProgressStrip(progress = ports.progress)
                    VSpace(6)
                    Text(
                        text = ports.completed.toString() + " / " + ports.total + " ports" +
                            if (ports.elapsedMs > 0L) "  -  " + Formatters.duration(ports.elapsedMs) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(items = ports.open, key = { it.port }) { probe ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = probe.port.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = palette.up,
                        modifier = Modifier.width(64.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = probe.serviceName ?: "open",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        probe.banner?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    MonoTag(text = Formatters.latency(probe.rttMs))
                }
            }
        }

        if (ports.finished && ports.open.isEmpty()) {
            item(key = "none") {
                SectionCard {
                    Text(
                        text = "No open ports found in the scanned range.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
