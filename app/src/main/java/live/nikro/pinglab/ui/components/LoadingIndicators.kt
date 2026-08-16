package live.nikro.pinglab.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * Expressive loading indicators.
 *
 * Material 3 Expressive replaced the flat progress bar with wavy indicators and a morphing
 * loading shape. Those composables (LinearWavyProgressIndicator, LoadingIndicator, ...) ship
 * in material3 1.4, while this app is pinned to the 2024.12.01 BOM, i.e. material3 1.3.1.
 * Rather than pulling an unverified alpha into a build that has to pass CI, the shapes are
 * drawn here from the published spec: 4 dp active stroke, ~40 dp wavelength, amplitude that
 * relaxes to zero as the value approaches 100 %, a 4 dp gap before the track, and a stop
 * indicator pinned to the end of the track.
 *
 * Reference: m3.material.io/components/progress-indicators/specs
 */

private const val WAVE_SAMPLE_STEP_PX = 2f

/**
 * Wavy linear progress indicator.
 *
 * @param progress 0..1 for determinate, `null` for indeterminate
 * @param amplitude peak wave height; the spec keeps this small relative to the stroke
 * @param wavelength distance between two crests
 */
@Composable
fun WavyLinearProgressIndicator(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    stroke: Dp = 4.dp,
    amplitude: Dp = 3.dp,
    wavelength: Dp = 40.dp,
    gap: Dp = 4.dp,
    showStopIndicator: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "wavy-linear")

    // The crests travel left to right at a constant speed; one full period per 1.2 s.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // Indeterminate sweep: a head that runs ahead of a tail, both eased, so the segment
    // stretches on the way out and contracts on the way in.
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    val target = progress?.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = target ?: 0f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "progress",
    )

    Canvas(
        modifier = modifier
            .height(stroke + amplitude * 2 + 4.dp),
    ) {
        val strokePx = stroke.toPx()
        val gapPx = gap.toPx()
        val wavelengthPx = wavelength.toPx()
        val centerY = size.height / 2f
        val stopRadius = strokePx / 2f
        val trackEnd = if (showStopIndicator) size.width - strokePx else size.width

        if (target != null) {
            // Determinate: wave up to the value, flat track after the gap.
            val head = (trackEnd * animatedProgress).coerceIn(0f, trackEnd)
            // Flatten the wave as the bar fills, so completion reads as "settled".
            val amp = amplitude.toPx() * (1f - animatedProgress).coerceIn(0f, 1f)

            if (head > 0.5f) {
                drawWave(
                    startX = 0f,
                    endX = head,
                    centerY = centerY,
                    amplitude = amp,
                    wavelength = wavelengthPx,
                    phase = phase,
                    color = color,
                    strokeWidth = strokePx,
                )
            }

            val trackStart = (head + gapPx).coerceAtMost(trackEnd)
            if (trackStart < trackEnd) {
                drawLine(
                    color = trackColor,
                    start = Offset(trackStart, centerY),
                    end = Offset(trackEnd, centerY),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
            }
        } else {
            // Indeterminate: full track, one travelling wavy segment on top of it.
            drawLine(
                color = trackColor,
                start = Offset(0f, centerY),
                end = Offset(trackEnd, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )

            val head = smoothStep((sweep * 1.7f).coerceIn(0f, 1f)) * trackEnd
            val tail = smoothStep(((sweep - 0.4f) * 1.7f).coerceIn(0f, 1f)) * trackEnd
            if (head - tail > 1f) {
                drawWave(
                    startX = tail,
                    endX = head,
                    centerY = centerY,
                    amplitude = amplitude.toPx(),
                    wavelength = wavelengthPx,
                    phase = phase,
                    color = color,
                    strokeWidth = strokePx,
                )
            }
        }

        if (showStopIndicator) {
            drawCircle(
                color = color,
                radius = stopRadius,
                center = Offset(size.width - stopRadius, centerY),
            )
        }
    }
}

/**
 * Wavy circular progress indicator. Same idea in polar coordinates: the radius is modulated
 * by a sine so the ring ripples, and the whole thing spins when indeterminate.
 */
@Composable
fun WavyCircularProgressIndicator(
    progress: Float?,
    modifier: Modifier = Modifier,
    diameter: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    stroke: Dp = 4.dp,
    amplitude: Dp = 2.dp,
    waves: Int = 10,
) {
    val transition = rememberInfiniteTransition(label = "wavy-circular")

    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    val target = progress?.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = target ?: 0f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "progress",
    )

    Canvas(
        modifier = modifier
            .size(diameter)
            .graphicsLayer { rotationZ = if (target == null) spin else 0f },
    ) {
        val strokePx = stroke.toPx()
        val ampPx = amplitude.toPx()
        val radius = (min(size.width, size.height) - strokePx - ampPx * 2f) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        val fraction = if (target != null) animatedProgress else 0.28f
        val startAngle = -PI.toFloat() / 2f

        // Track is the full ripple ring; the active arc is the same ring clipped to value.
        drawRipple(
            center = center,
            radius = radius,
            amplitude = ampPx,
            waves = waves,
            phase = phase,
            startAngle = startAngle,
            sweep = (2f * PI).toFloat(),
            color = trackColor,
            strokeWidth = strokePx,
        )

        if (fraction > 0.001f) {
            drawRipple(
                center = center,
                radius = radius,
                amplitude = ampPx,
                waves = waves,
                phase = phase,
                startAngle = startAngle,
                sweep = (2f * PI).toFloat() * fraction,
                color = color,
                strokeWidth = strokePx,
            )
        }
    }
}

/**
 * The Expressive "loading indicator": a filled blob that morphs between rounded polygon
 * shapes while it rotates.
 *
 * Built from two harmonics of the radius. Using whole numbers of lobes keeps the curve
 * closed (r(0) == r(2 pi)), and cross-fading their weights is what produces the squish.
 */
@Composable
fun MorphingLoadingIndicator(
    modifier: Modifier = Modifier,
    diameter: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "morph")

    val morph by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "morph",
    )

    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    Canvas(
        modifier = modifier
            .size(diameter)
            .graphicsLayer { rotationZ = spin },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val base = min(size.width, size.height) / 2f * 0.92f
        val fourLobe = 0.10f * (1f - morph)
        val sixLobe = 0.10f * morph

        val path = Path()
        val steps = 180
        for (i in 0..steps) {
            val angle = (2f * PI.toFloat()) * i / steps
            val r = base * (1f + fourLobe * cos(4f * angle) + sixLobe * cos(6f * angle))
            val x = center.x + r * cos(angle)
            val y = center.y + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path = path, color = color)
    }
}

/** Three dots that breathe in sequence. Cheap, and reads as "working" at any size. */
@Composable
fun PulsingDots(
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    count: Int = 3,
) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val scale by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(offsetMillis = index * 160),
                ),
                label = "dot" + index,
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.35f + 0.65f * scale
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * Skeleton placeholder with a highlight sweeping across it. Used while a screen waits for
 * its first sample instead of showing an empty card.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shift",
    )

    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(base),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val start = width * shift
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(start, 0f),
                    end = Offset(start + width * 0.6f, size.height),
                ),
            )
        }
    }
}

// ------------------------------------------------------------------ drawing helpers

/** Smoothstep, used to ease the indeterminate head and tail without an Animatable each. */
private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Strokes a sine wave between two x positions. */
private fun DrawScope.drawWave(
    startX: Float,
    endX: Float,
    centerY: Float,
    amplitude: Float,
    wavelength: Float,
    phase: Float,
    color: Color,
    strokeWidth: Float,
) {
    if (endX <= startX) return

    val path = Path()
    if (amplitude <= 0.25f || wavelength <= 0f) {
        path.moveTo(startX, centerY)
        path.lineTo(endX, centerY)
    } else {
        val k = (2f * PI.toFloat()) / wavelength
        var x = startX
        path.moveTo(startX, centerY + amplitude * sin(k * startX + phase))
        while (x < endX) {
            x = min(x + WAVE_SAMPLE_STEP_PX, endX)
            path.lineTo(x, centerY + amplitude * sin(k * x + phase))
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Strokes an arc whose radius ripples, i.e. the circular version of [drawWave]. */
private fun DrawScope.drawRipple(
    center: Offset,
    radius: Float,
    amplitude: Float,
    waves: Int,
    phase: Float,
    startAngle: Float,
    sweep: Float,
    color: Color,
    strokeWidth: Float,
) {
    if (sweep <= 0f || radius <= 0f) return

    val steps = (sweep / (2f * PI.toFloat()) * 180f).toInt().coerceAtLeast(8)
    val path = Path()
    for (i in 0..steps) {
        val angle = startAngle + sweep * i / steps
        val r = radius + amplitude * sin(waves * angle + phase)
        val x = center.x + r * cos(angle)
        val y = center.y + r * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
