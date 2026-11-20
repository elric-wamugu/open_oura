package org.openoura.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openoura.android.data.NightRow
import org.openoura.android.data.Summary
import org.openoura.android.ui.theme.dataColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// Today as a bento (2026-11-20): the day's three scores as tiles of different sizes and
// shapes, each in its topic's colour, then last night and the day's movement across the
// full width. Material 3 Expressive's mixed shapes do the hierarchy: the tall Readiness
// tile reads first, Sleep and Activity beside it.

/** A container colour and the colour drawn on it. */
@Immutable
data class Topic(val container: Color, val onContainer: Color)

/**
 * The three topics' colours, used wherever a score appears so each reads at a glance:
 * Readiness the brand teal, Sleep indigo (the night's stage hues), Activity amber.
 */
object Topics {
    @Composable
    private fun dark() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val readiness: Topic
        @Composable get() = Topic(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)

    val sleep: Topic
        @Composable get() = if (dark()) Topic(Color(0xFF3A3F6B), Color(0xFFDDE0FF)) else Topic(Color(0xFFDDE1FF), Color(0xFF283077))

    val activity: Topic
        @Composable get() = if (dark()) Topic(Color(0xFF5B4A2A), Color(0xFFFFE2B0)) else Topic(Color(0xFFFFDDB0), Color(0xFF4A3000))
}

/** "Friday" over "Nov 20": the day the page is about, where the app's name used to be. */
@Composable
fun DayHeader(ymd: String) {
    val date = runCatching { LocalDate.parse(ymd) }.getOrNull()
    Column {
        Text(
            date?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.getDefault()) ?: ymd,
            style = MaterialTheme.typography.headlineSmallEmphasized,
        )
        date?.let {
            Text(
                it.format(DateTimeFormatter.ofPattern("MMM d")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The word under the Readiness figure, with the weakest contributor when one is dragging
 * it down: "Good · HRV balance low". Pure, so tested.
 */
object BentoText {
    fun readinessLine(score: Int, contributors: List<Pair<String, Int>>): String {
        val word = SleepInterpretation.scoreLabel(score)
        val weakest = contributors.minByOrNull { it.second }?.takeIf { it.second < 60 }
        return if (weakest != null) "$word · ${weakest.first.lowercase()} low" else word
    }

    /** "5h 54m" from minutes. */
    fun hoursMinutes(min: Double): String {
        val m = min.roundToInt()
        return "${m / 60}h ${"%02d".format(m % 60)}m"
    }

    /** "4.8k steps · 3.5 km", either half left out when missing. */
    fun activityLine(steps: Double?, distanceM: Double?): String = listOfNotNull(
        steps?.takeIf { it > 0 }?.let {
            if (it >= 1000) "%.1fk steps".format(it / 1000) else "${it.roundToInt()} steps"
        },
        distanceM?.takeIf { it >= 100 }?.let { "%.1f km".format(it / 1000) },
    ).joinToString(" · ").ifEmpty { "No steps yet" }
}

/** The three scores as the bento's top: Readiness tall on the left, Sleep and Activity stacked. */
@Composable
fun ScoreBento(
    s: Summary,
    day: String,
    onReadiness: () -> Unit,
    onSleep: () -> Unit,
    onActivity: () -> Unit,
) {
    val readiness = s.readiness[day]
    val sleep = s.nightForDay(day)?.sleepScore?.score
    val activity = s.activityScores[day]?.score
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val r = readiness?.score
        BentoTile(
            topic = Topics.readiness,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp),
            onClick = onReadiness.takeIf { r != null },
            spoken = r?.let { "Readiness score $it, ${SleepInterpretation.scoreLabel(it)}" } ?: "Readiness not available yet",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { on ->
            Column(Modifier.padding(16.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text("Readiness", style = MaterialTheme.typography.labelLarge, color = on)
                Text(r?.toString() ?: "—", style = MaterialTheme.typography.displayLargeEmphasized, color = on)
                Text(
                    r?.let { BentoText.readinessLine(it, ScoreText.readiness(readiness.contributors)) } ?: "After a scored night",
                    style = MaterialTheme.typography.labelMedium,
                    color = on,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallScore("Sleep", sleep, Topics.sleep, RoundedCornerShape(percent = 50), onSleep)
            SmallScore(
                "Activity",
                activity,
                Topics.activity,
                RoundedCornerShape(topStart = 12.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 32.dp),
                onActivity,
            )
        }
    }
}

@Composable
private fun SmallScore(label: String, value: Int?, topic: Topic, shape: Shape, onClick: () -> Unit) {
    BentoTile(
        topic = topic,
        shape = shape,
        onClick = onClick.takeIf { value != null },
        spoken = value?.let { "$label score $it, ${SleepInterpretation.scoreLabel(it)}" } ?: "$label score not available yet",
        modifier = Modifier.fillMaxWidth(),
    ) { on ->
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = on)
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value?.toString() ?: "—", style = MaterialTheme.typography.headlineMediumEmphasized, color = on)
                value?.let {
                    Text(
                        SleepInterpretation.scoreLabel(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = on,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BentoTile(
    topic: Topic,
    shape: Shape,
    onClick: (() -> Unit)?,
    spoken: String,
    modifier: Modifier,
    content: @Composable (Color) -> Unit,
) {
    val m = modifier.semantics(mergeDescendants = true) { contentDescription = spoken }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = topic.container, modifier = m) { content(topic.onContainer) }
    } else {
        Surface(shape = shape, color = topic.container, modifier = m) { content(topic.onContainer) }
    }
}

/**
 * Last night across the full width: when, how long asleep, and the stage split with its
 * figures — the same [StageSplit] as the sleep page's "Sleep architecture".
 */
@Composable
fun LastNightTile(n: NightRow, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Last night", style = MaterialTheme.typography.titleMediumEmphasized)
                val asleep = n.metrics?.asleepMin?.let { "${BentoText.hoursMinutes(it)} asleep" }
                Text(
                    listOfNotNull("${n.start ?: "—"}–${n.end ?: "—"}", asleep).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            StageSplit(n)
        }
    }
}

/** A night's stages as one rounded bar, to scale; nothing when the night has no stages. */
@Composable
fun StageBar(n: NightRow, height: androidx.compose.ui.unit.Dp = 12.dp) {
    val d = MaterialTheme.dataColors
    val parts = listOf(n.deepPct to d.deep, n.lightPct to d.light, n.remPct to d.rem, n.wakePct to d.wake)
    if (parts.sumOf { it.first ?: 0.0 } <= 0) return
    Row(
        Modifier.fillMaxWidth().height(height).clip(CircleShape),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        parts.forEach { (pct, colour) ->
            val w = (pct ?: 0.0).toFloat()
            if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().background(colour))
        }
    }
}

/** The day's movement across the full width: steps and distance, and the 24-hour ridge. */
@Composable
fun ActivityTile(s: Summary, day: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ds = s.activityDaily[day]
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Activity", style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    BentoText.activityLine(ds?.steps, ds?.distanceM),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            s.activityProfile[day]?.takeIf { it.size > 1 }?.let { prof ->
                MovementRidge(prof, Topics.activity.onContainer.takeIf { cs.surface.luminance() < 0.5f } ?: cs.tertiary, cs.outlineVariant)
                RidgeAxis()
            }
        }
    }
}
