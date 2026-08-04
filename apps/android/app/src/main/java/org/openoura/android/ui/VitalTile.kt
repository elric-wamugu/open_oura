package org.openoura.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.ui.theme.Oura
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The key-vitals tile from the web dashboard (`metricCard` in app.js), ported. The status
// thresholds and the comparison-bar geometry are kept identical to the web so the two
// clients can't quietly disagree about whether a reading is "Normal".

enum class StatusKind { Ok, Warn, Neutral }

data class TileStatus(val label: String, val kind: StatusKind)

/** `statusFor(deltaPct, good)` in app.js — direction-aware, ±5% counts as unchanged. */
fun statusFor(deltaPct: Double?, goodIsUp: Boolean): TileStatus? {
    if (deltaPct == null) return null
    if (abs(deltaPct) <= 5.0) return TileStatus("Normal", StatusKind.Ok)
    val improving = if (goodIsUp) deltaPct > 0 else deltaPct < 0
    return if (improving) TileStatus(if (goodIsUp) "High" else "Low", StatusKind.Ok)
    else TileStatus(if (goodIsUp) "Low" else "Elevated", StatusKind.Warn)
}

private fun trimZeros(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString()
    else (Math.round(v * 10.0) / 10.0).toString()

@Composable
fun VitalTile(
    label: String,
    value: Double?,
    unit: String,
    modifier: Modifier = Modifier,
    reference: Double? = null,
    referenceLabel: String = "baseline",
    status: TileStatus? = null,
    subtitle: String? = null,
) {
    val c = Oura.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(8.dp))
            .defaultMinSize(minHeight = 118.dp)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label.uppercase(),
                color = c.faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (status != null) StatusPill(status)
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value?.let { trimZeros(it) } ?: "—",
                color = c.text,
                fontSize = 30.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
            )
            if (unit.isNotEmpty() && value != null) {
                Text(
                    unit,
                    color = c.faint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
        }

        if (value != null && reference != null) {
            ComparisonBar(value, reference, unit, referenceLabel)
        } else if (subtitle != null) {
            Text(subtitle, color = c.faint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatusPill(status: TileStatus) {
    val c = Oura.colors
    val tone: Color = when (status.kind) {
        StatusKind.Ok -> c.accent
        StatusKind.Warn -> c.warn
        StatusKind.Neutral -> c.muted
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(status.label, color = tone, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * `cmpBar()` in app.js: the value as a fill and the reference as a marker, both on a
 * shared 0…(max×1.3) scale, with a caption giving the reference and the signed delta.
 */
@Composable
private fun ComparisonBar(value: Double, reference: Double, unit: String, refLabel: String) {
    val c = Oura.colors
    val hi = max(value, reference) * 1.3
    val fill = if (hi <= 0) 0f else (min(100.0, max(3.0, value / hi * 100.0)) / 100.0).toFloat()
    val mark = if (hi <= 0) 0f else (min(100.0, reference / hi * 100.0) / 100.0).toFloat()
    val delta = Math.round((value - reference) * 10.0) / 10.0
    val u = unit.trim()

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.lineSoft),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.accent),
            )
            // The marker is placed by fraction of the measured track, so it stays aligned
            // with the fill at any tile width.
            Layout(
                content = {
                    Box(Modifier.background(c.muted).height(4.dp).fillMaxWidth())
                },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            ) { measurables, constraints ->
                val markPx = (constraints.maxWidth * mark).roundToInt()
                val p = measurables.first().measure(constraints.copy(minWidth = 2, maxWidth = 2))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    p.place(x = (markPx - 1).coerceIn(0, constraints.maxWidth - 2), y = 0)
                }
            }
        }
        Text(
            buildString {
                append(refLabel).append(' ').append(trimZeros(reference)).append(u)
                append(" · ").append(if (delta >= 0) "+" else "").append(trimZeros(delta)).append(u)
            },
            color = c.faint,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
