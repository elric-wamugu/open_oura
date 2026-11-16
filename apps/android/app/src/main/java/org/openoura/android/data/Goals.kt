package org.openoura.android.data

import android.content.Context
import androidx.core.content.edit

/**
 * Targets the user sets for themselves. So far there is one: a daily step target, which
 * the steps widget's ring fills towards.
 *
 * A preference, not a metric: nothing in `oura-summary` knows about it, and nothing is
 * computed from it beyond "how far round the ring".
 */
object Goals {
    const val DEFAULT_STEPS = 7_000
    const val MIN_STEPS = 500
    const val MAX_STEPS = 100_000

    private const val PREFS = "goals"
    private const val KEY_STEPS = "daily_steps"

    fun stepTarget(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STEPS, DEFAULT_STEPS)

    fun setStepTarget(ctx: Context, steps: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_STEPS, steps) }
        // The steps widget draws its ring against this.
        WidgetBus.changed()
    }

    /** What was typed, as a target; null when it is not a whole number in range. */
    fun parseSteps(input: String): Int? =
        input.filter { it.isDigit() }.takeIf { it.isNotEmpty() && it.length <= 6 }
            ?.toInt()?.takeIf { it in MIN_STEPS..MAX_STEPS }
}
