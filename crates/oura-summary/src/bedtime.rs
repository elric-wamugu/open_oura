//! Sleep the ring has not declared yet, found on this device.
//!
//! A night reaches the summary as the ring's `bedtime_period`, which the ring writes some time
//! after you wake — so a morning sync can bring the night's data without the period that
//! frames it, and Today has no last night until a later sync. This runs upstream's bedtime
//! detector (`oura-analysis::ported::bedtime`, from Th0rgal/open_oura#32) on the motion and
//! temperature since the last declared main sleep, the way the Oura app does, and returns
//! the periods it finds that no `bedtime_period` covers. Once the ring declares the night,
//! the detected one overlaps it and is dropped.

use oura_analysis::ported::bedtime_inputs::{assemble, previous, run, window_start, Events};

/// The tags the detector reads: `state_change`, `temp_event`, `motion_event`, `feature_session`.
pub const INPUT_TAGS: [u8; 4] = [0x45, 0x46, 0x47, 0x6c];

/// Detected periods shorter than this are not a night (an evening doze).
const MIN_MS: i64 = 30 * 60_000;

/// The summary's own floor for a night (`MIN_SLEEP_PERIOD_DS` in `lib.rs`): one sleep cycle.
pub const NIGHT_MIN_MS: i64 = 90 * 60_000;

/// The periods the detector finds in `events` (the input tags of one boot, as
/// `(ring ds, tag, json, captured_unix)`) that overlap none of `confirmed`, in Unix
/// milliseconds. `unix_ms` places an event on the calendar; `confirmed` are the ring's own
/// bedtime periods in Unix milliseconds; `tz_hours` gives a confirmed sleep its local day.
pub fn open_periods(
    events: &[(i64, u8, String, i64)],
    unix_ms: &dyn Fn(i64, i64) -> i64,
    confirmed: &[(i64, i64)],
    tz_hours: f64,
) -> Vec<(i64, i64)> {
    // Only declared periods long enough to be a night count. The ring also declares 10–40
    // minute stretches of stillness, which the summary drops anyway, and they get in the
    // way twice: the detector looks only after the latest period it is given, so an
    // afternoon's quiet half hour would hide the night before it, and one at the start
    // of a night would hide the night itself.
    let nights: Vec<(i64, i64)> = confirmed
        .iter()
        .copied()
        .filter(|&(s, e)| e - s >= NIGHT_MIN_MS)
        .collect();
    detect(events, unix_ms, &nights, tz_hours)
        .into_iter()
        .filter(|&(s, e)| e - s >= MIN_MS)
        .filter(|&(s, e)| !nights.iter().any(|&(cs, ce)| s < ce && cs < e))
        .collect()
}

/// Every period the detector finds over the window, declared or not.
fn detect(
    events: &[(i64, u8, String, i64)],
    unix_ms: &dyn Fn(i64, i64) -> i64,
    confirmed: &[(i64, i64)],
    tz_hours: f64,
) -> Vec<(i64, i64)> {
    let ev = Events::from_decoded(events, unix_ms);
    let Some(w_end) = ev
        .motion
        .iter()
        .map(|x| x.0)
        .chain(ev.temp.iter().map(|x| x.0))
        .max()
    else {
        return vec![];
    };
    // As the app seeds it: from the start of the latest declared main sleep (at most 28 days
    // back), with the declared sleeps as the detector's previous bedtimes.
    let win_start = window_start(&[], confirmed, true, tz_hours, w_end - 30 * 60_000, w_end);
    let prev = previous(&[], confirmed, true, win_start, w_end);
    assemble(&ev, win_start, w_end, w_end)
        .and_then(|a| run(&a, &prev))
        .unwrap_or_default()
        .into_iter()
        .map(|p| (p.start_ms as i64, p.end_ms as i64))
        .collect()
}
