package org.openoura.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.data.Device
import org.openoura.android.ui.theme.Oura
import kotlin.math.roundToInt

/**
 * The dashboard's top bar: the ring's battery with how stale the reading is, and a sync
 * control. The web bar's DNA and Blood entries are deliberately omitted — both are
 * documented as web-only (they don't go through oura-summary and have nothing to do with
 * ring data), so mirroring them here would promise pages that will never exist.
 */
@Composable
fun TopBar(
    device: Device?,
    busy: Boolean,
    onSync: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // the "live" dot — teal when the last reading is recent, muted once it ages
            val fresh = (device?.freshHours ?: Double.MAX_VALUE) < 12.0
            val dot = if (fresh) c.accent else c.muted
            Canvas(Modifier.size(9.dp)) { drawCircle(dot) }
            Text("open_oura", color = c.text, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatteryPill(device)
            SyncButton(busy = busy, onClick = onSync)
            ProfileButton(onClick = onProfile)
        }
    }
}

@Composable
private fun BatteryPill(device: Device?) {
    val c = Oura.colors
    val pct = device?.batteryPct
    val low = pct != null && pct < 20
    val label = buildString {
        append(pct?.let { "$it%" } ?: "—")
        // Freshness is visible, not hover-only: a battery figure from two days ago is a
        // different claim from one taken at the last sync.
        device?.freshHours?.let { h ->
            append(" · ")
            append(if (h < 1) "<1h" else if (h < 48) "${h.roundToInt()}h" else "${(h / 24).roundToInt()}d")
        }
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (low) c.warn.copy(alpha = 0.13f) else c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (low) c.warn else c.muted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SyncButton(busy: Boolean, onClick: () -> Unit) {
    val c = Oura.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(999.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(13.dp), color = c.accent)
        } else {
            Text("\u27F3", color = c.muted, fontSize = 14.sp)
        }
        Text("Sync", color = c.text, fontSize = 12.sp)
    }
}

@Composable
private fun ProfileButton(onClick: () -> Unit) {
    val c = Oura.colors
    Text(
        "\u25CB",
        color = c.muted,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}
