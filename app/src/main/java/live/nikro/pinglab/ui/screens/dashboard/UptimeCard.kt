package live.nikro.pinglab.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import live.nikro.pinglab.R
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.domain.stats.UptimeDigest
import live.nikro.pinglab.ui.components.SectionCard
import live.nikro.pinglab.ui.components.StatTile
import live.nikro.pinglab.ui.components.VSpace
import live.nikro.pinglab.ui.theme.statusPalette

/**
 * Availability report built from samples that are already on disk.
 *
 * Packet loss elsewhere in the app answers "how many probes died". This card answers what an
 * operator actually asks: how long was it unreachable, how many times, and how fast did it come
 * back. Time weighted, so a single twenty minute outage outranks a hundred scattered lost
 * packets.
 */
@Composable
fun UptimeCard(
    digest: UptimeDigest,
    modifier: Modifier = Modifier,
) {
    val palette = statusPalette()
    val window = stringResource(R.string.uptime_window)
    val availability = digest.availabilityPercent
    val accent = when {
        !digest.hasData -> null
        digest.downNow.isNotEmpty() -> palette.down
        availability >= 99.9 -> palette.up
        availability >= 99.0 -> palette.degraded
        else -> palette.down
    }

    SectionCard(
        modifier = modifier,
        title = stringResource(R.string.uptime_title),
        subtitle = stringResource(R.string.uptime_subtitle, window, digest.hostCount),
        trailing = {
            Text(
                text = if (digest.hasData) {
                    Formatters.percent(availability, 2)
                } else {
                    Formatters.PLACEHOLDER
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        if (!digest.hasData) {
            Text(
                text = stringResource(R.string.uptime_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatTile(
                label = stringResource(R.string.uptime_stat_outages),
                value = digest.outageCount.toString(),
                modifier = Modifier.weight(1f),
                accent = if (digest.outageCount > 0) palette.degraded else palette.up,
            )
            StatTile(
                label = stringResource(R.string.uptime_stat_downtime),
                value = shortDuration(digest.downtimeMs),
                modifier = Modifier.weight(1f),
                accent = if (digest.downtimeMs > 0L) palette.down else null,
            )
            StatTile(
                label = stringResource(R.string.uptime_stat_longest),
                value = shortDuration(digest.longestOutageMs),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.uptime_stat_mttr),
                value = digest.meanTimeToRecoveryMs?.let { shortDuration(it) } ?: Formatters.PLACEHOLDER,
                modifier = Modifier.weight(1f),
            )
        }

        VSpace(8)

        val downNow = digest.downNow
        when {
            downNow.isNotEmpty() -> downNow.take(MAX_DOWN_LINES).forEach { host ->
                Text(
                    text = stringResource(
                        R.string.uptime_down_now,
                        host.label,
                        shortDuration(host.report.ongoingOutage?.durationMs ?: 0L),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.down,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            digest.perfect -> Text(
                text = stringResource(R.string.uptime_perfect),
                style = MaterialTheme.typography.bodySmall,
                color = palette.up,
            )

            else -> digest.worst?.let { worst ->
                Text(
                    text = stringResource(
                        R.string.uptime_worst,
                        worst.label,
                        Formatters.percent(worst.report.availabilityPercent, 2),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        VSpace(4)

        Text(
            text = stringResource(
                R.string.uptime_probe_success,
                Formatters.percent(digest.probeSuccessPercent, 1),
                Formatters.count(digest.sent),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact, locale independent duration: 3d 4h, 2h 15m, 6m 30s, 12s. */
private fun shortDuration(millis: Long): String {
    if (millis <= 0L) return "0s"
    val totalSeconds = millis / 1_000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        days > 0L -> days.toString() + "d " + hours + "h"
        hours > 0L -> hours.toString() + "h " + minutes + "m"
        minutes > 0L -> minutes.toString() + "m " + seconds + "s"
        else -> seconds.toString() + "s"
    }
}

private const val MAX_DOWN_LINES = 3
