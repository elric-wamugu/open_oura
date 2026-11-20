package org.openoura.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.ble.HeartReading
import org.openoura.android.ble.SyncPhase
import org.openoura.android.data.Summary
import org.openoura.android.data.SummaryState
import org.openoura.android.ui.components.ScreenScaffold
import org.openoura.android.ui.components.SectionHeader
import org.openoura.android.ui.components.SyncStatusCard

/** Overnight SpO2 at or above this reads as normal — same constant as app.js. */
private const val SPO2_HEALTHY = 95.0

/** Room under the last item so the Sync button never covers it. */
internal val FabClearance = 96.dp

/**
 * The Today tab: the latest day at a glance, then the four key vitals.
 *
 * Pull-to-refresh means the same as the Sync button: pull history off the ring. Keeping
 * both matters — the gesture is the reflex, the button is the one that is discoverable.
 */
@Composable
fun TodayScreen(
    state: SummaryState,
    busy: Boolean,
    syncPhase: SyncPhase,
    onSync: () -> Unit,
    onDismissSync: () -> Unit,
    onRetrySummary: () -> Unit,
    onImportDatabase: () -> Unit,
    onOpenDay: (String, Boolean) -> Unit,
    onOpenReadiness: (String) -> Unit,
    onBrowseDays: () -> Unit,
    onOpenRing: () -> Unit,
    cardioExpanded: Boolean,
    onToggleCardio: () -> Unit,
    /** The last heart-rate measurement, for the card that starts a new one. */
    lastHeartRate: HeartReading?,
    onMeasureHeartRate: () -> Unit,
    /** Open the heart-rate page without starting a measurement. */
    onOpenHeartRate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? SummaryState.Ready
    val syncing = syncPhase is SyncPhase.Running
    ScreenScaffold(
        title = "Today",
        titleContent = { AppTitle(ready?.summary?.device) },
        actions = { BatteryChip(ready?.summary?.device, onClick = onOpenRing) },
        modifier = modifier,
    ) { padding ->
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = onSync,
            state = pull,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pull,
                    isRefreshing = syncing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = FabClearance),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "sync") {
                    AnimatedVisibility(syncPhase is SyncPhase.Running || syncPhase is SyncPhase.Failed) {
                        SyncStatusCard(syncPhase, onRetry = onSync, onDismiss = onDismissSync)
                    }
                }
                when (state) {
                    is SummaryState.Loading -> item {
                        Notice(
                            "Computing the summary from the ring database. The result is cached, " +
                                "so later launches open straight from it.",
                            loading = true,
                        )
                    }

                    is SummaryState.Empty -> item {
                        Notice(state.reason) {
                            Button(onClick = onImportDatabase, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                                Icon(painterResource(R.drawable.ic_sym_file_open), contentDescription = null)
                                Text("Import oura.db", Modifier.padding(start = 8.dp))
                            }
                        }
                    }

                    is SummaryState.Failed -> item {
                        Notice("Could not build the summary.\n\n${state.message}") {
                            Button(onClick = onRetrySummary, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                                Text("Retry")
                            }
                        }
                    }

                    is SummaryState.Ready -> readyItems(state, onOpenDay, onOpenReadiness, onBrowseDays, cardioExpanded, onToggleCardio, lastHeartRate, onMeasureHeartRate, onOpenHeartRate)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.readyItems(
    state: SummaryState.Ready,
    onOpenDay: (String, Boolean) -> Unit,
    onOpenReadiness: (String) -> Unit,
    onBrowseDays: () -> Unit,
    cardioExpanded: Boolean,
    onToggleCardio: () -> Unit,
    lastHeartRate: HeartReading?,
    onMeasureHeartRate: () -> Unit,
    onOpenHeartRate: () -> Unit,
) {
    val s = state.summary
    // The summary's one-line `digest` ("HRV −11%, resting HR …") used to open the page. It
    // repeated the vitals tiles below in a harder-to-read form, so Today starts with the
    // scores instead (removed 2026-11-20 at the user's request).
    // The day's date first, then its three scores, then the day itself.
    s.days.firstOrNull()?.let { day ->
        item(key = "day") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(dayTitle(day))
                ScoresRow(
                    readiness = s.readiness[day]?.score,
                    sleep = s.nightForDay(day)?.sleepScore?.score,
                    activity = s.activityScores[day]?.score,
                    onReadiness = { onOpenReadiness(day) },
                    onSleep = { onOpenDay(day, true) },
                    onActivity = { onOpenDay(day, false) },
                )
                DayCard(
                    summary = s,
                    ymd = day,
                    onOpenSleep = { onOpenDay(day, true) },
                    onOpenActivity = { onOpenDay(day, false) },
                )
            }
        }
    }
    // Today often has activity but no night yet — the night you woke from belongs to
    // yesterday's date — so the History tab is how a scored night is reached from here.
    if (s.days.size > 1) {
        item(key = "all-days") {
            FilledTonalButton(
                onClick = onBrowseDays,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_sym_calendar_month), contentDescription = null, Modifier.size(18.dp))
                Text("All ${s.days.size} days", Modifier.padding(start = 8.dp))
            }
        }
    }
    item(key = "vitals") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Vitals")
            VitalsGrid(s, onOpenHeartRate = onOpenHeartRate, onOpenSleep = { day -> onOpenDay(day, true) })
        }
    }
    item(key = "heart-rate") {
        HeartRateCard(lastHeartRate, nowUnix = System.currentTimeMillis() / 1000, onMeasure = onMeasureHeartRate)
    }
    item(key = "cardio") { CardioPanel(s, cardioExpanded, onToggleCardio) }
    item(key = "footer") { Footer(s, state.stale) }
}

/**
 * The four key vitals, in the same order and with the same thresholds as the web
 * dashboard's `renderTiles`: HRV, resting HR, sleep efficiency, blood oxygen.
 */
@Composable
private fun VitalsGrid(s: Summary, onOpenHeartRate: () -> Unit, onOpenSleep: (day: String) -> Unit) {
    val hrv = s.vitals.hrv
    val rhr = s.vitals.rhr
    // The newest main sleep: the same nights `vitals` is built from.
    val night = s.nights.firstOrNull { it.main }
    // The day whose sleep report this night belongs on: the one it was woken from. Asked of
    // the night itself, not by finding the day that lists it — a nap is the newest night
    // without being its day's main sleep, and looking it up that way found no day at all.
    val nightDay = night?.let(s::wakeYmd)
    val spo2 = s.nights.firstOrNull { it.main && it.spo2Mean != null }?.spo2Mean
    val eff = night?.efficiency

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // IntrinsicSize.Min + fillMaxHeight keeps a pair level when one caption wraps to
        // two lines on a narrow screen; otherwise the row goes ragged.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            VitalTile(
                label = "HRV (RMSSD)", value = hrv.latest, unit = "ms", icon = R.drawable.ic_sym_monitor_heart,
                reference = hrv.baseline, status = statusFor(hrv.deltaPct, goodIsUp = true),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            VitalTile(
                label = "Resting HR", value = rhr.latest, unit = "bpm", icon = R.drawable.ic_sym_favorite,
                reference = rhr.baseline, status = statusFor(rhr.deltaPct, goodIsUp = false),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // Last night's resting rate leads to measuring it now. It opens the page
                // rather than starting: this tile reports a figure, and a tap on a figure
                // that set the ring measuring would be a surprise.
                onClick = onOpenHeartRate,
                onClickLabel = "Measure heart rate",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            VitalTile(
                label = "Sleep efficiency", value = eff, unit = "%", icon = R.drawable.ic_sym_bedtime,
                reference = if (eff != null) 85.0 else null, referenceLabel = "target",
                status = eff?.let {
                    when {
                        it >= 85 -> TileStatus("Normal", StatusKind.Ok, StatusLevel.Normal)
                        it >= 75 -> TileStatus("Fair", StatusKind.Neutral, StatusLevel.Fair)
                        else -> TileStatus("Low", StatusKind.Warn, StatusLevel.Low)
                    }
                },
                subtitle = if (eff == null) "needs a scored night" else null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // To the sleep report of the night the figure is from; nowhere without one.
                onClick = nightDay?.let { day -> { onOpenSleep(day) } },
                onClickLabel = "Open the sleep report",
            )
            VitalTile(
                label = "Blood oxygen", value = spo2, unit = "%", icon = R.drawable.ic_sym_spo2,
                reference = if (spo2 != null) SPO2_HEALTHY else null, referenceLabel = "healthy",
                status = spo2?.let {
                    when {
                        it >= SPO2_HEALTHY -> TileStatus("Normal", StatusKind.Ok, StatusLevel.Normal)
                        it >= 90 -> TileStatus("Low", StatusKind.Neutral, StatusLevel.Low)
                        else -> TileStatus("Low", StatusKind.Warn, StatusLevel.Low)
                    }
                },
                subtitle = if (spo2 == null) "needs spo2 on overnight" else null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun Footer(s: Summary, stale: Boolean) {
    val d = s.device
    val bits = buildList {
        d?.batteryPct?.let { add("battery $it%") }
        // one decimal, matching the web's History figure — truncating to 25 where the
        // dashboard says 25.8 is exactly the kind of quiet divergence to avoid
        d?.daysOfData?.let { add("%.1f days".format(it)) }
        d?.nights?.let { add("$it nights") }
        d?.shortPeriodsExcluded?.takeIf { it > 0 }?.let { add("$it short periods excluded") }
    }
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (stale) {
            Text("Showing the cached snapshot.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (bits.isNotEmpty()) {
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Everything here is computed on this device. Nothing left it.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A full-width message card, optionally with a spinner or an action under the text. */
@Composable
internal fun Notice(
    text: String,
    loading: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            if (loading) LoadingIndicator()
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}
