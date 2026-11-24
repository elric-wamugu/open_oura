//! Encrypted backups of the ring database.
//!
//! The ring keeps about eight days, so the database on a phone is the only copy of
//! everything older. A backup is how that survives a lost phone, and how the desktop gets to
//! read what the phone synced.
//!
//! **What is in one.** Not the SQLite file: a *logical* copy. Every event exactly as the
//! ring delivered it (serial, tag, ring timestamp, body, capture time), the device rows, the
//! sync cursors, the scalar readings, the person's own effort-session labels, and the two
//! small files that live beside the database
//! (`profile.json`, `feature_modes.json`). Decoded JSON and indexes are left out because
//! they are derived — restoring re-decodes with whatever decoders the restoring build has.
//! That makes a backup far smaller than the database, and independent of its schema.
//!
//! **Restoring is merging.** Events are inserted with the store's own "ignore exact
//! duplicates" rule, so restoring into a database that already has some or all of them adds
//! only what is missing. The same file can be restored twice, into a phone or a laptop,
//! before or after either has synced on its own. The ring's auth key is never included.
//!
//! **The file** (`.ourabak`, everything little-endian):
//!
//! ```text
//! header   "OURABAK\x01" | argon2id m_kib u32 | t u32 | p u32 | salt[16] | nonce[19]
//! chunks   ( last u8 | len u32 | ciphertext[len] )*      — XChaCha20-Poly1305, STREAM
//! ```
//!
//! The key is Argon2id over the passphrase and salt. Each chunk seals up to 64 KiB of the
//! deflate-compressed record stream, with the header as associated data. STREAM binds every
//! chunk to its position and marks the final one, so a file that was cut short, reordered or
//! altered fails to open rather than restoring part of itself. Nothing needs to seek, so a
//! backup can be written straight into a pipe — which is what a cloud document provider
//! hands an Android app.
//!
//! Inside, the plaintext is `kind u8 | len u32 | payload` records: a JSON manifest first, an
//! end record with the event count last, and the rows between.

use std::io::{self, Read, Write};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::stream::{DecryptorBE32, EncryptorBE32};
use chacha20poly1305::aead::{KeyInit, Payload};
use chacha20poly1305::XChaCha20Poly1305;
use flate2::read::DeflateDecoder;
use flate2::write::DeflateEncoder;
use flate2::Compression;
use oura_store::{EffortLabel, RawEvent, Store};
use serde_json::{json, Value};

const MAGIC: &[u8; 8] = b"OURABAK\x01";
const HEADER_LEN: usize = 8 + 12 + 16 + 19;
/// Plaintext per sealed chunk.
const CHUNK: usize = 64 * 1024;
const TAG_LEN: usize = 16;
const FORMAT: u64 = 1;

/// The files that live beside the database and belong to the same history.
const SIDE_FILES: [&str; 2] = ["profile.json", "feature_modes.json"];

const R_MANIFEST: u8 = 1;
const R_DEVICE: u8 = 2;
const R_SYNC: u8 = 3;
const R_READING: u8 = 4;
const R_EVENT: u8 = 5;
const R_FILE: u8 = 6;
/// Added within format 1: a reader that predates it skips the record (see restore).
const R_LABEL: u8 = 7;
const R_END: u8 = 9;

#[derive(thiserror::Error, Debug)]
pub enum Error {
    #[error("this is not an open_oura backup file")]
    NotABackup,
    #[error("this backup was made by a newer version of open_oura (format {0})")]
    NewerFormat(u64),
    #[error("wrong passphrase, or the backup file is damaged")]
    WrongPassphrase,
    #[error("the backup file is damaged or incomplete: {0}")]
    Damaged(String),
    #[error("{0}")]
    Io(#[from] io::Error),
    #[error("{0}")]
    Store(#[from] oura_store::Error),
}

pub type Result<T> = std::result::Result<T, Error>;

/// How hard the passphrase is stretched. Stored in the header, so a backup says how to open
/// itself and the defaults can be raised later without orphaning old files.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Kdf {
    pub m_kib: u32,
    pub t: u32,
    pub p: u32,
}

impl Default for Kdf {
    /// 64 MiB, three passes: about a second on a phone, and every guess at the passphrase
    /// costs an attacker the same memory.
    fn default() -> Self {
        Kdf {
            m_kib: 64 * 1024,
            t: 3,
            p: 1,
        }
    }
}

impl Kdf {
    /// A header is attacker-controlled until a chunk has authenticated, so refuse
    /// parameters that would have this process allocate gigabytes on a file's say-so.
    fn sane(&self) -> bool {
        (8..=1024 * 1024).contains(&self.m_kib)
            && (1..=16).contains(&self.t)
            && (1..=8).contains(&self.p)
    }

    fn key(&self, passphrase: &str, salt: &[u8; 16]) -> Result<[u8; 32]> {
        let params = Params::new(self.m_kib, self.t, self.p, Some(32))
            .map_err(|e| Error::Damaged(format!("key parameters: {e}")))?;
        let mut key = [0u8; 32];
        Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
            .hash_password_into(passphrase.as_bytes(), salt, &mut key)
            .map_err(|e| Error::Damaged(format!("key derivation: {e}")))?;
        Ok(key)
    }
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct BackupStats {
    pub events: u64,
    pub readings: u64,
    pub devices: u64,
    pub labels: u64,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct RestoreStats {
    /// Events in the backup, and how many of them the database did not already have.
    pub events: u64,
    pub events_added: u64,
    pub readings_added: u64,
    /// Effort-session labels written: new ones, or newer edits of ones already held.
    pub labels_added: u64,
    /// Side files written because the destination had none.
    pub files_written: u64,
    /// When the backup was made (unix seconds).
    pub created_unix: i64,
}

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

// ---- sealing ---------------------------------------------------------------------------

/// Buffers plaintext and writes it out as sealed chunks.
struct Sealer<W: Write> {
    out: W,
    enc: Option<EncryptorBE32<XChaCha20Poly1305>>,
    header: [u8; HEADER_LEN],
    buf: Vec<u8>,
}

impl<W: Write> Sealer<W> {
    fn seal(&mut self, last: bool) -> io::Result<()> {
        let payload = Payload {
            msg: &self.buf,
            aad: &self.header,
        };
        let sealed = if last {
            let enc = self
                .enc
                .take()
                .ok_or_else(|| io::Error::other("backup already finished"))?;
            enc.encrypt_last(payload)
        } else {
            let enc = self
                .enc
                .as_mut()
                .ok_or_else(|| io::Error::other("backup already finished"))?;
            enc.encrypt_next(payload)
        }
        .map_err(|_| io::Error::other("encryption failed"))?;
        self.out.write_all(&[last as u8])?;
        self.out.write_all(&(sealed.len() as u32).to_le_bytes())?;
        self.out.write_all(&sealed)?;
        self.buf.clear();
        Ok(())
    }

    /// Seal whatever is left as the final chunk — even if that is nothing, because the
    /// final chunk is what proves the file was not cut short.
    fn finish(mut self) -> io::Result<W> {
        self.seal(true)?;
        self.out.flush()?;
        Ok(self.out)
    }
}

impl<W: Write> Write for Sealer<W> {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        let take = (CHUNK - self.buf.len()).min(data.len());
        self.buf.extend_from_slice(&data[..take]);
        if self.buf.len() == CHUNK {
            self.seal(false)?;
        }
        Ok(take)
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Reads sealed chunks and hands back their plaintext.
struct Opener<R: Read> {
    input: R,
    dec: Option<DecryptorBE32<XChaCha20Poly1305>>,
    header: [u8; HEADER_LEN],
    buf: Vec<u8>,
    pos: usize,
    /// Nothing has authenticated yet, so a failure is most likely the passphrase.
    first: bool,
}

/// Marks a failure the reader should report as a wrong passphrase.
const WRONG_PASSPHRASE: &str = "wrong-passphrase";

impl<R: Read> Opener<R> {
    fn next_chunk(&mut self) -> io::Result<bool> {
        let Some(dec) = self.dec.as_mut() else {
            return Ok(false); // the final chunk has been read: a clean end
        };
        let mut head = [0u8; 5];
        self.input.read_exact(&mut head).map_err(|e| {
            if e.kind() == io::ErrorKind::UnexpectedEof {
                io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "the file ends before its final chunk",
                )
            } else {
                e
            }
        })?;
        let last = head[0] == 1;
        let len = u32::from_le_bytes([head[1], head[2], head[3], head[4]]) as usize;
        if head[0] > 1 || len > CHUNK + TAG_LEN {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "a chunk header is not valid",
            ));
        }
        let mut sealed = vec![0u8; len];
        self.input.read_exact(&mut sealed)?;
        let payload = Payload {
            msg: &sealed,
            aad: &self.header,
        };
        let opened = if last {
            self.dec
                .take()
                .expect("checked above")
                .decrypt_last(payload)
        } else {
            dec.decrypt_next(payload)
        };
        let was_first = std::mem::replace(&mut self.first, false);
        self.buf = opened.map_err(|_| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                if was_first {
                    WRONG_PASSPHRASE
                } else {
                    "a chunk failed its integrity check"
                },
            )
        })?;
        self.pos = 0;
        Ok(true)
    }
}

impl<R: Read> Read for Opener<R> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        while self.pos == self.buf.len() {
            if !self.next_chunk()? {
                return Ok(0);
            }
        }
        let n = (self.buf.len() - self.pos).min(out.len());
        out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}

// ---- records ---------------------------------------------------------------------------

fn put(out: &mut impl Write, kind: u8, payload: &[u8]) -> io::Result<()> {
    out.write_all(&[kind])?;
    out.write_all(&(payload.len() as u32).to_le_bytes())?;
    out.write_all(payload)
}

fn put_json(out: &mut impl Write, kind: u8, v: &Value) -> io::Result<()> {
    put(out, kind, v.to_string().as_bytes())
}

/// `tag u8 | ring_timestamp i64 | captured_unix i64 | serial_len u8 | serial | body`
fn event_bytes(ev: &RawEvent, scratch: &mut Vec<u8>) {
    scratch.clear();
    scratch.push(ev.tag);
    scratch.extend_from_slice(&ev.ring_timestamp.to_le_bytes());
    scratch.extend_from_slice(&ev.captured_unix.to_le_bytes());
    let serial = ev.serial.as_bytes();
    let n = serial.len().min(255);
    scratch.push(n as u8);
    scratch.extend_from_slice(&serial[..n]);
    scratch.extend_from_slice(&ev.body);
}

fn event_from(b: &[u8]) -> Option<RawEvent> {
    let tag = *b.first()?;
    let ring_timestamp = i64::from_le_bytes(b.get(1..9)?.try_into().ok()?);
    let captured_unix = i64::from_le_bytes(b.get(9..17)?.try_into().ok()?);
    let n = *b.get(17)? as usize;
    let serial = std::str::from_utf8(b.get(18..18 + n)?).ok()?.to_string();
    Some(RawEvent {
        serial,
        tag,
        ring_timestamp,
        body: b.get(18 + n..)?.to_vec(),
        captured_unix,
    })
}

fn store_io(e: io::Error) -> oura_store::Error {
    oura_store::Error::Storage(e.to_string())
}

// ---- backup ----------------------------------------------------------------------------

/// Write an encrypted backup of the database at `db` to `out`.
///
/// Reads one consistent snapshot, so it is safe while a sync is writing. `progress` is
/// called with `(events written, events in total)`.
pub fn write_backup(
    db: &Path,
    passphrase: &str,
    out: impl Write,
    progress: &mut dyn FnMut(u64, u64),
) -> Result<BackupStats> {
    write_backup_with(db, passphrase, out, Kdf::default(), progress)
}

/// [`write_backup`] with explicit key-stretching parameters.
pub fn write_backup_with(
    db: &Path,
    passphrase: &str,
    mut out: impl Write,
    kdf: Kdf,
    progress: &mut dyn FnMut(u64, u64),
) -> Result<BackupStats> {
    let mut salt = [0u8; 16];
    let mut nonce = [0u8; 19];
    getrandom::getrandom(&mut salt).map_err(|e| Error::Io(io::Error::other(e.to_string())))?;
    getrandom::getrandom(&mut nonce).map_err(|e| Error::Io(io::Error::other(e.to_string())))?;

    let mut header = [0u8; HEADER_LEN];
    header[..8].copy_from_slice(MAGIC);
    header[8..12].copy_from_slice(&kdf.m_kib.to_le_bytes());
    header[12..16].copy_from_slice(&kdf.t.to_le_bytes());
    header[16..20].copy_from_slice(&kdf.p.to_le_bytes());
    header[20..36].copy_from_slice(&salt);
    header[36..].copy_from_slice(&nonce);

    let key = kdf.key(passphrase, &salt)?;
    let cipher = XChaCha20Poly1305::new((&key).into());
    out.write_all(&header)?;
    let sealer = Sealer {
        out,
        enc: Some(EncryptorBE32::from_aead(cipher, (&nonce).into())),
        header,
        buf: Vec::with_capacity(CHUNK),
    };
    let mut z = DeflateEncoder::new(sealer, Compression::new(6));

    let store = Store::open(db)?;
    let dir = db.parent().unwrap_or(Path::new("."));
    let mut stats = BackupStats::default();
    store.snapshot(|s| {
        let total = s.total_events()?;
        put_json(
            &mut z,
            R_MANIFEST,
            &json!({ "format": FORMAT, "created_unix": now_unix(), "events": total }),
        )
        .map_err(store_io)?;
        for (serial, hw, fw, api, mac, updated) in s.device_rows()? {
            put_json(
                &mut z,
                R_DEVICE,
                &json!([serial, hw, fw, api, mac, updated]),
            )
            .map_err(store_io)?;
            stats.devices += 1;
        }
        for name in SIDE_FILES {
            if let Ok(text) = std::fs::read_to_string(dir.join(name)) {
                put_json(&mut z, R_FILE, &json!({ "name": name, "text": text }))
                    .map_err(store_io)?;
            }
        }
        let mut scratch = Vec::new();
        s.for_each_raw_event(&mut |ev| {
            event_bytes(&ev, &mut scratch);
            put(&mut z, R_EVENT, &scratch).map_err(store_io)?;
            stats.events += 1;
            if stats.events.is_multiple_of(20_000) {
                progress(stats.events, total);
            }
            Ok(())
        })?;
        for (serial, kind, value, unit, captured) in s.reading_rows()? {
            put_json(
                &mut z,
                R_READING,
                &json!([serial, kind, value, unit, captured]),
            )
            .map_err(store_io)?;
            stats.readings += 1;
        }
        for l in s.effort_labels()? {
            put_json(
                &mut z,
                R_LABEL,
                &json!([
                    l.serial,
                    l.capture_lo,
                    l.start_ds,
                    l.end_ds,
                    l.kind,
                    l.note,
                    l.updated_unix
                ]),
            )
            .map_err(store_io)?;
            stats.labels += 1;
        }
        // Cursors last: a cursor is only true alongside the events before it.
        for (serial, cursor, last_sync) in s.sync_rows()? {
            put_json(&mut z, R_SYNC, &json!([serial, cursor, last_sync])).map_err(store_io)?;
        }
        put_json(&mut z, R_END, &json!({ "events": stats.events })).map_err(store_io)?;
        progress(stats.events, total);
        Ok(())
    })?;
    z.finish()?.finish()?;
    Ok(stats)
}

// ---- restore ---------------------------------------------------------------------------

fn damaged(what: &str) -> Error {
    Error::Damaged(what.to_string())
}

fn read_error(e: io::Error) -> Error {
    match e.kind() {
        io::ErrorKind::InvalidData if e.to_string() == WRONG_PASSPHRASE => Error::WrongPassphrase,
        io::ErrorKind::InvalidData | io::ErrorKind::UnexpectedEof => Error::Damaged(e.to_string()),
        _ => Error::Io(e),
    }
}

/// One record, or `None` at a clean end of the stream.
fn next_record(z: &mut impl Read, payload: &mut Vec<u8>) -> Result<Option<u8>> {
    let mut head = [0u8; 5];
    match z.read(&mut head[..1]).map_err(read_error)? {
        0 => return Ok(None),
        _ => z.read_exact(&mut head[1..]).map_err(read_error)?,
    }
    let len = u32::from_le_bytes([head[1], head[2], head[3], head[4]]) as usize;
    if len > 64 * 1024 * 1024 {
        return Err(damaged("a record is implausibly large"));
    }
    payload.resize(len, 0);
    z.read_exact(payload).map_err(read_error)?;
    Ok(Some(head[0]))
}

/// A label record: `[serial, capture_lo, start_ds, end_ds, kind, note, updated_unix]`.
fn label_from(v: &Value) -> Option<EffortLabel> {
    Some(EffortLabel {
        serial: v[0].as_str()?.to_string(),
        capture_lo: v[1].as_i64()?,
        start_ds: v[2].as_i64()?,
        end_ds: v[3].as_i64()?,
        kind: v[4].as_str()?.to_string(),
        note: v[5].as_str().map(str::to_string),
        updated_unix: v[6].as_i64().unwrap_or(0),
    })
}

fn json_of(payload: &[u8]) -> Result<Value> {
    serde_json::from_slice(payload).map_err(|_| damaged("a record is not valid JSON"))
}

/// Merge the backup read from `input` into the database at `db`, creating it if needed.
///
/// All or nothing: a wrong passphrase, or a file that turns out to be damaged part-way
/// through, leaves the database exactly as it was. `progress` is called with
/// `(events read, events in the backup)`.
pub fn restore_backup(
    mut input: impl Read,
    passphrase: &str,
    db: &Path,
    progress: &mut dyn FnMut(u64, u64),
) -> Result<RestoreStats> {
    let mut header = [0u8; HEADER_LEN];
    input
        .read_exact(&mut header)
        .map_err(|_| Error::NotABackup)?;
    if header[..7] != MAGIC[..7] {
        return Err(Error::NotABackup);
    }
    if header[7] != MAGIC[7] {
        return Err(Error::NewerFormat(header[7] as u64));
    }
    let u32_at =
        |i: usize| u32::from_le_bytes([header[i], header[i + 1], header[i + 2], header[i + 3]]);
    let kdf = Kdf {
        m_kib: u32_at(8),
        t: u32_at(12),
        p: u32_at(16),
    };
    if !kdf.sane() {
        return Err(damaged("the key parameters are out of range"));
    }
    let salt: [u8; 16] = header[20..36].try_into().expect("fixed slice");
    let nonce: [u8; 19] = header[36..].try_into().expect("fixed slice");
    let key = kdf.key(passphrase, &salt)?;
    let cipher = XChaCha20Poly1305::new((&key).into());
    let mut z = DeflateDecoder::new(Opener {
        input,
        dec: Some(DecryptorBE32::from_aead(cipher, (&nonce).into())),
        header,
        buf: Vec::new(),
        pos: 0,
        first: true,
    });

    let mut payload = Vec::new();
    // The manifest is read before the database is touched, so a wrong passphrase never
    // even opens it.
    let manifest = match next_record(&mut z, &mut payload)? {
        Some(R_MANIFEST) => json_of(&payload)?,
        _ => return Err(damaged("the backup does not start with a manifest")),
    };
    let format = manifest["format"].as_u64().unwrap_or(0);
    if format > FORMAT {
        return Err(Error::NewerFormat(format));
    }
    let total = manifest["events"].as_u64().unwrap_or(0);
    let mut stats = RestoreStats {
        created_unix: manifest["created_unix"].as_i64().unwrap_or(0),
        ..Default::default()
    };

    let store = Store::open(db)?;
    let dir = db.parent().unwrap_or(Path::new(".")).to_path_buf();
    let mut files: Vec<(String, String)> = Vec::new();
    // Errors that are not the store's own are carried out of the transaction here.
    let failure: std::cell::RefCell<Option<Error>> = std::cell::RefCell::new(None);
    let fail = |e: Error| {
        *failure.borrow_mut() = Some(e);
        oura_store::Error::Storage("backup unreadable".into())
    };
    let bad = |what: &str| fail(damaged(what));
    let outcome = store.transaction(|s| {
        let mut ended = false;
        loop {
            let kind = match next_record(&mut z, &mut payload) {
                Ok(Some(k)) => k,
                Ok(None) => break,
                Err(e) => return Err(fail(e)),
            };
            match kind {
                R_EVENT => {
                    let ev =
                        event_from(&payload).ok_or_else(|| bad("an event record is malformed"))?;
                    stats.events += 1;
                    if s.insert_raw_event(&ev)? {
                        stats.events_added += 1;
                    }
                    if stats.events.is_multiple_of(20_000) {
                        progress(stats.events, total);
                    }
                }
                R_DEVICE => {
                    let v = json_of(&payload).map_err(|_| bad("a device record is malformed"))?;
                    let serial = v[0]
                        .as_str()
                        .ok_or_else(|| bad("a device record is malformed"))?;
                    s.merge_device_row(
                        serial,
                        v[1].as_str(),
                        v[2].as_str(),
                        v[3].as_str(),
                        v[4].as_str(),
                        v[5].as_i64().unwrap_or(0),
                    )?;
                }
                R_READING => {
                    let v = json_of(&payload).map_err(|_| bad("a reading record is malformed"))?;
                    let (Some(serial), Some(kind), Some(value)) =
                        (v[0].as_str(), v[1].as_str(), v[2].as_f64())
                    else {
                        return Err(bad("a reading record is malformed"));
                    };
                    if s.merge_reading(
                        serial,
                        kind,
                        value,
                        v[3].as_str(),
                        v[4].as_i64().unwrap_or(0),
                    )? {
                        stats.readings_added += 1;
                    }
                }
                R_SYNC => {
                    let v = json_of(&payload).map_err(|_| bad("a cursor record is malformed"))?;
                    let serial = v[0]
                        .as_str()
                        .ok_or_else(|| bad("a cursor record is malformed"))?;
                    s.merge_sync_row(
                        serial,
                        v[1].as_i64().unwrap_or(0),
                        v[2].as_i64().unwrap_or(0),
                    )?;
                }
                R_LABEL => {
                    let l = json_of(&payload)
                        .ok()
                        .and_then(|v| label_from(&v))
                        .ok_or_else(|| bad("a label record is malformed"))?;
                    if s.merge_effort_label(&l)? {
                        stats.labels_added += 1;
                    }
                }
                R_FILE => {
                    let v = json_of(&payload).map_err(|_| bad("a file record is malformed"))?;
                    if let (Some(name), Some(text)) = (v["name"].as_str(), v["text"].as_str()) {
                        // Only the names this format knows: a backup must not be able to
                        // write anywhere else.
                        if SIDE_FILES.contains(&name) {
                            files.push((name.to_string(), text.to_string()));
                        }
                    }
                }
                R_END => {
                    let v = json_of(&payload).map_err(|_| bad("the end record is malformed"))?;
                    if v["events"].as_u64() != Some(stats.events) {
                        return Err(bad("the event count does not match the backup's own total"));
                    }
                    ended = true;
                }
                // A record kind from a later minor revision: skip it rather than fail.
                _ => {}
            }
        }
        if !ended {
            return Err(bad("the backup has no end record"));
        }
        Ok(())
    });
    if let Some(e) = failure.into_inner() {
        return Err(e);
    }
    outcome?;

    // Side files only where the destination has none: a restore fills gaps, and must not
    // replace a profile someone has since edited.
    for (name, text) in files {
        let path = dir.join(&name);
        if !path.exists() && std::fs::write(&path, text).is_ok() {
            stats.files_written += 1;
        }
    }
    progress(stats.events, total);
    Ok(stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    /// Cheap stretching: the tests are about the format, not about waiting on Argon2.
    const FAST: Kdf = Kdf {
        m_kib: 64,
        t: 1,
        p: 1,
    };

    /// A fresh directory per test, so each has its own database and side files.
    fn dir(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("oura-backup-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn event(i: i64) -> RawEvent {
        RawEvent {
            serial: "RING1".into(),
            tag: 0x43,
            ring_timestamp: 1_000 + i,
            // Pseudo-random, so it does not compress away: 2,000 of these span several
            // sealed chunks, which is what the truncation and tampering tests need.
            body: {
                let mut x = (i as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1;
                (0..200)
                    .map(|_| {
                        x ^= x << 13;
                        x ^= x >> 7;
                        x ^= x << 17;
                        x as u8
                    })
                    .collect()
            },
            captured_unix: 1_790_000_000 + i,
        }
    }

    /// A database with `events` numbered `from..to`, one device, a cursor and a reading.
    fn seed(db: &Path, range: std::ops::Range<i64>, last_sync: i64) {
        let s = Store::open(db).unwrap();
        for i in range.clone() {
            s.insert_raw_event(&event(i)).unwrap();
        }
        s.merge_device_row("RING1", Some("hw"), Some("fw1"), None, None, last_sync)
            .unwrap();
        s.merge_sync_row("RING1", range.end, last_sync).unwrap();
        s.merge_reading("RING1", "battery_percent", 80.0, Some("%"), last_sync)
            .unwrap();
    }

    fn backup(db: &Path, pass: &str) -> Vec<u8> {
        let mut out = Vec::new();
        write_backup_with(db, pass, &mut out, FAST, &mut |_, _| {}).unwrap();
        out
    }

    fn all_events(db: &Path) -> Vec<RawEvent> {
        let mut v = Vec::new();
        Store::open(db)
            .unwrap()
            .for_each_raw_event(&mut |e| {
                v.push(e);
                Ok(())
            })
            .unwrap();
        v
    }

    #[test]
    fn effort_labels_travel_with_a_backup_and_the_newer_edit_wins() {
        let src = dir("lbl-src");
        let dst = dir("lbl-dst");
        seed(&src.join("oura.db"), 0..10, 500);
        let lbl = |kind: &str, note: Option<&str>, at: i64| EffortLabel {
            serial: "RING1".into(),
            capture_lo: 1_790_000_000,
            start_ds: 1_000,
            end_ds: 1_009,
            kind: kind.into(),
            note: note.map(str::to_string),
            updated_unix: at,
        };
        let boot = (1_790_000_000, 1_790_000_009);
        Store::open(src.join("oura.db"))
            .unwrap()
            .set_effort_label(&lbl("dance", Some("salsa class"), 600), boot)
            .unwrap();
        // The destination labelled the same session earlier, differently.
        seed(&dst.join("oura.db"), 0..10, 400);
        Store::open(dst.join("oura.db"))
            .unwrap()
            .set_effort_label(&lbl("walk", None, 450), boot)
            .unwrap();

        let file = backup(&src.join("oura.db"), "pw");
        let stats = restore_backup(&file[..], "pw", &dst.join("oura.db"), &mut |_, _| {}).unwrap();
        assert_eq!(stats.labels_added, 1);
        assert_eq!(
            Store::open(dst.join("oura.db"))
                .unwrap()
                .effort_labels()
                .unwrap(),
            vec![lbl("dance", Some("salsa class"), 600)]
        );
        // Restoring again changes nothing.
        let again = restore_backup(&file[..], "pw", &dst.join("oura.db"), &mut |_, _| {}).unwrap();
        assert_eq!(again.labels_added, 0);
    }

    #[test]
    fn a_backup_restores_into_an_empty_database() {
        let src = dir("rt-src");
        let dst = dir("rt-dst");
        seed(&src.join("oura.db"), 0..2_000, 500);
        std::fs::write(src.join("profile.json"), r#"{"age":30}"#).unwrap();

        let file = backup(&src.join("oura.db"), "correct horse");
        let stats = restore_backup(
            &file[..],
            "correct horse",
            &dst.join("oura.db"),
            &mut |_, _| {},
        )
        .unwrap();

        assert_eq!((stats.events, stats.events_added), (2_000, 2_000));
        assert_eq!(stats.readings_added, 1);
        assert_eq!(stats.files_written, 1);
        assert_eq!(
            all_events(&dst.join("oura.db")),
            all_events(&src.join("oura.db"))
        );
        let s = Store::open(dst.join("oura.db")).unwrap();
        assert_eq!(
            s.sync_rows().unwrap(),
            vec![("RING1".to_string(), 2_000, 500)]
        );
        assert_eq!(
            std::fs::read_to_string(dst.join("profile.json")).unwrap(),
            r#"{"age":30}"#
        );
    }

    #[test]
    fn the_file_is_much_smaller_than_what_it_holds_and_shows_none_of_it() {
        let src = dir("size");
        seed(&src.join("oura.db"), 0..2_000, 500);
        let file = backup(&src.join("oura.db"), "pw");
        // Several sealed chunks, so the multi-chunk path is what the other tests exercise.
        assert!(file.len() > 2 * CHUNK, "{} bytes", file.len());
        assert!(
            !file.windows(5).any(|w| w == b"RING1"),
            "the serial is readable in the file"
        );
    }

    #[test]
    fn restoring_twice_adds_nothing_the_second_time() {
        let src = dir("twice-src");
        let dst = dir("twice-dst");
        seed(&src.join("oura.db"), 0..300, 500);
        let file = backup(&src.join("oura.db"), "pw");
        restore_backup(&file[..], "pw", &dst.join("oura.db"), &mut |_, _| {}).unwrap();
        let again = restore_backup(&file[..], "pw", &dst.join("oura.db"), &mut |_, _| {}).unwrap();
        assert_eq!(
            (again.events, again.events_added, again.readings_added),
            (300, 0, 0)
        );
        assert_eq!(all_events(&dst.join("oura.db")).len(), 300);
    }

    #[test]
    fn restoring_merges_with_what_the_destination_synced_itself() {
        // The phone backed up events 0..300 at t=500. The laptop has 200..450 from its own
        // later sync (t=900), and a profile of its own.
        let phone = dir("merge-phone");
        let laptop = dir("merge-laptop");
        seed(&phone.join("oura.db"), 0..300, 500);
        std::fs::write(phone.join("profile.json"), r#"{"age":30}"#).unwrap();
        seed(&laptop.join("oura.db"), 200..450, 900);
        std::fs::write(laptop.join("profile.json"), r#"{"age":31}"#).unwrap();

        let file = backup(&phone.join("oura.db"), "pw");
        let stats =
            restore_backup(&file[..], "pw", &laptop.join("oura.db"), &mut |_, _| {}).unwrap();

        assert_eq!((stats.events, stats.events_added), (300, 200));
        assert_eq!(all_events(&laptop.join("oura.db")).len(), 450);
        // The laptop's sync was the later one, so its cursor and its profile stand.
        let s = Store::open(laptop.join("oura.db")).unwrap();
        assert_eq!(
            s.sync_rows().unwrap(),
            vec![("RING1".to_string(), 450, 900)]
        );
        assert_eq!(stats.files_written, 0);
        assert_eq!(
            std::fs::read_to_string(laptop.join("profile.json")).unwrap(),
            r#"{"age":31}"#
        );
    }

    #[test]
    fn a_wrong_passphrase_is_refused_and_touches_nothing() {
        let src = dir("wrong-src");
        let dst = dir("wrong-dst");
        seed(&src.join("oura.db"), 0..50, 500);
        let file = backup(&src.join("oura.db"), "right");
        let err =
            restore_backup(&file[..], "wrong", &dst.join("oura.db"), &mut |_, _| {}).unwrap_err();
        assert!(matches!(err, Error::WrongPassphrase), "{err}");
        assert!(
            !dst.join("oura.db").exists(),
            "the database was created before the passphrase was checked"
        );
    }

    #[test]
    fn a_file_cut_short_restores_nothing() {
        let src = dir("cut-src");
        let dst = dir("cut-dst");
        seed(&src.join("oura.db"), 0..2_000, 500);
        seed(&dst.join("oura.db"), 5_000..5_010, 100);
        let file = backup(&src.join("oura.db"), "pw");
        // Cut on a chunk boundary: every chunk present is genuine, only the end is missing.
        let first_len =
            u32::from_le_bytes(file[HEADER_LEN + 1..HEADER_LEN + 5].try_into().unwrap()) as usize;
        let cut = &file[..HEADER_LEN + 5 + first_len];
        let err = restore_backup(cut, "pw", &dst.join("oura.db"), &mut |_, _| {}).unwrap_err();
        assert!(matches!(err, Error::Damaged(_)), "{err}");
        assert_eq!(
            all_events(&dst.join("oura.db")).len(),
            10,
            "a partial restore was kept"
        );
    }

    #[test]
    fn a_single_altered_byte_is_caught() {
        let src = dir("flip-src");
        let dst = dir("flip-dst");
        seed(&src.join("oura.db"), 0..2_000, 500);
        let mut file = backup(&src.join("oura.db"), "pw");
        let n = file.len();
        file[n - 40] ^= 1; // inside the last chunk, long after the first has authenticated
        let err =
            restore_backup(&file[..], "pw", &dst.join("oura.db"), &mut |_, _| {}).unwrap_err();
        assert!(matches!(err, Error::Damaged(_)), "{err}");
        assert_eq!(all_events(&dst.join("oura.db")).len(), 0);
    }

    #[test]
    fn an_altered_header_is_caught_too() {
        // The header is not encrypted, but it is authenticated with every chunk.
        let src = dir("hdr-src");
        seed(&src.join("oura.db"), 0..50, 500);
        let mut file = backup(&src.join("oura.db"), "pw");
        file[40] ^= 1; // a nonce byte
        let err = restore_backup(
            &file[..],
            "pw",
            &dir("hdr-dst").join("oura.db"),
            &mut |_, _| {},
        )
        .unwrap_err();
        assert!(matches!(err, Error::WrongPassphrase), "{err}");
    }

    #[test]
    fn other_files_are_not_mistaken_for_backups() {
        let dst = dir("not");
        let err = restore_backup(
            &b"SQLite format 3\0 and then some more bytes to fill a header....."[..],
            "pw",
            &dst.join("oura.db"),
            &mut |_, _| {},
        )
        .unwrap_err();
        assert!(matches!(err, Error::NotABackup), "{err}");
        let err =
            restore_backup(&b"short"[..], "pw", &dst.join("oura.db"), &mut |_, _| {}).unwrap_err();
        assert!(matches!(err, Error::NotABackup), "{err}");
    }

    #[test]
    fn a_header_cannot_demand_absurd_memory() {
        let src = dir("kdf-src");
        seed(&src.join("oura.db"), 0..5, 500);
        let mut file = backup(&src.join("oura.db"), "pw");
        file[8..12].copy_from_slice(&u32::MAX.to_le_bytes());
        let err = restore_backup(
            &file[..],
            "pw",
            &dir("kdf-dst").join("oura.db"),
            &mut |_, _| {},
        )
        .unwrap_err();
        assert!(matches!(err, Error::Damaged(_)), "{err}");
    }

    #[test]
    fn an_empty_database_still_makes_a_valid_backup() {
        let src = dir("empty-src");
        Store::open(src.join("oura.db")).unwrap();
        let file = backup(&src.join("oura.db"), "pw");
        let stats = restore_backup(
            &file[..],
            "pw",
            &dir("empty-dst").join("oura.db"),
            &mut |_, _| {},
        )
        .unwrap();
        assert_eq!(stats.events, 0);
    }
}
