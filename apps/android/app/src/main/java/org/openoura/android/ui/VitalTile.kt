package org.openoura.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openoura.android.ui.components.ShapedIcon
import org.openoura.android.ui.theme.dataColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The key-vitals tile from the web dashboard (`metricCard` in app.js), ported. The status
// thresholds and the comparison-bar geometry are kept identical to the web so the two
// clients can't quietly disagree about whether a reading is "Normal".

/** Whether the reading is moving the healthy way ([Ok]) or the unhealthy way ([Warn]). */
enum class StatusKind { Ok, Warn, Neutral }

/**
 * Where the reading sits against its reference, which is what the pill's colour codes —
 * lab-report style: high, low, or in range. Deliberately separate from [StatusKind]: a low
 * resting HR is good and a low SpO2 is not, so "is it low" and "is it good" are different
 * questions. The pill answers the first; the comparison bar turns amber for the second.
 */
enum class StatusLevel { High, Low, Normal, Fair }

data class TileStatus(val label: String, val kind: StatusKind, val level: StatusLevel)

/** `statusFor(deltaPct, good)` in app.js — direction-aware, ±5% counts as unchanged. */
fun statusFor(deltaPct: Double?, goodIsUp: Boolean): TileStatus? {
    if (deltaPct == null) return null
    if (abs(deltaPct) <= 5.0) return TileStatus("Normal", StatusKind.Ok, StatusLevel.Normal)
    val improving = if (goodIsUp) deltaPct > 0 else deltaPct < 0
    val up = deltaPct > 0
    val kind = if (improving) StatusKind.Ok else StatusKind.Warn
    val label = when {
        up && !goodIsUp -> "Elevated"
        up -> "High"
        else -> "Low"
    }
    return TileStatus(label, kind, if (up) StatusLevel.High else StatusLevel.Low)
}

private fun trimZeros(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString()
    else (Math.round(v * 10.0) / 10.0).toString()

@Composable
fun VitalTile(
    label: String,
    value: Double?,
    unit: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    reference: Double? = null,
    referenceLabel: String = "baseline",
    status: TileStatus? = null,
    subtitle: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .defaultMinSize(minHeight = 132.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                ShapedIcon(icon, size = 36.dp)
                if (status != null) StatusPill(status)
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        value?.let { trimZeros(it) } ?: "—",
                        style = MaterialTheme.typography.headlineLargeEmphasized,
                        color = cs.onSurface,
                    )
                    if (unit.isNotEmpty() && value != null) {
                        Text(
                            unit,
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f, fill = false))
            if (value != null && reference != null) {
                ComparisonBar(value, reference, unit, referenceLabel, warn = status?.kind == StatusKind.Warn)
            } else if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusPill(status: TileStatus) {
    val cs = MaterialTheme.colorScheme
    val d = MaterialTheme.dataColors
    val family = when (status.level) {
        StatusLevel.High -> d.high
        StatusLevel.Low -> d.low
        StatusLevel.Normal -> d.normal
        StatusLevel.Fair -> null
    }
    val (bg: Color, fg: Color) = family?.let { it.container to it.onContainer }
        ?: (cs.surfaceContainerHighest to cs.onSurfaceVariant)
    Box(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(status.label, style = MaterialTheme.typography.labelMediumEmphasized, color = fg)
    }
}

/**
 * `cmpBar()` in app.js: the value as a fill and the reference as a marker, both on a
 * shared 0…(max×1.3) scale, with a caption giving the reference and the signed delta.
 */
@Composable
private fun ComparisonBar(value: Double, reference: Double, unit: String, refLabel: String, warn: Boolean) {
    val cs = MaterialTheme.colorScheme
    val fillColor = if (warn) MaterialTheme.dataColors.warn.color else cs.primary
    val hi = max(value, reference) * 1.3
    val fill = if (hi <= 0) 0f else (min(100.0, max(3.0, value / hi * 100.0)) / 100.0).toFloat()
    val mark = if (hi <= 0) 0f else (min(100.0, reference / hi * 100.0) / 100.0).toFloat()
    val delta = Math.round((value - reference) * 10.0) / 10.0
    val u = unit.trim()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(cs.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(fillColor),
            )
            // The marker is placed by fraction of the measured track, so it stays aligned
            // with the fill at any tile width.
            Layout(
                content = {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(cs.onSurface),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            ) { measurables, constraints ->
                val markPx = (constraints.maxWidth * mark).roundToInt()
                val p = measurables.first().measure(constraints.copy(minWidth = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    p.place(x = (markPx - p.width / 2).coerceIn(0, constraints.maxWidth - p.width), y = 0)
                }
            }
        }
        Text(
            buildString {
                append(refLabel).append(' ').append(trimZeros(reference)).append(u)
                append(" · ").append(if (delta >= 0) "+" else "").append(trimZeros(delta)).append(u)
            },
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
    }
}
