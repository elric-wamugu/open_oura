package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.data.NightRow
import org.openoura.android.data.SleepDebt
import org.openoura.android.data.SleepMetrics
import org.openoura.android.ui.NightWindow
import org.openoura.android.ui.SleepInterpretation
import org.openoura.android.ui.nightWindow

/**
 * The sleep report's words and its time axis — the two parts of that screen that are pure
 * enough to check without a phone, and both easy to get subtly wrong. A night that crosses
 * midnight is the case that breaks naive axis code, and the interpretation's thresholds are
 * the sort of thing that drifts from the web client's wording unnoticed.
 */
class SleepInterpretationTest {

    private fun night(
        efficiency: Double? = null,
        deep: Double? = null,
        rem: Double? = null,
        metrics: SleepMetrics? = null,
    ) = NightRow(
        date = "Thu", start = "23:00", end = "07:00",
        efficiency = efficiency, deepPct = deep, remPct = rem, metrics = metrics,
    )

    @Test
    fun `efficiency is read in three bands`() {
        assertTrue(SleepInterpretation.lines(night(efficiency = 91.0))[0].contains("is solid"))
        assertTrue(SleepInterpretation.lines(night(efficiency = 80.0))[0].contains("is fair"))
        assertTrue(SleepInterpretation.lines(night(efficiency = 60.0))[0].contains("is low"))
    }

    @Test
    fun `the band edges fall where the web client puts them`() {
        assertTrue(SleepInterpretation.lines(night(efficiency = 85.0))[0].contains("solid"))
        assertTrue(SleepInterpretation.lines(night(efficiency = 84.9))[0].contains("fair"))
        assertTrue(SleepInterpretation.lines(night(efficiency = 75.0))[0].contains("fair"))
        assertTrue(SleepInterpretation.lines(night(efficiency = 74.9))[0].contains("low"))
    }

    @Test
    fun `scarce deep sleep says so, adequate deep sleep does not`() {
        assertTrue(SleepInterpretation.lines(night(deep = 6.0))[0].contains("scarce"))
        assertTrue(SleepInterpretation.lines(night(deep = 18.0))[0].contains("target ~13–23%"))
    }

    @Test
    fun `one awakening is not pluralised`() {
        val one = SleepInterpretation.lines(
            night(metrics = SleepMetrics(wasoMin = 12.0, awakenings = 1)),
        )
        assertTrue(one[0].contains("1 awakening after"))
        val many = SleepInterpretation.lines(
            night(metrics = SleepMetrics(wasoMin = 40.0, awakenings = 4)),
        )
        assertTrue(many[0].contains("4 awakenings after"))
    }

    @Test
    fun `missing numbers drop their sentence rather than printing a dash`() {
        assertEquals(emptyList<String>(), SleepInterpretation.lines(night()))
        // REM needs both its own figure and the latency, or the sentence makes no claim.
        assertEquals(emptyList<String>(), SleepInterpretation.lines(night(rem = 20.0)))
    }

    @Test
    fun `sleep debt is only quoted once the brain calls it valid`() {
        assertNull(SleepInterpretation.debtNote(null))
        assertNull(
            SleepInterpretation.debtNote(SleepDebt(debtMin = 300.0, valid = false, needH = 8.0)),
        )
        val note = SleepInterpretation.debtNote(
            SleepDebt(debtMin = 305.0, recentShortfallMin = 42.0, valid = true, needH = 8.0),
        )
        assertEquals("5h 5m", note?.first)
        assertTrue(note!!.second.contains("vs an 8 h nightly need"))
        assertTrue(note.second.contains("last night 42 min short"))
    }

    @Test
    fun `the article matches the number that follows it`() {
        assertEquals("an", SleepInterpretation.article("8"))
        assertEquals("a", SleepInterpretation.article("7.5"))
        assertEquals("a", SleepInterpretation.article("9"))
        assertEquals("a", SleepInterpretation.article("6"))
    }

    @Test
    fun `a night with no shortfall does not claim one`() {
        val note = SleepInterpretation.debtNote(
            SleepDebt(debtMin = 60.0, recentShortfallMin = 0.0, valid = true, needH = 8.0),
        )
        assertEquals("1h 0m", note?.first)
        assertTrue(note!!.second.endsWith("nightly need"))
    }

    @Test
    fun `a night that crosses midnight unwraps instead of inverting`() {
        val win = nightWindow("23:20", "07:05")
        assertEquals(NightWindow(23 * 60 + 20, 7 * 60 + 45), win)
        assertEquals("23:20", win.clockAt(0f))
        assertEquals("07:05", win.clockAt(1f))
        // Halfway is 03:13 — the axis walks *through* midnight rather than back to it,
        // which a plain end-minus-start would have done. Same minute the web client lands
        // on, which is the point of checking it.
        assertEquals("03:13", win.clockAt(0.5f))
    }

    @Test
    fun `a night inside one day is not treated as wrapping`() {
        // This ring's owner sleeps 03:32 to 10:58.
        val win = nightWindow("03:32", "10:58")
        assertEquals(7 * 60 + 26, win.spanMin)
        assertEquals("03:32", win.clockAt(0f))
        assertEquals("10:58", win.clockAt(1f))
    }

    @Test
    fun `a missing or malformed bedtime degrades to a whole day, not a zero-width axis`() {
        // Both ends parse to 00:00, so the unwrap rule makes it midnight-to-midnight. A
        // day-wide axis is wrong but harmless; a zero-width one would divide by zero.
        assertEquals(1440, nightWindow(null, null).spanMin)
        assertEquals(1440, nightWindow("ab:cd", "").spanMin)
    }
}
