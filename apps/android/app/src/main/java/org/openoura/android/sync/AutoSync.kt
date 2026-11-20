package org.openoura.android.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.openoura.android.ble.LiveHeartRate
import org.openoura.android.widget.WidgetSync
import org.openoura.android.widget.WidgetUpdates
import org.openoura.android.battery.BatteryAlerts
import org.openoura.android.battery.BatteryLevel
import org.openoura.android.battery.NightForecast
import org.openoura.android.health.HealthExport
import org.openoura.android.ble.RingSyncService
import org.openoura.android.ble.SyncPhase
import org.openoura.android.ble.runRingSync
import org.openoura.android.data.RingKeyStore
import org.openoura.android.data.Summary
import org.openoura.android.data.SummaryRepository
import java.time.LocalTime
import java.util.concurrent.TimeUnit

private const val TAG = "OpenOuraSync"

private const val DAY_MINUTES = 24 * 60
private const val HALF_DAY_MINUTES = 12 * 60

/**
 * A recurring clock window, in minutes past local midnight. [endMin] may be *before*
 * [startMin], which is the normal case for sleep — the window then wraps midnight.
 */
data class SleepWindow(val startMin: Int, val endMin: Int) {
    fun contains(minuteOfDay: Int): Boolean =
        if (startMin <= endMin) minuteOfDay in startMin until endMin
        else minuteOfDay >= startMin || minuteOfDay < endMin
}

/** What asked for a sync. The two are trusted differently — see [AutoSync.decide]. */
/**
 * What woke a sync. [FOLLOW_UP] is a sync this app scheduled for itself because the ring's
 * battery state is worth watching closely — charging, or running low — and is held to a
 * much shorter floor than the others.
 */
enum class SyncTrigger {
    PERIODIC,
    APP_OPEN,
    FOLLOW_UP,

    /** The refresh button on the home-screen widget: someone asked, by hand, right now. */
    WIDGET,

    /**
     * Android saw the ring come back into range ([RingPresenceService]), after the phone
     * and the ring were apart. Held to quiet hours like the scheduled job.
     */
    PRESENCE,
}

enum class SyncDecision { SYNC, TOO_SOON, ASLEEP }

/** How a run ended, as far as the scheduler cares. */
enum class SyncOutcome { DONE, FAILED, CANCELLED }

/** What a finished run leaves behind: whether to rebuild the summary, and whether the
 *  interval has been spent. */
data class SyncAftermath(val recompute: Boolean, val consumeInterval: Boolean)

/**
 * When to sync from the ring without being asked.
 *
 * Two triggers feed one policy: an hourly periodic worker, and opening the app (plus the
 * widget, battery follow-ups and the ring coming into range, each with its own floor).
 *
 * **Doze is the real activity detector, and it is free.** With the phone still, dark and
 * unplugged — which is to say, while its owner is asleep — the system defers jobs into
 * occasional maintenance windows; while the phone is in use they run on time. A periodic
 * job therefore thins out overnight and stays punctual by day without being asked to.
 *
 * Quiet hours are the cheap extra on top, derived from the user's *own* nightly times
 * rather than a guessed 23:00-07:00, and they only ever suppress the periodic trigger.
 *
 * **Unlock was the obvious trigger and it is not available.** `ACTION_USER_PRESENT` cannot
 * be received from the manifest: Android 8's implicit-broadcast restrictions drop it before
 * delivery, which `dumpsys activity broadcasts` states outright for every app that tries —
 * `skipped by policy at enqueue: Background execution not allowed`. Registering it needs a
 * process already running, which is exactly what a background trigger does not have. So the
 * opportunistic trigger is the app being opened, which is a weaker signal but a real one,
 * and it costs nothing.
 *
 * Skipping a night costs nothing: the ring holds a rolling ~8.5 days, and a drain
 * checkpoints its cursor after every batch, so an interrupted or missed sync resumes rather
 * than restarts.
 */
object AutoSync {

    /**
     * The floor between scheduled syncs. An hour since 2026-11-20 (it was three): a sync is a
     * few seconds of radio for a ring that already spends 1.3–2.2%/h, and three hours —
     * which Android's own deferral often stretched to five — left the day's figures stale.
     */
    const val MIN_INTERVAL_SEC = 60L * 60

    /** Below this many nights the sleep window is guesswork, so there are no quiet hours. */
    const val MIN_NIGHTS = 7

    private const val PREFS = "auto_sync"
    private const val KEY_LAST_ATTEMPT = "last_attempt_unix"
    private const val PERIODIC = "auto-sync"
    private const val KEY_PERIOD_H = "periodic_hours"
    private const val ONE_SHOT = "auto-sync-now"
    private const val FOLLOW_UP = "auto-sync-follow-up"

    /** While charging: often enough that "fully charged" arrives soon after it happens. */
    const val FOLLOW_UP_CHARGING_SEC = 30L * 60
    /**
     * At 20% and 10%: at the ring's 1.3–2.2%/h a band lasts 2.5–7 h, so a 3-hour cadence
     * could step straight over the next warning — and one sync costs the ring very little.
     */
    const val FOLLOW_UP_LOW_SEC = 90L * 60
    /** A follow-up's own floor, well under the delays above and far under [MIN_INTERVAL_SEC]. */
    const val FOLLOW_UP_MIN_INTERVAL_SEC = 20L * 60
    internal const val KEY_TRIGGER = "trigger"

    /**
     * The decision itself, kept pure: every input is a value, so the whole policy is
     * testable without a ring, a clock or a phone.
     *
     * Opening the app overrides quiet hours deliberately. If someone opens it at 04:00 then
     * whatever the usual pattern says, the person holding the phone is awake.
     */
    fun decide(
        trigger: SyncTrigger,
        nowEpochSec: Long,
        lastAttemptEpochSec: Long,
        minuteOfDay: Int,
        window: SleepWindow?,
        minIntervalSec: Long = MIN_INTERVAL_SEC,
    ): SyncDecision {
        // A negative gap means the clock moved backwards under us. Treating that as "too
        // soon" would wedge auto-sync until real time caught up, so it falls through.
        val since = nowEpochSec - lastAttemptEpochSec
        return when {
            since in 0 until minIntervalSec -> SyncDecision.TOO_SOON
            // Someone is holding the phone and asked: quiet hours are not for them.
            trigger == SyncTrigger.APP_OPEN || trigger == SyncTrigger.WIDGET -> SyncDecision.SYNC
            window?.contains(minuteOfDay) == true -> SyncDecision.ASLEEP
            else -> SyncDecision.SYNC
        }
    }

    /**
     * The widget's refresh button is a person asking, so the hourly floor does not apply.
     * A minute is only there to swallow a double tap: a scan for a ring that is out of
     * reach takes 25 s, and two of those back to back help nobody.
     */
    const val WIDGET_MIN_INTERVAL_SEC = 60L

    /** The floor a trigger is held to. */
    fun minIntervalFor(trigger: SyncTrigger): Long = when (trigger) {
        SyncTrigger.FOLLOW_UP -> FOLLOW_UP_MIN_INTERVAL_SEC
        SyncTrigger.WIDGET -> WIDGET_MIN_INTERVAL_SEC
        // The ring has been away and is back: worth a look sooner than the hour, though not
        // every time it drops out of range for a minute in another room.
        SyncTrigger.PRESENCE -> FOLLOW_UP_MIN_INTERVAL_SEC
        else -> MIN_INTERVAL_SEC
    }

    /**
     * How soon to look at the ring again, given what the last sync said about its battery;
     * null when the ordinary cadence will do.
     *
     * Not at critical: every sync pulls a nearly empty ring's voltage down, and with the 5%
     * warning given there is nothing left to warn about. The hourly job still notices the
     * ring going onto its charger, which restarts the 30-minute cadence.
     */
    fun followUpDelaySec(charging: Boolean, band: BatteryLevel?): Long? = when {
        charging -> FOLLOW_UP_CHARGING_SEC
        band == BatteryLevel.LOW || band == BatteryLevel.VERY_LOW -> FOLLOW_UP_LOW_SEC
        else -> null
    }

    /**
     * Schedule (or call off) the next follow-up after a sync.
     *
     * Its own unique work, apart from the app-open one-shot, so a pending follow-up never
     * swallows an app-open sync. From any other sync it REPLACEs, restarting the delay from
     * now. From inside a follow-up it APPENDs instead, because REPLACE would cancel the very
     * worker that is asking; the new request then waits for this one to finish. No phone
     * battery constraint: these are few, short, and only while something is happening.
     */
    fun scheduleFollowUp(ctx: Context, delaySec: Long?, fromFollowUp: Boolean) {
        val work = WorkManager.getInstance(ctx)
        if (delaySec == null) {
            if (!fromFollowUp) work.cancelUniqueWork(FOLLOW_UP)
            return
        }
        val req = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.FOLLOW_UP.name))
            .build()
        val policy = if (fromFollowUp) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        work.enqueueUniqueWork(FOLLOW_UP, policy, req)
        Log.i(TAG, "follow-up sync in ${delaySec / 60} min")
    }

    /**
     * What to do once a run has ended.
     *
     * The overnight test on 2026-09-18 is the reason this exists. WorkManager stopped a
     * background drain 59 s in, after it had already checkpointed 20,256 events; the
     * interval had been marked spent before the attempt, so the immediate re-run was
     * refused and the phone sat four hours behind a ring it had been talking to seconds
     * earlier.
     *
     * So a *cancelled but productive* run does not spend the interval: it was making
     * progress and deserves to carry on at the next opportunity. A cancelled *unproductive*
     * one does spend it, because retrying in a tight loop against a system that keeps
     * stopping us would only cost battery.
     *
     * A cancelled run never recomputes. The coroutine is already being torn down, and the
     * drain's own checkpoints mean the database is consistent — [SummaryRepository.load]
     * notices the summary is behind it and rebuilds on the next launch instead.
     */
    fun aftermath(outcome: SyncOutcome, eventsDrained: Long): SyncAftermath = when (outcome) {
        SyncOutcome.DONE -> SyncAftermath(recompute = true, consumeInterval = true)
        // A drain that died on a dropped link still moved the cursor; rebuild over what it
        // did manage, but let the interval stand rather than chasing a ring that just left.
        SyncOutcome.FAILED -> SyncAftermath(eventsDrained > 0, consumeInterval = true)
        SyncOutcome.CANCELLED -> SyncAftermath(recompute = false, consumeInterval = eventsDrained == 0L)
    }

    /**
     * The habitual sleep window, from the nightly `start`/`end` the summary already carries.
     * Null when there are too few nights to be worth trusting.
     */
    fun deriveSleepWindow(
        nights: List<Pair<String, String>>,
        minNights: Int = MIN_NIGHTS,
    ): SleepWindow? {
        if (nights.size < minNights) return null
        val starts = nights.mapNotNull { hmToMinutes(it.first) }
        val ends = nights.mapNotNull { hmToMinutes(it.second) }
        if (starts.size < minNights || ends.size < minNights) return null
        return SleepWindow(medianClock(starts), medianClock(ends))
    }

    /** [deriveSleepWindow] over a summary's nights. */
    fun sleepWindow(summary: Summary?): SleepWindow? = deriveSleepWindow(
        summary?.nights.orEmpty().mapNotNull { night ->
            val start = night.start ?: return@mapNotNull null
            val end = night.end ?: return@mapNotNull null
            start to end
        },
    )

    /** "23:45" → 1425. Null for anything that isn't a plain 24-hour HH:MM. */
    fun hmToMinutes(hm: String): Int? {
        val parts = hm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /**
     * Median of a set of clock times, taken around noon instead of midnight.
     *
     * A plain median is wrong for times that straddle midnight: 23:40, 00:10 and 23:55 are
     * 1420, 10 and 1435 as raw minutes, and their median is 1420 rather than the 23:55 a
     * person would name. Rotating the day so its seam falls at noon puts every plausible
     * bedtime and wake time on one side of the wrap, after which an ordinary median works.
     */
    private fun medianClock(minutes: List<Int>): Int {
        val shifted = minutes.map { (it + HALF_DAY_MINUTES) % DAY_MINUTES }.sorted()
        return (shifted[shifted.size / 2] + HALF_DAY_MINUTES) % DAY_MINUTES
    }

    /**
     * The hourly job.
     *
     * KEEP, not UPDATE: re-enqueuing on every launch with UPDATE would restart the period
     * each time and a frequently-opened app would never reach its own interval.
     */
    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<AutoSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.PERIODIC.name))
            .build()
        // UPDATE once, for phones still holding the 3-hour job: KEEP would leave it in place.
        // After that KEEP again, for the reason above.
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val policy = if (prefs.getInt(KEY_PERIOD_H, 3) != 1) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, policy, req)
        prefs.edit().putInt(KEY_PERIOD_H, 1).apply()
    }

    /**
     * Ask for a sync now, subject to [decide]. Deliberately not expedited: the app being
     * open means the device is awake and interactive, which is when ordinary work runs
     * promptly anyway, and expedited work below API 31 would need a foreground notification
     * for something the user did not ask to watch.
     */
    fun requestSync(ctx: Context, trigger: SyncTrigger) {
        val req = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.KEEP, req)
    }

    /**
     * When a sync was last *attempted*, not last succeeded.
     *
     * Recording attempts bounds radio use to one scan per interval even when the ring is
     * out of range, where recording only successes would let every unlock pay another 25 s
     * scan timeout. The cost is that a failure waits out the full interval — acceptable
     * against ~8.5 days of retention.
     */
    internal fun lastAttempt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_ATTEMPT, 0L)

    internal fun markAttempt(ctx: Context, unix: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_ATTEMPT, unix).apply()
    }

    /** Give the interval back, so the next trigger may pick up where a stop left off. */
    internal fun clearAttempt(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_ATTEMPT).apply()
    }
}

/**
 * One unattended sync.
 *
 * Runs the drain directly rather than starting [RingSyncService]: Android 12+ refuses a
 * foreground-service start from the background, which is precisely the situation this
 * worker exists for. A routine catch-up is seconds of work — 4,827 events took 12 s — so it
 * fits comfortably inside a worker's budget, and if a long one is ever cut short the
 * checkpointed cursor means the next run continues from where it stopped.
 */
class AutoSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        sync()
    } finally {
        // Whatever happened — synced, too soon, no ring in reach, stopped — the widget's
        // refresh icon has to stop turning, or it would go on until its own timeout.
        withContext(NonCancellable) {
            if (WidgetSync.finish(applicationContext)) WidgetUpdates.refreshAll(applicationContext)
        }
    }

    private suspend fun sync(): Result {
        val ctx = applicationContext
        val trigger = runCatching {
            SyncTrigger.valueOf(inputData.getString(AutoSync.KEY_TRIGGER) ?: "")
        }.getOrDefault(SyncTrigger.PERIODIC)

        // A manual sync owns the radio; two drains on one link would fight.
        if (RingSyncService.isRunning) {
            Log.i(TAG, "$trigger: a manual sync is already running")
            SyncLog.record(ctx, trigger.name, SyncLogResult.SKIPPED, "another sync was running")
            return Result.success()
        }
        // Same for a heart-rate measurement: it holds the link for half a minute.
        if (LiveHeartRate.isRunning) {
            Log.i(TAG, "$trigger: a heart-rate measurement is running")
            SyncLog.record(ctx, trigger.name, SyncLogResult.SKIPPED, "measuring heart rate")
            return Result.success()
        }
        if (!RingKeyStore(ctx).hasKey) {
            Log.i(TAG, "$trigger: no ring key stored")
            SyncLog.record(ctx, trigger.name, SyncLogResult.SKIPPED, "no ring key")
            return Result.success()
        }

        val repo = SummaryRepository.get(ctx)
        val window = AutoSync.sleepWindow(repo.cached())
        val now = System.currentTimeMillis() / 1000
        val nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }

        val floor = AutoSync.minIntervalFor(trigger)
        when (val decision = AutoSync.decide(trigger, now, AutoSync.lastAttempt(ctx), nowMinute, window, floor)) {
            SyncDecision.SYNC -> Unit
            else -> {
                Log.i(TAG, "$trigger: skipped ($decision)")
                SyncLog.record(ctx, trigger.name, SyncLogResult.SKIPPED, SyncLog.skipReason(decision, floor))
                // This worker is the one clock that keeps ticking while syncs fail, so it
                // is where an overdue sync gets noticed — but not said in the small hours.
                if (decision != SyncDecision.ASLEEP) SyncHealth.check(ctx, now)
                return Result.success()
            }
        }

        val attemptedAt = now
        AutoSync.markAttempt(ctx, attemptedAt)
        Log.i(TAG, "$trigger: syncing")

        // The drain reports its running total, which is the only way to tell a stop that
        // achieved nothing from one that checkpointed thousands of events.
        var drained = 0L
        val phase = try {
            // Filtered scan: with the screen off Android drops results a ScanFilter did not
            // match, and the ring's name arrives too late in the scan response to filter on.
            // Measured 2026-09-17 — it does advertise the service UUID, so this matches.
            runRingSync(ctx, filteredScan = true) { p ->
                if (p is SyncPhase.Running) drained = maxOf(drained, p.eventsSynced)
            }
        } catch (c: CancellationException) {
            // Everything the drain checkpointed is already committed. Decide whether this
            // run spent the interval, then get out of the way — the marker is a synchronous
            // preference write, so it still lands while the coroutine is being torn down.
            val after = AutoSync.aftermath(SyncOutcome.CANCELLED, drained)
            if (!after.consumeInterval) AutoSync.clearAttempt(ctx)
            Log.i(TAG, "$trigger: stopped after $drained events (retry=${!after.consumeInterval})")
            SyncLog.record(ctx, trigger.name, SyncLogResult.STOPPED, "%,d events".format(drained))
            throw c
        }

        val outcome = if (phase is SyncPhase.Done) SyncOutcome.DONE else SyncOutcome.FAILED
        SyncHealth.record(ctx, phase)
        SyncHealth.check(ctx)
        val after = AutoSync.aftermath(outcome, drained)
        when (phase) {
            is SyncPhase.Done -> Log.i(TAG, "$trigger: ${phase.events} events, ${phase.inserted} new")
            else -> Log.w(TAG, "$trigger: ${(phase as? SyncPhase.Failed)?.message ?: phase}")
        }
        SyncLog.record(ctx, trigger.name, SyncLog.resultOf(phase), SyncLog.detailOf(phase))
        if (after.recompute) {
            runCatching { repo.recompute() }
                .onFailure { Log.e(TAG, "post-sync recompute failed", it) }
            repo.cached()?.let {
                BatteryAlerts.check(ctx, it)
                NightForecast.check(ctx, it)
                it.device?.let { d ->
                    AutoSync.scheduleFollowUp(
                        ctx,
                        AutoSync.followUpDelaySec(d.batteryCharging, BatteryAlerts.bandOf(d.batteryStatus)),
                        fromFollowUp = trigger == SyncTrigger.FOLLOW_UP,
                    )
                }
                // Publish what the sync brought in. Inside the recompute branch on
                // purpose: if the drain achieved nothing there is nothing new to send.
                HealthExport.exportAfterSync(ctx, it)
            }
        }
        // Not Result.retry(): a failure is usually the ring being out of range, and retrying
        // with backoff would spend the radio on a scan that cannot succeed. The next trigger
        // is the retry.
        return Result.success()
    }
}
