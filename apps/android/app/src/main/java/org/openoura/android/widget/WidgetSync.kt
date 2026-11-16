package org.openoura.android.widget

import android.content.Context
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import android.util.Log
import org.openoura.android.ble.BlePermissions
import org.openoura.android.ble.RingSyncService
import org.openoura.android.data.RingKeyStore
import org.openoura.android.data.WidgetBus
import org.openoura.android.sync.AutoSync
import org.openoura.android.sync.SyncTrigger

/**
 * The widget's refresh button: sync with the ring and redraw, without opening the app.
 *
 * The tap starts a sync (see [SyncFromWidget] for which kind). Whatever runs it recomputes
 * and repaints the widgets when it finishes, so all this has to do is ask, and say that it
 * has asked.
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

/** How a tap on the refresh button gets its sync run. */
enum class WidgetSyncPath { ALREADY_RUNNING, FOREGROUND, BACKGROUND }

/**
 * Which way to run it. Pure, so the choice is tested.
 *
 * The foreground service is the fast way, and a widget tap is one of the few moments
 * Android lets an app start one from the background. It needs what a sync in the app
 * needs — the Bluetooth permissions and a ring key — and without them the background
 * worker is asked instead, which knows how to do nothing quietly.
 */
fun widgetSyncPath(serviceRunning: Boolean, bluetoothGranted: Boolean, hasKey: Boolean): WidgetSyncPath = when {
    serviceRunning -> WidgetSyncPath.ALREADY_RUNNING
    bluetoothGranted && hasKey -> WidgetSyncPath.FOREGROUND
    else -> WidgetSyncPath.BACKGROUND
}

/**
 * Runs when the refresh button is tapped. Created by Glance by class name.
 *
 * The sync runs in [RingSyncService], the same foreground service the app's own Sync
 * button uses, not in the background worker. Measured on a Pixel 9a over one database:
 * the summary recompute that follows a sync took 17.8–19.5 s in the worker and takes about
 * 3 s in the app, because a process with nothing in the foreground is confined to the
 * small cores and a small share of them. A foreground service is not. The cost is the
 * service's notification while it runs, which for something the user just asked for is
 * also the honest thing to show.
 */
class SyncFromWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetSync.start(context)
        // Repaint first, so the tap is answered at once rather than when the ring replies.
        WidgetUpdates.refreshAll(context)
        val path = widgetSyncPath(
            serviceRunning = RingSyncService.isRunning,
            bluetoothGranted = BlePermissions.granted(context),
            hasKey = RingKeyStore(context).hasKey,
        )
        when (path) {
            // The sync already under way will stop the icon turning when it ends.
            WidgetSyncPath.ALREADY_RUNNING -> Unit
            WidgetSyncPath.FOREGROUND -> runCatching { RingSyncService.start(context) }.onFailure {
                // Android refused the start (it is its call to make): slower beats not at all.
                Log.w(TAG, "widget sync: foreground start refused, using the background worker", it)
                RingSyncService.acknowledgeRefusedStart()
                AutoSync.requestSync(context, SyncTrigger.WIDGET)
            }
            WidgetSyncPath.BACKGROUND -> AutoSync.requestSync(context, SyncTrigger.WIDGET)
        }
        Log.i(TAG, "widget sync: $path")
    }
}

private const val TAG = "OpenOuraSync"
