//! The Activity Score and the Readiness Score, day by day.
//!
//! Both are `oura-analysis`'s ports from upstream Th0rgal/open_oura#32, chained the way
//! upstream's `tools/score_daily_exact.py` chains them:
//!
//! 1. **Activity days** (`activity_day::compute_stateless`): each day — 04:00 to 04:00 local,
//!    as the app counts it — gets its per-minute MET bytes from `activity_information`,
//!    non-wear from `wear_event`, inactivity alerts from `alert_event` and its rest from the
//!    sleep periods, and is scored against the nine days before it.
//! 2. **Readiness** (`daily::DailyScorer`), one step per day that has a main sleep, oldest
//!    first, so the nine long-term baselines build up from the start of the history. Each
//!    step takes the night (its scan plus the lowest heart rate and its time, the average
//!    RMSSD and the highest temperature, from `night_summary`), the previous day's activity
//!    statistics, and fourteen days of history.
//! 3. **Activity again**, now with each day's readiness, which scales the daily target.
//!
//! Upstream's port dates events with the ring's `time_sync` events, which this database has
//! almost none of, and assumes one ring clock across the history, which reboots break. So
//! events are handed over already dated by the summary's own clock: their "ring time" is
//! the Unix time in deciseconds, with one anchor at zero that maps it straight back.

use std::collections::BTreeMap;

use oura_analysis::ported::actinfo::User;
use oura_analysis::ported::activity_day::{
    compute_stateless, met_byte, AlertEvent, Anchor, DayResult, RingEvent, StatelessInputs,
    WearEvent,
};
use oura_analysis::ported::activity_score::recovery_time;
use oura_analysis::ported::daily::{wear_percent, DailyScorer};
use oura_analysis::ported::night_summary::{night_summary, RingSeries};
use oura_analysis::ported::sleep_inputs::{period_from_scan, Scan};
use serde_json::{json, Value};

/// One night, as the summary has it.
pub struct NightIn {
    pub start: f64,
    pub end: f64,
    pub is_main: bool,
    pub scan: Option<Scan>,
    /// 30 s stage codes from `start`.
    pub stages: Vec<u8>,
}

/// One decoded event the scores read, dated.
pub struct EventIn<'a> {
    pub unix: f64,
    pub tag: u8,
    pub json: &'a str,
}

/// Profile values the scores need.
pub struct Person {
    pub age: u32,
    /// 'M', 'F' or anything else.
    pub sex: char,
    pub weight_kg: f64,
}

/// What [`compute`] gives back, by local date (`YYYY-MM-DD`).
pub struct Scores {
    pub activity: BTreeMap<String, Value>,
    pub readiness: BTreeMap<String, Value>,
}

const DAY_S: i64 = 86_400;

fn local_day(unix: f64, tz_hours: i64) -> i64 {
    ((unix + (tz_hours * 3600) as f64) / DAY_S as f64).floor() as i64
}

fn ymd(day: i64) -> String {
    // Days since 1970-01-01 to a civil date (Howard Hinnant's algorithm).
    let z = day + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}")
}

/// `ds` handed to the activity port: Unix time in deciseconds (see the module docs).
fn as_ds(unix: f64) -> i64 {
    (unix * 10.0).round() as i64
}

pub fn compute(
    events: &[EventIn],
    nights: &[NightIn],
    person: &Person,
    tz_hours: i64,
    chrono: Option<(u8, i32)>,
) -> Scores {
    // ── activity inputs ────────────────────────────────────────────────────────────────
    let mut act = Vec::new();
    let mut wear = Vec::new();
    let mut alerts = Vec::new();
    let mut newest = f64::NEG_INFINITY;
    let mut series = RingSeries::default();
    for e in events {
        newest = newest.max(e.unix);
        let Ok(v) = serde_json::from_str::<Value>(e.json) else {
            continue;
        };
        match e.tag {
            // activity_information: the first body byte (decoded as `state`) is the step
            // count; the MET floats go back to the ring's bytes.
            0x50 => {
                let met: Vec<u8> = v["met"].as_array().map_or(vec![], |a| {
                    a.iter().filter_map(Value::as_f64).map(met_byte).collect()
                });
                if !met.is_empty() {
                    let steps = v["state"].as_u64().unwrap_or(0).min(255) as u8;
                    act.push(RingEvent {
                        ds: as_ds(e.unix),
                        steps,
                        met,
                    });
                }
            }
            0x53 => {
                if let Some(state) = v["state"].as_u64() {
                    wear.push(WearEvent {
                        ds: as_ds(e.unix),
                        state: state.min(255) as u8,
                    });
                }
            }
            0x56 => alerts.push(AlertEvent {
                ds: as_ds(e.unix),
                sit: v["alert_type"].as_u64() == Some(1),
            }),
            0x47 => series
                .motion
                .push((e.unix, v["motion_seconds"].as_i64().unwrap_or(0))),
            0x46 => {
                if let Some(x) = v["temps_c"].get(0).and_then(Value::as_f64) {
                    series.temp.push((e.unix, x));
                }
            }
            0x5d => {
                let bytes = |k: &str| -> Option<Vec<u8>> {
                    v[k].as_array()?
                        .iter()
                        .map(|x| x.as_u64().map(|y| y.min(255) as u8))
                        .collect()
                };
                if let (Some(hr), Some(rm)) = (bytes("hr_bpm"), bytes("rmssd_ms")) {
                    if hr.len() == rm.len() {
                        series.hrv.push((e.unix, hr, rm));
                    }
                }
            }
            _ => {}
        }
    }
    let gender = match person.sex.to_ascii_uppercase() {
        'M' => 2,
        'F' => 1,
        _ => 3,
    };
    let weight = person.weight_kg.round().max(0.0) as u32;
    let bmr_sex = match gender {
        2 => 0,
        1 => 1,
        _ => 2,
    };
    let user = User {
        bmr: oura_analysis::ported::metabolic::bmr_kcal(person.age, weight, bmr_sex),
        age: person.age,
        gender,
        weight_kg: weight,
        target_mult: 100,
        target_type: 0,
    };
    let sleeps: Vec<(f64, f64)> = nights.iter().map(|n| (n.start, n.end)).collect();
    let anchors = [Anchor { ds: 0, unix: 0 }];
    let day_tz = BTreeMap::new();
    let activity_days = |readiness: &BTreeMap<String, u32>| -> BTreeMap<String, DayResult> {
        compute_stateless(&StatelessInputs {
            anchors: &anchors,
            events: &act,
            wear: &wear,
            alerts: &alerts,
            newest_ds: newest.is_finite().then(|| as_ds(newest)),
            sleeps: &sleeps,
            user,
            tz: tz_hours as f64,
            day_tz: &day_tz,
            readiness,
            pad_history: true,
        })
    };

    // ── readiness, oldest day first ───────────────────────────────────────────────────
    let first_pass = activity_days(&BTreeMap::new());
    series.motion.sort_by(|a, b| a.0.total_cmp(&b.0));
    series.temp.sort_by(|a, b| a.0.total_cmp(&b.0));
    series.hrv.sort_by(|a, b| a.0.total_cmp(&b.0));
    // The night's slice of each series: night_summary filters by time itself, but handing
    // it the whole history for every night would be a quadratic walk.
    let window = |start: f64, end: f64| -> RingSeries {
        let cut = |t: f64| t >= start - 900.0 && t <= end + 900.0;
        RingSeries {
            motion: series.motion.iter().filter(|x| cut(x.0)).cloned().collect(),
            temp: series.temp.iter().filter(|x| cut(x.0)).cloned().collect(),
            hrv: series.hrv.iter().filter(|x| cut(x.0)).cloned().collect(),
        }
    };
    let stats_of = |day: i64| first_pass.get(&ymd(day)).map(|r| &r.stats);
    let mut main_by_day: BTreeMap<i64, &NightIn> = BTreeMap::new();
    for n in nights.iter().filter(|n| n.is_main && n.scan.is_some()) {
        main_by_day.insert(local_day(n.end, tz_hours), n);
    }
    let tz_min = (tz_hours * 60) as i32;
    let mut scorer = DailyScorer::default().track_sri();
    let mut night_vals: BTreeMap<i64, (u32, u32)> = BTreeMap::new(); // (total sleep s, rmssd)
    let mut readiness_json = BTreeMap::new();
    let mut readiness_map = BTreeMap::new();
    for (&day, n) in &main_by_day {
        let Some(scan) = n.scan else { continue };
        let start_ms = (n.start * 1000.0).round() as i64;
        let end_ms = (n.end * 1000.0).round() as i64;
        let summ = night_summary(&window(n.start, n.end), start_ms, end_ms, Some(&n.stages));
        let mut p = period_from_scan(&scan, n.start.round() as i64, tz_min, 1);
        p.lowest_hr = summ.lowest_hr;
        p.lowest_hr_time = summ.lowest_hr_time;
        p.rmssd = summ.rmssd;
        p.highest_temp = summ.highest_temp as u32;
        let tib = summ.time_in_bed.max(0) as u32;
        if p.total_sleep > 0 && tib > 0 {
            p.time_in_bed = tib;
            p.efficiency = (200 * p.total_sleep + tib) / (2 * tib);
        }
        if p.total_sleep == 0 {
            continue;
        }
        let prev = stats_of(day - 1).map_or([0u32; 8], |st| {
            [
                0,
                st.eq_distance,
                st.non_wear,
                st.classes[1],
                st.classes[4],
                st.classes[2],
                st.classes[3],
                st.classes[0],
            ]
        });
        let history: Vec<(u32, u32, u32, u8)> = (0..14)
            .map(|k| {
                let (sleep, rmssd) = night_vals.get(&(day - 1 - k)).copied().unwrap_or((0, 0));
                let (dist, wear) = stats_of(day - 2 - k).map_or((0, 0), |st| {
                    let c = st.classes;
                    (
                        st.eq_distance,
                        wear_percent(c[1], c[2], c[3], c[4], c[0]) as u8,
                    )
                });
                (sleep, dist, rmssd, wear)
            })
            .collect();
        let res = scorer.step_with_history(
            day * DAY_S,
            person.age,
            std::slice::from_ref(&p),
            &prev,
            chrono,
            0,
            Some(&history),
        );
        night_vals.insert(day, (p.total_sleep, p.rmssd));
        if let (Some(score), Some(c)) = (res.readiness_score, res.contributors) {
            let date = ymd(day);
            readiness_map.insert(date.clone(), score.max(0) as u32);
            readiness_json.insert(
                date,
                json!({
                    "score": score,
                    "contributors": {
                        "activity_balance": c[0],
                        "previous_day_activity": c[1],
                        "previous_night": c[2],
                        "resting_heart_rate": c[3],
                        "recovery_index": c[4],
                        "sleep_balance": c[5],
                        "body_temperature": c[6],
                        "hrv_balance": c[7],
                        "sleep_regularity": c[8],
                    },
                }),
            );
        }
    }

    // ── activity, with each day's readiness ──────────────────────────────────────────
    let activity_json = activity_days(&readiness_map)
        .into_iter()
        .map(|(date, r)| {
            let c = r.contributors;
            let round = |x: f32| x.round() as i32;
            (
                date,
                json!({
                    "score": r.score,
                    "contributors": {
                        "meet_daily_targets": round(c[0]),
                        "stay_active": round(c[1]),
                        "move_every_hour": round(c[2]),
                        "training_frequency": round(c[3]),
                        "training_volume": round(c[4]),
                        "recovery_time": round(recovery_time(&c)),
                    },
                    "active_kcal": r.stats.active_cal,
                    "target_kcal": r.stats.target_cal,
                }),
            )
        })
        .collect();

    Scores {
        activity: activity_json,
        readiness: readiness_json,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates_are_civil() {
        assert_eq!(ymd(0), "1970-01-01");
        assert_eq!(ymd(20_407), "2025-11-15");
        assert_eq!(ymd(local_day(1_763_164_800.0 - 1.0, 3)), "2025-11-15");
    }

    #[test]
    fn nothing_in_nothing_out() {
        let s = compute(
            &[],
            &[],
            &Person {
                age: 36,
                sex: 'M',
                weight_kg: 66.0,
            },
            3,
            None,
        );
        assert!(s.activity.is_empty());
        assert!(s.readiness.is_empty());
    }
}
