package org.openoura.android.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openoura.android.MainActivity
import org.openoura.android.R
import org.openoura.android.data.SummaryRepository

private const val TAG = "OpenOuraBle"
private const val CHANNEL_ID = "ring_sync"
private const val NOTIF_ID = 1001

/**
 * Runs a ring sync as a foreground service.
 *
 * A drain can take many minutes over BLE, and this ring answers only the legacy
 * `GetEvent` path at 255 events per batch, so a large catch-up is a long run of round
 * trips. A plain coroutine in the Activity would be killed the moment the screen went off
 * or the app was backgrounded; a foreground service of type `connectedDevice` is what keeps
 * Doze off it. On API 34 that type is mandatory — `startForeground` throws without it.
 *
 * Progress is published through a process-wide [StateFlow] rather than a bound service.
 * This is a single-activity app and the UI only ever observes, so a binder would be
 * ceremony; the trade is that the flow outlives any one Activity, which is exactly why the
 * terminal states are left in place for a returning UI to read.
 */
class RingSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_LOW: an ongoing progress notification that must not make a sound
        // every time the stage changes.
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Ring sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while syncing history from the ring." },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(notification("Starting…"))

        if (running) {
            Log.i(TAG, "sync already in flight; ignoring start")
            return START_NOT_STICKY
        }
        running = true

        scope.launch {
            val result = runRingSync(applicationContext) { phase ->
                _phase.value = phase
                if (phase is SyncPhase.Running) notify(describe(phase))
            }
            _phase.value = result

            if (result is SyncPhase.Done) {
                // Only now is the database newer than the cached summary. recompute()
                // rewrites the widget projection and repaints the widgets itself.
                notify("Rebuilding the summary…")
                runCatching { SummaryRepository.get(applicationContext).recompute() }
                    .onFailure { Log.e(TAG, "post-sync recompute failed", it) }
            }

            running = false
            stopSelf()
        }
        // START_NOT_STICKY: if the process dies mid-sync, silently restarting a BLE drain
        // the user cannot see is worse than doing nothing. The cursor is checkpointed, so
        // the next manual sync resumes anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        running = false
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Syncing from the ring")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        @Volatile
        private var running = false

        private val _phase = MutableStateFlow<SyncPhase>(SyncPhase.Idle)
        val phase: StateFlow<SyncPhase> = _phase.asStateFlow()

        val isRunning: Boolean get() = running

        fun start(ctx: Context) {
            _phase.value = SyncPhase.Running("starting")
            ctx.startForegroundService(Intent(ctx, RingSyncService::class.java))
        }

        /** Human-readable line for a phase, shared by the notification and the UI. */
        fun describe(phase: SyncPhase): String = when (phase) {
            is SyncPhase.Idle -> "Idle"
            is SyncPhase.Running -> when {
                phase.eventsSynced > 0 && phase.bytesLeft > 0 ->
                    "${phase.eventsSynced} events · ${phase.bytesLeft / 1024} KB left"
                phase.eventsSynced > 0 -> "${phase.eventsSynced} events"
                else -> phase.stage.replaceFirstChar { it.uppercase() }
            }
            is SyncPhase.Done ->
                "Synced ${phase.events} events, ${phase.inserted} new"
            is SyncPhase.Failed -> phase.message
        }
    }
}
