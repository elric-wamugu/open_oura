package org.openoura.android.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import org.openoura.android.MainActivity
import org.openoura.android.R
import org.openoura.android.data.Summary

private const val TAG = "OpenOuraBattery"

private const val CHANNEL_LOW = "ring_battery_low"
private const val CHANNEL_CRITICAL = "ring_battery_critical"
private const val CHANNEL_FULL = "ring_battery_full"

/** One id for every low warning, so each escalation replaces the last rather than stacking. */
private const val NOTIF_LOW = 1002
private const val NOTIF_FULL = 1003

/** The notification accent per band: the brain's amber, then red. */
private const val AMBER = 0xFFC9842B.toInt()
private const val RED = 0xFFBA1A1A.toInt()
private const val TEAL = 0xFF0D9488.toInt()

/** Ordered worst-last: a band is only worth announcing if it is worse than the last one. */
enum class BatteryLevel { OK, LOW, VERY_LOW, CRITICAL }

/**
 * What [BatteryAlerts.evaluate] concluded: what to tell the user, what to remember, and
 * whether an outstanding warning has stopped being true.
 */
data class BatteryAlert(val notify: BatteryLevel?, val state: BatteryLevel, val clearWarning: Boolean = false)

/**
 * Warns as the ring runs down — at 20%, 10% and 5% — and says when a charge has finished.
 *
 * The ring reports its level only when a sync fetches its battery log, so this is checked
 * after every sync and nowhere else; [org.openoura.android.sync.AutoSync] syncs more often
 * while the ring is charging or low, so these land close to when they happen.
 *
 * **What counts as low, charging or full is not decided here.** `oura-summary` bands the
 * level (`battery_status`) and reads charging and full off the ring's own log, and the web
 * dashboard renders the same fields. The gauge is voltage-derived and sags under radio
 * load, so all of that has to be judged on rested readings, and getting it right in two
 * languages independently is how the clients would come to disagree.
 *
 * What is left here is notification policy: when something is worth interrupting someone
 * for, and when it has already been said.
 */
object BatteryAlerts {

    /**
     * Re-arm the warnings only above this, not the moment the brain says `ok` again.
     *
     * Purely a notification concern, which is why it lives here and the thresholds do not:
     * the rested level is a rolling maximum, so a ring sitting near the 20% line can read
     * 20 then 21 then 20 again, and a client that cleared on the first `ok` would warn
     * afresh every time it did. A detected charge re-arms at once instead — see [recharged].
     */
    const val CLEAR_PCT = 25

    /** A full charge older than this is history, not news. */
    const val FULL_MAX_AGE_SEC = 6 * 3600L

    /** Two "full" times this close together are the same charge, not two. */
    const val FULL_SAME_CHARGE_SEC = 2 * 3600L

    private const val PREFS = "battery_alerts"
    private const val KEY_STATE = "last_level"
    private const val KEY_CHARGE_START = "last_charge_start"
    private const val KEY_FULL = "last_full"

    /** The brain's `battery_status` as a band. Unknown stays unknown, never `OK`. */
    fun bandOf(status: String?): BatteryLevel? = when (status) {
        "critical" -> BatteryLevel.CRITICAL
        "very_low" -> BatteryLevel.VERY_LOW
        "low" -> BatteryLevel.LOW
        "ok" -> BatteryLevel.OK
        else -> null
    }

    /**
     * Whether the ring has been charged since warnings were last weighed: it is on the
     * charger now, or the brain has seen a charge start later than the newest one already
     * accounted for. The second catches a top-up no sync happened to see while it ran.
     * On a first run there is nothing to compare against, so that is not a charge.
     */
    fun recharged(charging: Boolean, started: Double?, seenStart: Double?): Boolean =
        charging || (started != null && seenStart != null && started > seenStart)

    /**
     * Whether this band deserves a notification, and what to remember.
     *
     * Only a *worsening* announces itself. The remembered state never improves on its own —
     * a wobble from 4% back to 6% must not let the 5% warning fire twice — and clears
     * only when the ring was charged (silently re-arming at whatever band it is in now) or
     * reads `ok` comfortably above the line ([CLEAR_PCT]). A null [pct] with an `ok` band is
     * taken at face value, since there is nothing better to go on.
     */
    fun evaluate(band: BatteryLevel, pct: Int?, last: BatteryLevel, recharged: Boolean = false): BatteryAlert = when {
        recharged -> BatteryAlert(notify = null, state = band, clearWarning = true)
        band == BatteryLevel.OK && (pct == null || pct >= CLEAR_PCT) ->
            BatteryAlert(notify = null, state = BatteryLevel.OK, clearWarning = true)
        band > last -> BatteryAlert(notify = band, state = band)
        else -> BatteryAlert(notify = null, state = last)
    }

    /**
     * Whether a full charge is worth announcing: recent, and not the charge already
     * announced. The brain's full time is stable while the ring sits full, so the same
     * charge reads the same on every sync and is said once.
     */
    fun announceFull(full: Double?, lastAnnounced: Double?, now: Double): Boolean =
        full != null &&
            now - full <= FULL_MAX_AGE_SEC &&
            (lastAnnounced == null || full > lastAnnounced + FULL_SAME_CHARGE_SEC)

    /**
     * Whether battery alerts can reach the user at all: notifications on for the app, and
     * none of the battery channels switched off. The Ring tab shows a card when not.
     */
    fun canAlert(ctx: Context): Boolean {
        val compat = NotificationManagerCompat.from(ctx)
        if (!compat.areNotificationsEnabled()) return false
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return false
        return listOf(CHANNEL_LOW, CHANNEL_CRITICAL, CHANNEL_FULL).none { id ->
            manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }

    /**
     * Check a freshly computed summary: warn if the ring is running down, say so if it has
     * finished charging. Safe to call when notifications are denied.
     */
    fun check(ctx: Context, summary: Summary) {
        val device = summary.device ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() / 1000.0
        val canPost = canAlert(ctx)
        val manager = ctx.getSystemService(NotificationManager::class.java)

        val seenStart = prefs.getDouble(KEY_CHARGE_START)
        val recharged = recharged(device.batteryCharging, device.batteryChargeStartedUnix, seenStart)
        // Always remember the newest start and full seen, announced or not, so a charge from
        // before this install — or one seen while notifications were off — is not news later.
        val lastFull = prefs.getDouble(KEY_FULL)
        val full = device.batteryFullUnix
        val announce = announceFull(full, lastFull, now)
        prefs.edit {
            later(seenStart, device.batteryChargeStartedUnix)?.let { putFloat64(KEY_CHARGE_START, it) }
            later(lastFull, full)?.let { putFloat64(KEY_FULL, it) }
        }
        if (announce && canPost && manager != null) {
            manager.cancel(NOTIF_LOW)
            postFull(ctx, manager, full!!)
            Log.i(TAG, "ring fully charged")
        }

        val band = bandOf(device.batteryStatus) ?: return
        val pct = device.batteryRestedPct ?: device.batteryPct
        val alert = evaluate(band, pct, stored(ctx), recharged)
        if (alert.clearWarning || device.batteryCharging) manager?.cancel(NOTIF_LOW)
        val level = pct?.let { "$it%" } ?: "unknown"
        when {
            alert.notify == null -> {
                store(ctx, alert.state)
                Log.i(TAG, "ring at $level (${alert.state}${if (device.batteryCharging) ", charging" else ""})")
            }
            // A warning that cannot be shown is not a warning given: leave the state where it
            // was, so it fires once notifications are allowed.
            !canPost || manager == null -> Log.w(TAG, "ring at $level — ${alert.notify} warning held: notifications are off")
            else -> {
                store(ctx, alert.state)
                manager.cancel(NOTIF_FULL)
                postLow(ctx, manager, alert.notify, pct)
                Log.i(TAG, "ring at $level — warning ${alert.notify}")
            }
        }
    }

    internal fun stored(ctx: Context): BatteryLevel = runCatching {
        BatteryLevel.valueOf(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null) ?: "",
        )
    }.getOrDefault(BatteryLevel.OK)

    internal fun store(ctx: Context, level: BatteryLevel) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_STATE, level.name) }
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun postLow(ctx: Context, manager: NotificationManager, level: BatteryLevel, pct: Int?) {
        ensureChannels(manager)
        val (title, text) = when (level) {
            BatteryLevel.CRITICAL -> "Ring battery critical" to
                "Charge it now. Below 3.6 V the last of the charge goes quickly, and an " +
                "empty ring stops recording."
            BatteryLevel.VERY_LOW -> "Ring battery very low" to
                "Charge it soon — the last tenth goes quickly."
            else -> "Ring battery low" to "Charge it today to keep tonight's data."
        }
        val critical = level == BatteryLevel.CRITICAL
        val notification: Notification = NotificationCompat.Builder(ctx, if (critical) CHANNEL_CRITICAL else CHANNEL_LOW)
            .setContentTitle(title + (pct?.let { " — $it%" } ?: ""))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(if (level == BatteryLevel.LOW) AMBER else RED)
            .setPriority(if (critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        manager.notify(NOTIF_LOW, notification)
    }

    private fun postFull(ctx: Context, manager: NotificationManager, fullUnix: Double) {
        ensureChannels(manager)
        val notification: Notification = NotificationCompat.Builder(ctx, CHANNEL_FULL)
            .setContentTitle("Ring fully charged")
            .setContentText("Put it back on to keep recording.")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(TEAL)
            // Stamped with when it reached 100%, which can be a while before the sync that
            // noticed — the shade then says "2:14 pm", not "now".
            .setWhen((fullUnix * 1000).toLong())
            .setShowWhen(true)
            .setTimeoutAfter(FULL_MAX_AGE_SEC * 1000)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        manager.notify(NOTIF_FULL, notification)
    }

    /**
     * Three channels: a heads-up at 20% or 10% every few days should be ordinary, a 5%
     * warning has earned the right to interrupt, and "charged" is good news that someone
     * may want quieter still. Separate channels let each be tuned or silenced on its own.
     */
    private fun ensureChannels(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LOW, "Ring battery low", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Shown at 20% and again at 10%." },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CRITICAL, "Ring battery critical", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Shown at 5%, when the ring is nearly empty." },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FULL, "Ring charged", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Shown when a charge reaches 100%." },
        )
    }
}

private fun later(a: Double?, b: Double?): Double? = listOfNotNull(a, b).maxOrNull()

private fun android.content.SharedPreferences.getDouble(key: String): Double? =
    if (contains(key)) Double.fromBits(getLong(key, 0L)) else null

private fun android.content.SharedPreferences.Editor.putFloat64(key: String, value: Double) {
    putLong(key, value.toRawBits())
}
