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
