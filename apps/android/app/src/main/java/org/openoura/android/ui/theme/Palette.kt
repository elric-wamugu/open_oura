package org.openoura.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// TRANSITIONAL. The dashboard-era token set, now *derived* from the Material scheme so
// screens that still read `Oura.colors` follow the brand / Material You scheme and light
// and dark without being rewritten first. Screens move to `MaterialTheme.colorScheme` and
// `MaterialTheme.dataColors` as they are restyled; this file goes once nothing reads it.

data class OuraColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val lineSoft: Color,
    val accent: Color,
    val accentSoft: Color,
    val warn: Color,
    val deep: Color,
    val light: Color,
    val rem: Color,
    val wake: Color,
)

/** Token → role. accentSoft keeps its old meaning: a faint wash of the accent. */
internal fun ouraColorsFrom(s: ColorScheme, d: DataColors) = OuraColors(
    bg = s.surface,
    surface = s.surfaceContainer,
    surface2 = s.surfaceContainerHigh,
    text = s.onSurface,
    muted = s.onSurfaceVariant,
    faint = s.outline,
    line = s.outlineVariant,
    lineSoft = s.outlineVariant.copy(alpha = 0.5f),
    accent = s.primary,
    accentSoft = s.primary.copy(alpha = 0.12f),
    warn = d.warnMark,
    deep = d.deep,
    light = d.light,
    rem = d.rem,
    wake = d.wake,
)

val LocalOuraColors = staticCompositionLocalOf { ouraColorsFrom(BrandDark, DarkData) }

object Oura {
    val colors: OuraColors
        @Composable @ReadOnlyComposable get() = LocalOuraColors.current
}
