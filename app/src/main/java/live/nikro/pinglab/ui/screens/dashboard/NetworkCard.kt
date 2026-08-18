package live.nikro.pinglab.ui.screens.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import live.nikro.pinglab.R
import live.nikro.pinglab.core.model.NetworkDetail
import live.nikro.pinglab.core.model.NetworkStatus
import live.nikro.pinglab.core.model.TransportKind
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.ui.components.MonoTag
import live.nikro.pinglab.ui.components.SectionCard
import live.nikro.pinglab.ui.components.VSpace
import live.nikro.pinglab.ui.theme.statusPalette

/**
 * The network card at the top of the dashboard.
 *
 * Wi-Fi and mobile data deserve different bodies: on Wi-Fi the band and the negotiated link
 * speed explain a latency change, on LTE or 5G the operator, the radio technology and the bar
 * count do. Everything rendered here is permission free - see NetworkInspector for why the
 * SSID is missing and why very old Android versions only get a generic mobile label.
 */
@Composable
fun NetworkCard(
    status: NetworkStatus,
    detail: NetworkDetail,
    modifier: Modifier = Modifier,
) {
    val palette = statusPalette()
    val cellular = detail.cellular
    val wifi = detail.wifi
    val unknownTransport = detail.kind == TransportKind.NONE

    // Title is the radio technology when the system names it: LTE, LTE+, 5G, Wi-Fi, Ethernet.
    val title = if (unknownTransport) status.transportLabel else detail.title

    val subtitle = when {
        cellular != null -> buildList {
            add(
                stringResource(
                    if (status.isConnected) R.string.net_mobile else R.string.net_mobile_no_route,
                ),
            )
            cellular.carrier?.let { add(it) }
            if (cellular.roaming) add(stringResource(R.string.net_roaming))
        }.joinToString(" \u00b7 ")

        wifi != null -> buildList {
            add(
                stringResource(
                    if (status.isConnected) R.string.net_wlan else R.string.net_wlan_no_route,
                ),
            )
            wifi.bandLabel?.let { add(it) }
            if (wifi.linkSpeedMbps > 0) {
                add(stringResource(R.string.net_link_speed, wifi.linkSpeedMbps))
            }
        }.joinToString(" \u00b7 ")

        status.isConnected -> stringResource(R.string.net_connected)
        else -> stringResource(R.string.net_offline)
    }

    val icon = when (detail.kind) {
        TransportKind.CELLULAR -> Icons.Rounded.SignalCellularAlt
        TransportKind.WIFI -> Icons.Rounded.Wifi
        TransportKind.ETHERNET -> Icons.Rounded.SettingsEthernet
        TransportKind.VPN -> Icons.Rounded.VpnLock
        TransportKind.BLUETOOTH -> Icons.Rounded.Bluetooth
        TransportKind.OTHER -> Icons.Rounded.Router
        TransportKind.NONE -> Icons.Rounded.CloudOff
    }

    SectionCard(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        trailing = {
            // Either the measured meter or the transport glyph, never both. Material's
            // SignalCellularAlt is itself a bar chart, so drawing it next to our own bars
            // looked like the card was showing two different signal strengths.
            if (detail.signalLevel >= 0) {
                SignalBars(
                    level = detail.signalLevel,
                    activeColor = if (status.isConnected) palette.up else palette.degraded,
                    inactiveColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (status.isConnected) palette.up else palette.down,
                )
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            status.localAddress?.takeIf { it.isRoutable() }?.let { MonoTag(text = "ip " + it) }
            // Cellular and VPN links routinely report a wildcard default route. Printing
            // "gw 0.0.0.0" looks like a bug to the user and tells nobody anything.
            status.gateway?.takeIf { it.isRoutable() }?.let { MonoTag(text = "gw " + it) }
            if (status.isVpn) MonoTag(text = "VPN")
            if (status.isMetered) MonoTag(text = "metered")
        }

        // Radio quality line. On mobile the bar count and dBm are the first thing to check
        // when latency spikes; the bandwidth estimate comes from the platform, not a speedtest.
        val extras = buildList {
            if (cellular != null) {
                if (cellular.hasSignal) add("signal " + cellular.signalLevel + "/4")
                cellular.signalDbm?.let { add(it.toString() + " dBm") }
            }
            if (wifi != null) {
                wifi.rssiDbm?.let { add(it.toString() + " dBm") }
                if (wifi.frequencyMhz > 0) add(wifi.frequencyMhz.toString() + " MHz")
            }
            if (status.linkDownstreamKbps > 0) {
                add("~" + Formatters.bitrate(status.linkDownstreamKbps) + " down")
            }
        }
        if (extras.isNotEmpty()) {
            VSpace(6)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                extras.forEach { MonoTag(text = it) }
            }
        }

        if (cellular != null && cellular.techLabel == null) {
            VSpace(6)
            Text(
                text = stringResource(R.string.net_radio_hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (status.dnsServers.isNotEmpty()) {
            VSpace(6)
            Text(
                text = "DNS: " + status.dnsServers.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Wildcard and unspecified addresses carry no information and are hidden from the chips. */
private fun String.isRoutable(): Boolean =
    isNotBlank() && this != "0.0.0.0" && this != "::" && this != "0:0:0:0:0:0:0:0"

/**
 * Four bars drawn by hand instead of picking one of Android's signal icons, so the level the
 * modem reports is exactly the level shown. Drawn in the draw phase only: no recomposition,
 * no allocations per frame.
 */
@Composable
private fun SignalBars(
    level: Int,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .width(20.dp)
            .height(16.dp),
    ) {
        val bars = 4
        val gap = size.width * 0.12f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val corner = CornerRadius(barWidth * 0.4f, barWidth * 0.4f)
        for (index in 0 until bars) {
            val fraction = (index + 1) / bars.toFloat()
            val barHeight = size.height * (0.3f + 0.7f * fraction)
            drawRoundRect(
                color = if (index < level) activeColor else inactiveColor,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = corner,
            )
        }
    }
}
