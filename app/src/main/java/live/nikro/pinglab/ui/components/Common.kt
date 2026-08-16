package live.nikro.pinglab.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import live.nikro.pinglab.core.model.HostState
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.core.model.QualityGrade
import live.nikro.pinglab.ui.theme.StatusPalette
import live.nikro.pinglab.ui.theme.statusPalette

/*
 * Shared Material 3 building blocks. Everything here uses colorScheme roles
 * (surfaceContainer / onSurfaceVariant / primaryContainer ...) so dynamic colour and
 * light-dark switching work without any per-screen tweaking.
 */

/** Container colour pair for a host state, taken from the semantic palette. */
fun StatusPalette.colorsFor(state: HostState): Pair<Color, Color> = when (state) {
    HostState.UP -> up to onUp
    HostState.DEGRADED -> degraded to onDegraded
    HostState.DOWN -> down to onDown
    HostState.CHECKING -> idle to onIdle
    HostState.IDLE -> idle to onIdle
}

fun StatusPalette.colorFor(grade: QualityGrade): Color = when (grade) {
    QualityGrade.EXCELLENT -> excellent
    QualityGrade.GOOD -> good
    QualityGrade.FAIR -> fair
    QualityGrade.POOR -> poor
    QualityGrade.BAD -> bad
    QualityGrade.UNKNOWN -> idle
}

/** Standard content card used across every screen. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (trailing != null) trailing()
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** Pulsing dot for CHECKING, solid dot otherwise. */
@Composable
fun StatusDot(
    state: HostState,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 10.dp,
) {
    val palette = statusPalette()
    val (color, _) = palette.colorsFor(state)
    val animatedColor by animateColorAsState(targetValue = color, label = "statusColor")

    val alpha = if (state == HostState.CHECKING) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
        value
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(animatedColor)
    )
}

/** Tonal chip: status dot plus label, e.g. "UP" or "DEGRADED". */
@Composable
fun StatusChip(
    state: HostState,
    label: String,
    modifier: Modifier = Modifier,
) {
    val palette = statusPalette()
    val (color, onColor) = palette.colorsFor(state)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.16f),
        contentColor = onColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(state = state, size = 8.dp)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Letter badge (A..E) for a quality grade. */
@Composable
fun QualityBadge(
    grade: QualityGrade,
    modifier: Modifier = Modifier,
) {
    val palette = statusPalette()
    val color = palette.colorFor(grade)
    Surface(
        modifier = modifier.size(28.dp),
        shape = CircleShape,
        color = color.copy(alpha = 0.18f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = grade.shortLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

/** One number with a caption; the atom of every stats grid in the app. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    caption: String? = null,
) {
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Fixed-column grid of [StatTile]s; wraps rows itself to stay predictable on small screens. */
@Composable
fun StatGrid(
    stats: List<Triple<String, String, Color?>>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stats.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { (label, value, accent) ->
                    StatTile(
                        label = label,
                        value = value,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Key-value row used in detail sheets. */
@Composable
fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    valueColor: Color? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (monospace) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/** Protocol picker built on the M3 segmented button row. */
@Composable
fun ProtocolSelector(
    selected: Protocol,
    onSelect: (Protocol) -> Unit,
    modifier: Modifier = Modifier,
    protocols: List<Protocol> = Protocol.entries.toList(),
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        protocols.forEachIndexed { index, protocol ->
            SegmentedButton(
                selected = protocol == selected,
                onClick = { onSelect(protocol) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = protocols.size),
                label = {
                    Text(
                        text = protocol.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

/** Friendly placeholder with an optional call to action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}

/** Section title used above lists outside of cards. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null) action()
    }
}

/** Thin horizontal progress used while a tool is running. */
@Composable
fun ProgressStrip(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track)
    ) {
        val fraction = progress?.coerceIn(0f, 1f) ?: 0f
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

/** Compact monospace pill for addresses, ports and transports. */
@Composable
fun MonoTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = (color ?: MaterialTheme.colorScheme.surfaceVariant).copy(alpha = 0.7f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

/** Spacer helper so screens do not repeat the same magic number. */
@Composable
fun VSpace(height: Int = 12) {
    Spacer(modifier = Modifier.height(height.dp))
}

@Composable
fun HSpace(width: Int = 8) {
    Spacer(modifier = Modifier.width(width.dp))
}
