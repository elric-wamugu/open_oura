//! The dashboard **summary computation** — the single source of truth both clients
//! render. Moved verbatim out of `oura-cli/src/dashboard.rs` so the native (iOS)
//! client computes the *same* JSON as the web client; the only thing the two share
//! is this crate, so they can't drift.
//!
//! The ML models (sleep hypnogram, cardiovascular age, activity sessions) are an
//! injected [`ModelRunner`]: `oura-cli` shells out to the Python torch runners, the
//! native client runs the `.ptl` models on-device (or supplies [`NoModelRunner`]).
//! Everything else here is pure Rust over the synced SQLite DB.
//!
//! A new field added to the JSON here surfaces in BOTH clients — but each must still
//! *render* it: web `dashboard/web/app.js`, iOS `apps/ios/OuraApp/OuraApp.swift`. See
//! `docs/clients-web-and-ios.md`.

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, Context, Result};
use serde_json::{json, Value};

use oura_store::storage::Store;

/// User anthropometrics — only the CVA model needs them; everything else is
/// signal-derived. Stored in an editable `profile.json` next to the DB.
#[derive(Clone, Copy)]
pub struct Demographics {
    pub sex: char, // 'M' | 'F' | 'O'
    pub age: f64,
    pub height_m: f64,
    pub weight_kg: f64,
    pub ring_size: f64,
}

impl Demographics {
    pub fn to_json(self) -> Value {
        json!({ "sex": self.sex.to_string(), "age": self.age, "height_m": self.height_m,
                "weight_kg": self.weight_kg, "ring_size": self.ring_size })
    }
    pub fn from_json(v: &Value) -> Self {
        let d = Demographics::default();
        Demographics {
            sex: v["sex"]
                .as_str()
                .and_then(|s| s.chars().next())
                .unwrap_or(d.sex)
                .to_ascii_uppercase(),
            age: v["age"].as_f64().unwrap_or(d.age),
            height_m: v["height_m"].as_f64().unwrap_or(d.height_m),
            weight_kg: v["weight_kg"].as_f64().unwrap_or(d.weight_kg),
            ring_size: v["ring_size"].as_f64().unwrap_or(d.ring_size),
        }
    }
}
impl Default for Demographics {
    fn default() -> Self {
        Demographics {
            sex: 'M',
            age: 30.0,
            height_m: 1.78,
            weight_kg: 75.0,
            ring_size: 10.0,
        }
    }
}

// ── the model seam ────────────────────────────────────────────────────────────
/// What the ML models need: the DB, timezone, profile, and the night windows to
/// stage. The runner returns each model's raw `--json` output (or `None`).
pub struct ModelInputs<'a> {
    pub db: &'a Path,
    pub tz: i64,
    pub demo: &'a Demographics,
    pub sleep_ranges: &'a [Value],
}

/// Raw model outputs, matching the Python runners' `--json` shape.
#[derive(Default)]
pub struct ModelOutputs {
    pub sleep_batch: Option<Value>, // run_sleep_model.py --batch
    pub cva: Option<Value>,         // run_cva_model.py
    pub activity: Option<Value>,    // run_activity_model.py
}

/// Runs the torch models. `oura-cli` shells out to Python; the native client runs
/// `.ptl` on-device. [`NoModelRunner`] degrades to the signal-derived panels only.
pub trait ModelRunner {
    fn run(&self, input: ModelInputs) -> ModelOutputs;
}

/// No models — vitals / cardio-trend / activity-profile / device / digest only.
pub struct NoModelRunner;
impl ModelRunner for NoModelRunner {
    fn run(&self, _: ModelInputs) -> ModelOutputs {
        ModelOutputs::default()
    }
}

// ── profile + feature-mode persistence (files next to the DB) ─────────────────
pub fn profile_path(db: &Path) -> PathBuf {
    db.parent().unwrap_or(Path::new(".")).join("profile.json")
}
/// Read the user profile (defaults if absent or malformed).
pub fn read_profile(db: &Path) -> Demographics {
    std::fs::read_to_string(profile_path(db))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .map(|v| Demographics::from_json(&v))
        .unwrap_or_default()
}
pub fn write_profile(db: &Path, v: &Value) -> Result<Demographics> {
    let demo = Demographics::from_json(v);
    std::fs::write(
        profile_path(db),
        serde_json::to_vec_pretty(&demo.to_json())?,
    )
    .context("writing profile.json")?;
    Ok(demo)
}

pub fn feature_modes_path(db: &Path) -> PathBuf {
    db.parent()
        .unwrap_or(Path::new("."))
        .join("feature_modes.json")
}
/// Real on-ring feature modes snapshotted at the last sync, as `{ feature: mode }`.
pub fn read_feature_modes(db: &Path) -> Value {
    std::fs::read_to_string(feature_modes_path(db))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .unwrap_or(Value::Null)
}
/// Persist a snapshot of on-ring feature modes as `{ feature: mode, …, _at: unix_secs }`.
/// Stamps `_at` and writes atomically next to the DB; best-effort (never panics/errs).
pub fn write_feature_modes(db: &Path, mut modes: serde_json::Map<String, Value>) {
    let at = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    modes.insert("_at".into(), json!(at));
    let _ = std::fs::write(
        feature_modes_path(db),
        serde_json::to_vec_pretty(&Value::Object(modes)).unwrap_or_default(),
    );
}
/// Record a feature's new mode (0 = off, 1 = automatic) right after a toggle.
pub fn write_feature_mode(db: &Path, feature: &str, mode_int: i64) {
    let mut modes = match read_feature_modes(db) {
        Value::Object(m) => m,
        _ => serde_json::Map::new(),
    };
    modes.insert(feature.to_string(), json!(mode_int));
    write_feature_modes(db, modes);
}

// ── small date helpers (no chrono dep) ────────────────────────────────────────
/// Howard Hinnant's civil_from_days: days since 1970-01-01 → (year, month, day).
pub fn civil(days: i64) -> (i64, u32, u32) {
    let z = days + 719468;
    let era = (if z >= 0 { z } else { z - 146096 }) / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = (if mp < 10 { mp + 3 } else { mp - 9 }) as u32;
    (y + i64::from(m <= 2), m, d)
}
/// Inverse of `civil`: (year, month, day) → days since 1970-01-01.
pub fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = (if y >= 0 { y } else { y - 399 }) / 400;
    let yoe = y - era * 400;
    let m = m as i64;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

const WD: [&str; 7] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

fn date_label(unix_s: f64, tz: i64) -> String {
    let days = (unix_s as i64 + tz * 3600).div_euclid(86400);
    let (_, m, d) = civil(days);
    let wd = WD[(days + 3).rem_euclid(7) as usize];
    format!("{wd} {m:02}-{d:02}")
}
/// Full calendar date (`YYYY-MM-DD`) — an unambiguous key for matching a night to a
/// day's activity (the weekday `date_label` collides across years).
fn ymd_label(unix_s: f64, tz: i64) -> String {
    let days = (unix_s as i64 + tz * 3600).div_euclid(86400);
    let (y, m, d) = civil(days);
    format!("{y:04}-{m:02}-{d:02}")
}
fn hm(unix_s: f64, tz: i64) -> String {
    let sod = (unix_s as i64 + tz * 3600).rem_euclid(86400);
    format!("{:02}:{:02}", sod / 3600, (sod % 3600) / 60)
}
/// Oura "SpO2 Simple" calibration → percent, clamped to the 85–100 display range.
/// The quadratic itself is ecore's `spo2_simple_calculate` (see `oura_analysis`);
/// these are the gen4/oreo coefficients (`a + b·r + c·r²`).
fn spo2_pct(r: f64) -> f64 {
    oura_analysis::ported::spo2::spo2_simple(r, 105.2, -5.1, -13.4).clamp(85.0, 100.0)
}

fn mean(v: &[f64]) -> Option<f64> {
    (!v.is_empty()).then(|| v.iter().sum::<f64>() / v.len() as f64)
}

/// Nightly skin temperature (°C) via ecore's `nightly_temperature` (7-sample median
/// → 30-sample windows → minimum of the window maxima). Falls back to the plain mean
/// when there aren't enough valid windows, so sparse nights still report a value.
fn nightly_skin_temp(temps_c: &[f64]) -> Option<f64> {
    let centi: Vec<u16> = temps_c
        .iter()
        .map(|&c| (c * 100.0).round().clamp(0.0, u16::MAX as f64) as u16)
        .collect();
    oura_analysis::ported::temperature::nightly_temperature(&centi)
        .map(|t| t as f64 / 100.0)
        .or_else(|| mean(temps_c))
}

/// Highest *sustained* heart rate in a day's quality-gated beat series.
///
/// A single 167 bpm beat is a PPG artefact, not an effort, so a raw daily max is
/// meaningless. We slide a 30-second window over the beats and take the largest window
/// median, requiring `MIN_BEATS` beats in the window — a rate you actually held, not a
/// spike. Input must be `(unix_seconds, bpm)` sorted by time.
fn peak_sustained_hr(samples: &[(f64, f64)]) -> Option<f64> {
    const WINDOW_S: f64 = 30.0;
    const MIN_BEATS: usize = 10;
    let mut best: Option<f64> = None;
    let mut j = 0usize;
    for i in 0..samples.len() {
        if j < i {
            j = i;
        }
        while j < samples.len() && samples[j].0 <= samples[i].0 + WINDOW_S {
            j += 1;
        }
        if j - i < MIN_BEATS {
            continue;
        }
        let mut win: Vec<f64> = samples[i..j].iter().map(|s| s.1).collect();
        win.sort_by(f64::total_cmp);
        let m = if win.len().is_multiple_of(2) {
            (win[win.len() / 2 - 1] + win[win.len() / 2]) / 2.0
        } else {
            win[win.len() / 2]
        };
        if best.is_none_or(|b| m > b) {
            best = Some(m);
        }
    }
    best
}

/// Age-predicted maximum heart rate (Tanaka et al. 2001, `208 − 0.7·age`) — the modern
/// replacement for `220 − age`, which overestimates in the young and under-estimates
/// with age. Only a population mean: the real spread is roughly ±10 bpm.
fn hr_max_predicted(age_years: f64) -> f64 {
    (208.0 - 0.7 * age_years).max(1.0)
}

// ── per-night signal accumulation ────────────────────────────────────────────
#[derive(Default)]
struct Night {
    start_ds: i64,
    end_ds: i64,
    epoch_idx: usize,
    rmssd: Vec<f64>,
    hr: Vec<f64>,
    temp: Vec<f64>,
    spo2: Vec<f64>,
    motion: Vec<f64>,
    // timestamped (time_ds, value) HRV/HR samples for stage-resolved autonomics — the
    // flat `rmssd`/`hr` vecs above drop timing, which we need to map each sample to its
    // hypnogram stage.
    hrv_t: Vec<(i64, f64)>,
    hr_t: Vec<(i64, f64)>,
}

/// Mean HR / HRV within each sleep stage, mapping each timestamped sample to the
/// hypnogram epoch it falls in (stages tile [start_ds, end_ds] uniformly). Codes
/// 1=deep 2=light 3=rem; wake is excluded (not recovery). Returns per-stage means where
/// samples exist, else `null`.
///
/// We deliberately expose per-stage means (esp. deep-sleep HRV, the cleanest recovery
/// signal) rather than a single overnight HRV slope: nocturnal HRV is stage-driven
/// (deep ↑, REM ↓), so a naive slope mostly tracks stage ordering, not recovery — a point
/// the sleep-HRV literature makes explicitly and which Oura's own app avoids.
fn autonomic_by_stage(
    hrv_t: &[(i64, f64)],
    hr_t: &[(i64, f64)],
    stages: &[i64],
    start_ds: i64,
    end_ds: i64,
) -> Value {
    if stages.is_empty() {
        return Value::Null;
    }
    let span = (end_ds - start_ds).max(1) as f64;
    let n = stages.len();
    let stage_at = |time_ds: i64| -> Option<i64> {
        let f = (time_ds - start_ds) as f64 / span;
        if !(0.0..=1.0).contains(&f) {
            return None;
        }
        Some(stages[((f * n as f64) as usize).min(n - 1)])
    };
    // per stage code: (hrv_sum, hrv_n, hr_sum, hr_n)
    let mut acc: std::collections::HashMap<i64, (f64, u32, f64, u32)> = Default::default();
    for &(t, v) in hrv_t {
        if let Some(s) = stage_at(t) {
            let e = acc.entry(s).or_default();
            e.0 += v;
            e.1 += 1;
        }
    }
    for &(t, v) in hr_t {
        if let Some(s) = stage_at(t) {
            let e = acc.entry(s).or_default();
            e.2 += v;
            e.3 += 1;
        }
    }
    let hrv = |c: i64| {
        acc.get(&c)
            .filter(|e| e.1 > 0)
            .map(|e| (e.0 / e.1 as f64).round())
    };
    let hr = |c: i64| {
        acc.get(&c)
            .filter(|e| e.3 > 0)
            .map(|e| (e.2 / e.3 as f64).round())
    };
    json!({
        "hrv_deep": hrv(1), "hrv_light": hrv(2), "hrv_rem": hrv(3),
        "hr_deep":  hr(1),  "hr_light":  hr(2),  "hr_rem":  hr(3),
    })
}

/// Count maximal runs of `code` at least `min_len` epochs long.
fn count_bouts(seq: &[i64], code: i64, min_len: usize) -> u32 {
    let mut count = 0;
    let mut run = 0usize;
    for &c in seq {
        if c == code {
            run += 1;
        } else {
            if run >= min_len {
                count += 1;
            }
            run = 0;
        }
    }
    if run >= min_len {
        count += 1;
    }
    count
}

/// Count periods of `code`, merging two runs separated by fewer than `merge_gap`
/// non-`code` epochs into one, then keeping only merged periods of at least `min_len`.
fn count_periods(seq: &[i64], code: i64, merge_gap: usize, min_len: usize) -> u32 {
    // collect (start,end) runs of the code
    let mut runs: Vec<(usize, usize)> = Vec::new();
    let mut i = 0;
    while i < seq.len() {
        if seq[i] == code {
            let start = i;
            while i < seq.len() && seq[i] == code {
                i += 1;
            }
            runs.push((start, i));
        } else {
            i += 1;
        }
    }
    if runs.is_empty() {
        return 0;
    }
    // merge runs closer than merge_gap
    let mut merged: Vec<(usize, usize)> = vec![runs[0]];
    for &(s, e) in &runs[1..] {
        let last = merged.last_mut().unwrap();
        if s - last.1 < merge_gap {
            last.1 = e;
        } else {
            merged.push((s, e));
        }
    }
    merged.iter().filter(|(s, e)| e - s >= min_len).count() as u32
}

/// Science-based per-night sleep metrics derived from the model hypnogram (per-epoch
/// codes 1=deep 2=light 3=rem 4=wake). The epoch length is inferred from the night's
/// in-bed window so we never hardcode the model's 30 s cadence. Returns clinical
/// readouts (onset latency, REM latency, WASO, awakenings, cycles, fragmentation) plus
/// the deep/REM front-vs-back-half split, and the asleep seconds used for sleep debt.
fn sleep_metrics(stages: &[i64], in_bed_s: f64) -> (Value, i32) {
    let n = stages.len();
    if n == 0 {
        return (Value::Null, 0);
    }
    let epoch_min = in_bed_s / 60.0 / n as f64;
    let is_sleep = |c: i64| (1..=3).contains(&c);
    let onset = stages.iter().position(|&c| is_sleep(c));
    let final_sleep = stages.iter().rposition(|&c| is_sleep(c));
    let (Some(onset), Some(final_sleep)) = (onset, final_sleep) else {
        return (Value::Null, 0);
    };
    let sleep_span = &stages[onset..=final_sleep];
    let asleep_epochs = stages.iter().filter(|&&c| is_sleep(c)).count();
    let asleep_min = asleep_epochs as f64 * epoch_min;

    // WASO + awakenings: wake epochs strictly inside the sleep period. An awakening is
    // a wake bout of at least ~1 min (min_wake epochs) so brief scoring flicker doesn't
    // inflate the count.
    let waso_epochs = sleep_span.iter().filter(|&&c| c == 4).count();
    let min_wake = (1.0 / epoch_min).ceil() as usize; // ≥ ~1 minute
    let awakenings = count_bouts(sleep_span, 4, min_wake.max(1));

    let rem_latency = stages[onset..]
        .iter()
        .position(|&c| c == 3)
        .map(|i| i as f64 * epoch_min);

    // Sleep cycles ≈ REM periods: a REM period is a run of REM, merging gaps shorter
    // than ~15 min and requiring the period to reach a few minutes — otherwise 30 s
    // REM flecks read as dozens of "cycles".
    let merge_gap = (15.0 / epoch_min).round() as usize;
    let min_rem = (3.0 / epoch_min).ceil() as usize;
    let cycles = count_periods(sleep_span, 3, merge_gap.max(1), min_rem.max(1));

    // fragmentation: stage changes per hour of sleep.
    let transitions = sleep_span.windows(2).filter(|w| w[0] != w[1]).count();
    let frag_index = if asleep_min > 0.0 {
        transitions as f64 / (asleep_min / 60.0)
    } else {
        0.0
    };

    // deep/REM concentration: share of each stage that falls in the first half of the
    // sleep period (healthy sleep front-loads deep, back-loads REM).
    let mid = onset + (final_sleep - onset) / 2;
    let half_pct = |code: i64| -> Option<f64> {
        let total = stages.iter().filter(|&&c| c == code).count();
        if total == 0 {
            return None;
        }
        let first = stages[onset..=mid].iter().filter(|&&c| c == code).count();
        Some((first as f64 / total as f64 * 100.0).round())
    };

    let round1 = |x: f64| (x * 10.0).round() / 10.0;
    let metrics = json!({
        "asleep_min": round1(asleep_min),
        "sol_min": round1(onset as f64 * epoch_min),
        "rem_latency_min": rem_latency.map(round1),
        "waso_min": round1(waso_epochs as f64 * epoch_min),
        "awakenings": awakenings,
        "cycles": cycles,
        "frag_index": round1(frag_index),
        "deep_first_half_pct": half_pct(1),
        "rem_first_half_pct": half_pct(3),
    });
    (metrics, (asleep_min * 60.0) as i32)
}

/// Personal baseline for a vital via ecore's annealing-EMA `Baseline`, over the nights
/// before the latest one. ecore anneals from zero over long history; the dashboard only
/// has a short window, so we prime the EMA at the first observed value (its mature
/// result is unchanged) and age it one "day" per night. `None` with < 2 valid nights.
fn ema_baseline(per_night: &[Option<f64>]) -> Option<f64> {
    use oura_analysis::ported::baseline::Baseline;
    let n = per_night.len();
    if n < 2 {
        return None;
    }
    let priors: Vec<f64> = per_night[..n - 1].iter().filter_map(|x| *x).collect();
    let (first, rest) = priors.split_first()?;
    let mut b = Baseline {
        mean_x8: (first.round() as i32) << 3,
        dev_x8: 0,
    };
    for (i, v) in rest.iter().enumerate() {
        b.update(v.round() as i32, (i + 1) as u32);
    }
    Some(b.mean())
}

/// `(sparkline series, latest, baseline)` for a per-night vital.
type VitalStat = (Vec<f64>, Option<f64>, Option<f64>);
fn vital_stat(per_night: &[Option<f64>]) -> VitalStat {
    let series: Vec<f64> = per_night.iter().filter_map(|x| *x).collect();
    let latest = per_night.last().copied().flatten();
    (series, latest, ema_baseline(per_night))
}
/// Percent change of `latest` vs `baseline`, guarding a zero/absent baseline.
fn vital_delta_pct(stat: &VitalStat) -> Option<f64> {
    match (stat.1, stat.2) {
        (Some(l), Some(b)) if b != 0.0 => Some(((l - b) / b * 100.0).round()),
        _ => None,
    }
}

fn feat(name: &str, on: bool, feature: &str) -> Value {
    json!({ "name": name, "on": on, "feature": feature })
}

/// Mode filter over a centered odd window — removes single-epoch flicker from the
/// model hypnogram so cycle/awakening counts reflect real architecture, not 30 s noise
/// (clinical scoring smooths the same way before deriving events).
fn smooth_stages(vals: &[i64], win: usize) -> Vec<i64> {
    if vals.len() < win || win < 3 {
        return vals.to_vec();
    }
    let half = win / 2;
    (0..vals.len())
        .map(|i| {
            let a = i.saturating_sub(half);
            let b = (i + half + 1).min(vals.len());
            let mut counts = [0u32; 5];
            for &s in &vals[a..b] {
                if (1..=4).contains(&s) {
                    counts[s as usize] += 1;
                }
            }
            (1..=4)
                .max_by_key(|&k| counts[k as usize])
                .unwrap_or(vals[i])
        })
        .collect()
}

/// Bucket-average a dense value series down to at most `n` points, so a per-sample
/// signal (SpO₂ has tens of thousands of samples/night) stays a light payload while
/// keeping its shape for the lane charts.
fn downsample_mean(v: &[f64], n: usize) -> Vec<f64> {
    if v.len() <= n {
        return v.to_vec();
    }
    let step = v.len() as f64 / n as f64;
    (0..n)
        .map(|i| {
            let a = (i as f64 * step) as usize;
            let b = (((i + 1) as f64 * step) as usize).max(a + 1).min(v.len());
            let slice = &v[a..b];
            slice.iter().sum::<f64>() / slice.len() as f64
        })
        .collect()
}

fn downsample_codes(vals: &[i64], n: usize) -> Vec<i64> {
    if vals.len() <= n {
        return vals.to_vec();
    }
    let step = vals.len() as f64 / n as f64;
    (0..n)
        .map(|i| {
            let a = (i as f64 * step) as usize;
            let b = ((i as f64 + 1.0) * step) as usize;
            let slice = &vals[a..b.min(vals.len()).max(a + 1)];
            let mut counts = [0u32; 5];
            for &s in slice {
                if (1..=4).contains(&s) {
                    counts[s as usize] += 1;
                }
            }
            (1..=4).max_by_key(|&k| counts[k as usize]).unwrap_or(2) as i64
        })
        .collect()
}

fn make_digest(hrv: &VitalStat, rhr: &VitalStat) -> String {
    let mut parts: Vec<String> = Vec::new();
    let hrv_pct = vital_delta_pct(hrv);
    let rhr_delta = match (rhr.1, rhr.2) {
        (Some(l), Some(b)) => Some(l - b),
        _ => None,
    };
    if let Some(p) = hrv_pct {
        parts.push(format!("HRV {}{:.0}%", if p >= 0.0 { "+" } else { "" }, p));
    }
    if let Some(d) = rhr_delta {
        parts.push(format!(
            "resting HR {}{:.0} bpm",
            if d >= 0.0 { "+" } else { "" },
            d
        ));
    }
    let recovering = match (hrv_pct, rhr_delta) {
        (Some(p), _) => p >= 0.0,
        (None, Some(d)) => d <= 0.0,
        (None, None) => false,
    };
    let mut s = parts.join(", ");
    if !s.is_empty() {
        s.push_str(if recovering {
            ". Recovering well."
        } else {
            ". Recovery dipping, take it easy."
        });
    }
    if s.is_empty() {
        s = "Synced. Not enough history yet for trends.".into();
    }
    s
}

/// Assemble a night's hypnogram from the ring's OWN `sleep_phase_data` events —
/// the on-device fallback when no SleepNet model output is available. The ring
/// finishes staging a night and logs the hypnogram as a burst a couple of hours
/// *after* wake, so we take the phases from just after the night's `end_ds`. Codes:
/// 1=deep 2=light 3=rem 4=wake (matching the model). They tile the sleep window
/// uniformly, exactly like the model's `stages`.
fn stages_from_phase_data(
    events: &[(i64, u8, String, i64)],
    event_epochs: &[usize],
    epoch_idx: usize,
    end_ds: i64,
) -> Vec<i64> {
    // 5 h after wake clears the ~2 h logging delay but stays short of the next night.
    const POST_WAKE_DS: i64 = 5 * 3600 * 10;
    let mut burst: Vec<(i64, &str)> = Vec::new();
    for (i, (ds, tag, jstr, _)) in events.iter().enumerate() {
        if event_epochs[i] == epoch_idx
            && *ds > end_ds
            && *ds <= end_ds + POST_WAKE_DS
            && oura_protocol::events::event_name(*tag) == "sleep_phase_data"
        {
            burst.push((*ds, jstr.as_str()));
        }
    }
    burst.sort_by_key(|&(ds, _)| ds);
    let mut stages = Vec::new();
    for (_, jstr) in burst {
        if let Ok(v) = serde_json::from_str::<Value>(jstr) {
            if let Some(arr) = v["phases"].as_array() {
                for p in arr {
                    match p.as_str() {
                        Some("deep") => stages.push(1),
                        Some("light") => stages.push(2),
                        Some("rem") => stages.push(3),
                        Some("awake") => stages.push(4),
                        _ => {}
                    }
                }
            }
        }
    }
    stages
}

/// A break longer than this in capture time means a separate sync. Within one drain the
/// capture times only track transfer progress, so a session must collapse to a single
/// observation — treating each event separately would map generation time onto drain order.
const SYNC_GAP_S: i64 = 120;

/// Fit the `ds → wall-clock` offset steps for one epoch from its `(captured_unix, ds)`
/// rows (any order). Returns `(ds, offset)` ascending by ds, where
/// `unix = offset + ds/10` holds for every ds at or below that step's ds.
///
/// Each sync contributes one observation — its newest event, ds `D` drained at host time
/// `T` — which bounds the offset from above at `T − D/10`, inflated by however far behind
/// that drain finished. The true offset only grows (the counter loses time, never gains
/// it), so the tightest consistent fit is the running minimum taken from the newest ds
/// backwards. See the call site for why the old single-anchor model was wrong.
fn fit_ds_offsets(rows: &mut [(i64, i64)]) -> Vec<(i64, f64)> {
    rows.sort_unstable();
    let mut obs: Vec<(i64, f64)> = Vec::new();
    let mut last_cu = i64::MIN;
    let (mut sess_d, mut sess_t) = (i64::MIN, 0i64);
    for &(cu, ds) in rows.iter() {
        if cu - last_cu > SYNC_GAP_S && sess_d != i64::MIN {
            obs.push((sess_d, sess_t as f64 - sess_d as f64 / 10.0));
            sess_d = i64::MIN;
        }
        if ds >= sess_d {
            sess_d = ds;
            sess_t = cu;
        }
        last_cu = cu;
    }
    if sess_d != i64::MIN {
        obs.push((sess_d, sess_t as f64 - sess_d as f64 / 10.0));
    }
    obs.sort_by_key(|o| o.0);
    let mut run = f64::INFINITY;
    for o in obs.iter_mut().rev() {
        run = run.min(o.1);
        o.1 = run;
    }
    obs
}

/// Map a ring counter to wall-clock through fitted offset steps: use the tightest bound
/// from the earliest sync that had already observed this ds.
fn unix_from_offsets(offsets: &[(i64, f64)], ds: i64) -> Option<f64> {
    if offsets.is_empty() {
        return None;
    }
    let i = offsets
        .partition_point(|(d, _)| *d < ds)
        .min(offsets.len() - 1);
    Some(offsets[i].1 + ds as f64 / 10.0)
}

/// Discharge runs and the raw curve from the ring's own battery log.
///
/// The ring emits `debug_data { kind: "battery_level_changed", battery_pct, voltage_mv }`
/// roughly once a minute whenever the level moves — a far better record than the handful
/// of samples taken at sync time, which is all `readings` holds.
///
/// Reading both percent *and* voltage matters. The gauge is voltage-derived, and below
/// ~3.6 V the lithium discharge curve is nearly vertical, so the last quarter appears to
/// vanish in minutes. Some of that is not real capacity: voltage sags under radio load and
/// recovers at rest, so a percentage read during a long sync understates the charge left.
/// Showing the volts alongside lets that be seen rather than guessed at.
///
/// `points` must be `(unix, pct, mv)`. Returns `(series, cycles)`.
fn battery_history(mut points: Vec<(f64, i64, i64)>) -> (Vec<Value>, Vec<Value>) {
    points.sort_by(|a, b| a.0.total_cmp(&b.0));
    points.dedup_by_key(|p| p.0 as i64);
    if points.len() < 2 {
        return (Vec::new(), Vec::new());
    }
    // Zigzag with hysteresis: a rise of this many points confirms the charger went on and
    // closes the discharge run, so ordinary jitter doesn't chop a run into fragments.
    const RISE_CONFIRMS_CHARGE: i64 = 5;
    // Ignore runs that never dropped far enough to say anything about battery life.
    const MIN_RUN_DROP: i64 = 15;

    let pct = |i: usize| points[i].1;
    let mut cycles = Vec::new();
    let (mut peak, mut trough) = (0usize, 0usize);
    let close = |peak: usize, trough: usize, out: &mut Vec<Value>| {
        if pct(peak) - pct(trough) < MIN_RUN_DROP {
            return;
        }
        let hours = (points[trough].0 - points[peak].0) / 3600.0;
        if hours <= 0.0 {
            return;
        }
        let drop = (pct(peak) - pct(trough)) as f64;
        out.push(json!({
            "start": points[peak].0.round(),
            "end": points[trough].0.round(),
            "from_pct": pct(peak),
            "to_pct": pct(trough),
            "from_mv": points[peak].2,
            "to_mv": points[trough].2,
            "hours": (hours * 10.0).round() / 10.0,
            "pct_per_hour": (drop / hours * 100.0).round() / 100.0,
            // What a full 100 → 0 run would take at this rate — the comparable number
            // across runs that start from different levels.
            "projected_full_h": ((100.0 / (drop / hours)) * 10.0).round() / 10.0,
        }));
    };
    for i in 1..points.len() {
        if pct(i) >= pct(peak) && trough == peak {
            peak = i;
            trough = i;
        } else if pct(i) <= pct(trough) {
            trough = i;
        } else if pct(i) - pct(trough) >= RISE_CONFIRMS_CHARGE {
            close(peak, trough, &mut cycles);
            peak = i;
            trough = i;
        }
    }
    close(peak, trough, &mut cycles);

    // Cap the charted curve so the payload can't grow without bound as history piles up.
    const MAX_POINTS: usize = 600;
    let step = points.len().div_ceil(MAX_POINTS).max(1);
    let series: Vec<Value> = points
        .iter()
        .step_by(step)
        .map(|(t, p, mv)| json!({ "t": t.round(), "pct": p, "mv": mv }))
        .collect();
    (series, cycles)
}

/// Assemble the full dashboard summary as a JSON value. The torch models are
/// supplied by `runner` (Python subprocess on desktop, `.ptl` on-device).
pub fn build_summary(db: &Path, tz: i64, runner: &dyn ModelRunner) -> Result<Value> {
    let db_abs = std::fs::canonicalize(db).unwrap_or_else(|_| {
        std::env::current_dir()
            .map(|d| d.join(db))
            .unwrap_or_else(|_| db.to_path_buf())
    });
    let db = db_abs.as_path();
    let demo = read_profile(db);
    let store = Store::open(db).context("opening DB")?;
    let dev = store.device_info().ok().flatten();
    let events = match dev.as_ref().map(|d| d.0.as_str()) {
        Some(serial) => store
            .decoded_events_for_serial(serial)
            .with_context(|| format!("reading events for serial {serial}"))?,
        None => store.decoded_events().context("reading events")?,
    };
    if events.is_empty() {
        return Err(anyhow!(
            "no decoded events in {} — run `oura sync` first",
            db.display()
        ));
    }
    // `ring_timestamp` (ds) is a per-boot deciseconds counter. During continuous wear
    // it advances at ~10/sec of real time, so the newest event ≈ "now" (its sync's
    // capture time) and everything else sits (newest_ds − ds)/10 seconds earlier —
    // regardless of when it was *synced*. That last point matters: a night's sleep is
    // buffered and drained the next morning, yet its ds deltas are still real-time, so
    // anchoring on the counter (not the sync time) places it back overnight where it
    // belongs. We split into boot "epochs" (a reboot resets ds toward ~0) and anchor
    // each on its newest event's capture time.
    //
    // Anchoring on the *newest* event alone was wrong, and visibly so: a sync that
    // stopped before draining the buffer left `max_ds` behind the ring's true counter
    // while still being pinned to "now", which slid every earlier timestamp forward.
    // Re-syncing moved the anchor again, so the same night could shift by hours between
    // two views of the same data. Measured on real history: sync-to-sync rates alternate
    // between ~3 ds/sec and ~500 ds/sec — the signature of a partial drain followed by
    // one that catches up — even though the counter's own long-run rate is 9.96/sec.
    //
    // So instead of one anchor we fit an *offset* per epoch. Writing the mapping as
    //     unix(ds) = offset + ds/10
    // each sync yields one observation: its newest event, ds `D` drained at host time
    // `T`, must have been generated at or before `T`, giving `offset <= T − D/10`. Every
    // sync is therefore an upper bound, inflated exactly by how far behind that drain
    // finished. The true offset only ever grows (the counter loses time; it cannot gain
    // it), so the tightest fit is the running minimum of those bounds taken from the
    // newest ds backwards — a non-decreasing step function that respects every bound.
    //
    // Two properties matter. Partial drains are self-correcting: a later, caught-up sync
    // supplies a tighter bound and the inflated one is discarded. And an event's time
    // depends only on syncs that had already observed its ds, so adding today's sync
    // cannot move last week's night.
    struct Epoch {
        min_ds: i64,
        max_ds: i64,
        anchor_unix: i64,
        /// (ds, offset) steps, ascending by ds. `offset` is seconds such that
        /// `unix = offset + ds/10` for every ds at or below that step's ds.
        offsets: Vec<(i64, f64)>,
    }
    // A real reboot drops ds by millions; 6 h of slack absorbs minor out-of-order
    // framing within an epoch without ever splitting one.
    const EPOCH_RESET_SLACK_DS: i64 = 6 * 3600 * 10;
    let mut order: Vec<(i64, usize, i64)> = events
        .iter()
        .enumerate()
        .map(|(idx, (ds, _, _, cu))| (*cu, idx, *ds))
        .collect();
    order.sort_unstable();
    let mut epochs: Vec<Epoch> = Vec::new();
    let mut event_epochs = vec![0usize; events.len()];
    for (cu, event_idx, ds) in order {
        if let Some(i) = epochs.len().checked_sub(1) {
            if ds >= epochs[i].max_ds - EPOCH_RESET_SLACK_DS {
                event_epochs[event_idx] = i;
                if ds >= epochs[i].max_ds {
                    epochs[i].max_ds = ds;
                    epochs[i].anchor_unix = cu; // newest event → its capture time
                }
                epochs[i].min_ds = epochs[i].min_ds.min(ds);
                continue;
            }
        }
        event_epochs[event_idx] = epochs.len();
        epochs.push(Epoch {
            min_ds: ds,
            max_ds: ds,
            anchor_unix: cu,
            offsets: Vec::new(),
        });
    }

    // Fit the per-epoch offset steps described above.
    {
        let mut per_epoch: Vec<Vec<(i64, i64)>> = vec![Vec::new(); epochs.len()]; // (cu, ds)
        for (idx, (ds, _, _, cu)) in events.iter().enumerate() {
            per_epoch[event_epochs[idx]].push((*cu, *ds));
        }
        for (e, mut rows) in epochs.iter_mut().zip(per_epoch) {
            e.offsets = fit_ds_offsets(&mut rows);
        }
    }

    // ds → wall-clock through the fitted offsets, falling back to the old single-anchor
    // behaviour only if an epoch somehow produced no observations.
    let unix_in_epoch = |ds: i64, epoch_idx: usize| -> f64 {
        let e = &epochs[epoch_idx];
        unix_from_offsets(&e.offsets, ds)
            .unwrap_or_else(|| e.anchor_unix as f64 - (e.max_ds - ds) as f64 / 10.0)
    };
    // Wall-clock "now" reference (newest sync).
    let anchor_unix = epochs.iter().map(|e| e.anchor_unix).max().unwrap_or(0);

    let mut beds: Vec<(i64, i64, usize)> = Vec::new();
    let mut present_recent = std::collections::HashSet::new();
    // "recent" = within 10 days of the newest data, measured in wall-clock so it never
    // sweeps in an older epoch that happens to share a high raw ds.
    let recent_cut_unix = anchor_unix as f64 - 10.0 * 86_400.0;
    let name_of = |tag: u8| oura_protocol::events::event_name(tag);
    for (event_idx, (ds, tag, jstr, _)) in events.iter().enumerate() {
        let epoch_idx = event_epochs[event_idx];
        let n = name_of(*tag);
        if unix_in_epoch(*ds, epoch_idx) >= recent_cut_unix {
            present_recent.insert(n);
        }
        if n == "bedtime_period" {
            if let Ok(v) = serde_json::from_str::<Value>(jstr) {
                if let (Some(s), Some(e)) =
                    (v["bedtime_start_ds"].as_i64(), v["bedtime_end_ds"].as_i64())
                {
                    match beds
                        .iter_mut()
                        .find(|(bs, _, b_epoch_idx)| *bs == s && *b_epoch_idx == epoch_idx)
                    {
                        Some(b) => b.1 = b.1.max(e),
                        None => beds.push((s, e, epoch_idx)),
                    }
                }
            }
        }
    }
    beds.sort_by(|a, b| unix_in_epoch(a.0, a.2).total_cmp(&unix_in_epoch(b.0, b.2)));

    // The ring emits a `bedtime_period` for any stretch of stillness, so an evening spent
    // sitting quietly yields several 10–40 minute "nights" alongside the real one. They
    // can't be scored — a sleep cycle is ~90 min, so anything shorter has no architecture
    // to stage — and they poison everything keyed off the newest night (the sleep tile,
    // the HRV/RHR baselines, sleep debt) whenever one lands after the actual sleep.
    //
    // Drop them below one sleep cycle. On this ring's data the split is unambiguous:
    // periods are either ≤ 1.1 h or ≥ 2.8 h, so the cut sits in a 1.7 h empty band rather
    // than near any real value. Genuine long naps stay — they clear 90 min.
    const MIN_SLEEP_PERIOD_DS: i64 = 90 * 600;
    let beds_before = beds.len();
    beds.retain(|&(s, e, _)| e - s >= MIN_SLEEP_PERIOD_DS);
    let short_periods_excluded = beds_before - beds.len();

    let mut nights: Vec<Night> = beds
        .iter()
        .map(|&(s, e, epoch_idx)| Night {
            start_ds: s,
            end_ds: e,
            epoch_idx,
            ..Default::default()
        })
        .collect();
    let find_night = |ds: i64, epoch_idx: usize, nights: &[Night]| {
        nights.iter().position(|nt| {
            nt.epoch_idx == epoch_idx && nt.start_ds - 600 <= ds && ds <= nt.end_ds + 600
        })
    };
    for (event_idx, (ds, tag, jstr, _)) in events.iter().enumerate() {
        let epoch_idx = event_epochs[event_idx];
        let Some(idx) = find_night(*ds, epoch_idx, &nights) else {
            continue;
        };
        let n = name_of(*tag);
        let v: Value = match serde_json::from_str(jstr) {
            Ok(v) => v,
            Err(_) => continue,
        };
        match n {
            "hrv_event" => {
                // Each array element is an `interval_min`-minute average starting at the
                // event's ring timestamp, so element i sits at ds + i·interval (in
                // deciseconds: minutes × 600). Keep both the flat vecs (mean/min) and the
                // timestamped samples (stage-resolved autonomics).
                let step_ds = v["interval_min"].as_i64().unwrap_or(5).max(1) * 600;
                if let Some(a) = v["rmssd_ms"].as_array() {
                    for (i, x) in a.iter().enumerate() {
                        if let Some(val) = x.as_f64() {
                            if val > 0.0 {
                                nights[idx].rmssd.push(val);
                                nights[idx].hrv_t.push((*ds + i as i64 * step_ds, val));
                            }
                        }
                    }
                }
                if let Some(a) = v["hr_bpm"].as_array() {
                    for (i, x) in a.iter().enumerate() {
                        if let Some(val) = x.as_f64() {
                            if val > 0.0 {
                                nights[idx].hr.push(val);
                                nights[idx].hr_t.push((*ds + i as i64 * step_ds, val));
                            }
                        }
                    }
                }
            }
            "temp_event" | "sleep_temp_event" => {
                // Keep every in-window sample (not just the first): the ecore nightly
                // algorithm needs a dense series. `find_night` already restricts these
                // to the bedtime window, so they're all nocturnal skin-temp readings.
                if let Some(a) = v["temps_c"].as_array() {
                    nights[idx]
                        .temp
                        .extend(a.iter().filter_map(|x| x.as_f64()).filter(|&c| c > 0.0));
                }
            }
            "spo2_r_pi_event" => {
                if let Some(a) = v["r"].as_array() {
                    nights[idx].spo2.extend(
                        a.iter()
                            .filter_map(|x| x.as_f64())
                            .filter(|&x| x > 0.0)
                            .map(spo2_pct),
                    );
                }
            }
            "spo2_event" => {
                // Rings that emit summarized SpO2 % directly (no R-ratio to calibrate).
                if let Some(a) = v["spo2_percent"].as_array() {
                    nights[idx].spo2.extend(
                        a.iter()
                            .filter_map(|x| x.as_f64())
                            .filter(|&x| (70.0..=100.0).contains(&x)),
                    );
                }
            }
            "motion_event" => {
                // seconds of motion in this window — a restlessness signal aligned to
                // the night, feeds the polysomnograph's movement lane.
                if let Some(s) = v["motion_seconds"].as_f64() {
                    nights[idx].motion.push(s);
                }
            }
            _ => {}
        }
    }

    // the model seam — sleep / cva / activity (Python subprocess or on-device .ptl)
    let sleep_ranges: Vec<Value> = nights
        .iter()
        .map(|nt| {
            json!({
                "key": format!("{}:{}", nt.epoch_idx, nt.start_ds),
                "epoch_idx": nt.epoch_idx,
                "start_ds": nt.start_ds,
                "end_ds": nt.end_ds,
            })
        })
        .collect();
    let ModelOutputs {
        sleep_batch,
        cva,
        activity: activity_raw,
    } = runner.run(ModelInputs {
        db,
        tz,
        demo: &demo,
        sleep_ranges: &sleep_ranges,
    });

    let hyps: std::collections::HashMap<String, Value> = sleep_batch
        .as_ref()
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|h| {
                    let key = h["key"]
                        .as_str()
                        .map(str::to_string)
                        .or_else(|| Some(h["start_ds"].as_i64()?.to_string()))?;
                    Some((key, h.clone()))
                })
                .collect()
        })
        .unwrap_or_default();

    // downsample a raw signal to ≤N points (bucket mean) then round for a compact
    // payload; the frontend spreads each series evenly across the night window (all
    // signals cover the full night, so index→time is shared across lanes).
    const SERIES_MAX: usize = 240;
    let series = |v: &[f64], dp: i32| -> Vec<f64> {
        let m = 10f64.powi(dp);
        downsample_mean(v, SERIES_MAX)
            .iter()
            .map(|x| (x * m).round() / m)
            .collect()
    };

    let mut nights_json = Vec::new();
    let mut asleep_by_night: Vec<i32> = Vec::new(); // oldest-first, for sleep debt
    for nt in &nights {
        let sleep_key = format!("{}:{}", nt.epoch_idx, nt.start_ds);
        let hyp = hyps
            .get(&sleep_key)
            .or_else(|| hyps.get(&nt.start_ds.to_string()));
        let raw_stages: Vec<i64> = hyp
            .and_then(|h| h["stages"].as_array())
            .map(|s| s.iter().filter_map(|x| x.as_i64()).collect::<Vec<i64>>())
            .filter(|v| !v.is_empty())
            // No SleepNet output → fall back to the ring's own on-device hypnogram.
            .unwrap_or_else(|| {
                stages_from_phase_data(&events, &event_epochs, nt.epoch_idx, nt.end_ds)
            });
        // smooth once (≈2.5 min window) — used for the displayed hypnogram AND the
        // derived metrics, so the two always agree.
        let full_stages = smooth_stages(&raw_stages, 5);
        let stage_cells = (!full_stages.is_empty()).then(|| downsample_codes(&full_stages, 120));
        let in_bed_s = (nt.end_ds - nt.start_ds) as f64 / 10.0;
        let (metrics, asleep_s) = sleep_metrics(&full_stages, in_bed_s);
        asleep_by_night.push(asleep_s);
        let autonomic =
            autonomic_by_stage(&nt.hrv_t, &nt.hr_t, &full_stages, nt.start_ds, nt.end_ds);
        let start_unix = unix_in_epoch(nt.start_ds, nt.epoch_idx);
        let end_unix = unix_in_epoch(nt.end_ds, nt.epoch_idx);
        // Stage distribution + efficiency: from the model when present, else derived
        // from the (on-device) hypnogram so they always match the displayed stages.
        let (deep_pct, light_pct, rem_pct, wake_pct, efficiency) = match hyp {
            Some(h) => (
                h["deep_pct"].clone(),
                h["light_pct"].clone(),
                h["rem_pct"].clone(),
                h["wake_pct"].clone(),
                h["efficiency_pct"].clone(),
            ),
            None => {
                let n = full_stages.len();
                let pct = |code: i64| {
                    if n == 0 {
                        Value::Null
                    } else {
                        let c = full_stages.iter().filter(|&&x| x == code).count();
                        json!((c as f64 / n as f64 * 100.0).round())
                    }
                };
                let eff = if n == 0 {
                    Value::Null
                } else {
                    let asleep = full_stages.iter().filter(|&&c| (1..=3).contains(&c)).count();
                    json!((asleep as f64 / n as f64 * 100.0).round())
                };
                (pct(1), pct(2), pct(3), pct(4), eff)
            }
        };
        nights_json.push(json!({
            "date": date_label(start_unix, tz),
            "ymd": ymd_label(start_unix, tz),
            "start_ds": nt.start_ds, // exact bedtime key for on-device model injection
            "start": hm(start_unix, tz),
            "end": hm(end_unix, tz),
            "in_bed_h": ((nt.end_ds - nt.start_ds) as f64 / 10.0 / 3600.0 * 10.0).round() / 10.0,
            "hrv_ms": mean(&nt.rmssd).map(|x| x.round()),
            "rhr": nt.hr.iter().cloned().fold(f64::INFINITY, f64::min).is_finite().then(|| nt.hr.iter().cloned().fold(f64::INFINITY, f64::min).round()),
            "skin_temp": nightly_skin_temp(&nt.temp).map(|x| (x * 10.0).round() / 10.0),
            "spo2_mean": mean(&nt.spo2).map(|x| x.round()),
            "deep_pct": deep_pct,
            "light_pct": light_pct,
            "rem_pct": rem_pct,
            "wake_pct": wake_pct,
            "efficiency": efficiency,
            "stages": stage_cells,
            // full-resolution hypnogram + aligned raw signals for the detail page's
            // stacked polysomnograph (empty arrays stay out of the way when absent).
            "stages_full": (!full_stages.is_empty()).then_some(full_stages),
            "series": {
                "hr": series(&nt.hr, 0),
                "hrv": series(&nt.rmssd, 0),
                "temp": series(&nt.temp, 2),
                "spo2": series(&nt.spo2, 0),
                "motion": series(&nt.motion, 0),
            },
            "metrics": metrics,
            // mean HR/HRV per sleep stage (deep/light/rem) — deep-sleep HRV is the
            // recovery-relevant number; null when there's no hypnogram.
            "autonomic": autonomic,
        }));
    }
    nights_json.reverse();

    // sleep debt over the recent nights (newest-first for the linear-decay weighting),
    // against an 8 h nightly need — the same ecore-ported algorithm as the ring.
    let need_h = 8.0;
    let sleep_debt = {
        use oura_analysis::ported::sleep_debt::{sleep_debt, SleepDebtConfig};
        let mut actual: Vec<i32> = asleep_by_night.clone();
        actual.reverse(); // newest-first
        let need = vec![(need_h * 3600.0) as i32; actual.len()];
        let d = sleep_debt(&actual, &need, &SleepDebtConfig::default());
        json!({
            "debt_min": (d.debt_s as f64 / 60.0).round(),
            "recent_shortfall_min": (d.recent_shortfall_s as f64 / 60.0).round(),
            "valid": d.valid,
            "need_h": need_h,
        })
    };

    let mut activity = activity_raw
        .as_ref()
        .and_then(|v| v["sessions"].as_array().cloned())
        .unwrap_or_default();

    let mut prof_sum: std::collections::BTreeMap<String, [f64; 96]> = Default::default();
    let mut prof_cnt: std::collections::BTreeMap<String, [u32; 96]> = Default::default();
    // Steps per 15-min bucket, accumulated from the SAME per-minute `step_rate` that feeds
    // the daily total below — so the buckets always add up to the day (bar its round-to-100)
    // and a hover readout can't disagree with the headline number.
    let mut prof_steps: std::collections::BTreeMap<String, [f64; 96]> = Default::default();
    let mut daily: std::collections::BTreeMap<String, (f64, f64)> = Default::default();
    let mut met_min: std::collections::BTreeMap<i64, f64> = Default::default();
    let weight = demo.weight_kg;
    // Resting daily expenditure via ecore's Schofield BMR (by age band + sex), instead
    // of the old flat `weight × 24` approximation. Added to active kcal for total kcal.
    let sex_code = match demo.sex {
        'F' => 1u8,
        'M' => 0,
        _ => 2, // unknown → the male/female average (bmr_schofield's fallback)
    };
    let bmr_kcal_day = oura_analysis::ported::metabolic::bmr_schofield(demo.age, sex_code, weight);
    // Anthropometric VO2max (Jackson non-exercise estimate), ecore's own formula.
    let vo2max =
        oura_analysis::ported::metabolic::vo2max_jackson(demo.age, demo.sex == 'F', weight);
    for (event_idx, (ds, tag, jstr, _)) in events.iter().enumerate() {
        if name_of(*tag) != "activity_information" || !jstr.contains("\"met\"") {
            continue;
        }
        if let Ok(v) = serde_json::from_str::<Value>(jstr) {
            if let Some(met) = v["met"].as_array() {
                for (i, m) in met.iter().enumerate() {
                    let mv = m.as_f64().unwrap_or(1.0);
                    let unix = unix_in_epoch(*ds, event_epochs[event_idx]) + i as f64 * 60.0;
                    let local = unix + tz as f64 * 3600.0;
                    let day_idx = (local / 86400.0).floor() as i64;
                    let (y, mo, dd) = civil(day_idx);
                    let key = format!("{y:04}-{mo:02}-{dd:02}");
                    let bucket = (((local - day_idx as f64 * 86400.0) / 86400.0) * 96.0)
                        .floor()
                        .clamp(0.0, 95.0) as usize;
                    prof_sum.entry(key.clone()).or_insert([0.0; 96])[bucket] += (mv - 1.0).max(0.0);
                    prof_cnt.entry(key.clone()).or_insert([0; 96])[bucket] += 1;
                    let step_rate = if mv >= 7.0 {
                        150.0
                    } else if mv >= 2.5 {
                        105.0
                    } else {
                        0.0
                    };
                    prof_steps.entry(key.clone()).or_insert([0.0; 96])[bucket] += step_rate;
                    let e = daily.entry(key).or_insert((0.0, 0.0));
                    e.0 += (mv - 1.0).max(0.0) * weight / 60.0;
                    e.1 += step_rate;
                    *met_min.entry((local / 60.0).floor() as i64).or_insert(0.0) +=
                        (mv - 1.0).max(0.0);
                }
            }
        }
    }
    for sess in activity.iter_mut() {
        let (Some(start), Some(dur)) = (sess["start"].as_str(), sess["duration_min"].as_f64())
        else {
            continue;
        };
        let parse = || -> Option<i64> {
            let (date, time) = start.split_once(' ')?;
            let mut dp = date.split('-');
            let y: i64 = dp.next()?.parse().ok()?;
            let mo: u32 = dp.next()?.parse().ok()?;
            let dd: u32 = dp.next()?.parse().ok()?;
            let mut tp = time.split(':');
            let hh: i64 = tp.next()?.parse().ok()?;
            let mm: i64 = tp.next()?.parse().ok()?;
            Some(days_from_civil(y, mo, dd) * 1440 + hh * 60 + mm)
        };
        if let Some(m0) = parse() {
            let kcal: f64 = (m0..m0 + dur as i64)
                .filter_map(|m| met_min.get(&m))
                .map(|met| met * weight / 60.0)
                .sum();
            sess["active_kcal"] = json!(kcal.round());
        }
    }

    // Peak sustained HR per day, from the quality-gated beat stream (`green_ibi_quality_event`
    // carries a per-beat quality flag; the ungated `ibi_and_amplitude_event` is full of
    // 30-bpm/2000-ms artefacts). Beat times step forward by each beat's own IBI.
    let mut hr_by_day: std::collections::BTreeMap<String, Vec<(f64, f64)>> = Default::default();
    for (event_idx, (ds, tag, jstr, _)) in events.iter().enumerate() {
        if name_of(*tag) != "green_ibi_quality_event" {
            continue;
        }
        let Ok(v) = serde_json::from_str::<Value>(jstr) else {
            continue;
        };
        let Some(hr) = v["hr_bpm"].as_array() else {
            continue;
        };
        let q = v["quality"].as_array();
        let ibi = v["ibi_ms"].as_array();
        let mut t = unix_in_epoch(*ds, event_epochs[event_idx]);
        for (i, h) in hr.iter().enumerate() {
            let bpm = h.as_f64().unwrap_or(0.0);
            let good = q
                .and_then(|a| a.get(i))
                .and_then(|x| x.as_i64())
                .unwrap_or(0)
                == 1;
            if good && (25.0..=220.0).contains(&bpm) {
                let local = t + tz as f64 * 3600.0;
                let (y, mo, dd) = civil((local / 86400.0).floor() as i64);
                hr_by_day
                    .entry(format!("{y:04}-{mo:02}-{dd:02}"))
                    .or_default()
                    .push((t, bpm));
            }
            t += ibi
                .and_then(|a| a.get(i))
                .and_then(|x| x.as_f64())
                .unwrap_or(600.0)
                / 1000.0;
        }
    }
    let peak_hr: std::collections::BTreeMap<String, f64> = hr_by_day
        .iter_mut()
        .filter_map(|(k, v)| {
            v.sort_by(|a, b| a.0.total_cmp(&b.0));
            peak_sustained_hr(v).map(|p| (k.clone(), p.round()))
        })
        .collect();

    let activity_daily: Value = daily
        .iter()
        .map(|(k, (act, steps))| {
            let steps_r = (steps / 100.0).round() * 100.0;
            // walking distance from steps via ecore's actinfo_steps_to_meters (0.762 m/step)
            let distance_m = oura_analysis::ported::metabolic::steps_to_meters(steps_r as u32);
            (
                k.clone(),
                json!({
                    "active_kcal": act.round(),
                    "total_kcal": (bmr_kcal_day + act).round(),
                    "steps": steps_r,
                    "distance_m": distance_m,
                    "peak_hr": peak_hr.get(k),
                }),
            )
        })
        .collect::<serde_json::Map<_, _>>()
        .into();
    let activity_profile: Value = prof_sum
        .iter()
        .map(|(k, sums)| {
            let cnt = &prof_cnt[k];
            let arr: Vec<f64> = sums
                .iter()
                .zip(cnt.iter())
                .map(|(s, c)| {
                    if *c > 0 {
                        (s / *c as f64 * 100.0).round() / 100.0
                    } else {
                        0.0
                    }
                })
                .collect();
            (k.clone(), json!(arr))
        })
        .collect::<serde_json::Map<_, _>>()
        .into();
    // Same 96 buckets as activity_profile, but steps rather than MET-above-rest, so a
    // hover on the movement chart can answer "how many steps at 12pm".
    let activity_steps: Value = prof_steps
        .iter()
        .map(|(k, steps)| {
            let arr: Vec<f64> = steps.iter().map(|s| s.round()).collect();
            (k.clone(), json!(arr))
        })
        .collect::<serde_json::Map<_, _>>()
        .into();

    let hrv_by_night: Vec<Option<f64>> = nights.iter().map(|n| mean(&n.rmssd)).collect();
    let rhr_by_night: Vec<Option<f64>> = nights
        .iter()
        .map(|n| n.hr.iter().cloned().reduce(f64::min))
        .collect();
    let hrv_stat = vital_stat(&hrv_by_night);
    let rhr_stat = vital_stat(&rhr_by_night);
    let trend = |stat: &VitalStat| -> Value {
        let (series, latest, base) = stat;
        json!({
            "series": series.iter().map(|x| x.round()).collect::<Vec<_>>(),
            "latest": latest.map(|x| x.round()),
            "baseline": base.map(|b| (b * 10.0).round() / 10.0),
            "delta_pct": vital_delta_pct(stat),
        })
    };

    let has = |evname: &str| present_recent.contains(evname);
    let modes = read_feature_modes(db);
    let cap_on = |feature: &str, present: bool| -> bool {
        modes
            .get(feature)
            .and_then(Value::as_i64)
            .map(|m| m != 0)
            .unwrap_or(present)
    };
    let measuring = json!([
        feat(
            "Daytime HR",
            cap_on(
                "daytime_hr",
                has("ibi_and_amplitude_event") || has("green_ibi_quality_event")
            ),
            "daytime_hr"
        ),
        feat("SpO2", cap_on("spo2", has("spo2_r_pi_event") || has("spo2_event")), "spo2"),
        feat(
            "Exercise HR",
            cap_on("exercise_hr", has("ehr_trace_event")),
            "exercise_hr"
        ),
        feat(
            "Real steps",
            cap_on(
                "real_steps",
                has("real_step_event_feature_1") || has("real_step_event_feature_2")
            ),
            "real_steps"
        ),
        feat(
            "Cardio PPG (CVA)",
            cap_on("cva_ppg", has("cva_raw_ppg_data")),
            "cva_ppg"
        ),
    ]);

    let mut sc: std::collections::BTreeMap<&str, i64> = Default::default();
    for (_ds, tag, _j, _) in &events {
        let cat = match name_of(*tag) {
            "spo2_r_pi_event" | "spo2_event" => Some("Blood oxygen"),
            "ibi_and_amplitude_event" | "green_ibi_quality_event" => Some("Heart beats"),
            "ehr_trace_event" | "ehr_acm_intensity_event" => Some("Exercise HR"),
            "motion_event" | "sleep_acm_period" => Some("Motion"),
            "temp_event" | "sleep_temp_event" => Some("Skin temp"),
            "real_step_event_feature_1" | "real_step_event_feature_2" => Some("Steps"),
            "cva_raw_ppg_data" => Some("Cardio PPG"),
            _ => None,
        };
        if let Some(c) = cat {
            *sc.entry(c).or_insert(0) += 1;
        }
    }
    let mut sv: Vec<(&str, i64)> = sc.into_iter().collect();
    sv.sort_by_key(|b| std::cmp::Reverse(b.1));
    let streams = json!(sv
        .iter()
        .map(|(n, c)| json!({ "name": n, "count": c }))
        .collect::<Vec<_>>());

    let mut allc: std::collections::BTreeMap<&str, i64> = Default::default();
    for (_ds, tag, _j, _) in &events {
        *allc.entry(name_of(*tag)).or_insert(0) += 1;
    }
    let mut allv: Vec<(&str, i64)> = allc.into_iter().collect();
    allv.sort_by_key(|b| std::cmp::Reverse(b.1));
    let event_counts = json!(allv
        .iter()
        .map(|(n, c)| json!({ "name": n, "count": c }))
        .collect::<Vec<_>>());
    let insight = |name: &str, live: bool, why: &str| json!({"name": name, "status": if live {"live"} else {"gated"}, "why": why});
    let insights = json!([
        insight("Sleep stages", true, ""),
        insight(
            "Apnea / breathing",
            has("ibi_and_amplitude_event"),
            "needs overnight IBI"
        ),
        insight(
            "Cardiovascular age",
            has("cva_raw_ppg_data"),
            "enable cva_ppg"
        ),
        insight("SpO2", has("spo2_r_pi_event") || has("spo2_event"), "enable spo2"),
        insight("Activity sessions", true, ""),
        insight("HRV / resting HR", true, ""),
        insight(
            "Steps",
            has("real_step_event_feature_1") || has("real_step_event_feature_2"),
            "enable real_steps"
        ),
        insight("Stress / resilience", false, "needs cloud scores"),
    ]);
    // Battery. The debug_data stream carries historical `battery_pct` snapshots the
    // ring logged at some past time — so prefer the live reading `sync` captures (kind
    // "battery_percent", timestamped) and keep debug_data only as a fallback (and for
    // voltage, which the live read doesn't provide).
    let mut dbg_batt: Option<(i64, i64)> = None;
    for (_ds, tag, jstr, _) in &events {
        if name_of(*tag) == "debug_data" && jstr.contains("battery_pct") {
            if let Ok(v) = serde_json::from_str::<Value>(jstr) {
                if let Some(p) = v["battery_pct"].as_i64() {
                    dbg_batt = Some((p, v["voltage_mv"].as_i64().unwrap_or(0)));
                }
            }
        }
    }
    let live_batt = dev
        .as_ref()
        .and_then(|d| store.latest_reading(&d.0, "battery_percent").ok().flatten());

    let last_sync = dev.as_ref().map(|d| d.6).filter(|&t| t > 0);
    let synced_unix = last_sync.map(|t| t as f64);

    // The ring's own battery log, on the wall clock.
    let (battery_series, battery_cycles) = {
        let mut pts: Vec<(f64, i64, i64)> = Vec::new();
        for (event_idx, (ds, tag, jstr, _)) in events.iter().enumerate() {
            if name_of(*tag) != "debug_data" || !jstr.contains("battery_level_changed") {
                continue;
            }
            let Ok(v) = serde_json::from_str::<Value>(jstr) else {
                continue;
            };
            if let Some(p) = v["battery_pct"].as_i64() {
                pts.push((
                    unix_in_epoch(*ds, event_epochs[event_idx]),
                    p,
                    v["voltage_mv"].as_i64().unwrap_or(0),
                ));
            }
        }
        battery_history(pts)
    };

    // ONE battery figure, so the top bar and the battery panel cannot disagree.
    //
    // There are two sources and neither is reliably fresher, so take whichever carries the
    // later timestamp rather than hard-wiring a preference:
    //   * the live read `sync` performs (`readings`, kind "battery_percent"), taken at the
    //     moment of the sync — but only ever as recent as your last sync;
    //   * the newest point in the ring's own log, which is the last time the level actually
    //     *changed*, so it lags whenever the level has been steady.
    // Measured on real data the live read won by 18 min (69% at 10:15 vs 74% at 09:56),
    // which is why the panel showing the log's last point disagreed with the top bar.
    // Voltage only exists in the log, so it comes from there regardless.
    let log_batt = battery_series
        .last()
        .and_then(|p| Some((p["pct"].as_i64()?, p["mv"].as_i64()?, p["t"].as_f64()?)));
    let live_newer = match (live_batt, log_batt) {
        (Some((_, lt)), Some((_, _, gt))) => lt as f64 >= gt,
        (Some(_), None) => true,
        _ => false,
    };
    let (battery_pct, battery_as_of) = if live_newer {
        (
            live_batt.map(|(v, _)| v.round() as i64),
            live_batt.map(|(_, t)| t),
        )
    } else {
        (
            log_batt.map(|b| b.0).or(dbg_batt.map(|b| b.0)),
            log_batt.map(|b| b.2 as i64),
        )
    };
    let battery_v = log_batt
        .map(|b| b.1)
        .or(dbg_batt.map(|b| b.1))
        .map(|mv| (mv as f64 / 1000.0 * 100.0).round() / 100.0);

    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as f64)
        .unwrap_or(anchor_unix as f64);
    let device = json!({
        "serial": dev.as_ref().map(|d| d.0.clone()),
        "hardware_id": dev.as_ref().map(|d| d.1.clone()).filter(|s| !s.is_empty()),
        "firmware": dev.as_ref().map(|d| d.2.clone()).filter(|s| !s.is_empty()),
        "api_version": dev.as_ref().map(|d| d.3.clone()).filter(|s| !s.is_empty()),
        "mac": dev.as_ref().map(|d| d.4.clone()).filter(|s| !s.is_empty()),
        "synced": synced_unix.map(|s| date_label(s, tz)),
        "synced_hm": synced_unix.map(|s| hm(s, tz)),
        "fresh_hours": synced_unix.map(|s| ((now - s) / 3600.0 * 10.0).round() / 10.0),
        "days_of_data": ((epochs.iter().map(|e| (e.max_ds - e.min_ds) as f64).sum::<f64>() / 10.0 / 86400.0) * 10.0).round() / 10.0,
        "total_events": events.len(),
        "nights": nights.len(),
        // reported, not silently dropped — so the night count reconciles with the ring's
        // own bedtime_period count if anyone goes looking.
        "short_periods_excluded": short_periods_excluded,
        "battery_pct": battery_pct,
        "battery_v": battery_v,
        "battery_as_of": battery_as_of,
        "measuring": measuring,
        "streams": streams,
        "event_counts": event_counts,
        "next_cursor": dev.as_ref().map(|d| d.7),
        "insights": insights,
    });

    let digest = make_digest(&hrv_stat, &rhr_stat);

    Ok(json!({
        "generated_at": now,
        "tz": tz,
        "digest": digest,
        "device": device,
        "profile": demo.to_json(),
        "nights": nights_json,
        "sleep_debt": sleep_debt,
        "cardio": cva,
        "fitness": {
            "vo2max": (vo2max * 10.0).round() / 10.0,
            "hr_max_predicted": hr_max_predicted(demo.age).round(),
        },
        "activity": activity,
        "activity_profile": activity_profile,
        "activity_steps": activity_steps,
        "battery": { "series": battery_series, "cycles": battery_cycles },
        "activity_daily": activity_daily,
        "vitals": { "hrv": trend(&hrv_stat), "rhr": trend(&rhr_stat) },
    }))
}

#[cfg(test)]
mod tests {
    use super::{fit_ds_offsets, unix_from_offsets};

    /// Build one sync session: `n` events ending at counter `end_ds`, drained starting at
    /// host time `t0` at one event per second (so capture times track transfer, not
    /// generation).
    fn session(t0: i64, end_ds: i64, n: i64) -> Vec<(i64, i64)> {
        (0..n)
            .map(|i| (t0 + i, end_ds - (n - 1 - i) * 10))
            .collect()
    }

    #[test]
    fn caught_up_syncs_agree_on_one_offset() {
        // Two syncs, each fully drained: the counter reads 1000 at t=100 and 2000 at
        // t=200, i.e. exactly 10 ds/sec, so a single offset explains both.
        let mut rows = session(100, 1000, 1);
        rows.extend(session(200, 2000, 1));
        let offs = fit_ds_offsets(&mut rows);
        for ds in [0, 500, 1000, 1500, 2000] {
            let unix = unix_from_offsets(&offs, ds).unwrap();
            assert!((unix - ds as f64 / 10.0).abs() < 1e-6, "ds {ds} -> {unix}");
        }
    }

    #[test]
    fn a_partial_drain_is_corrected_by_the_next_sync() {
        // The 08:00 sync stops 3600 s (36 000 ds) short of the ring's true counter, so on
        // its own it would place everything an hour late. The 09:00 sync catches up and
        // supplies the tighter bound, which must win for the earlier data too.
        let partial = session(28_800, 252_000, 1); // offset would be 28800-25200 = 3600
        let stale = fit_ds_offsets(&mut partial.clone());
        assert!((stale[0].1 - 3600.0).abs() < 1e-6, "partial bound {stale:?}");

        let mut rows = partial;
        rows.extend(session(32_400, 324_000, 1)); // caught up: offset 32400-32400 = 0
        let offs = fit_ds_offsets(&mut rows);
        let unix = unix_from_offsets(&offs, 252_000).unwrap();
        assert!(
            (unix - 25_200.0).abs() < 1e-6,
            "the inflated bound should be discarded, got {unix}"
        );
    }

    #[test]
    fn earlier_timestamps_do_not_move_when_a_later_sync_arrives() {
        // The regression this whole change exists for: re-syncing must not slide history.
        let mut before = session(1000, 10_000, 40);
        before.extend(session(5000, 50_000, 40));
        let offs_before = fit_ds_offsets(&mut before.clone());

        let mut after = before;
        after.extend(session(9000, 90_000, 40)); // a third sync, a day later in ring time
        let offs_after = fit_ds_offsets(&mut after);

        for ds in (0..=50_000).step_by(2_500) {
            let a = unix_from_offsets(&offs_before, ds).unwrap();
            let b = unix_from_offsets(&offs_after, ds).unwrap();
            assert!((a - b).abs() < 1e-6, "ds {ds} moved {a} -> {b}");
        }
    }

    #[test]
    fn mapping_is_monotonic_across_offset_steps() {
        // A counter that loses an hour between two regimes still maps monotonically.
        let mut rows = session(1000, 10_000, 5);
        rows.extend(session(9000, 50_000, 5)); // 8000 s of wall time for 4000 s of counter
        let offs = fit_ds_offsets(&mut rows);
        let mut prev = f64::NEG_INFINITY;
        for ds in (0..=50_000).step_by(500) {
            let u = unix_from_offsets(&offs, ds).unwrap();
            assert!(u >= prev, "ds {ds}: {u} < {prev}");
            prev = u;
        }
    }
}
