package org.openoura.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openoura.android.ble.SyncPhase
import org.openoura.android.sync.SyncDecision
import org.openoura.android.sync.SyncLog
import org.openoura.android.sync.SyncLogEntry
import org.openoura.android.sync.SyncLogResult
import org.openoura.android.sync.SyncTrigger
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** What the sync log keeps and how it reads. */
class SyncLogTest {
    private val zone: ZoneId = ZoneOffset.ofHours(3)
    private val today = LocalDate.of(2026, 11, 20)
    private fun at(day: Int, h: Int, m: Int = 0) =
        LocalDate.of(2026, 11, day).atTime(h, m).atZone(zone).toEpochSecond()

    private fun entry(at: Long, result: SyncLogResult = SyncLogResult.SYNCED, detail: String? = null) =
        SyncLogEntry(at, SyncTrigger.PERIODIC.name, result, detail)

    @Test
    fun `new entries go in front and the oldest fall off`() {
        var log = emptyList<SyncLogEntry>()
        for (i in 1L..5L) log = SyncLog.append(log, entry(i), cap = 3)
        assertEquals(listOf(5L, 4L, 3L), log.map { it.at })
    }

    @Test
    fun `the headline counts today's syncs and names the last one`() {
        val log = listOf(
            entry(at(20, 15, 5)),
            entry(at(20, 14, 0), SyncLogResult.SKIPPED, "synced less than an hour ago"),
            entry(at(20, 9, 30)),
            entry(at(19, 22, 0)),
        )
        assertEquals("2 syncs today · last 15:05", SyncLog.todaySummary(log, zone, today))
        assertEquals("No syncs today · last Thu 22:00", SyncLog.todaySummary(listOf(entry(at(19, 22, 0))), zone, today))
        assertNull(SyncLog.todaySummary(emptyList(), zone, today))
    }

    @Test
    fun `times read as today, this week, or a date`() {
        assertEquals("09:30", SyncLog.timeLabel(at(20, 9, 30), zone, today))
        assertEquals("Mon 09:30", SyncLog.timeLabel(at(16, 9, 30), zone, today))
        assertEquals("2 Nov 09:30", SyncLog.timeLabel(at(2, 9, 30), zone, today))
    }

    @Test
    fun `a skip says why, in the floor it was held to`() {
        assertEquals("synced less than an hour ago", SyncLog.skipReason(SyncDecision.TOO_SOON, 3600))
        assertEquals("synced less than 20 min ago", SyncLog.skipReason(SyncDecision.TOO_SOON, 1200))
        assertEquals("your usual sleep hours", SyncLog.skipReason(SyncDecision.ASLEEP, 3600))
    }

    @Test
    fun `a finished sync logs its new events, a failure its message`() {
        val done = SyncPhase.Done("serial", events = 5000, inserted = 1204)
        assertEquals(SyncLogResult.SYNCED, SyncLog.resultOf(done))
        assertEquals("1,204 new events", SyncLog.detailOf(done))
        val failed = SyncPhase.Failed("ring not found")
        assertEquals(SyncLogResult.FAILED, SyncLog.resultOf(failed))
        assertEquals("Failed · ring not found", SyncLog.resultLabel(entry(0, SyncLogResult.FAILED, "ring not found")))
        assertEquals("Ring came near", SyncLog.triggerLabel(SyncTrigger.PRESENCE.name))
        assertEquals("Sync now", SyncLog.triggerLabel("MANUAL"))
    }
}
