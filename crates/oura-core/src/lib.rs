//! `oura-core` — the UniFFI surface the iOS (and any native) client links against.
//!
//! Everything here delegates to the shared Rust crates that already power the web
//! dashboard (`oura-analysis`, `oura-store`, …). The contract returned to Swift is
//! the same JSON the web client renders, so the two clients never diverge. Bindings
//! are generated with UniFFI (`uniffi-bindgen`), packaged as an `.xcframework`.

use serde_json::json;

uniffi::setup_scaffolding!();

/// Build/version string — a trivial call to validate the FFI round-trip.
#[uniffi::export]
pub fn core_version() -> String {
    format!("oura-core {}", env!("CARGO_PKG_VERSION"))
}

/// HRV RMSSD (ms) over inter-beat intervals — the shared `oura-analysis` algorithm,
/// reachable natively. Returns -1 when the input is too short.
#[uniffi::export]
pub fn rmssd(ibi_ms: Vec<u16>) -> f64 {
    oura_analysis::ported::hrv::rmssd(&ibi_ms).unwrap_or(-1.0)
}

/// The full dashboard summary — the SAME `build_summary()` JSON the web client
/// renders, computed from the synced SQLite DB. `tz_offset` is hours from UTC.
///
/// Models (sleep hypnogram / cardiovascular age / activity sessions) use a
/// [`oura_summary::ModelRunner`]; on-device we'll pass the `.ptl` torch runner.
/// For now [`oura_summary::NoModelRunner`] yields the signal-derived panels
/// (vitals, cardio trend, activity profile, device & data-health, digest) — most
/// of the dashboard — with model fields null until the torch runner is wired.
///
/// Returns the summary JSON string, or `{ "error": "…" }`.
#[uniffi::export]
pub fn summary_json(db_path: String, tz_offset: i64) -> String {
    match oura_summary::build_summary(
        std::path::Path::new(&db_path),
        tz_offset,
        &oura_summary::NoModelRunner,
    ) {
        Ok(v) => v.to_string(),
        Err(e) => json!({ "error": e.to_string() }).to_string(),
    }
}

/// [`summary_json`], with nights the models did not stage staged on this device by the
/// sleep stager — see `oura-summary`'s `staging.rs`. At most `stage_budget_ms` is spent
/// staging new nights per call; staged nights are cached in the database, so later calls
/// are fast and pick up where an earlier one ran out of time.
///
/// A separate function, not a flag on [`summary_json`], so that clients which have not
/// chosen this (iOS) keep their behaviour without a change to their code.
#[uniffi::export]
pub fn summary_json_staged(db_path: String, tz_offset: i64, stage_budget_ms: u64) -> String {
    match oura_summary::build_summary_with(
        std::path::Path::new(&db_path),
        tz_offset,
        &oura_summary::NoModelRunner,
        &oura_summary::SummaryOptions {
            stage_sleep: Some(std::time::Duration::from_millis(stage_budget_ms)),
        },
    ) {
        Ok(v) => v.to_string(),
        Err(e) => json!({ "error": e.to_string() }).to_string(),
    }
}

/// The client implements this to follow a backup or a restore: events handled so far, out
/// of how many.
#[uniffi::export(callback_interface)]
pub trait BackupProgressListener: Send + Sync {
    fn on_progress(&self, done: u64, total: u64);
}

/// Take ownership of a file descriptor the client opened. The client must have detached
/// it (Android: `ParcelFileDescriptor.detachFd()`), because it is closed when this drops.
#[cfg(unix)]
fn owned_file(fd: i32) -> std::fs::File {
    use std::os::unix::io::FromRawFd;
    // SAFETY: the contract above — the caller hands over a valid descriptor it no longer owns.
    unsafe { std::fs::File::from_raw_fd(fd) }
}

fn backup_error(e: oura_backup::Error) -> String {
    let code = match e {
        oura_backup::Error::WrongPassphrase => "wrong_passphrase",
        oura_backup::Error::NotABackup => "not_a_backup",
        oura_backup::Error::NewerFormat(_) => "newer_format",
        oura_backup::Error::Damaged(_) => "damaged",
        _ => "failed",
    };
    json!({ "error": e.to_string(), "code": code }).to_string()
}

/// Write an encrypted backup of the database to an open file descriptor — see
/// `oura-backup` for the format. A descriptor rather than a path because Android's
/// document picker hands out descriptors, possibly a pipe into a cloud provider, and the
/// format is written without seeking so that works.
///
/// Returns `{ events, readings, devices }`, or `{ error, code }`.
#[cfg(unix)]
#[uniffi::export]
pub fn backup_to_fd(
    db_path: String,
    passphrase: String,
    fd: i32,
    listener: Box<dyn BackupProgressListener>,
) -> String {
    let out = std::io::BufWriter::new(owned_file(fd));
    match oura_backup::write_backup(
        std::path::Path::new(&db_path),
        &passphrase,
        out,
        &mut |done, total| listener.on_progress(done, total),
    ) {
        Ok(s) => {
            json!({ "events": s.events, "readings": s.readings, "devices": s.devices }).to_string()
        }
        Err(e) => backup_error(e),
    }
}

/// Merge the backup read from an open file descriptor into the database, creating it if
/// there is none. All or nothing: a wrong passphrase or a damaged file changes nothing.
///
/// Returns `{ events, events_added, readings_added, labels_added, files_written, created_unix }`, or
/// `{ error, code }` where `code` is `wrong_passphrase`, `not_a_backup`, `newer_format`,
/// `damaged` or `failed`.
#[cfg(unix)]
#[uniffi::export]
pub fn restore_from_fd(
    db_path: String,
    passphrase: String,
    fd: i32,
    listener: Box<dyn BackupProgressListener>,
) -> String {
    let input = std::io::BufReader::new(owned_file(fd));
    match oura_backup::restore_backup(
        input,
        &passphrase,
        std::path::Path::new(&db_path),
        &mut |done, total| listener.on_progress(done, total),
    ) {
        Ok(s) => json!({
            "events": s.events,
            "events_added": s.events_added,
            "readings_added": s.readings_added,
            "labels_added": s.labels_added,
            "files_written": s.files_written,
            "created_unix": s.created_unix,
        })
        .to_string(),
        Err(e) => backup_error(e),
    }
}

/// Label one ring-detected effort session with what the person says it was.
///
/// The session is named by its `ring` object in the summary's `effort[]`: `serial`, the
/// boot's `capture` range (`capture_lo`, `capture_hi`), and `start_ds`/`end_ds`. `kind` is a
/// short activity name ("dance", "walk", "run", "cycling", "strength", "other"); `note` is
/// optional free text. Replaces any label the session already had. Returns `{}`, or
/// `{ error }`.
#[allow(clippy::too_many_arguments)]
#[uniffi::export]
pub fn set_effort_label(
    db_path: String,
    serial: String,
    capture_lo: i64,
    capture_hi: i64,
    start_ds: i64,
    end_ds: i64,
    kind: String,
    note: Option<String>,
) -> String {
    let kind = kind.trim().to_lowercase();
    if kind.is_empty() || kind.chars().count() > 40 {
        return json!({ "error": "an activity name is 1 to 40 characters" }).to_string();
    }
    let note = note
        .map(|n| n.trim().chars().take(200).collect::<String>())
        .filter(|n| !n.is_empty());
    let label = oura_store::EffortLabel {
        serial,
        capture_lo,
        start_ds,
        end_ds,
        kind,
        note,
        updated_unix: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0),
    };
    let run = || -> Result<(), String> {
        let store = Store::open(std::path::Path::new(&db_path)).map_err(|e| e.to_string())?;
        store
            .set_effort_label(&label, (capture_lo, capture_hi))
            .map_err(|e| e.to_string())
    };
    match run() {
        Ok(()) => "{}".to_string(),
        Err(e) => json!({ "error": e }).to_string(),
    }
}

/// Remove the person's label from one effort session, named as for [`set_effort_label`].
/// Returns `{ removed }`, or `{ error }`.
#[uniffi::export]
pub fn clear_effort_label(
    db_path: String,
    serial: String,
    capture_lo: i64,
    capture_hi: i64,
    start_ds: i64,
    end_ds: i64,
) -> String {
    let run = || -> Result<usize, String> {
        let store = Store::open(std::path::Path::new(&db_path)).map_err(|e| e.to_string())?;
        store
            .clear_effort_label(&serial, (capture_lo, capture_hi), start_ds, end_ds)
            .map_err(|e| e.to_string())
    };
    match run() {
        Ok(n) => json!({ "removed": n }).to_string(),
        Err(e) => json!({ "error": e }).to_string(),
    }
}

/// Re-apply the database's effort labels to a summary already built by [`summary_json`],
/// so a client can show a new label at once instead of waiting out a recompute. Returns
/// the summary JSON with `effort[].user_label` brought up to date, or `{ error }`.
#[uniffi::export]
pub fn relabel_summary_json(db_path: String, summary: String) -> String {
    let run = || -> Result<String, String> {
        let mut v: serde_json::Value = serde_json::from_str(&summary).map_err(|e| e.to_string())?;
        oura_summary::relabel_summary(std::path::Path::new(&db_path), &mut v)
            .map_err(|e| e.to_string())?;
        Ok(v.to_string())
    };
    match run() {
        Ok(s) => s,
        Err(e) => json!({ "error": e }).to_string(),
    }
}

/// Rewrite the database without its free pages. Nothing in it changes; the file gets
/// smaller. Needs about twice the database's size free while it runs — the caller checks,
/// because only the platform knows what is free. Returns `{ before, after }` in bytes, or
/// `{ error }`.
#[uniffi::export]
pub fn compact_db(db_path: String) -> String {
    let run = || -> Result<(u64, u64), String> {
        let store = Store::open(std::path::Path::new(&db_path)).map_err(|e| e.to_string())?;
        store.compact().map_err(|e| e.to_string())
    };
    match run() {
        Ok((before, after)) => json!({ "before": before, "after": after }).to_string(),
        Err(e) => json!({ "error": e }).to_string(),
    }
}

/// A lightweight, model-free summary (device + data-health only) — kept as a fast
/// path / fallback. Returns `{ serials, device, event_counts, decoded_events }`.
#[uniffi::export]
pub fn quick_summary_json(db_path: String) -> String {
    match quick_summary(&db_path) {
        Ok(v) => v.to_string(),
        Err(e) => json!({ "error": e }).to_string(),
    }
}

fn quick_summary(db_path: &str) -> Result<serde_json::Value, String> {
    let store = oura_store::storage::Store::open(db_path).map_err(|e| e.to_string())?;
    let serials = store.device_serials().map_err(|e| e.to_string())?;
    let primary = serials.first().cloned().unwrap_or_default();

    let device = store.device_info().map_err(|e| e.to_string())?.map(
        |(
            serial,
            hardware_id,
            firmware,
            api_version,
            mac,
            updated_unix,
            last_sync_unix,
            cursor,
        )| {
            json!({ "serial": serial, "hardware_id": hardware_id, "firmware": firmware,
                    "api_version": api_version, "mac": mac, "updated_unix": updated_unix,
                    "last_sync_unix": last_sync_unix, "next_cursor": cursor })
        },
    );

    let event_counts: Vec<_> = store
        .event_counts(&primary)
        .map_err(|e| e.to_string())?
        .into_iter()
        .map(|(kind, n)| json!({ "kind": kind, "count": n }))
        .collect();

    let decoded = store.decoded_events().map_err(|e| e.to_string())?.len();

    Ok(json!({
        "serials": serials,
        "device": device,
        "event_counts": event_counts,
        "decoded_events": decoded,
    }))
}

// ── on-device BLE sync over a Swift-provided transport ────────────────────────
// The iOS app does CoreBluetooth; this drives the SAME oura-link OuraClient<T>
// (auth → app stream → drain → store) over a transport that bridges to Swift, so
// the device builds its own DB from a real ring — no btleplug, no cloud.
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use oura_link::transport::Transport;
use oura_link::OuraClient;
use oura_store::storage::Store;
use tokio::sync::broadcast;

/// Swift implements this to send one request frame over CoreBluetooth. Fire-and-
/// forget: the ring's responses come back asynchronously via `push_frame`.
#[uniffi::export(callback_interface)]
pub trait BleWriter: Send + Sync {
    fn write(&self, data: Vec<u8>);
}

/// The client implements this to receive sync progress. `stage` is a short machine
/// tag ("auth" / "setup" / "sync"); during "sync", `bytes_left` is the ring's
/// own count of event bytes still to transfer (0 = unknown/finished) and
/// `events_synced` the events pulled so far this session.
///
/// `progress` is 0..=1 through the drain, or `None` while it cannot be known — before
/// the drain starts, and until the ring has reported a backlog to measure against.
/// It comes from `oura-link`, the same value the web dashboard renders, so clients
/// display a fraction rather than each inventing one.
#[uniffi::export(callback_interface)]
pub trait SyncProgressListener: Send + Sync {
    fn on_progress(
        &self,
        stage: String,
        bytes_left: u64,
        events_synced: u32,
        progress: Option<f32>,
    );
}

/// How a live heart-rate measurement went, beyond the beats themselves — enough to tell
/// "the ring refused" from "the ring was listening and found no pulse".
#[derive(uniffi::Record)]
pub struct HeartRateReport {
    pub beats: u32,
    /// Every notification the ring sent while measuring.
    pub frames: u32,
    /// Those that were live heart-rate notifications, valid beat or not.
    pub live_frames: u32,
    /// The ring's result byte for the switch to live mode; 0 is accepted.
    pub mode_result: Option<u8>,
    /// The first few notifications as hex, for the log.
    pub first_frames: Vec<String>,
}

fn hex_of(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// The client implements this to receive each beat of a live heart-rate measurement, as
/// it happens. `bpm` is that one beat's rate (from its interval, `ibi_ms`), so it jitters
/// from beat to beat; smoothing it for display is the client's business.
#[uniffi::export(callback_interface)]
pub trait HeartRateListener: Send + Sync {
    fn on_beat(&self, bpm: u16, ibi_ms: u16);
}

#[derive(uniffi::Record)]
pub struct SyncReport {
    pub serial: String,
    pub events_synced: u32,
    pub inserted: u32,
    pub next_cursor: u32,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SyncError {
    #[error("{0}")]
    Failed(String),
}

/// A live sync session bound to a connected ring: Swift creates it with a writer,
/// feeds inbound BLE frames via `push_frame`, then awaits `sync`.
#[derive(uniffi::Object)]
pub struct RingSession {
    tx: broadcast::Sender<Vec<u8>>,
    writer: Arc<dyn BleWriter>,
    /// Set by [`RingSession::stop_heart_rate`] to end a live measurement early.
    stop_live: std::sync::atomic::AtomicBool,
}

/// Bridges oura-link's `Transport` onto the Swift writer + the inbound frame channel.
struct FfiTransport {
    tx: broadcast::Sender<Vec<u8>>,
    writer: Arc<dyn BleWriter>,
}

#[async_trait::async_trait]
impl Transport for FfiTransport {
    async fn write(&self, data: &[u8]) -> oura_link::Result<()> {
        self.writer.write(data.to_vec());
        Ok(())
    }
    fn subscribe(&self) -> broadcast::Receiver<Vec<u8>> {
        self.tx.subscribe()
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl RingSession {
    #[uniffi::constructor]
    pub fn new(writer: Box<dyn BleWriter>) -> Arc<Self> {
        let (tx, _) = broadcast::channel(8192);
        Arc::new(Self {
            tx,
            writer: Arc::from(writer),
            stop_live: std::sync::atomic::AtomicBool::new(false),
        })
    }

    /// Swift pushes each inbound BLE notification frame here.
    pub fn push_frame(&self, data: Vec<u8>) {
        let _ = self.tx.send(data);
    }

    /// Authenticate, set up the app stream, and drain history events into the DB at
    /// `db_path`. `key_hex` is the 32-char ring auth key. `progress` receives stage
    /// changes and per-batch drain progress. Returns the sync counts.
    ///
    /// The drain checkpoints its cursor after every batch, so a failed call can be
    /// retried (reconnect + call again) and resumes where it left off.
    pub async fn sync(
        &self,
        db_path: String,
        key_hex: String,
        progress: Box<dyn SyncProgressListener>,
    ) -> Result<SyncReport, SyncError> {
        let fail = |e: String| SyncError::Failed(e);
        let key =
            parse_key(&key_hex).ok_or_else(|| fail("auth key must be 32 hex chars".into()))?;
        let transport = FfiTransport {
            tx: self.tx.clone(),
            writer: self.writer.clone(),
        };
        let client = OuraClient::new(transport);

        progress.on_progress("auth".into(), 0, 0, None);
        client
            .authenticate(&key)
            .await
            .map_err(|e| fail(e.to_string()))?;
        progress.on_progress("setup".into(), 0, 0, None);
        client
            .setup_app_stream()
            .await
            .map_err(|e| fail(e.to_string()))?;
        let serial = client.serial().await.unwrap_or_else(|_| "unknown".into());
        let info = client.firmware().await.ok();

        // Mutex<Store> keeps the future Send across the drain's awaits (rusqlite's
        // Connection is !Sync), while still writing incrementally (no buffering).
        let store = Mutex::new(Store::open(&db_path).map_err(|e| fail(e.to_string()))?);
        store
            .lock()
            .unwrap()
            .upsert_device(&serial, None, info.as_ref())
            .map_err(|e| fail(e.to_string()))?;

        // Capture the live battery, exactly as `oura sync` does. `build_summary` picks
        // whichever of this reading and the ring's own battery log is newer, and the log
        // only records *changes* — so without this a client that syncs only over FFI shows
        // a battery figure that silently ages, while the sync itself succeeds. Best-effort:
        // a ring that declines the read should not fail an otherwise good sync.
        if let Ok(b) = client.battery().await {
            let _ = store.lock().unwrap().insert_battery(&serial, &b);
        }

        let cursor = store
            .lock()
            .unwrap()
            .cursor(&serial)
            .map_err(|e| fail(e.to_string()))?;

        let inserted = AtomicU32::new(0);
        let db_err: Mutex<Option<String>> = Mutex::new(None);
        progress.on_progress("sync".into(), 0, 0, None);
        let outcome = client
            .drain_events(
                cursor,
                |ev| {
                    if db_err.lock().unwrap().is_some() {
                        return;
                    }
                    match store.lock().unwrap().insert_event(&serial, ev) {
                        Ok(true) => {
                            inserted.fetch_add(1, Ordering::Relaxed);
                        }
                        Ok(false) => {}
                        Err(e) => *db_err.lock().unwrap() = Some(e.to_string()),
                    }
                },
                |p| {
                    progress.on_progress(
                        "sync".into(),
                        p.bytes_left as u64,
                        p.events_synced,
                        p.progress,
                    );
                    if db_err.lock().unwrap().is_some() {
                        return;
                    }
                    if let Err(e) = store.lock().unwrap().set_cursor(&serial, p.next_cursor) {
                        *db_err.lock().unwrap() = Some(e.to_string());
                    }
                },
            )
            .await
            .map_err(|e| fail(e.to_string()))?;

        if let Some(msg) = db_err.into_inner().unwrap() {
            return Err(fail(msg));
        }
        Ok(SyncReport {
            serial,
            events_synced: outcome.events_synced,
            inserted: inserted.into_inner(),
            next_cursor: outcome.next_cursor,
        })
    }

    /// Measure heart rate now: authenticate, put the ring in its live mode for up to
    /// `seconds`, and hand each valid beat to `listener`. Returns how many beats arrived —
    /// zero means the ring was not on a finger, or could not get a reading.
    ///
    /// Ends early when [`RingSession::stop_heart_rate`] is called; either way the ring is
    /// put back in the daytime-HR mode `feature_modes.json` last recorded for it, so a
    /// measurement never switches on a feature its owner had switched off. **Stop it that
    /// way rather than cancelling the call**: a dropped future never sends the restore.
    ///
    /// Nothing is written to the database. A spot reading is not part of the ring's
    /// history, and `build_summary` does not read it.
    pub async fn live_heart_rate(
        &self,
        db_path: String,
        key_hex: String,
        seconds: u32,
        listener: Box<dyn HeartRateListener>,
    ) -> Result<HeartRateReport, SyncError> {
        use oura_protocol::protocol::feature_mode;

        let fail = |e: String| SyncError::Failed(e);
        let key =
            parse_key(&key_hex).ok_or_else(|| fail("auth key must be 32 hex chars".into()))?;
        let transport = FfiTransport {
            tx: self.tx.clone(),
            writer: self.writer.clone(),
        };
        let client = OuraClient::new(transport);
        client
            .authenticate(&key)
            .await
            .map_err(|e| fail(e.to_string()))?;

        // 0 in the file is "off"; anything else, or no record at all, is the ring's
        // ordinary automatic mode.
        let modes = oura_summary::read_feature_modes(std::path::Path::new(&db_path));
        let restore = match modes.get("daytime_hr").and_then(|m| m.as_i64()) {
            Some(0) => feature_mode::OFF,
            _ => feature_mode::AUTOMATIC,
        };

        let stats = client
            .live_heart_rate_until(
                std::time::Duration::from_secs(seconds as u64),
                restore,
                false,
                || self.stop_live.load(Ordering::Relaxed),
                |s| listener.on_beat(s.bpm, s.ibi_ms),
            )
            .await
            .map_err(|e| fail(e.to_string()))?;
        Ok(HeartRateReport {
            beats: stats.beats,
            frames: stats.frames,
            live_frames: stats.live_frames,
            mode_result: stats.mode_result,
            first_frames: stats.first_frames.iter().map(|f| hex_of(f)).collect(),
        })
    }

    /// End a [`RingSession::live_heart_rate`] in progress. It returns within a quarter of
    /// a second, after restoring the ring's mode. Called before the measurement has begun,
    /// it makes it end as soon as it does — a session is used for one measurement, so the
    /// flag is never cleared.
    pub fn stop_heart_rate(&self) {
        self.stop_live.store(true, Ordering::Relaxed);
    }

    /// Turn one on-ring capability on or off, and record the new mode next to the DB.
    ///
    /// The same operation the web dashboard offers, over the same protocol call — but
    /// directly, where the dashboard shells out to `oura feature-mode` and greps its
    /// stdout for "SUCCESS". `feature` is a name from
    /// [`oura_protocol::protocol::feature_id`]; the ring is always put in `AUTOMATIC` or
    /// `OFF`, the only two modes a user-facing switch should express.
    ///
    /// Writing `feature_modes.json` matters as much as the write to the ring. The summary
    /// reads that file for the "Recording" state, and it is otherwise only refreshed by a
    /// sync — so without this the switch would flip back the moment the panel redrew.
    ///
    /// Returns a sentence to show the user, because the interesting case is not failure:
    /// the ring answers `0x20` when asked for the mode it is already in, which is a
    /// rejection that means "nothing to do" and must not be reported as an error.
    pub async fn set_feature_mode(
        &self,
        db_path: String,
        key_hex: String,
        feature: String,
        on: bool,
    ) -> Result<String, SyncError> {
        use oura_protocol::protocol::{feature_id, feature_mode};

        let fail = |e: String| SyncError::Failed(e);
        let key =
            parse_key(&key_hex).ok_or_else(|| fail("auth key must be 32 hex chars".into()))?;
        let id = feature_id(&feature).ok_or_else(|| fail(format!("unknown feature {feature}")))?;
        let mode = if on {
            feature_mode::AUTOMATIC
        } else {
            feature_mode::OFF
        };
        let label = if on { "on" } else { "off" };

        let transport = FfiTransport {
            tx: self.tx.clone(),
            writer: self.writer.clone(),
        };
        let client = OuraClient::new(transport);
        client
            .authenticate(&key)
            .await
            .map_err(|e| fail(e.to_string()))?;

        let db = std::path::Path::new(&db_path);
        match client.set_feature_mode(id, mode).await {
            Ok(()) => {
                oura_summary::write_feature_mode(db, &feature, if on { 1 } else { 0 });
                Ok(format!("{feature} turned {label}"))
            }
            // 0x20 is the ring declining to re-enter a mode it is already in. The user's
            // intent is satisfied, so record it and say so rather than showing a failure.
            Err(e) if e.to_string().contains("0x20") => {
                oura_summary::write_feature_mode(db, &feature, if on { 1 } else { 0 });
                Ok(format!("{feature} was already {label}"))
            }
            Err(e) => Err(fail(e.to_string())),
        }
    }
}

fn parse_key(hex: &str) -> Option<[u8; 16]> {
    let hex = hex.trim();
    if hex.len() != 32 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let mut key = [0u8; 16];
    for i in 0..16 {
        key[i] = u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(key)
}
