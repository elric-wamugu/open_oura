//! Where build_summary's time actually goes.
//! `cargo run --release -p oura-summary --example profile_summary -- oura.db [tz_hours] [out.json]`
//!
//! With `out.json`, the summary is also written there without `timings_ms`,
//! `generated_at` and `device.fresh_hours`, which are the only parts that differ between two runs over the same
//! database. Diff that file before and after a change that is meant to be a pure speedup.
use std::time::Instant;

fn main() -> anyhow::Result<()> {
    let db = std::env::args().nth(1).unwrap_or_else(|| "oura.db".into());
    let tz: i64 = std::env::args()
        .nth(2)
        .and_then(|s| s.parse().ok())
        .unwrap_or(0);
    let db = std::path::Path::new(&db);

    // The real thing, model-free as the phone runs it: the stages build_summary times
    // itself, slowest first. This is the same `timings_ms` the Android app logs.
    let t = Instant::now();
    // OURA_STAGE_BUDGET_MS=<ms> runs the on-device sleep stager as the Android app does,
    // spending at most that long on nights not already in the cache.
    let opts = oura_summary::SummaryOptions {
        stage_sleep: std::env::var("OURA_STAGE_BUDGET_MS")
            .ok()
            .and_then(|v| v.parse().ok())
            .map(std::time::Duration::from_millis),
    };
    let summary = oura_summary::build_summary_with(db, tz, &oura_summary::NoModelRunner, &opts)?;
    if !summary["stager"].is_null() {
        println!("stager {}", summary["stager"]);
    }
    let wall = t.elapsed();
    let mut stages: Vec<(String, f64)> = summary["timings_ms"]
        .as_object()
        .map(|m| {
            m.iter()
                .map(|(k, v)| (k.clone(), v.as_f64().unwrap_or(0.0)))
                .collect()
        })
        .unwrap_or_default();
    stages.sort_by(|a, b| b.1.total_cmp(&a.1));
    println!(
        "build_summary  {wall:>7.2?}  {} events",
        summary["device"]["total_events"]
    );
    for (stage, ms) in &stages {
        println!("  {stage:<14} {ms:>9.1} ms");
    }
    if let Some(out) = std::env::args().nth(3) {
        let mut stable = summary.clone();
        if let Some(o) = stable.as_object_mut() {
            o.remove("timings_ms");
            o.remove("generated_at");
        }
        // Hours since the last sync, measured against the clock on the wall.
        if let Some(d) = stable.get_mut("device").and_then(|d| d.as_object_mut()) {
            d.remove("fresh_hours");
        }
        std::fs::write(&out, serde_json::to_string_pretty(&stable)?)?;
        println!("  wrote {out}");
    }
    let t = Instant::now();
    let json = summary.to_string();
    println!(
        "  {:<14} {:>9.1} ms  ({} KB)",
        "serialize",
        t.elapsed().as_secs_f64() * 1000.0,
        json.len() / 1024
    );

    // The floor under all of it: reading every event, and parsing each one's JSON once.
    let store = oura_store::Store::open(db)?;
    let dev = store.device_info().ok().flatten();
    let serial = dev.as_ref().map(|d| d.0.clone()).unwrap_or_default();

    let t = Instant::now();
    let events = store.decoded_events_for_serial(&serial)?;
    let read = t.elapsed();
    let bytes: usize = events.iter().map(|e| e.2.len()).sum();
    println!(
        "read      {:>7.2?}  {} events, {} MB of JSON",
        read,
        events.len(),
        bytes / 1024 / 1024
    );

    let t = Instant::now();
    let mut n = 0usize;
    for (_, _, j, _) in &events {
        if serde_json::from_str::<serde_json::Value>(j).is_ok() {
            n += 1;
        }
    }
    let one = t.elapsed();
    println!("parse x1  {:>7.2?}  {} parsed ok", one, n);
    Ok(())
}
