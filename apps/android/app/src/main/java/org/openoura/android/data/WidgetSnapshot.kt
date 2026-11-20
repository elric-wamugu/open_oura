package org.openoura.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

/**
 * The handful of figures the home-screen widgets show.
 *
 * Deliberately separate from `summary.json`, and deliberately tiny. `build_summary` takes
 * seconds and its output is ~200 KB; a widget update runs in a constrained, short-lived
 * context and may fire while the app is not running at all. So widgets read this and never
 * call the Rust core — the app writes it after each successful refresh.
 *
 * Every field is nullable: a widget must render something sensible before the first sync.
 */
@Serializable
data class WidgetSnapshot(
    val updated: Long = 0L,
    // vitals
    val hrv: Int? = null,
    val hrvBaseline: Int? = null,
    val rhr: Int? = null,
    val rhrBaseline: Int? = null,
    // last night
    val nightLabel: String? = null,
    val sleepHours: Double? = null,
    /** Time actually asleep, where the night was scored. [sleepHours] is time in bed. */
    val asleepHours: Double? = null,
    val efficiency: Int? = null,
    val spo2: Int? = null,
    val deepPct: Int? = null,
    val lightPct: Int? = null,
    val remPct: Int? = null,
    val wakePct: Int? = null,
    // today
    val steps: Int? = null,
    /** Walking distance, from the brain's `distance_m`. */
    val distanceKm: Double? = null,
    val peakHr: Int? = null,
    val batteryPct: Int? = null,
    /** The brain's band, so the widget colours the level like the app does. */
    val batteryStatus: String? = null,
    /** Today's Readiness Score, 0..100; null until the night you woke from is scored. */
    val readiness: Int? = null,
    /** Last night's Sleep Score, 0..100, for the same night as [asleepHours]. */
    val sleepScore: Int? = null,
)

/**
 * Tells widgets that are on screen right now that what they show has changed.
 *
 * Glance keeps a widget's composition alive for a while after it is drawn or tapped, and
 * asking it to update only recomposes what is already there — it does not run the code
 * that loaded the data again. A widget that read its snapshot once, up front, therefore
 * went on showing it: tap refresh, the ring syncs, and the figures stay as they were. So
 * the widgets watch this counter and re-read whenever it moves. It is in-process state,
 * which is all that is needed: the sync and the widget's composition share the process.
 */
object WidgetBus {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun changed() = _version.update { it + 1 }
}

object WidgetStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(ctx: Context) = File(ctx.filesDir, "widget.json")

    fun read(ctx: Context): WidgetSnapshot = runCatching {
        val f = file(ctx)
        if (!f.exists()) WidgetSnapshot() else json.decodeFromString<WidgetSnapshot>(f.readText())
    }.getOrElse { WidgetSnapshot() }

    fun write(ctx: Context, snap: WidgetSnapshot) {
        runCatching {
            file(ctx).writeText(json.encodeToString(WidgetSnapshot.serializer(), snap))
        }
        WidgetBus.changed()
    }

    /** Project a full summary down to what the widgets need. */
    fun from(s: Summary): WidgetSnapshot {
        // The newest day with a scored night — today usually has activity but no night yet,
        // since the night you woke from is dated yesterday.
        val nightDay = s.days.firstOrNull { s.nightForDay(it) != null }
        val night = nightDay?.let { s.nightForDay(it) }
        // Activity, by contrast, should be today's.
        val today = s.days.firstOrNull()
        val daily = today?.let { s.activityDaily[it] }
        return WidgetSnapshot(
            updated = System.currentTimeMillis() / 1000,
            hrv = s.vitals.hrv.latest?.roundToInt(),
            hrvBaseline = s.vitals.hrv.baseline?.roundToInt(),
            rhr = s.vitals.rhr.latest?.roundToInt(),
            rhrBaseline = s.vitals.rhr.baseline?.roundToInt(),
            nightLabel = nightDay,
            sleepHours = night?.inBedH,
            asleepHours = night?.metrics?.asleepMin?.let { it / 60.0 },
            efficiency = night?.efficiency?.roundToInt(),
            spo2 = s.nights.firstOrNull { it.main && it.spo2Mean != null }?.spo2Mean?.roundToInt(),
            deepPct = night?.deepPct?.roundToInt(),
            lightPct = night?.lightPct?.roundToInt(),
            remPct = night?.remPct?.roundToInt(),
            wakePct = night?.wakePct?.roundToInt(),
            steps = daily?.steps?.roundToInt(),
            distanceKm = daily?.distanceM?.let { it / 1000.0 },
            peakHr = daily?.peakHr?.roundToInt(),
            batteryPct = s.device?.batteryPct,
            batteryStatus = s.device?.batteryStatus,
            // Keyed the way Today's scores row reads it: the day the night was woken on.
            readiness = today?.let { s.readiness[it]?.score },
            sleepScore = night?.sleepScore?.score,
        )
    }
}
