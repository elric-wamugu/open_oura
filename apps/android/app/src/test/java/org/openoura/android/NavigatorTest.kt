package org.openoura.android

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.ui.nav.DayReportRoute
import org.openoura.android.ui.nav.HistoryRoute
import org.openoura.android.ui.nav.Navigator
import org.openoura.android.ui.nav.RingKeyRoute
import org.openoura.android.ui.nav.RingRoute
import org.openoura.android.ui.nav.Tab
import org.openoura.android.ui.nav.TodayRoute
import org.openoura.android.ui.nav.YouRoute

class NavigatorTest {

    private fun navigator() = Navigator(
        Tab.entries.associateWith { mutableListOf<NavKey>(it.root) },
        mutableStateOf(Tab.TODAY),
    )

    private val day = DayReportRoute("2026-10-11", sleep = true, origin = Tab.HISTORY)

    @Test
    fun `starts on Today alone`() {
        val n = navigator()
        assertEquals(listOf(TodayRoute), n.visible)
        assertTrue(n.atTabRoot)
    }

    @Test
    fun `another tab sits on top of Today`() {
        val n = navigator()
        n.select(Tab.HISTORY)
        assertEquals(listOf(TodayRoute, HistoryRoute), n.visible)
    }

    @Test
    fun `back pops within a tab, then returns to Today, then stops`() {
        val n = navigator()
        n.select(Tab.HISTORY)
        n.push(day)
        assertEquals(listOf(TodayRoute, HistoryRoute, day), n.visible)
        assertFalse(n.atTabRoot)

        n.back()
        assertEquals(listOf(TodayRoute, HistoryRoute), n.visible)
        n.back()
        assertEquals(Tab.TODAY, n.tab)
        assertEquals(listOf(TodayRoute), n.visible)
        // Today's root: nothing to pop. The system handles this one and leaves the app.
        n.back()
        assertEquals(listOf(TodayRoute), n.visible)
    }

    @Test
    fun `switching tabs keeps each tab's place`() {
        val n = navigator()
        n.select(Tab.YOU)
        n.push(RingKeyRoute)
        n.select(Tab.RING)
        assertEquals(listOf(TodayRoute, RingRoute), n.visible)
        n.select(Tab.YOU)
        assertEquals(listOf(TodayRoute, YouRoute, RingKeyRoute), n.visible)
    }

    @Test
    fun `reselecting the current tab returns it to its root`() {
        val n = navigator()
        n.select(Tab.HISTORY)
        n.push(day)
        n.select(Tab.HISTORY)
        assertEquals(listOf(TodayRoute, HistoryRoute), n.visible)
        assertTrue(n.atTabRoot)
    }

    @Test
    fun `pushing the screen already on top does not stack a duplicate`() {
        val n = navigator()
        n.select(Tab.HISTORY)
        n.push(day)
        n.push(day)
        assertEquals(listOf(HistoryRoute, day), n.stack(Tab.HISTORY))
    }
}
