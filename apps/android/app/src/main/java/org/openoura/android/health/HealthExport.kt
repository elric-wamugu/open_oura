package org.openoura.android.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Percentage
import org.openoura.android.data.NightRow
import org.openoura.android.data.Summary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "OpenOuraHealth"

/** What a run of the export did, or why it could not run. */
sealed interface ExportResult {
    data class Wrote(val sleep: Int, val vitals: Int, val activity: Int) : ExportResult {
        val total get() = sleep + vitals + activity
    }

    data object NoPermission : ExportResult
    data object Unavailable : ExportResult
    data class Failed(val message: String) : ExportResult
}

/**
 * Publishes the ring's history to Health Connect.
 *
 * Write-only, and deliberately so: the point is to let other apps read what the ring
 * measured, not to pull in anyone else's numbers and blur where a figure came from.
 *
 * Health Connect is on-device storage, so this does not undo the claim in the footer.
 * Nothing is uploaded here. Whether some other app the user has installed then syncs it
 * onward is their arrangement with that app, and it is worth them knowing that is the
 * choice they are making.
 *
 * **What goes, and what does not.** Nightly sessions with their full stage breakdown, plus
 * the three nightly vitals and the daily activity totals — around 500 records for a year.
 * The raw event stream stays here: 2.9 million rows of undecoded protocol frames would be
 * unreadable to any other app, and Health Connect is not a backup.
 */
object HealthExport {

    /**
     * Only write permissions. Reading would let this app show numbers it did not measure,
     * which is the opposite of what the rest of it is careful about.
     */
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    /**
     * How far back an automatic export reaches.
     *
     * Chosen from the ring's ~8.5-day retention window plus margin, not picked round: a
     * sync physically cannot alter a night older than what the ring still holds.
     */
    const val RECENT_DAYS = 14

    private const val PREFS = "health_export"
    private const val KEY_AUTO = "after_each_sync"

    /**
     * Whether a finished sync should also publish what it brought in.
     *
     * On by default, because "sync my data to Health Connect" is what anyone granting these
     * permissions is asking for — the first version of this shipped as a button that ran
     * once, and the data quietly stopped arriving. The switch exists so that can be turned
     * off without revoking the permissions, and it is only ever consulted when they are
     * granted, so it cannot cause a write nobody allowed.
     */
    fun autoExport(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoExport(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO, on).apply()
    }

    /**
     * Publish the recent window after a sync, if that is switched on and allowed.
     *
     * Quiet by design: this runs behind a background drain with nobody watching, so a
     * Health Connect that is unavailable or unpermitted is a log line, not an error the
     * sync has to carry.
     */
    suspend fun exportAfterSync(ctx: Context, summary: Summary) {
        if (!autoExport(ctx)) return
        when (val res = export(ctx, summary, sinceDays = RECENT_DAYS)) {
            is ExportResult.Wrote -> Log.i(TAG, "after sync: ${res.total} records")
            else -> Log.i(TAG, "after sync: skipped ($res)")
        }
    }

    /** Health Connect insists a record name the writer; this is how the ring appears. */
    private val device = Device(
        manufacturer = "Oura",
        model = "Ring Gen3",
        type = Device.TYPE_RING,
    )

    /** [HealthConnectClient.getSdkStatus], without making callers import the client. */
    fun status(ctx: Context): Int = HealthConnectClient.getSdkStatus(ctx)

    fun available(ctx: Context): Boolean =
        status(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(ctx: Context): Boolean = runCatching {
        HealthConnectClient.getOrCreate(ctx).permissionController
            .getGrantedPermissions()
            .containsAll(PERMISSIONS)
    }.getOrDefault(false)

    /**
     * Write every night and day the summary holds.
     *
     * Re-running is safe and is the intended way to correct history: each record carries a
     * stable `clientRecordId` derived from its date, so Health Connect replaces rather than
     * duplicates. The version is the wall clock, so a later export always wins over an
     * earlier one — which matters because a night's staging can change when a sync brings
     * in events that were still on the ring the first time round.
     */
    suspend fun export(
        ctx: Context,
        summary: Summary,
        /**
         * Only export days at least this recent, or null for everything.
         *
         * The automatic path passes [RECENT_DAYS] rather than rewriting a year on every
         * sync. That is safe because of a property of the ring rather than an assumption
         * about the app: it holds a rolling ~8.5 days, so a sync cannot change a night
         * older than that. Anything further back only moves when a database is imported,
         * and the button on the Health Connect screen is there for exactly that.
         */
        sinceDays: Int? = null,
    ): ExportResult {
        if (!available(ctx)) return ExportResult.Unavailable
        if (!hasPermissions(ctx)) return ExportResult.NoPermission

        val zone = ZoneId.systemDefault()
        val version = System.currentTimeMillis() / 1000
        val cutoff = sinceDays?.let { LocalDate.now(zone).minusDays(it.toLong()).toString() }
        // Dates are ISO, so a string comparison is a date comparison and needs no parsing.
        fun recent(ymd: String?) = cutoff == null || (ymd != null && ymd >= cutoff)

        val sleep = mutableListOf<Record>()
        val vitals = mutableListOf<Record>()
        summary.nights.filter { recent(it.ymd) }.forEach { night ->
            sleepSession(night, zone, version)?.let { sleep += it }
            vitals += nightlyVitals(night, zone, version)
        }
        val activity = summary.activityDaily.filterKeys { recent(it) }.flatMap { (date, stat) ->
            dailyActivity(date, stat, zone, version)
        }

        return runCatching {
            val client = HealthConnectClient.getOrCreate(ctx)
            // Chunked because a single insert of everything is one transaction, and a
            // first export carries a year at once.
            (sleep + vitals + activity).chunked(200).forEach { client.insertRecords(it) }
            Log.i(TAG, "exported ${sleep.size} sleep, ${vitals.size} vitals, ${activity.size} activity")
            ExportResult.Wrote(sleep.size, vitals.size, activity.size)
        }.getOrElse {
            Log.e(TAG, "health connect export failed", it)
            ExportResult.Failed(it.message ?: it::class.java.simpleName)
        }
    }

    private fun meta(id: String, version: Long) = Metadata(
        clientRecordId = id,
        clientRecordVersion = version,
        device = device,
        recordingMethod = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
    )

    /**
     * One night, with its stages.
     *
     * [NightRow.stagesFull] is 30-second epochs from the session start, so a stage's time
     * is its index — which is the whole reason the brain now emits `start_unix`. Equal
     * neighbours are merged into runs first: a hypnogram is a few dozen stretches, not
     * eleven hundred half-minutes, and writing it unmerged would be both wasteful and a
     * misrepresentation of how the model actually scored it.
     */
    private fun sleepSession(night: NightRow, zone: ZoneId, version: Long): SleepSessionRecord? {
        val startUnix = night.startUnix ?: return null
        val endUnix = night.endUnix ?: return null
        if (endUnix <= startUnix) return null
        val start = Instant.ofEpochSecond(startUnix)
        val end = Instant.ofEpochSecond(endUnix)

        val stages = night.stagesFull?.takeIf { it.size > 1 }.orEmpty()
        val runs = mutableListOf<SleepSessionRecord.Stage>()
        var i = 0
        while (i < stages.size) {
            val code = stages[i]
            var j = i
            while (j < stages.size && stages[j] == code) j++
            val from = start.plusSeconds(i * 30L)
            val to = minOf(start.plusSeconds(j * 30L), end)
            if (to.isAfter(from)) {
                runs += SleepSessionRecord.Stage(from, to, stageType(code))
            }
            i = j
        }

        return SleepSessionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            title = "Oura ring",
            stages = runs,
            metadata = meta("oura-sleep-${night.ymd ?: startUnix}", version),
        )
    }

    /** 1 deep, 2 light, 3 REM, 4 awake — the codes `stages_full` uses. */
    private fun stageType(code: Int) = when (code) {
        1 -> SleepSessionRecord.STAGE_TYPE_DEEP
        3 -> SleepSessionRecord.STAGE_TYPE_REM
        4 -> SleepSessionRecord.STAGE_TYPE_AWAKE
        else -> SleepSessionRecord.STAGE_TYPE_LIGHT
    }

    /**
     * The three figures the ring derives from a whole night, stamped at wake.
     *
     * A single instant rather than the overnight series: these *are* nightly aggregates —
     * a resting heart rate is not a reading taken at 04:12 — and writing them as one value
     * per night is the honest shape. The per-sample series stays in this app, where the
     * polysomnograph can show it against the hypnogram that explains it.
     */
    private fun nightlyVitals(night: NightRow, zone: ZoneId, version: Long): List<Record> {
        val endUnix = night.endUnix ?: return emptyList()
        val at = Instant.ofEpochSecond(endUnix)
        val offset = zone.rules.getOffset(at)
        val key = night.ymd ?: endUnix.toString()
        val out = mutableListOf<Record>()

        night.rhr?.let {
            out += RestingHeartRateRecord(
                time = at,
                zoneOffset = offset,
                beatsPerMinute = it.toLong(),
                metadata = meta("oura-rhr-$key", version),
            )
        }
        night.hrvMs?.let {
            out += HeartRateVariabilityRmssdRecord(
                time = at,
                zoneOffset = offset,
                heartRateVariabilityMillis = it,
                metadata = meta("oura-hrv-$key", version),
            )
        }
        night.spo2Mean?.takeIf { it in 1.0..100.0 }?.let {
            out += OxygenSaturationRecord(
                time = at,
                zoneOffset = offset,
                percentage = Percentage(it),
                metadata = meta("oura-spo2-$key", version),
            )
        }
        return out
    }

    /**
     * A day's steps and energy, spanning local midnight to midnight.
     *
     * Steps are the ring's MET-derived estimate, not counted footfalls — see the caption on
     * the movement chart. They are still worth exporting, because a coarse estimate from a
     * device worn all day beats the nothing another app would otherwise have, but that is
     * why the day total goes out rather than the 15-minute buckets it was accumulated from.
     */
    private fun dailyActivity(
        date: String,
        stat: org.openoura.android.data.DailyStat,
        zone: ZoneId,
        version: Long,
    ): List<Record> {
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return emptyList()
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        val out = mutableListOf<Record>()

        stat.steps?.takeIf { it > 0 }?.let {
            out += StepsRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                count = it.toLong(),
                metadata = meta("oura-steps-$date", version),
            )
        }
        stat.activeKcal?.takeIf { it > 0 }?.let {
            out += ActiveCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                energy = Energy.kilocalories(it),
                metadata = meta("oura-akcal-$date", version),
            )
        }
        stat.totalKcal?.takeIf { it > 0 }?.let {
            out += TotalCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                energy = Energy.kilocalories(it),
                metadata = meta("oura-tkcal-$date", version),
            )
        }
        return out
    }
}
