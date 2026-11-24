package org.openoura.android.ui

import androidx.annotation.DrawableRes
import org.openoura.android.R
import org.openoura.android.data.UserLabel

/**
 * The activities a person can tag a ring-detected effort session with.
 *
 * These are the person's own words for a session, never a guess: the ring only knows it
 * was recording effort, and naming that is what Oura's activity model would do. `key` is
 * what is stored in `oura.db` (`effort_labels.kind`), so it never changes once shipped; the
 * core accepts any short name, so a kind added here later needs no migration, and one this
 * build doesn't know still shows, as its key.
 */
object ActivityLabels {
    data class Kind(val key: String, val name: String, @param:DrawableRes val icon: Int)

    const val OTHER = "other"

    val kinds = listOf(
        Kind("dance", "Dance", R.drawable.ic_sym_taunt),
        Kind("walk", "Walk", R.drawable.ic_sym_directions_walk),
        Kind("run", "Run", R.drawable.ic_sym_directions_run),
        Kind("cycling", "Cycling", R.drawable.ic_sym_directions_bike),
        Kind("strength", "Strength", R.drawable.ic_sym_fitness_center),
        Kind(OTHER, "Other", R.drawable.ic_sym_more_horiz),
    )

    fun kind(key: String): Kind? = kinds.firstOrNull { it.key == key }

    /**
     * What the session is called. "Other" with a note is called by the note — "Bouldering"
     * says more than "Other" — and a kind this build doesn't know by its own key.
     */
    fun title(label: UserLabel): String {
        val note = label.note?.trim().orEmpty()
        if (label.kind == OTHER && note.isNotEmpty()) return note
        return kind(label.kind)?.name ?: label.kind.replaceFirstChar { it.uppercase() }
    }

    /** The note, when the title isn't already it. */
    fun note(label: UserLabel): String? =
        label.note?.trim()?.takeIf { it.isNotEmpty() && it != title(label) }

    @DrawableRes
    fun icon(label: UserLabel): Int = kind(label.kind)?.icon ?: R.drawable.ic_sym_sell
}
