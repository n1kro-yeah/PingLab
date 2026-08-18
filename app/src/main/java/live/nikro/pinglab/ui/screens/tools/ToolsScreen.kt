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
import live.nikro.pinglab.core.model.PortEvidence
import live.nikro.pinglab.core.model.PortState
import live.nikro.pinglab.core.model.ScanTrust
import live.nikro.pinglab.core.net.PortScanner
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                ToolTab.TLS -> TlsTab(state, viewModel)
                ToolTab.WOL -> WolTab(state, viewModel)
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
                    val stageLabel = when (ports.stage) {
                        PortScanner.Stage.CONTROL -> "probing control ports"
                        PortScanner.Stage.SWEEP -> "scanning"
                        null -> "finished"
                    }
                    Text(
                        text = stageLabel + "  -  " + ports.completed + " / " + ports.total +
                            if (ports.elapsedMs > 0L) "  -  " + Formatters.duration(ports.elapsedMs) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ports.resolvedAddress?.let { address ->
                        VSpace(2)
                        Text(
                            text = "target " + address,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (ports.trust == ScanTrust.ACCEPT_ALL || ports.trust == ScanTrust.SUSPICIOUS) {
            item(key = "trust") {
                SectionCard {
                    Text(
                        text = if (ports.trust == ScanTrust.ACCEPT_ALL) {
                            "This path accepts everything"
                        } else {
                            "This path looks suspicious"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    VSpace(4)
                    Text(
                        text = ports.controlAccepted.toString() + " of " + ports.controlSamples +
                            " random unused high ports also completed a handshake, so a transparent proxy, " +
                            "CGNAT or load balancer is answering for the host. Only ports where a service " +
                            "really replied are reported as open; the rest are listed as accepted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(items = ports.results, key = { it.port }) { probe ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = probe.port.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = if (probe.state == PortState.OPEN) palette.up else palette.degraded,
                        modifier = Modifier.width(64.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = (probe.serviceName ?: "unknown") + "  -  " + portStateLabel(probe.state),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = evidenceLabel(probe.evidence) +
                                (probe.detail?.let { ": " + it } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
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

        if (ports.finished) {
            item(key = "summary") {
                SectionCard {
                    Text(
                        text = "Verdict",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    VSpace(4)
                    Text(
                        text = "open " + ports.openCount + "  -  accepted but unproven " +
                            ports.acceptedCount + "  -  refused " + ports.closedCount +
                            "  -  no reply " + ports.filteredCount,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ports.results.isEmpty()) {
                        VSpace(4)
                        Text(
                            text = if (ports.closedCount > 0) {
                                "Nothing is listening here: the host refused every probe."
                            } else {
                                "Every probe was dropped without an answer, which usually means a firewall."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Short verdict shown next to the service name. */
private fun portStateLabel(state: PortState): String = when (state) {
    PortState.OPEN -> "open"
    PortState.ACCEPTED -> "accepted, unproven"
    PortState.CLOSED -> "closed"
    PortState.FILTERED -> "filtered"
    PortState.UNREACHABLE -> "unreachable"
}

/** How that verdict was reached, so the number is never a bare claim. */
private fun evidenceLabel(evidence: PortEvidence): String = when (evidence) {
    PortEvidence.BANNER -> "service banner received"
    PortEvidence.HTTP -> "HTTP response received"
    PortEvidence.TLS -> "TLS handshake completed"
    PortEvidence.RESPONSE -> "service sent data"
    PortEvidence.SILENT -> "handshake only, no data"
    PortEvidence.DROPPED -> "accepted, then dropped"
    PortEvidence.RESET -> "connection refused"
    PortEvidence.NO_REPLY -> "no reply"
    PortEvidence.NOT_CHECKED -> "not verified"
}

/**
 * Certificate and HTTP health of a single endpoint. Expiry dates are the most common cause
 * of a service that "worked yesterday", and pinging can never reveal them.
 */
@Composable
private fun TlsTab(state: ToolsUiState, viewModel: ToolsViewModel) {
    val tls = state.tls
    val report = tls.report
    val palette = statusPalette()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "input") {
            SectionCard {
                OutlinedTextField(
                    value = tls.host,
                    onValueChange = viewModel::onTlsHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Host, e.g. example.com") },
                    singleLine = true,
                    enabled = !tls.running,
                )
                VSpace(10)
                OutlinedTextField(
                    value = tls.port,
                    onValueChange = viewModel::onTlsPortChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Port") },
                    singleLine = true,
                    enabled = !tls.running,
                )
                VSpace(10)
                Button(
                    onClick = { viewModel.inspectTls() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !tls.running,
                ) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
                    Text(
                        text = if (tls.running) "Checking..." else "Check endpoint",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (tls.running) {
                    VSpace(10)
                    ProgressStrip(progress = null)
                }
            }
        }

        if (report != null) {
            item(key = "verdict") {
                SectionCard {
                    val trusted = report.chainTrusted == true
                    val healthy = trusted && report.hostnameMatches == true && !report.expired
                    val headline = when {
                        report.protocol == null -> "No TLS answer"
                        report.expired -> "Certificate expired"
                        report.expiringSoon -> "Certificate expires soon"
                        healthy -> "Certificate is valid"
                        else -> "Certificate needs attention"
                    }
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            report.protocol == null || report.expired -> MaterialTheme.colorScheme.error
                            healthy -> palette.up
                            else -> palette.degraded
                        },
                    )
                    report.daysLeft?.let { days ->
                        VSpace(4)
                        Text(
                            text = if (days >= 0) {
                                days.toString() + " days left"
                            } else {
                                "expired " + (-days) + " days ago"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    report.error?.let { error ->
                        VSpace(4)
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "certificate") {
                SectionCard {
                    report.address?.let { LabeledValue(label = "IP", value = it, monospace = true) }
                    report.protocol?.let { LabeledValue(label = "Protocol", value = it) }
                    report.cipherSuite?.let { LabeledValue(label = "Cipher", value = it, monospace = true) }
                    report.subject?.let { LabeledValue(label = "Subject", value = it) }
                    report.issuer?.let { LabeledValue(label = "Issuer", value = it) }
                    report.validFrom?.let { LabeledValue(label = "Valid from", value = formatInstant(it), monospace = true) }
                    report.validTo?.let { LabeledValue(label = "Valid to", value = formatInstant(it), monospace = true) }
                    if (report.chainLength > 0) {
                        LabeledValue(
                            label = "Chain",
                            value = report.chainLength.toString() + " certificates" +
                                if (report.selfSigned) ", self-signed" else "",
                        )
                    }
                    report.chainTrusted?.let {
                        LabeledValue(label = "System trust", value = if (it) "accepted" else "rejected")
                    }
                    report.hostnameMatches?.let {
                        LabeledValue(label = "Hostname", value = if (it) "matches" else "does not match")
                    }
                    report.handshakeMs?.let {
                        LabeledValue(label = "Handshake", value = Formatters.latency(it), monospace = true)
                    }
                    if (report.sans.isNotEmpty()) {
                        LabeledValue(
                            label = "Names",
                            value = report.sans.take(8).joinToString(", "),
                            monospace = true,
                        )
                    }
                }
            }

            if (report.httpStatus != null) {
                item(key = "http") {
                    SectionCard {
                        LabeledValue(label = "HTTP status", value = report.httpStatus.toString())
                        report.ttfbMs?.let {
                            LabeledValue(label = "TTFB", value = Formatters.latency(it), monospace = true)
                        }
                        report.httpServer?.let { LabeledValue(label = "Server", value = it) }
                        report.redirect?.let { LabeledValue(label = "Redirect", value = it, monospace = true) }
                        report.hsts?.let { LabeledValue(label = "HSTS", value = it, monospace = true) }
                    }
                }
            }
        }
    }
}

/** Wake-on-LAN sender: a magic packet is the only way to boot a sleeping machine remotely. */
@Composable
private fun WolTab(state: ToolsUiState, viewModel: ToolsViewModel) {
    val wol = state.wol

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "input") {
            SectionCard {
                Text(
                    text = "Broadcasts a Wake-on-LAN magic packet. The target needs Wake-on-LAN enabled " +
                        "in BIOS and its network card, and the phone must be on the same LAN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VSpace(10)
                OutlinedTextField(
                    value = wol.mac,
                    onValueChange = viewModel::onMacChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("MAC, e.g. AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    enabled = !wol.sending,
                )
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = wol.broadcast,
                        onValueChange = viewModel::onWolBroadcastChange,
                        modifier = Modifier.weight(2f),
                        label = { Text("Broadcast") },
                        singleLine = true,
                        enabled = !wol.sending,
                    )
                    OutlinedTextField(
                        value = wol.port,
                        onValueChange = viewModel::onWolPortChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("Port") },
                        singleLine = true,
                        enabled = !wol.sending,
                    )
                }
                VSpace(10)
                Button(
                    onClick = { viewModel.sendWol() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !wol.sending,
                ) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
                    Text(
                        text = if (wol.sending) "Sending..." else "Send magic packet",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        if (wol.log.isNotEmpty()) {
            item(key = "log") {
                SectionCard {
                    Text(text = "Recent sends", style = MaterialTheme.typography.titleSmall)
                    VSpace(4)
                    wol.log.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun formatInstant(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
