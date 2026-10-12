package org.openoura.android.ui.preview

import org.openoura.android.data.Capability
import org.openoura.android.data.DailyStat
import org.openoura.android.data.Device
import org.openoura.android.data.Insight
import org.openoura.android.data.NightRow
import org.openoura.android.data.Stream
import org.openoura.android.data.Summary
import org.openoura.android.data.Trend
import org.openoura.android.data.Vitals
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * A plausible day of data for @Preview only — every field of [Summary] has a default, so
 * this sets just what the previewed components read. Deterministic (no randomness), so a
 * preview looks the same every time it renders.
 */
internal object PreviewFixtures {
    const val DAY = "2026-10-12"

    /** 1 deep, 2 light, 3 REM, 4 awake — a rough night of four cycles. */
    private val stages: List<Int> = buildList {
        repeat(4) { cycle ->
            repeat(3) { add(2) }
            repeat(if (cycle < 2) 5 else 2) { add(1) }
            repeat(3) { add(2) }
            repeat(2 + cycle) { add(3) }
            if (cycle == 1) add(4)
        }
        add(4)
    }

    val night = NightRow(
        ymd = DAY,
        start = "00:42",
        end = "07:18",
        inBedH = 6.6,
        deepPct = 21.0,
        lightPct = 52.0,
        remPct = 19.0,
        wakePct = 8.0,
        efficiency = 88.0,
        spo2Mean = 97.0,
        stages = stages,
    )

    /** 96 × 15-minute MET buckets: quiet night, a walk mid-morning, an evening session. */
    private val profile: List<Double> = List(96) { i ->
        val h = i / 4.0
        val day = if (h in 7.0..23.0) 1.3 + 0.3 * sin(h / 24 * 2 * PI) else 1.0
        val walk = max(0.0, 2.2 - kotlin.math.abs(h - 10.5) * 3)
        val run = max(0.0, 4.5 - kotlin.math.abs(h - 19.0) * 6)
        day + walk + run
    }

    val device = Device(
        batteryPct = 72,
        freshHours = 2.0,
        daysOfData = 88.9,
        nights = 81,
        totalEvents = 2_266_962,
        streams = listOf(Stream("Heart beats", 680_779), Stream("Motion", 205_931), Stream("Blood oxygen", 150_783)),
        insights = listOf(Insight("Sleep stages", "live"), Insight("Cardiovascular age", "gated", "needs the CVA model")),
        measuring = listOf(
            Capability("Daytime HR", on = true, feature = "daytime_hr"),
            Capability("SpO2", on = true, feature = "spo2"),
            Capability("Cardio PPG (CVA)", on = false, feature = "cva"),
        ),
    )

    val summary = Summary(
        digest = "HRV +9%, resting HR −8 bpm. Recovering well.",
        device = device,
        nights = listOf(night),
        vitals = Vitals(
            hrv = Trend(latest = 40.0, baseline = 36.6, deltaPct = 9.3),
            rhr = Trend(latest = 59.0, baseline = 66.6, deltaPct = -11.4),
        ),
        activityProfile = mapOf(DAY to profile),
        activityDaily = mapOf(DAY to DailyStat(steps = 8_240.0, activeKcal = 312.0, totalKcal = 2_069.0, peakHr = 133.0)),
    )
}
