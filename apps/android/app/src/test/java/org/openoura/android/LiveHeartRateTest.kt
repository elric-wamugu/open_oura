package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.ble.LiveHeartRate

/** What a run of beats is shown as, and what it amounts to. The ring and the link are not here. */
class LiveHeartRateTest {

    @Test
    fun `the figure shown is the median of the last five beats`() {
        assertNull(LiveHeartRate.smoothed(emptyList()))
        assertEquals(62, LiveHeartRate.smoothed(listOf(62)))
        assertEquals(63, LiveHeartRate.smoothed(listOf(62, 64)))
        // One mistimed beat does not move it.
        assertEquals(62, LiveHeartRate.smoothed(listOf(61, 62, 120, 62, 63)))
        // Only the last five count.
        assertEquals(70, LiveHeartRate.smoothed(listOf(50, 50, 50, 69, 70, 70, 71, 72)))
    }

    @Test
    fun `a result needs enough beats, and is built from the smoothed series`() {
        assertNull(LiveHeartRate.summarise(listOf(60, 61, 62, 63, 64, 65, 66), seconds = 30, atUnix = 1_000))
        val steady = LiveHeartRate.summarise(List(30) { 62 }, seconds = 30, atUnix = 1_000)!!
        assertEquals(62, steady.bpm)
        assertEquals(62, steady.low)
        assertEquals(62, steady.high)
        assertEquals(30, steady.beats)
        // A single wild beat in a steady run changes neither the average nor the range.
        val spiked = LiveHeartRate.summarise(List(30) { if (it == 15) 140 else 62 }, seconds = 30, atUnix = 1_000)!!
        assertEquals(62, spiked.bpm)
        assertEquals(62, spiked.high)
        // A real change does show.
        val rising = LiveHeartRate.summarise(List(30) { 60 + it / 2 }, seconds = 30, atUnix = 1_000)!!
        assertEquals(61, rising.low)
        assertEquals(73, rising.high)
    }

    @Test
    fun `the reading is set against the resting rate when there is one`() {
        assertEquals("5 bpm above your resting rate", LiveHeartRate.againstResting(63, 58))
        assertEquals("3 bpm below your resting rate", LiveHeartRate.againstResting(55, 58))
        assertEquals("Level with your resting rate", LiveHeartRate.againstResting(58, 58))
        assertNull(LiveHeartRate.againstResting(63, null))
    }

    @Test
    fun `how long ago reads in the largest unit that fits`() {
        assertEquals("just now", LiveHeartRate.ago(1_000, 1_060))
        assertEquals("12 min ago", LiveHeartRate.ago(0, 12 * 60))
        assertEquals("2 h ago", LiveHeartRate.ago(0, 2 * 3600 + 500))
        assertEquals("3 days ago", LiveHeartRate.ago(0, 3 * 86_400 + 10))
        // A clock that went backwards is not "minus five minutes ago".
        assertEquals("just now", LiveHeartRate.ago(1_000, 700))
    }
}
