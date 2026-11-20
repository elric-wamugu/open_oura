package org.openoura.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.NightRow
import org.openoura.android.data.Summary
import org.openoura.android.ui.components.SegmentGap
import org.openoura.android.ui.components.ShapedIcon
import org.openoura.android.ui.components.segmentShape
import org.openoura.android.ui.theme.dataColors
import kotlin.math.min
import kotlin.math.roundToInt

// Pieces the day card used to be made of, still shared: the day's title, the stage split
// on the sleep page, and the 24-hour movement ridge. Today itself is the bento in
// TodayBento.kt since 2026-11-20.

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** "Mon · Aug 3" — matches the web's dayTitle(). */
fun dayTitle(ymd: String): String {
    val p = ymd.split("-").mapNotNull(String::toIntOrNull)
    if (p.size != 3) return ymd
    val (y, m, d) = p
    // Sakamoto's algorithm — no java.time, no timezone to get wrong; the date is local
    // already and we only need the weekday name.
    val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    val yy = if (m < 3) y - 1 else y
    val dow = (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7
    return "${WEEKDAYS[dow]} · ${MONTHS[m - 1]} $d"
}

/**
 * A night's stage split as one rounded bar, each stage's share drawn to scale, with the
 * percentages underneath — the proportions read at a glance, the figures on a second look.
 *
 * Shared by the Today card and the sleep page's "Sleep architecture", so the two show the
 * same thing the same way.
 */
@Composable
fun StageSplit(n: NightRow, modifier: Modifier = Modifier) {
    val d = MaterialTheme.dataColors
    val parts = listOf(
        Triple("Deep", n.deepPct, d.deep),
        Triple("Light", n.lightPct, d.light),
        Triple("REM", n.remPct, d.rem),
        Triple("Awake", n.wakePct, d.wake),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val total = parts.sumOf { it.second ?: 0.0 }
        if (total > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(CircleShape),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                parts.forEach { (_, pct, colour) ->
                    val w = (pct ?: 0.0).toFloat()
                    if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().background(colour))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            parts.forEach { (label, pct, colour) ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("${(pct ?: 0.0).roundToInt()}%", style = MaterialTheme.typography.titleLargeEmphasized)
                }
            }
        }
    }
}

/** 96 × 15-min MET buckets as a filled area with faint 6-hour anchors. */
@Composable
fun MovementRidge(profile: List<Double>, accent: Color, grid: Color, height: Dp = 40.dp) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val p = profile.map { it.coerceAtLeast(0.0) }
        if (p.size < 2) return@Canvas
        val peak = maxOf(0.5, p.max())
        val stepX = size.width / (p.size - 1)
        fun yOf(v: Double) = (size.height - min(1.0, v / peak) * size.height).toFloat()

        for (hr in 6 until 24 step 6) {
            val x = size.width * hr / 24f
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }

        val path = Path().apply {
            moveTo(0f, yOf(p[0]))
            for (i in 1 until p.size) lineTo(i * stepX, yOf(p[i]))
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, accent.copy(alpha = 0.22f))
        val line = Path().apply {
            moveTo(0f, yOf(p[0]))
            for (i in 1 until p.size) lineTo(i * stepX, yOf(p[i]))
        }
        drawPath(line, accent, style = Stroke(width = 2.dp.toPx()))
    }
}

/** 12-hour AM/PM ticks every 6 hours, matching the ridge's anchors. */
@Composable
internal fun RidgeAxis() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0, 6, 12, 18, 24).forEach { h ->
            val m = ((h % 24) + 24) % 24
            val label = "${if (m % 12 == 0) 12 else m % 12}${if (m < 12) "am" else "pm"}"
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
