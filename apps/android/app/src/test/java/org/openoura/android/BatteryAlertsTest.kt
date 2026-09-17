package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.battery.BatteryAlerts
import org.openoura.android.battery.BatteryLevel
import org.openoura.android.data.BatteryPoint

/**
 * A battery warning is only useful if it is both timely and rare. The two ways to get that
 * wrong — crying wolf at a level the radio only made look low, and warning again every
 * three hours once it is genuinely low — are both cheap to check here and expensive to
 * discover on a wrist.
 */
class BatteryAlertsTest {

    @Test
    fun `crossing ten percent warns once`() {
        val first = BatteryAlerts.evaluate(9, BatteryLevel.OK)
        assertEquals(BatteryLevel.LOW, first.notify)
        assertEquals(BatteryLevel.LOW, first.state)

        // Still low three hours later. Nothing new to say.
        assertNull(BatteryAlerts.evaluate(8, first.state).notify)
        assertNull(BatteryAlerts.evaluate(5, BatteryLevel.LOW).notify)
    }

    @Test
    fun `dropping to three percent escalates even though it was already low`() {
        val alert = BatteryAlerts.evaluate(3, BatteryLevel.LOW)
        assertEquals(BatteryLevel.CRITICAL, alert.notify)
    }

    @Test
    fun `a critical warning does not repeat`() {
        assertNull(BatteryAlerts.evaluate(2, BatteryLevel.CRITICAL).notify)
        assertNull(BatteryAlerts.evaluate(0, BatteryLevel.CRITICAL).notify)
    }

    @Test
    fun `a straight drop past both thresholds warns once, critically`() {
        // The ring can lose the last quarter fast, so two syncs can straddle both lines.
        val alert = BatteryAlerts.evaluate(1, BatteryLevel.OK)
        assertEquals(BatteryLevel.CRITICAL, alert.notify)
    }

    @Test
    fun `hovering on the threshold does not warn repeatedly`() {
        // 11, 12 and 13 are above LOW but below CLEAR: the band is held, not cleared, so
        // slipping back to 10 has nothing new to announce.
        var state = BatteryAlerts.evaluate(9, BatteryLevel.OK).state
        for (pct in listOf(11, 12, 13, 10, 12, 9)) {
            val alert = BatteryAlerts.evaluate(pct, state)
            assertNull("warned again at $pct%", alert.notify)
            state = alert.state
        }
    }

    @Test
    fun `charging it clears the warning so the next discharge warns again`() {
        val charged = BatteryAlerts.evaluate(80, BatteryLevel.CRITICAL)
        assertNull(charged.notify)
        assertEquals(BatteryLevel.OK, charged.state)
        assertEquals(BatteryLevel.LOW, BatteryAlerts.evaluate(10, charged.state).notify)
    }

    @Test
    fun `the sync's own sagged reading does not trigger a false alarm`() {
        // The measured failure mode: the gauge is voltage-derived and sags under radio
        // load, so the points logged during a drain read far below the rested level.
        // 24% at rest, collapsing to 6% while the radio worked, is not a 6% ring.
        val now = 1_000_000L
        val series = listOf(
            BatteryPoint(t = (now - 1500).toDouble(), pct = 24, mv = 3679),
            BatteryPoint(t = (now - 1200).toDouble(), pct = 24, mv = 3670),
            BatteryPoint(t = (now - 120).toDouble(), pct = 12, mv = 3500),
            BatteryPoint(t = (now - 30).toDouble(), pct = 6, mv = 3449),
        )
        assertEquals(24, BatteryAlerts.restedPct(series, now))
        assertNull(BatteryAlerts.evaluate(24, BatteryLevel.OK).notify)
    }

    @Test
    fun `a genuinely flat ring still reads flat`() {
        val now = 1_000_000L
        val series = listOf(
            BatteryPoint(t = (now - 1500).toDouble(), pct = 4, mv = 3460),
            BatteryPoint(t = (now - 60).toDouble(), pct = 2, mv = 3400),
        )
        assertEquals(4, BatteryAlerts.restedPct(series, now))
        assertEquals(BatteryLevel.LOW, BatteryAlerts.evaluate(4, BatteryLevel.OK).notify)
    }

    @Test
    fun `readings older than the window are ignored`() {
        val now = 1_000_000L
        val series = listOf(
            BatteryPoint(t = (now - 7200).toDouble(), pct = 90, mv = 4100),
            BatteryPoint(t = (now - 300).toDouble(), pct = 9, mv = 3480),
        )
        // Yesterday's 90% must not mask today's 9%.
        assertEquals(9, BatteryAlerts.restedPct(series, now))
    }

    @Test
    fun `an empty window is null rather than zero`() {
        val now = 1_000_000L
        val stale = listOf(BatteryPoint(t = (now - 99_999).toDouble(), pct = 50, mv = 3900))
        assertNull(BatteryAlerts.restedPct(stale, now))
        assertNull(BatteryAlerts.restedPct(emptyList(), now))
    }
}
