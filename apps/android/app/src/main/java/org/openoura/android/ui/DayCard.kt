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

// The combined day card from the web dashboard (`dayCard` in app.js) and the iOS
// TodayCard: a Sleep part above an Activity part, each opening its full report. Here the
// two parts are separate tappable surfaces joined into one segmented block.

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

private fun kfmt(n: Double): String =
    if (n >= 1000) "%.${if (n >= 10000) 0 else 1}fk".format(n / 1000) else n.roundToInt().toString()

private fun num(v: Double?): String = when {
    v == null -> "—"
    v == v.roundToInt().toDouble() -> v.roundToInt().toString()
    else -> (Math.round(v * 10.0) / 10.0).toString()
}

@Composable
fun DayCard(
    summary: Summary,
    ymd: String,
    onOpenSleep: () -> Unit,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val night = summary.nightForDay(ymd)
    val count = if (night != null) 2 else 1
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        if (night != null) {
            Segment(index = 0, count = count, onClick = onOpenSleep) { SleepPart(night) }
        }
        Segment(index = count - 1, count = count, onClick = onOpenActivity) {
            ActivityPart(summary, ymd)
        }
    }
}

@Composable
private fun Segment(index: Int, count: Int, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        onClick = onClick,
        shape = segmentShape(index, count, outer = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SleepPart(n: NightRow) {
    PartHead(
        icon = R.drawable.ic_sym_bedtime,
        title = "Sleep",
        meta = "${n.start ?: "—"}–${n.end ?: "—"} · ${num(n.inBedH)} h in bed",
        score = n.sleepScore?.score,
    )
    // The split, not the night as it unfolded: how much of each stage is what a glance at
    // Today can take in, and the hour-by-hour picture is one tap away on the sleep page.
    StageSplit(n)
}

@Composable
private fun ActivityPart(s: Summary, ymd: String) {
    val ds = s.activityDaily[ymd]
    val stat = if (ds != null) {
        "${kfmt(ds.steps ?: 0.0)} steps · ${(ds.activeKcal ?: 0.0).roundToInt()} kcal"
    } else {
        "no activity totals"
    }
    PartHead(icon = R.drawable.ic_sym_directions_walk, title = "Activity", meta = stat)
    s.activityProfile[ymd]?.takeIf { it.size > 1 }?.let { prof ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MovementRidge(prof, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.outlineVariant)
            RidgeAxis()
        }
    }
}

@Composable
private fun PartHead(@DrawableRes icon: Int, title: String, meta: String, score: Int? = null) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShapedIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The night's Sleep Score, where the day card has room for one number.
        score?.let {
            Column(horizontalAlignment = Alignment.End) {
                Text("$it", style = MaterialTheme.typography.titleLargeEmphasized)
                Text("score", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            painterResource(R.drawable.ic_sym_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
private fun RidgeAxis() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0, 6, 12, 18, 24).forEach { h ->
            val m = ((h % 24) + 24) % 24
            val label = "${if (m % 12 == 0) 12 else m % 12}${if (m < 12) "am" else "pm"}"
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
