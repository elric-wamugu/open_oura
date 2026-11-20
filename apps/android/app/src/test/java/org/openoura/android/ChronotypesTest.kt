package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.data.ChronotypeInfo
import org.openoura.android.ui.Chronotypes

class ChronotypesTest {

    @Test
    fun `a midpoint is a 24-hour clock time`() {
        assertEquals("03:30", Chronotypes.parseMidpoint("3:30"))
        assertEquals("23:05", Chronotypes.parseMidpoint(" 23:05 "))
        assertNull(Chronotypes.parseMidpoint("24:00"))
        assertNull(Chronotypes.parseMidpoint("3:7"))
        assertNull(Chronotypes.parseMidpoint("half three"))
    }

    @Test
    fun `the row and the sleep page say where the chronotype came from`() {
        val entered = ChronotypeInfo(source = "profile", type = "late morning", idealMidpoint = "03:30")
        assertEquals("Late morning, midpoint 03:30. Entered by you", Chronotypes.describe(entered))
        val worked = ChronotypeInfo(source = "history", type = "late morning", idealMidpoint = "03:42", nights = 60)
        assertEquals("Late morning (estimated), midpoint 03:42, from your last 60 nights", Chronotypes.describe(worked))
        assertEquals(
            "Timing is measured against your usual sleep midpoint, 03:42, from your last 60 nights " +
                "(an estimated late morning type). Set your chronotype on the You tab to use the Oura app's.",
            Chronotypes.timingNote(worked),
        )
        assertEquals("Needs 14 nights to work out (9 so far)", Chronotypes.describe(ChronotypeInfo(source = "none", nights = 9, needs = 14)))
        assertEquals(
            "Timing is measured against your chronotype (Late morning, midpoint 03:30).",
            Chronotypes.timingNote(entered),
        )
    }
}
