//! The pulse's shape, from the ring's raw PPG (`cva_raw_ppg_data`, tag `0x81`).
//!
//! Our own measure, not Oura's: Oura reads this waveform with its CVA model, which is not
//! bundled. This takes one published, model-free index from it — the **b/a ratio** of the
//! second-derivative photoplethysmogram (Takazawa et al., *Hypertension* 1998; Imanaga et al.,
//! 1998). The second derivative of a pulse's upstroke has an early positive wave `a` and a
//! negative wave `b` after it; `b/a` grows less negative as arteries stiffen with age. It is
//! established on fingertip sensors, not on a ring at the base of the finger, so the app
//! shows it as a trend against your own nights, not as an age.
//!
//! Per burst (about 30 s of samples, overnight, when Cardio PPG is on):
//!
//! 1. Rebuild the waveform: each byte is a signed delta off the previous sample, and `0x80`
//!    starts a signed 24-bit absolute sample. The running value carries across the events
//!    of a burst. The sample rate is the burst's samples over its span in ring time — about
//!    52 Hz on a Gen3, and the pulse rate it gives matches the ring's own heart rate.
//! 2. Remove the baseline (a 1.5 s moving average), smooth lightly, trim the edges.
//! 3. Turn the pulse the right way up: a pulse rises fast and falls slowly.
//! 4. Find the pulse rate from the spectrum, the beats from its peaks and the feet between
//!    them; keep beats within 20% of the median length.
//! 5. Resample each beat to 200 points, scale it to 0..1, and keep those that correlate
//!    above 0.9 with the median beat — at least six. Average them into one pulse.
//! 6. On that pulse: `a` is the second derivative's highest point on the upstroke, `b` its
//!    lowest point after `a` and before 0.1 s past the systolic peak.
//!
//! A night's value is the median over its usable bursts, when there are at least three.
//!
//! Why not the stiffness index (height over the time between the systolic and the
//! reflected peak): the prototype on this user's 336 usable bursts found a reflected peak in
//! only 42 of them. At 52 Hz from the base of the finger it is mostly not there to time.

/// Bursts are split where events are more than this apart in ring time (2 s).
const GAP_DS: i64 = 20;
const MIN_SAMPLES: usize = 600;
const MIN_SPAN_S: f64 = 5.0;
const BEAT_POINTS: usize = 200;
const MIN_BEATS: usize = 6;
/// A night needs this many usable bursts.
pub const MIN_BURSTS: usize = 3;

/// One burst's result.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BurstShape {
    pub b_over_a: f64,
    pub pulse_bpm: f64,
    /// Beats averaged into the pulse.
    pub beats: usize,
}

/// A night's result.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NightShape {
    pub b_over_a: f64,
    pub pulse_bpm: f64,
    /// Bursts recorded, and how many were clean enough to use.
    pub bursts: usize,
    pub used: usize,
}

/// Names the per-night results in the store's stage cache. Change it when this module's
/// method changes, so old results are recomputed.
pub const CACHE_NAME: &str = "pulse-shape-v1";

/// A night's result as the cache keeps it: `[b/a, pulse, bursts, used]`, or `[]` for none.
pub fn to_cache(n: Option<NightShape>) -> String {
    n.map_or("[]".to_string(), |n| {
        format!("[{},{},{},{}]", n.b_over_a, n.pulse_bpm, n.bursts, n.used)
    })
}

pub fn from_cache(json: &str) -> Option<NightShape> {
    let v: Vec<f64> = serde_json::from_str(json).ok()?;
    match v[..] {
        [b, p, bursts, used] => Some(NightShape {
            b_over_a: b,
            pulse_bpm: p,
            bursts: bursts as usize,
            used: used as usize,
        }),
        _ => None,
    }
}

/// The waveform of one burst's events, the running value carried from event to event.
pub fn reconstruct<'a>(bodies: impl IntoIterator<Item = &'a [u8]>) -> Vec<f64> {
    let mut out = Vec::new();
    let mut acc: i32 = 0;
    for body in bodies {
        let mut i = 0;
        while i < body.len() {
            let b = body[i];
            if b == 0x80 && i + 3 < body.len() {
                let raw =
                    body[i + 1] as u32 | (body[i + 2] as u32) << 8 | (body[i + 3] as u32) << 16;
                acc = if raw & 0x80_0000 != 0 {
                    (raw | 0xff00_0000) as i32
                } else {
                    raw as i32
                };
                i += 4;
            } else {
                acc = acc.wrapping_add(i32::from(b as i8));
                i += 1;
            }
            out.push(acc as f64);
        }
    }
    out
}

/// Split `(ring ds, body)` rows, in arrival order, into bursts: `(first ds, last ds,
/// waveform)`. A gap over 2 s, or ring time going backwards, starts a new one.
pub fn bursts(rows: &[(i64, Vec<u8>)]) -> Vec<(i64, i64, Vec<f64>)> {
    let mut out = Vec::new();
    let mut start = 0;
    for i in 1..=rows.len() {
        let split =
            i == rows.len() || rows[i].0 - rows[i - 1].0 > GAP_DS || rows[i].0 < rows[i - 1].0;
        if split {
            let run = &rows[start..i];
            out.push((
                run[0].0,
                run[run.len() - 1].0,
                reconstruct(run.iter().map(|r| r.1.as_slice())),
            ));
            start = i;
        }
    }
    out
}

/// The night's value from its rows; `None` under [`MIN_BURSTS`] usable bursts.
pub fn night(rows: &[(i64, Vec<u8>)]) -> Option<NightShape> {
    if rows.is_empty() {
        return None;
    }
    let all = bursts(rows);
    let used: Vec<BurstShape> = all
        .iter()
        .filter_map(|(s, e, w)| {
            let span = (e - s) as f64 / 10.0;
            if w.len() < MIN_SAMPLES || span < MIN_SPAN_S {
                return None;
            }
            analyse(w, (w.len() as f64 / span).clamp(40.0, 70.0))
        })
        .collect();
    if used.len() < MIN_BURSTS {
        return None;
    }
    Some(NightShape {
        b_over_a: median(&used.iter().map(|b| b.b_over_a).collect::<Vec<_>>()),
        pulse_bpm: median(&used.iter().map(|b| b.pulse_bpm).collect::<Vec<_>>()),
        bursts: all.len(),
        used: used.len(),
    })
}

/// One burst at `fs` samples a second; `None` when it is too short or too noisy.
pub fn analyse(w: &[f64], fs: f64) -> Option<BurstShape> {
    let base = moving_average(w, (fs * 1.5) as usize);
    let x0: Vec<f64> = w.iter().zip(&base).map(|(a, b)| a - b).collect();
    let x0 = moving_average(&x0, 3);
    let trim = (fs * 1.5) as usize;
    if x0.len() <= 2 * trim {
        return None;
    }
    let mut x = x0[trim..x0.len() - trim].to_vec();
    if (x.len() as f64) < fs * 8.0 {
        return None;
    }
    // A pulse rises fast and falls slowly: the sign whose steepest slopes are rises.
    let d: Vec<f64> = x.windows(2).map(|p| p[1] - p[0]).collect();
    if percentile(&d, 99.0) < -percentile(&d, 1.0) {
        x.iter_mut().for_each(|v| *v = -*v);
    }

    let hz = pulse_hz(&x, fs)?;
    let period = fs / hz;
    let min_gap = (period * 0.6) as usize;
    let thr = percentile(&x, 60.0);
    let mut peaks: Vec<usize> = Vec::new();
    for p in 1..x.len() - 1 {
        if !(x[p] > x[p - 1] && x[p] >= x[p + 1]) || x[p] < thr {
            continue;
        }
        if let Some(&last) = peaks.last() {
            if p - last < min_gap {
                if x[p] > x[last] {
                    *peaks.last_mut().unwrap() = p;
                }
                continue;
            }
        }
        peaks.push(p);
    }
    if peaks.len() < MIN_BEATS {
        return None;
    }
    let feet: Vec<usize> = peaks
        .windows(2)
        .map(|w| w[0] + argmin(&x[w[0]..w[1]]))
        .collect();
    let beats: Vec<(usize, usize)> = feet.windows(2).map(|f| (f[0], f[1])).collect();
    let lens: Vec<f64> = beats.iter().map(|(a, b)| (b - a) as f64).collect();
    if lens.is_empty() {
        return None;
    }
    let med = median(&lens);
    let good: Vec<(usize, usize)> = beats
        .iter()
        .copied()
        .filter(|(a, b)| ((*b - *a) as f64 - med).abs() < 0.2 * med)
        .collect();
    if good.len() < MIN_BEATS {
        return None;
    }

    // Each beat on 200 points, scaled to 0..1.
    let n = BEAT_POINTS;
    let resampled: Vec<Vec<f64>> = good
        .iter()
        .map(|&(a, b)| {
            let v: Vec<f64> = (0..n)
                .map(|k| interp(&x, a as f64 + (b - a) as f64 * k as f64 / (n - 1) as f64))
                .collect();
            let lo = v.iter().cloned().fold(f64::INFINITY, f64::min);
            let hi = v.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            v.iter().map(|y| (y - lo) / (hi - lo + 1e-9)).collect()
        })
        .collect();
    let template: Vec<f64> = (0..n)
        .map(|k| median(&resampled.iter().map(|b| b[k]).collect::<Vec<_>>()))
        .collect();
    let kept: Vec<&Vec<f64>> = resampled
        .iter()
        .filter(|b| correlation(b, &template) > 0.9)
        .collect();
    if kept.len() < MIN_BEATS {
        return None;
    }
    let mean: Vec<f64> = (0..n)
        .map(|k| kept.iter().map(|b| b[k]).sum::<f64>() / kept.len() as f64)
        .collect();
    let pulse = moving_average(&mean, 5);
    let dt = med / fs / n as f64;

    let sp = argmax(&pulse[..(n as f64 * 0.6) as usize]);
    let d1 = gradient(&pulse, dt);
    let d2 = gradient(&d1, dt);
    if sp + 1 < 5 {
        return None;
    }
    let a_i = argmax(&d2[..=sp]);
    let end = (sp + (0.1 / dt) as usize).min(d2.len());
    if end <= a_i || d2[a_i] <= 0.0 {
        return None;
    }
    let b_i = a_i + argmin(&d2[a_i..end]);
    Some(BurstShape {
        b_over_a: d2[b_i] / d2[a_i],
        pulse_bpm: hz * 60.0,
        beats: kept.len(),
    })
}

/// The strongest frequency between 0.6 and 2.5 Hz, on the same bins as a real FFT of the
/// Hann-windowed signal.
fn pulse_hz(x: &[f64], fs: f64) -> Option<f64> {
    let n = x.len();
    let w: Vec<f64> = (0..n)
        .map(|i| {
            x[i] * (0.5 - 0.5 * (2.0 * std::f64::consts::PI * i as f64 / (n - 1) as f64).cos())
        })
        .collect();
    let mut best: Option<(f64, f64)> = None;
    for k in 0..=n / 2 {
        let f = k as f64 * fs / n as f64;
        if f <= 0.6 || f >= 2.5 {
            continue;
        }
        // The phasor turned by multiplication rather than a cos/sin per sample.
        let step = -2.0 * std::f64::consts::PI * k as f64 / n as f64;
        let (sr, si) = (step.cos(), step.sin());
        let (mut cr, mut ci) = (1.0f64, 0.0f64);
        let (mut re, mut im) = (0.0, 0.0);
        for v in &w {
            re += v * cr;
            im += v * ci;
            (cr, ci) = (cr * sr - ci * si, cr * si + ci * sr);
        }
        let p = re.hypot(im);
        if best.is_none_or(|(_, bp)| p > bp) {
            best = Some((f, p));
        }
    }
    best.map(|(f, _)| f)
}

/// `numpy.convolve(x, ones(n)/n, "same")`: centred, zeros beyond the ends. Prefix sums,
/// so the 1.5 s baseline costs the same as the 3-sample smoothing.
fn moving_average(x: &[f64], n: usize) -> Vec<f64> {
    let n = n.max(1);
    let off = (n - 1) / 2;
    let mut prefix = Vec::with_capacity(x.len() + 1);
    prefix.push(0.0);
    for v in x {
        prefix.push(prefix[prefix.len() - 1] + v);
    }
    (0..x.len())
        .map(|i| {
            // Window x[i + off - (n - 1) ..= i + off], clipped to the signal.
            let hi = (i + off + 1).min(x.len());
            let lo = (i + off + 1).saturating_sub(n);
            (prefix[hi] - prefix[lo]) / n as f64
        })
        .collect()
}

/// `numpy.gradient`: central differences inside, one-sided at the ends.
fn gradient(y: &[f64], dt: f64) -> Vec<f64> {
    let n = y.len();
    (0..n)
        .map(|i| match i {
            0 => (y[1] - y[0]) / dt,
            _ if i == n - 1 => (y[n - 1] - y[n - 2]) / dt,
            _ => (y[i + 1] - y[i - 1]) / (2.0 * dt),
        })
        .collect()
}

fn interp(x: &[f64], t: f64) -> f64 {
    let i = (t.floor() as usize).min(x.len() - 1);
    if i + 1 >= x.len() {
        return x[x.len() - 1];
    }
    let f = t - i as f64;
    x[i] * (1.0 - f) + x[i + 1] * f
}

fn correlation(a: &[f64], b: &[f64]) -> f64 {
    let n = a.len() as f64;
    let (ma, mb) = (a.iter().sum::<f64>() / n, b.iter().sum::<f64>() / n);
    let (mut cov, mut va, mut vb) = (0.0, 0.0, 0.0);
    for (x, y) in a.iter().zip(b) {
        cov += (x - ma) * (y - mb);
        va += (x - ma).powi(2);
        vb += (y - mb).powi(2);
    }
    cov / (va * vb).sqrt()
}

/// `numpy.percentile` with linear interpolation.
fn percentile(x: &[f64], p: f64) -> f64 {
    let mut v = x.to_vec();
    v.sort_by(f64::total_cmp);
    let pos = p / 100.0 * (v.len() - 1) as f64;
    let i = pos.floor() as usize;
    let f = pos - i as f64;
    if i + 1 < v.len() {
        v[i] * (1.0 - f) + v[i + 1] * f
    } else {
        v[i]
    }
}

fn median(x: &[f64]) -> f64 {
    percentile(x, 50.0)
}

fn argmax(x: &[f64]) -> usize {
    x.iter()
        .enumerate()
        .fold(0, |b, (i, v)| if *v > x[b] { i } else { b })
}

fn argmin(x: &[f64]) -> usize {
    x.iter()
        .enumerate()
        .fold(0, |b, (i, v)| if *v < x[b] { i } else { b })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_waveform_carries_across_events_and_resets_on_an_absolute_sample() {
        // 0x80 + 24-bit 1000, then +2, -1 in the next event, then an absolute -5.
        let a = [0x80u8, 0xe8, 0x03, 0x00, 0x02];
        let b = [0xffu8, 0x80, 0xfb, 0xff, 0xff, 0x01];
        assert_eq!(
            reconstruct([&a[..], &b[..]]),
            vec![1000.0, 1002.0, 1001.0, -5.0, -4.0]
        );
    }

    #[test]
    fn a_night_survives_the_cache() {
        let n = NightShape {
            b_over_a: -0.704,
            pulse_bpm: 61.5,
            bursts: 12,
            used: 10,
        };
        assert_eq!(from_cache(&to_cache(Some(n))), Some(n));
        assert_eq!(from_cache(&to_cache(None)), None);
    }

    #[test]
    fn bursts_split_on_a_gap_or_a_reset() {
        let rows = vec![
            (100, vec![1u8]),
            (105, vec![1]),
            (200, vec![1]),
            (50, vec![1]),
        ];
        let b = bursts(&rows);
        assert_eq!(
            b.iter().map(|r| (r.0, r.1)).collect::<Vec<_>>(),
            vec![(100, 105), (200, 200), (50, 50)]
        );
    }

    /// A synthetic pulse: a fast rise, a slow fall, a small reflected wave, at 60 bpm.
    fn synthetic(fs: f64, secs: f64) -> Vec<f64> {
        (0..(fs * secs) as usize)
            .map(|i| {
                let t = (i as f64 / fs) % 1.0;
                let rise = (-((t - 0.15) / 0.05).powi(2)).exp();
                let fall = if t > 0.15 {
                    (-(t - 0.15) / 0.25).exp() * 0.6
                } else {
                    0.0
                };
                let wave = 0.15 * (-((t - 0.45) / 0.06).powi(2)).exp();
                1000.0 * (rise + fall + wave)
            })
            .collect()
    }

    #[test]
    fn a_clean_pulse_gives_its_rate_and_a_negative_b_over_a() {
        let r = analyse(&synthetic(52.0, 30.0), 52.0).expect("a clean pulse is usable");
        assert!((r.pulse_bpm - 60.0).abs() < 3.0, "{r:?}");
        assert!(r.b_over_a < 0.0 && r.b_over_a > -5.0, "{r:?}");
        assert!(r.beats >= MIN_BEATS);
    }

    #[test]
    fn noise_is_not_a_pulse() {
        let mut seed = 7u64;
        let noise: Vec<f64> = (0..1560)
            .map(|_| {
                seed = seed
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                (seed >> 33) as f64 / 1e6
            })
            .collect();
        assert!(analyse(&noise, 52.0).is_none());
    }
}
