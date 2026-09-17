package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.sync.AutoSync
import org.openoura.android.sync.SleepWindow
import org.openoura.android.sync.SyncDecision
import org.openoura.android.sync.SyncTrigger

private const val HOUR = 3600L

/**
 * Auto-sync decides when to spend the radio, and every one of its inputs — the hour, the
 * gap since the last attempt, months of nightly times — is awkward to stage on a real
 * phone. So the policy is a pure function and the awkward parts are checked here instead.
 */
class AutoSyncTest {

    private fun decide(
        trigger: SyncTrigger = SyncTrigger.PERIODIC,
        sinceLastSec: Long = 4 * HOUR,
        minuteOfDay: Int = 12 * 60,
        window: SleepWindow? = null,
    ) = AutoSync.decide(
        trigger = trigger,
        nowEpochSec = 1_000_000,
        lastAttemptEpochSec = 1_000_000 - sinceLastSec,
        minuteOfDay = minuteOfDay,
        window = window,
    )

    @Test
    fun `the three-hour floor holds for both triggers`() {
        assertEquals(SyncDecision.TOO_SOON, decide(sinceLastSec = 2 * HOUR))
        assertEquals(
            SyncDecision.TOO_SOON,
            decide(trigger = SyncTrigger.APP_OPEN, sinceLastSec = 2 * HOUR),
        )
        assertEquals(SyncDecision.SYNC, decide(sinceLastSec = 3 * HOUR))
    }

    @Test
    fun `a first run has no last attempt to wait on`() {
        assertEquals(
            SyncDecision.SYNC,
            AutoSync.decide(SyncTrigger.PERIODIC, 1_000_000, 0, 12 * 60, null),
        )
    }

    @Test
    fun `a clock moved backwards does not wedge the scheduler`() {
        // Last attempt "in the future". Treating that as too soon would block auto-sync
        // until real time caught up, which for a large clock change could be days.
        assertEquals(SyncDecision.SYNC, decide(sinceLastSec = -5 * HOUR))
    }

    @Test
    fun `quiet hours suppress the periodic trigger but not an app open`() {
        val night = SleepWindow(startMin = 23 * 60, endMin = 7 * 60)
        assertEquals(SyncDecision.ASLEEP, decide(minuteOfDay = 3 * 60, window = night))
        assertEquals(
            SyncDecision.SYNC,
            decide(trigger = SyncTrigger.APP_OPEN, minuteOfDay = 3 * 60, window = night),
        )
    }

    @Test
    fun `a window that wraps midnight covers both sides of it`() {
        val night = SleepWindow(startMin = 23 * 60, endMin = 7 * 60)
        assertTrue(night.contains(23 * 60 + 30))
        assertTrue(night.contains(0))
        assertTrue(night.contains(6 * 60 + 59))
        assertFalse(night.contains(7 * 60))
        assertFalse(night.contains(22 * 60 + 59))
    }

    @Test
    fun `a window inside one day does not wrap`() {
        // This ring's owner sleeps 03:32 to 10:58 — a window that never crosses midnight,
        // which the same code has to handle without treating it as inverted.
        val late = SleepWindow(startMin = 3 * 60 + 32, endMin = 10 * 60 + 58)
        assertTrue(late.contains(5 * 60))
        assertFalse(late.contains(2 * 60))
        assertFalse(late.contains(20 * 60))
    }

    @Test
    fun `the sleep window is the median of the nights`() {
        val nights = listOf(
            "23:00" to "07:00",
            "23:30" to "07:30",
            "22:45" to "06:45",
            "23:15" to "07:15",
            "23:10" to "07:10",
            "22:50" to "06:50",
            "23:20" to "07:20",
        )
        val window = AutoSync.deriveSleepWindow(nights)
        assertEquals(SleepWindow(23 * 60 + 10, 7 * 60 + 10), window)
    }

    @Test
    fun `bedtimes either side of midnight average to the right hour`() {
        // The reason medians are taken around noon: as raw minutes these are 1420, 10 and
        // 1435, whose median is 23:40 — an hour off the 00:10 a person would name.
        val nights = List(3) { "23:40" to "07:00" } +
            List(4) { "00:10" to "07:30" }
        val window = AutoSync.deriveSleepWindow(nights)
        assertEquals(10, window?.startMin)
    }

    @Test
    fun `too few nights means no quiet hours at all`() {
        val nights = List(6) { "23:00" to "07:00" }
        assertNull(AutoSync.deriveSleepWindow(nights))
        assertEquals(SyncDecision.SYNC, decide(minuteOfDay = 3 * 60, window = null))
    }

    @Test
    fun `unparseable times are not counted towards the night total`() {
        val nights = List(6) { "23:00" to "07:00" } + listOf("" to "07:00")
        assertNull(AutoSync.deriveSleepWindow(nights))
    }

    @Test
    fun `clock strings parse, or cleanly do not`() {
        assertEquals(0, AutoSync.hmToMinutes("00:00"))
        assertEquals(1439, AutoSync.hmToMinutes("23:59"))
        assertNull(AutoSync.hmToMinutes("24:00"))
        assertNull(AutoSync.hmToMinutes("7:60"))
        assertNull(AutoSync.hmToMinutes("07"))
        assertNull(AutoSync.hmToMinutes("ab:cd"))
    }
}
