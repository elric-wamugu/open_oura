package org.openoura.android.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * Four tabs, each with its own back stack, shown as one stack: Today's, with the current
 * tab's on top when that isn't Today.
 *
 * That shape is what makes Back read naturally. Inside a tab it pops; at another tab's
 * root it pops *to Today*, with the same animation as any other pop because that is
 * exactly what happens to the visible stack; at Today's root there is nothing left to pop,
 * so the system takes it and the app goes home. Opening a tab never loses where you were
 * in the others.
 */
@Stable
class Navigator(
    private val stacks: Map<Tab, MutableList<NavKey>>,
    tabState: MutableState<Tab>,
) {
    var tab: Tab by tabState
        private set

    fun stack(t: Tab): List<NavKey> = stacks.getValue(t)

    /** What is on screen, bottom to top. */
    val visible: List<NavKey>
        get() = if (tab == Tab.TODAY) stack(Tab.TODAY) else stack(Tab.TODAY) + stack(tab)

    /** The current tab is showing its root screen, not something pushed on top of it. */
    val atTabRoot: Boolean
        get() = stack(tab).size == 1

    /** Selecting the tab you are on returns it to its root, as the platform's bars do. */
    fun select(t: Tab) {
        if (t == tab) popToRoot(t) else tab = t
    }

    /** Open [key] on top of the current tab. */
    fun push(key: NavKey) {
        val s = stacks.getValue(tab)
        if (s.lastOrNull() != key) s.add(key)
    }

    fun back() {
        val s = stacks.getValue(tab)
        when {
            s.size > 1 -> s.removeAt(s.lastIndex)
            tab != Tab.TODAY -> tab = Tab.TODAY
            // Today's root: NavDisplay never asks, because there is nothing to pop.
        }
    }

    private fun popToRoot(t: Tab) {
        val s = stacks.getValue(t)
        while (s.size > 1) s.removeAt(s.lastIndex)
    }
}

@Composable
fun rememberNavigator(): Navigator {
    val today = rememberNavBackStack(Tab.TODAY.root)
    val history = rememberNavBackStack(Tab.HISTORY.root)
    val ring = rememberNavBackStack(Tab.RING.root)
    val you = rememberNavBackStack(Tab.YOU.root)
    val tabState = rememberSaveable { mutableStateOf(Tab.TODAY) }
    return remember(today, history, ring, you, tabState) {
        Navigator(
            mapOf(Tab.TODAY to today, Tab.HISTORY to history, Tab.RING to ring, Tab.YOU to you),
            tabState,
        )
    }
}
