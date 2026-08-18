package live.nikro.pinglab.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import live.nikro.pinglab.data.prefs.ThemePalette

/*
 * Material 3 colour engine.
 *
 * M3 does not describe a theme as a bag of hex codes. A theme is five tonal palettes
 * (primary, secondary, tertiary, neutral, neutral variant), each one a single hue held at a
 * constant chroma and sampled at tones 0..100, plus a fixed mapping from colour roles to
 * tones. Light and dark schemes are the same palettes read at different tones, which is why
 * an M3 dark theme never looks like an inverted light theme.
 *
 * Reference: m3.material.io/styles/color/system/overview
 */

/** Key tones every palette below is authored at. Everything else is interpolated. */
private val KEY_TONES = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)

/**
 * One hue sampled across the tonal range.
 *
 * The 13 authored stops are the ones Material publishes; the surface tones M3 added in 2023
 * (4, 6, 12, 17, 22, 24, 87, 92, 94, 96, 98) sit between them and are interpolated, which is
 * accurate to about one step of 8-bit precision for the near-neutral ramps where they are used.
 */
@Immutable
class TonalPalette(
    val t0: Color,
    val t10: Color,
    val t20: Color,
    val t30: Color,
    val t40: Color,
    val t50: Color,
    val t60: Color,
    val t70: Color,
    val t80: Color,
    val t90: Color,
    val t95: Color,
    val t99: Color,
    val t100: Color,
) {
    private val stops: Array<Color> =
        arrayOf(t0, t10, t20, t30, t40, t50, t60, t70, t80, t90, t95, t99, t100)

    /** Returns the colour at [value] (0 = black end, 100 = white end). */
    fun tone(value: Int): Color {
        val target = value.coerceIn(0, 100)
        for (index in KEY_TONES.indices) {
            val stop = KEY_TONES[index]
            if (stop == target) return stops[index]
            if (stop > target) {
                val lowerTone = KEY_TONES[index - 1]
                val span = (stop - lowerTone).toFloat()
                val fraction = (target - lowerTone) / span
                return lerp(stops[index - 1], stops[index], fraction)
            }
        }
        return stops.last()
    }
}

/** Mixes [overlay] into [base]; used to give the grey ramps the low chroma M3 asks for. */
private fun tint(base: Color, overlay: Color, amount: Float): Color = lerp(base, overlay, amount)

/** Pure grey ramp. Tinting it with the seed produces the neutral and neutral-variant palettes. */
private val GreyRamp = TonalPalette(
    t0 = Color(0xFF000000),
    t10 = Color(0xFF1B1B1B),
    t20 = Color(0xFF303030),
    t30 = Color(0xFF474747),
    t40 = Color(0xFF5E5E5E),
    t50 = Color(0xFF777777),
    t60 = Color(0xFF919191),
    t70 = Color(0xFFACACAC),
    t80 = Color(0xFFC8C8C8),
    t90 = Color(0xFFE4E4E4),
    t95 = Color(0xFFF2F2F2),
    t99 = Color(0xFFFCFCFC),
    t100 = Color(0xFFFFFFFF),
)

/**
 * Builds a neutral ramp from the grey ramp and the seed.
 *
 * M3 neutrals are not pure grey: they carry chroma 4 (neutral) or 8 (neutral variant) of the
 * seed hue, which is what makes an M3 surface feel like it belongs to the theme instead of
 * looking like a screenshot of a grey box. The pure black and white ends stay untinted so
 * `surfaceContainerLowest` in light mode is still #FFFFFF.
 */
private fun neutralRamp(seed: Color, amount: Float): TonalPalette = TonalPalette(
    t0 = GreyRamp.t0,
    t10 = tint(GreyRamp.t10, seed, amount),
    t20 = tint(GreyRamp.t20, seed, amount),
    t30 = tint(GreyRamp.t30, seed, amount),
    t40 = tint(GreyRamp.t40, seed, amount),
    t50 = tint(GreyRamp.t50, seed, amount),
    t60 = tint(GreyRamp.t60, seed, amount),
    t70 = tint(GreyRamp.t70, seed, amount),
    t80 = tint(GreyRamp.t80, seed, amount),
    t90 = tint(GreyRamp.t90, seed, amount),
    t95 = tint(GreyRamp.t95, seed, amount),
    t99 = tint(GreyRamp.t99, seed, amount),
    t100 = GreyRamp.t100,
)

/** The error palette is a system constant in M3: it must not drift with the brand hue. */
private val ErrorPalette = TonalPalette(
    t0 = Color(0xFF000000),
    t10 = Color(0xFF410E0B),
    t20 = Color(0xFF601410),
    t30 = Color(0xFF8C1D18),
    t40 = Color(0xFFB3261E),
    t50 = Color(0xFFDC362E),
    t60 = Color(0xFFE46962),
    t70 = Color(0xFFEC928E),
    t80 = Color(0xFFF2B8B5),
    t90 = Color(0xFFF9DEDC),
    t95 = Color(0xFFFCEEEE),
    t99 = Color(0xFFFFFBF9),
    t100 = Color(0xFFFFFFFF),
)

/** Everything needed to build both schemes of one theme. */
@Immutable
class PaletteSpec(
    val id: ThemePalette,
    val seed: Color,
    val primary: TonalPalette,
    val secondary: TonalPalette,
    val tertiary: TonalPalette,
) {
    val neutral: TonalPalette = neutralRamp(seed, 0.05f)
    val neutralVariant: TonalPalette = neutralRamp(seed, 0.12f)

    /**
     * Light scheme role mapping, straight out of the M3 spec.
     * Primary is tone 40, its container tone 90, text on that container tone 10, and so on.
     */
    fun lightScheme(): ColorScheme = lightColorScheme(
        primary = primary.tone(40),
        onPrimary = primary.tone(100),
        primaryContainer = primary.tone(90),
        onPrimaryContainer = primary.tone(10),
        inversePrimary = primary.tone(80),
        secondary = secondary.tone(40),
        onSecondary = secondary.tone(100),
        secondaryContainer = secondary.tone(90),
        onSecondaryContainer = secondary.tone(10),
        tertiary = tertiary.tone(40),
        onTertiary = tertiary.tone(100),
        tertiaryContainer = tertiary.tone(90),
        onTertiaryContainer = tertiary.tone(10),
        error = ErrorPalette.tone(40),
        onError = ErrorPalette.tone(100),
        errorContainer = ErrorPalette.tone(90),
        onErrorContainer = ErrorPalette.tone(10),
        background = neutral.tone(98),
        onBackground = neutral.tone(10),
        surface = neutral.tone(98),
        onSurface = neutral.tone(10),
        surfaceVariant = neutralVariant.tone(90),
        onSurfaceVariant = neutralVariant.tone(30),
        surfaceTint = primary.tone(40),
        inverseSurface = neutral.tone(20),
        inverseOnSurface = neutral.tone(95),
        outline = neutralVariant.tone(50),
        outlineVariant = neutralVariant.tone(80),
        scrim = neutral.tone(0),
        surfaceBright = neutral.tone(98),
        surfaceDim = neutral.tone(87),
        surfaceContainerLowest = neutral.tone(100),
        surfaceContainerLow = neutral.tone(96),
        surfaceContainer = neutral.tone(94),
        surfaceContainerHigh = neutral.tone(92),
        surfaceContainerHighest = neutral.tone(90),
    )

    /**
     * Dark scheme role mapping. Accents move to tone 80 so they stay readable on a dark
     * surface, containers move to tone 30, and the surfaces sit in the 4..24 range rather
     * than at pure black.
     *
     * @param amoled collapses the darkest surfaces to true black for OLED panels, where an
     *   unlit pixel draws no power. It is deliberately not the default: M3 uses tone 6 so
     *   elevation stays legible.
     */
    fun darkScheme(amoled: Boolean = false): ColorScheme {
        val scheme = darkColorScheme(
            primary = primary.tone(80),
            onPrimary = primary.tone(20),
            primaryContainer = primary.tone(30),
            onPrimaryContainer = primary.tone(90),
            inversePrimary = primary.tone(40),
            secondary = secondary.tone(80),
            onSecondary = secondary.tone(20),
            secondaryContainer = secondary.tone(30),
            onSecondaryContainer = secondary.tone(90),
            tertiary = tertiary.tone(80),
            onTertiary = tertiary.tone(20),
            tertiaryContainer = tertiary.tone(30),
            onTertiaryContainer = tertiary.tone(90),
            error = ErrorPalette.tone(80),
            onError = ErrorPalette.tone(20),
            errorContainer = ErrorPalette.tone(30),
            onErrorContainer = ErrorPalette.tone(90),
            background = neutral.tone(6),
            onBackground = neutral.tone(90),
            surface = neutral.tone(6),
            onSurface = neutral.tone(90),
            surfaceVariant = neutralVariant.tone(30),
            onSurfaceVariant = neutralVariant.tone(80),
            surfaceTint = primary.tone(80),
            inverseSurface = neutral.tone(90),
            inverseOnSurface = neutral.tone(20),
            outline = neutralVariant.tone(60),
            outlineVariant = neutralVariant.tone(30),
            scrim = neutral.tone(0),
            surfaceBright = neutral.tone(24),
            surfaceDim = neutral.tone(6),
            surfaceContainerLowest = neutral.tone(4),
            surfaceContainerLow = neutral.tone(10),
            surfaceContainer = neutral.tone(12),
            surfaceContainerHigh = neutral.tone(17),
            surfaceContainerHighest = neutral.tone(22),
        )
        return if (amoled) scheme.toAmoled() else scheme
    }
}

/** Pulls the darkest surfaces down to true black while keeping container separation. */
fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0C),
    surfaceContainer = Color(0xFF121215),
    surfaceContainerHigh = Color(0xFF1B1B1F),
    surfaceContainerHighest = Color(0xFF242429),
)

/*
 * ---------------------------------------------------------------------------------------
 * The palettes themselves.
 * ---------------------------------------------------------------------------------------
 */

/**
 * The Material 3 baseline palette, seeded from #6750A4.
 *
 * This purple is not an arbitrary brand colour: it is the scheme Material ships when no
 * dynamic colour and no custom seed are available, so every token below is the published
 * baseline value rather than something re-derived. Tone 40 (#6750A4) is the light primary,
 * tone 80 (#D0BCFF) the dark primary, tone 90 (#EADDFF) the light primary container.
 */
private val PurpleSpec = PaletteSpec(
    id = ThemePalette.PURPLE,
    seed = Color(0xFF6750A4),
    primary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF21005D),
        t20 = Color(0xFF381E72),
        t30 = Color(0xFF4F378B),
        t40 = Color(0xFF6750A4),
        t50 = Color(0xFF7F67BE),
        t60 = Color(0xFF9A82DB),
        t70 = Color(0xFFB69DF8),
        t80 = Color(0xFFD0BCFF),
        t90 = Color(0xFFEADDFF),
        t95 = Color(0xFFF6EDFF),
        t99 = Color(0xFFFFFBFE),
        t100 = Color(0xFFFFFFFF),
    ),
    secondary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF1D192B),
        t20 = Color(0xFF332D41),
        t30 = Color(0xFF4A4458),
        t40 = Color(0xFF625B71),
        t50 = Color(0xFF7A7289),
        t60 = Color(0xFF958DA5),
        t70 = Color(0xFFB0A7C0),
        t80 = Color(0xFFCCC2DC),
        t90 = Color(0xFFE8DEF8),
        t95 = Color(0xFFF6EDFF),
        t99 = Color(0xFFFFFBFE),
        t100 = Color(0xFFFFFFFF),
    ),
    tertiary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF31111D),
        t20 = Color(0xFF492532),
        t30 = Color(0xFF633B48),
        t40 = Color(0xFF7D5260),
        t50 = Color(0xFF986977),
        t60 = Color(0xFFB58392),
        t70 = Color(0xFFD29DAC),
        t80 = Color(0xFFEFB8C8),
        t90 = Color(0xFFFFD8E4),
        t95 = Color(0xFFFFECF1),
        t99 = Color(0xFFFFFBFA),
        t100 = Color(0xFFFFFFFF),
    ),
)

/** The original PingLab brand blue, kept so existing installs can stay on it. */
private val BlueSpec = PaletteSpec(
    id = ThemePalette.BLUE,
    seed = Color(0xFF2C5FE0),
    primary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF00164E),
        t20 = Color(0xFF002A78),
        t30 = Color(0xFF0F41AC),
        t40 = Color(0xFF2C5FE0),
        t50 = Color(0xFF4D79FF),
        t60 = Color(0xFF7B97FF),
        t70 = Color(0xFF9AAEFF),
        t80 = Color(0xFFB6C4FF),
        t90 = Color(0xFFDCE1FF),
        t95 = Color(0xFFEEF0FF),
        t99 = Color(0xFFFEFBFF),
        t100 = Color(0xFFFFFFFF),
    ),
    secondary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF151B2C),
        t20 = Color(0xFF2A3042),
        t30 = Color(0xFF404659),
        t40 = Color(0xFF585E71),
        t50 = Color(0xFF71768A),
        t60 = Color(0xFF8B90A5),
        t70 = Color(0xFFA5AAC0),
        t80 = Color(0xFFC1C5DD),
        t90 = Color(0xFFDDE1F9),
        t95 = Color(0xFFEFF0FF),
        t99 = Color(0xFFFEFBFF),
        t100 = Color(0xFFFFFFFF),
    ),
    tertiary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF2B1228),
        t20 = Color(0xFF42283E),
        t30 = Color(0xFF5A3E55),
        t40 = Color(0xFF74546D),
        t50 = Color(0xFF8E6C86),
        t60 = Color(0xFFAA85A1),
        t70 = Color(0xFFC79FBC),
        t80 = Color(0xFFE2BBD8),
        t90 = Color(0xFFFED7F2),
        t95 = Color(0xFFFFEBF7),
        t99 = Color(0xFFFFFBFF),
        t100 = Color(0xFFFFFFFF),
    ),
)

/** A calm green for people who read "up" as green and want the whole UI to agree. */
private val GreenSpec = PaletteSpec(
    id = ThemePalette.GREEN,
    seed = Color(0xFF006D3B),
    primary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF00210F),
        t20 = Color(0xFF00391D),
        t30 = Color(0xFF00522C),
        t40 = Color(0xFF006D3B),
        t50 = Color(0xFF00894B),
        t60 = Color(0xFF00A65C),
        t70 = Color(0xFF2FC276),
        t80 = Color(0xFF55DF90),
        t90 = Color(0xFF74FCA9),
        t95 = Color(0xFFC6FFD5),
        t99 = Color(0xFFF5FFF4),
        t100 = Color(0xFFFFFFFF),
    ),
    secondary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF0C1F14),
        t20 = Color(0xFF223528),
        t30 = Color(0xFF384B3E),
        t40 = Color(0xFF4F6354),
        t50 = Color(0xFF677C6C),
        t60 = Color(0xFF809685),
        t70 = Color(0xFF9AB19F),
        t80 = Color(0xFFB5CDBA),
        t90 = Color(0xFFD1E9D5),
        t95 = Color(0xFFDFF7E3),
        t99 = Color(0xFFF5FFF4),
        t100 = Color(0xFFFFFFFF),
    ),
    tertiary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF001F26),
        t20 = Color(0xFF003641),
        t30 = Color(0xFF004E5C),
        t40 = Color(0xFF00677A),
        t50 = Color(0xFF008299),
        t60 = Color(0xFF009DB8),
        t70 = Color(0xFF22B9D6),
        t80 = Color(0xFF4FD5F3),
        t90 = Color(0xFFB0EBFF),
        t95 = Color(0xFFDBF5FF),
        t99 = Color(0xFFF7FDFF),
        t100 = Color(0xFFFFFFFF),
    ),
)

/** Warm amber, useful at night and the highest contrast option against dark charts. */
private val AmberSpec = PaletteSpec(
    id = ThemePalette.AMBER,
    seed = Color(0xFF8B5000),
    primary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF2C1600),
        t20 = Color(0xFF4A2800),
        t30 = Color(0xFF6A3B00),
        t40 = Color(0xFF8B5000),
        t50 = Color(0xFFAD6600),
        t60 = Color(0xFFD07C00),
        t70 = Color(0xFFF19300),
        t80 = Color(0xFFFFB86B),
        t90 = Color(0xFFFFDCBE),
        t95 = Color(0xFFFFEEE0),
        t99 = Color(0xFFFFFBFF),
        t100 = Color(0xFFFFFFFF),
    ),
    secondary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF291806),
        t20 = Color(0xFF412C19),
        t30 = Color(0xFF5A422D),
        t40 = Color(0xFF755A3F),
        t50 = Color(0xFF8F7356),
        t60 = Color(0xFFAA8C6E),
        t70 = Color(0xFFC7A787),
        t80 = Color(0xFFE4C2A1),
        t90 = Color(0xFFFFDDB9),
        t95 = Color(0xFFFFEEDD),
        t99 = Color(0xFFFFFBFF),
        t100 = Color(0xFFFFFFFF),
    ),
    tertiary = TonalPalette(
        t0 = Color(0xFF000000),
        t10 = Color(0xFF231A00),
        t20 = Color(0xFF3B2F00),
        t30 = Color(0xFF554500),
        t40 = Color(0xFF6F5C00),
        t50 = Color(0xFF8A7400),
        t60 = Color(0xFFA78E00),
        t70 = Color(0xFFC4A900),
        t80 = Color(0xFFE2C500),
        t90 = Color(0xFFFFE264),
        t95 = Color(0xFFFFF2B7),
        t99 = Color(0xFFFFFBFF),
        t100 = Color(0xFFFFFFFF),
    ),
)

/** Every palette offered on the Theme tab, in display order. */
val PaletteSpecs: List<PaletteSpec> = listOf(PurpleSpec, BlueSpec, GreenSpec, AmberSpec)

fun paletteSpecFor(palette: ThemePalette): PaletteSpec =
    PaletteSpecs.firstOrNull { it.id == palette } ?: PurpleSpec

/**
 * Re-tints the chart colours so graphs follow the chosen palette, while the semantic
 * status colours (up / degraded / down) stay put: a red "down" badge must never turn amber
 * because someone liked the amber theme.
 */
fun statusPaletteFor(spec: PaletteSpec, dark: Boolean): StatusPalette {
    val base = if (dark) DarkStatusPalette else LightStatusPalette
    val line = if (dark) spec.primary.tone(80) else spec.primary.tone(40)
    return base.copy(
        chartLine = line,
        chartFillTop = line.copy(alpha = 0.40f),
        chartFillBottom = line.copy(alpha = 0.04f),
        chartAverage = if (dark) spec.tertiary.tone(80) else spec.tertiary.tone(40),
        chartJitter = if (dark) spec.secondary.tone(70) else spec.secondary.tone(50),
    )
}

/** Same idea, but for a scheme we did not build ourselves (dynamic colour). */
fun statusPaletteForScheme(scheme: ColorScheme, dark: Boolean): StatusPalette {
    val base = if (dark) DarkStatusPalette else LightStatusPalette
    return base.copy(
        chartLine = scheme.primary,
        chartFillTop = scheme.primary.copy(alpha = 0.40f),
        chartFillBottom = scheme.primary.copy(alpha = 0.04f),
        chartAverage = scheme.tertiary,
        chartJitter = scheme.secondary,
    )
}
