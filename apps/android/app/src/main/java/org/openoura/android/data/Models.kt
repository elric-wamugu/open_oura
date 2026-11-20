package org.openoura.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── the shared build_summary() JSON, decoded ─────────────────────────────────────────
// SIBLING CLIENT: the web dashboard (dashboard/web/app.js) renders the SAME summary JSON,
// and this file mirrors apps/ios/OuraApp/Models.swift field for field. A user-facing
// change here usually belongs in the web client too — see docs/clients-web-and-ios.md.
// New computed fields go in crates/oura-summary, never in a client.
//
// Every field is nullable or defaulted and the decoder ignores unknown keys, so the Rust
// core can add fields without breaking an installed app.

@Serializable
data class Trend(
    val series: List<Double> = emptyList(),
    val latest: Double? = null,
    val baseline: Double? = null,
    @SerialName("delta_pct") val deltaPct: Double? = null,
)

@Serializable
data class Vitals(val hrv: Trend = Trend(), val rhr: Trend = Trend())

/** Per-night raw signal series; each covers the whole night, so index→time is a shared axis. */
@Serializable
data class NightSeries(
    val hr: List<Double> = emptyList(),
    val hrv: List<Double> = emptyList(),
    val spo2: List<Double> = emptyList(),
    val temp: List<Double> = emptyList(),
    val motion: List<Double> = emptyList(),
)

/** Clinical sleep metrics — computed in Rust; do NOT reimplement these in Kotlin. */
@Serializable
data class SleepMetrics(
    @SerialName("asleep_min") val asleepMin: Double? = null,
    val awakenings: Int? = null,
    val cycles: Int? = null,
    @SerialName("frag_index") val fragIndex: Double? = null,
    @SerialName("rem_latency_min") val remLatencyMin: Double? = null,
    /** Sleep onset latency. The brain calls it `sol_min`; this was declared as
     *  `onset_min`, so the tile had silently never rendered. */
    @SerialName("sol_min") val onsetMin: Double? = null,
    @SerialName("waso_min") val wasoMin: Double? = null,
)

/** Mean HR/HRV within each sleep stage. Also Rust-computed. */
@Serializable
data class Autonomic(
    @SerialName("hr_deep") val hrDeep: Double? = null,
    @SerialName("hr_light") val hrLight: Double? = null,
    @SerialName("hr_rem") val hrRem: Double? = null,
    @SerialName("hrv_deep") val hrvDeep: Double? = null,
    @SerialName("hrv_light") val hrvLight: Double? = null,
    @SerialName("hrv_rem") val hrvRem: Double? = null,
)

@Serializable
data class NightRow(
    val date: String? = null,
    val ymd: String? = null,
    @SerialName("start_ds") val startDs: Long? = null,
    val start: String? = null,
    val end: String? = null,
    /** The same two instants as [start]/[end], unrounded. Exporting a night needs a real
     *  interval, and "02:33" plus [ymd] reconstructs the wrong one whenever it crosses
     *  midnight. `stagesFull` is 30-second epochs from [startUnix]. */
    @SerialName("start_unix") val startUnix: Long? = null,
    @SerialName("end_unix") val endUnix: Long? = null,
    @SerialName("in_bed_h") val inBedH: Double? = null,
    /** Whether this is the main sleep of the day it was woken from. False for a nap or a
     *  long rest on a day that also has a night; `vitals` is built without those, so a
     *  tile that wants "the latest night" must skip them too. True when the summary
     *  predates the field. */
    val main: Boolean = true,
    /** Found on this phone from motion and temperature; the ring has not declared it yet. */
    val detected: Boolean = false,
    @SerialName("hrv_ms") val hrvMs: Double? = null,
    val rhr: Double? = null,
    @SerialName("skin_temp") val skinTemp: Double? = null,
    @SerialName("spo2_mean") val spo2Mean: Double? = null,
    /** Breaths per minute over the night; null where the night was not staged on this phone. */
    @SerialName("breathing_rate") val breathingRate: BreathingRate? = null,
    @SerialName("deep_pct") val deepPct: Double? = null,
    @SerialName("light_pct") val lightPct: Double? = null,
    @SerialName("rem_pct") val remPct: Double? = null,
    @SerialName("wake_pct") val wakePct: Double? = null,
    val efficiency: Double? = null,
    /** Downsampled stage cells for the hypnogram strip. 1=deep 2=light 3=rem 4=wake. */
    val stages: List<Int>? = null,
    /** Full-resolution 30 s stages; present model-free via the ring's own hypnogram. */
    @SerialName("stages_full") val stagesFull: List<Int>? = null,
    /** Where the hypnogram came from: "model", "stager" (staged on this phone), "ring" (the
     *  ring's own phases) or "none". */
    @SerialName("stages_source") val stagesSource: String? = null,
    /** The night's Sleep Score and contributors, computed in Rust; null without stages. */
    @SerialName("sleep_score") val sleepScore: SleepScore? = null,
    val metrics: SleepMetrics? = null,
    val autonomic: Autonomic? = null,
    val series: NightSeries? = null,
) {
    val id: String get() = (date ?: "") + (start ?: "")
    val hasHypnogram: Boolean get() = (stages?.size ?: 0) > 1
}

@Serializable
data class DailyStat(
    @SerialName("active_kcal") val activeKcal: Double? = null,
    @SerialName("total_kcal") val totalKcal: Double? = null,
    val steps: Double? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("peak_hr") val peakHr: Double? = null,
)

@Serializable
data class Profile(
    val sex: String? = null,
    val age: Double? = null,
    @SerialName("height_m") val heightM: Double? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("ring_size") val ringSize: Double? = null,
)

@Serializable
data class Cardio(
    @SerialName("vascular_age") val vascularAge: Double? = null,
    @SerialName("chronological_age") val chronologicalAge: Double? = null,
    @SerialName("pwv_ms") val pwvMs: Double? = null,
    val segments: Int? = null,
)

@Serializable
data class Fitness(
    val vo2max: Double? = null,
    @SerialName("hr_max_predicted") val hrMaxPredicted: Double? = null,
)

/** One event family the ring is recording, and how much of it has been captured. */
@Serializable
data class Stream(val name: String = "", val count: Long = 0)

/** A derived metric, and whether the data to compute it is present. */
@Serializable
data class Insight(val name: String = "", val status: String = "", val why: String = "") {
    val gated: Boolean get() = status == "gated"
}

/** An on-ring capability. `feature` is the toggle id the web client can flip; Android
 *  shows these read-only, so it is carried but unused. */
@Serializable
data class Capability(val name: String = "", val on: Boolean = false, val feature: String = "")

@Serializable
data class Device(
    val serial: String? = null,
    val firmware: String? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("days_of_data") val daysOfData: Double? = null,
    val nights: Int? = null,
    @SerialName("short_periods_excluded") val shortPeriodsExcluded: Int? = null,
    @SerialName("total_events") val totalEvents: Long? = null,
    @SerialName("battery_v") val batteryV: Double? = null,
    /**
     * The level to *judge* by, and the band the brain judged. Not the same as
     * [batteryPct]: the freshest reading is taken mid-sync with the radio pulling the
     * voltage down, so `oura-summary` bands on a rested level instead. Never re-derive
     * these — the web pill renders the same two fields.
     */
    @SerialName("battery_rested_pct") val batteryRestedPct: Int? = null,
    @SerialName("battery_status") val batteryStatus: String? = null,
    /**
     * Charging, read off the ring's own battery log by `oura-summary`
     * (`battery_charge_state`), not guessed from the gauge. [batteryFullUnix] is stable
     * while the ring sits full, so it can be announced once and recognised afterwards; a
     * newer [batteryChargeStartedUnix] means a charge happened, seen or not.
     */
    @SerialName("battery_charging") val batteryCharging: Boolean = false,
    @SerialName("battery_charge_started_unix") val batteryChargeStartedUnix: Double? = null,
    @SerialName("battery_full_unix") val batteryFullUnix: Double? = null,
    @SerialName("hardware_id") val hardwareId: String? = null,
    @SerialName("api_version") val apiVersion: String? = null,
    val mac: String? = null,
    @SerialName("next_cursor") val nextCursor: Long? = null,
    /** What the ring is recording, what can be derived from it, and what is switched on. */
    val streams: List<Stream> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val measuring: List<Capability> = emptyList(),
    /** Hours since the reading was captured — drives the freshness suffix on the pill. */
    @SerialName("fresh_hours") val freshHours: Double? = null,
    val synced: String? = null,
    @SerialName("synced_hm") val syncedHm: String? = null,
)

/** One point from the ring's own `battery_level_changed` log. */
@Serializable
data class BatteryPoint(val t: Double, val pct: Int, val mv: Int)

/**
 * One discharge run, peak to trough. `projectedFullH` — what a 100 → 0 run would take at
 * this run's rate — is the figure to compare across runs, since runs start from different
 * levels.
 */
@Serializable
data class BatteryCycle(
    val start: Double,
    val end: Double,
    @SerialName("from_pct") val fromPct: Int,
    @SerialName("to_pct") val toPct: Int,
    @SerialName("from_mv") val fromMv: Int = 0,
    @SerialName("to_mv") val toMv: Int = 0,
    val hours: Double,
    @SerialName("pct_per_hour") val pctPerHour: Double,
    @SerialName("projected_full_h") val projectedFullH: Double,
)

@Serializable
data class Battery(
    val series: List<BatteryPoint> = emptyList(),
    val cycles: List<BatteryCycle> = emptyList(),
)

/**
 * A stretch the ring recorded an exercise-HR trace for.
 *
 * Deliberately unlabelled: naming a session ("running") is what Oura's AAD model does, and
 * that model isn't bundled. This only knows the ring was recording effort, so it must never
 * be rendered as a named workout.
 *
 * `hrPeak` is absent whenever it wouldn't exceed `hrMean` — see effort_sessions() in
 * oura-summary for why that can legitimately happen during movement.
 */
@Serializable
data class EffortSession(
    val start: Double,
    val end: Double,
    @SerialName("duration_min") val durationMin: Double = 0.0,
    @SerialName("hr_mean") val hrMean: Double? = null,
    @SerialName("hr_peak") val hrPeak: Double? = null,
    @SerialName("intensity_mean") val intensityMean: Double? = null,
    @SerialName("intensity_peak") val intensityPeak: Double? = null,
    val traces: Int = 0,
)

/** Accumulated sleep debt against a nightly need, computed in Rust. */
@Serializable
data class SleepDebt(
    @SerialName("debt_min") val debtMin: Double? = null,
    @SerialName("recent_shortfall_min") val recentShortfallMin: Double? = null,
    val valid: Boolean = false,
    @SerialName("need_h") val needH: Double? = null,
)

@Serializable
data class Summary(
    val digest: String? = null,
    val device: Device? = null,
    val nights: List<NightRow> = emptyList(),
    val vitals: Vitals = Vitals(),
    /** date → 96 × 15-min mean MET-above-rest */
    @SerialName("activity_profile") val activityProfile: Map<String, List<Double>> = emptyMap(),
    /** date → 96 × 15-min steps, same buckets */
    @SerialName("activity_steps") val activitySteps: Map<String, List<Double>> = emptyMap(),
    @SerialName("activity_daily") val activityDaily: Map<String, DailyStat> = emptyMap(),
    val battery: Battery = Battery(),
    val effort: List<EffortSession> = emptyList(),
    val profile: Profile? = null,
    val cardio: Cardio? = null,
    val fitness: Fitness? = null,
    @SerialName("sleep_debt") val sleepDebt: SleepDebt? = null,
    /** When the core built this, unix seconds. `device.freshHours` is relative to it. */
    @SerialName("generated_at") val generatedAt: Double? = null,
    /** Where the core's recompute went, stage → ms. Diagnostic: logged, never rendered. */
    @SerialName("timings_ms") val timingsMs: Map<String, Double> = emptyMap(),
    /** What the on-device sleep stager did in this recompute: nights reused from its cache,
     *  staged now, and left for a later recompute. Diagnostic: logged, never rendered. */
    val stager: StagerStats? = null,
    /** Activity Score by local date (04:00 to 04:00), from `oura-summary`. */
    @SerialName("activity_scores") val activityScores: Map<String, ActivityScore> = emptyMap(),
    /** Readiness Score by local date (the day a night was woken on), from `oura-summary`. */
    val readiness: Map<String, ReadinessScore> = emptyMap(),
    /** What the Sleep Score's timing was measured against. */
    val chronotype: ChronotypeInfo? = null,
    val error: String? = null,
) {
    /**
     * The calendar date you WOKE from a night. Nights are labelled by onset date, so an
     * overnight sleep crossing midnight belongs to the next day's morning. Pairing a day
     * with the sleep you woke from is what makes "night + activity" one coherent day.
     * Kept identical to the web `wakeYmd()` and the iOS `Summary.wakeYmd`.
     */
    fun wakeYmd(n: NightRow): String? {
        val ymd = n.ymd ?: return null
        val s = n.start
        val e = n.end
        if (s == null || e == null || e >= s) return ymd
        val p = ymd.split("-").mapNotNull(String::toIntOrNull)
        if (p.size != 3) return ymd
        return nextCivilDay(p[0], p[1], p[2])
    }

    /** Every date with a night (by wake date) or activity — newest first. */
    val days: List<String>
        get() = (activityProfile.keys + nights.mapNotNull { wakeYmd(it) })
            .toSortedSet(reverseOrder()).toList()

    /** Ring-detected effort falling on `day`, earliest first. Mirrors the web effortForDay(). */
    fun effortForDay(day: String): List<EffortSession> =
        effort.filter { localYmd(it.start) == day }.sortedBy { it.start }

    /** The primary sleep you woke from on `day` — the longest in-bed night beats a nap. */
    fun nightForDay(day: String): NightRow? =
        nights.filter { wakeYmd(it) == day }.maxByOrNull { it.inBedH ?: 0.0 }
            ?: nights.firstOrNull { it.ymd == null && (it.date ?: "").endsWith(day.takeLast(5)) }
}

/** Civil-calendar day increment, no TimeZone involved — the dates are already local. */
internal fun nextCivilDay(y: Int, m: Int, d: Int): String {
    val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    val dim = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var yy = y
    var mm = m
    var dd = d + 1
    if (dd > dim[mm - 1]) { dd = 1; mm++ }
    if (mm > 12) { mm = 1; yy++ }
    return "%04d-%02d-%02d".format(yy, mm, dd)
}

/** Local calendar date of a unix instant, in the device's own zone. */
internal fun localYmd(unix: Double): String {
    val d = java.time.Instant.ofEpochSecond(unix.toLong()).atZone(java.time.ZoneId.systemDefault())
    return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
}

@Serializable
data class StagerStats(val reused: Int = 0, val staged: Int = 0, val pending: Int = 0)

/** A night's Sleep Score (0..100) and its seven contributors (1..100), from `oura-summary`. */
@Serializable
data class SleepScore(
    val score: Int? = null,
    val contributors: SleepScoreContributors = SleepScoreContributors(),
)

@Serializable
data class SleepScoreContributors(
    @SerialName("total_sleep") val totalSleep: Int? = null,
    val deep: Int? = null,
    val rem: Int? = null,
    val efficiency: Int? = null,
    val latency: Int? = null,
    val restfulness: Int? = null,
    val timing: Int? = null,
)

/** The chronotype the summary scored timing with: entered ("profile"), worked out from the
 *  user's nights ("history"), or none yet ("none", with how many nights it has and needs). */
@Serializable
data class ChronotypeInfo(
    val source: String? = null,
    val type: String? = null,
    @SerialName("ideal_midpoint") val idealMidpoint: String? = null,
    val nights: Int? = null,
    val needs: Int? = null,
)

/** A day's Activity Score (0..100) and its contributors (0..100), from `oura-summary`. */
@Serializable
data class ActivityScore(
    val score: Int? = null,
    val contributors: ActivityScoreContributors = ActivityScoreContributors(),
    @SerialName("active_kcal") val activeKcal: Int? = null,
    @SerialName("target_kcal") val targetKcal: Int? = null,
)

@Serializable
data class ActivityScoreContributors(
    @SerialName("meet_daily_targets") val meetDailyTargets: Int? = null,
    @SerialName("stay_active") val stayActive: Int? = null,
    @SerialName("move_every_hour") val moveEveryHour: Int? = null,
    @SerialName("training_frequency") val trainingFrequency: Int? = null,
    @SerialName("training_volume") val trainingVolume: Int? = null,
    @SerialName("recovery_time") val recoveryTime: Int? = null,
)

/** A day's Readiness Score (0..100) and its contributors (1..100, 0 when not available). */
@Serializable
data class ReadinessScore(
    val score: Int? = null,
    val contributors: ReadinessContributors = ReadinessContributors(),
)

@Serializable
data class ReadinessContributors(
    @SerialName("resting_heart_rate") val restingHeartRate: Int? = null,
    @SerialName("hrv_balance") val hrvBalance: Int? = null,
    @SerialName("body_temperature") val bodyTemperature: Int? = null,
    @SerialName("recovery_index") val recoveryIndex: Int? = null,
    @SerialName("previous_night") val previousNight: Int? = null,
    @SerialName("sleep_balance") val sleepBalance: Int? = null,
    @SerialName("sleep_regularity") val sleepRegularity: Int? = null,
    @SerialName("previous_day_activity") val previousDayActivity: Int? = null,
    @SerialName("activity_balance") val activityBalance: Int? = null,
)

/** A night's average breathing rate and how much it varied, both in breaths per minute. */
@Serializable
data class BreathingRate(
    val rate: Double? = null,
    val variability: Double? = null,
)
