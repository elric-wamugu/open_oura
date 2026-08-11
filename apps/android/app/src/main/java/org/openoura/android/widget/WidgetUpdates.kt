package org.openoura.android.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
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
        runCatching { VitalsWidget().updateAll(ctx) }
        runCatching { SleepWidget().updateAll(ctx) }
        runCatching { ActivityWidget().updateAll(ctx) }
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
