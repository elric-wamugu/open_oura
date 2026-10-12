package org.openoura.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.Device
import org.openoura.android.ui.theme.CodeType
import org.openoura.android.ui.theme.dataColors
import kotlin.math.roundToInt

// The Today tab's app bar pieces. The web bar's DNA and Blood entries are deliberately
// omitted — both are documented as web-only (they don't go through oura-summary and have
// nothing to do with ring data), so mirroring them here would promise pages that will
// never exist.

/**
 * Logo lockup: the open-ring mark (same artwork as the launcher icon) and the wordmark.
 * The mark doubles as the liveness indicator — primary while the last reading is recent,
 * muted once it ages.
 */
@Composable
fun AppTitle(device: Device?) {
    val fresh = (device?.freshHours ?: Double.MAX_VALUE) < 12.0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OpenRingMark(
            tint = if (fresh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            size = 22.dp,
        )
        Text("open_oura", style = CodeType.wordmark)
    }
}

/**
 * The ring's battery with how stale the reading is. Freshness is shown, not hidden: a
 * battery figure from two days ago is a different claim from one taken at the last sync.
 * Tapping it opens the Ring tab, where the battery history lives.
 */
@Composable
fun BatteryChip(device: Device?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pct = device?.batteryPct
    val low = pct != null && pct < 20
    val warn = MaterialTheme.dataColors.warn
    val label = buildString {
        append(pct?.let { "$it%" } ?: "—")
        device?.freshHours?.let { h ->
            append(" · ")
            append(if (h < 1) "<1h" else if (h < 48) "${h.roundToInt()}h" else "${(h / 24).roundToInt()}d")
        }
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                painterResource(if (low) R.drawable.ic_sym_battery_alert else R.drawable.ic_sym_battery_full),
                contentDescription = "Ring battery",
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        colors = if (low) {
            AssistChipDefaults.assistChipColors(
                containerColor = warn.container,
                labelColor = warn.onContainer,
                leadingIconContentColor = warn.onContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
        border = if (low) null else AssistChipDefaults.assistChipBorder(enabled = true),
        modifier = modifier.padding(end = 8.dp),
    )
}

/**
 * The open-ring mark: a 300° arc with the gap at the top. Drawn rather than loaded so it
 * scales cleanly and can take a state colour; geometry matches
 * `res/drawable/ic_launcher_foreground.xml` so the app icon and the in-app logo are the
 * same shape.
 */
@Composable
fun OpenRingMark(tint: Color, size: Dp = 17.dp) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.145f
        val inset = stroke / 2f
        drawArc(
            color = tint,
            startAngle = -50f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
