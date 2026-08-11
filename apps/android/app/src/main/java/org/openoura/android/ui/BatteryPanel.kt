package org.openoura.android.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import org.openoura.android.data.Battery
import org.openoura.android.data.BatteryPoint
import org.openoura.android.data.Device
import org.openoura.android.ui.theme.Oura
import org.openoura.android.ui.theme.OuraColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

// The ring's own battery log, mirroring the web panel. Percent and volts are drawn
// together on purpose: the gauge is voltage-derived, so below ~3.6 V the curve is
// near-vertical and the last quarter appears to vanish, and voltage sags under radio load
// then recovers at rest. Seeing both is the difference between "the battery died" and
// "the reading dipped during a sync".

private const val MV_LO = 3300f
private const val MV_HI = 4250f

/**
 * Draw the percent and voltage curves into any [DrawScope].
 *
 * Kept separate from the composable so the Glance widgets in Phase 3 can render the same
 * chart into a Bitmap — App Widgets are RemoteViews and cannot host a Canvas.
 */
fun DrawScope.drawBatteryChart(series: List<BatteryPoint>, c: OuraColors) {
    if (series.size < 2) return
    val t0 = series.first().t
    val span = (series.last().t - t0).coerceAtLeast(1.0)
    fun x(t: Double) = (((t - t0) / span) * size.width).toFloat()
    fun yPct(p: Int) = size.height - (p.coerceIn(0, 100) / 100f) * size.height
    fun yMv(mv: Int) =
        size.height - ((mv.toFloat().coerceIn(MV_LO, MV_HI) - MV_LO) / (MV_HI - MV_LO)) * size.height

    // a tick per day, so the discharge slope reads against real time
    val day = 86_400.0
    var t = Math.ceil(t0 / day) * day
    while (t < series.last().t) {
        val gx = x(t)
        drawLine(c.lineSoft, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
        t += day
    }

    fun curve(value: (BatteryPoint) -> Float): Path = Path().apply {
        moveTo(x(series[0].t), value(series[0]))
        for (i in 1 until series.size) lineTo(x(series[i].t), value(series[i]))
    }
    drawPath(curve { yMv(it.mv) }, c.muted.copy(alpha = 0.55f), style = Stroke(width = 1.2f))
    drawPath(curve { yPct(it.pct) }, c.accent, style = Stroke(width = 1.8f))
}

@Composable
fun BatteryPanel(
    battery: Battery,
    device: Device?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    val series = battery.series
    // The reconciled figure from the brain, not the tail of the series — the brain already
    // picked whichever source is freshest, and reading the tail here is exactly what made
    // this header disagree with the top bar.
    val last = series.lastOrNull()
    val pct = device?.batteryPct ?: last?.pct
    val volts = device?.batteryV ?: last?.let { it.mv / 1000.0 }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "BATTERY",
                color = c.faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The header says something useful while collapsed.
                Text(
                    pct?.let { p -> "$p%" + (volts?.let { " · ${"%.2f".format(it)} V" } ?: "") } ?: "—",
                    color = c.muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(if (expanded) "▾" else "▸", color = c.faint, fontSize = 11.sp)
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (series.size < 2) {
                    Text(
                        "No battery log yet — the ring emits these as it discharges.",
                        color = c.muted, fontSize = 12.sp,
                    )
                } else {
                    Canvas(Modifier.fillMaxWidth().height(90.dp)) { drawBatteryChart(series, c) }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Legend("charge", c.accent, c)
                        Legend("volts (3.3–4.25 V)", c.muted, c)
                    }
                    if (battery.cycles.isNotEmpty()) CycleList(battery, c)
                }
                Text(
                    "Percent comes from voltage, so the last quarter falls away quickly and a " +
                        "reading taken mid-sync can dip well below the resting level — watch the " +
                        "volts line for that.",
                    color = c.faint, fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun CycleList(battery: Battery, c: OuraColors) {
    val fmt = rememberFormatter("MMM d")
    val recent = battery.cycles.takeLast(6).reversed()

    Text(
        "DISCHARGE RUNS",
        color = c.faint, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
    Column {
        recent.forEach { cy ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    fmt.format(Instant.ofEpochSecond(cy.start.toLong()).atZone(ZoneId.systemDefault())),
                    color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1.1f),
                )
                Text("${cy.fromPct}→${cy.toPct}%", color = c.text, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.2f))
                Text("${cy.hours.roundToInt()}h", color = c.text, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.8f))
                Text("≈${cy.projectedFullH.roundToInt()}h full", color = c.faint, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.3f))
            }
        }
    }

    // Comparing newest against oldest is the point of the panel and easy to miss row by row.
    if (battery.cycles.size >= 4) {
        val mean = { l: List<Double> -> l.sum() / l.size }
        val early = mean(battery.cycles.take(2).map { it.projectedFullH })
        val now = mean(battery.cycles.takeLast(2).map { it.projectedFullH })
        val change = (((now - early) / early) * 100).roundToInt()
        if (abs(change) >= 15) {
            Text(
                "A full charge now lasts about ${now.roundToInt()} h, against ${early.roundToInt()} h " +
                    "across the earliest runs here — ${if (change < 0) "down" else "up"} ${abs(change)}%. " +
                    "Compare like with like before reading that as wear on the cell: drain tracks how " +
                    "much the ring actually measures, so a stretch spent off the finger will always " +
                    "look like excellent battery life.",
                color = c.faint, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun Legend(label: String, swatch: Color, c: OuraColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))) { drawRect(swatch) }
        Text(label, color = c.faint, fontSize = 10.sp)
    }
}

@Composable
private fun rememberFormatter(pattern: String): DateTimeFormatter =
    remember(pattern) { DateTimeFormatter.ofPattern(pattern) }
