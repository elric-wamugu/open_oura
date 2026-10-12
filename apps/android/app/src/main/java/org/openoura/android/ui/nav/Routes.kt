package org.openoura.android.ui.nav

import androidx.annotation.DrawableRes
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.openoura.android.R

// Every place the app can be. Serializable so each tab's back stack survives the Activity
// being recreated (a dark-mode flip, a rotation) and process death.

@Serializable data object TodayRoute : NavKey

@Serializable data object HistoryRoute : NavKey

@Serializable data object RingRoute : NavKey

@Serializable data object YouRoute : NavKey

/**
 * One day's full report, opened on its sleep or its activity half. [origin] is the tab it
 * was opened from: the same day can be open on Today and on History at once, and the two
 * must stay distinct entries.
 */
@Serializable data class DayReportRoute(val ymd: String, val sleep: Boolean, val origin: Tab) : NavKey

@Serializable data object RingKeyRoute : NavKey

@Serializable data object HealthConnectRoute : NavKey

/** The bottom-bar destinations, each the root of its own back stack. */
enum class Tab(
    val root: NavKey,
    val label: String,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    TODAY(TodayRoute, "Today", R.drawable.ic_sym_today, R.drawable.ic_sym_today_fill),
    HISTORY(HistoryRoute, "History", R.drawable.ic_sym_calendar_month, R.drawable.ic_sym_calendar_month_fill),
    RING(RingRoute, "Ring", R.drawable.ic_ring_mark, R.drawable.ic_ring_mark_fill),
    YOU(YouRoute, "You", R.drawable.ic_sym_person, R.drawable.ic_sym_person_fill),
}
