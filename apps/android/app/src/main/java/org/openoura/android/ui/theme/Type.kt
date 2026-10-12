package org.openoura.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material 3's type scale, including the Expressive emphasized styles, with one change:
// tabular figures everywhere. Numbers are most of what this app shows, and with
// proportional digits a changing reading reflows its neighbours and columns of values
// don't line up. The dashboard got that from a monospace face; tabular figures give the
// same alignment without a second typeface in every sentence.

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

private val Base = Typography()

val OpenOuraTypography = Typography(
    displayLarge = Base.displayLarge.tabular(),
    displayMedium = Base.displayMedium.tabular(),
    displaySmall = Base.displaySmall.tabular(),
    headlineLarge = Base.headlineLarge.tabular(),
    headlineMedium = Base.headlineMedium.tabular(),
    headlineSmall = Base.headlineSmall.tabular(),
    titleLarge = Base.titleLarge.tabular(),
    titleMedium = Base.titleMedium.tabular(),
    titleSmall = Base.titleSmall.tabular(),
    bodyLarge = Base.bodyLarge.tabular(),
    bodyMedium = Base.bodyMedium.tabular(),
    bodySmall = Base.bodySmall.tabular(),
    labelLarge = Base.labelLarge.tabular(),
    labelMedium = Base.labelMedium.tabular(),
    labelSmall = Base.labelSmall.tabular(),
    displayLargeEmphasized = Base.displayLargeEmphasized.tabular(),
    displayMediumEmphasized = Base.displayMediumEmphasized.tabular(),
    displaySmallEmphasized = Base.displaySmallEmphasized.tabular(),
    headlineLargeEmphasized = Base.headlineLargeEmphasized.tabular(),
    headlineMediumEmphasized = Base.headlineMediumEmphasized.tabular(),
    headlineSmallEmphasized = Base.headlineSmallEmphasized.tabular(),
    titleLargeEmphasized = Base.titleLargeEmphasized.tabular(),
    titleMediumEmphasized = Base.titleMediumEmphasized.tabular(),
    titleSmallEmphasized = Base.titleSmallEmphasized.tabular(),
    bodyLargeEmphasized = Base.bodyLargeEmphasized.tabular(),
    bodyMediumEmphasized = Base.bodyMediumEmphasized.tabular(),
    bodySmallEmphasized = Base.bodySmallEmphasized.tabular(),
    labelLargeEmphasized = Base.labelLargeEmphasized.tabular(),
    labelMediumEmphasized = Base.labelMediumEmphasized.tabular(),
    labelSmallEmphasized = Base.labelSmallEmphasized.tabular(),
)

/**
 * Monospace, kept only where the characters themselves are the content: key hex and
 * fingerprints, probe output, device identifiers, and the `open_oura` wordmark.
 */
object CodeType {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
    val wordmark = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        letterSpacing = (-0.3).sp,
    )
}
