package org.openoura.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Everything a chart draws with, resolved to plain colours so the drawing code can run in
 * any DrawScope — a Canvas on screen, or a Bitmap for a widget.
 *
 * Chrome (text, grid, the movement line) follows the Material scheme, so it tracks light,
 * dark and Material You. The signal lanes and sleep stages come from [DataColors] and
 * never change: heart rate is the same amber on every night, whatever the wallpaper.
 */
@Immutable
data class ChartColors(
    /** Readouts and the scrub cursor. */
    val text: Color,
    /** Labels. */
    val muted: Color,
    /** Risers between stages, de-emphasised marks. */
    val faint: Color,
    /** Grid and baseline rules. */
    val grid: Color,
    /** The movement line and anything else that is "the app's" colour. */
    val accent: Color,
    val hr: Color,
    val hrv: Color,
    val spo2: Color,
    val temp: Color,
    val motion: Color,
    val deep: Color,
    val light: Color,
    val rem: Color,
    val wake: Color,
)

@Composable
@ReadOnlyComposable
fun chartColors(): ChartColors {
    val cs = MaterialTheme.colorScheme
    val d = MaterialTheme.dataColors
    return ChartColors(
        text = cs.onSurface,
        muted = cs.onSurfaceVariant,
        faint = cs.outline,
        grid = cs.outlineVariant,
        accent = cs.primary,
        hr = d.warnMark,
        hrv = d.hrv,
        spo2 = d.rem,
        temp = d.light,
        motion = cs.outline,
        deep = d.deep,
        light = d.light,
        rem = d.rem,
        wake = d.wake,
    )
}
