package live.nikro.pinglab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.ui.theme.statusPalette

/**
 * Terminal-style probe log. Newest entries are appended at the bottom and the list
 * auto-follows unless the user has scrolled up to read history.
 */
@Composable
fun PingLogList(
    results: List<ProbeResult>,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = true,
    showHeader: Boolean = true,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(results.size, autoScroll) {
        if (!autoScroll || results.isEmpty()) return@LaunchedEffect
        // Only follow if the user is already near the end: yanking the viewport away
        // while someone reads an old failure is infuriating.
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= results.lastIndex - 3) {
            listState.animateScrollToItem(results.lastIndex)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (showHeader) {
            item(key = "log-header") {
                LogHeaderRow()
            }
        }
        items(items = results, key = { it.sequence.toLong() * 1_000L + (it.timestampMs % 1_000L) }) { result ->
            PingLogRow(result = result)
        }
    }
}

@Composable
private fun LogHeaderRow() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogCell(text = "#", color = color, width = 42, bold = true)
        LogCell(text = "TIME", color = color, width = 82, bold = true)
        LogCell(text = "RTT", color = color, width = 74, bold = true)
        Text(
            text = "RESULT",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One probe. Colour comes from the semantic palette so failures pop without shouting. */
@Composable
fun PingLogRow(
    result: ProbeResult,
    modifier: Modifier = Modifier,
) {
    val palette = statusPalette()
    val statusColor = when (result.status) {
        ProbeStatus.SUCCESS -> palette.up
        ProbeStatus.TIMEOUT -> palette.degraded
        ProbeStatus.TTL_EXPIRED -> palette.degraded
        ProbeStatus.DNS_FAILURE,
        ProbeStatus.UNREACHABLE,
        ProbeStatus.NETWORK_UNAVAILABLE,
        ProbeStatus.PERMISSION_DENIED,
        ProbeStatus.PROTOCOL_ERROR,
        ProbeStatus.ERROR -> palette.down
    }
    val background = if (result.status.isFailure) {
        statusColor.copy(alpha = 0.09f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .padding(end = 0.dp)
                .background(statusColor)
        )
        LogCell(
            text = result.sequence.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            width = 42,
        )
        LogCell(
            text = Formatters.clockPrecise(result.timestampMs),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            width = 82,
        )
        LogCell(
            text = Formatters.latency(result.rttMs),
            color = statusColor,
            width = 74,
            bold = true,
        )
        Text(
            text = describe(result),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LogCell(
    text: String,
    color: Color,
    width: Int,
    bold: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .width(width.dp)
            .padding(end = 6.dp),
    )
}

/** Short human description of a probe, mirroring what `ping` prints. */
private fun describe(result: ProbeResult): String = when (result.status) {
    ProbeStatus.SUCCESS -> buildString {
        append(result.resolvedAddress ?: result.hostname)
        result.ttl?.let { append("  ttl=").append(it) }
        result.payloadBytes?.let { append("  ").append(it).append("B") }
        result.httpStatusCode?.let { append("  HTTP ").append(it) }
        if (result.transport.label.isNotEmpty()) {
            append("  [").append(result.transport.label).append("]")
        }
    }

    ProbeStatus.TIMEOUT -> "Request timed out"
    ProbeStatus.UNREACHABLE -> result.detail ?: "Destination unreachable"
    ProbeStatus.DNS_FAILURE -> result.detail ?: "DNS resolution failed"
    ProbeStatus.TTL_EXPIRED -> "TTL expired in transit"
    ProbeStatus.PERMISSION_DENIED -> result.detail ?: "Permission denied"
    ProbeStatus.NETWORK_UNAVAILABLE -> "Network unavailable"
    ProbeStatus.PROTOCOL_ERROR -> result.detail ?: "Protocol error"
    ProbeStatus.ERROR -> result.detail ?: "Error"
}
