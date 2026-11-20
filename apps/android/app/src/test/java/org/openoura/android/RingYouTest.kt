package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.data.Battery
import org.openoura.android.data.BatteryCycle
import org.openoura.android.data.BatteryPoint
import org.openoura.android.data.ChronotypeInfo
import org.openoura.android.data.RingProfile
import org.openoura.android.ui.Chronotypes
import org.openoura.android.ui.RingText
import org.openoura.android.ui.profileLine

/** The words on the Ring and You bentos. */
class RingYouTest {
    private fun cycle(fullH: Double) = BatteryCycle(
        start = 0.0, end = 1.0, fromPct = 100, toPct = 50, hours = fullH / 2, pctPerHour = 100 / fullH, projectedFullH = fullH,
    )

    @Test
    fun `a charge is read off the newest runs, and what is left from the level`() {
        val b = Battery(cycles = listOf(cycle(60.0), cycle(110.0), cycle(120.0), cycle(130.0)))
        assertEquals(120.0, RingText.hoursPerCharge(b)!!, 1e-9)
        assertEquals("≈ 5 days per charge", RingText.perCharge(120.0))
        assertEquals("≈ 30 h per charge", RingText.perCharge(30.0))
        assertEquals("≈ 4 days left", RingText.timeLeft(83, 120.0))
        assertEquals("≈ 12 h left", RingText.timeLeft(10, 120.0))
        assertNull(RingText.timeLeft(83, null))
        assertNull(RingText.hoursPerCharge(Battery()))
    }

    @Test
    fun `the charge tile keeps the last week of the log`() {
        val day = 86_400.0
        val series = (0..20).map { BatteryPoint(it * day, 90 - it, 4000) }
        assertEquals((13..20).toList(), RingText.lastDays(series, 7).map { (it.t / day).toInt() })
        assertEquals(emptyList<BatteryPoint>(), RingText.lastDays(emptyList(), 7))
    }

    @Test
    fun `the profile reads as one line`() {
        assertEquals("34 · male · 72 kg · 1.76 m", profileLine(RingProfile(sex = "M", age = 34.0, height_m = 1.76, weight_kg = 72.4)))
        assertEquals("29 · female · 60 kg · 1.65 m", profileLine(RingProfile(sex = "f", age = 29.0, height_m = 1.65, weight_kg = 60.0)))
    }

    @Test
    fun `the chronotype tile names it and says where it came from`() {
        val estimated = ChronotypeInfo(source = "history", type = "evening", idealMidpoint = "05:08", nights = 90)
        assertEquals("Evening", Chronotypes.shortName(estimated))
        assertEquals("estimated · 05:08", Chronotypes.shortDetail(estimated))
        val none = ChronotypeInfo(source = "none", nights = 3, needs = 14)
        assertEquals("Not yet", Chronotypes.shortName(none))
        assertEquals("needs 14 nights", Chronotypes.shortDetail(none))
    }
}
