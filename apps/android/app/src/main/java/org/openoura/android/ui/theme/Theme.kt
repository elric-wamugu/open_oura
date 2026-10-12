package org.openoura.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme: Material 3 Expressive on the generated brand scheme (see gen-theme.py),
 * or on the wallpaper's Material You scheme when [dynamicColor] is on.
 *
 * Expressive motion is the spring-based scheme, so every component that animates — the
 * navigation bar's indicator, buttons morphing on press, the FAB — gets the same bouncy,
 * physical feel without per-call tuning. The data colours ([dataColors]) are provided
 * beside the scheme and never change with it.
 */
@Composable
fun OpenOuraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDark
        else -> BrandLight
    }
    val data = if (darkTheme) DarkData else LightData
    CompositionLocalProvider(LocalDataColors provides data) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = Shapes(),
            typography = OpenOuraTypography,
            content = content,
        )
    }
}
