package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.data.NightRow
import org.openoura.android.data.PulseShape
import org.openoura.android.data.Summary
import org.openoura.android.ui.PulseShapeText
import org.openoura.android.ui.TrendMetric
import org.openoura.android.ui.TrendWindow
import org.openoura.android.ui.describeChange
import org.openoura.android.ui.trendPoints

/** The pulse-shape index as the Cardiovascular panel and Trends put it. */
class PulseShapeTest {
    private fun night(ymd: String, ba: Double?, main: Boolean = true) = NightRow(
        ymd = ymd, start = "23:30", end = "07:30", inBedH = 8.0, main = main,
        pulseShape = ba?.let { PulseShape(bOverA = it, pulseBpm = 60.0, bursts = 12, used = 10) },
    )

    @Test
    fun `the latest night is read against the median of the ones before`() {
        assertNull(PulseShapeText.usual(listOf(-0.62, -0.70, -0.71)))
        assertEquals(-0.70, PulseShapeText.usual(listOf(-0.62, -0.70, -0.71, -0.69))!!, 1e-9)
        assertEquals("Less negative than your usual −0.70", PulseShapeText.versus(-0.62, -0.70))
        assertEquals("In line with your usual −0.70", PulseShapeText.versus(-0.71, -0.70))
        assertEquals("More negative than your usual −0.70", PulseShapeText.versus(-0.80, -0.70))
        assertNull(PulseShapeText.versus(-0.62, null))
    }

    @Test
    fun `only main nights with a reading count, newest first`() {
        val s = Summary(nights = listOf(night("2026-11-20", -0.62), night("2026-11-20", -0.9, main = false), night("2026-11-19", null), night("2026-11-18", -0.7)))
        assertEquals(listOf(-0.62, -0.7), PulseShapeText.nights(s).map { it.second })
    }

    @Test
    fun `trends chart the negative values and word the change by direction`() {
        val s = Summary(nights = listOf(night("2026-11-14", -0.70)))
        assertEquals(listOf(-0.70), trendPoints(s, TrendMetric.PULSE_SHAPE).map { it.value })
        val w = TrendWindow(emptyList(), 0, 30, mean = -0.66, previousMean = -0.70)
        assertEquals("0.04 less negative than the 30 days before", TrendMetric.PULSE_SHAPE.describeChange(w))
    }

    @Test
    fun `the chart sits nights side by side and leaves a slot at long breaks`() {
        // Newest first, as PulseShapeText.nights gives them.
        val nights = listOf("2026-11-21" to -0.68, "2026-08-11" to -0.62, "2026-08-09" to -0.75, "2026-07-24" to -0.64)
        val pts = PulseShapeText.chartPoints(nights)
        assertEquals(listOf(-0.64, -0.75, -0.62, -0.68), pts.map { it.value })
        // Jul 24 → Aug 9 is 16 days and Aug 11 → Nov 21 is months: both skip a slot.
        assertEquals(listOf(0L, 2L, 3L, 5L), pts.map { it.day })
        assertEquals(listOf(-0.62, -0.68), PulseShapeText.chartPoints(nights, limit = 2).map { it.value })
        assertEquals("Jul 24 – Nov 21", PulseShapeText.span(nights))
        assertNull(PulseShapeText.span(nights.take(1)))
    }
}
