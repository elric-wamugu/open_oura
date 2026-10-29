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
    /** Size of the local oura.db on disk, if known. */
    databaseBytes: Long? = null,
    modifier: Modifier = Modifier,
) {
    val d = device ?: return
    val context = LocalContext.current
    // Android's own short form ("729 MB"), so it reads the same as the Storage settings.
    val dbSize = databaseBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
    ExpandablePanel(
        icon = R.drawable.ic_sym_database,
        title = "Device & data",
        // Worth reading while collapsed: the two numbers that say whether there is
        // anything to look at inside.
        summary = listOfNotNull(
            d.nights?.let { "$it nights" },
            d.totalEvents?.let { "${compact(it)} events" },
            dbSize,
        ).joinToString(" · ").ifEmpty { null },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        StatGrid(d, dbSize)
        ShortPeriodsNote(d)
        Streams(d)
        Insights(d)
        Capabilities(d, onToggleCapability, busyCapability)
        Identity(d)
    }
}

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
private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fk".format(n / 1_000.0)
    else -> "$n"
}

private fun grouped(n: Long): String = "%,d".format(n)

@Composable
private fun Subhead(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

private data class Stat(val label: String, val value: String, val tint: Color? = null, val caption: String? = null)

@Composable
private fun StatGrid(d: Device, dbSize: String?) {
    val cs = MaterialTheme.colorScheme
    val fresh = d.freshHours?.let { if (it < 1) "<1" else it.roundToInt().toString() } ?: "—"
    // The brain's band, as everywhere else: amber at 20%, red at 10% and 5%.
    val batteryTint = batteryTone(d.batteryStatus).colors()?.color
    val items = listOf(
        Stat("Battery", (d.batteryPct?.let { "$it" } ?: "—") + "%", tint = batteryTint),
        Stat("Last sync", "$fresh h ago"),
        Stat("History", "%.1f days".format(d.daysOfData ?: 0.0)),
        Stat("Events", grouped(d.totalEvents ?: 0), caption = dbSize?.let { "$it on this phone" }),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value, tint, caption) ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = cs.surfaceContainerHigh,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            Text(
                                value,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                color = tint ?: cs.onSurface,
                            )
                            caption?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
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
private fun ShortPeriodsNote(d: Device) {
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

/** Event families by volume. The bar is relative to the largest, as on the web. */
@Composable
private fun Streams(d: Device) {
    val cs = MaterialTheme.colorScheme
    val streams = d.streams.takeIf { it.isNotEmpty() } ?: return
    val max = streams.maxOf { it.count }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Subhead("Data captured")
        streams.forEach { s ->
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
private fun Insights(d: Device) {
    val cs = MaterialTheme.colorScheme
    val insights = d.insights.takeIf { it.isNotEmpty() } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Subhead("Insights available")
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
private fun Capabilities(d: Device, onToggle: ((Capability) -> Unit)?, busy: String?) {
    val cs = MaterialTheme.colorScheme
    val caps = d.measuring.takeIf { it.isNotEmpty() } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        // The heading is what tells you the ring is about to be written to rather than the
        // app's own settings — the part worth knowing before you touch one.
        Subhead(if (onToggle != null) "On the ring · tap to change" else "On the ring")
        caps.forEachIndexed { index, m ->
            val working = busy == m.feature
            val changeable = onToggle != null && m.feature.isNotBlank() && busy == null
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index, caps.size),
                colors = ListItemDefaults.segmentedColors(containerColor = cs.surfaceContainerHigh),
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
private fun Identity(d: Device) {
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
        Subhead("Device")
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
