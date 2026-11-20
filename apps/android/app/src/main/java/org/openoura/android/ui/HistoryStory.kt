package org.openoura.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openoura.android.data.NightRow
import org.openoura.android.data.Summary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

// History as a day story (2026-11-20): each day — or week, or month — is a stop on one
// vertical line, its three scores as labelled pills in their topic colours, last night's
// stages as a bar, and a line of what it held. Week and Month average the days inside.

enum class HistoryScale { DAY, WEEK, MONTH }

/** One stop on the line: a day, or the days of a week or a month averaged. */
data class HistoryStop(
    /** The first day it covers, "2026-11-16"; for a day stop, the day. */
    val key: String,
    val title: String,
    /** A day stop's day, to open its report; null for a week or month. */
    val day: String?,
    val days: Int,
    val readiness: Int?,
    val sleep: Int?,
    val activity: Int?,
    /** Average minutes asleep over the nights that had one. */
    val asleepMin: Double?,
    /** Average steps over the days that had any. */
    val steps: Double?,
    /** Average walking distance in metres over the days that had any. */
    val distanceM: Double? = null,
    /** A day stop's main night, for its stage bar. */
    val night: NightRow? = null,
    /** A day stop's naps: their start times. */
    val naps: List<String> = emptyList(),
)

/** The stops for a scale, newest first. Pure, so tested. */
object HistoryStops {
    fun build(s: Summary, scale: HistoryScale, today: LocalDate? = null): List<HistoryStop> {
        val days = s.days.mapNotNull { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { it to d } }
        return when (scale) {
            HistoryScale.DAY -> days.map { (date, d) -> dayStop(s, d, date, today) }
            HistoryScale.WEEK -> days.groupBy { it.first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
                .map { (monday, group) -> period(s, monday, weekTitle(monday), group.map { it.second }) }
            HistoryScale.MONTH -> days.groupBy { YearMonth.from(it.first) }
                .map { (ym, group) -> period(s, ym.atDay(1), ym.format(DateTimeFormatter.ofPattern("MMMM yyyy")), group.map { it.second }) }
        }.sortedByDescending { it.key }
    }

    private fun dayStop(s: Summary, d: String, date: LocalDate, today: LocalDate?): HistoryStop {
        val night = s.nightForDay(d)
        val naps = s.nights.filter { !it.main && s.wakeYmd(it) == d }.mapNotNull { it.start }.sorted()
        return HistoryStop(
            key = d,
            title = date.format(DateTimeFormatter.ofPattern("EEE · MMM d")),
            day = d,
            days = 1,
            readiness = s.readiness[d]?.score,
            sleep = night?.sleepScore?.score,
            activity = s.activityScores[d]?.score,
            asleepMin = night?.metrics?.asleepMin,
            steps = s.activityDaily[d]?.steps?.takeIf { it > 0 },
            distanceM = s.activityDaily[d]?.distanceM?.takeIf { it >= 100 },
            night = night,
            naps = naps,
        )
    }

    private fun period(s: Summary, first: LocalDate, title: String, days: List<String>): HistoryStop {
        fun avg(xs: List<Double>) = xs.takeIf { it.isNotEmpty() }?.average()
        return HistoryStop(
            key = first.toString(),
            title = title,
            day = null,
            days = days.size,
            readiness = avg(days.mapNotNull { s.readiness[it]?.score?.toDouble() })?.roundToInt(),
            sleep = avg(days.mapNotNull { s.nightForDay(it)?.sleepScore?.score?.toDouble() })?.roundToInt(),
            activity = avg(days.mapNotNull { s.activityScores[it]?.score?.toDouble() })?.roundToInt(),
            asleepMin = avg(days.mapNotNull { s.nightForDay(it)?.metrics?.asleepMin }),
            steps = avg(days.mapNotNull { s.activityDaily[it]?.steps?.takeIf { st -> st > 0 } }),
            distanceM = avg(days.mapNotNull { s.activityDaily[it]?.distanceM?.takeIf { m -> m >= 100 } }),
        )
    }

    /** "Nov 16 – 22", or "Oct 26 – Nov 1" across a month. */
    fun weekTitle(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        val start = monday.format(DateTimeFormatter.ofPattern("MMM d"))
        val end = sunday.format(DateTimeFormatter.ofPattern(if (sunday.month == monday.month) "d" else "MMM d"))
        return "$start – $end"
    }

    /** "5h 54m asleep · 4.8k steps · 3.7 km", averaged ones prefixed "avg". */
    fun line(stop: HistoryStop): String {
        val avg = stop.day == null
        val bits = listOfNotNull(
            stop.asleepMin?.let { "${BentoText.hoursMinutes(it)} asleep" },
            stop.steps?.let {
                if (it >= 1000) "%.1fk steps".format(it / 1000) else "${it.roundToInt()} steps"
            },
            stop.distanceM?.let { "%.1f km".format(it / 1000) },
        )
        return when {
            bits.isEmpty() -> "No data"
            avg -> "avg " + bits.joinToString(" · ")
            else -> bits.joinToString(" · ")
        }
    }
}

/**
 * The History list: [header] (the view switch and the Cardiovascular panel) and then the
 * stops for [scale] on one line. A day opens its report; a week or month is a summary.
 */
@Composable
fun HistoryStory(
    summary: Summary,
    scale: HistoryScale,
    onOpenDay: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val stops = remember(summary, scale) { HistoryStops.build(summary, scale) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        item(key = "header") { header() }
        items(stops, key = { "${scale.name}-${it.key}" }) { stop ->
            Stop(stop, first = stop === stops.first(), last = stop === stops.last(), onOpenDay = onOpenDay)
        }
    }
}

/** The line's marker shapes, turning through Material 3 Expressive's rounder forms. */
private val MARKERS: List<Shape> = listOf(
    CircleShape,
    RoundedCornerShape(5.dp),
    RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 2.dp),
)

@Composable
private fun Stop(stop: HistoryStop, first: Boolean, last: Boolean, onOpenDay: (String, Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // The line and this stop's marker.
        Box(Modifier.width(28.dp).fillMaxHeight()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (first) 12.dp else 0.dp)
                    .width(2.dp)
                    // The last stop's line ends at its marker.
                    .then(if (last) Modifier.height(12.dp) else Modifier.fillMaxHeight())
                    .background(cs.outlineVariant),
            )
            val shape = MARKERS[(stop.key.hashCode() and 0x7fffffff) % MARKERS.size]
            val marker = Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(16.dp).clip(shape)
            if (first) {
                Box(marker.background(cs.surface).border(2.dp, cs.primary, shape))
            } else {
                Box(marker.background(cs.primaryContainer))
            }
        }
        Column(Modifier.weight(1f).padding(start = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stop.title, style = MaterialTheme.typography.titleMediumEmphasized)
                val note = when {
                    stop.day == null -> "${stop.days} day${if (stop.days == 1) "" else "s"}"
                    stop.naps.isNotEmpty() -> "+ nap ${stop.naps.joinToString(", ")}"
                    else -> null
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
            }
            val card: @Composable () -> Unit = {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScorePill("Readiness", stop.readiness, Topics.readiness)
                        ScorePill("Sleep", stop.sleep, Topics.sleep)
                        ScorePill("Activity", stop.activity, Topics.activity)
                    }
                    Text(HistoryStops.line(stop), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            val day = stop.day
            if (day != null) {
                Surface(
                    onClick = { onOpenDay(day, stop.night != null) },
                    shape = RoundedCornerShape(24.dp),
                    color = cs.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) { card() }
            } else {
                Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) { card() }
            }
        }
    }
}

/** "Readiness 73" in the topic's colour; a score the day did not have is left out. */
@Composable
private fun ScorePill(label: String, value: Int?, topic: Topic) {
    value ?: return
    Box(
        Modifier
            .clip(CircleShape)
            .background(topic.container)
            .semantics { contentDescription = "$label $value" }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text("$label $value", style = MaterialTheme.typography.labelMedium, color = topic.onContainer)
    }
}
