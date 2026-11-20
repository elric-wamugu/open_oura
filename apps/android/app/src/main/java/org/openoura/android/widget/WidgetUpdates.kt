package org.openoura.android.widget

import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Widget refresh, driven explicitly.
 *
 * `updatePeriodMillis` in the provider XML is set to 0 on purpose: it has a 30-minute floor,
 * the system coalesces and delays it, and it cannot be triggered on demand. Everything here
 * is the replacement — repaint when the data actually changes, plus a slow heartbeat so a
 * widget left on the home screen doesn't sit at yesterday's figures.
 */
object WidgetUpdates {

    /** Repaint every placed widget from the current snapshot. Cheap: no core call. */
    suspend fun refreshAll(ctx: Context) {
        update(ctx, StageRingWidget(), ActivityWidgetReceiver::class.java)
        update(ctx, StepsWidget(), StepsWidgetReceiver::class.java)
        update(ctx, BatteryWidget(), BatteryWidgetReceiver::class.java)
    }

    /**
     * Repaints the widgets placed from [receiver] — asking the system which those are, not
     * Glance's `updateAll`.
     *
     * `updateAll` finds a widget's placements through a map Glance saves of which receiver
     * belongs to which widget class, and it only ever adds to it. On a phone where
     * `StepsWidgetReceiver` once served the stage ring, the map still lists it under
     * `StageRingWidget` as well, so a refresh painted the steps widget twice, once with each
     * design, and whichever finished last stayed on screen (seen 2026-11-20).
     */
    private suspend fun update(ctx: Context, widget: GlanceAppWidget, receiver: Class<*>) {
        runCatching {
            val glance = GlanceAppWidgetManager(ctx)
            AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(ComponentName(ctx, receiver))
                .forEach { widget.update(ctx, glance.getGlanceIdBy(it)) }
        }
    }

    private const val PERIODIC = "widget-refresh"

    /**
     * A six-hourly nudge. The snapshot only changes when the app recomputes, so this is a
     * backstop against a stale widget rather than the main path — the real update happens
     * the moment a refresh finishes. Kept long to stay off the battery budget, which is
     * the whole reason widgets read a cached projection in the first place.
     */
    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        WidgetUpdates.refreshAll(applicationContext)
        return Result.success()
    }
}
