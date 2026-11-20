package org.openoura.android.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import org.openoura.android.R
import org.openoura.android.alerts.ALERT_AMBER
import org.openoura.android.alerts.ALERT_RED
import org.openoura.android.alerts.AlertKind
import org.openoura.android.alerts.AlertPrefs
import org.openoura.android.alerts.Alerts
import org.openoura.android.ble.SyncPhase
import org.openoura.android.data.RingKeyStore
import org.openoura.android.data.SummaryRepository
import org.openoura.android.ui.nav.Tab

private const val TAG = "OpenOuraSync"

private const val CHANNEL_OVERDUE = "ring_sync_overdue"
private const val CHANNEL_AT_RISK = "ring_data_at_risk"

/** One id for both stages, so the urgent warning replaces the first rather than joining it. */
private const val NOTIF_OVERDUE = 1005

private const val HOUR = 3600L
private const val DAY = 24 * HOUR

/** How far behind the ring the phone is. Ordered worst-last, like the battery bands. */
enum class StaleLevel { FRESH, STALE, URGENT }

/** What [SyncHealth.evaluate] concluded: what to say, and what to remember. */
data class StaleAlert(val notify: StaleLevel?, val state: StaleLevel)

/**
 * Says when the ring has gone too long without handing its data over.
 *
 * The ring keeps a rolling ~8.5 days and then overwrites the oldest. [AutoSync] is right
 * that skipping a night costs nothing, and that is exactly why nothing ever said anything:
 * a ring left in a drawer, a revoked Bluetooth permission or a rejected key all fail
 * quietly, every hour, until the week is gone.
 *
 * What is measured is the time since the last *complete* sync, which is when the phone was
 * last level with the ring. A drain that was cut short still saved the oldest of what was
 * waiting, so this errs towards warning early, never late.
 *
 * Checked whenever the periodic worker runs, whether or not it reached the ring — that
 * worker is the only clock here that keeps ticking while syncs fail.
 */
object SyncHealth {

    /** Two missed days: unusual enough to mention, with most of the week still in hand. */
    const val STALE_SEC = 2 * DAY

    /**
     * A day is enough when the tries themselves are failing. A ring that is merely out of
     * reach for a working day never gets here; a sync that cannot work is flagged a day
     * sooner, with the reason.
     */
    const val STALE_FAILING_SEC = DAY
    const val FAILING_STREAK = 4

    /** Leaves about two and a half days to act before the ring starts overwriting. */
    const val URGENT_SEC = 6 * DAY

    /** The ring's rolling history: about eight and a half days. */
    const val RETENTION_SEC = 8 * DAY + 12 * HOUR

    /** Once urgent, say so again daily — each day now costs a day of data. */
    const val URGENT_REPEAT_SEC = DAY

    private const val PREFS = "sync_health"
    private const val KEY_LAST_COMPLETE = "last_complete_unix"
    private const val KEY_FAIL_STREAK = "fail_streak"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_STATE = "last_level"
    private const val KEY_LAST_NOTICE = "last_notice_unix"

    fun levelOf(sinceSec: Long, failStreak: Int): StaleLevel = when {
        sinceSec >= URGENT_SEC -> StaleLevel.URGENT
        sinceSec >= STALE_SEC -> StaleLevel.STALE
        sinceSec >= STALE_FAILING_SEC && failStreak >= FAILING_STREAK -> StaleLevel.STALE
        else -> StaleLevel.FRESH
    }

    /**
     * Whether this level deserves a notification. A worsening always does; the urgent stage
     * also repeats daily. Nothing here ever improves the state — only a complete sync does,
     * and that resets it outright in [record].
     */
    fun evaluate(level: StaleLevel, last: StaleLevel, sinceNoticeSec: Long?): StaleAlert = when {
        level > last -> StaleAlert(notify = level, state = level)
        level == StaleLevel.URGENT && (sinceNoticeSec == null || sinceNoticeSec >= URGENT_REPEAT_SEC) ->
            StaleAlert(notify = level, state = level)
        else -> StaleAlert(notify = null, state = maxOf(level, last))
    }

    /** Title and text for a stage. Pure, so the wording is tested rather than eyeballed. */
    fun describe(level: StaleLevel, sinceSec: Long, lastError: String?): Pair<String, String> {
        val days = sinceSec / DAY
        val since = if (days >= 2) "$days days" else "${sinceSec / HOUR} hours"
        val why = lastError?.takeIf { it.isNotBlank() }?.let { "\nLast try: $it" }.orEmpty()
        return when (level) {
            StaleLevel.URGENT -> {
                val left = RETENTION_SEC - sinceSec
                val text = when {
                    left <= 0 ->
                        "The ring has probably started overwriting its oldest data. Sync now to keep the rest."
                    left < DAY ->
                        "About ${count((left / HOUR).coerceAtLeast(1), "hour")} until the ring starts " +
                            "overwriting what it hasn't handed over. Sync now to keep it."
                    else ->
                        "About ${count(left / DAY, "day")} until the ring starts overwriting what it " +
                            "hasn't handed over. Sync now to keep it."
                }
                "Ring data at risk — $since unsynced" to text + why
            }
            else -> {
                val title = if (sinceSec >= STALE_SEC) "Ring not synced for $since" else "Ring sync keeps failing"
                title to "The ring keeps about 8 days of data, then overwrites the oldest. " +
                    "Open the app with the ring nearby to sync." + why
            }
        }
    }

    private fun count(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /**
     * Note how a sync ended. A complete one is the only thing that clears a warning.
     * Cancelled runs are neither: the system stopped them, and they say nothing about
     * whether the ring can be reached.
     */
    fun record(ctx: Context, phase: SyncPhase, now: Long = System.currentTimeMillis() / 1000) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        when (phase) {
            is SyncPhase.Done -> {
                prefs.edit {
                    putLong(KEY_LAST_COMPLETE, now)
                    putInt(KEY_FAIL_STREAK, 0)
                    remove(KEY_LAST_ERROR)
                    putString(KEY_STATE, StaleLevel.FRESH.name)
                    remove(KEY_LAST_NOTICE)
                }
                ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_OVERDUE)
            }
            is SyncPhase.Failed -> prefs.edit {
                putInt(KEY_FAIL_STREAK, prefs.getInt(KEY_FAIL_STREAK, 0) + 1)
                putString(KEY_LAST_ERROR, phase.message.take(200))
            }
            else -> Unit
        }
    }

    /**
     * Weigh how long it has been, and warn if it has been too long. Safe to call often and
     * when notifications are denied.
     */
    fun check(ctx: Context, now: Long = System.currentTimeMillis() / 1000) {
        // Without a key there is nothing to sync and nothing at risk that a warning could save.
        if (!RingKeyStore(ctx).hasKey) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastComplete = if (prefs.contains(KEY_LAST_COMPLETE)) {
            prefs.getLong(KEY_LAST_COMPLETE, now)
        } else {
            seed(ctx, now).also { prefs.edit { putLong(KEY_LAST_COMPLETE, it) } }
        }
        val since = (now - lastComplete).coerceAtLeast(0)
        val last = runCatching { StaleLevel.valueOf(prefs.getString(KEY_STATE, null) ?: "") }
            .getOrDefault(StaleLevel.FRESH)
        val sinceNotice = if (prefs.contains(KEY_LAST_NOTICE)) now - prefs.getLong(KEY_LAST_NOTICE, now) else null
        val alert = evaluate(levelOf(since, prefs.getInt(KEY_FAIL_STREAK, 0)), last, sinceNotice)
        val notify = alert.notify

        val manager = ctx.getSystemService(NotificationManager::class.java)
        val channel = if (notify == StaleLevel.URGENT) CHANNEL_AT_RISK else CHANNEL_OVERDUE
        when {
            notify == null -> prefs.edit { putString(KEY_STATE, alert.state.name) }
            // Switched off on the You tab: a decision, so the stage is noted and never said.
            !AlertPrefs.enabled(ctx, AlertKind.SYNC_OVERDUE) -> prefs.edit {
                putString(KEY_STATE, alert.state.name)
                putLong(KEY_LAST_NOTICE, now)
            }
            // Blocked by Android: not a warning given, so it stays owed until it can be shown.
            manager == null || !Alerts.canPost(ctx, channel) ->
                Log.w(TAG, "unsynced for ${since / HOUR} h — $notify warning held: notifications are off")
            else -> {
                post(ctx, manager, notify, since, prefs.getString(KEY_LAST_ERROR, null))
                prefs.edit {
                    putString(KEY_STATE, alert.state.name)
                    putLong(KEY_LAST_NOTICE, now)
                }
                Log.i(TAG, "unsynced for ${since / HOUR} h — warning $notify")
            }
        }
    }

    /**
     * The first time this runs there is no record of a complete sync, so take the summary's
     * own account of when the ring was last read. Failing that, start the clock now rather
     * than warn about a past nobody recorded.
     */
    private fun seed(ctx: Context, now: Long): Long {
        val summary = SummaryRepository.get(ctx).cached() ?: return now
        val generated = summary.generatedAt ?: return now
        val fresh = summary.device?.freshHours ?: return now
        return (generated - fresh * HOUR).toLong().coerceIn(0, now)
    }

    private fun post(ctx: Context, manager: NotificationManager, level: StaleLevel, sinceSec: Long, lastError: String?) {
        ensureChannels(manager)
        val urgent = level == StaleLevel.URGENT
        val (title, text) = describe(level, sinceSec, lastError)
        val notification: Notification = NotificationCompat.Builder(ctx, if (urgent) CHANNEL_AT_RISK else CHANNEL_OVERDUE)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(if (urgent) ALERT_RED else ALERT_AMBER)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            // The Ring tab is where the Sync button and the last sync's outcome are.
            .setContentIntent(Alerts.open(ctx, Tab.RING))
            .build()
        manager.notify(NOTIF_OVERDUE, notification)
    }

    /** Two channels for the same reason the battery has them: the last warning may interrupt. */
    private fun ensureChannels(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_OVERDUE, "Ring not synced", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Shown after two days without a sync." },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AT_RISK, "Ring data at risk", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Shown from six days without a sync, when the ring is close to overwriting data." },
        )
    }
}
