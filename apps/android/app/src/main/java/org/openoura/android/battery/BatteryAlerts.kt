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
import org.openoura.android.data.BatteryPoint
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
 * **The gauge cannot be read naively.** It is derived from voltage, and voltage sags under
 * radio load: a level measured during a drain reads far below the rested truth (24% → 12%
 * inside ten minutes of syncing, recovering once the radio went quiet). The freshest
 * reading is therefore always the least trustworthy one, because it was taken while the
 * ring was busy talking to this phone. [restedPct] is the answer to that.
 */
object BatteryAlerts {

    const val LOW_PCT = 10
    const val CRITICAL_PCT = 3

    /**
     * Clear back to [BatteryLevel.OK] only above this, not at [LOW_PCT] + 1. Without the
     * gap, a ring sitting at 10–11% would cross the line every few syncs and warn again
     * each time.
     */
    const val CLEAR_PCT = 15

    /** How far back to look for a reading taken while the ring was not transmitting. */
    const val RESTED_WINDOW_SEC = 30L * 60

    private const val PREFS = "battery_alerts"
    private const val KEY_STATE = "last_level"

    /**
     * Whether this reading deserves a notification, and the band to remember.
     *
     * Only a *worsening* announces itself. Between [LOW_PCT] and [CLEAR_PCT] the previous
     * band is held rather than recomputed, which is what stops a level hovering on the
     * boundary from warning over and over.
     */
    fun evaluate(pct: Int, last: BatteryLevel): BatteryAlert {
        val now = when {
            pct <= CRITICAL_PCT -> BatteryLevel.CRITICAL
            pct <= LOW_PCT -> BatteryLevel.LOW
            pct >= CLEAR_PCT -> BatteryLevel.OK
            else -> last
        }
        return BatteryAlert(notify = if (now > last) now else null, state = now)
    }

    /**
     * The highest level the ring logged in the last [RESTED_WINDOW_SEC], which is the best
     * available stand-in for a rested reading.
     *
     * A sync's own points are all depressed by the radio it is using to fetch them, so the
     * last point in the series is the sagged one by construction. Taking the maximum over a
     * window that also covers the quiet half hour before the sync rejects that dip, while
     * still tracking a genuine discharge down — at this ring's 1.3–2.2%/h a half-hour
     * window costs at most a point of lag.
     *
     * Null when the window holds nothing; the ring only logs while the level is moving, so
     * that is normal rather than an error.
     */
    fun restedPct(
        series: List<BatteryPoint>,
        nowUnix: Long,
        windowSec: Long = RESTED_WINDOW_SEC,
    ): Int? = series
        .filter { it.t >= nowUnix - windowSec }
        .maxOfOrNull { it.pct }

    /** Read the ring's level the careful way, falling back to the headline figure. */
    fun levelFrom(summary: Summary, nowUnix: Long): Int? =
        restedPct(summary.battery.series, nowUnix) ?: summary.device?.batteryPct

    /**
     * Check a freshly computed summary and warn if the ring is low. Safe to call when
     * notifications are denied — [NotificationManager] simply drops it.
     */
    fun check(ctx: Context, summary: Summary, nowUnix: Long = System.currentTimeMillis() / 1000) {
        val pct = levelFrom(summary, nowUnix) ?: return
        val alert = evaluate(pct, stored(ctx))
        store(ctx, alert.state)
        when (alert.notify) {
            null -> Log.i(TAG, "ring at $pct% (${alert.state})")
            else -> {
                Log.i(TAG, "ring at $pct% — warning ${alert.notify}")
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

    private fun post(ctx: Context, level: BatteryLevel, pct: Int) {
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
                if (critical) "Ring battery critical — $pct%" else "Ring battery low — $pct%",
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
            ).apply { description = "Shown once the ring drops to $LOW_PCT%." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CRITICAL,
                "Ring battery critical",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Shown once the ring drops to $CRITICAL_PCT%." },
        )
    }
}
