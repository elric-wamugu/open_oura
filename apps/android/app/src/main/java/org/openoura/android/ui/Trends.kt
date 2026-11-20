package org.openoura.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openoura.android.data.Summary
import org.openoura.android.ui.components.ChoiceOption
import org.openoura.android.ui.components.ConnectedChoice
import org.openoura.android.ui.theme.chartColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One measure worth watching over weeks. Nightly ones are read off the night a day was
 * woken from, the same pairing the day cards use; steps are the day's own.
 */
enum class TrendMetric(val title: String, val unit: String, val decimals: Int, val bars: Boolean = false) {
    HRV("HRV", "ms", 0),
    RESTING_HR("Resting heart rate", "bpm", 0),
    ASLEEP("Time asleep", "h", 1),
    EFFICIENCY("Sleep efficiency", "%", 0),
    SPO2("Blood oxygen", "%", 0),
    SKIN_TEMP("Skin temperature", "°C", 1),
    BREATHING("Breathing rate", "/min", 1),
    STEPS("Steps", "", 0, bars = true),
}

/** A value on a day, the day counted from the epoch so gaps are simple arithmetic. */
data class TrendPoint(val day: Long, val value: Double)

/**
 * A metric over the last [days] ending at [endDay], and how it compares with the same
 * number of days before that.
 */
data class TrendWindow(
    val points: List<TrendPoint>,
    val endDay: Long,
    val days: Int,
    val mean: Double?,
    /** Null when the earlier period holds too little to compare against. */
    val previousMean: Double?,
) {
    val startDay: Long get() = endDay - days + 1
    val change: Double? get() = if (mean != null && previousMean != null) mean - previousMean else null
    val low: Double? get() = points.minOfOrNull { it.value }
    val high: Double? get() = points.maxOfOrNull { it.value }
}

/** Fewer points than this is an anecdote, not a period to compare against. */
private const val MIN_PREVIOUS_POINTS = 3

private fun dayOf(ymd: String): Long? = runCatching { LocalDate.parse(ymd).toEpochDay() }.getOrNull()

/** Every day's value for [metric], oldest first. Days without one are simply absent. */
fun trendPoints(summary: Summary, metric: TrendMetric): List<TrendPoint> =
    summary.days.mapNotNull { ymd ->
        val day = dayOf(ymd) ?: return@mapNotNull null
        val night = summary.nightForDay(ymd)
        val value = when (metric) {
            TrendMetric.HRV -> night?.hrvMs
            TrendMetric.RESTING_HR -> night?.rhr
            // Time actually asleep where the night was scored; time in bed is the nearest
            // thing where it was not, and saying nothing would hide the night entirely.
            TrendMetric.ASLEEP -> night?.metrics?.asleepMin?.let { it / 60.0 } ?: night?.inBedH
            TrendMetric.EFFICIENCY -> night?.efficiency
            TrendMetric.SPO2 -> night?.spo2Mean
            TrendMetric.SKIN_TEMP -> night?.skinTemp
            TrendMetric.BREATHING -> night?.breathingRate?.rate
            // A day with a movement profile and no steps is a ring that was not worn, or
            // today before anyone has walked anywhere: neither is a zero-step day.
            TrendMetric.STEPS -> summary.activityDaily[ymd]?.steps
        }
        value?.takeIf { it > 0 }?.let { TrendPoint(day, it) }
    }.sortedBy { it.day }

/** The last day anything was recorded: trends end there, not at today's empty column. */
fun lastDay(summary: Summary): Long? = summary.days.mapNotNull(::dayOf).maxOrNull()

fun trendWindow(all: List<TrendPoint>, endDay: Long, days: Int): TrendWindow {
    val start = endDay - days + 1
    val current = all.filter { it.day in start..endDay }
    val previous = all.filter { it.day in (start - days) until start }
    return TrendWindow(
        points = current,
        endDay = endDay,
        days = days,
        mean = current.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
        previousMean = previous.takeIf { it.size >= MIN_PREVIOUS_POINTS }?.map { it.value }?.average(),
    )
}

/** "47", "7.4", "8,214" — a value in the metric's own precision. */
fun TrendMetric.format(value: Double): String =
    if (decimals == 0) "%,d".format(value.roundToInt()) else "%.${decimals}f".format(value)

/** "2 ms higher than the 30 days before", or null when there is nothing to compare. */
fun TrendMetric.describeChange(w: TrendWindow): String? {
    val change = w.change ?: return null
    val shown = format(abs(change))
    val before = "the ${w.days} days before"
    // Smaller than the precision it is shown in: calling that a change would be noise.
    if ((abs(change) * 10.0.pow(decimals)).roundToInt() == 0) return "Level with $before"
    val amount = when (unit) {
        "" -> shown
        "%" -> "$shown%"
        else -> "$shown $unit"
    }
    return "$amount ${if (change > 0) "higher" else "lower"} than $before"
}

/** The ranges on offer. A week shows last night in context; a quarter shows a season. */
val TREND_RANGES = listOf(7, 30, 90)

@Composable
fun TrendsScreen(
    summary: Summary,
    range: Int,
    onRange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val end = remember(summary) { lastDay(summary) }
    val series = remember(summary) { TrendMetric.entries.associateWith { trendPoints(summary, it) } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { header() }
        item(key = "range") {
            ConnectedChoice(
                options = TREND_RANGES.map { ChoiceOption("$it days") },
                selected = TREND_RANGES.indexOf(range).coerceAtLeast(0),
                onSelect = { onRange(TREND_RANGES[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (end == null) {
            item { Notice("No days to chart yet.") }
        } else {
            items(TrendMetric.entries, key = { it.name }) { metric ->
                TrendCard(metric, trendWindow(series.getValue(metric), end, range))
            }
        }
    }
}

@Composable
private fun TrendCard(metric: TrendMetric, w: TrendWindow) {
    val cs = MaterialTheme.colorScheme
    val colors = chartColors()
    val line = when (metric) {
        TrendMetric.HRV -> colors.hrv
        TrendMetric.RESTING_HR -> colors.hr
        TrendMetric.EFFICIENCY -> colors.deep
        TrendMetric.SPO2 -> colors.spo2
        TrendMetric.SKIN_TEMP -> colors.temp
        TrendMetric.BREATHING -> colors.rem
        TrendMetric.ASLEEP, TrendMetric.STEPS -> colors.accent
    }
    val mean = w.mean
    Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(metric.title, style = MaterialTheme.typography.titleMediumEmphasized)
            if (mean == null) {
                Text(
                    "Nothing recorded in these ${w.days} days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(metric.format(mean), style = MaterialTheme.typography.displaySmallEmphasized)
                Text(
                    listOf(metric.unit, "average").filter { it.isNotEmpty() }.joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            metric.describeChange(w)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            val low = w.low
            val high = w.high
            val spoken = "${metric.title}: average ${metric.format(mean)} ${metric.unit} over ${w.points.size} " +
                "days, from ${low?.let(metric::format)} to ${high?.let(metric::format)}."
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(top = 4.dp)
                    .semantics { contentDescription = spoken },
            ) { drawTrend(w, metric.bars, line, colors.grid) }
            val fmt = remember { DateTimeFormatter.ofPattern("MMM d") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    LocalDate.ofEpochDay(w.startDay).format(fmt),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
                if (low != null && high != null) {
                    Text(
                        "low ${metric.format(low)} · high ${metric.format(high)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Text(
                    LocalDate.ofEpochDay(w.endDay).format(fmt),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The window's points against its own range, with the average as a dashed rule.
 *
 * Each day keeps its place on the x axis whether or not it has a value, so a week off the
 * finger shows as a gap rather than being closed up. The line is broken across gaps of more
 * than a few days for the same reason: joining them would draw a trend nobody measured.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrend(
    w: TrendWindow,
    bars: Boolean,
    line: Color,
    grid: Color,
) {
    val pts = w.points
    if (pts.isEmpty()) return
    val lo = pts.minOf { it.value }
    val hi = pts.maxOf { it.value }
    // Bars measure from zero. A line fills the height with its own range, padded so the
    // extremes do not sit on the edges, and a flat series sits in the middle.
    val (yMin, yMax) = if (bars) {
        0.0 to hi.coerceAtLeast(1.0)
    } else {
        val pad = ((hi - lo) * 0.15).coerceAtLeast(abs(hi) * 0.01).coerceAtLeast(0.5)
        (lo - pad) to (hi + pad)
    }
    val slot = size.width / w.days
    fun x(day: Long) = ((day - w.startDay) + 0.5f) * slot
    fun y(v: Double) = (size.height - ((v - yMin) / (yMax - yMin)) * size.height).toFloat()

    w.mean?.let { m ->
        drawLine(
            grid,
            Offset(0f, y(m)),
            Offset(size.width, y(m)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }

    if (bars) {
        val width = (slot * 0.7f).coerceIn(1.5f, 14.dp.toPx())
        pts.forEach { p ->
            val top = y(p.value)
            drawRoundRect(
                line,
                topLeft = Offset(x(p.day) - width / 2, top),
                size = Size(width, size.height - top),
                cornerRadius = CornerRadius(width / 3),
            )
        }
        return
    }

    val maxGap = (w.days / 10).coerceAtLeast(3)
    val path = Path()
    var previous: TrendPoint? = null
    pts.forEach { p ->
        val last = previous
        if (last == null || p.day - last.day > maxGap) path.moveTo(x(p.day), y(p.value)) else path.lineTo(x(p.day), y(p.value))
        previous = p
    }
    drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    // Dots while there is room for them; beyond a month they would merge into the line.
    val radius = if (w.days <= 31) 3.dp.toPx() else 1.5.dp.toPx()
    pts.forEach { drawCircle(line, radius, Offset(x(it.day), y(it.value))) }
}
