package org.openoura.android.ui

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.Capability
import org.openoura.android.data.Device
import org.openoura.android.ui.components.ExpandablePanel
import org.openoura.android.ui.components.ShapedIcon
import org.openoura.android.ui.theme.CodeType
import kotlin.math.roundToInt

// The Ring tab's parts about the device and its data (2026-11-20: laid out by RingScreen
// as a bento rather than one "Device & data" panel). Mostly a read of the summary, with one
// exception: the capability rows write to the ring.

/**
 * The same glyph the web panel shows beside each capability, keyed the same way.
 *
 * By name rather than by feature id, because that is how the web does it and the two lists
 * should not need reconciling to see they agree. An unknown capability falls back to the
 * chip icon rather than leaving a hole in the column.
 */
@DrawableRes
private fun capabilityIcon(name: String): Int = when (name) {
    "Daytime HR", "Cardio PPG (CVA)" -> R.drawable.ic_cap_heartbeat
    "SpO2" -> R.drawable.ic_cap_wind
    "Exercise HR" -> R.drawable.ic_cap_run
    "Real steps" -> R.drawable.ic_cap_walking
    else -> R.drawable.ic_cap_cpu
}

/** 2,900,505 → "2.9M". The header has room for a shape, not a figure. */
internal fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fk".format(n / 1_000.0)
    else -> "$n"
}

private fun grouped(n: Long): String = "%,d".format(n)

/**
 * The ring logs bedtime periods this app refuses to call sleep. Saying so keeps the night
 * count from quietly disagreeing with the ring's own tally.
 */
@Composable
internal fun ShortPeriodsNote(d: Device) {
    val dropped = d.shortPeriodsExcluded?.takeIf { it > 0 } ?: return
    val nights = d.nights ?: 0
    Text(
        "$nights scoreable ${if (nights == 1) "night" else "nights"}. $dropped shorter bedtime " +
            "${if (dropped == 1) "period was" else "periods were"} logged by the ring but " +
            "excluded — under 90 minutes, they're stillness rather than sleep and can't be staged.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Event families by volume, the first [limit] of them. The bar is relative to the largest,
 * as on the web.
 */
@Composable
internal fun Streams(d: Device, limit: Int = Int.MAX_VALUE) {
    val cs = MaterialTheme.colorScheme
    val streams = d.streams.takeIf { it.isNotEmpty() } ?: return
    val max = streams.maxOf { it.count }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        streams.take(limit).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
                Box(
                    Modifier
                        .weight(0.4f)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceContainerHighest),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((s.count.toFloat() / max).coerceAtLeast(0.03f))
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(cs.primary),
                    )
                }
                Text(
                    grouped(s.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.2f),
                )
            }
        }
    }
}

/** What can be computed from what is arriving — and, when it cannot, why not. */
@Composable
internal fun Insights(d: Device) {
    val cs = MaterialTheme.colorScheme
    val insights = d.insights.takeIf { it.isNotEmpty() } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        insights.forEach { i ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(i.name, style = MaterialTheme.typography.bodyMedium)
                    if (i.gated && i.why.isNotBlank()) {
                        Text(i.why, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                val (bg, fg) = if (i.gated) {
                    cs.surfaceContainerHighest to cs.onSurfaceVariant
                } else {
                    cs.secondaryContainer to cs.onSecondaryContainer
                }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(bg)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(i.status, style = MaterialTheme.typography.labelMedium, color = fg) }
            }
        }
    }
}

/**
 * Which on-ring features are switched on, and — when [onToggle] is supplied — a switch to
 * change one.
 *
 * Real switches rather than coloured dots: a dot that also happens to be a button reads as
 * a status light. A switch says what it is before it is read.
 *
 * Flipping one is not a UI state change. It connects to the ring, authenticates and writes
 * a feature mode, which takes on the order of fifteen seconds and can fail — so the switch
 * gives way to a loading indicator while that happens, and the outcome is spelled out in a
 * snackbar rather than left to be inferred from a control that may or may not have moved.
 *
 * Only one at a time: the ring holds a single connection, and two writes racing for it
 * would fail in a way neither row could explain.
 */
@Composable
internal fun Capabilities(d: Device, onToggle: ((Capability) -> Unit)?, busy: String?) {
    val cs = MaterialTheme.colorScheme
    val caps = d.measuring.takeIf { it.isNotEmpty() } ?: return
    // The section heading ("Recording on the ring") is the caller's: it is what tells you
    // the ring is about to be written to rather than the app's own settings.
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        caps.forEachIndexed { index, m ->
            val working = busy == m.feature
            val changeable = onToggle != null && m.feature.isNotBlank() && busy == null
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index, caps.size),
                colors = ListItemDefaults.segmentedColors(containerColor = cs.surfaceContainer),
                leadingContent = {
                    // Lit when the ring is recording it, quiet when it is not, so the column
                    // reads at a glance without following each row to its switch.
                    ShapedIcon(
                        capabilityIcon(m.name),
                        size = 36.dp,
                        container = if (m.on) cs.secondaryContainer else cs.surfaceContainerHighest,
                        content = if (m.on) cs.onSecondaryContainer else cs.outline,
                    )
                },
                supportingContent = { Text(if (working) "Writing to the ring…" else if (m.on) "Recording" else "Off") },
                trailingContent = {
                    if (working) {
                        LoadingIndicator(Modifier.size(36.dp))
                    } else {
                        Switch(
                            checked = m.on,
                            // Null rather than a no-op lambda: it is what makes the switch
                            // read as unavailable instead of merely unresponsive.
                            onCheckedChange = if (changeable) ({ onToggle!!(m) }) else null,
                            enabled = changeable,
                            thumbContent = if (m.on) {
                                {
                                    Icon(
                                        painterResource(R.drawable.ic_sym_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                },
            ) { Text(m.name) }
        }
    }
}

/** The ring's identity and sync internals — the numbers worth quoting in a bug report. */
@Composable
internal fun Identity(d: Device) {
    val cs = MaterialTheme.colorScheme
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Text(v, style = CodeType.small, color = cs.onSurface)
                    }
                }
            }
        }
    }
}
