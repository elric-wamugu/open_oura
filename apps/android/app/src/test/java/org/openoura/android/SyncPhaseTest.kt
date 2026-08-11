package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openoura.android.ble.SyncPhase
import org.openoura.android.ble.describeSync

/**
 * The sync status line is the only progress the user sees during a drain that can run for
 * minutes, and it is otherwise awkward to check — catching a determinate bar on screen
 * needs a ring holding a real backlog at exactly the right moment.
 */
class SyncPhaseTest {

    @Test
    fun `stage shows on its own before any events arrive`() {
        assertEquals("Connecting", describeSync(SyncPhase.Running("connecting")))
        assertEquals("Auth", describeSync(SyncPhase.Running("auth")))
    }

    @Test
    fun `percentage leads once the ring has reported a backlog`() {
        val phase = SyncPhase.Running(
            stage = "sync",
            eventsSynced = 4096,
            bytesLeft = 512_000,
            progress = 0.375f,
        )
        assertEquals("38% · 4096 events · 500 KB left", describeSync(phase))
    }

    @Test
    fun `no percentage while the fraction is unknown`() {
        val phase = SyncPhase.Running(stage = "sync", eventsSynced = 120, progress = null)
        assertEquals("120 events", describeSync(phase))
    }

    @Test
    fun `bounds read exactly 0 and 100 percent`() {
        assertEquals(
            "0% · Sync",
            describeSync(SyncPhase.Running(stage = "sync", progress = 0f)),
        )
        assertEquals(
            "100% · 900 events",
            describeSync(SyncPhase.Running(stage = "sync", eventsSynced = 900, progress = 1f)),
        )
    }

    @Test
    fun `terminal phases read as results, not progress`() {
        assertEquals(
            "Synced 163 events, 163 new",
            describeSync(SyncPhase.Done(serial = "XXXXXXXXXXXXXX", events = 163, inserted = 163)),
        )
        assertEquals("ring out of range", describeSync(SyncPhase.Failed("ring out of range")))
    }
}
