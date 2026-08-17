package live.nikro.pinglab.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.data.prefs.ChartStyle
import live.nikro.pinglab.domain.stats.LatencyStatistics
import live.nikro.pinglab.ui.theme.statusPalette
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Charts are hand-drawn on a Compose Canvas instead of pulling in a charting library.
 * Reasons: ping data has gaps (a lost packet is a null, not a zero), the app needs a
 * 1 Hz redraw without allocation churn, and every colour has to come from the Material 3
 * scheme so dynamic colour keeps working.
 */

private const val GRID_DIVISIONS = 4

/**
 * Main latency chart: line / area / bars, with a gap wherever a packet was lost.
 *
 * @param samples ordered oldest -> newest, `null` means the probe failed.
 */
@Composable
fun LatencyChart(
    samples: List<Double?>,
    modifier: Modifier = Modifier,
    style: ChartStyle = ChartStyle.AREA,
    showGrid: Boolean = true,
    animate: Boolean = true,
    averageMs: Double? = null,
    thresholdMs: Double? = null,
    maxPoints: Int = 120,
    height: Dp = 190.dp,
    emptyLabel: String = "Waiting for data",
) {
    val palette = statusPalette()
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = remember(labelColor) { TextStyle(color = labelColor, fontSize = 10.sp) }
    val emptyStyle = remember(labelColor) { TextStyle(color = labelColor, fontSize = 13.sp) }

    val window = remember(samples, maxPoints) {
        if (samples.size > maxPoints) samples.takeLast(maxPoints) else samples
    }
    val values = remember(window) { window.filterNotNull() }
    val ceiling = remember(values, thresholdMs) {
        niceCeiling(max(values.maxOrNull() ?: 0.0, thresholdMs ?: 0.0))
    }

    val ceilingF = ceiling.toFloat().coerceAtLeast(1f)

    // Animating the axis instead of snapping keeps a spike from making the whole chart jump.
    //
    // These two values change on every single animation frame. They are deliberately kept as
    // State and read inside the draw lambda below instead of being unwrapped with `by` here:
    // a state read during composition invalidates composition, layout and draw for the whole
    // chart on every frame, while a read inside the draw lambda repeats the draw phase only.
    // On a 120 Hz screen that is the difference between smooth and visibly stuttering.
    val animatedMaxState = animateFloatAsState(
        targetValue = ceilingF,
        animationSpec = tween(durationMillis = if (animate) 420 else 0),
        label = "axisMax",
    )
    val revealState = animateFloatAsState(
        targetValue = if (values.isEmpty()) 0f else 1f,
        animationSpec = tween(durationMillis = if (animate) 520 else 0),
        label = "reveal",
    )

    // Text measurement and PathEffect allocation are expensive, and neither depends on the
    // animated values, so both happen once per data change instead of once per frame.
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f) }
    val axisLabels = remember(ceilingF, labelStyle) {
        List(GRID_DIVISIONS + 1) { index ->
            measurer.measure(
                text = Formatters.axisLatency(ceilingF * (index / GRID_DIVISIONS.toFloat())),
                style = labelStyle,
            )
        }
    }
    val emptyLayout = remember(emptyLabel, emptyStyle) { measurer.measure(emptyLabel, emptyStyle) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        // Draw-phase reads: everything below sees a plain Float, but Compose only invalidates
        // the draw phase when these change.
        val animatedMax = animatedMaxState.value
        val reveal = revealState.value

        val gutter = if (showGrid) 42.dp.toPx() else 6.dp.toPx()
        val plotLeft = gutter
        val plotTop = 10.dp.toPx()
        val plotRight = size.width - 10.dp.toPx()
        val plotBottom = size.height - 14.dp.toPx()
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        if (window.isEmpty() || values.isEmpty()) {
            val layout = emptyLayout
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (size.width - layout.size.width) / 2f,
                    y = (size.height - layout.size.height) / 2f,
                ),
            )
            return@Canvas
        }

        val dash = dashEffect

        if (showGrid) {
            for (i in 0..GRID_DIVISIONS) {
                val fraction = i / GRID_DIVISIONS.toFloat()
                val y = plotBottom - fraction * plotHeight
                drawLine(
                    color = palette.chartGrid,
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1f,
                    pathEffect = if (i == 0) null else dash,
                )
                val layout = axisLabels[i]
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = plotLeft - layout.size.width - 6.dp.toPx(),
                        y = y - layout.size.height / 2f,
                    ),
                )
            }
        }

        val count = window.size
        val stepX = if (count > 1) plotWidth / (count - 1) else plotWidth
        fun xAt(index: Int): Float =
            if (count > 1) plotLeft + index * stepX else plotLeft + plotWidth / 2f

        fun yAt(value: Double): Float {
            val ratio = (value / animatedMax).coerceIn(0.0, 1.0).toFloat()
            return plotBottom - ratio * plotHeight
        }

        // Packet loss: a translucent band so a gap reads as "lost", not "no data".
        val bandWidth = max(2f, stepX * 0.7f)
        window.forEachIndexed { index, value ->
            if (value == null) {
                val x = xAt(index)
                drawRect(
                    color = palette.chartLoss.copy(alpha = 0.16f),
                    topLeft = Offset(x - bandWidth / 2f, plotTop),
                    size = Size(bandWidth, plotHeight),
                )
                drawCircle(
                    color = palette.chartLoss,
                    radius = 2.dp.toPx(),
                    center = Offset(x, plotBottom),
                )
            }
        }

        clipRect(left = 0f, top = 0f, right = plotLeft + plotWidth * reveal, bottom = size.height) {
            when (style) {
                ChartStyle.BARS -> {
                    val barWidth = (stepX * 0.62f).coerceIn(2f, 16.dp.toPx())
                    window.forEachIndexed { index, value ->
                        if (value == null) return@forEachIndexed
                        val top = yAt(value)
                        val x = xAt(index)
                        val color = when {
                            thresholdMs != null && value > thresholdMs -> palette.degraded
                            else -> palette.chartLine
                        }
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x - barWidth / 2f, top),
                            size = Size(barWidth, (plotBottom - top).coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(barWidth / 2.5f, barWidth / 2.5f),
                        )
                    }
                }

                ChartStyle.LINE, ChartStyle.AREA -> {
                    val segments = contiguousSegments(window)
                    segments.forEach { segment ->
                        if (segment.isEmpty()) return@forEach

                        if (style == ChartStyle.AREA && segment.size > 1) {
                            val fill = Path().apply {
                                moveTo(xAt(segment.first().first), plotBottom)
                                segment.forEach { (index, value) -> lineTo(xAt(index), yAt(value)) }
                                lineTo(xAt(segment.last().first), plotBottom)
                                close()
                            }
                            drawPath(
                                path = fill,
                                brush = Brush.verticalGradient(
                                    colors = listOf(palette.chartFillTop, palette.chartFillBottom),
                                    startY = plotTop,
                                    endY = plotBottom,
                                ),
                            )
                        }

                        if (segment.size == 1) {
                            val (index, value) = segment.first()
                            drawCircle(
                                color = palette.chartLine,
                                radius = 2.5.dp.toPx(),
                                center = Offset(xAt(index), yAt(value)),
                            )
                        } else {
                            val line = Path().apply {
                                segment.forEachIndexed { position, (index, value) ->
                                    val point = Offset(xAt(index), yAt(value))
                                    if (position == 0) moveTo(point.x, point.y)
                                    else lineTo(point.x, point.y)
                                }
                            }
                            drawPath(
                                path = line,
                                color = palette.chartLine,
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                    }
                }
            }
        }

        if (averageMs != null && averageMs > 0.0) {
            val y = yAt(averageMs)
            drawLine(
                color = palette.chartAverage,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
            )
        }

        if (thresholdMs != null && thresholdMs > 0.0) {
            val y = yAt(thresholdMs)
            drawLine(
                color = palette.degraded.copy(alpha = 0.7f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 7f), 0f),
            )
        }

        // Highlight the newest successful sample so the eye tracks "now".
        val lastIndex = window.indexOfLast { it != null }
        if (lastIndex >= 0 && reveal > 0.98f) {
            val value = window[lastIndex] ?: 0.0
            val center = Offset(xAt(lastIndex), yAt(value))
            drawCircle(palette.chartLine.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = center)
            drawCircle(palette.chartLine, radius = 3.5.dp.toPx(), center = center)
        }
    }
}

/** Tiny inline trend line used inside host cards and list rows. */
@Composable
fun Sparkline(
    samples: List<Double?>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    filled: Boolean = true,
    height: Dp = 34.dp,
    maxPoints: Int = 60,
) {
    val palette = statusPalette()
    val lineColor = color ?: palette.chartLine
    val window = remember(samples, maxPoints) {
        if (samples.size > maxPoints) samples.takeLast(maxPoints) else samples
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val values = window.filterNotNull()
        if (values.size < 2) return@Canvas

        val minValue = values.min()
        val maxValue = values.max()
        val span = (maxValue - minValue).takeIf { it > 0.5 } ?: 1.0
        val padding = 3.dp.toPx()
        val usableHeight = (size.height - padding * 2).coerceAtLeast(1f)
        val stepX = size.width / (window.size - 1).coerceAtLeast(1)

        fun pointFor(index: Int, value: Double) = Offset(
            x = index * stepX,
            y = padding + (1.0 - (value - minValue) / span).toFloat() * usableHeight,
        )

        contiguousSegments(window).forEach { segment ->
            if (segment.size < 2) return@forEach
            val path = Path().apply {
                segment.forEachIndexed { position, (index, value) ->
                    val point = pointFor(index, value)
                    if (position == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
            if (filled) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(pointFor(segment.last().first, segment.last().second).x, size.height)
                    lineTo(pointFor(segment.first().first, segment.first().second).x, size.height)
                    close()
                }
                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.28f), Color.Transparent),
                    ),
                )
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        window.forEachIndexed { index, value ->
            if (value == null) {
                drawCircle(
                    color = palette.chartLoss,
                    radius = 1.6.dp.toPx(),
                    center = Offset(index * stepX, size.height - padding),
                )
            }
        }
    }
}

/** Distribution of round-trip times; makes bimodal latency obvious at a glance. */
@Composable
fun LatencyHistogramChart(
    buckets: List<LatencyStatistics.HistogramBucket>,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
) {
    val palette = statusPalette()
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = remember(labelColor) { TextStyle(color = labelColor, fontSize = 9.sp) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (buckets.isEmpty()) return@Canvas
        val peak = buckets.maxOf { it.count }.coerceAtLeast(1)
        val bottom = size.height - 14.dp.toPx()
        val top = 6.dp.toPx()
        val usable = (bottom - top).coerceAtLeast(1f)
        val slot = size.width / buckets.size
        val barWidth = (slot * 0.72f).coerceAtLeast(2f)

        buckets.forEachIndexed { index, bucket ->
            val ratio = bucket.count.toFloat() / peak
            val barHeight = (ratio * usable).coerceAtLeast(if (bucket.count > 0) 2.dp.toPx() else 0f)
            val x = index * slot + (slot - barWidth) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(palette.chartLine, palette.chartLine.copy(alpha = 0.55f)),
                    startY = bottom - barHeight,
                    endY = bottom,
                ),
                topLeft = Offset(x, bottom - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }

        drawLine(
            color = palette.chartGrid,
            start = Offset(0f, bottom),
            end = Offset(size.width, bottom),
            strokeWidth = 1f,
        )

        // Only the extremes get labels: anything more is unreadable at phone width.
        val first = measurer.measure(Formatters.axisLatency(buckets.first().fromMs.toFloat()), labelStyle)
        drawText(first, topLeft = Offset(0f, bottom + 2.dp.toPx()))
        val last = measurer.measure(Formatters.axisLatency(buckets.last().toMs.toFloat()), labelStyle)
        drawText(last, topLeft = Offset(size.width - last.size.width, bottom + 2.dp.toPx()))
    }
}

/** Per-packet delta chart: the visual counterpart of the RFC 3550 jitter number. */
@Composable
fun JitterChart(
    samples: List<Double?>,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
    maxPoints: Int = 80,
) {
    val palette = statusPalette()
    val deltas = remember(samples, maxPoints) {
        val window = if (samples.size > maxPoints) samples.takeLast(maxPoints) else samples
        buildList {
            var previous: Double? = null
            window.forEach { value ->
                if (value != null && previous != null) add(value - previous!!)
                if (value != null) previous = value
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (deltas.isEmpty()) return@Canvas
        val scale = deltas.maxOf { abs(it) }.coerceAtLeast(1.0)
        val middle = size.height / 2f
        val slot = size.width / deltas.size
        val barWidth = (slot * 0.6f).coerceIn(1.5f, 10.dp.toPx())

        drawLine(
            color = palette.chartGrid,
            start = Offset(0f, middle),
            end = Offset(size.width, middle),
            strokeWidth = 1f,
        )

        deltas.forEachIndexed { index, delta ->
            val magnitude = (abs(delta) / scale).toFloat() * (middle - 4.dp.toPx())
            val x = index * slot + (slot - barWidth) / 2f
            val top = if (delta >= 0) middle - magnitude else middle
            drawRoundRect(
                color = if (delta >= 0) palette.chartJitter else palette.chartJitter.copy(alpha = 0.55f),
                topLeft = Offset(x, top),
                size = Size(barWidth, magnitude.coerceAtLeast(1f)),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** Packet-loss donut with the number in the middle. */
@Composable
fun LossDonut(
    lossPercent: Double,
    modifier: Modifier = Modifier,
    diameter: Dp = 116.dp,
    caption: String? = null,
) {
    val palette = statusPalette()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = when {
        lossPercent <= 0.5 -> palette.up
        lossPercent <= 5.0 -> palette.degraded
        else -> palette.down
    }
    val sweep by animateFloatAsState(
        targetValue = (lossPercent / 100.0).coerceIn(0.0, 1.0).toFloat(),
        animationSpec = tween(durationMillis = 600),
        label = "loss",
    )

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = Formatters.percent(lossPercent),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 240-degree gauge for the 0..100 connection quality score. */
@Composable
fun QualityGauge(
    score: Int,
    gradeLabel: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 148.dp,
) {
    val palette = statusPalette()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = palette.forScore(score)
    val progress by animateFloatAsState(
        targetValue = (score / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 700),
        label = "score",
    )

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val startAngle = 150f
            val fullSweep = 240f
            drawArc(
                color = track,
                startAngle = startAngle,
                sweepAngle = fullSweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = fullSweep * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            Text(
                text = gradeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Horizontal bars for traceroute hops; `null` renders as a dimmed "timeout" bar. */
@Composable
fun HopLatencyBars(
    values: List<Double?>,
    modifier: Modifier = Modifier,
    barHeight: Dp = 8.dp,
) {
    val palette = statusPalette()
    val peak = remember(values) { values.filterNotNull().maxOrNull() ?: 1.0 }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.forEach { value ->
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
            ) {
                val ratio = if (value == null) 1f else (value / peak).coerceIn(0.05, 1.0).toFloat()
                val color = when {
                    value == null -> palette.chartLoss.copy(alpha = 0.35f)
                    value <= peak * 0.5 -> palette.up
                    value <= peak * 0.8 -> palette.degraded
                    else -> palette.down
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, size.height * (1f - ratio) / 2f),
                    size = Size(size.width, size.height * ratio),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                )
            }
        }
    }
}

/** Small colour key rendered under charts. */
@Composable
fun ChartLegend(
    entries: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = color, radius = size.minDimension / 2f)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
    }
}

/** Splits the series into runs of consecutive successful samples (index to value). */
private fun contiguousSegments(samples: List<Double?>): List<List<Pair<Int, Double>>> {
    val segments = mutableListOf<List<Pair<Int, Double>>>()
    var current = mutableListOf<Pair<Int, Double>>()
    samples.forEachIndexed { index, value ->
        if (value == null) {
            if (current.isNotEmpty()) {
                segments.add(current)
                current = mutableListOf()
            }
        } else {
            current.add(index to value)
        }
    }
    if (current.isNotEmpty()) segments.add(current)
    return segments
}

/** Rounds an axis maximum up to a readable 1 / 2 / 5 x 10^n step. */
private fun niceCeiling(value: Double): Double {
    if (value <= 0.0 || value.isNaN()) return 10.0
    val exponent = floor(log10(value))
    val magnitude = 10.0.pow(exponent)
    val normalized = value / magnitude
    val step = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return (step * magnitude).coerceAtLeast(5.0)
}

/** Shared helper so screens can colour a value the same way the charts do. */
fun DrawScope.dpToPxCompat(dp: Dp): Float = dp.toPx()

/** Rounds a latency to one decimal for compact chart tooltips. */
fun roundLatency(value: Double): Double = (value * 10.0).roundToInt() / 10.0
