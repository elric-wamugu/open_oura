package org.openoura.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.data.Capability
import org.openoura.android.data.Device
import org.openoura.android.ui.theme.Oura
import org.openoura.android.ui.theme.OuraColors
import kotlin.math.roundToInt

/**
 * Device & data health, mirroring the web dashboard's panel of the same name.
 *
 * This is the screen that answers "is the ring actually recording what I think it is". Not
 * a vital in sight: how much history exists, which event families are arriving and in what
 * volume, which derived metrics that does and does not unlock, and the ring's own identity.
 *
 * Mostly a read of the summary, with one exception: the capability rows write. Tapping one
 * connects to the ring and sets a feature mode, the same operation the web dashboard
 * offers. Still web-only are the key export/QR tools — the key has its own screen here —
 * and the per-event-type table.
 */
@Composable
fun DeviceHealthPanel(
    device: Device?,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** Flip one on-ring capability. Null leaves the rows read-only. */
    onToggleCapability: ((Capability) -> Unit)? = null,
    /** The capability currently being written to the ring, if any. */
    busyCapability: String? = null,
    /** The last toggle's outcome, shown under the rows until the next one. */
    capabilityMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    val d = device ?: return

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
                "DEVICE & DATA HEALTH",
                color = c.faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Worth reading while collapsed: the two numbers that say whether there is
                // anything to look at inside.
                Text(
                    listOfNotNull(
                        d.nights?.let { "$it nights" },
                        d.totalEvents?.let { "${compact(it)} events" },
                    ).joinToString(" · ").ifEmpty { "—" },
                    color = c.muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(if (expanded) "▾" else "▸", color = c.faint, fontSize = 11.sp)
            }
        }

        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatRow(d, c)
                ShortPeriodsNote(d, c)
                Streams(d, c)
                Insights(d, c)
                Capabilities(d, c, onToggleCapability, busyCapability, capabilityMessage)
                Identity(d, c)
            }
        }
    }
}

/** 2,900,505 → "2.9M". The header has room for a shape, not a figure. */
private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fk".format(n / 1_000.0)
    else -> "$n"
}

private fun grouped(n: Long): String = "%,d".format(n)

@Composable
private fun StatRow(d: Device, c: OuraColors) {
    val fresh = d.freshHours?.let { if (it < 1) "<1" else it.roundToInt().toString() } ?: "—"
    val items = listOf(
        "Battery" to ((d.batteryPct?.let { "$it" } ?: "—") + "%"),
        "Last sync" to "$fresh h ago",
        "History" to "%.1f days".format(d.daysOfData ?: 0.0),
        "Events" to grouped(d.totalEvents ?: 0),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (k, v) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.surface2)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(k, color = c.faint, fontSize = 10.sp)
                        Text(
                            v,
                            color = if (k == "Battery" && d.batteryStatus != "ok" && d.batteryStatus != null) c.warn else c.text,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The ring logs bedtime periods this app refuses to call sleep. Saying so keeps the night
 * count from quietly disagreeing with the ring's own tally.
 */
@Composable
private fun ShortPeriodsNote(d: Device, c: OuraColors) {
    val dropped = d.shortPeriodsExcluded?.takeIf { it > 0 } ?: return
    val nights = d.nights ?: 0
    Text(
        "$nights scoreable ${if (nights == 1) "night" else "nights"}. $dropped shorter bedtime " +
            "${if (dropped == 1) "period was" else "periods were"} logged by the ring but " +
            "excluded — under 90 minutes, they're stillness rather than sleep and can't be staged.",
        color = c.faint,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

/** Event families by volume. The bar is relative to the largest, as on the web. */
@Composable
private fun Streams(d: Device, c: OuraColors) {
    val streams = d.streams.takeIf { it.isNotEmpty() } ?: return
    val max = streams.maxOf { it.count }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Subhead("Data captured", c)
        streams.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name,
                    color = c.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(0.42f),
                )
                Box(
                    Modifier
                        .weight(0.38f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.lineSoft),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((s.count.toFloat() / max).coerceAtLeast(0.03f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(c.accent),
                    )
                }
                Text(
                    grouped(s.count),
                    color = c.faint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(0.20f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/** What can be computed from what is arriving — and, when it cannot, why not. */
@Composable
private fun Insights(d: Device, c: OuraColors) {
    val insights = d.insights.takeIf { it.isNotEmpty() } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Subhead("Insights available", c)
        insights.forEach { i ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(i.name, color = c.muted, fontSize = 11.sp)
                    if (i.gated && i.why.isNotBlank()) {
                        Text("· ${i.why}", color = c.faint, fontSize = 10.sp)
                    }
                }
                Text(
                    i.status,
                    color = if (i.gated) c.faint else c.accent,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Which on-ring features are switched on, and — when [onToggle] is supplied — a switch to
 * change one.
 *
 * Real switches rather than the coloured dots this started with. A dot that also happens to
 * be a button is a guess: it reads as a status light, and the only thing saying otherwise
 * was a line of caption text the eye skips. A switch says what it is before it is read.
 *
 * Flipping one is not a UI state change. It connects to the ring, authenticates and writes
 * a feature mode, which takes on the order of fifteen seconds and can fail — so the switch
 * gives way to a spinner while that happens, and the outcome is spelled out underneath
 * rather than left to be inferred from a control that may or may not have moved.
 *
 * Only one at a time: the ring holds a single connection, and two writes racing for it
 * would fail in a way neither row could explain.
 */
@Composable
private fun Capabilities(
    d: Device,
    c: OuraColors,
    onToggle: ((Capability) -> Unit)?,
    busy: String?,
    message: String?,
) {
    val caps = d.measuring.takeIf { it.isNotEmpty() } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Same words as the web panel. The switches say what they are on their own, but
        // the heading is what tells you the ring is about to be written to rather than the
        // app's own settings — which is the part worth knowing before you touch one.
        Subhead(if (onToggle != null) "Capabilities · tap to toggle" else "Capabilities", c)
        caps.forEach { m ->
            val working = busy == m.feature
            val changeable = onToggle != null && m.feature.isNotBlank()
            Row(
                Modifier.fillMaxWidth().height(40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    m.name,
                    color = if (m.on) c.text else c.muted,
                    fontSize = 12.sp,
                )
                if (working) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = c.accent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Switch(
                        checked = m.on,
                        // Null rather than a no-op lambda: it is what makes the switch
                        // read as unavailable instead of merely unresponsive.
                        onCheckedChange = if (changeable && busy == null) {
                            { onToggle!!(m) }
                        } else {
                            null
                        },
                        enabled = changeable && busy == null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = c.bg,
                            checkedTrackColor = c.accent,
                            checkedBorderColor = c.accent,
                            uncheckedThumbColor = c.faint,
                            uncheckedTrackColor = c.surface2,
                            uncheckedBorderColor = c.line,
                        ),
                        modifier = Modifier.scale(0.8f),
                    )
                }
            }
        }
        message?.let {
            Text(
                it,
                color = c.faint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The ring's identity and sync internals — the numbers worth quoting in a bug report. */
@Composable
private fun Identity(d: Device, c: OuraColors) {
    val rows = listOfNotNull(
        d.serial?.let { "Ring ID" to it },
        d.firmware?.let { "Firmware" to it },
        d.apiVersion?.let { "API" to it },
        d.mac?.let { "MAC" to it },
        d.hardwareId?.let { "Hardware" to it },
        d.batteryV?.let { "Battery" to "%.2f V".format(it) },
        d.syncedHm?.let { "Last sync" to "${d.synced.orEmpty()} $it".trim() },
        d.nextCursor?.let { "Sync cursor" to grouped(it) },
    ).takeIf { it.isNotEmpty() } ?: return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Subhead("Device", c)
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, color = c.faint, fontSize = 11.sp)
                Text(v, color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Subhead(text: String, c: OuraColors) {
    Text(
        text.uppercase(),
        color = c.faint,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
    )
}
