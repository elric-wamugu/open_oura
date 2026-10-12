package org.openoura.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Colours that carry meaning in the data, kept OUTSIDE the Material colour scheme on
// purpose. Deep sleep has to look like deep sleep on every night, in every chart, whether
// the scheme is the brand teal or repainted from the wallpaper — so these never follow
// Material You. Values are the web dashboard's (dashboard/web/styles.css).

/** The amber "needs attention" family. Generated alongside the scheme by gen-theme.py. */
@Immutable
data class WarnColors(
    val warn: Color,
    val onWarn: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
)

@Immutable
data class DataColors(
    val deep: Color,
    val light: Color,
    val rem: Color,
    val wake: Color,
    /** Warn as a graphic (bars, chart marks): the dashboard's own amber. */
    val warnMark: Color,
    val warn: WarnColors,
)

internal val LightData = DataColors(
    deep = Color(0xFF454FB0),
    light = Color(0xFF8693D6),
    rem = Color(0xFF4FA6B0),
    wake = Color(0xFFD2B06E),
    warnMark = Color(0xFFC9842B),
    warn = WarnLight,
)

internal val DarkData = DataColors(
    deep = Color(0xFF5D6989),
    light = Color(0xFF8C97B3),
    rem = Color(0xFF6FA1A8),
    wake = Color(0xFFC1B08F),
    warnMark = Color(0xFFE0A44E),
    warn = WarnDark,
)

val LocalDataColors = staticCompositionLocalOf { DarkData }

/** The fixed data colours, read alongside `MaterialTheme.colorScheme`. */
val MaterialTheme.dataColors: DataColors
    @Composable @ReadOnlyComposable get() = LocalDataColors.current
