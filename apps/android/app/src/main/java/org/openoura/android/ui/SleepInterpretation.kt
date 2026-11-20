package org.openoura.android.ui

import org.openoura.android.data.NightRow
import org.openoura.android.data.SleepDebt
import org.openoura.android.data.SleepScoreContributors
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The night in plain sentences — the same read the web client prints under its charts.
 *
 * Deliberately not a score. Every line names the number it is reading and what that number
 * is for, so the reader can disagree with it; a single "sleep score" would hide exactly the
 * reasoning that makes the rest of this report worth having.
 *
 * Pure so it can be tested: the wording is the feature, and wording is easy to break.
 */
object SleepInterpretation {

    fun lines(night: NightRow): List<String> {
        val m = night.metrics
        val out = mutableListOf<String>()

        night.efficiency?.let { eff ->
            val e = eff.roundToInt()
            out += when {
                eff >= 85 -> "Sleep efficiency of $e% is solid — little time awake once down."
                eff >= 75 -> "Efficiency $e% is fair; some fragmentation kept you from deeper rest."
                else -> "Efficiency $e% is low — a lot of the night in bed wasn't spent asleep."
            }
        }

        night.deepPct?.let { deep ->
            val d = deep.roundToInt()
            out += if (deep < 10) {
                "Deep sleep was scarce ($d%) — the physically-restorative stage; low deep " +
                    "often follows late meals, alcohol, or stress."
            } else {
                "Deep sleep $d% (target ~13–23%), the physically-restorative stage."
            }
        }

        val rem = night.remPct
        val remLatency = m?.remLatencyMin
        if (rem != null && remLatency != null) {
            out += "REM was ${rem.roundToInt()}% with first REM ${remLatency.roundToInt()} min " +
                "after onset (a short REM latency can signal REM pressure or sleep debt)."
        }

        // "4 cycles" is the one figure on this page that means nothing without being told
        // what a cycle is, and the number alone invites reading more into it than it holds.
        m?.cycles?.let { cycles ->
            out += "You went through $cycles sleep ${if (cycles == 1) "cycle" else "cycles"} — " +
                "one cycle is a pass from light sleep down into deep and back up into REM, " +
                "around 90 minutes. Four to six is typical, and the earliest ones carry most " +
                "of the deep sleep."
        }

        val waso = m?.wasoMin
        val awakenings = m?.awakenings
        if (waso != null && awakenings != null) {
            val plural = if (awakenings == 1) "" else "s"
            out += "You spent ${waso.roundToInt()} min awake across $awakenings awakening$plural " +
                "after first falling asleep."
        }

        return out
    }

    /**
     * "an 8 h need", but "a 7.5 h need".
     *
     * The web client hardcodes "an", which reads correctly only because the nightly need
     * is usually eight hours. Spoken aloud, every plausible value but eight starts with a
     * consonant.
     */
    internal fun article(need: String) = if (need.startsWith("8")) "an" else "a"

    /**
     * The word that goes with a Sleep Score, on the app's bands: 85 and up optimal, 70 to
     * 84 good, 60 to 69 fair, below that pay attention.
     */
    fun scoreLabel(score: Int): String = when {
        score >= 85 -> "Optimal"
        score >= 70 -> "Good"
        score >= 60 -> "Fair"
        else -> "Pay attention"
    }

    /** The contributors in the app's order, with their names; ones not given are left out. */
    fun scoreContributors(c: SleepScoreContributors): List<Pair<String, Int>> = listOfNotNull(
        c.totalSleep?.let { "Total sleep" to it },
        c.efficiency?.let { "Efficiency" to it },
        c.restfulness?.let { "Restfulness" to it },
        c.rem?.let { "REM sleep" to it },
        c.deep?.let { "Deep sleep" to it },
        c.latency?.let { "Latency" to it },
        c.timing?.let { "Timing" to it },
    )

    /**
     * Where a night's stages came from, in a line, or null when there are none to explain.
     * Two sources can disagree on the same night — they agree on about two thirds of its
     * 30-second epochs — so the page says which one it is showing.
     */
    fun stageSourceNote(source: String?): String? = when (source) {
        "stager" -> "Stages worked out on this phone from your heart beats, movement and skin temperature."
        "ring" -> "Stages as the ring recorded them. This phone works out its own as it catches up on past nights."
        "model" -> "Stages from the sleep model."
        else -> null
    }

    /**
     * Said under the headline figures of a night the ring has not declared yet, so its times
     * are not taken as the ring's: they move, sometimes by most of an hour, when it does.
     */
    fun detectedNote(detected: Boolean): String? = if (detected) {
        "The ring hasn't confirmed this night yet. Its start and end were worked out on this " +
            "phone from your movement and skin temperature, and the ring's own times replace " +
            "them after a later sync."
    } else {
        null
    }

    /**
     * The sleep-debt footnote, or null when there is not enough history to mean anything.
     *
     * `valid` is the brain's own judgement on that and is not second-guessed here.
     */
    fun debtNote(debt: SleepDebt?): Pair<String, String>? {
        if (debt == null || !debt.valid) return null
        val minutes = debt.debtMin ?: return null
        val h = floor(minutes / 60).roundToInt()
        val m = (minutes % 60).roundToInt()
        val need = debt.needH?.let { "%.1f".format(it).removeSuffix(".0") } ?: "—"
        val shortfall = debt.recentShortfallMin ?: 0.0
        val tail = if (shortfall > 0) " · last night ${shortfall.roundToInt()} min short" else ""
        return "${h}h ${m}m" to
            "accumulated sleep debt vs ${article(need)} $need h nightly need$tail"
    }
}
