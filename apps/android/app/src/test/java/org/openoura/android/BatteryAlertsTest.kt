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
    fun `four bands map, very_low included`() {
        assertEquals(BatteryLevel.VERY_LOW, BatteryAlerts.bandOf("very_low"))
        assertEquals(
            listOf(BatteryLevel.OK, BatteryLevel.LOW, BatteryLevel.VERY_LOW, BatteryLevel.CRITICAL),
            BatteryLevel.entries.toList(),
        )
    }

    @Test
    fun `each band warns once on the way down`() {
        var state = BatteryLevel.OK
        val said = mutableListOf<BatteryLevel>()
        for ((band, pct) in listOf(
            BatteryLevel.OK to 30, BatteryLevel.LOW to 20, BatteryLevel.LOW to 15,
            BatteryLevel.VERY_LOW to 10, BatteryLevel.VERY_LOW to 7, BatteryLevel.CRITICAL to 5,
            BatteryLevel.CRITICAL to 2,
        )) {
            val alert = BatteryAlerts.evaluate(band, pct, state)
            alert.notify?.let(said::add)
            state = alert.state
        }
        assertEquals(listOf(BatteryLevel.LOW, BatteryLevel.VERY_LOW, BatteryLevel.CRITICAL), said)
    }

    @Test
    fun `a better reading cannot lower the state and re-warn`() {
        // 4%, then a reading of 6% (very_low), then 4% again: the 5% warning is said once.
        val first = BatteryAlerts.evaluate(BatteryLevel.CRITICAL, 4, BatteryLevel.VERY_LOW)
        assertEquals(BatteryLevel.CRITICAL, first.notify)
        val wobble = BatteryAlerts.evaluate(BatteryLevel.VERY_LOW, 6, first.state)
        assertEquals(BatteryLevel.CRITICAL, wobble.state)
        assertNull(BatteryAlerts.evaluate(BatteryLevel.CRITICAL, 4, wobble.state).notify)
    }

    @Test
    fun `hovering just above 20 does not re-warn, and 25 clears`() {
        var state = low(20, BatteryLevel.OK).state
        for (pct in 21..24) {
            val alert = ok(pct, state)
            assertEquals(BatteryLevel.LOW, alert.state)
            state = alert.state
        }
        assertNull(low(20, state).notify)
        assertEquals(BatteryLevel.OK, ok(25, state).state)
    }

    @Test
    fun `charging clears the warning silently`() {
        val alert = BatteryAlerts.evaluate(BatteryLevel.VERY_LOW, 9, BatteryLevel.CRITICAL, recharged = true)
        assertNull(alert.notify)
        assertEquals(BatteryLevel.VERY_LOW, alert.state)
        assertEquals(true, alert.clearWarning)
    }

    @Test
    fun `a partial charge re-arms the bands below it`() {
        // Warned at 5%, topped up to 18% (still low), then off the charger: the 10% and 5%
        // warnings must come round again, though the 20% one has nothing new to say.
        val topped = BatteryAlerts.evaluate(BatteryLevel.LOW, 18, BatteryLevel.CRITICAL, recharged = true)
        assertEquals(BatteryLevel.LOW, topped.state)
        assertNull(low(16, topped.state).notify)
        assertEquals(BatteryLevel.VERY_LOW, BatteryAlerts.evaluate(BatteryLevel.VERY_LOW, 10, topped.state).notify)
    }

    @Test
    fun `recharged needs a newer start than the one already seen`() {
        assertEquals(true, BatteryAlerts.recharged(charging = true, started = null, seenStart = null))
        assertEquals(true, BatteryAlerts.recharged(false, started = 2_000.0, seenStart = 1_000.0))
        assertEquals(false, BatteryAlerts.recharged(false, started = 1_000.0, seenStart = 1_000.0))
        // First run: nothing to compare against, so an old charge is not news.
        assertEquals(false, BatteryAlerts.recharged(false, started = 1_000.0, seenStart = null))
    }

    @Test
    fun `a full charge is announced once, while it is news`() {
        val now = 100_000.0
        val full = now - 600
        assertEquals(true, BatteryAlerts.announceFull(full, lastAnnounced = null, now = now))
        assertEquals(false, BatteryAlerts.announceFull(full, lastAnnounced = full, now = now))
        assertEquals(false, BatteryAlerts.announceFull(now - 7 * 3600, lastAnnounced = null, now = now))
        assertEquals(false, BatteryAlerts.announceFull(null, lastAnnounced = null, now = now))
        // The next charge, a day later, is news again.
        assertEquals(true, BatteryAlerts.announceFull(full + 86_400, lastAnnounced = full, now = now + 86_400))
    }

    @Test
    fun `an ok band with no percentage is taken at face value`() {
        // Nothing better to go on, and holding a stale warning forever would be worse.
        assertEquals(BatteryLevel.OK, ok(null, BatteryLevel.LOW).state)
    }
}
