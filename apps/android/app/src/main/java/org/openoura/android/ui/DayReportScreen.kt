package org.openoura.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.data.NightRow
import org.openoura.android.data.Summary
import org.openoura.android.ui.theme.Oura
import org.openoura.android.ui.theme.OuraColors
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The full-page day report — Sleep and Activity tabs, the counterpart to the web's
 * `openDayPage` and the iOS `DayReportView`.
 *
 * Every clinical number here (`metrics`, `autonomic`, the stage percentages) comes
 * straight from `oura-summary`. Nothing is recomputed in Kotlin.
 */
@Composable
fun DayReportScreen(
    summary: Summary,
    ymd: String,
    sleepTab: Boolean,
    onSelectTab: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    BackHandler(onBack = onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹ Back",
                    color = c.muted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, c.line, RoundedCornerShape(999.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(dayTitle(ymd), color = c.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.surface2)
                    .border(1.dp, c.line, RoundedCornerShape(999.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TabChip("Sleep", sleepTab) { onSelectTab(true) }
                TabChip("Activity", !sleepTab) { onSelectTab(false) }
            }
        }

        if (sleepTab) SleepReport(summary, ymd, c) else ActivityReport(summary, ymd, c)
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Oura.colors
    Text(
        label,
        color = if (selected) c.text else c.muted,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) c.surface else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

// ── sleep ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SleepReport(s: Summary, ymd: String, c: OuraColors) {
    val n = s.nightForDay(ymd)
    if (n == null) {
        Text("No scored night for this day.", color = c.muted, fontSize = 13.sp)
        return
    }
    StatStrip(
        listOf(
            "%.1fh".format(n.inBedH ?: 0.0) to "in bed",
            "${(n.efficiency ?: 0.0).roundToInt()}%" to "efficiency",
            (n.hrvMs?.let { "${it.roundToInt()} ms" } ?: "—") to "hrv",
            (n.rhr?.let { "${it.roundToInt()} bpm" } ?: "—") to "resting hr",
        ),
        c,
    )

    Section("Hypnogram", c)
    n.stages?.takeIf { it.isNotEmpty() }?.let { Hypnogram(it, c, height = 60.dp) }
        ?: Text("No hypnogram for this night.", color = c.muted, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StagePct("Deep", n.deepPct, c); StagePct("Light", n.lightPct, c)
        StagePct("REM", n.remPct, c); StagePct("Awake", n.wakePct, c)
    }

    n.metrics?.let { m ->
        Section("Sleep metrics", c)
        MetricGrid(
            listOfNotNull(
                m.asleepMin?.let { "%.0f min".format(it) to "asleep" },
                m.onsetMin?.let { "%.0f min".format(it) to "onset" },
                m.wasoMin?.let { "%.0f min".format(it) to "awake after onset" },
                m.remLatencyMin?.let { "%.0f min".format(it) to "rem latency" },
                m.awakenings?.let { "$it" to "awakenings" },
                m.cycles?.let { "$it" to "cycles" },
                m.fragIndex?.let { "%.1f".format(it) to "fragmentation" },
            ),
            c,
        )
    }

    n.autonomic?.let { a ->
        if (listOfNotNull(a.hrDeep, a.hrLight, a.hrRem, a.hrvDeep, a.hrvLight, a.hrvRem).isNotEmpty()) {
            Section("Autonomic recovery by stage", c)
            MetricGrid(
                listOfNotNull(
                    a.hrDeep?.let { "%.0f bpm".format(it) to "hr · deep" },
                    a.hrLight?.let { "%.0f bpm".format(it) to "hr · light" },
                    a.hrRem?.let { "%.0f bpm".format(it) to "hr · rem" },
                    a.hrvDeep?.let { "%.0f ms".format(it) to "hrv · deep" },
                    a.hrvLight?.let { "%.0f ms".format(it) to "hrv · light" },
                    a.hrvRem?.let { "%.0f ms".format(it) to "hrv · rem" },
                ),
                c,
            )
            Text(
                "Per-stage means rather than an overnight HRV trend: nocturnal HRV is " +
                    "stage-driven (deep up, REM down), so a slope would track stage order, " +
                    "not recovery.",
                color = c.faint, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun StagePct(label: String, pct: Double?, c: OuraColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = c.muted, fontSize = 11.sp)
        Text(
            "${(pct ?: 0.0).roundToInt()}%",
            color = c.text, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        )
    }
}

// ── activity ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ActivityReport(s: Summary, ymd: String, c: OuraColors) {
    val ds = s.activityDaily[ymd]
    val prof = s.activityProfile[ymd] ?: emptyList()
    val steps = s.activitySteps[ymd] ?: emptyList()

    StatStrip(
        listOfNotNull(
            "${(ds?.steps ?: 0.0).roundToInt()}" to "steps",
            "${(ds?.activeKcal ?: 0.0).roundToInt()} kcal" to "active energy",
            "${(ds?.totalKcal ?: 0.0).roundToInt()} kcal" to "total energy",
            ds?.distanceM?.let { "%.1f km".format(it / 1000) to "distance" },
        ),
        c,
    )

    Section("Movement across the day", c)
    if (prof.size > 1) {
        MovementChart(prof = prof, steps = steps, dayTotalSteps = ds?.steps)
    } else {
        Text("No movement profile for this day.", color = c.muted, fontSize = 12.sp)
    }

    val bucketMin = if (prof.isEmpty()) 15.0 else 24.0 * 60.0 / prof.size
    val activeMin = prof.count { it >= 3 } * bucketMin
    val lightMin = prof.count { it >= 1.5 && it < 3 } * bucketMin
    val peakMet = prof.maxOrNull() ?: 0.0
    val peakHr = ds?.peakHr

    Section("Intensity", c)
    MetricGrid(
        listOf(
            "${activeMin.roundToInt()} min" to "active",
            "${lightMin.roundToInt()} min" to "lightly active",
            "%.1f MET".format(peakMet) to "peak intensity",
            (peakHr?.let { "${it.roundToInt()} bpm" } ?: "—") to "peak hr",
            "${s.effortForDay(ymd).size}" to "sessions",
        ),
        c,
    )
    EffortSessions(s, ymd, c)

    if (peakHr != null) {
        val hrMax = s.fitness?.hrMaxPredicted
        val pct = hrMax?.let { (peakHr / it * 100).roundToInt() }
        Text(
            if (pct == null) {
                "Peak HR is the highest 30-second sustained rate of the day, from the " +
                    "ring's quality-checked beats."
            } else {
                "Peak HR is the highest 30-second sustained rate of the day — $pct% of the " +
                    "${hrMax.roundToInt()} bpm predicted for your age. ${peakHrVerdict(pct)}"
            },
            color = c.faint, fontSize = 11.sp,
        )
    }
}

/**
 * Sessions the ring recorded an exercise-HR trace for.
 *
 * Shown as "effort", never as a named workout: labelling is what Oura's AAD model does and
 * it isn't bundled, so `activity[]` stays empty. Mirrors the web's effort rows.
 */
@Composable
private fun EffortSessions(s: Summary, ymd: String, c: OuraColors) {
    val sessions = s.effortForDay(ymd)
    Section("Sessions", c)
    if (sessions.isEmpty()) {
        Text("No sessions detected this day.", color = c.muted, fontSize = 12.sp)
        return
    }
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sessions.forEach { e ->
            val bits = buildList {
                add("${e.durationMin.roundToInt()} min")
                e.hrMean?.let { add("${it.roundToInt()} bpm avg") }
                e.hrPeak?.let { add("${it.roundToInt()} peak") }
                e.intensityPeak?.let { add("intensity ${it.roundToInt()}") }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "effort · ${clock.format(
                        Instant.ofEpochSecond(e.start.toLong()).atZone(ZoneId.systemDefault()),
                    )}",
                    color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                )
                Text(bits.joinToString(" · "), color = c.faint, fontSize = 11.sp)
            }
        }
    }
    Text(
        "Detected from the ring's own exercise-HR trace — it records while the ring thinks " +
            "you're working, so these are start/stop and effort, not labelled workouts. " +
            "Naming them needs Oura's activity model, which isn't bundled. Heart rate is " +
            "missing from some because motion corrupts the optical signal, which is exactly " +
            "when the ring is least able to read a clean beat.",
        color = c.faint, fontSize = 10.sp,
    )
}

/** Shared with the web `peakHrVerdict` in app.js — keep the 95/85 thresholds in step. */
fun peakHrVerdict(pct: Int): String = when {
    pct >= 95 -> "That's close enough to a true maximum to be worth trusting as one."
    pct >= 85 -> "That's a hard effort, but still short of a true maximum, which needs roughly 95%."
    else -> "That's the hardest sustained stretch of the day rather than anything near your ceiling."
}

// ── shared bits ──────────────────────────────────────────────────────────────────────

@Composable
private fun Section(title: String, c: OuraColors) {
    Text(
        title.uppercase(),
        color = c.faint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun StatStrip(items: List<Pair<String, String>>, c: OuraColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEach { (v, k) ->
            Column(horizontalAlignment = Alignment.Start) {
                Text(v, color = c.text, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                Text(k.uppercase(), color = c.faint, fontSize = 9.sp, letterSpacing = 0.6.sp)
            }
        }
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>, c: OuraColors) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (v, k) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.surface)
                            .border(1.dp, c.line, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                    ) {
                        Text(v, color = c.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                        Text(k.uppercase(), color = c.faint, fontSize = 9.sp, letterSpacing = 0.5.sp)
                    }
                }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}
