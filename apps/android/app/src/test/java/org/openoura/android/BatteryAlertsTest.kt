package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.battery.BatteryAlerts
import org.openoura.android.battery.BatteryLevel

/**
 * What counts as low is `oura-summary`'s call, and is tested there against the ring's own
 * sagging voltage curve. What is left here is the half that can annoy someone: saying it
 * once, escalating when it gets worse, and not starting again every three hours.
 */
class BatteryAlertsTest {

    private fun low(pct: Int?, last: BatteryLevel) =
        BatteryAlerts.evaluate(BatteryLevel.LOW, pct, last)

    private fun ok(pct: Int?, last: BatteryLevel) =
        BatteryAlerts.evaluate(BatteryLevel.OK, pct, last)

    @Test
    fun `the brain's band is what decides, and unknown is not ok`() {
        assertEquals(BatteryLevel.OK, BatteryAlerts.bandOf("ok"))
        assertEquals(BatteryLevel.LOW, BatteryAlerts.bandOf("low"))
        assertEquals(BatteryLevel.CRITICAL, BatteryAlerts.bandOf("critical"))
        assertNull(BatteryAlerts.bandOf(null))
        assertNull(BatteryAlerts.bandOf("fine"))
    }

    @Test
    fun `going low warns once`() {
        val first = low(9, BatteryLevel.OK)
        assertEquals(BatteryLevel.LOW, first.notify)
        assertEquals(BatteryLevel.LOW, first.state)

        assertNull(low(8, first.state).notify)
        assertNull(low(5, BatteryLevel.LOW).notify)
    }

    @Test
    fun `going critical escalates even though it was already low`() {
        val alert = BatteryAlerts.evaluate(BatteryLevel.CRITICAL, 3, BatteryLevel.LOW)
        assertEquals(BatteryLevel.CRITICAL, alert.notify)
    }

    @Test
    fun `a critical warning does not repeat`() {
        assertNull(BatteryAlerts.evaluate(BatteryLevel.CRITICAL, 2, BatteryLevel.CRITICAL).notify)
        assertNull(BatteryAlerts.evaluate(BatteryLevel.CRITICAL, 0, BatteryLevel.CRITICAL).notify)
    }

    @Test
    fun `a straight drop past both bands warns once, critically`() {
        // The ring can lose its last quarter fast, so two syncs can straddle both lines.
        val alert = BatteryAlerts.evaluate(BatteryLevel.CRITICAL, 1, BatteryLevel.OK)
        assertEquals(BatteryLevel.CRITICAL, alert.notify)
    }

    @Test
    fun `hovering on the line does not warn again`() {
        // The rested level is a rolling maximum, so it can read 11, 12, 10 while the ring
        // discharges steadily. Clearing on the first `ok` would warn afresh each time.
        var state = low(9, BatteryLevel.OK).state
        for (pct in listOf(11, 12, 13, 14)) {
            val alert = ok(pct, state)
            assertEquals("cleared too early at $pct%", BatteryLevel.LOW, alert.state)
            assertNull(alert.notify)
            state = alert.state
        }
        assertNull(low(10, state).notify)
    }

    @Test
    fun `charging it clears the warning so the next discharge warns again`() {
        val charged = ok(80, BatteryLevel.CRITICAL)
        assertNull(charged.notify)
        assertEquals(BatteryLevel.OK, charged.state)
        assertEquals(BatteryLevel.LOW, low(10, charged.state).notify)
    }

    @Test
    fun `an ok band with no percentage is taken at face value`() {
        // Nothing better to go on, and holding a stale warning forever would be worse.
        assertEquals(BatteryLevel.OK, ok(null, BatteryLevel.LOW).state)
    }
}
