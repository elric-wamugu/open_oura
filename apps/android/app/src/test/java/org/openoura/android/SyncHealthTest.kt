package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.sync.StaleLevel
import org.openoura.android.sync.SyncHealth

private const val HOUR = 3600L
private const val DAY = 24 * HOUR

/**
 * When a ring that has not synced is worth a notification.
 *
 * The ring keeps about eight and a half days. Nothing used to say anything while that ran
 * out: every failed sync was logged and forgotten, three hours apart.
 */
class SyncHealthTest {

    @Test
    fun `a missed day is not news, two are, six are urgent`() {
        assertEquals(StaleLevel.FRESH, SyncHealth.levelOf(DAY + 23 * HOUR, failStreak = 0))
        assertEquals(StaleLevel.STALE, SyncHealth.levelOf(2 * DAY, failStreak = 0))
        assertEquals(StaleLevel.STALE, SyncHealth.levelOf(6 * DAY - 1, failStreak = 0))
        assertEquals(StaleLevel.URGENT, SyncHealth.levelOf(6 * DAY, failStreak = 0))
    }

    @Test
    fun `tries that keep failing are flagged a day sooner`() {
        // Four in a row at three-hour spacing is a working day of failures; a ring left at
        // home fails just as often, so it still has to have been a full day.
        assertEquals(StaleLevel.STALE, SyncHealth.levelOf(DAY, failStreak = 4))
        assertEquals(StaleLevel.FRESH, SyncHealth.levelOf(DAY, failStreak = 3))
        assertEquals(StaleLevel.FRESH, SyncHealth.levelOf(DAY - 1, failStreak = 9))
    }

    @Test
    fun `each stage is said once on the way down`() {
        val stale = SyncHealth.evaluate(StaleLevel.STALE, StaleLevel.FRESH, sinceNoticeSec = null)
        assertEquals(StaleLevel.STALE, stale.notify)
        // Still two-to-six days out, three hours later: already said.
        val again = SyncHealth.evaluate(StaleLevel.STALE, StaleLevel.STALE, sinceNoticeSec = 3 * HOUR)
        assertNull(again.notify)
        assertEquals(StaleLevel.STALE, again.state)
        val urgent = SyncHealth.evaluate(StaleLevel.URGENT, StaleLevel.STALE, sinceNoticeSec = 3 * HOUR)
        assertEquals(StaleLevel.URGENT, urgent.notify)
    }

    @Test
    fun `the urgent stage repeats daily, not on every check`() {
        assertNull(SyncHealth.evaluate(StaleLevel.URGENT, StaleLevel.URGENT, sinceNoticeSec = 23 * HOUR).notify)
        assertEquals(
            StaleLevel.URGENT,
            SyncHealth.evaluate(StaleLevel.URGENT, StaleLevel.URGENT, sinceNoticeSec = DAY).notify,
        )
    }

    @Test
    fun `nothing here un-warns, only a complete sync does`() {
        // The level cannot really fall without a sync, but if it did the stage must hold.
        val held = SyncHealth.evaluate(StaleLevel.FRESH, StaleLevel.STALE, sinceNoticeSec = HOUR)
        assertNull(held.notify)
        assertEquals(StaleLevel.STALE, held.state)
    }

    @Test
    fun `the first warning says how long and what to do`() {
        val (title, text) = SyncHealth.describe(StaleLevel.STALE, 2 * DAY + 5 * HOUR, lastError = null)
        assertEquals("Ring not synced for 2 days", title)
        assertTrue(text, text.contains("Open the app with the ring nearby"))
    }

    @Test
    fun `failing tries are named as such, with the reason`() {
        val (title, text) = SyncHealth.describe(StaleLevel.STALE, DAY + 2 * HOUR, lastError = "Ring not found")
        assertEquals("Ring sync keeps failing", title)
        assertTrue(text, text.endsWith("Last try: Ring not found"))
    }

    @Test
    fun `the urgent warning counts down to the ring's limit`() {
        val (title, text) = SyncHealth.describe(StaleLevel.URGENT, 6 * DAY, lastError = null)
        assertEquals("Ring data at risk — 6 days unsynced", title)
        assertTrue(text, text.startsWith("About 2 days until"))
        // Seen on the phone as "About 1 days until": the last full day is singular.
        val (_, oneDay) = SyncHealth.describe(StaleLevel.URGENT, 7 * DAY + 3 * HOUR, lastError = null)
        assertTrue(oneDay, oneDay.startsWith("About 1 day until"))
        val (_, lastDay) = SyncHealth.describe(StaleLevel.URGENT, 8 * DAY, lastError = null)
        assertTrue(lastDay, lastDay.startsWith("About 12 hours until"))
        val (_, past) = SyncHealth.describe(StaleLevel.URGENT, 9 * DAY, lastError = null)
        assertTrue(past, past.startsWith("The ring has probably started overwriting"))
    }
}
