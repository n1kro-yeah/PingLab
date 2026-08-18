package live.nikro.pinglab.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import live.nikro.pinglab.data.prefs.ThemeMode
import live.nikro.pinglab.data.prefs.ThemePalette

/** Semantic colours, provided alongside the Material scheme. */
val LocalStatusPalette = staticCompositionLocalOf { LightStatusPalette }

/** Sugar so screens can write `statusPalette().down` instead of touching the local directly. */
@Composable
fun statusPalette(): StatusPalette = LocalStatusPalette.current

/** True when the running device can hand us a wallpaper-derived scheme (Android 12+). */
val dynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Root theme.
 *
 * Colour resolution order:
 *  1. dynamic colour, when the user asked for it and the device is on Android 12+,
 *  2. otherwise the selected [ThemePalette], built from tonal palettes in `Tonal.kt`.
 *
 * @param themeMode light / dark / follow the system
 * @param palette which static palette to build the scheme from
 * @param dynamicColor prefer the wallpaper-derived scheme over [palette]
 * @param amoledBlack collapse the darkest dark-theme surfaces to true black
 */
@Composable
fun PingLabTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    palette: ThemePalette = ThemePalette.PURPLE,
    dynamicColor: Boolean = false,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val useDynamic = dynamicColor && dynamicColorSupported

    val spec = remember(palette) { paletteSpecFor(palette) }
    val staticScheme = remember(spec, darkTheme, amoledBlack) {
        if (darkTheme) spec.darkScheme(amoledBlack) else spec.lightScheme()
    }

    val colorScheme: ColorScheme = when {
        useDynamic && darkTheme -> {
            val dynamic = dynamicDarkColorScheme(context)
            if (amoledBlack) dynamic.toAmoled() else dynamic
        }

        useDynamic -> dynamicLightColorScheme(context)
        else -> staticScheme
    }

    val palettes = if (useDynamic) {
        statusPaletteForScheme(colorScheme, darkTheme)
    } else {
        statusPaletteFor(spec, darkTheme)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Edge-to-edge: only the icon tint needs to follow the theme, the bars stay
            // transparent so content can scroll behind them.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalStatusPalette provides palettes) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PingLabTypography,
            shapes = PingLabShapes,
            content = content,
        )
    }
}
