package org.openoura.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.Battery
import org.openoura.android.data.BatteryPoint
import org.openoura.android.data.Device
import org.openoura.android.ui.components.ExpandablePanel
import org.openoura.android.ui.components.segmentShape
import org.openoura.android.ui.theme.dataColors
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
 * Kept separate from the composable, with plain colours rather than a theme, so the same
 * chart can render into a Bitmap — App Widgets are RemoteViews and cannot host a Canvas.
 */
fun DrawScope.drawBatteryChart(series: List<BatteryPoint>, percent: Color, volts: Color, grid: Color) {
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
        drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
        t += day
    }

    fun curve(value: (BatteryPoint) -> Float): Path = Path().apply {
        moveTo(x(series[0].t), value(series[0]))
        for (i in 1 until series.size) lineTo(x(series[i].t), value(series[i]))
    }
    drawPath(curve { yMv(it.mv) }, volts, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    drawPath(curve { yPct(it.pct) }, percent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
}

@Composable
fun BatteryPanel(
    battery: Battery,
    device: Device?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val warn = MaterialTheme.dataColors.warn
    val series = battery.series
    // The reconciled figure from the brain, not the tail of the series — the brain already
    // picked whichever source is freshest, and reading the tail here is exactly what made
    // this header disagree with the top bar.
    val last = series.lastOrNull()
    val pct = device?.batteryPct ?: last?.pct
    val volts = device?.batteryV ?: last?.let { it.mv / 1000.0 }
    val low = pct != null && pct < 20

    ExpandablePanel(
        icon = if (low) R.drawable.ic_sym_battery_alert else R.drawable.ic_sym_battery_full,
        title = "Battery",
        // The header says something useful while collapsed.
        summary = pct?.let { p -> "$p%" + (volts?.let { " · ${"%.2f".format(it)} V" } ?: "") } ?: "No reading yet",
        expanded = expanded,
        onToggle = onToggle,
        iconContainer = if (low) warn.container else cs.secondaryContainer,
        iconContent = if (low) warn.onContainer else cs.onSecondaryContainer,
        modifier = modifier,
    ) {
        if (series.size < 2) {
            Text(
                "No battery log yet — the ring emits these as it discharges.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        } else {
            val voltsColor = cs.tertiary
            Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(12.dp),
                ) { drawBatteryChart(series, cs.primary, voltsColor, cs.outlineVariant) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend("Charge %", cs.primary)
                Legend("Volts, 3.3–4.25 V", voltsColor)
            }
            if (battery.cycles.isNotEmpty()) CycleList(battery)
        }
        Text(
            "Percent comes from voltage, so the last quarter falls away quickly and a reading " +
                "taken mid-sync can dip well below the resting level — watch the volts line for that.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun CycleList(battery: Battery) {
    val cs = MaterialTheme.colorScheme
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d") }
    val recent = battery.cycles.takeLast(6).reversed()

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "Discharge runs",
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = cs.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
        )
        recent.forEachIndexed { i, cy ->
            Surface(shape = segmentShape(i, recent.size, outer = 16.dp, inner = 4.dp), color = cs.surfaceContainerHigh) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${cy.fromPct}% → ${cy.toPct}% over ${cy.hours.roundToInt()} h",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            fmt.format(Instant.ofEpochSecond(cy.start.toLong()).atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("≈${cy.projectedFullH.roundToInt()} h", style = MaterialTheme.typography.titleMediumEmphasized)
                        Text("per full charge", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
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
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun Legend(label: String, swatch: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(swatch))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
