package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.ui.StatusKind
import org.openoura.android.ui.StatusLevel
import org.openoura.android.ui.TileStatus
import org.openoura.android.ui.statusFor

/** Labels and kinds must match app.js's statusFor; the level is the pill's colour. */
class StatusForTest {

    @Test
    fun `within five percent is normal either way`() {
        val normal = TileStatus("Normal", StatusKind.Ok, StatusLevel.Normal)
        assertEquals(normal, statusFor(4.9, goodIsUp = true))
        assertEquals(normal, statusFor(-5.0, goodIsUp = false))
    }

    @Test
    fun `HRV - up is good, down is bad`() {
        assertEquals(TileStatus("High", StatusKind.Ok, StatusLevel.High), statusFor(9.0, goodIsUp = true))
        assertEquals(TileStatus("Low", StatusKind.Warn, StatusLevel.Low), statusFor(-9.0, goodIsUp = true))
    }

    @Test
    fun `resting HR - down is good, up is bad`() {
        assertEquals(TileStatus("Low", StatusKind.Ok, StatusLevel.Low), statusFor(-11.0, goodIsUp = false))
        assertEquals(TileStatus("Elevated", StatusKind.Warn, StatusLevel.High), statusFor(11.0, goodIsUp = false))
    }

    @Test
    fun `no baseline, no status`() {
        assertNull(statusFor(null, goodIsUp = true))
    }
}
