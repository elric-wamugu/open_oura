package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.data.BreathingRate
import org.openoura.android.data.DailyStat
import org.openoura.android.data.NightRow
import org.openoura.android.data.SleepMetrics
import org.openoura.android.data.Summary
import org.openoura.android.ui.TrendMetric
import org.openoura.android.ui.TrendPoint
import org.openoura.android.ui.describeChange
import org.openoura.android.ui.format
import org.openoura.android.ui.lastDay
import org.openoura.android.ui.restingVsBaseline
import org.openoura.android.ui.trendPoints
import org.openoura.android.ui.trendWindow
import org.openoura.android.ui.vascularVsAge
import java.time.LocalDate

private fun day(ymd: String) = LocalDate.parse(ymd).toEpochDay()

/** A night slept through on the evening before [wake], so it belongs to [wake]'s morning. */
private fun night(wake: String, hrv: Double? = null, inBed: Double = 8.0, asleepMin: Double? = null) = NightRow(
    ymd = LocalDate.parse(wake).minusDays(1).toString(),
    start = "23:30",
    end = "07:30",
    inBedH = inBed,
    hrvMs = hrv,
    metrics = asleepMin?.let { SleepMetrics(asleepMin = it) },
)

/**
 * What the Trends view charts, and what it compares it with.
 *
 * The comparison is the point of the view — "is this month better than last?" — so the
 * windows have to be exactly adjacent and exactly equal, and say nothing when the earlier
 * one is too thin to mean anything.
 */
class TrendsTest {

    private val end = day("2026-11-15")
    /** Oldest first, as [trendPoints] hands them over. */
    private fun series(vararg v: Pair<Int, Double>) =
        v.map { (ago, value) -> TrendPoint(end - ago, value) }.sortedBy { it.day }

    @Test
    fun `a window is its last N days, and the N before are the comparison`() {
        val all = series(0 to 50.0, 6 to 40.0, 7 to 30.0, 9 to 32.0, 13 to 28.0, 14 to 99.0)
        val w = trendWindow(all, end, days = 7)
        assertEquals(listOf(40.0, 50.0), w.points.map { it.value })
        assertEquals(45.0, w.mean!!, 1e-9)
        // Days 7..13 ago; day 14 belongs to neither.
        assertEquals(30.0, w.previousMean!!, 1e-9)
        assertEquals(15.0, w.change!!, 1e-9)
        assertEquals(end - 6, w.startDay)
    }

    @Test
    fun `too little before is no comparison at all`() {
        val w = trendWindow(series(0 to 50.0, 8 to 20.0, 9 to 22.0), end, days = 7)
        assertNull("two points are an anecdote", w.previousMean)
        assertNull(w.change)
        assertNull(TrendMetric.HRV.describeChange(w))
    }

    @Test
    fun `an empty window has no average rather than a zero`() {
        val w = trendWindow(series(40 to 50.0), end, days = 7)
        assertNull(w.mean)
        assertNull(w.low)
    }

    @Test
    fun `a change is put in the metric's own words and precision`() {
        val up = trendWindow(series(0 to 52.0, 8 to 40.0, 9 to 40.0, 10 to 40.0), end, days = 7)
        assertEquals("12 ms higher than the 7 days before", TrendMetric.HRV.describeChange(up))
        val steps = trendWindow(series(0 to 6_000.0, 8 to 8_500.0, 9 to 8_500.0, 10 to 8_500.0), end, days = 7)
        assertEquals("2,500 steps lower than the 7 days before", TrendMetric.STEPS.describeChange(steps))
        val pct = trendWindow(series(0 to 81.0, 8 to 80.0, 9 to 80.0, 10 to 80.0), end, days = 7)
        assertEquals("1% higher than the 7 days before", TrendMetric.EFFICIENCY.describeChange(pct))
        // 0.04 h is under the one decimal it would be shown in.
        val flat = trendWindow(series(0 to 7.44, 8 to 7.40, 9 to 7.40, 10 to 7.40), end, days = 7)
        assertEquals("Level with the 7 days before", TrendMetric.ASLEEP.describeChange(flat))
    }

    @Test
    fun `values print in the metric's precision`() {
        assertEquals("47", TrendMetric.HRV.format(47.4))
        assertEquals("7.4", TrendMetric.ASLEEP.format(7.44))
        assertEquals("8,214", TrendMetric.STEPS.format(8_214.0))
    }

    @Test
    fun `a night counts on the day it was woken from`() {
        val s = Summary(
            nights = listOf(night("2026-11-15", hrv = 47.0), night("2026-11-14", hrv = 41.0)),
            activityProfile = mapOf("2026-11-15" to emptyList(), "2026-11-14" to emptyList()),
        )
        val pts = trendPoints(s, TrendMetric.HRV)
        assertEquals(listOf(day("2026-11-14") to 41.0, day("2026-11-15") to 47.0), pts.map { it.day to it.value })
        assertEquals(day("2026-11-15"), lastDay(s))
    }

    @Test
    fun `time asleep falls back to time in bed, and a missing value is a gap`() {
        val s = Summary(
            nights = listOf(
                night("2026-11-15", asleepMin = 420.0, inBed = 8.5),
                night("2026-11-14", inBed = 7.5),
                night("2026-11-13", hrv = null),
            ),
        )
        assertEquals(listOf(8.0, 7.5, 7.0), trendPoints(s, TrendMetric.ASLEEP).map { it.value })
        assertEquals(emptyList<TrendPoint>(), trendPoints(s, TrendMetric.HRV))
    }

    @Test
    fun `breathing rate charts each night that has one, read from the summary's JSON`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val row = json.decodeFromString<NightRow>(
            """{"ymd":"2026-11-14","breathing_rate":{"rate":13.625,"variability":3.875}}""",
        )
        assertEquals(BreathingRate(rate = 13.625, variability = 3.875), row.breathingRate)
        val s = Summary(nights = listOf(row.copy(start = "23:30", end = "07:30"), night("2026-11-14")))
        assertEquals(listOf(13.625), trendPoints(s, TrendMetric.BREATHING).map { it.value })
        assertEquals("13.6", TrendMetric.BREATHING.format(13.625))
    }

    @Test
    fun `scores lead, then sleep and activity, then the body's signals`() {
        assertEquals(
            listOf(
                TrendMetric.READINESS, TrendMetric.SLEEP_SCORE, TrendMetric.ACTIVITY_SCORE,
                TrendMetric.ASLEEP, TrendMetric.STEPS, TrendMetric.EFFICIENCY,
                TrendMetric.RESTING_HR, TrendMetric.HRV, TrendMetric.BREATHING,
                TrendMetric.SKIN_TEMP, TrendMetric.SPO2,
            ),
            TrendMetric.entries.toList(),
        )
    }

    @Test
    fun `the day's scores chart, and their changes read in points`() {
        val s = Summary(
            nights = listOf(night("2026-11-15").copy(sleepScore = org.openoura.android.data.SleepScore(64))),
            readiness = mapOf("2026-11-15" to org.openoura.android.data.ReadinessScore(73)),
            activityScores = mapOf("2026-11-15" to org.openoura.android.data.ActivityScore(49)),
        )
        assertEquals(listOf(73.0), trendPoints(s, TrendMetric.READINESS).map { it.value })
        assertEquals(listOf(64.0), trendPoints(s, TrendMetric.SLEEP_SCORE).map { it.value })
        assertEquals(listOf(49.0), trendPoints(s, TrendMetric.ACTIVITY_SCORE).map { it.value })
        val w = org.openoura.android.ui.TrendWindow(emptyList(), 0, 7, mean = 70.0, previousMean = 66.0)
        assertEquals("4 points higher than the 7 days before", TrendMetric.READINESS.describeChange(w))
        val steps = org.openoura.android.ui.TrendWindow(emptyList(), 0, 7, mean = 3_900.0, previousMean = 7_757.0)
        assertEquals("3,857 steps lower than the 7 days before", TrendMetric.STEPS.describeChange(steps))
    }

    @Test
    fun `a day with no steps is not a zero-step day`() {
        val s = Summary(
            activityProfile = mapOf("2026-11-15" to emptyList(), "2026-11-14" to emptyList()),
            activityDaily = mapOf("2026-11-15" to DailyStat(steps = 0.0), "2026-11-14" to DailyStat(steps = 8_200.0)),
        )
        assertEquals(listOf(8_200.0), trendPoints(s, TrendMetric.STEPS).map { it.value })
    }

    @Test
    fun `resting heart rate is read against the baseline, in the web's words`() {
        assertEquals("8.3 bpm below your 64.3 bpm baseline", restingVsBaseline(56.0, 64.3))
        assertEquals("2 bpm above your 60 bpm baseline", restingVsBaseline(62.0, 60.0))
        assertNull(restingVsBaseline(56.0, null))
    }

    @Test
    fun `vascular age is read against the real one, as the web's relAge`() {
        assertEquals("3 years older than", vascularVsAge(43.0, 40.0))
        assertEquals("1 year younger than", vascularVsAge(39.0, 40.0))
        assertEquals("in line with", vascularVsAge(40.04, 40.0))
    }
}
