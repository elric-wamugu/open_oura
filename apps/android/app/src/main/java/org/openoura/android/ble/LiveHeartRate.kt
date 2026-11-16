package org.openoura.android.ble

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.oura_core.RingSession
import kotlin.math.roundToInt

/** A finished measurement. */
data class HeartReading(
    val bpm: Int,
    val low: Int,
    val high: Int,
    val beats: Int,
    val seconds: Int,
    val atUnix: Long,
)

/** Where a heart-rate measurement is. */
sealed interface HeartState {
    data object Idle : HeartState

    /** Finding the ring and authenticating; [stage] is the bridge's own tag. */
    data class Connecting(val stage: String) : HeartState

    /** [bpm] is null until the first beats arrive; [trace] is what has been shown so far. */
    data class Measuring(val bpm: Int?, val trace: List<Int>, val elapsedSec: Int, val totalSec: Int) : HeartState
    data class Done(val reading: HeartReading) : HeartState
    data class Failed(val message: String) : HeartState
}

/**
 * Measuring heart rate now: the ring's live mode, for half a minute.
 *
 * The ring reports about one figure a second, each worked out from a single beat's
 * interval, so the raw stream jumps about — 61, 66, 58 — and now and then contains a beat it mistimed. What
 * is shown is therefore the median of the last few beats, and the result is built from
 * those smoothed values, not from the raw ones.
 *
 * Process-wide state, like a sync's, for the same reason: the measurement outlives the
 * screen that started it for a moment (it has to put the ring back in its ordinary mode),
 * and the Today card wants the outcome too.
 *
 * Nothing here goes into the database. A spot reading is not part of the ring's history;
 * only the most recent one is kept, in preferences, for the card to show.
 */
object LiveHeartRate {

    const val SECONDS = 30

    /** Beats the median is taken over: enough to drop a mistimed one, few enough to follow a change. */
    const val SMOOTH_OVER = 5

    /** Fewer than this in half a minute is not a measurement. */
    const val MIN_BEATS = 8

    private const val PREFS = "heart_rate"

    private val _state = MutableStateFlow<HeartState>(HeartState.Idle)
    val state: StateFlow<HeartState> = _state.asStateFlow()

    /**
     * Its own scope, on the IO dispatcher, for two separate reasons.
     *
     * Off the main thread because the BLE bridge waits for GATT callbacks that Android
     * delivers on the main thread: driven from there, the first write waited for a reply
     * that could not be delivered until it stopped waiting, and the app froze. Every other
     * user of the bridge already runs on IO for this reason.
     *
     * Its own scope, not the Activity's, because a measurement has to finish what it
     * started. Rotating the phone would cancel a lifecycle-bound job mid-measurement and
     * leave the ring in live mode.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var session: RingSession? = null

    @Volatile
    private var stopped = false

    /** True from the tap until the ring has been put back in its ordinary mode. */
    val isRunning: Boolean get() = job?.isActive == true

    /** The median of the last [SMOOTH_OVER] beats, or null with none. */
    fun smoothed(beats: List<Int>): Int? {
        val last = beats.takeLast(SMOOTH_OVER).sorted()
        if (last.isEmpty()) return null
        return if (last.size % 2 == 1) last[last.size / 2] else ((last[last.size / 2 - 1] + last[last.size / 2]) / 2.0).roundToInt()
    }

    /**
     * What a run of beats amounts to: the average, low and high of the smoothed series, or
     * null when there were too few to say. Pure, so the arithmetic is tested.
     */
    fun summarise(beats: List<Int>, seconds: Int, atUnix: Long): HeartReading? {
        if (beats.size < MIN_BEATS) return null
        val series = beats.indices.mapNotNull { smoothed(beats.subList(0, it + 1)) }
            // The first few medians are taken over one or two beats: not smoothed yet.
            .drop(SMOOTH_OVER - 1)
        if (series.isEmpty()) return null
        return HeartReading(
            bpm = series.average().roundToInt(),
            low = series.min(),
            high = series.max(),
            beats = beats.size,
            seconds = seconds,
            atUnix = atUnix,
        )
    }

    /** "5 bpm above your resting rate", or null without a resting rate to compare with. */
    fun againstResting(bpm: Int, resting: Int?): String? = when {
        resting == null -> null
        bpm == resting -> "Level with your resting rate"
        else -> "${kotlin.math.abs(bpm - resting)} bpm ${if (bpm > resting) "above" else "below"} your resting rate"
    }

    /** "just now", "12 min ago", "2 h ago", "3 days ago". */
    fun ago(atUnix: Long, nowUnix: Long): String {
        val s = (nowUnix - atUnix).coerceAtLeast(0)
        return when {
            s < 90 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 48 * 3600 -> "${s / 3600} h ago"
            else -> "${s / 86_400} days ago"
        }
    }

    fun last(ctx: Context): HeartReading? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("bpm")) return null
        return HeartReading(
            bpm = p.getInt("bpm", 0),
            low = p.getInt("low", 0),
            high = p.getInt("high", 0),
            beats = p.getInt("beats", 0),
            seconds = p.getInt("seconds", SECONDS),
            atUnix = p.getLong("at", 0),
        )
    }

    private fun save(ctx: Context, r: HeartReading) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt("bpm", r.bpm)
            putInt("low", r.low)
            putInt("high", r.high)
            putInt("beats", r.beats)
            putInt("seconds", r.seconds)
            putLong("at", r.atUnix)
        }
    }

    /** Begin a measurement. Does nothing if one is running; says why if it cannot start. */
    fun start(ctx: Context) {
        if (isRunning) return
        val app = ctx.applicationContext
        when {
            // One link to the ring at a time: a drain and a live stream would fight over it.
            RingSyncService.isRunning -> {
                _state.value = HeartState.Failed("A sync is running. Try again when it finishes.")
                return
            }
            !BlePermissions.granted(app) -> {
                _state.value = HeartState.Failed(
                    "Bluetooth permission is needed to reach the ring. Sync once from the Ring tab to grant it.",
                )
                return
            }
        }
        stopped = false
        session = null
        _state.value = HeartState.Connecting("connecting")
        job = scope.launch {
            val beats = java.util.Collections.synchronizedList(mutableListOf<Int>())
            var started = 0L
            var ticker: Job? = null
            val result = runLiveHeartRate(
                app,
                SECONDS,
                onStage = { stage ->
                    if (stage == "measuring") {
                        started = System.currentTimeMillis()
                        _state.value = HeartState.Measuring(null, emptyList(), 0, SECONDS)
                        // The countdown runs on its own clock: beats may not be arriving.
                        ticker = launch {
                            while (true) {
                                delay(250)
                                val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt().coerceAtMost(SECONDS)
                                _state.update { (it as? HeartState.Measuring)?.copy(elapsedSec = elapsed) ?: it }
                            }
                        }
                    } else if (started == 0L) {
                        _state.value = HeartState.Connecting(stage)
                    }
                },
                onSession = {
                    session = it
                    // Stop was pressed while still connecting.
                    if (stopped) it.stopHeartRate()
                },
                onBeat = { bpm ->
                    beats.add(bpm)
                    val shown = smoothed(beats.toList())
                    _state.update { s ->
                        (s as? HeartState.Measuring)?.copy(bpm = shown, trace = if (shown != null) s.trace + shown else s.trace) ?: s
                    }
                },
            )
            ticker?.cancel()
            session = null
            val seconds = if (started > 0) ((System.currentTimeMillis() - started) / 1000).toInt().coerceIn(1, SECONDS) else 0
            val reading = summarise(beats.toList(), seconds, System.currentTimeMillis() / 1000)
            _state.value = when {
                result is SyncPhase.Failed -> HeartState.Failed(result.message)
                reading != null -> {
                    save(app, reading)
                    HeartState.Done(reading)
                }
                // Stopped before there was anything to report: back to the start, no complaint.
                stopped -> HeartState.Idle
                else -> HeartState.Failed(
                    "Couldn't get a reading. Make sure the ring is on your finger, snug, and keep your hand still.",
                )
            }
        }
    }

    /**
     * End the measurement. Asks the core to stop rather than cancelling it, so the ring is
     * put back in its ordinary mode; the state settles a moment later.
     */
    fun stop() {
        stopped = true
        val live = session
        if (live != null) {
            live.stopHeartRate()
        } else {
            // Still looking for the ring: nothing has been asked of it yet, so there is
            // nothing to put back and the search can simply be abandoned.
            job?.cancel()
            _state.value = HeartState.Idle
        }
    }

    /** Clear a finished or failed measurement, e.g. on leaving the screen. */
    fun acknowledge() {
        if (!isRunning && _state.value !is HeartState.Done) _state.value = HeartState.Idle
    }
}
