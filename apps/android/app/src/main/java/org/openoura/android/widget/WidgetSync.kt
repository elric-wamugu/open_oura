package org.openoura.android.widget

import android.content.Context
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import org.openoura.android.data.WidgetBus
import org.openoura.android.sync.AutoSync
import org.openoura.android.sync.SyncTrigger

/**
 * The widget's refresh button: sync with the ring and redraw, without opening the app.
 *
 * The tap queues the same background sync the app-open trigger uses ([AutoSync]), minus
 * its 3-hour floor. The worker recomputes and repaints the widgets when it finishes, so
 * all this has to do is ask, and say that it has asked.
 *
 * "Syncing" (the icon turning) is a timestamp, not a flag, so that it cannot stick: a worker the system
 * never ran, or a process killed mid-sync, would otherwise leave it on the home screen
 * for good. The worker clears it when it ends; failing that it lapses on its own.
 */
object WidgetSync {

    /** Longer than a scan for an absent ring (25 s) plus a recompute; short enough to forgive. */
    const val SHOWN_FOR_SEC = 90L

    private const val PREFS = "widget_sync"
    private const val KEY_REQUESTED = "requested_unix"

    /** Whether a tap at [requestedUnix] should still show as syncing at [nowUnix]. */
    fun isShowing(requestedUnix: Long, nowUnix: Long): Boolean =
        requestedUnix > 0 && nowUnix - requestedUnix in 0 until SHOWN_FOR_SEC

    fun isSyncing(ctx: Context, nowUnix: Long = System.currentTimeMillis() / 1000): Boolean =
        isShowing(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_REQUESTED, 0), nowUnix)

    internal fun start(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putLong(KEY_REQUESTED, System.currentTimeMillis() / 1000) }
        WidgetBus.changed()
    }

    /** Stop showing it. True when it was up, so the caller knows to repaint. */
    fun finish(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_REQUESTED)) return false
        prefs.edit { remove(KEY_REQUESTED) }
        WidgetBus.changed()
        return true
    }
}

/** Runs when the refresh button is tapped. Created by Glance by class name. */
class SyncFromWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetSync.start(context)
        // Repaint first, so the tap is answered at once rather than when the ring replies.
        WidgetUpdates.refreshAll(context)
        AutoSync.requestSync(context, SyncTrigger.WIDGET)
    }
}
