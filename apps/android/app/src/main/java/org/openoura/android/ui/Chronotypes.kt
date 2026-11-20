package org.openoura.android.ui

import org.openoura.android.data.ChronotypeInfo

/** The chronotype choices and their wording. Pure, so tested. */
object Chronotypes {
    /** The Oura app's six, in its order; `oura-summary` reads these names. */
    val NAMES = listOf("early morning", "morning", "late morning", "early evening", "evening", "late evening")

    fun title(name: String): String = name.replaceFirstChar { it.uppercase() }

    /** "3:30", "03:30" or "23:05" as a canonical "HH:MM"; null for anything else. */
    fun parseMidpoint(input: String): String? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(input) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return "%02d:%02d".format(h, min)
    }

    /**
     * The You tab row's line. A worked-out type is marked "estimated": Oura does not publish
     * its type boundaries, so the name comes from one-hour bands of sleep midpoint
     * (`chronotype_from_midpoint` in `oura-summary`).
     */
    /** The tile's name for it: "Evening", or "Not yet". */
    fun shortName(info: ChronotypeInfo?): String = when (info?.source) {
        "profile", "history" -> info.type?.let(::title) ?: "Worked out"
        else -> "Not yet"
    }

    /** Under the name: "estimated · 05:08", "entered · 03:30", or how many nights it needs. */
    fun shortDetail(info: ChronotypeInfo?): String = when (info?.source) {
        "profile" -> "entered · ${info.idealMidpoint}"
        "history" -> "estimated · ${info.idealMidpoint}"
        "none" -> "needs ${info.needs} nights"
        else -> "tap to set"
    }

    fun describe(info: ChronotypeInfo?): String = when (info?.source) {
        "profile" -> "${info.type?.let(::title) ?: "Entered"}, midpoint ${info.idealMidpoint}. Entered by you"
        "history" -> "${info.type?.let(::title)?.plus(" (estimated)") ?: "Worked out"}, midpoint " +
            "${info.idealMidpoint}, from your last ${info.nights} nights"
        "none" -> "Needs ${info.needs} nights to work out (${info.nights} so far)"
        else -> "Not worked out yet"
    }

    /** The sleep page's line about timing. */
    fun timingNote(info: ChronotypeInfo?): String = when (info?.source) {
        "profile" -> "Timing is measured against your chronotype (${info.type?.let(::title)}, midpoint ${info.idealMidpoint})."
        "history" -> "Timing is measured against your usual sleep midpoint, ${info.idealMidpoint}, from your last " +
            "${info.nights} nights${info.type?.let { " (an estimated $it type)" } ?: ""}. " +
            "Set your chronotype on the You tab to use the Oura app's."
        else -> "Timing is measured against a fixed curve until there are enough nights to work out your usual " +
            "sleep midpoint."
    }
}
