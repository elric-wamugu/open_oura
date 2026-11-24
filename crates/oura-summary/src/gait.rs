//! Walking and jogging, from the ring's own gait records (`real_steps_features_1/2`, tags
//! `0x7e`/`0x7f`).
//!
//! Our own reading, not Oura's: Oura feeds these records through its `steps_motion_decoder`
//! model, which is not bundled. The records themselves are bit-packed, and the packing is
//! known (`EventParser::parse_api_real_steps_features_1/2` in libringeventparser.so; the
//! Python twin is `unpack27` in `tools/run_activity_model.py`). A pair of 14-byte halves
//! unpacks into 27 quantized columns: three globals, then three blocks of eight, one block
//! per 10-second window. The ring writes a pair every 30 s while worn.
//!
//! What each column *means* is not documented anywhere we have. Three of them behave
//! unmistakably once you hold a walk and a jog side by side (2026-11-24, one person, a
//! 14-minute walk and a 10-minute jog, labelled by hand):
//!
//! | column in a block | at rest      | walking        | jogging        |
//! |-------------------|--------------|----------------|----------------|
//! | 0, "rate"         | 150–300, noisy | 330–370      | 420–465        |
//! | 1, "level"        | anything     | locked near 200 | locked near 380 |
//! | 6, "irregularity" | 30–150       | 0–5            | 3–15           |
//!
//! Irregularity near zero means the ring found a steady stepping rhythm; the two rate-like
//! columns then step up together from walking to jogging. "Rate" is likely cadence, but its
//! unit is not known yet, so it is reported raw and never as steps per minute.
//!
//! So a 10-second window is:
//! - **no gait** when its irregularity is above [`IRREGULAR_MAX`];
//! - **jogging** when rate and level are both at or above their splits;
//! - **walking** when both are below;
//! - **no gait** when they disagree — the honest answer to an ambiguous window.
//!
//! Windows join into segments ([`segments`]). There is no running label yet: no run has been
//! recorded to set its threshold, and jogging and running have no physical boundary between
//! them, so that line is a definition to choose from data, not to guess.

use serde_json::{json, Value};

/// Above this, the window has no steady stepping rhythm.
pub const IRREGULAR_MAX: u16 = 20;
/// The rate column's split between walking (below) and jogging. Walking sat at 330–370 and
/// jogging at 420–465; 400 sits in the gap.
pub const RATE_SPLIT: u16 = 400;
/// The level column's split. Walking locked near 200 and jogging near 380.
pub const LEVEL_SPLIT: u16 = 290;
/// Each record covers three 10-second windows, ending 20 s before, 10 s before, and at its
/// own timestamp (the layout `steps_motion_decoder` expands it to).
pub const WINDOW_S: f64 = 10.0;
/// Labelled windows further apart than this start a new run.
const RUN_GAP_S: f64 = 60.0;
/// A run shorter than this is a stray window or two, not a change of pace: it is dropped
/// before neighbouring runs are joined across it.
const MIN_RUN_S: f64 = 60.0;
/// Same-label runs this close together, once stray runs are gone, are one segment.
const MERGE_GAP_S: f64 = 120.0;
/// Shorter than this is not worth calling a segment.
pub const MIN_SEGMENT_S: f64 = 120.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Gait {
    Walking,
    Jogging,
}

impl Gait {
    pub fn name(self) -> &'static str {
        match self {
            Gait::Walking => "walking",
            Gait::Jogging => "jogging",
        }
    }
}

/// One 10-second window: when it ended, what it was, and its raw rate.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Window {
    pub end: f64,
    pub gait: Option<Gait>,
    pub rate: u16,
}

/// A stretch of one gait. `windows` is how many 10-second windows it spans, `labelled` how
/// many of those were read as this gait; `rate` is the median raw rate over the labelled.
#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    pub gait: Gait,
    pub start: f64,
    pub end: f64,
    pub windows: usize,
    pub labelled: usize,
    pub rate: u16,
}

/// The 27 quantized columns of one record, from its two 14-byte halves.
pub fn unpack(p1: &[u8], p2: &[u8]) -> Option<[u16; 27]> {
    if p1.len() != 14 || p2.len() != 14 {
        return None;
    }
    let (p1, p2): (Vec<u16>, Vec<u16>) = (
        p1.iter().map(|&b| b as u16).collect(),
        p2.iter().map(|&b| b as u16).collect(),
    );
    let c = p2[13]; // the carry byte: the ninth/tenth bits of the wider fields
    Some([
        (p2[10] << 2) | (c & 0x3),
        p2[11],
        p2[12],
        (p1[0] << 1) | (p1[3] >> 7),
        (p1[1] << 1) | ((c >> 7) & 1),
        (p1[2] << 1) | ((c >> 6) & 1),
        p1[3] & 0x7f,
        p1[4],
        p1[5],
        p1[6],
        p1[7],
        (p1[8] << 1) | (p1[11] >> 7),
        (p1[9] << 1) | ((c >> 5) & 1),
        (p1[10] << 1) | ((c >> 4) & 1),
        p1[11] & 0x7f,
        p1[12],
        p1[13],
        p2[0],
        p2[1],
        (p2[2] << 1) | (p2[5] >> 7),
        (p2[3] << 1) | ((c >> 3) & 1),
        (p2[4] << 1) | ((c >> 2) & 1),
        p2[5] & 0x7f,
        p2[6],
        p2[7],
        p2[8],
        p2[9],
    ])
}

/// Read one window from its block's rate, level and irregularity.
pub fn classify(rate: u16, level: u16, irregular: u16) -> Option<Gait> {
    if irregular > IRREGULAR_MAX {
        return None;
    }
    match (rate >= RATE_SPLIT, level >= LEVEL_SPLIT) {
        (true, true) => Some(Gait::Jogging),
        (false, false) => Some(Gait::Walking),
        _ => None,
    }
}

/// The three windows of one record ending at `t`, oldest first.
pub fn record_windows(cols: &[u16; 27], t: f64) -> [Window; 3] {
    std::array::from_fn(|k| {
        let b = 3 + 8 * k;
        Window {
            end: t - WINDOW_S * (2 - k) as f64,
            gait: classify(cols[b], cols[b + 1], cols[b + 6]),
            rate: cols[b],
        }
    })
}

/// Every window in one boot's raw records. `f1` and `f2` are `(ring ds, body)` for tags
/// `0x7e` and `0x7f`; the second half of a record is written one decisecond after the
/// first. `unix` maps that boot's ds to wall-clock seconds.
pub fn windows(
    f1: &[(i64, Vec<u8>)],
    f2: &[(i64, Vec<u8>)],
    unix: &dyn Fn(i64) -> f64,
) -> Vec<Window> {
    let second: std::collections::HashMap<i64, &[u8]> =
        f2.iter().map(|(ds, b)| (*ds, b.as_slice())).collect();
    let mut out: Vec<Window> = f1
        .iter()
        .filter_map(|(ds, b1)| {
            let cols = unpack(b1, second.get(&(ds + 1))?)?;
            Some(record_windows(&cols, unix(*ds)))
        })
        .flatten()
        .collect();
    out.sort_by(|a, b| a.end.total_cmp(&b.end));
    out
}

/// Join windows (sorted by time) into segments of one gait.
pub fn segments(windows: &[Window]) -> Vec<Segment> {
    // 1. Runs of one label, broken by a change of label or a long unlabelled stretch.
    let mut runs: Vec<(Gait, f64, f64, Vec<u16>)> = Vec::new();
    for w in windows {
        let Some(g) = w.gait else { continue };
        match runs.last_mut() {
            Some((rg, _, end, rates)) if *rg == g && w.end - *end <= RUN_GAP_S => {
                *end = w.end;
                rates.push(w.rate);
            }
            _ => runs.push((g, w.end - WINDOW_S, w.end, vec![w.rate])),
        }
    }
    // 2. Drop stray runs, so a lone jogging window inside a walk does not split it.
    runs.retain(|(_, s, e, _)| e - s >= MIN_RUN_S);
    // 3. Join same-label neighbours across the gaps the stray runs left.
    let mut joined: Vec<(Gait, f64, f64, Vec<u16>)> = Vec::new();
    for r in runs {
        match joined.last_mut() {
            Some(last) if last.0 == r.0 && r.1 - last.2 <= MERGE_GAP_S => {
                last.2 = r.2;
                last.3.extend(r.3);
            }
            _ => joined.push(r),
        }
    }
    joined
        .into_iter()
        .filter(|(_, s, e, _)| e - s >= MIN_SEGMENT_S)
        .map(|(gait, start, end, mut rates)| {
            rates.sort_unstable();
            Segment {
                gait,
                start,
                end,
                windows: ((end - start) / WINDOW_S).round() as usize,
                labelled: rates.len(),
                rate: rates[rates.len() / 2],
            }
        })
        .collect()
}

/// The segments as JSON, for the summary.
pub fn to_json(segs: &[Segment]) -> Value {
    Value::Array(
        segs.iter()
            .map(|s| {
                json!({
                    "label": s.gait.name(),
                    "start": s.start.round(),
                    "end": s.end.round(),
                    "duration_min": ((s.end - s.start) / 60.0).round(),
                    // Share of the segment's windows read as this gait, 0..1.
                    "coverage": ((s.labelled as f64 / s.windows.max(1) as f64).min(1.0) * 100.0).round() / 100.0,
                    // Raw and uncalibrated: likely cadence, unit unknown.
                    "rate_raw": s.rate,
                })
            })
            .collect(),
    )
}

/// The gait that covers the most of a session, when it covers at least a third of it.
/// A short walk to the bike rack must not name a ride.
pub fn dominant(segs: &[Segment], session_s: f64) -> Option<Gait> {
    let total = |g: Gait| -> f64 {
        segs.iter()
            .filter(|s| s.gait == g)
            .map(|s| s.end - s.start)
            .sum()
    };
    let (g, t) = [Gait::Walking, Gait::Jogging]
        .into_iter()
        .map(|g| (g, total(g)))
        .max_by(|a, b| a.1.total_cmp(&b.1))?;
    (t > 0.0 && t >= session_s / 3.0).then_some(g)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Pack 27 columns back into the two halves, the inverse of [`unpack`].
    fn pack(c: &[u16; 27]) -> (Vec<u8>, Vec<u8>) {
        let mut p1 = [0u8; 14];
        let mut p2 = [0u8; 14];
        let mut carry = (c[0] & 0x3) as u8;
        p2[10] = (c[0] >> 2) as u8;
        p2[11] = c[1] as u8;
        p2[12] = c[2] as u8;
        p1[0] = (c[3] >> 1) as u8;
        p1[3] = ((c[3] & 1) << 7) as u8 | (c[6] & 0x7f) as u8;
        p1[1] = (c[4] >> 1) as u8;
        carry |= ((c[4] & 1) << 7) as u8;
        p1[2] = (c[5] >> 1) as u8;
        carry |= ((c[5] & 1) << 6) as u8;
        p1[4..8].copy_from_slice(&[c[7] as u8, c[8] as u8, c[9] as u8, c[10] as u8]);
        p1[8] = (c[11] >> 1) as u8;
        p1[11] = ((c[11] & 1) << 7) as u8 | (c[14] & 0x7f) as u8;
        p1[9] = (c[12] >> 1) as u8;
        carry |= ((c[12] & 1) << 5) as u8;
        p1[10] = (c[13] >> 1) as u8;
        carry |= ((c[13] & 1) << 4) as u8;
        p1[12] = c[15] as u8;
        p1[13] = c[16] as u8;
        p2[0] = c[17] as u8;
        p2[1] = c[18] as u8;
        p2[2] = (c[19] >> 1) as u8;
        p2[5] = ((c[19] & 1) << 7) as u8 | (c[22] & 0x7f) as u8;
        p2[3] = (c[20] >> 1) as u8;
        carry |= ((c[20] & 1) << 3) as u8;
        p2[4] = (c[21] >> 1) as u8;
        carry |= ((c[21] & 1) << 2) as u8;
        p2[6..10].copy_from_slice(&[c[23] as u8, c[24] as u8, c[25] as u8, c[26] as u8]);
        p2[13] = carry;
        (p1.to_vec(), p2.to_vec())
    }

    /// A record whose three windows all read (rate, level, irregularity).
    fn record(rate: u16, level: u16, irregular: u16) -> [u16; 27] {
        let mut c = [0u16; 27];
        c[0] = 700;
        for k in 0..3 {
            let b = 3 + 8 * k;
            c[b] = rate;
            c[b + 1] = level;
            c[b + 6] = irregular;
        }
        c
    }

    #[test]
    fn unpack_inverts_the_packing() {
        let mut c = [0u16; 27];
        for (i, v) in c.iter_mut().enumerate() {
            // Wide fields take up to 9 or 10 bits, 7-bit fields 127, the rest a byte.
            *v = match i {
                0 => 873,
                3 | 4 | 5 | 11 | 12 | 13 | 19 | 20 | 21 => 300 + i as u16,
                6 | 14 | 22 => 100 + i as u16,
                _ => 200 + i as u16,
            };
        }
        let (p1, p2) = pack(&c);
        assert_eq!(unpack(&p1, &p2), Some(c));
        assert_eq!(unpack(&p1[..13], &p2), None);
    }

    #[test]
    fn a_window_is_walking_jogging_or_nothing() {
        // Values from the hand-labelled walk and jog of 2026-11-24.
        assert_eq!(classify(356, 205, 1), Some(Gait::Walking));
        assert_eq!(classify(437, 382, 5), Some(Gait::Jogging));
        // Steady enough, but the two rate columns disagree.
        assert_eq!(classify(402, 198, 4), None);
        // At rest: no rhythm.
        assert_eq!(classify(195, 309, 88), None);
    }

    /// Records every 30 s from `t0`, `n` of them, all reading the same.
    fn stretch(t0: f64, n: usize, rate: u16, level: u16, irregular: u16) -> Vec<(f64, [u16; 27])> {
        (0..n)
            .map(|i| (t0 + 30.0 * i as f64, record(rate, level, irregular)))
            .collect()
    }

    fn windows_of(recs: &[(f64, [u16; 27])]) -> Vec<Window> {
        let f1: Vec<(i64, Vec<u8>)> = recs
            .iter()
            .map(|(t, c)| ((*t * 10.0) as i64, pack(c).0))
            .collect();
        let f2: Vec<(i64, Vec<u8>)> = recs
            .iter()
            .map(|(t, c)| ((*t * 10.0) as i64 + 1, pack(c).1))
            .collect();
        windows(&f1, &f2, &|ds| ds as f64 / 10.0)
    }

    #[test]
    fn a_walk_then_a_jog_are_two_segments() {
        let mut recs = stretch(0.0, 10, 200, 300, 90); // rest
        recs.extend(stretch(300.0, 20, 350, 200, 2)); // 10 min walking
        recs.extend(stretch(900.0, 16, 440, 380, 6)); // 8 min jogging
        recs.extend(stretch(1380.0, 6, 220, 100, 120)); // rest
        let segs = segments(&windows_of(&recs));
        assert_eq!(segs.len(), 2, "{segs:?}");
        assert_eq!(segs[0].gait, Gait::Walking);
        assert_eq!(segs[0].rate, 350);
        assert_eq!(segs[1].gait, Gait::Jogging);
        assert!((segs[1].end - segs[1].start - 480.0).abs() <= 30.0);
        assert_eq!(dominant(&segs, 1400.0), Some(Gait::Walking));
    }

    #[test]
    fn a_stray_window_does_not_split_a_walk() {
        let mut recs = stretch(0.0, 10, 350, 200, 2);
        recs.push((300.0, record(440, 380, 6))); // one jogging record: 30 s
        recs.extend(stretch(330.0, 10, 350, 200, 2));
        let segs = segments(&windows_of(&recs));
        assert_eq!(segs.len(), 1, "{segs:?}");
        assert_eq!(segs[0].gait, Gait::Walking);
    }

    #[test]
    fn a_short_walk_does_not_name_a_long_session() {
        let segs = segments(&windows_of(&stretch(0.0, 8, 350, 200, 2))); // 4 min
        assert_eq!(segs.len(), 1);
        assert_eq!(dominant(&segs, 40.0 * 60.0), None);
        assert_eq!(dominant(&segs, 10.0 * 60.0), Some(Gait::Walking));
        assert_eq!(dominant(&[], 600.0), None);
    }

    #[test]
    fn rest_has_no_segments() {
        assert!(segments(&windows_of(&stretch(0.0, 40, 200, 300, 90))).is_empty());
    }
}
