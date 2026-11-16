package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openoura.android.battery.NightForecast
import org.openoura.android.data.BatteryCycle
import org.openoura.android.sync.SleepWindow

private const val DAY_MIN = 24 * 60L

/** A day far enough from the epoch that nothing here is accidentally zero. */
private const val DAY0 = 20_000L

private fun at(hour: Int, minute: Int = 0, day: Long = DAY0) = day * DAY_MIN + hour * 60 + minute

private fun run(end: Double, pctPerHour: Double) = BatteryCycle(
    start = end - 36_000, end = end, fromPct = 80, toPct = 60, hours = 10.0,
    pctPerHour = pctPerHour, projectedFullH = 100 / pctPerHour,
)

/**
 * Whether the ring will see the morning.
 *
 * The 20% warning answers a different question. It fires when the level crosses a line,
 * whatever the hour; this weighs the level against the night that is about to start.
 */
class NightForecastTest {

    /** Asleep 23:30 → 07:30. */
    private val window = SleepWindow(startMin = 23 * 60 + 30, endMin = 7 * 60 + 30)

    @Test
    fun `a ring above 20 percent can still fail the night`() {
        // 21:30, 30%, 2.1 %/h: two hours to bed and eight asleep is 21 points, and the
        // gauge is not to be trusted below 5.
        val f = NightForecast.forecast(at(21, 30), window, pct = 26, ratePctPerHour = 2.1)
        assertNotNull(f)
        assertEquals(5, f!!.pctAtWake)
        assertNull("it is nearly empty by morning, not empty before it", f.emptyAtMin)
    }

    @Test
    fun `enough for the night says nothing`() {
        assertNull(NightForecast.forecast(at(21, 30), window, pct = 60, ratePctPerHour = 2.1))
    }

    @Test
    fun `it names when the ring runs out if that is before waking`() {
        // 12% at 1.5 %/h lasts eight hours from 22:00: 06:00, ninety minutes short.
        val f = NightForecast.forecast(at(22), window, pct = 12, ratePctPerHour = 1.5)!!
        assertEquals(6 * 60, f.emptyAtMin)
        assertEquals(0, f.pctAtWake)
        val (title, text) = NightForecast.describe(f, pct = 12)
        assertEquals("Ring won't last the night — 12%", title)
        assertTrue(text, text.contains("runs out around 06:00, before you usually wake at 07:30"))
    }

    @Test
    fun `it only speaks in the four hours before bed`() {
        // The same hopeless level at lunchtime is the 20% warning's business, not this one's.
        assertNull(NightForecast.forecast(at(13), window, pct = 8, ratePctPerHour = 2.0))
        assertNotNull(NightForecast.forecast(at(19, 30), window, pct = 8, ratePctPerHour = 2.0))
        // Past bedtime the evening is over.
        assertNull(NightForecast.forecast(at(23, 45), window, pct = 8, ratePctPerHour = 2.0))
    }

    @Test
    fun `a bedtime after midnight is still one night`() {
        // Asleep 01:40 → 10:00. 23:00 and 00:30 are the same evening, and must not be
        // counted as two nights just because the date changed between them.
        val late = SleepWindow(startMin = 100, endMin = 10 * 60)
        val before = NightForecast.forecast(at(23), late, pct = 10, ratePctPerHour = 2.0)!!
        val after = NightForecast.forecast(at(0, 30, day = DAY0 + 1), late, pct = 9, ratePctPerHour = 2.0)!!
        assertEquals(before.night, after.night)
        assertEquals(DAY0 + 1, before.night)
        // The next evening is a new night.
        val next = NightForecast.forecast(at(23, 0, day = DAY0 + 1), late, pct = 10, ratePctPerHour = 2.0)!!
        assertEquals(before.night + 1, next.night)
    }

    @Test
    fun `the plan is for the fastest of the last three runs`() {
        val now = 1_800_000_000.0
        val cycles = listOf(
            run(now - 9 * 86_400, 2.6),   // older than the last three: not considered
            run(now - 6 * 86_400, 0.8),
            run(now - 3 * 86_400, 2.1),
            run(now - 3_600, 1.1),
        )
        assertEquals(2.1, NightForecast.drainRate(cycles, now)!!, 1e-9)
    }

    @Test
    fun `runs from weeks ago say nothing about tonight`() {
        val now = 1_800_000_000.0
        assertNull(NightForecast.drainRate(listOf(run(now - 30 * 86_400, 1.5)), now))
        assertNull(NightForecast.drainRate(emptyList(), now))
    }
}
