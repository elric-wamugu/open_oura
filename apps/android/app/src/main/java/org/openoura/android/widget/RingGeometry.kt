package org.openoura.android.widget

import org.openoura.android.data.WidgetSnapshot

/** A sleep stage, in the order the ring draws them: clockwise from twelve o'clock. */
enum class RingStage { DEEP, LIGHT, REM, AWAKE }

/** One segment of the stage ring, in degrees. Zero is three o'clock, as Canvas counts. */
data class RingArc(val stage: RingStage, val startDeg: Float, val sweepDeg: Float)

/**
 * Last night's stages as arcs of one ring, starting at twelve o'clock.
 *
 * Each stage's share of the circle is its share of the night. The gaps between segments
 * come out of the circle before it is shared, so the proportions stay true to each other.
 * Stages that did not happen are left out rather than drawn as slivers, and a night that
 * was not staged at all gives no arcs — the widget then draws a plain ring.
 */
fun stageArcs(s: WidgetSnapshot, gapDeg: Float = 4f): List<RingArc> {
    val parts = listOf(
        RingStage.DEEP to s.deepPct,
        RingStage.LIGHT to s.lightPct,
        RingStage.REM to s.remPct,
        RingStage.AWAKE to s.wakePct,
    ).mapNotNull { (stage, pct) -> pct?.takeIf { it > 0 }?.let { stage to it } }
    val total = parts.sumOf { it.second }
    if (total <= 0) return emptyList()
    // One stage alone is a closed ring: a gap would be a notch with nothing either side.
    val gap = if (parts.size > 1) gapDeg else 0f
    val usable = 360f - gap * parts.size
    var at = -90f + gap / 2
    return parts.map { (stage, pct) ->
        val sweep = usable * pct / total
        RingArc(stage, at, sweep).also { at += sweep + gap }
    }
}

/** The figure in the middle of the ring: hours asleep, or hours in bed for an unscored night. */
fun ringHours(s: WidgetSnapshot): String? = (s.asleepHours ?: s.sleepHours)?.let { "%.1f".format(it) }

/**
 * What sits under that figure: sleep efficiency, shortened when the ring is too small to
 * hold the word. A night with no efficiency says which hours the figure is instead.
 */
fun ringCaption(s: WidgetSnapshot, roomy: Boolean): String = when {
    s.efficiency != null -> if (roomy) "${s.efficiency}% efficiency" else "${s.efficiency}% eff."
    s.asleepHours != null -> "asleep"
    else -> "in bed"
}

fun stepsLabel(steps: Int?): String = steps?.let { "%,d".format(it) } ?: "—"

/** "5.3" for a day's walking; under 100 m is shown as nothing walked yet, not "0.0". */
fun distanceLabel(km: Double?): String = when {
    km == null -> "—"
    km < 0.1 -> "0"
    else -> "%.1f".format(km)
}

/** How far round the ring today's steps are towards [target]: 0 to 1, full once reached. */
fun stepProgress(steps: Int?, target: Int): Float =
    if (steps == null || target <= 0) 0f else (steps.toFloat() / target).coerceIn(0f, 1f)

/** Under the step count: how far there is to go, or that the target is met. */
fun stepsCaption(steps: Int?, target: Int): String? = when {
    steps == null -> null
    target > 0 && steps >= target -> "Goal reached"
    target > 0 -> "of ${"%,d".format(target)}"
    else -> null
}

/** The ring's charge as a warning line, only when it is in one of the brain's low bands. */
fun chargeWarning(pct: Int?, status: String?): String? = when (status) {
    "low", "very_low", "critical" -> pct?.let { "Ring at $it%, charge it" } ?: "Ring charge low"
    else -> null
}

/** "7h 12m" for the hours asleep (or in bed, for an unscored night). */
fun sleepDuration(s: WidgetSnapshot): String? = (s.asleepHours ?: s.sleepHours)?.let {
    val min = Math.round(it * 60).toInt()
    "${min / 60}h ${"%02d".format(min % 60)}m"
}

/** Steps short enough for the middle of a small ring: "300", "4.9k", "12k". */
fun stepsShort(steps: Int?): String = when {
    steps == null -> "—"
    steps < 1000 -> steps.toString()
    steps < 10_000 -> "%.1fk".format(steps / 1000.0)
    else -> "${steps / 1000}k"
}

/** How the score-trio widget arranges itself in the space it was given. */
enum class TrioLayout {
    /** The three rings alone, in a cell too short for anything under them. */
    RINGS_ONLY,
    /** The rings, then time asleep and distance under them. */
    ROWS_BELOW,
    /** Wide and short: the rings, with the two rows to their right. */
    ROWS_BESIDE,
}

// Below first whenever it is tall enough: beside, the rows only get what the rings leave,
// and at 325 dp wide that was too little — "Asleep" broke over two lines (2026-11-21).
fun trioLayout(widthDp: Float, heightDp: Float): TrioLayout = when {
    heightDp >= 170f -> TrioLayout.ROWS_BELOW
    widthDp >= 360f && heightDp >= 90f -> TrioLayout.ROWS_BESIDE
    else -> TrioLayout.RINGS_ONLY
}
