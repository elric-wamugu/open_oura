package org.openoura.android.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import org.openoura.android.R
import org.openoura.android.alerts.ALERT_AMBER
import org.openoura.android.alerts.AlertKind
import org.openoura.android.alerts.AlertPrefs
import org.openoura.android.alerts.Alerts
import org.openoura.android.data.BatteryCycle
import org.openoura.android.data.Summary
import org.openoura.android.sync.AutoSync
import org.openoura.android.sync.SleepWindow
import org.openoura.android.ui.nav.Tab
import java.util.TimeZone
import kotlin.math.roundToInt

private const val TAG = "OpenOuraBattery"
private const val CHANNEL_NIGHT = "ring_battery_night"
private const val NOTIF_NIGHT = 1004
private const val DAY_MIN = 24 * 60

/**
 * What tonight looks like for the ring: where it will be when its owner usually wakes, and
 * when it empties if that is sooner. Minutes are minutes past local midnight.
 */
data class Forecast(
    val pctAtWake: Int,
    val emptyAtMin: Int?,
    val wakeMin: Int,
    val minutesToWake: Int,
    /** The local day the bedtime falls on — one warning per night, whatever the hour. */
    val night: Long,
)

/**
 * Warns before bed when the ring will not see the morning.
 *
 * The 20% warning cannot do this job. It fires whenever the level crosses the line — often
 * mid-morning, when nobody is about to sleep — and a ring at 30% in the evening is above it
 * and can still be empty by 5 am. What matters at bedtime is the level against the hours
 * left, so this projects one onto the other.
 *
 * The rate and the forecast are worked out here, not in `oura-summary`, because nothing
 * else shows them: this is a decision about when to interrupt someone, like the rest of
 * the notification policy. If a "time left" figure ever appears on a screen, the rate
 * moves into the brain so every client reads the same one.
 */
object NightForecast {

    /** How long before the usual bedtime this may speak: early enough to act on. */
    const val LEAD_MIN = 4 * 60

    /** The critical band. Below it the gauge falls away, so arriving there is not "lasting". */
    const val RESERVE_PCT = 5

    /** How many of the newest discharge runs to judge by, and how old one may be. */
    const val RUNS = 3
    const val RUN_MAX_AGE_SEC = 21 * 24 * 3600L

    private const val PREFS = "battery_alerts"
    private const val KEY_NIGHT = "last_forecast_night"

    /**
     * The drain to plan for, in points per hour: the fastest of the last few runs.
     *
     * Runs on this ring have ranged from 0.5 to 2.1 %/h depending on what it was measuring,
     * and a night is the heavy end of that. Planning on the fastest recent run costs an
     * occasional unneeded top-up; planning on the average costs a night's data.
     */
    fun drainRate(cycles: List<BatteryCycle>, now: Double): Double? =
        cycles.filter { now - it.end <= RUN_MAX_AGE_SEC && it.pctPerHour > 0 }
            .takeLast(RUNS)
            .maxOfOrNull { it.pctPerHour }

    /**
     * Tonight's forecast, or null when there is nothing to say: bedtime is more than
     * [LEAD_MIN] away (or already past), or the ring reaches the usual wake time with more
     * than [RESERVE_PCT] left.
     *
     * @param nowLocalMin minutes since the epoch in local time, so the night can be named.
     */
    fun forecast(nowLocalMin: Long, window: SleepWindow, pct: Int, ratePctPerHour: Double): Forecast? {
        if (ratePctPerHour <= 0) return null
        val minuteOfDay = (nowLocalMin % DAY_MIN).toInt()
        val toBed = (window.startMin - minuteOfDay + DAY_MIN) % DAY_MIN
        if (toBed > LEAD_MIN) return null
        val asleep = (window.endMin - window.startMin + DAY_MIN) % DAY_MIN
        val toWake = toBed + asleep
        val atWake = pct - ratePctPerHour * toWake / 60.0
        if (atWake > RESERVE_PCT) return null
        val emptyIn = (pct / ratePctPerHour * 60).roundToInt()
        return Forecast(
            pctAtWake = atWake.roundToInt().coerceAtLeast(0),
            emptyAtMin = if (emptyIn < toWake) (minuteOfDay + emptyIn) % DAY_MIN else null,
            wakeMin = window.endMin,
            minutesToWake = toWake,
            night = (nowLocalMin + toBed) / DAY_MIN,
        )
    }

    /** Title and text. Pure, so the wording is tested. */
    fun describe(f: Forecast, pct: Int): Pair<String, String> {
        val wake = clock(f.wakeMin)
        val text = if (f.emptyAtMin != null) {
            "At its recent rate it runs out around ${clock(f.emptyAtMin)}, before you usually wake " +
                "at $wake. Charge it before bed."
        } else {
            "At its recent rate it will be down to about ${f.pctAtWake}% when you usually wake at " +
                "$wake. Charge it before bed."
        }
        return "Ring won't last the night — $pct%" to text
    }

    private fun clock(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** Check a freshly computed summary. Safe to call when notifications are denied. */
    fun check(ctx: Context, summary: Summary, nowMs: Long = System.currentTimeMillis()) {
        val device = summary.device ?: return
        val manager = ctx.getSystemService(NotificationManager::class.java)
        // On the charger the night is being taken care of, and at critical the 5% warning
        // has already said "charge it now" more bluntly than a forecast would.
        if (device.batteryCharging) {
            manager?.cancel(NOTIF_NIGHT)
            return
        }
        if (BatteryAlerts.bandOf(device.batteryStatus) == BatteryLevel.CRITICAL) return
        val pct = device.batteryRestedPct ?: device.batteryPct ?: return
        val window = AutoSync.sleepWindow(summary) ?: return
        val rate = drainRate(summary.battery.cycles, nowMs / 1000.0) ?: return
        val nowLocalMin = (nowMs + TimeZone.getDefault().getOffset(nowMs)) / 60_000
        val f = forecast(nowLocalMin, window, pct, rate)
        if (f == null) {
            // Charged since the warning, or the evening has passed: it is no longer true.
            manager?.cancel(NOTIF_NIGHT)
            return
        }

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_NIGHT, -1) == f.night) return
        when {
            !AlertPrefs.enabled(ctx, AlertKind.NIGHT_FORECAST) -> prefs.edit { putLong(KEY_NIGHT, f.night) }
            // Held, not dropped: a later sync this evening may still be able to show it.
            manager == null || !Alerts.canPost(ctx, CHANNEL_NIGHT) ->
                Log.w(TAG, "ring at $pct% won't last the night — warning held: notifications are off")
            else -> {
                post(ctx, manager, f, pct)
                prefs.edit { putLong(KEY_NIGHT, f.night) }
                Log.i(TAG, "ring at $pct% won't last the night (${f.pctAtWake}% at wake, $rate %/h)")
            }
        }
    }

    private fun post(ctx: Context, manager: NotificationManager, f: Forecast, pct: Int) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NIGHT, "Ring won't last the night", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Shown before bed when the ring would run out before you wake." },
        )
        val (title, text) = describe(f, pct)
        val notification: Notification = NotificationCompat.Builder(ctx, CHANNEL_NIGHT)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(ALERT_AMBER)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // By morning it is either wrong or too late.
            .setTimeoutAfter(f.minutesToWake * 60_000L)
            .setAutoCancel(true)
            .setContentIntent(Alerts.open(ctx, Tab.RING))
            .build()
        manager.notify(NOTIF_NIGHT, notification)
    }
}
