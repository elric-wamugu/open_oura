package org.openoura.android.sync

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What every sync attempt did, newest first, so "why hasn't it synced?" has an answer.
 *
 * Kept because the schedule is mostly Android's to keep: a periodic job asked for hourly can
 * run hours late, and a sync that did run can find no ring in reach. Neither shows anywhere
 * else. One small JSON file of the last [CAP] entries; nothing leaves the phone.
 */
@Serializable
data class SyncLogEntry(
    /** Unix seconds. */
    val at: Long,
    /** A [SyncTrigger] name, or "MANUAL" for a sync started from the Ring tab. */
    val trigger: String,
    val result: SyncLogResult,
    /** Events drained for a sync, the reason for a skip, the error for a failure. */
    val detail: String? = null,
)

@Serializable
enum class SyncLogResult { SYNCED, FAILED, STOPPED, SKIPPED, RING_NEAR, RING_AWAY }

object SyncLog {
    const val CAP = 200
    private const val FILE = "sync_log.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun read(ctx: Context): List<SyncLogEntry> = synchronized(lock) {
        runCatching {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) emptyList()
            else json.decodeFromString(ListSerializer(SyncLogEntry.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    fun record(
        ctx: Context,
        trigger: String,
        result: SyncLogResult,
        detail: String? = null,
        at: Long = System.currentTimeMillis() / 1000,
    ) {
        synchronized(lock) {
            val next = append(read(ctx), SyncLogEntry(at, trigger, result, detail))
            runCatching {
                File(ctx.filesDir, FILE).writeText(
                    json.encodeToString(ListSerializer(SyncLogEntry.serializer()), next),
                )
            }
        }
    }

    /** The new entry in front, the oldest dropped past [cap]. */
    fun append(log: List<SyncLogEntry>, entry: SyncLogEntry, cap: Int = CAP): List<SyncLogEntry> =
        (listOf(entry) + log).take(cap)

    fun resultOf(phase: org.openoura.android.ble.SyncPhase): SyncLogResult =
        if (phase is org.openoura.android.ble.SyncPhase.Done) SyncLogResult.SYNCED else SyncLogResult.FAILED

    /** "1,204 new events", or the failure's own message. */
    fun detailOf(phase: org.openoura.android.ble.SyncPhase): String? = when (phase) {
        is org.openoura.android.ble.SyncPhase.Done -> "%,d new events".format(phase.inserted)
        is org.openoura.android.ble.SyncPhase.Failed -> phase.message
        else -> null
    }

    /** "Periodic", "App opened", … — what woke it, in words. */
    fun triggerLabel(trigger: String): String = when (trigger) {
        SyncTrigger.PERIODIC.name -> "Scheduled"
        SyncTrigger.APP_OPEN.name -> "App opened"
        SyncTrigger.FOLLOW_UP.name -> "Battery check"
        SyncTrigger.WIDGET.name -> "Widget"
        SyncTrigger.PRESENCE.name -> "Ring came near"
        "MANUAL" -> "Sync now"
        "COMPANION" -> "Android"
        else -> trigger.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** One line for an entry's outcome. */
    fun resultLabel(e: SyncLogEntry): String = when (e.result) {
        SyncLogResult.SYNCED -> "Synced" + (e.detail?.let { " · $it" } ?: "")
        SyncLogResult.FAILED -> "Failed" + (e.detail?.let { " · $it" } ?: "")
        SyncLogResult.STOPPED -> "Stopped by Android" + (e.detail?.let { " · $it" } ?: "")
        SyncLogResult.SKIPPED -> "Skipped" + (e.detail?.let { " · $it" } ?: "")
        SyncLogResult.RING_NEAR -> "Ring in range"
        SyncLogResult.RING_AWAY -> "Ring out of range"
    }

    /** Why a skip happened, in words. */
    fun skipReason(decision: SyncDecision, floorSec: Long): String = when (decision) {
        SyncDecision.TOO_SOON -> "synced less than ${floorWords(floorSec)} ago"
        SyncDecision.ASLEEP -> "your usual sleep hours"
        SyncDecision.SYNC -> "—"
    }

    private fun floorWords(sec: Long): String = when {
        sec >= 3600 && sec % 3600 == 0L -> if (sec == 3600L) "an hour" else "${sec / 3600} hours"
        sec >= 60 -> "${sec / 60} min"
        else -> "$sec s"
    }

    /**
     * The headline over the list: how many syncs reached the ring today, and when the last
     * one did. Null before anything has been logged.
     */
    fun todaySummary(log: List<SyncLogEntry>, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String? {
        if (log.isEmpty()) return null
        val synced = log.filter { it.result == SyncLogResult.SYNCED }
        val todayCount = synced.count { Instant.ofEpochSecond(it.at).atZone(zone).toLocalDate() == today }
        val last = synced.firstOrNull()?.let { timeLabel(it.at, zone, today) }
        val count = when (todayCount) {
            0 -> "No syncs today"
            1 -> "1 sync today"
            else -> "$todayCount syncs today"
        }
        return if (last != null) "$count · last $last" else count
    }

    /** "14:05" today, "Wed 14:05" within the week, "12 Nov 14:05" before that. */
    fun timeLabel(at: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val t = Instant.ofEpochSecond(at).atZone(zone)
        val pattern = when {
            t.toLocalDate() == today -> "HH:mm"
            t.toLocalDate().isAfter(today.minusDays(7)) -> "EEE HH:mm"
            else -> "d MMM HH:mm"
        }
        return t.format(DateTimeFormatter.ofPattern(pattern))
    }
}
