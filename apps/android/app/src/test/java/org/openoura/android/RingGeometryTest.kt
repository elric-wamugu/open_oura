package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp
import org.openoura.android.data.Goals
import org.openoura.android.data.WidgetSnapshot
import org.openoura.android.widget.RingLayout
import org.openoura.android.widget.ringLayout
import org.openoura.android.widget.RingStage
import org.openoura.android.widget.WidgetSync
import org.openoura.android.widget.WidgetSyncPath
import org.openoura.android.widget.widgetSyncPath
import org.openoura.android.widget.distanceCaption
import org.openoura.android.widget.distanceLabel
import org.openoura.android.widget.stepProgress
import org.openoura.android.widget.ringCaption
import org.openoura.android.widget.ringHours
import org.openoura.android.widget.stageArcs
import org.openoura.android.widget.stepsLabel

/** What the home-screen ring draws and writes. The drawing itself is Android's. */
class RingGeometryTest {

    private val night = WidgetSnapshot(deepPct = 12, lightPct = 59, remPct = 13, wakePct = 16)

    @Test
    fun `the stages share the circle in proportion, clockwise from the top`() {
        val arcs = stageArcs(night, gapDeg = 4f)
        assertEquals(listOf(RingStage.DEEP, RingStage.LIGHT, RingStage.REM, RingStage.AWAKE), arcs.map { it.stage })
        assertEquals(-88f, arcs.first().startDeg, 0.001f)
        // Four gaps come out of the circle first; what is left is shared by percentage.
        assertEquals(344f, arcs.sumOf { it.sweepDeg.toDouble() }.toFloat(), 0.01f)
        assertEquals(344f * 0.59f, arcs[1].sweepDeg, 0.01f)
        // Each arc starts one gap after the last one ends, and the ring closes on itself.
        arcs.zipWithNext { a, b -> assertEquals(a.startDeg + a.sweepDeg + 4f, b.startDeg, 0.01f) }
        assertEquals(270f - 2f, arcs.last().let { it.startDeg + it.sweepDeg }, 0.01f)
    }

    @Test
    fun `a stage that did not happen is left out, and one alone closes the ring`() {
        val noDeep = stageArcs(night.copy(deepPct = 0))
        assertEquals(listOf(RingStage.LIGHT, RingStage.REM, RingStage.AWAKE), noDeep.map { it.stage })
        val only = stageArcs(WidgetSnapshot(lightPct = 100))
        assertEquals(1, only.size)
        assertEquals(360f, only.single().sweepDeg, 0.001f)
        assertTrue(stageArcs(WidgetSnapshot()).isEmpty())
    }

    @Test
    fun `the middle is hours asleep, or hours in bed for an unscored night`() {
        assertEquals("7.3", ringHours(WidgetSnapshot(sleepHours = 8.1, asleepHours = 7.34)))
        assertEquals("8.1", ringHours(WidgetSnapshot(sleepHours = 8.1)))
        assertNull(ringHours(WidgetSnapshot()))
    }

    @Test
    fun `under it is the efficiency, shortened for a small ring`() {
        val scored = WidgetSnapshot(sleepHours = 8.1, asleepHours = 7.3, efficiency = 84)
        assertEquals("84% efficiency", ringCaption(scored, roomy = true))
        assertEquals("84% eff.", ringCaption(scored, roomy = false))
        // Without one, the caption says which hours the figure is.
        assertEquals("asleep", ringCaption(scored.copy(efficiency = null), roomy = true))
        assertEquals("in bed", ringCaption(WidgetSnapshot(sleepHours = 8.1), roomy = true))
    }

    @Test
    fun `steps and distance read as figures, or a dash before the first sync`() {
        assertEquals("6,921", stepsLabel(6921))
        assertEquals("—", stepsLabel(null))
        assertEquals("5.3", distanceLabel(5.27))
        assertEquals("0", distanceLabel(0.04))
        assertEquals("—", distanceLabel(null))
    }

    @Test
    fun `the figures go beside the ring when wide, under it when tall, and away when neither`() {
        assertEquals(RingLayout.STATS_BESIDE, ringLayout(300.dp, 130.dp))
        assertEquals(RingLayout.STATS_BELOW, ringLayout(180.dp, 210.dp))
        assertEquals(RingLayout.RING_ONLY, ringLayout(180.dp, 130.dp))
        assertEquals(RingLayout.RING_ONLY, ringLayout(110.dp, 60.dp))
        // Wide but a single short row: there is no height for three lines.
        assertEquals(RingLayout.RING_ONLY, ringLayout(300.dp, 60.dp))
    }

    @Test
    fun `syncing shows from the tap until it lapses, and never sticks`() {
        assertTrue(WidgetSync.isShowing(requestedUnix = 1_000, nowUnix = 1_000))
        assertTrue(WidgetSync.isShowing(requestedUnix = 1_000, nowUnix = 1_089))
        assertTrue(!WidgetSync.isShowing(requestedUnix = 1_000, nowUnix = 1_090))
        // Never tapped, or the clock went backwards: not syncing.
        assertTrue(!WidgetSync.isShowing(requestedUnix = 0, nowUnix = 1_000))
        assertTrue(!WidgetSync.isShowing(requestedUnix = 2_000, nowUnix = 1_000))
    }

    @Test
    fun `a tap runs in the foreground service when it can, the worker when it cannot`() {
        assertEquals(WidgetSyncPath.FOREGROUND, widgetSyncPath(serviceRunning = false, bluetoothGranted = true, hasKey = true))
        assertEquals(WidgetSyncPath.BACKGROUND, widgetSyncPath(serviceRunning = false, bluetoothGranted = false, hasKey = true))
        assertEquals(WidgetSyncPath.BACKGROUND, widgetSyncPath(serviceRunning = false, bluetoothGranted = true, hasKey = false))
        // A sync is already going: join it rather than start a second.
        assertEquals(WidgetSyncPath.ALREADY_RUNNING, widgetSyncPath(serviceRunning = true, bluetoothGranted = true, hasKey = true))
    }

    @Test
    fun `the steps ring fills towards the target and stops at full`() {
        assertEquals(0.5f, stepProgress(3_500, 7_000), 0.0001f)
        assertEquals(1f, stepProgress(9_800, 7_000), 0.0001f)
        assertEquals(0f, stepProgress(null, 7_000), 0.0001f)
        assertEquals(0f, stepProgress(100, 0), 0.0001f)
        assertEquals("5.3 km", distanceCaption(5.27))
        assertNull(distanceCaption(null))
    }

    @Test
    fun `a step target is a whole number in a sensible range`() {
        assertEquals(7_000, Goals.parseSteps("7000"))
        assertEquals(12_500, Goals.parseSteps("12,500"))
        assertNull(Goals.parseSteps("120"))
        assertNull(Goals.parseSteps("250000"))
        assertNull(Goals.parseSteps(""))
        assertNull(Goals.parseSteps("lots"))
    }
}
