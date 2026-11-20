//! Sleep stages worked out on this device, for nights the models did not stage.
//!
//! Without a SleepNet runner (the Android app has none) a night's hypnogram used to be the
//! ring's own `sleep_phase_data`. This stages it instead with the tree-ensemble stager in
//! `oura-analysis` (`sleepstaging`, from upstream Th0rgal/open_oura#36), from the night's
//! inter-beat intervals, motion and skin temperature.
//!
//! The stager takes about a second a night on a laptop core and more on a phone, so each
//! night is staged once and kept in the store's `sleep_stage_cache`. A recompute reuses
//! what is kept, re-stages a recent night that has gained data since, and stages new
//! nights newest first until its time budget runs out; the rest wait for the next
//! recompute and show the ring's own hypnogram meanwhile.

use std::time::{Duration, Instant};

use oura_analysis::ported::sleepstaging::{events::collect, pipeline};
use oura_store::{StageCacheKey, Store};

/// Names the stager in the cache. Change it when the model or the pipeline changes, so old
/// results are not mixed with new ones.
pub const MODEL: &str = "bundled-gbt-800x4";

/// The stager's inputs: `ibi_and_amplitude_event`, `sleep_acm_period`, `sleep_temp_event`.
const INPUT_TAGS: [u8; 3] = [0x60, 0x72, 0x75];

/// The stager reads 10 minutes either side of the window; so does the fetch.
const MARGIN_DS: i64 = 6000;

/// A night whose window ended within this of the newest capture may still be gaining
/// events from later syncs, so its cached stages are checked against a fresh count.
const SETTLING_S: i64 = 3 * 86_400;

/// One night to stage.
pub struct Window {
    pub start_ds: i64,
    pub end_ds: i64,
    /// Capture-time range of the boot the night belongs to.
    pub capture: (i64, i64),
    pub start_unix: f64,
    pub end_unix: f64,
}

/// Where a night's stages came from, for the caller to report.
pub enum Staged {
    /// 30 s stage codes from `start_unix`, 1 deep, 2 light, 3 REM, 4 wake.
    Stages(Vec<i64>),
    /// Not staged this time (over budget, or not enough data); use something else.
    None,
}

/// What one call did, reported in the summary as `stager`.
#[derive(Debug, Default, Clone, Copy)]
pub struct Stats {
    /// Nights taken from the cache.
    pub reused: usize,
    /// Nights staged now (including ones that turned out to have too little data).
    pub staged: usize,
    /// Nights left for a later recompute because the budget ran out.
    pub pending: usize,
}

/// Stage `windows` (newest first is the caller's order), reusing the cache. `to_unix` maps
/// ring time to Unix seconds for a window's boot. Returns one result per window.
pub fn stage_nights(
    store: &Store,
    serial: &str,
    windows: &[Window],
    to_unix: &dyn Fn(usize, i64) -> f64,
    scalars: [f32; 3],
    newest_capture: i64,
    budget: Duration,
) -> (Vec<Staged>, Stats) {
    let began = Instant::now();
    let mut out = Vec::with_capacity(windows.len());
    let mut stats = Stats::default();
    for (i, w) in windows.iter().enumerate() {
        let key = StageCacheKey {
            serial: serial.to_string(),
            start_ds: w.start_ds,
            end_ds: w.end_ds,
            capture_lo: w.capture.0,
            model: MODEL.to_string(),
        };
        let ds = (w.start_ds - MARGIN_DS, w.end_ds + MARGIN_DS);
        let cached = store.stage_cache_get(&key).ok().flatten();
        let settling = newest_capture - w.end_unix as i64 <= SETTLING_S;
        let count = || store.count_events_in_window(serial, &INPUT_TAGS, ds, w.capture).ok();
        if let Some((inputs, json)) = &cached {
            let fresh = !settling || count() == Some(*inputs);
            if fresh {
                if let Ok(codes) = serde_json::from_str::<Vec<i64>>(json) {
                    stats.reused += 1;
                    out.push(if codes.is_empty() { Staged::None } else { Staged::Stages(codes) });
                    continue;
                }
            }
        }
        if began.elapsed() >= budget {
            stats.pending += 1;
            out.push(Staged::None);
            continue;
        }
        let Ok(rows) = store.decoded_events_in_window(serial, &INPUT_TAGS, ds, w.capture) else {
            out.push(Staged::None);
            continue;
        };
        let inputs = rows.len() as i64;
        let codes = stage_one(&rows, w, &|d| to_unix(i, d), scalars).unwrap_or_default();
        // Kept even when empty: a night without the data to stage it is not tried again
        // until it gains some.
        if let Ok(json) = serde_json::to_string(&codes) {
            let _ = store.stage_cache_put(&key, inputs, &json);
        }
        stats.staged += 1;
        out.push(if codes.is_empty() { Staged::None } else { Staged::Stages(codes) });
    }
    (out, stats)
}

/// Stage one night; an empty result when the stager cannot.
fn stage_one(
    rows: &[(i64, u8, String, i64)],
    w: &Window,
    to_unix: &dyn Fn(i64) -> f64,
    scalars: [f32; 3],
) -> Option<Vec<i64>> {
    // No ring `time_sync` rows are passed, so `collect` dates everything with our own clock.
    let inp = collect(rows, &|ds, _| to_unix(ds), w.start_ds, w.end_ds, 0, scalars).ok()?;
    let ibi_ts: Vec<i64> = inp.beats.iter().map(|b| b.0).collect();
    let ibi: Vec<[f32; 3]> = inp.beats.iter().map(|b| [b.1, b.2, b.3]).collect();
    let acm_ts: Vec<i64> = inp.acm.iter().map(|a| a.0).collect();
    let acm: Vec<[f32; 6]> = inp.acm.iter().map(|a| a.1).collect();
    let temp_ts: Vec<i64> = inp.temp.iter().map(|t| t.0).collect();
    let temp: Vec<f32> = inp.temp.iter().map(|t| t.1).collect();
    let out = pipeline::stage_night_default(
        &pipeline::Inputs {
            acm_values: &acm,
            acm_timestamps: &acm_ts,
            ibi_values: &ibi,
            ibi_timestamps: &ibi_ts,
            temp_values: &temp,
            temp_timestamps: &temp_ts,
            scalars: inp.scalars,
            bedtime: [inp.start_ms, inp.end_ms],
        },
        true,
        true,
    )
    .ok()?;
    Some(on_grid(&out.timestamps_ms, |i| out.stages.at(i, 0), w))
}

/// The stager's epochs placed on the summary's 30 s grid from the window start. Epochs it
/// trimmed off as wake, and any it left unlabelled (`0`), read as wake, as the app reads them.
fn on_grid(timestamps_ms: &[i64], label: impl Fn(usize) -> f32, w: &Window) -> Vec<i64> {
    let start_ms = (w.start_unix * 1000.0).round() as i64;
    let n = ((w.end_unix - w.start_unix) / 30.0).ceil().max(0.0) as usize;
    let mut grid = vec![4i64; n];
    let mut any_sleep = false;
    for (i, &ts) in timestamps_ms.iter().enumerate() {
        let k = ((ts - start_ms) as f64 / 30_000.0).round();
        if k < 0.0 || k as usize >= n {
            continue;
        }
        let code = label(i) as i64;
        if (1..=3).contains(&code) {
            any_sleep = true;
            grid[k as usize] = code;
        }
    }
    // A night the false-nap check cleared, or one with no sleep at all, has no hypnogram.
    if any_sleep {
        grid
    } else {
        Vec::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epochs_land_on_the_grid_and_gaps_read_as_wake() {
        let w = Window { start_ds: 0, end_ds: 0, capture: (0, 0), start_unix: 1000.0, end_unix: 1000.0 + 150.0 };
        // Five epochs; the stager covered the second to fourth, one unlabelled.
        let ts = [1_030_000, 1_060_000, 1_090_000];
        let labels = [1.0, 0.0, 3.0];
        let g = on_grid(&ts, |i| labels[i], &w);
        assert_eq!(g, vec![4, 1, 4, 3, 4]);
    }

    #[test]
    fn a_night_with_no_sleep_has_no_hypnogram() {
        let w = Window { start_ds: 0, end_ds: 0, capture: (0, 0), start_unix: 0.0, end_unix: 90.0 };
        assert!(on_grid(&[0, 30_000], |_| 4.0, &w).is_empty());
    }
}
