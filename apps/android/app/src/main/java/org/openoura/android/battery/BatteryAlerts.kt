package org.openoura.android.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import org.openoura.android.MainActivity
import org.openoura.android.R
import org.openoura.android.data.Summary

private const val TAG = "OpenOuraBattery"

private const val CHANNEL_LOW = "ring_battery_low"
private const val CHANNEL_CRITICAL = "ring_battery_critical"

/** One id for both, so a critical warning replaces the low one rather than stacking. */
private const val NOTIF_ID = 1002

/** Ordered worst-last: a band is only worth announcing if it is worse than the last one. */
enum class BatteryLevel { OK, LOW, CRITICAL }

/** What [BatteryAlerts.evaluate] concluded: what to tell the user, and what to remember. */
data class BatteryAlert(val notify: BatteryLevel?, val state: BatteryLevel)

/**
 * Warns when the ring is running out of charge.
 *
 * The ring reports its level only when a sync fetches its `battery_level_changed` log, so
 * this is checked after every sync and nowhere else — there is no other moment when the
 * number can have changed.
 *
 * **What counts as low is not decided here.** `oura-summary` bands the level and ships
 * `battery_status`, which the web dashboard's pill renders from too. That matters because
 * the gauge is voltage-derived and sags under radio load — a level measured during a drain
 * reads far below the rested truth — so the band is judged on a rested reading rather than
 * the freshest one, and getting that right in two languages independently is how the two
 * clients would come to disagree.
 *
 * What is left here is notification policy: when a band is worth interrupting someone for,
 * and when it has already been said.
 */
object BatteryAlerts {

    /**
     * Re-arm the warning only above this, not the moment the brain says `ok` again.
     *
     * Purely a notification concern, which is why it lives here and the thresholds do not:
     * the rested level is a rolling maximum, so a ring sitting near the line can read 10
     * then 11 then 10 again, and a client that cleared on the first `ok` would warn afresh
     * every time it did.
     */
    const val CLEAR_PCT = 15

    private const val PREFS = "battery_alerts"
    private const val KEY_STATE = "last_level"

    /** The brain's `battery_status` as a band. Unknown stays unknown, never `OK`. */
    fun bandOf(status: String?): BatteryLevel? = when (status) {
        "critical" -> BatteryLevel.CRITICAL
        "low" -> BatteryLevel.LOW
        "ok" -> BatteryLevel.OK
        else -> null
    }

    /**
     * Whether this band deserves a notification, and what to remember.
     *
     * Only a *worsening* announces itself, and `ok` clears the memory only once [pct] is
     * clearly above the line — see [CLEAR_PCT]. A null [pct] with an `ok` band is taken at
     * face value, since there is nothing better to go on.
     */
    fun evaluate(band: BatteryLevel, pct: Int?, last: BatteryLevel): BatteryAlert {
        val now = when {
            band != BatteryLevel.OK -> band
            pct != null && pct < CLEAR_PCT -> last
            else -> BatteryLevel.OK
        }
        return BatteryAlert(notify = if (now > last) now else null, state = now)
    }

    /**
     * Check a freshly computed summary and warn if the ring is low. Safe to call when
     * notifications are denied — [NotificationManager] simply drops it.
     */
    fun check(ctx: Context, summary: Summary) {
        val device = summary.device ?: return
        val band = bandOf(device.batteryStatus) ?: return
        val pct = device.batteryRestedPct ?: device.batteryPct
        val alert = evaluate(band, pct, stored(ctx))
        store(ctx, alert.state)
        val level = pct?.let { "$it%" } ?: "unknown"
        when (alert.notify) {
            null -> Log.i(TAG, "ring at $level (${alert.state})")
            else -> {
                Log.i(TAG, "ring at $level — warning ${alert.notify}")
                post(ctx, alert.notify, pct)
            }
        }
    }

    internal fun stored(ctx: Context): BatteryLevel = runCatching {
        BatteryLevel.valueOf(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATE, null) ?: "",
        )
    }.getOrDefault(BatteryLevel.OK)

    internal fun store(ctx: Context, level: BatteryLevel) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, level.name).apply()
    }

    private fun post(ctx: Context, level: BatteryLevel, pct: Int?) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(manager)

        val critical = level == BatteryLevel.CRITICAL
        val open = android.app.PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(
            ctx,
            if (critical) CHANNEL_CRITICAL else CHANNEL_LOW,
        )
            .setContentTitle(
                buildString {
                    append(if (critical) "Ring battery critical" else "Ring battery low")
                    pct?.let { append(" — $it%") }
                },
            )
            .setContentText(
                if (critical) {
                    "Charge it now. Below 3.6 V the last of the charge goes quickly, and an " +
                        "empty ring stops recording."
                } else {
                    "Charge it soon to keep the night's data."
                },
            )
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setPriority(if (critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(NOTIF_ID, notification)
    }

    /**
     * Two channels, not one: a 10% heads-up every few days should be quiet, while a 3%
     * warning has earned the right to interrupt. Separate channels also let the user tune
     * or silence each without losing the other.
     */
    private fun ensureChannels(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LOW,
                "Ring battery low",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Shown once the ring is running low." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CRITICAL,
                "Ring battery critical",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Shown once the ring is nearly empty." },
        )
    }
}
