package org.openoura.android.data

import android.content.Context
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
    val efficiency: Int? = null,
    val spo2: Int? = null,
    val deepPct: Int? = null,
    val lightPct: Int? = null,
    val remPct: Int? = null,
    val wakePct: Int? = null,
    // today
    val steps: Int? = null,
    val peakHr: Int? = null,
    val batteryPct: Int? = null,
)

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
            efficiency = night?.efficiency?.roundToInt(),
            spo2 = s.nights.firstOrNull { it.spo2Mean != null }?.spo2Mean?.roundToInt(),
            deepPct = night?.deepPct?.roundToInt(),
            lightPct = night?.lightPct?.roundToInt(),
            remPct = night?.remPct?.roundToInt(),
            wakePct = night?.wakePct?.roundToInt(),
            steps = daily?.steps?.roundToInt(),
            peakHr = daily?.peakHr?.roundToInt(),
            batteryPct = s.device?.batteryPct,
        )
    }
}
