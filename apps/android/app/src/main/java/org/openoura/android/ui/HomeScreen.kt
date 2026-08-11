package org.openoura.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.data.Summary
import org.openoura.android.data.SummaryState
import org.openoura.android.ui.theme.Oura

/** Overnight SpO2 at or above this reads as normal — same constant as app.js. */
private const val SPO2_HEALTHY = 95.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: SummaryState,
    busy: Boolean,
    onRefresh: () -> Unit,
    /** Pull new history off the ring over BLE. The top bar's primary action. */
    onSyncFromRing: () -> Unit,
    /** One line of live sync state, or null when nothing is happening. */
    syncStatus: String? = null,
    /** 0..1 through the drain; null means show an indeterminate bar. */
    syncProgress: Float? = null,
    /** Whether a sync is in flight, which drives the pull-to-refresh spinner. */
    syncing: Boolean = false,
    onImportDatabase: () -> Unit,
    onOpenDay: (String, Boolean) -> Unit,
    onBrowseDays: () -> Unit,
    onProfile: () -> Unit,
    batteryExpanded: Boolean,
    onToggleBattery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    val ready = state as? SummaryState.Ready
    // Pull-to-refresh means the same thing as the top bar's Sync: pull history off the
    // ring. Keeping both matters — the gesture is the reflex, the button is the one that
    // is discoverable and still reachable when the list is already at the top.
    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = onSyncFromRing,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Order: top bar → day card → key vitals.
            TopBar(
                device = ready?.summary?.device,
                busy = busy,
                onSync = onSyncFromRing,
                onProfile = onProfile,
            )

            if (syncStatus != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        syncStatus,
                        color = c.muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (syncing) {
                        // Determinate once the ring has told us how much it is holding;
                        // indeterminate through connect, auth and setup, where there is
                        // genuinely nothing to measure against.
                        if (syncProgress != null) {
                            LinearProgressIndicator(
                                progress = { syncProgress },
                                color = c.accent,
                                trackColor = c.surface2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(
                                color = c.accent,
                                trackColor = c.surface2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            when (state) {
                is SummaryState.Loading -> Notice(
                    "Computing the summary from the ring database. Over ~850k events this takes " +
                        "roughly 8 seconds on this device; the result is cached, so later launches " +
                        "open straight from it.",
                )

                is SummaryState.Empty -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Notice(state.reason)
                    Button(onClick = onImportDatabase, enabled = !busy) {
                        Text("Import oura.db…")
                    }
                }

                is SummaryState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Notice("Could not build the summary.\n\n${state.message}")
                    Button(onClick = onRefresh, enabled = !busy) { Text("Retry") }
                }

                is SummaryState.Ready -> {
                    state.summary.digest?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = c.muted, fontSize = 13.sp)
                    }
                    val days = state.summary.days
                    days.firstOrNull()?.let { day ->
                        DayCard(
                            summary = state.summary,
                            ymd = day,
                            onOpenSleep = { onOpenDay(day, true) },
                            onOpenActivity = { onOpenDay(day, false) },
                        )
                    }
                    // Today often has activity but no night yet — the night you woke from
                    // belongs to yesterday's date — so the browser is the way to reach a
                    // scored night from the home screen.
                    if (days.size > 1) {
                        Text(
                            "Show all ${days.size} days",
                            color = c.accent,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, c.line, RoundedCornerShape(8.dp))
                                .clickable(onClick = onBrowseDays)
                                .padding(vertical = 10.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    VitalsGrid(state.summary)
                    BatteryPanel(
                        battery = state.summary.battery,
                        device = state.summary.device,
                        expanded = batteryExpanded,
                        onToggle = onToggleBattery,
                    )
                    if (state.stale) {
                        Text("Showing the cached snapshot.", color = c.faint, fontSize = 11.sp)
                    }
                    Footer(state.summary)
                }
            }
        }
    }
}

/**
 * The four key vitals, in the same order and with the same thresholds as the web
 * dashboard's `renderTiles`: HRV, resting HR, sleep efficiency, blood oxygen.
 */
@Composable
private fun VitalsGrid(s: Summary) {
    val hrv = s.vitals.hrv
    val rhr = s.vitals.rhr
    val night = s.nights.firstOrNull()
    val spo2 = s.nights.firstOrNull { it.spo2Mean != null }?.spo2Mean
    val eff = night?.efficiency

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // IntrinsicSize.Min + fillMaxHeight keeps a pair level when one caption wraps to
        // two lines on a narrow screen; otherwise the row goes ragged.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            VitalTile(
                label = "HRV (RMSSD)", value = hrv.latest, unit = "ms",
                reference = hrv.baseline, status = statusFor(hrv.deltaPct, goodIsUp = true),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            VitalTile(
                label = "Resting HR", value = rhr.latest, unit = "bpm",
                reference = rhr.baseline, status = statusFor(rhr.deltaPct, goodIsUp = false),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        // IntrinsicSize.Min + fillMaxHeight keeps a pair level when one caption wraps to
        // two lines on a narrow screen; otherwise the row goes ragged.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            VitalTile(
                label = "Sleep efficiency", value = eff, unit = "%",
                reference = if (eff != null) 85.0 else null, referenceLabel = "target",
                status = eff?.let {
                    when {
                        it >= 85 -> TileStatus("Normal", StatusKind.Ok)
                        it >= 75 -> TileStatus("Fair", StatusKind.Neutral)
                        else -> TileStatus("Low", StatusKind.Warn)
                    }
                },
                subtitle = if (eff == null) "needs a scored night" else null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            VitalTile(
                label = "Blood oxygen", value = spo2, unit = "%",
                reference = if (spo2 != null) SPO2_HEALTHY else null, referenceLabel = "healthy",
                status = spo2?.let {
                    when {
                        it >= SPO2_HEALTHY -> TileStatus("Normal", StatusKind.Ok)
                        it >= 90 -> TileStatus("Low", StatusKind.Neutral)
                        else -> TileStatus("Low", StatusKind.Warn)
                    }
                },
                subtitle = if (spo2 == null) "needs spo2 on overnight" else null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun Footer(s: Summary) {
    val c = Oura.colors
    val d = s.device ?: return
    val bits = buildList {
        d.batteryPct?.let { add("battery $it%") }
        // one decimal, matching the web's History figure — truncating to 25 where the
        // dashboard says 25.8 is exactly the kind of quiet divergence to avoid
        d.daysOfData?.let { add("%.1f days".format(it)) }
        d.nights?.let { add("$it nights") }
        d.shortPeriodsExcluded?.takeIf { it > 0 }?.let { add("$it short periods excluded") }
    }
    if (bits.isNotEmpty()) {
        Text(bits.joinToString(" · "), color = c.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    Text(
        "Everything here is computed on this device. Nothing left it.",
        color = c.faint,
        fontSize = 11.sp,
    )
}

@Composable
private fun Notice(text: String) {
    val c = Oura.colors
    Box(Modifier.fillMaxWidth()) {
        Text(text, color = c.muted, fontSize = 13.sp)
    }
}
