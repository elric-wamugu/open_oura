package org.openoura.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.openoura.android.data.Battery
import org.openoura.android.data.BatteryPoint
import org.openoura.android.data.Device
import kotlin.math.roundToInt

// The Ring tab as a bento (2026-11-20), in the style of Today: the battery as the tall tile,
// history and storage beside it, and the week's charge across the full width.

/** Words for the battery tiles. Pure, so tested. */
object RingText {
    /** The newest discharge runs' typical hours per full charge, or null without any. */
    fun hoursPerCharge(b: Battery): Double? =
        b.cycles.takeLast(3).map { it.projectedFullH }.takeIf { it.isNotEmpty() }?.average()

    /** "≈ 4 days left" or "≈ 9 h left" at [pct] for a charge that lasts [fullH]. */
    fun timeLeft(pct: Int, fullH: Double?): String? {
        fullH ?: return null
        val h = fullH * pct / 100.0
        return if (h >= 48) "≈ ${(h / 24).roundToInt()} days left" else "≈ ${h.roundToInt()} h left"
    }

    /** "≈ 5 days per charge" / "≈ 30 h per charge". */
    fun perCharge(fullH: Double?): String? = fullH?.let {
        if (it >= 48) "≈ ${(it / 24).roundToInt()} days per charge" else "≈ ${it.roundToInt()} h per charge"
    }

    /** The series' last [days] days, for the tile's chart. */
    fun lastDays(series: List<BatteryPoint>, days: Int): List<BatteryPoint> {
        val end = series.lastOrNull()?.t ?: return emptyList()
        return series.filter { it.t >= end - days * 86_400.0 }
    }
}

/** Battery tall on the left; history and storage stacked beside it. */
@Composable
fun RingBento(device: Device?, battery: Battery, databaseBytes: Long?) {
    val pct = device?.batteryPct
    val charging = device?.batteryCharging == true
    val toned = batteryTone(device?.batteryStatus).colors()
    val container = toned?.container ?: Topics.readiness.container
    val on = toned?.onContainer ?: Topics.readiness.onContainer
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp),
            color = container,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            Column(Modifier.padding(16.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text("Battery", style = MaterialTheme.typography.labelLarge, color = on)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(pct?.toString() ?: "—", style = MaterialTheme.typography.displayLargeEmphasized, color = on)
                    if (pct != null) {
                        Text("%", style = MaterialTheme.typography.titleLarge, color = on, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                Text(
                    listOfNotNull(
                        if (charging) "Charging" else pct?.let { RingText.timeLeft(it, RingText.hoursPerCharge(battery)) },
                        device?.batteryV?.let { "%.2f V".format(it) },
                    ).joinToString(" · ").ifEmpty { "No reading yet" },
                    style = MaterialTheme.typography.labelMedium,
                    color = on,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallStat(
                "History",
                device?.daysOfData?.roundToInt()?.toString() ?: "—",
                "days",
                RoundedCornerShape(percent = 50),
            )
            val size = databaseBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
            SmallStat(
                "On this phone",
                size?.substringBeforeLast(' ') ?: "—",
                size?.substringAfterLast(' ', "").orEmpty(),
                RoundedCornerShape(topStart = 12.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 32.dp),
            )
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, unit: String, shape: androidx.compose.ui.graphics.Shape) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = shape, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, style = MaterialTheme.typography.headlineMediumEmphasized)
                if (unit.isNotEmpty()) {
                    Text(unit, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
    }
}

/**
 * The week's charge across the full width, percent and volts. Tapping it opens the
 * discharge runs and the note on reading voltage-derived percent.
 */
@Composable
fun ChargeTile(battery: Battery, expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val week = RingText.lastDays(battery.series, 7)
    Surface(onClick = onToggle, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Charge, last 7 days", style = MaterialTheme.typography.titleMediumEmphasized)
                RingText.perCharge(RingText.hoursPerCharge(battery))?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            if (week.size < 2) {
                Text("No battery log yet. The ring records one as it discharges.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            } else {
                val volts = cs.tertiary
                Canvas(Modifier.fillMaxWidth().height(96.dp)) { drawBatteryChart(week, cs.primary, volts, cs.outlineVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Legend("Charge %", cs.primary)
                    Legend("Volts, 3.3–4.25 V", volts)
                }
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (battery.cycles.isNotEmpty()) CycleList(battery)
                    Text(
                        "Percent comes from voltage, so the last quarter falls away quickly and a reading " +
                            "taken mid-sync can dip well below the resting level — watch the volts line for that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The biggest event families, the rest behind a tap. */
@Composable
fun DataTile(device: Device, expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val more = (device.streams.size - 3).coerceAtLeast(0)
    Surface(onClick = onToggle, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Streams(device, limit = if (expanded) Int.MAX_VALUE else 3)
            val total = device.totalEvents?.let { "${compact(it)} events in all" }
            Text(
                listOfNotNull(if (!expanded && more > 0) "+ $more more" else null, total).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
