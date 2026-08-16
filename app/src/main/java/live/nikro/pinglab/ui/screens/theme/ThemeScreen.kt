package live.nikro.pinglab.ui.screens.theme

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.sharp.Speed
import androidx.compose.material.icons.twotone.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import live.nikro.pinglab.R
import live.nikro.pinglab.data.prefs.ThemeMode
import live.nikro.pinglab.data.prefs.ThemePalette
import live.nikro.pinglab.ui.components.MorphingLoadingIndicator
import live.nikro.pinglab.ui.components.PulsingDots
import live.nikro.pinglab.ui.components.SectionCard
import live.nikro.pinglab.ui.components.ShimmerBox
import live.nikro.pinglab.ui.components.VSpace
import live.nikro.pinglab.ui.components.WavyCircularProgressIndicator
import live.nikro.pinglab.ui.components.WavyLinearProgressIndicator
import live.nikro.pinglab.ui.theme.PaletteSpec
import live.nikro.pinglab.ui.theme.PaletteSpecs
import live.nikro.pinglab.ui.theme.dynamicColorSupported
import live.nikro.pinglab.ui.theme.paletteSpecFor

/**
 * The Theme tab.
 *
 * Everything here writes straight to DataStore, so the change is visible in the same frame
 * across the whole app: the palette cards, the preview below them and the navigation bar are
 * all reading the one scheme that [live.nikro.pinglab.ui.theme.PingLabTheme] built.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    viewModel: ThemeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings

    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val dynamicActive = settings.useDynamicColor && dynamicColorSupported
    val activeSpec = paletteSpecFor(settings.themePalette)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_theme)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.nav_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "palettes") {
                PaletteSection(
                    selected = settings.themePalette,
                    dark = darkTheme,
                    dimmed = dynamicActive,
                    onSelect = viewModel::selectPalette,
                )
            }

            item(key = "appearance") {
                AppearanceSection(
                    themeMode = settings.themeMode,
                    dynamicColor = settings.useDynamicColor,
                    amoledBlack = settings.amoledBlack,
                    onThemeMode = viewModel::setThemeMode,
                    onDynamicColor = viewModel::setDynamicColor,
                    onAmoledBlack = viewModel::setAmoledBlack,
                )
            }

            item(key = "tonal") {
                TonalSection(spec = activeSpec)
            }

            item(key = "roles") {
                RolesSection()
            }

            item(key = "components") {
                ComponentsSection()
            }

            item(key = "loading") {
                LoadingSection()
            }

            item(key = "icons") {
                IconsSection()
            }
        }
    }
}

// -------------------------------------------------------------------------- palettes

@Composable
private fun PaletteSection(
    selected: ThemePalette,
    dark: Boolean,
    dimmed: Boolean,
    onSelect: (ThemePalette) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.theme_palette_title),
        subtitle = stringResource(R.string.theme_palette_subtitle),
    ) {
        if (dimmed) {
            Text(
                text = stringResource(R.string.theme_dynamic_overrides),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            VSpace(8)
        }

        PaletteSpecs.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { spec ->
                    PaletteCard(
                        spec = spec,
                        dark = dark,
                        selected = spec.id == selected,
                        onClick = { onSelect(spec.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Box(modifier = Modifier.weight(1f))
            }
            VSpace(12)
        }
    }
}

@Composable
private fun PaletteCard(
    spec: PaletteSpec,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentTone = if (dark) 80 else 40
    val containerTone = if (dark) 30 else 90

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(spec.primary.tone(accentTone), 26)
                Swatch(spec.secondary.tone(accentTone), 20)
                Swatch(spec.tertiary.tone(accentTone), 20)
                Swatch(spec.primary.tone(containerTone), 20)
                Box(modifier = Modifier.weight(1f))
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            VSpace(10)
            Text(
                text = stringResource(paletteNameRes(spec.id)),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(paletteCaptionRes(spec.id)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(6)
            Text(
                text = stringResource(R.string.theme_seed) + " " + hexOf(spec.seed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Swatch(color: Color, size: Int) {
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun paletteNameRes(palette: ThemePalette): Int = when (palette) {
    ThemePalette.PURPLE -> R.string.theme_palette_purple
    ThemePalette.BLUE -> R.string.theme_palette_blue
    ThemePalette.GREEN -> R.string.theme_palette_green
    ThemePalette.AMBER -> R.string.theme_palette_amber
}

private fun paletteCaptionRes(palette: ThemePalette): Int = when (palette) {
    ThemePalette.PURPLE -> R.string.theme_palette_purple_caption
    ThemePalette.BLUE -> R.string.theme_palette_blue_caption
    ThemePalette.GREEN -> R.string.theme_palette_green_caption
    ThemePalette.AMBER -> R.string.theme_palette_amber_caption
}

/**
 * Formats a colour the way the Material docs write it, so the seed is copy-pasteable.
 *
 * Goes through toArgb rather than reading Color.value directly: that field is a packed
 * 64-bit representation whose byte order is not ARGB, so masking it would print nonsense.
 */
private fun hexOf(color: Color): String {
    val rgb = color.toArgb() and 0xFFFFFF
    return "#" + rgb.toString(16).uppercase().padStart(6, '0')
}

// ------------------------------------------------------------------------ appearance

@Composable
private fun AppearanceSection(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    amoledBlack: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAmoledBlack: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.theme_mode_title)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = ThemeMode.entries
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    icon = {
                        Icon(
                            imageVector = when (mode) {
                                ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                                ThemeMode.LIGHT -> Icons.Outlined.LightMode
                                ThemeMode.DARK -> Icons.Outlined.DarkMode
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(stringResource(themeModeRes(mode))) },
                )
            }
        }

        VSpace(12)

        ThemeSwitchRow(
            title = stringResource(R.string.theme_dynamic_title),
            description = if (dynamicColorSupported) {
                stringResource(R.string.theme_dynamic_desc)
            } else {
                stringResource(R.string.theme_dynamic_unavailable)
            },
            checked = dynamicColor && dynamicColorSupported,
            enabled = dynamicColorSupported,
            onCheckedChange = onDynamicColor,
        )

        ThemeSwitchRow(
            title = stringResource(R.string.theme_amoled_title),
            description = stringResource(R.string.theme_amoled_desc),
            checked = amoledBlack,
            enabled = true,
            onCheckedChange = onAmoledBlack,
        )

        Text(
            text = stringResource(R.string.theme_api_level) + " " + Build.VERSION.SDK_INT,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun themeModeRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
}

@Composable
private fun ThemeSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

// ----------------------------------------------------------------------- tonal ramp

@Composable
private fun TonalSection(spec: PaletteSpec) {
    SectionCard(
        title = stringResource(R.string.theme_tonal_title),
        subtitle = stringResource(R.string.theme_tonal_subtitle),
    ) {
        val tones = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99)

        TonalRow(label = "Primary", tones = tones) { spec.primary.tone(it) }
        VSpace(8)
        TonalRow(label = "Secondary", tones = tones) { spec.secondary.tone(it) }
        VSpace(8)
        TonalRow(label = "Tertiary", tones = tones) { spec.tertiary.tone(it) }
        VSpace(8)
        TonalRow(label = "Neutral", tones = tones) { spec.neutral.tone(it) }
    }
}

@Composable
private fun TonalRow(
    label: String,
    tones: List<Int>,
    colorAt: (Int) -> Color,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    VSpace(4)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
    ) {
        tones.forEach { tone ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(colorAt(tone)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tone.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    // Tones up to 50 are dark enough to need light text on top.
                    color = if (tone <= 50) Color.White else Color.Black,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- colour roles

@Composable
private fun RolesSection() {
    val scheme = MaterialTheme.colorScheme
    SectionCard(
        title = stringResource(R.string.theme_roles_title),
        subtitle = stringResource(R.string.theme_roles_subtitle),
    ) {
        val roles = listOf(
            Triple("primary", scheme.primary, scheme.onPrimary),
            Triple("primaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer),
            Triple("secondary", scheme.secondary, scheme.onSecondary),
            Triple("secondaryContainer", scheme.secondaryContainer, scheme.onSecondaryContainer),
            Triple("tertiary", scheme.tertiary, scheme.onTertiary),
            Triple("tertiaryContainer", scheme.tertiaryContainer, scheme.onTertiaryContainer),
            Triple("error", scheme.error, scheme.onError),
            Triple("errorContainer", scheme.errorContainer, scheme.onErrorContainer),
            Triple("surface", scheme.surface, scheme.onSurface),
            Triple("surfaceContainer", scheme.surfaceContainer, scheme.onSurface),
            Triple("surfaceVariant", scheme.surfaceVariant, scheme.onSurfaceVariant),
            Triple("inverseSurface", scheme.inverseSurface, scheme.inverseOnSurface),
        )

        roles.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { (name, container, onContainer) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(container)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = onContainer,
                        )
                    }
                }
                if (pair.size == 1) Box(modifier = Modifier.weight(1f))
            }
            VSpace(8)
        }
    }
}

// ------------------------------------------------------------------------ components

@Composable
private fun ComponentsSection() {
    var sliderValue by remember { mutableFloatStateOf(0.6f) }
    var filterSelected by remember { mutableStateOf(true) }
    var switchOn by remember { mutableStateOf(true) }

    SectionCard(title = stringResource(R.string.theme_components_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text("Filled") }
            FilledTonalButton(onClick = {}) { Text("Tonal") }
            OutlinedButton(onClick = {}) { Text("Outlined") }
        }
        VSpace(10)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(onClick = {}, label = { Text("Assist") })
            FilterChip(
                selected = filterSelected,
                onClick = { filterSelected = !filterSelected },
                label = { Text("Filter") },
            )
            Switch(checked = switchOn, onCheckedChange = { switchOn = it })
        }
        VSpace(4)
        Slider(value = sliderValue, onValueChange = { sliderValue = it })
    }
}

// ------------------------------------------------------------------ loading previews

@Composable
private fun LoadingSection() {
    // A looping value so the determinate variants have something to show.
    val transition = rememberInfiniteTransition(label = "demo")
    val demoProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "demo-progress",
    )

    SectionCard(
        title = stringResource(R.string.theme_loading_title),
        subtitle = stringResource(R.string.theme_loading_subtitle),
    ) {
        Text(
            text = stringResource(R.string.theme_loading_determinate),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(6)
        WavyLinearProgressIndicator(
            progress = demoProgress,
            modifier = Modifier.fillMaxWidth(),
        )

        VSpace(12)
        Text(
            text = stringResource(R.string.theme_loading_indeterminate),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(6)
        WavyLinearProgressIndicator(
            progress = null,
            modifier = Modifier.fillMaxWidth(),
        )

        VSpace(16)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WavyCircularProgressIndicator(progress = demoProgress)
            WavyCircularProgressIndicator(progress = null)
            MorphingLoadingIndicator()
            PulsingDots()
        }

        VSpace(16)
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        )
    }
}

// ----------------------------------------------------------------------------- icons

@Composable
private fun IconsSection() {
    SectionCard(
        title = stringResource(R.string.theme_icons_title),
        subtitle = stringResource(R.string.theme_icons_subtitle),
    ) {
        // Material Symbols ships the same glyph in several styles; Compose exposes them as
        // Filled / Outlined / Rounded / Sharp / TwoTone.
        val styles = listOf<Pair<String, ImageVector>>(
            "Filled" to Icons.Filled.Speed,
            "Outlined" to Icons.Outlined.Speed,
            "Rounded" to Icons.Rounded.Speed,
            "Sharp" to Icons.Sharp.Speed,
            "TwoTone" to Icons.TwoTone.Speed,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            styles.forEach { (name, icon) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = icon,
                        contentDescription = name,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    VSpace(4)
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        VSpace(14)
        Text(
            text = stringResource(R.string.theme_icons_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(10)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf(
                Icons.Outlined.Dashboard,
                Icons.Outlined.Timeline,
                Icons.Outlined.Dns,
                Icons.Outlined.Build,
                Icons.Outlined.Palette,
            ).forEach { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
