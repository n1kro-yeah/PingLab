package live.nikro.pinglab.ui.screens.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import live.nikro.pinglab.R
import live.nikro.pinglab.core.model.HostState
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.ui.components.EmptyState
import live.nikro.pinglab.ui.components.SectionCard
import live.nikro.pinglab.ui.components.Sparkline
import live.nikro.pinglab.ui.components.StatTile
import live.nikro.pinglab.ui.components.StatusChip
import live.nikro.pinglab.ui.components.VSpace
import live.nikro.pinglab.ui.theme.statusPalette

/**
 * Overview of every monitored host plus the state of the network the phone is on.
 * The monitoring switch here is the only place that starts or stops the foreground service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenHost: (Long) -> Unit,
    onAddHost: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = statusPalette()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.nav_dashboard)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { viewModel.refreshNow() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_retry))
                    }
                    IconButton(onClick = onAddHost) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_add_host))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "network") {
                // Wi-Fi, LTE/5G, Ethernet and VPN all render from the same card; the body
                // switches to whatever the active transport can actually tell us.
                NetworkCard(status = state.network, detail = state.networkDetail)
            }

            item(key = "uptime") {
                // Availability over the last day, recomputed from the samples already on disk.
                UptimeCard(digest = state.uptime)
            }

            item(key = "monitoring") {
                SectionCard(
                    title = stringResource(R.string.dash_monitoring_title),
                    subtitle = if (state.monitoring) {
                        stringResource(R.string.dash_monitoring_on)
                    } else {
                        stringResource(R.string.dash_monitoring_off)
                    },
                    trailing = {
                        Switch(
                            checked = state.monitoring,
                            onCheckedChange = { viewModel.toggleMonitoring(context) },
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatTile(
                            label = stringResource(R.string.dash_stat_hosts),
                            value = state.cards.size.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = stringResource(R.string.dash_stat_up),
                            value = state.upCount.toString(),
                            accent = palette.up,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = stringResource(R.string.dash_stat_degraded),
                            value = state.degradedCount.toString(),
                            accent = palette.degraded,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = stringResource(R.string.dash_stat_down),
                            value = state.downCount.toString(),
                            accent = palette.down,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    VSpace(4)
                    Text(
                        text = stringResource(
                            R.string.dash_samples_stored,
                            Formatters.count(state.storedSamples),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.cards.isEmpty() && !state.loading) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.Router,
                        title = stringResource(R.string.dash_empty_title),
                        body = stringResource(R.string.dash_empty_body),
                        actionLabel = stringResource(R.string.action_add_host),
                        onAction = onAddHost,
                    )
                }
            }

            items(items = state.cards, key = { it.host.id }) { card ->
                HostCard(
                    card = card,
                    onClick = { onOpenHost(card.host.id) },
                )
            }
        }
    }
}

@Composable
private fun HostCard(
    card: HostCardState,
    onClick: () -> Unit,
) {
    val palette = statusPalette()
    val accent = when (card.state) {
        HostState.UP -> palette.up
        HostState.DEGRADED -> palette.degraded
        HostState.DOWN -> palette.down
        else -> palette.idle
    }

    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.host.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.host.displayTarget,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(state = card.state, label = card.state.name)
        }

        VSpace(10)

        Box(modifier = Modifier.height(38.dp)) {
            Sparkline(samples = card.samples, color = accent)
        }

        VSpace(8)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatTile(
                label = stringResource(R.string.stat_avg),
                value = Formatters.latency(card.stats.avgMs),
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stat_loss),
                value = Formatters.percent(card.stats.lossPercent),
                accent = if (card.stats.lossPercent > 0.0) palette.down else null,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stat_jitter),
                value = Formatters.latency(card.stats.rfc3550JitterMs),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stat_checked),
                value = if (card.lastCheckMs > 0L) Formatters.relative(card.lastCheckMs) else Formatters.PLACEHOLDER,
                modifier = Modifier.weight(1.2f),
            )
        }
    }
}
