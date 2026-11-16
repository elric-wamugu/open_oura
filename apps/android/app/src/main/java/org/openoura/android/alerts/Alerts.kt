package org.openoura.android.alerts

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import org.openoura.android.MainActivity
import org.openoura.android.ui.nav.Tab

/** Notification accents: the brain's amber and red, and the app's teal for good news. */
internal const val ALERT_AMBER = 0xFFC9842B.toInt()
internal const val ALERT_RED = 0xFFBA1A1A.toInt()
internal const val ALERT_TEAL = 0xFF0D9488.toInt()

/**
 * Everything the app may notify about, each with its own switch on the You tab.
 *
 * A switch is the app's own "don't tell me", on top of Android's: the notification
 * permission and the channel still have to allow it. The two are kept apart because they
 * answer different questions — a switched-off alert is a decision and is honoured silently,
 * while a blocked notification is usually an accident and is surfaced.
 */
enum class AlertKind(val key: String, val title: String, val detail: String) {
    BATTERY_LOW("battery_low", "Ring battery low", "At 20%, 10% and 5%"),
    BATTERY_FULL("battery_full", "Fully charged", "When a charge reaches 100%"),
    NIGHT_FORECAST(
        "night_forecast",
        "Won't last the night",
        "Before bed, when the ring would run out before you usually wake",
    ),
    SYNC_OVERDUE(
        "sync_overdue",
        "Sync overdue",
        "After 2 days without a sync, and before the ring starts overwriting data",
    ),
    BACKUP_FAILED(
        "backup_failed",
        "Automatic backup failing",
        "When the daily backup can't be written",
    ),
}

object AlertPrefs {
    private const val PREFS = "alerts"

    /** On unless switched off: an alert nobody chose to silence should reach them. */
    fun enabled(ctx: Context, kind: AlertKind): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(kind.key, true)

    fun set(ctx: Context, kind: AlertKind, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(kind.key, on) }
    }

    fun all(ctx: Context): Map<AlertKind, Boolean> = AlertKind.entries.associateWith { enabled(ctx, it) }
}

object Alerts {

    /** Whether Android lets this app notify at all. */
    fun allowed(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /**
     * Whether a notification on one of [channels] would actually be shown: notifications on
     * for the app, and none of those channels switched off in Android's settings. A channel
     * that does not exist yet has not been switched off.
     */
    fun canPost(ctx: Context, vararg channels: String): Boolean {
        if (!allowed(ctx)) return false
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return false
        return channels.none { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
    }

    /**
     * Tapping an alert opens the app on [tab].
     *
     * CLEAR_TOP with SINGLE_TOP hands the intent to the Activity already in the task
     * (`onNewIntent`) rather than stacking a second copy on top of it. The request code is
     * the tab, because extras alone do not make two PendingIntents different.
     */
    fun open(ctx: Context, tab: Tab): PendingIntent = PendingIntent.getActivity(
        ctx,
        tab.ordinal + 1,
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, tab.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
