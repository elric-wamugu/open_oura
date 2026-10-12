package org.openoura.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.ui.theme.BrandDark
import org.openoura.android.ui.theme.BrandLight
import org.openoura.android.ui.theme.HighDark
import org.openoura.android.ui.theme.HighLight
import org.openoura.android.ui.theme.LowDark
import org.openoura.android.ui.theme.LowLight
import org.openoura.android.ui.theme.NormalDark
import org.openoura.android.ui.theme.NormalLight
import org.openoura.android.ui.theme.ToneFamily
import org.openoura.android.ui.theme.WarnDark
import org.openoura.android.ui.theme.WarnLight

/**
 * Guards the generated scheme (gen-theme.py) against a re-run with a seed or variant that
 * quietly makes text unreadable. WCAG 2: 4.5:1 for body text, 3:1 for large text and for
 * graphics such as the primary colour's bars and the outline.
 */
class BrandSchemeContrastTest {

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(name: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertTrue("$name is %.2f:1, needs %.1f:1".format(r, min), r >= min)
    }

    @Test
    fun `text roles are readable on both schemes`() {
        for ((mode, s) in listOf("light" to BrandLight, "dark" to BrandDark)) {
            check("$mode onSurface/surface", s.onSurface, s.surface, 4.5)
            check("$mode onSurfaceVariant/surface", s.onSurfaceVariant, s.surface, 4.5)
            for ((n, c) in listOf(
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
            )) {
                check("$mode onSurface/$n", s.onSurface, c, 4.5)
                check("$mode onSurfaceVariant/$n", s.onSurfaceVariant, c, 4.5)
            }
            check("$mode onPrimary/primary", s.onPrimary, s.primary, 4.5)
            check("$mode onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5)
            check("$mode onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
            check("$mode onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
            check("$mode onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer, 4.5)
        }
    }

    @Test
    fun `graphics roles stand out from the page`() {
        for ((mode, s) in listOf("light" to BrandLight, "dark" to BrandDark)) {
            check("$mode primary/surface", s.primary, s.surface, 3.0)
            check("$mode primary/surfaceContainer", s.primary, s.surfaceContainer, 3.0)
            check("$mode outline/surface", s.outline, s.surface, 3.0)
        }
    }

    @Test
    fun `data colour families are readable`() {
        val families = listOf<Pair<String, ToneFamily>>(
            "light warn" to WarnLight, "dark warn" to WarnDark,
            "light high" to HighLight, "dark high" to HighDark,
            "light low" to LowLight, "dark low" to LowDark,
            "light normal" to NormalLight, "dark normal" to NormalDark,
        )
        for ((name, f) in families) {
            check("$name onContainer/container", f.onContainer, f.container, 4.5)
            check("$name onColor/color", f.onColor, f.color, 4.5)
        }
    }
}
