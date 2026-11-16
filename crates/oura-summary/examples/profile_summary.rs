//! Where build_summary's time actually goes.
//! `cargo run --release -p oura-summary --example profile_summary -- oura.db [tz_hours]`
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
    let summary = oura_summary::build_summary(db, tz, &oura_summary::NoModelRunner)?;
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
