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

use oura_analysis::ported::breathing_rate::breathing_rate;
use oura_analysis::ported::sleepstaging::{
    events::{collect, StagerInputs},
    pipeline,
};
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

/// Names the breathing-rate results in the same cache, beside the stages. Change it when
/// the breathing-rate code changes.
pub const BREATHING: &str = "breathing-rate-v1";

/// A night's breathing rate and its variability, in breaths per minute.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Breathing {
    pub rate: f32,
    pub variability: f32,
}

/// Stage `windows` (newest first is the caller's order), reusing the cache, and work out
/// each night's breathing rate from the same beats. `to_unix` maps ring time to Unix
/// seconds for a window's boot. Returns one result per window.
///
/// The two are cached separately, under [`MODEL`] and [`BREATHING`], so a night staged
/// before breathing rate existed gains one without being staged again: the stager is the
/// expensive part, and the breathing rate needs only the night's events read back.
pub fn stage_nights(
    store: &Store,
    serial: &str,
    windows: &[Window],
    to_unix: &dyn Fn(usize, i64) -> f64,
    scalars: [f32; 3],
    newest_capture: i64,
    budget: Duration,
) -> (Vec<(Staged, Option<Breathing>)>, Stats) {
    let began = Instant::now();
    let mut out = Vec::with_capacity(windows.len());
    let mut stats = Stats::default();
    for (i, w) in windows.iter().enumerate() {
        let key = |model: &str| StageCacheKey {
            serial: serial.to_string(),
            start_ds: w.start_ds,
            end_ds: w.end_ds,
            capture_lo: w.capture.0,
            model: model.to_string(),
        };
        let (stage_key, breath_key) = (key(MODEL), key(BREATHING));
        let ds = (w.start_ds - MARGIN_DS, w.end_ds + MARGIN_DS);
        let settling = newest_capture - w.end_unix as i64 <= SETTLING_S;
        // The input count, read at most once: only a settling night needs it.
        let mut counted: Option<Option<i64>> = None;
        let mut fresh = |inputs: i64| {
            !settling
                || *counted.get_or_insert_with(|| {
                    store
                        .count_events_in_window(serial, &INPUT_TAGS, ds, w.capture)
                        .ok()
                }) == Some(inputs)
        };
        let stages: Option<Vec<i64>> = store
            .stage_cache_get(&stage_key)
            .ok()
            .flatten()
            .filter(|(inputs, _)| fresh(*inputs))
            .and_then(|(_, json)| serde_json::from_str(&json).ok());
        let breathing: Option<Option<Breathing>> = store
            .stage_cache_get(&breath_key)
            .ok()
            .flatten()
            .filter(|(inputs, _)| fresh(*inputs))
            .and_then(|(_, json)| serde_json::from_str::<Vec<f32>>(&json).ok())
            .map(|v| match v[..] {
                [rate, variability] => Some(Breathing { rate, variability }),
                _ => None,
            });
        if let (Some(codes), Some(b)) = (&stages, breathing) {
            stats.reused += 1;
            out.push((staged(codes.clone()), b));
            continue;
        }
        if began.elapsed() >= budget {
            stats.pending += 1;
            out.push((
                stages.map(staged).unwrap_or(Staged::None),
                breathing.flatten(),
            ));
            continue;
        }
        let Ok(rows) = store.decoded_events_in_window(serial, &INPUT_TAGS, ds, w.capture) else {
            out.push((Staged::None, None));
            continue;
        };
        let inputs = rows.len() as i64;
        let collected = collect(
            &rows,
            &|d, _| to_unix(i, d),
            w.start_ds,
            w.end_ds,
            0,
            scalars,
        )
        .ok();
        let codes = match stages {
            Some(codes) => {
                stats.reused += 1;
                codes
            }
            None => {
                let codes = collected
                    .as_ref()
                    .and_then(|inp| stage_one(inp, w))
                    .unwrap_or_default();
                // Kept even when empty: a night without the data to stage it is not tried
                // again until it gains some.
                if let Ok(json) = serde_json::to_string(&codes) {
                    let _ = store.stage_cache_put(&stage_key, inputs, &json);
                }
                stats.staged += 1;
                codes
            }
        };
        let b = collected.as_ref().and_then(|inp| breathing_of(inp, w));
        let json = b.map_or("[]".to_string(), |b| {
            format!("[{},{}]", b.rate, b.variability)
        });
        let _ = store.stage_cache_put(&breath_key, inputs, &json);
        out.push((staged(codes), b));
    }
    (out, stats)
}

fn staged(codes: Vec<i64>) -> Staged {
    if codes.is_empty() {
        Staged::None
    } else {
        Staged::Stages(codes)
    }
}

/// The night's breathing rate from its corrected beats (`breathing_rate`, from upstream
/// Th0rgal/open_oura#32). Only the beats the corrector marked valid are used.
///
/// The port also takes one motion byte per 30 s epoch, but motion only decides each
/// epoch's usable flag, and the night average counts every epoch with beats whatever its
/// flag — so zeros are passed rather than inventing a conversion from the accelerometer
/// features. `None` for a night under three hours or without beats.
fn breathing_of(inp: &StagerInputs, w: &Window) -> Option<Breathing> {
    let start_ms = (w.start_unix * 1000.0).round() as i64;
    let epochs = ((w.end_unix - w.start_unix) / 30.0).ceil().max(0.0) as usize;
    let (mut ibi, mut ts) = (Vec::new(), Vec::new());
    for b in inp.beats.iter().filter(|b| b.3 == 1.0) {
        ibi.push(b.1.round().clamp(0.0, u16::MAX as f32) as u16);
        ts.push(b.0);
    }
    let (rate, variability, _hr) = breathing_rate(start_ms, &ibi, &ts, &vec![0u8; epochs]);
    (rate > 0.0).then_some(Breathing { rate, variability })
}

/// Stage one night from its collected inputs; `None` when the stager cannot.
fn stage_one(inp: &StagerInputs, w: &Window) -> Option<Vec<i64>> {
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
        let w = Window {
            start_ds: 0,
            end_ds: 0,
            capture: (0, 0),
            start_unix: 1000.0,
            end_unix: 1000.0 + 150.0,
        };
        // Five epochs; the stager covered the second to fourth, one unlabelled.
        let ts = [1_030_000, 1_060_000, 1_090_000];
        let labels = [1.0, 0.0, 3.0];
        let g = on_grid(&ts, |i| labels[i], &w);
        assert_eq!(g, vec![4, 1, 4, 3, 4]);
    }

    #[test]
    fn a_night_with_no_sleep_has_no_hypnogram() {
        let w = Window {
            start_ds: 0,
            end_ds: 0,
            capture: (0, 0),
            start_unix: 0.0,
            end_unix: 90.0,
        };
        assert!(on_grid(&[0, 30_000], |_| 4.0, &w).is_empty());
    }
}
