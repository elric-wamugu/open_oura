package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openoura.android.data.ActivityScore
import org.openoura.android.data.DailyStat
import org.openoura.android.data.NightRow
import org.openoura.android.data.ReadinessScore
import org.openoura.android.data.SleepMetrics
import org.openoura.android.data.SleepScore
import org.openoura.android.data.Summary
import org.openoura.android.ui.BentoText
import org.openoura.android.ui.HistoryScale
import org.openoura.android.ui.HistoryStops
import java.time.LocalDate

/** The words on Today's bento and the stops on History's line. */
class TodayHistoryTest {

    @Test
    fun `readiness names its weakest contributor only when it drags`() {
        assertEquals("Good · recovery index low", BentoText.readinessLine(73, listOf("HRV balance" to 85, "Recovery index" to 30)))
        assertEquals("Optimal", BentoText.readinessLine(88, listOf("HRV balance" to 85, "Sleep" to 70)))
        assertEquals("Fair", BentoText.readinessLine(62, emptyList()))
    }

    @Test
    fun `activity reads steps and distance, leaving out what is missing`() {
        assertEquals("4.8k steps · 3.5 km", BentoText.activityLine(4_820.0, 3_480.0))
        assertEquals("640 steps", BentoText.activityLine(640.0, 40.0))
        assertEquals("No steps yet", BentoText.activityLine(null, null))
        assertEquals("5h 54m", BentoText.hoursMinutes(354.0))
    }

    /** A night woken from on [wake], 23:30–07:30, with [asleep] minutes and a Sleep Score. */
    private fun night(wake: String, asleep: Double, score: Int, main: Boolean = true, start: String = "23:30", end: String = "07:30") = NightRow(
        ymd = LocalDate.parse(wake).minusDays(if (start > end) 1 else 0).toString(),
        start = start,
        end = end,
        inBedH = if (main) 8.0 else 1.5,
        main = main,
        metrics = SleepMetrics(asleepMin = asleep),
        sleepScore = SleepScore(score = score),
    )

    private val summary = Summary(
        nights = listOf(
            night("2026-11-20", 354.0, 64),
            night("2026-11-19", 423.0, 80),
            night("2026-11-18", 400.0, 70),
            night("2026-11-18", 75.0, 25, main = false, start = "17:52", end = "19:22"),
            night("2026-11-15", 420.0, 76),
        ),
        readiness = mapOf("2026-11-20" to ReadinessScore(73), "2026-11-19" to ReadinessScore(85), "2026-11-15" to ReadinessScore(60)),
        activityScores = mapOf("2026-11-20" to ActivityScore(51), "2026-11-19" to ActivityScore(69)),
        activityDaily = mapOf(
            "2026-11-20" to DailyStat(steps = 4_820.0, distanceM = 3_700.0),
            "2026-11-19" to DailyStat(steps = 6_900.0, distanceM = 5_100.0),
        ),
        activityProfile = mapOf("2026-11-20" to emptyList(), "2026-11-19" to emptyList()),
    )

    @Test
    fun `a day stop carries its scores, its night and its naps`() {
        val days = HistoryStops.build(summary, HistoryScale.DAY)
        assertEquals(listOf("2026-11-20", "2026-11-19", "2026-11-18", "2026-11-15"), days.map { it.key })
        val today = days.first()
        assertEquals(Triple(73, 64, 51), Triple(today.readiness, today.sleep, today.activity))
        assertEquals("Fri · Nov 20", today.title)
        assertEquals("5h 54m asleep · 4.8k steps · 3.7 km", HistoryStops.line(today))
        assertEquals(listOf("17:52"), days[2].naps)
    }

    @Test
    fun `weeks and months average the days inside, Monday to Sunday`() {
        val weeks = HistoryStops.build(summary, HistoryScale.WEEK)
        // Nov 16–22 holds the 18th to the 20th; Nov 9–15 holds the 15th, a Sunday.
        assertEquals(listOf("Nov 16 – 22", "Nov 9 – 15"), weeks.map { it.title })
        val week = weeks.first()
        assertEquals(3, week.days)
        assertEquals(79, week.readiness) // 73 and 85
        assertEquals(71, week.sleep) // 64, 80 and 70: the main nights, not the nap
        assertEquals(60, week.activity) // 51 and 69
        assertEquals("avg 6h 32m asleep · 5.9k steps · 4.4 km", HistoryStops.line(week))

        val months = HistoryStops.build(summary, HistoryScale.MONTH)
        assertEquals(listOf("November 2026"), months.map { it.title })
        assertEquals(4, months.single().days)
    }

    @Test
    fun `a week across two months names both`() {
        assertEquals("Oct 26 – Nov 1", HistoryStops.weekTitle(LocalDate.of(2026, 10, 26)))
    }
}
