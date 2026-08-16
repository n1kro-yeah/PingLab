package live.nikro.pinglab.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Static Material 3 palettes generated from the brand seed #2C5FE0.
 * They are the fallback when the device does not support dynamic color (< API 31) or the
 * user turned it off in Settings.
 */

val BrandPrimaryLight = Color(0xFF2C5FE0)
val BrandOnPrimaryLight = Color(0xFFFFFFFF)
val BrandPrimaryContainerLight = Color(0xFFDCE1FF)
val BrandOnPrimaryContainerLight = Color(0xFF00164E)
val BrandSecondaryLight = Color(0xFF585E71)
val BrandOnSecondaryLight = Color(0xFFFFFFFF)
val BrandSecondaryContainerLight = Color(0xFFDDE1F9)
val BrandOnSecondaryContainerLight = Color(0xFF151B2C)
val BrandTertiaryLight = Color(0xFF74546D)
val BrandOnTertiaryLight = Color(0xFFFFFFFF)
val BrandTertiaryContainerLight = Color(0xFFFED7F2)
val BrandOnTertiaryContainerLight = Color(0xFF2B1228)
val BrandErrorLight = Color(0xFFBA1A1A)
val BrandOnErrorLight = Color(0xFFFFFFFF)
val BrandErrorContainerLight = Color(0xFFFFDAD6)
val BrandOnErrorContainerLight = Color(0xFF410002)
val BrandBackgroundLight = Color(0xFFFEFBFF)
val BrandOnBackgroundLight = Color(0xFF1B1B1F)
val BrandSurfaceLight = Color(0xFFFEFBFF)
val BrandOnSurfaceLight = Color(0xFF1B1B1F)
val BrandSurfaceVariantLight = Color(0xFFE2E1EC)
val BrandOnSurfaceVariantLight = Color(0xFF45464F)
val BrandOutlineLight = Color(0xFF767680)
val BrandOutlineVariantLight = Color(0xFFC6C6D0)
val BrandSurfaceContainerLight = Color(0xFFF2F0F7)
val BrandSurfaceContainerHighLight = Color(0xFFECEAF2)
val BrandInverseSurfaceLight = Color(0xFF303034)
val BrandInverseOnSurfaceLight = Color(0xFFF2F0F4)

val BrandPrimaryDark = Color(0xFFB6C4FF)
val BrandOnPrimaryDark = Color(0xFF002C7A)
val BrandPrimaryContainerDark = Color(0xFF0F41AC)
val BrandOnPrimaryContainerDark = Color(0xFFDCE1FF)
val BrandSecondaryDark = Color(0xFFC1C5DD)
val BrandOnSecondaryDark = Color(0xFF2A3042)
val BrandSecondaryContainerDark = Color(0xFF404659)
val BrandOnSecondaryContainerDark = Color(0xFFDDE1F9)
val BrandTertiaryDark = Color(0xFFE2BBD8)
val BrandOnTertiaryDark = Color(0xFF42283E)
val BrandTertiaryContainerDark = Color(0xFF5A3E55)
val BrandOnTertiaryContainerDark = Color(0xFFFED7F2)
val BrandErrorDark = Color(0xFFFFB4AB)
val BrandOnErrorDark = Color(0xFF690005)
val BrandErrorContainerDark = Color(0xFF93000A)
val BrandOnErrorContainerDark = Color(0xFFFFDAD6)
val BrandBackgroundDark = Color(0xFF0E1330)
val BrandOnBackgroundDark = Color(0xFFE4E1E6)
val BrandSurfaceDark = Color(0xFF11142B)
val BrandOnSurfaceDark = Color(0xFFE4E1E6)
val BrandSurfaceVariantDark = Color(0xFF45464F)
val BrandOnSurfaceVariantDark = Color(0xFFC6C6D0)
val BrandOutlineDark = Color(0xFF90909A)
val BrandOutlineVariantDark = Color(0xFF45464F)
val BrandSurfaceContainerDark = Color(0xFF1A1D34)
val BrandSurfaceContainerHighDark = Color(0xFF23263D)
val BrandInverseSurfaceDark = Color(0xFFE4E1E6)
val BrandInverseOnSurfaceDark = Color(0xFF303034)

/**
 * Colours that carry meaning rather than branding.
 *
 * These deliberately live outside [androidx.compose.material3.ColorScheme]: "host is down"
 * must stay red even when dynamic color repaints the app green, and chart series need a
 * stable, distinguishable order.
 */
data class StatusPalette(
    val up: Color,
    val onUp: Color,
    val degraded: Color,
    val onDegraded: Color,
    val down: Color,
    val onDown: Color,
    val idle: Color,
    val onIdle: Color,
    val excellent: Color,
    val good: Color,
    val fair: Color,
    val poor: Color,
    val bad: Color,
    val chartLine: Color,
    val chartFillTop: Color,
    val chartFillBottom: Color,
    val chartGrid: Color,
    val chartAverage: Color,
    val chartJitter: Color,
    val chartLoss: Color,
) {
    /** Maps a 0..100 quality score onto the grade colours used by gauges and chips. */
    fun forScore(score: Int): Color = when {
        score >= 90 -> excellent
        score >= 75 -> good
        score >= 55 -> fair
        score >= 35 -> poor
        else -> bad
    }

    /** Latency colouring used by the log list and sparkline dots. */
    fun forLatency(latencyMs: Double?): Color = when {
        latencyMs == null -> down
        latencyMs < 50 -> up
        latencyMs < 120 -> good
        latencyMs < 250 -> degraded
        else -> poor
    }
}

val LightStatusPalette = StatusPalette(
    up = Color(0xFF1B873F),
    onUp = Color(0xFFFFFFFF),
    degraded = Color(0xFFB86E00),
    onDegraded = Color(0xFFFFFFFF),
    down = Color(0xFFC5221F),
    onDown = Color(0xFFFFFFFF),
    idle = Color(0xFF6B6F7B),
    onIdle = Color(0xFFFFFFFF),
    excellent = Color(0xFF1B873F),
    good = Color(0xFF5B9E1E),
    fair = Color(0xFFB86E00),
    poor = Color(0xFFDC5A12),
    bad = Color(0xFFC5221F),
    chartLine = Color(0xFF2C5FE0),
    chartFillTop = Color(0x662C5FE0),
    chartFillBottom = Color(0x0A2C5FE0),
    chartGrid = Color(0x1F1B1B1F),
    chartAverage = Color(0xFF74546D),
    chartJitter = Color(0xFF00838F),
    chartLoss = Color(0xFFC5221F),
)

val DarkStatusPalette = StatusPalette(
    up = Color(0xFF6DD58C),
    onUp = Color(0xFF00391A),
    degraded = Color(0xFFFFC46B),
    onDegraded = Color(0xFF3F2A00),
    down = Color(0xFFFF897D),
    onDown = Color(0xFF5F0014),
    idle = Color(0xFF9AA0AE),
    onIdle = Color(0xFF1B1B1F),
    excellent = Color(0xFF6DD58C),
    good = Color(0xFFA6D46A),
    fair = Color(0xFFFFC46B),
    poor = Color(0xFFFFA07A),
    bad = Color(0xFFFF897D),
    chartLine = Color(0xFFB6C4FF),
    chartFillTop = Color(0x66B6C4FF),
    chartFillBottom = Color(0x0AB6C4FF),
    chartGrid = Color(0x24E4E1E6),
    chartAverage = Color(0xFFE2BBD8),
    chartJitter = Color(0xFF4DD0E1),
    chartLoss = Color(0xFFFF897D),
)
