package org.openoura.android.ui

import androidx.compose.ui.Alignment
import org.openoura.android.data.SleepScore
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import org.openoura.android.R
import org.openoura.android.data.EffortSession
import org.openoura.android.data.NightRow
import org.openoura.android.data.Summary
import org.openoura.android.ui.components.ChoiceOption
import org.openoura.android.ui.components.ConnectedChoice
import org.openoura.android.ui.components.SectionHeader
import org.openoura.android.ui.components.ShapedIcon
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
    onLabelEffort: (EffortSession, kind: String?, note: String?) -> Unit,
    onOpenNap: (NightRow) -> Unit,
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
                if (sleep) SleepReport(summary, ymd, onOpenNap) else ActivityReport(summary, ymd, onLabelEffort)
            }
        }
    }
}

// ── sleep ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.SleepReport(s: Summary, ymd: String, onOpenNap: (NightRow) -> Unit) {
    val n = s.nightForDay(ymd)
    if (n == null) {
        Notice("No scored night for this day.")
        return
    }
    SleepBody(s, n, nap = false) {
        val naps = s.napsForDay(ymd)
        if (naps.isNotEmpty()) {
            NapList(naps, onOpenNap)
            Footnote(
                "Only the day's main sleep counts toward the Sleep score, readiness and sleep " +
                    "debt. Rests under 90 minutes aren't listed: they can't be staged, and the " +
                    "ring logs sitting still the same way.",
            )
        }
    }
}

/**
 * A nap or other second sleep period of a day, on its own page: the night's layout without
 * the Sleep score, the night-time reading and sleep debt, none of which describe a nap.
 */
@Composable
fun NapReportScreen(summary: Summary, id: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val n = summary.nightById(id)
        if (n == null) {
            // A recompute can replace the period (the ring declaring a detected one, say).
            Notice("This sleep is no longer in the summary.")
            return@Column
        }
        SleepBody(summary, n, nap = true)
    }
}

/** A day's naps as one segmented group, each opening its own page. */
@Composable
private fun NapList(naps: List<NightRow>, onOpenNap: (NightRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        naps.forEachIndexed { i, n ->
            SegmentedListItem(
                onClick = { onOpenNap(n) },
                shapes = ListItemDefaults.segmentedShapes(i, naps.size),
                colors = groupColors(),
                leadingContent = {
                    ShapedIcon(R.drawable.ic_sym_bedtime, container = Topics.sleep.container, content = Topics.sleep.onContainer)
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(NapText.line(n))
                        StageBar(n, height = 8.dp)
                    }
                },
                trailingContent = { Icon(painterResource(R.drawable.ic_sym_chevron_right), contentDescription = null) },
                content = { Text(NapText.times(n)) },
            )
        }
    }
}

/** A nap's words, shared by the day report's list and Today's tile. Pure, so tested. */
object NapText {
    // No "Nap": the summary only knows this is not the day's longest sleep. A real one on
    // the phone ran 19:54–23:23, which reads as the first half of a split night as easily.

    /** "14:05–15:50", for the day report's row under the night's headline figures. */
    fun times(n: NightRow): String = "${n.start ?: "—"}–${n.end ?: "—"}"

    /** "Also slept · 14:05–15:50", for Today's tile, which has no heading to lean on. */
    fun title(n: NightRow): String = "Also slept · ${times(n)}"

    /** "1h 09m asleep of 1h 45m in bed", or the time in bed alone without stages. */
    fun line(n: NightRow): String {
        // From the exact instants where there are some: `inBedH` is rounded to a tenth of
        // an hour, which would turn 1h 45m into "1h 48m" beside its own 14:05–15:50.
        val inBedMin = n.startUnix?.let { s -> n.endUnix?.let { e -> (e - s) / 60.0 } } ?: n.inBedH?.let { it * 60 }
        val inBed = inBedMin?.let { BentoText.hoursMinutes(it) }
        val asleep = n.metrics?.asleepMin?.let { BentoText.hoursMinutes(it) }
        return when {
            asleep != null && inBed != null -> "$asleep asleep of $inBed in bed"
            inBed != null -> "$inBed in bed · not staged"
            else -> "Not staged"
        }
    }

    /** Today's shorter line: "1h 09m asleep", or the time in bed without stages. */
    fun tileLine(n: NightRow): String =
        n.metrics?.asleepMin?.let { "${BentoText.hoursMinutes(it)} asleep" }
            ?: n.inBedH?.let { "%.1f h in bed".format(it) }
            ?: "Not staged"
}

/**
 * One sleep period's report. For the night, [afterHeadline] slots the day's naps under
 * its headline figures; a nap gets a note in place of the Sleep score, and no night-time
 * reading or sleep debt.
 */
@Composable
private fun ColumnScope.SleepBody(
    s: Summary,
    n: NightRow,
    nap: Boolean,
    afterHeadline: @Composable ColumnScope.() -> Unit = {},
) {
    // The web's four, in the web's order. HRV and resting HR used to sit here; they are
    // already the first two vitals tiles on Today, while asleep-vs-in-bed is the
    // comparison this page is about.
    HeroStats(
        listOf(
            "%.1f h".format(n.inBedH ?: 0.0) to "Time in bed",
            (n.metrics?.asleepMin?.let { "%.1f h".format(it / 60.0) } ?: "—") to "Asleep",
            (n.efficiency?.let { "${it.roundToInt()}%" } ?: "—") to "Efficiency",
            "${n.start ?: "—"}–${n.end ?: "—"}" to if (nap) "Times" else "Bedtime",
        ),
    )
    SleepInterpretation.detectedNote(n.detected)?.let { Footnote(it) }
    afterHeadline()

    if (nap) {
        // The summary scores every period, but the score's timing part judges a period's
        // midpoint against a night-time ideal, so an afternoon nap would always lose on it.
        Notice(
            "No Sleep score here. The score describes a day's main sleep, and its timing " +
                "part judges it against your usual night. This sleep doesn't count toward " +
                "readiness or sleep debt either.",
        )
    } else {
        n.sleepScore?.takeIf { it.score != null }?.let { score ->
            SectionHeader("Sleep score", Modifier.padding(top = 4.dp))
            SleepScoreCard(score)
            Footnote(
                "Worked out on this phone from the stages below, the way the Oura app scores a night. " +
                    Chronotypes.timingNote(s.chronotype),
            )
        }
    }

    SectionHeader(if (nap) "Polysomnograph" else "Overnight polysomnograph", Modifier.padding(top = 4.dp))
    StageLegend(Modifier.padding(start = 4.dp))
    Polysomnograph(n)

    SectionHeader("Sleep architecture", Modifier.padding(top = 4.dp))
    StageBar(n)
    SleepInterpretation.stageSourceNote(n.stagesSource)?.let { Footnote(it) }

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

    n.breathingRate?.rate?.let { rate ->
        SectionHeader("Breathing", Modifier.padding(top = 4.dp))
        MetricGrid(
            listOfNotNull(
                "%.1f /min".format(rate) to "Breathing rate",
                n.breathingRate.variability?.let { "%.1f /min".format(it) to "Variability" },
            ),
        )
        Footnote(
            "Worked out on this phone from the rhythm breathing leaves in your heartbeat, " +
                "averaged over the middle of the night. Most adults breathe 12 to 20 times a " +
                "minute asleep; a change from your own usual rate says more than the number.",
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

    // The reading is written for a night ("four to six cycles is typical"), and sleep debt
    // is built from nights only, so neither is said under a nap.
    if (nap) return
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

/** The night's stage split, on its own card. */
@Composable
private fun StageBar(n: NightRow) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        StageSplit(n, Modifier.padding(16.dp))
    }
}

// ── activity ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.ActivityReport(
    s: Summary,
    ymd: String,
    onLabelEffort: (EffortSession, String?, String?) -> Unit,
) {
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

    s.activityScores[ymd]?.let { a ->
        val total = a.score ?: return@let
        SectionHeader("Activity score", Modifier.padding(top = 4.dp))
        ScoreCard(total, ScoreText.activity(a.contributors))
        Footnote(
            "Worked out on this phone the way the Oura app scores a day, from 04:00 to 04:00, " +
                "against the nine days before it." +
                (a.targetKcal?.takeIf { it > 0 }?.let { " Today's target: $it active kcal, scaled by your readiness." } ?: ""),
        )
    }

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
    EffortSessions(s, ymd, onLabelEffort)
}

/**
 * How an effort session is named and broken down. A session is named only from the gait
 * the ring measured (see [EffortSession]); without one it stays "Effort".
 */
object GaitText {
    fun name(label: String?): String? = when (label) {
        "walking" -> "Walking"
        "jogging" -> "Jogging"
        else -> null
    }

    fun title(e: EffortSession, clock: String): String = "${name(e.label) ?: "Effort"} at $clock"

    /** Minutes of each gait in the session, the larger first: "Jogging 10 min · Walking 9 min". */
    fun breakdown(e: EffortSession): String? {
        val totals = e.gait.groupBy { it.label }
            .mapNotNull { (l, segs) -> name(l)?.let { it to segs.sumOf { s -> s.durationMin } } }
            .sortedByDescending { it.second }
        if (totals.isEmpty()) return null
        return totals.joinToString(" · ") { (n, m) -> "$n ${m.roundToInt()} min" }
    }

    @DrawableRes
    fun icon(label: String?): Int = when (label) {
        "walking" -> R.drawable.ic_cap_walking
        "jogging" -> R.drawable.ic_cap_run
        else -> R.drawable.ic_sym_monitor_heart
    }
}

/**
 * Sessions the ring recorded an exercise-HR trace for, named walking or jogging where the
 * ring's gait records say so, and "Effort" where they don't.
 *
 * The person can name a session too: tapping one asks "What was this?", and the answer is
 * shown as theirs, with its own mark, ahead of the gait name and never dressed up as
 * something the ring detected.
 */
@Composable
private fun ColumnScope.EffortSessions(
    s: Summary,
    ymd: String,
    onLabel: (EffortSession, String?, String?) -> Unit,
) {
    val sessions = s.effortForDay(ymd)
    SectionHeader("Sessions", Modifier.padding(top = 4.dp))
    if (sessions.isEmpty()) {
        Notice("No sessions detected this day.")
        return
    }
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val at = { e: EffortSession -> clock.format(Instant.ofEpochSecond(e.start.toLong()).atZone(ZoneId.systemDefault())) }
    // By start time rather than the object: a recompute behind the open sheet replaces it.
    var editingStart by rememberSaveable { mutableStateOf<Double?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        sessions.forEachIndexed { i, e ->
            val bits = buildList {
                add("${e.durationMin.roundToInt()} min")
                GaitText.breakdown(e)?.let { add(it) }
                e.hrMean?.let { add("${it.roundToInt()} bpm avg") }
                e.hrPeak?.let { add("${it.roundToInt()} peak") }
                e.intensityPeak?.let { add("intensity ${it.roundToInt()}") }
            }
            val label = e.userLabel
            val shapes = ListItemDefaults.segmentedShapes(i, sessions.size)
            val leading: @Composable () -> Unit = {
                if (label != null) {
                    ShapedIcon(
                        ActivityLabels.icon(label),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    ShapedIcon(GaitText.icon(e.label))
                }
            }
            val supporting: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (label != null) YourLabelLine(ActivityLabels.note(label))
                    Text(bits.joinToString(" · "))
                }
            }
            val headline: @Composable () -> Unit = {
                Text(if (label != null) "${ActivityLabels.title(label)} at ${at(e)}" else GaitText.title(e, at(e)))
            }
            if (e.ring != null) {
                SegmentedListItem(
                    onClick = { editingStart = e.start },
                    shapes = shapes,
                    colors = groupColors(),
                    leadingContent = leading,
                    supportingContent = supporting,
                    trailingContent = {
                        Icon(
                            painterResource(if (label != null) R.drawable.ic_sym_edit else R.drawable.ic_sym_sell),
                            contentDescription = if (label != null) "Change your label" else "Label this session",
                        )
                    },
                    content = headline,
                )
            } else {
                // A summary from before sessions carried their ring time: nothing to key a
                // label by until the next recompute.
                SegmentedListItem(
                    shapes = shapes,
                    colors = groupColors(),
                    leadingContent = leading,
                    supportingContent = supporting,
                    content = headline,
                )
            }
        }
    }
    Footnote(
        "Detected from the ring's own exercise-HR trace — it records while the ring thinks " +
            "you're working. Walking and jogging come from the ring's step rhythm; a session " +
            "without a steady one, like a ride, stays \"Effort\" rather than getting a guessed " +
            "name. Running isn't told apart from jogging yet. Tap a session to say what it was " +
            "yourself; your label is kept with the ring's history on this phone and in its " +
            "backups. Heart rate is missing from some because motion corrupts the optical " +
            "signal, which is exactly when the ring is least able to read a clean beat.",
    )

    val editing = editingStart?.let { start -> sessions.firstOrNull { it.start == start } }
    if (editing != null) {
        LabelSheet(
            session = editing,
            title = "Effort at ${at(editing)} · ${editing.durationMin.roundToInt()} min",
            onDismiss = { editingStart = null },
            onSave = { kind, note ->
                onLabel(editing, kind, note)
                editingStart = null
            },
        )
    }
}

/** The mark that says a session's name came from the person, not the ring. */
@Composable
private fun YourLabelLine(note: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painterResource(R.drawable.ic_sym_sell),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (note != null) "Your label · $note" else "Your label",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/**
 * "What was this?" — pick an activity, optionally say more. Saving with nothing picked is
 * not offered; taking a label off is its own button, so it is never done by accident.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LabelSheet(
    session: EffortSession,
    title: String,
    onDismiss: () -> Unit,
    onSave: (kind: String?, note: String?) -> Unit,
) {
    val current = session.userLabel
    var kind by rememberSaveable { mutableStateOf(current?.kind) }
    var note by rememberSaveable { mutableStateOf(current?.note.orEmpty()) }
    val save = { kind?.let { onSave(it, note.trim().ifEmpty { null }) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What was this?", style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    "$title. The ring only knows you were working hard; what you call it is " +
                        "yours, and is shown as your label.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A kind saved by a later build than this one still shows, so it can be kept.
                val unknown = current?.kind?.takeIf { ActivityLabels.kind(it) == null }
                val options = ActivityLabels.kinds.map { Triple(it.key, it.name, it.icon) } +
                    listOfNotNull(unknown?.let { Triple(it, it.replaceFirstChar { c -> c.uppercase() }, R.drawable.ic_sym_sell) })
                options.forEach { (key, name, icon) ->
                    ToggleButton(
                        checked = kind == key,
                        onCheckedChange = { kind = if (it) key else null },
                        icon = { Icon(painterResource(icon), contentDescription = null, Modifier.size(ToggleButtonDefaults.IconSize)) },
                    ) { Text(name) }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text(if (kind == ActivityLabels.OTHER) "What was it?" else "Note (optional)") },
                placeholder = { Text(if (kind == ActivityLabels.OTHER) "Bouldering" else "Salsa class") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    TextButton(onClick = { onSave(null, null) }, shapes = ButtonDefaults.shapes()) { Text("Remove label") }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { save() }, enabled = kind != null, shapes = ButtonDefaults.shapes()) { Text("Save") }
            }
        }
    }
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
private fun SleepScoreCard(sc: SleepScore) {
    val total = sc.score ?: return
    ScoreCard(total, SleepInterpretation.scoreContributors(sc.contributors))
}

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
