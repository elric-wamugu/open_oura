package org.openoura.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The web dashboard's "quiet data" tokens (dashboard/web/styles.css), ported so the two
// clients read as one product. One teal accent, off-black/off-white, hairlines carrying
// the structure rather than shadows.
//
// Material 3's own roles don't cover everything the dashboard uses (muted vs faint text,
// hairline vs soft hairline, the four sleep-stage hues), so those live in `OuraColors`
// alongside the standard ColorScheme.

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

private val LightOura = OuraColors(
    bg = Color(0xFFF3F4F6),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFFBFBFC),
    text = Color(0xFF1B1D21),
    muted = Color(0xFF6A7078),
    faint = Color(0xFF969CA6),
    line = Color(0x14111418),
    lineSoft = Color(0x0D111418),
    accent = Color(0xFF0D9488),
    accentSoft = Color(0x1F0D9488),
    warn = Color(0xFFC9842B),
    deep = Color(0xFF454FB0),
    light = Color(0xFF8693D6),
    rem = Color(0xFF4FA6B0),
    wake = Color(0xFFD2B06E),
)

private val DarkOura = OuraColors(
    bg = Color(0xFF0B0C0F),
    surface = Color(0xFF16181D),
    surface2 = Color(0xFF1B1E24),
    text = Color(0xFFE8E9EC),
    muted = Color(0xFF9AA0AA),
    faint = Color(0xFF6B7280),
    line = Color(0x17FFFFFF),
    lineSoft = Color(0x0FFFFFFF),
    accent = Color(0xFF2DD4BF),
    accentSoft = Color(0x242DD4BF),
    warn = Color(0xFFE0A44E),
    deep = Color(0xFF5D6989),
    light = Color(0xFF8C97B3),
    rem = Color(0xFF6FA1A8),
    wake = Color(0xFFC1B08F),
)

val LocalOuraColors = staticCompositionLocalOf { DarkOura }

/** The dashboard's extended palette. `MaterialTheme.colorScheme` still works as normal. */
object Oura {
    val colors: OuraColors
        @Composable @ReadOnlyComposable get() = LocalOuraColors.current
}

// Numbers are monospaced with tabular figures throughout the dashboard so columns of
// readings line up and a changing value doesn't reflow its neighbours.
val MonoNumerals = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)

private val OuraTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 26.sp),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp,
    ),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun OpenOuraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val oura = if (darkTheme) DarkOura else LightOura
    // Deliberately NOT using dynamicColor: the teal accent and the stage hues are the
    // product's identity, and Material You would repaint them from the wallpaper.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = oura.accent, background = oura.bg, surface = oura.surface,
            onBackground = oura.text, onSurface = oura.text, outline = oura.line,
        )
    } else {
        lightColorScheme(
            primary = oura.accent, background = oura.bg, surface = oura.surface,
            onBackground = oura.text, onSurface = oura.text, outline = oura.line,
        )
    }
    CompositionLocalProvider(LocalOuraColors provides oura) {
        MaterialTheme(colorScheme = scheme, typography = OuraTypography, content = content)
    }
}
