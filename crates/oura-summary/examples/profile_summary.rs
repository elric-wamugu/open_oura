//! Where build_summary's time actually goes. `cargo run --release -p oura-summary --example profile_summary -- oura.db`
use std::time::Instant;

fn main() -> anyhow::Result<()> {
    let db = std::env::args().nth(1).unwrap_or_else(|| "oura.db".into());
    let store = oura_store::Store::open(std::path::Path::new(&db))?;
    let dev = store.device_info().ok().flatten();
    let serial = dev.as_ref().map(|d| d.0.clone()).unwrap_or_default();

    let t = Instant::now();
    let events = store.decoded_events_for_serial(&serial)?;
    let read = t.elapsed();
    let bytes: usize = events.iter().map(|e| e.2.len()).sum();
    println!("read      {:>7.2?}  {} events, {} MB of JSON", read, events.len(), bytes / 1024 / 1024);

    let t = Instant::now();
    let mut n = 0usize;
    for (_, _, j, _) in &events {
        if serde_json::from_str::<serde_json::Value>(j).is_ok() {
            n += 1;
        }
    }
    let one = t.elapsed();
    println!("parse x1  {:>7.2?}  {} parsed ok", one, n);
    println!("parse x7  {:>7.2?}  (build_summary parses the same strings in 7 passes)", one * 7);
    Ok(())
}
