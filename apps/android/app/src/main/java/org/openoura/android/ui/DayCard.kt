package org.openoura.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.data.NightRow
import org.openoura.android.data.Summary
import org.openoura.android.ui.theme.Oura
import org.openoura.android.ui.theme.OuraColors
import kotlin.math.min
import kotlin.math.roundToInt

// The combined day card from the web dashboard (`dayCard` in app.js) and the iOS
// TodayCard: a Sleep region above an Activity region, each opening its full report.

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
    val c = Oura.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            dayTitle(ymd),
            color = c.faint,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.6.sp,
        )

        summary.nightForDay(ymd)?.let { night -> SleepPart(night, c, onOpenSleep) }

        ActivityPart(summary, ymd, c, onOpenActivity)
    }
}

@Composable
private fun SleepPart(n: NightRow, c: OuraColors, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PartHead(
            tag = "Sleep",
            meta = "${n.start ?: "—"}–${n.end ?: "—"} · ${num(n.inBedH)}h",
            c = c,
        )
        n.stages?.takeIf { it.isNotEmpty() }?.let { Hypnogram(it, c) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StageLegend("Deep", n.deepPct, c.deep, c)
            StageLegend("Light", n.lightPct, c.light, c)
            StageLegend("REM", n.remPct, c.rem, c)
            StageLegend("Awake", n.wakePct, c.wake, c)
        }
    }
}

@Composable
private fun ActivityPart(s: Summary, ymd: String, c: OuraColors, onOpen: () -> Unit) {
    val ds = s.activityDaily[ymd]
    val stat = if (ds != null) {
        "${kfmt(ds.steps ?: 0.0)} steps · ${(ds.activeKcal ?: 0.0).roundToInt()} kcal"
    } else {
        "no activity totals"
    }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PartHead(tag = "Activity", meta = stat, c = c)
        s.activityProfile[ymd]?.takeIf { it.size > 1 }?.let { prof ->
            MovementRidge(prof, c)
            RidgeAxis(c)
        }
    }
}

@Composable
private fun PartHead(tag: String, meta: String, c: OuraColors) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tag.uppercase(),
                color = c.faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Text(meta, color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Text("›", color = c.faint, fontSize = 16.sp)
    }
}

@Composable
private fun StageLegend(label: String, pct: Double?, swatch: Color, c: OuraColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))) { drawRect(swatch) }
        Text(label, color = c.muted, fontSize = 11.sp)
        Text("${num(pct)}%", color = c.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** Stage strip: one cell per downsampled epoch. 1=deep 2=light 3=rem 4=wake. */
@Composable
fun Hypnogram(stages: List<Int>, c: OuraColors, height: androidx.compose.ui.unit.Dp = 34.dp) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        if (stages.isEmpty()) return@Canvas
        val w = size.width / stages.size
        stages.forEachIndexed { i, code ->
            val color = when (code) {
                1 -> c.deep
                2 -> c.light
                3 -> c.rem
                else -> c.wake
            }
            drawRect(
                color = color.copy(alpha = 0.9f),
                topLeft = Offset(i * w, 0f),
                size = androidx.compose.ui.geometry.Size(w + 0.6f, size.height),
            )
        }
    }
}

/** 96 × 15-min MET buckets as a filled area with faint 6-hour anchors. */
@Composable
fun MovementRidge(profile: List<Double>, c: OuraColors, height: androidx.compose.ui.unit.Dp = 30.dp) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val p = profile.map { it.coerceAtLeast(0.0) }
        if (p.size < 2) return@Canvas
        val peak = maxOf(0.5, p.max())
        val stepX = size.width / (p.size - 1)
        fun yOf(v: Double) = (size.height - min(1.0, v / peak) * size.height).toFloat()

        for (hr in 6 until 24 step 6) {
            val x = size.width * hr / 24f
            drawLine(c.lineSoft, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }

        val path = Path().apply {
            moveTo(0f, yOf(p[0]))
            for (i in 1 until p.size) lineTo(i * stepX, yOf(p[i]))
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, c.accent.copy(alpha = 0.18f))
        val line = Path().apply {
            moveTo(0f, yOf(p[0]))
            for (i in 1 until p.size) lineTo(i * stepX, yOf(p[i]))
        }
        drawPath(line, c.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f))
    }
}

/** 12-hour AM/PM ticks every 3 hours, matching the web ridge axis. */
@Composable
private fun RidgeAxis(c: OuraColors) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0, 3, 6, 9, 12, 15, 18, 21, 24).forEach { h ->
            val m = ((h % 24) + 24) % 24
            val label = "${if (m % 12 == 0) 12 else m % 12}${if (m < 12) "AM" else "PM"}"
            Text(label, color = c.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
