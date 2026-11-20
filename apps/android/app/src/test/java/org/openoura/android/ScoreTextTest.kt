package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openoura.android.data.ActivityScoreContributors
import org.openoura.android.data.ReadinessContributors
import org.openoura.android.ui.ScoreText

class ScoreTextTest {

    @Test
    fun `readiness lists its contributors in the app's order and drops ones without data`() {
        val c = ReadinessContributors(
            restingHeartRate = 88, hrvBalance = 86, bodyTemperature = 73, recoveryIndex = 53,
            previousNight = 85, sleepBalance = 30, sleepRegularity = 0, previousDayActivity = 96, activityBalance = 95,
        )
        assertEquals(
            listOf(
                "Resting heart rate" to 88, "HRV balance" to 86, "Body temperature" to 73, "Recovery index" to 53,
                "Sleep" to 85, "Sleep balance" to 30, "Previous day activity" to 96, "Activity balance" to 95,
            ),
            ScoreText.readiness(c),
        )
    }

    @Test
    fun `activity lists its six contributors`() {
        val c = ActivityScoreContributors(0, 78, 100, 86, 0, 48)
        assertEquals(
            listOf(
                "Stay active" to 78, "Move every hour" to 100, "Meet daily targets" to 0,
                "Training frequency" to 86, "Training volume" to 0, "Recovery time" to 48,
            ),
            ScoreText.activity(c),
        )
    }
}
