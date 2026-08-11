package org.openoura.android.ble

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.openoura.android.data.RingKeyStore
import uniffi.oura_core.BleWriter
import uniffi.oura_core.RingSession
import uniffi.oura_core.SyncProgressListener
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "OpenOuraBle"

/** Where a sync currently is. Mirrors the `stage` tags the Rust core emits. */
sealed interface SyncPhase {
    data object Idle : SyncPhase

    /** `stage` is the Rust tag ("auth" / "setup" / "sync") or a local one ("connecting"). */
    data class Running(
        val stage: String,
        val eventsSynced: Long = 0,
        val bytesLeft: Long = 0,
        /**
         * 0..1 through the drain, or null while it cannot be known — during connect, auth
         * and setup, and until the ring has reported a backlog to measure against.
         * Computed in `oura-link`, never here. Null means "indeterminate", not "zero".
         */
        val progress: Float? = null,
    ) : SyncPhase

    data class Done(val serial: String, val events: Long, val inserted: Long) : SyncPhase
    data class Failed(val message: String) : SyncPhase
}

/**
 * Human-readable line for a phase, shared by the progress notification and the UI.
 *
 * Kept next to [SyncPhase] rather than on the service so it stays a pure function of the
 * phase — no Android types, directly unit-testable, and impossible to accidentally couple
 * to service state.
 */
fun describeSync(phase: SyncPhase): String = when (phase) {
    is SyncPhase.Idle -> "Idle"
    is SyncPhase.Running -> buildString {
        phase.progress?.let { append("${(it * 100).roundToInt()}% · ") }
        if (phase.eventsSynced > 0) {
            append("${phase.eventsSynced} events")
        } else {
            append(phase.stage.replaceFirstChar { it.uppercase() })
        }
        if (phase.bytesLeft > 0) append(" · ${phase.bytesLeft / 1024} KB left")
    }
    is SyncPhase.Done -> "Synced ${phase.events} events, ${phase.inserted} new"
    is SyncPhase.Failed -> phase.message
}

/**
 * Drives one full sync: connect over BLE, hand the link to the Rust core, and let it run
 * the auth handshake, the app-stream setup and the event drain straight into the database.
 *
 * The Kotlin side deliberately understands none of that. Its whole job is the two-way byte
 * bridge — [BleWriter.write] out, `pushFrame` in — which is why this file mentions no
 * protocol at all beyond the shape of the callbacks.
 *
 * The drain checkpoints its cursor after every batch, so an interrupted sync resumes rather
 * than restarting: calling this again after a dropped link picks up where it stopped.
 */
suspend fun runRingSync(
    ctx: Context,
    onPhase: (SyncPhase) -> Unit,
): SyncPhase {
    val keyHex = RingKeyStore(ctx).read()
        ?: return SyncPhase.Failed(
            "No ring key stored. Import it on the Ring key screen first — the ring refuses " +
                "history without it.",
        )
    val dbPath = File(ctx.filesDir, "oura.db").absolutePath

    var transport: BleTransport? = null
    return try {
        onPhase(SyncPhase.Running("connecting"))
        val link = BleTransport.connect(ctx) { stage -> onPhase(SyncPhase.Running(stage)) }
        transport = link

        coroutineScope {
            // Rust calls BleWriter.write synchronously and fire-and-forget, from inside its
            // own runtime; it must not block there. So writes are queued and drained by one
            // pump coroutine, which both keeps that callback instant and preserves the
            // one-operation-at-a-time discipline the GATT stack requires.
            val outbound = Channel<ByteArray>(Channel.UNLIMITED)
            val writer = object : BleWriter {
                override fun write(data: ByteArray) {
                    outbound.trySend(data)
                }
            }
            val session = RingSession(writer)

            val pump = launchWritePump(this, outbound, link)
            // Started before sync(): the transport buffers frames from the moment it
            // connects, so nothing that arrived during setup is lost.
            val reader = launch {
                link.frames.collect { frame -> session.pushFrame(frame) }
            }

            try {
                val report = session.sync(
                    dbPath,
                    keyHex,
                    object : SyncProgressListener {
                        // `progress` is computed in oura-link and arrives ready to
                        // render — the same fraction the web dashboard draws. Deriving it
                        // here instead is how the two clients came to disagree.
                        override fun onProgress(
                            stage: String,
                            bytesLeft: ULong,
                            eventsSynced: UInt,
                            progress: Float?,
                        ) {
                            onPhase(
                                SyncPhase.Running(
                                    stage = stage,
                                    eventsSynced = eventsSynced.toLong(),
                                    bytesLeft = bytesLeft.toLong(),
                                    progress = progress,
                                ),
                            )
                        }
                    },
                )
                Log.i(
                    TAG,
                    "sync done: ${report.eventsSynced} events, ${report.inserted} new, " +
                        "cursor ${report.nextCursor}",
                )
                SyncPhase.Done(
                    serial = report.serial,
                    events = report.eventsSynced.toLong(),
                    inserted = report.inserted.toLong(),
                )
            } finally {
                reader.cancel()
                pump.cancel()
                outbound.close()
                session.close()
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "sync failed", t)
        SyncPhase.Failed(t.message ?: t::class.java.simpleName)
    } finally {
        transport?.close()
    }
}

/**
 * Drains queued request frames onto the link, one at a time.
 *
 * A write that fails is logged rather than thrown: the Rust side is waiting on a response
 * it will now never get, and its own quiet-window timeout produces a far more informative
 * error ("no nonce response…", "batch ended without a summary packet…") than a bare GATT
 * status raised from a coroutine the sync isn't awaiting.
 */
private fun launchWritePump(
    scope: CoroutineScope,
    outbound: Channel<ByteArray>,
    link: BleTransport,
) = scope.launch {
    for (frame in outbound) {
        runCatching { link.write(frame) }
            .onFailure { Log.w(TAG, "write of ${frame.size}B failed: ${it.message}") }
    }
}
