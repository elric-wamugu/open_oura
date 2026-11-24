package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.data.EffortSession
import org.openoura.android.data.GaitSegment
import org.openoura.android.ui.GaitText

class GaitTextTest {
    private fun seg(label: String, min: Double) = GaitSegment(label, 0.0, min * 60, durationMin = min)

    @Test
    fun `a session with measured gait is named and broken down`() {
        val e = EffortSession(
            start = 0.0, end = 1080.0, durationMin = 18.0, label = "jogging",
            gait = listOf(seg("walking", 5.0), seg("jogging", 10.0), seg("walking", 4.0)),
        )
        assertEquals("Jogging at 21:31", GaitText.title(e, "21:31"))
        assertEquals("Jogging 10 min · Walking 9 min", GaitText.breakdown(e))
        assertEquals(R.drawable.ic_cap_run, GaitText.icon(e.label))
    }

    @Test
    fun `a session without gait stays effort`() {
        val e = EffortSession(start = 0.0, end = 900.0, durationMin = 15.0)
        assertEquals("Effort at 08:00", GaitText.title(e, "08:00"))
        assertNull(GaitText.breakdown(e))
        assertEquals(R.drawable.ic_sym_monitor_heart, GaitText.icon(null))
    }

    @Test
    fun `an unknown label is not shown as a name`() {
        val e = EffortSession(start = 0.0, end = 900.0, label = "running", gait = listOf(seg("running", 12.0)))
        assertEquals("Effort at 07:15", GaitText.title(e, "07:15"))
        assertNull(GaitText.breakdown(e))
    }
}
