package org.openoura.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.NightRow
import org.openoura.android.data.Summary
import org.openoura.android.ui.components.ChoiceOption
import org.openoura.android.ui.components.ConnectedChoice
import org.openoura.android.ui.components.SectionHeader
import org.openoura.android.ui.components.ShapedIcon
import org.openoura.android.ui.theme.dataColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The full-page day report — Sleep and Activity halves, the counterpart to the web's
 * `openDayPage` and the iOS `DayReportView`.
 *
 * Every clinical number here (`metrics`, `autonomic`, the stage percentages) comes
 * straight from `oura-summary`. Nothing is recomputed in Kotlin.
 *
 * The two halves switch with a connected button group rather than a swipeable pager: the
 * polysomnograph and movement chart are scrubbed sideways, and a pager would fight them for
 * every horizontal drag.
 */
@Composable
fun DayReportScreen(
    summary: Summary,
    ymd: String,
    sleepTab: Boolean,
    onSelectTab: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectedChoice(
            options = listOf(
                ChoiceOption("Sleep", R.drawable.ic_sym_bedtime),
                ChoiceOption("Activity", R.drawable.ic_sym_directions_walk),
            ),
            selected = if (sleepTab) 0 else 1,
            onSelect = { onSelectTab(it == 0) },
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedContent(
            targetState = sleepTab,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false))
            },
            label = "report-half",
        ) { sleep ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sleep) SleepReport(summary, ymd) else ActivityReport(summary, ymd)
            }
        }
    }
}

// ── sleep ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.SleepReport(s: Summary, ymd: String) {
    val n = s.nightForDay(ymd)
    if (n == null) {
        Notice("No scored night for this day.")
        return
    }
    // The web's four, in the web's order. HRV and resting HR used to sit here; they are
    // already the first two vitals tiles on Today, while asleep-vs-in-bed is the
    // comparison this page is about.
    HeroStats(
        listOf(
            "%.1f h".format(n.inBedH ?: 0.0) to "Time in bed",
            (n.metrics?.asleepMin?.let { "%.1f h".format(it / 60.0) } ?: "—") to "Asleep",
            (n.efficiency?.let { "${it.roundToInt()}%" } ?: "—") to "Efficiency",
            "${n.start ?: "—"}–${n.end ?: "—"}" to "Bedtime",
        ),
    )

    SectionHeader("Overnight polysomnograph", Modifier.padding(top = 4.dp))
    StageLegend(Modifier.padding(start = 4.dp))
    Polysomnograph(n)

    SectionHeader("Sleep architecture", Modifier.padding(top = 4.dp))
    StageBar(n)

    n.metrics?.let { m ->
        SectionHeader("Sleep metrics", Modifier.padding(top = 4.dp))
        MetricGrid(
            listOfNotNull(
                // Asleep leads the stats above, so repeating it here would spend a tile on
                // a figure already two inches up the page.
                m.onsetMin?.let { "%.0f min".format(it) to "Sleep onset" },
                m.wasoMin?.let { "%.0f min".format(it) to "Awake after onset" },
                m.remLatencyMin?.let { "%.0f min".format(it) to "REM latency" },
                m.awakenings?.let { "$it" to "Awakenings" },
                m.cycles?.let { "$it" to "Cycles" },
                m.fragIndex?.let { "%.1f".format(it) to "Fragmentation" },
            ),
        )
    }

    n.autonomic?.let { a ->
        if (listOfNotNull(a.hrDeep, a.hrLight, a.hrRem, a.hrvDeep, a.hrvLight, a.hrvRem).isNotEmpty()) {
            SectionHeader("Autonomic recovery by stage", Modifier.padding(top = 4.dp))
            MetricGrid(
                listOfNotNull(
                    a.hrDeep?.let { "%.0f bpm".format(it) to "HR · deep" },
                    a.hrLight?.let { "%.0f bpm".format(it) to "HR · light" },
                    a.hrRem?.let { "%.0f bpm".format(it) to "HR · REM" },
                    a.hrvDeep?.let { "%.0f ms".format(it) to "HRV · deep" },
                    a.hrvLight?.let { "%.0f ms".format(it) to "HRV · light" },
                    a.hrvRem?.let { "%.0f ms".format(it) to "HRV · REM" },
                ),
            )
            Footnote(
                "Per-stage means rather than an overnight HRV trend: nocturnal HRV is " +
                    "stage-driven (deep up, REM down), so a slope would track stage order, " +
                    "not recovery.",
            )
        }
    }

    val interpretation = SleepInterpretation.lines(n)
    val debt = SleepInterpretation.debtNote(s.sleepDebt)
    if (interpretation.isNotEmpty() || debt != null) {
        SectionHeader("Interpretation", Modifier.padding(top = 4.dp))
        interpretation.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
        debt?.let { (value, caption) ->
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sleep debt", style = MaterialTheme.typography.labelLarge)
                    Text(value, style = MaterialTheme.typography.headlineMediumEmphasized)
                    Text(caption, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * The night's stage split as one rounded bar, each stage's share drawn to scale, with the
 * percentages underneath — the proportions read at a glance, the figures on a second look.
 */
@Composable
private fun StageBar(n: NightRow) {
    val d = MaterialTheme.dataColors
    val parts = listOf(
        Triple("Deep", n.deepPct, d.deep),
        Triple("Light", n.lightPct, d.light),
        Triple("REM", n.remPct, d.rem),
        Triple("Awake", n.wakePct, d.wake),
    )
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val total = parts.sumOf { it.second ?: 0.0 }
            if (total > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .clip(CircleShape),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    parts.forEach { (_, pct, colour) ->
                        val w = (pct ?: 0.0).toFloat()
                        if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().background(colour))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                parts.forEach { (label, pct, colour) ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${(pct ?: 0.0).roundToInt()}%", style = MaterialTheme.typography.titleLargeEmphasized)
                    }
                }
            }
        }
    }
}

// ── activity ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.ActivityReport(s: Summary, ymd: String) {
    val ds = s.activityDaily[ymd]
    val prof = s.activityProfile[ymd] ?: emptyList()
    val steps = s.activitySteps[ymd] ?: emptyList()

    HeroStats(
        listOfNotNull(
            "${(ds?.steps ?: 0.0).roundToInt()}" to "Steps",
            "${(ds?.activeKcal ?: 0.0).roundToInt()} kcal" to "Active energy",
            "${(ds?.totalKcal ?: 0.0).roundToInt()} kcal" to "Total energy",
            ds?.distanceM?.let { "%.1f km".format(it / 1000) to "Distance" },
        ),
    )

    SectionHeader("Movement across the day", Modifier.padding(top = 4.dp))
    if (prof.size > 1) {
        MovementChart(prof = prof, steps = steps, dayTotalSteps = ds?.steps)
    } else {
        Notice("No movement profile for this day.")
    }

    val bucketMin = if (prof.isEmpty()) 15.0 else 24.0 * 60.0 / prof.size
    val activeMin = prof.count { it >= 3 } * bucketMin
    val lightMin = prof.count { it >= 1.5 && it < 3 } * bucketMin
    val peakMet = prof.maxOrNull() ?: 0.0
    val peakHr = ds?.peakHr

    SectionHeader("Intensity", Modifier.padding(top = 4.dp))
    MetricGrid(
        listOf(
            "${activeMin.roundToInt()} min" to "Active",
            "${lightMin.roundToInt()} min" to "Lightly active",
            "%.1f MET".format(peakMet) to "Peak intensity",
            (peakHr?.let { "${it.roundToInt()} bpm" } ?: "—") to "Peak HR",
            "${s.effortForDay(ymd).size}" to "Sessions",
        ),
    )
    if (peakHr != null) {
        val hrMax = s.fitness?.hrMaxPredicted
        val pct = hrMax?.let { (peakHr / it * 100).roundToInt() }
        Footnote(
            if (pct == null) {
                "Peak HR is the highest 30-second sustained rate of the day, from the " +
                    "ring's quality-checked beats."
            } else {
                "Peak HR is the highest 30-second sustained rate of the day — $pct% of the " +
                    "${hrMax.roundToInt()} bpm predicted for your age. ${peakHrVerdict(pct)}"
            },
        )
    }
    EffortSessions(s, ymd)
}

/**
 * Sessions the ring recorded an exercise-HR trace for.
 *
 * Shown as "effort", never as a named workout: labelling is what Oura's AAD model does and
 * it isn't bundled, so `activity[]` stays empty. Mirrors the web's effort rows.
 */
@Composable
private fun ColumnScope.EffortSessions(s: Summary, ymd: String) {
    val sessions = s.effortForDay(ymd)
    SectionHeader("Sessions", Modifier.padding(top = 4.dp))
    if (sessions.isEmpty()) {
        Notice("No sessions detected this day.")
        return
    }
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm") }
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        sessions.forEachIndexed { i, e ->
            val bits = buildList {
                add("${e.durationMin.roundToInt()} min")
                e.hrMean?.let { add("${it.roundToInt()} bpm avg") }
                e.hrPeak?.let { add("${it.roundToInt()} peak") }
                e.intensityPeak?.let { add("intensity ${it.roundToInt()}") }
            }
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(i, sessions.size),
                colors = groupColors(),
                leadingContent = { ShapedIcon(R.drawable.ic_sym_monitor_heart) },
                supportingContent = { Text(bits.joinToString(" · ")) },
            ) {
                Text("Effort at ${clock.format(Instant.ofEpochSecond(e.start.toLong()).atZone(ZoneId.systemDefault()))}")
            }
        }
    }
    Footnote(
        "Detected from the ring's own exercise-HR trace — it records while the ring thinks " +
            "you're working, so these are start/stop and effort, not labelled workouts. " +
            "Naming them needs Oura's activity model, which isn't bundled. Heart rate is " +
            "missing from some because motion corrupts the optical signal, which is exactly " +
            "when the ring is least able to read a clean beat.",
    )
}

/** Shared with the web `peakHrVerdict` in app.js — keep the 95/85 thresholds in step. */
fun peakHrVerdict(pct: Int): String = when {
    pct >= 95 -> "That's close enough to a true maximum to be worth trusting as one."
    pct >= 85 -> "That's a hard effort, but still short of a true maximum, which needs roughly 95%."
    else -> "That's the hardest sustained stretch of the day rather than anything near your ceiling."
}

// ── shared bits ──────────────────────────────────────────────────────────────────────

/**
 * The page's headline figures on one tonal card. A browser can put four on a line; a phone
 * cannot once one is a bedtime range like "03:32–10:58", so they sit two by two.
 */
@Composable
private fun HeroStats(items: List<Pair<String, String>>) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (value, label) ->
                        Column(Modifier.weight(1f)) {
                            Text(value, style = MaterialTheme.typography.headlineSmallEmphasized)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (value, label) ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(value, style = MaterialTheme.typography.titleLargeEmphasized)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
