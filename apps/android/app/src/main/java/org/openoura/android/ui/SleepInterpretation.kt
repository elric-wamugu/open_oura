package org.openoura.android.ui

import org.openoura.android.data.NightRow
import org.openoura.android.data.SleepDebt
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
