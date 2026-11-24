package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.data.NightRow
import org.openoura.android.data.SleepMetrics
import org.openoura.android.data.Summary
import org.openoura.android.ui.NapText

/** Which periods are a day's naps, and the words they are shown with. */
class NapsTest {

    private val night = NightRow(ymd = "2026-11-22", start = "23:48", end = "06:52", inBedH = 7.1)
    private val nap = NightRow(
        ymd = "2026-11-23", start = "14:05", end = "15:50", inBedH = 1.8, main = false,
        startUnix = 1_763_906_700, endUnix = 1_763_913_000,
        metrics = SleepMetrics(asleepMin = 69.0),
    )
    private val evening = NightRow(ymd = "2026-11-23", start = "18:10", end = "19:45", inBedH = 1.6, main = false)
    private val nextNight = NightRow(ymd = "2026-11-23", start = "23:30", end = "07:00", inBedH = 7.5)
    private val summary = Summary(nights = listOf(nextNight, evening, nap, night))

    @Test
    fun `a day's naps are its other periods, earliest first, never the night`() {
        assertSame(night, summary.nightForDay("2026-11-23"))
        assertEquals(listOf(nap, evening), summary.napsForDay("2026-11-23"))
        // The night that starts on the 23rd is woken from on the 24th, so it is not a nap.
        assertTrue(summary.napsForDay("2026-11-24").isEmpty())
        assertTrue(summary.napsForDay("2026-11-20").isEmpty())
    }

    @Test
    fun `naps agree with the night pick even when the summary has no main flag`() {
        // A summary from before `main`: every row says true, and the longest still wins.
        val old = Summary(nights = listOf(night, nap.copy(main = true)))
        assertEquals(listOf(nap.copy(main = true)), old.napsForDay("2026-11-23"))
    }

    @Test
    fun `a nap is found again by its id`() {
        assertSame(nap, summary.nightById(nap.id))
        assertNull(summary.nightById("gone"))
    }

    @Test
    fun `a nap reads its asleep time against its exact time in bed`() {
        assertEquals("Nap · 14:05–15:50", NapText.title(nap))
        // 1h 45m from the instants, not the 1h 48m that the rounded 1.8 h would give.
        assertEquals("1h 09m asleep of 1h 45m in bed", NapText.line(nap))
        assertEquals("1h 09m asleep · not in your score", NapText.tileLine(nap))
    }

    @Test
    fun `a nap without stages says so rather than guessing`() {
        assertEquals("1h 36m in bed · not staged", NapText.line(evening))
        assertEquals("1.6 h in bed · not in your score", NapText.tileLine(evening))
    }
}
