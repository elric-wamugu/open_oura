//! Optional SQLite persistence (feature `storage`).
//!
//! Events are stored with their raw body retained, so unknown event types are
//! never lost and can be decoded later. A per-device sync cursor enables
//! incremental syncs. Re-syncing is idempotent: identical events are de-duped.

use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{params, Connection, OptionalExtension};

use crate::error::Result;
use oura_protocol::device::{Battery, DeviceInfo};
use oura_protocol::events::RingEvent;

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS device (
    serial        TEXT PRIMARY KEY,
    hardware_id   TEXT,
    firmware      TEXT,
    api_version   TEXT,
    mac           TEXT,
    updated_unix  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_state (
    serial        TEXT PRIMARY KEY,
    next_cursor   INTEGER NOT NULL,
    last_sync_unix INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    serial         TEXT NOT NULL,
    tag            INTEGER NOT NULL,
    name           TEXT NOT NULL,
    ring_timestamp INTEGER NOT NULL,
    body           BLOB NOT NULL,
    decoded_json   TEXT,
    captured_unix  INTEGER NOT NULL,
    UNIQUE(serial, tag, ring_timestamp, body)
);

CREATE TABLE IF NOT EXISTS readings (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    serial        TEXT NOT NULL,
    kind          TEXT NOT NULL,
    value         REAL NOT NULL,
    unit          TEXT,
    captured_unix INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_readings_serial_kind ON readings(serial, kind);

-- Sleep stages computed on this device from the night's beats, motion and temperature,
-- kept so that the slow part runs once a night. Derived, like decoded_json: never backed
-- up, and safe to delete. `capture_lo` is the earliest capture time of the night's boot,
-- which tells two boots' nights apart when their ring times coincide; `inputs` is how many
-- events the night had when staged, so a night that gains data is staged again.
CREATE TABLE IF NOT EXISTS sleep_stage_cache (
    serial        TEXT NOT NULL,
    start_ds      INTEGER NOT NULL,
    end_ds        INTEGER NOT NULL,
    capture_lo    INTEGER NOT NULL,
    model         TEXT NOT NULL,
    inputs        INTEGER NOT NULL,
    stages        TEXT NOT NULL,
    computed_unix INTEGER NOT NULL,
    PRIMARY KEY (serial, start_ds, end_ds, capture_lo, model)
);
"#;

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// A decoded event as readers take it: `(ring_timestamp, tag, decoded_json, captured_unix)`.
type DecodedRow = (i64, u8, String, i64);

/// How many connections share the big read, and below how many rows it is not worth it.
/// Four is the Pixel 9a's count of fast cores; more only adds its slow ones.
const PARALLEL_READERS: usize = 4;
const PARALLEL_MIN_ROWS: i64 = 200_000;

/// One slice of the decoded-events read: ids in `(after, upto]`, in id order.
fn read_decoded(
    conn: &Connection,
    sql: &str,
    after: i64,
    upto: i64,
    serial: Option<&str>,
) -> Result<Vec<DecodedRow>> {
    let mut stmt = conn.prepare(sql)?;
    let map_row = |r: &rusqlite::Row<'_>| {
        Ok((
            r.get::<_, i64>(0)?,
            r.get::<_, i64>(1)? as u8,
            r.get::<_, String>(2)?,
            r.get::<_, i64>(3)?,
        ))
    };
    let rows = match serial {
        Some(serial) => stmt.query_map(params![after, upto, serial], map_row)?,
        None => stmt.query_map(params![after, upto], map_row)?,
    }
    .collect::<std::result::Result<Vec<_>, _>>()?;
    Ok(rows)
}

/// Which staged night a [`Store::stage_cache_get`] / [`Store::stage_cache_put`] is about.
#[derive(Debug, Clone, PartialEq)]
pub struct StageCacheKey {
    pub serial: String,
    pub start_ds: i64,
    pub end_ds: i64,
    pub capture_lo: i64,
    /// Which stager produced it; a new model or pipeline gets a new name, so its nights are
    /// staged afresh instead of mixing with the old ones.
    pub model: String,
}

/// One event as the ring delivered it, with when it was captured. Everything else a row
/// holds is derived from these.
#[derive(Debug, Clone, PartialEq)]
pub struct RawEvent {
    pub serial: String,
    pub tag: u8,
    pub ring_timestamp: i64,
    pub body: Vec<u8>,
    pub captured_unix: i64,
}

/// A SQLite-backed store for ring data.
pub struct Store {
    conn: Connection,
}

impl Store {
    /// Open (creating if needed) a database at `path` and ensure the schema.
    pub fn open<P: AsRef<Path>>(path: P) -> Result<Self> {
        let path = path.as_ref();
        let conn = Connection::open(path)?;
        // Health data + device identifiers are sensitive; keep the DB owner-only.
        // Do this BEFORE switching on WAL: SQLite copies the main database's mode onto
        // the -wal/-shm files it then creates, so those inherit 0600 as well.
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
                .map_err(|e| crate::error::Error::Storage(e.to_string()))?;
        }
        // WAL + synchronous=NORMAL fsync once per checkpoint instead of once per
        // INSERT. A sync draining tens of thousands of events under the default
        // (rollback journal + synchronous=FULL) pays an fsync per new row — minutes
        // of disk time; here it's seconds. WAL also lets the dashboard read the DB
        // while a sync is writing, without blocking.
        conn.execute_batch("PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL;")?;
        conn.execute_batch(SCHEMA)?;
        // `idx_events_serial_tag` was 137 MB of a 1,033 MB database and did nothing the
        // UNIQUE constraint's own index does not: that one starts with (serial, tag) too,
        // so every lookup the narrower index could serve, it serves. The summary's big
        // read had to go out of its way to avoid it (see `decoded_events_query`).
        //
        // Best effort: it needs the write lock, and if a sync holds that right now the
        // next open drops it instead. The freed pages are reused as the history grows;
        // `compact` hands them back to the filesystem.
        let _ = conn.execute_batch("DROP INDEX IF EXISTS idx_events_serial_tag");
        Ok(Self { conn })
    }

    /// Rewrite the database file without its free pages (SQLite's `VACUUM`), returning
    /// its size in bytes before and after.
    ///
    /// Nothing in the database changes; only how the file is packed. It needs free space
    /// of about twice the database's size while it runs, takes the write lock for the
    /// duration, and is rolled back as a whole if it is interrupted.
    pub fn compact(&self) -> Result<(u64, u64)> {
        let size = |conn: &Connection| -> Result<u64> {
            let pages: i64 = conn.query_row("PRAGMA page_count", [], |r| r.get(0))?;
            let page: i64 = conn.query_row("PRAGMA page_size", [], |r| r.get(0))?;
            Ok((pages * page) as u64)
        };
        let before = size(&self.conn)?;
        self.conn.execute_batch("VACUUM")?;
        // In WAL mode the rewritten pages sit in the log until a checkpoint; truncating
        // it is what actually gives the space back.
        self.conn
            .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()))?;
        Ok((before, size(&self.conn)?))
    }

    /// Open an in-memory database (useful for tests).
    pub fn open_in_memory() -> Result<Self> {
        let conn = Connection::open_in_memory()?;
        conn.execute_batch(SCHEMA)?;
        Ok(Self { conn })
    }

    /// Record/refresh device metadata.
    pub fn upsert_device(
        &self,
        serial: &str,
        hardware_id: Option<&str>,
        info: Option<&DeviceInfo>,
    ) -> Result<()> {
        self.conn.execute(
            "INSERT INTO device (serial, hardware_id, firmware, api_version, mac, updated_unix)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(serial) DO UPDATE SET
               hardware_id=COALESCE(excluded.hardware_id, device.hardware_id),
               firmware=COALESCE(excluded.firmware, device.firmware),
               api_version=COALESCE(excluded.api_version, device.api_version),
               mac=COALESCE(excluded.mac, device.mac),
               updated_unix=excluded.updated_unix",
            params![
                serial,
                hardware_id,
                info.map(|i| i.firmware_version.clone()),
                info.map(|i| i.api_version.clone()),
                info.map(|i| i.mac.clone()),
                now_unix(),
            ],
        )?;
        Ok(())
    }

    /// Device identity + last-sync for display: the most-recently-updated device
    /// row joined with its sync state.
    /// Returns `(serial, hardware_id, firmware, api_version, mac, updated_unix, last_sync_unix, next_cursor)`.
    #[allow(clippy::type_complexity)]
    pub fn device_info(
        &self,
    ) -> Result<Option<(String, String, String, String, String, i64, i64, i64)>> {
        let row = self
            .conn
            .query_row(
                "SELECT d.serial, COALESCE(d.hardware_id,''), COALESCE(d.firmware,''),
                        COALESCE(d.api_version,''), COALESCE(d.mac,''), COALESCE(d.updated_unix,0),
                        COALESCE(s.last_sync_unix,0), COALESCE(s.next_cursor,0)
                 FROM device d LEFT JOIN sync_state s ON s.serial = d.serial
                 ORDER BY d.updated_unix DESC LIMIT 1",
                [],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, String>(2)?,
                        r.get::<_, String>(3)?,
                        r.get::<_, String>(4)?,
                        r.get::<_, i64>(5)?,
                        r.get::<_, i64>(6)?,
                        r.get::<_, i64>(7)?,
                    ))
                },
            )
            .optional()?;
        Ok(row)
    }

    /// The persisted incremental-sync cursor (deciseconds), or 0 if none.
    pub fn cursor(&self, serial: &str) -> Result<u32> {
        let v: Option<i64> = self
            .conn
            .query_row(
                "SELECT next_cursor FROM sync_state WHERE serial = ?1",
                params![serial],
                |r| r.get(0),
            )
            .optional()?;
        Ok(v.unwrap_or(0) as u32)
    }

    /// Persist the next sync cursor.
    pub fn set_cursor(&self, serial: &str, cursor: u32) -> Result<()> {
        self.conn.execute(
            "INSERT INTO sync_state (serial, next_cursor, last_sync_unix)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(serial) DO UPDATE SET
               next_cursor=excluded.next_cursor,
               last_sync_unix=excluded.last_sync_unix",
            params![serial, cursor as i64, now_unix()],
        )?;
        Ok(())
    }

    /// Insert an event, ignoring exact duplicates. Returns true if a row was added.
    pub fn insert_event(&self, serial: &str, ev: &RingEvent) -> Result<bool> {
        let decoded = ev
            .decoded
            .as_ref()
            .map(|v| serde_json::to_string(v).unwrap_or_default());
        let changed = self.conn.execute(
            "INSERT OR IGNORE INTO events
               (serial, tag, name, ring_timestamp, body, decoded_json, captured_unix)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                serial,
                ev.tag as i64,
                ev.name,
                ev.timestamp as i64,
                ev.body,
                decoded,
                now_unix(),
            ],
        )?;
        Ok(changed > 0)
    }

    /// Record a scalar reading (e.g. live HR bpm, SpO2 %, battery %).
    pub fn insert_reading(&self, serial: &str, kind: &str, value: f64, unit: &str) -> Result<()> {
        self.conn.execute(
            "INSERT INTO readings (serial, kind, value, unit, captured_unix)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![serial, kind, value, unit, now_unix()],
        )?;
        Ok(())
    }

    /// Convenience: store a battery reading.
    pub fn insert_battery(&self, serial: &str, battery: &Battery) -> Result<()> {
        self.insert_reading(serial, "battery_percent", battery.percent as f64, "%")
    }

    /// The newest reading of `kind` for a device: `(value, captured_unix)`.
    pub fn latest_reading(&self, serial: &str, kind: &str) -> Result<Option<(f64, i64)>> {
        let row = self
            .conn
            .query_row(
                "SELECT value, captured_unix FROM readings \
                 WHERE serial = ?1 AND kind = ?2 ORDER BY captured_unix DESC, id DESC LIMIT 1",
                params![serial, kind],
                |r| Ok((r.get::<_, f64>(0)?, r.get::<_, i64>(1)?)),
            )
            .optional()?;
        Ok(row)
    }

    /// Re-decode every stored event body with the current decoders, updating
    /// `decoded_json`. Returns `(rows_with_decode, total_rows)`. Lets new decoders
    /// be applied to events captured before they existed — no re-sync needed.
    pub fn redecode(&self) -> Result<(usize, usize)> {
        let rows: Vec<(i64, i64, Vec<u8>)> = {
            let mut stmt = self.conn.prepare("SELECT id, tag, body FROM events")?;
            let collected = stmt
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            collected
        };
        let total = rows.len();
        let mut decoded_count = 0;
        for (id, tag, body) in rows {
            let decoded = oura_protocol::events::decode_event_body(tag as u8, &body)
                .map(|v| serde_json::to_string(&v).unwrap_or_default());
            if decoded.is_some() {
                decoded_count += 1;
            }
            let name = oura_protocol::events::event_name(tag as u8);
            self.conn.execute(
                "UPDATE events SET decoded_json = ?1, name = ?2 WHERE id = ?3",
                params![decoded, name, id],
            )?;
        }
        Ok((decoded_count, total))
    }

    /// All decoded events as `(ring_timestamp_deciseconds, tag, decoded_json,
    /// captured_unix)`, ordered by insertion/sync order. The ring timestamp can reset
    /// after reboots, so callers that reconstruct boot epochs need the real stream order.
    pub fn decoded_events(&self) -> Result<Vec<(i64, u8, String, i64)>> {
        self.decoded_events_for_serial_opt(None)
    }

    /// Decoded events for one device serial, ordered by insertion/sync order.
    pub fn decoded_events_for_serial(&self, serial: &str) -> Result<Vec<(i64, u8, String, i64)>> {
        self.decoded_events_for_serial_opt(Some(serial))
    }

    /// Decoded events like [`Store::decoded_events_for_serial`], but with the JSON of
    /// every tag outside `json_tags` left as an empty string.
    ///
    /// Every row still comes back — callers count events and fit the clock from all of
    /// them — but only the tags named have their JSON copied out of the database. On a
    /// year-scale history most of the JSON by weight belongs to streams a given reader
    /// never parses (raw beat intervals, step features), and holding it all in memory at
    /// once is what a phone can least afford.
    pub fn decoded_events_with_json_for(
        &self,
        serial: Option<&str>,
        json_tags: &[u8],
    ) -> Result<Vec<(i64, u8, String, i64)>> {
        self.decoded_events_query(serial, Some(json_tags))
    }

    fn decoded_events_for_serial_opt(
        &self,
        serial: Option<&str>,
    ) -> Result<Vec<(i64, u8, String, i64)>> {
        self.decoded_events_query(serial, None)
    }

    fn decoded_events_query(
        &self,
        serial: Option<&str>,
        json_tags: Option<&[u8]>,
    ) -> Result<Vec<(i64, u8, String, i64)>> {
        // `+serial` keeps SQLite off the indexes for this filter, and that one character
        // is worth roughly 9x on a real database.
        //
        // Every index on `events` starts with `serial`, and there is one ring, so
        // `serial = ?` matches every row. SQLite still takes an index for the equality,
        // which hands rows back in index order and then needs a TEMP B-TREE to put
        // millions of them back in `id` order, spilling to disk on the way. Measured on a
        // 523 MB database, 1,866,568 rows, identical results both ways:
        //
        //     AND serial = ?    SEARCH … USE TEMP B-TREE FOR ORDER BY    51.8 s
        //     AND +serial = ?   SCAN events                               5.6 s
        //
        // The scan is already in `id` order, so the sort disappears entirely.
        let serial_filter = if serial.is_some() {
            " AND +serial = ?3"
        } else {
            ""
        };
        // Tags are numbers from this program, not user input, so they go in as literals.
        let json_column = match json_tags {
            None => "decoded_json".to_string(),
            Some([]) => "''".to_string(),
            Some(tags) => format!(
                "CASE WHEN tag IN ({}) THEN decoded_json ELSE '' END",
                tags.iter().map(u8::to_string).collect::<Vec<_>>().join(",")
            ),
        };
        let sql = format!(
            "SELECT ring_timestamp, tag, {json_column}, captured_unix FROM events \
                 WHERE decoded_json IS NOT NULL AND id > ?1 AND id <= ?2{serial_filter} \
                 ORDER BY id"
        );
        let last_id: i64 =
            self.conn
                .query_row("SELECT COALESCE(MAX(id), 0) FROM events", [], |r| r.get(0))?;

        // On a phone this read is two thirds of a recompute, and it is the CPU walking
        // rows, not the disk: 4 s for 5.66M rows on a Pixel 9a against 1.2 s on a laptop.
        // The ids are dense and the table is their B-tree, so the range splits cleanly
        // into slices that separate read-only connections walk at the same time (WAL lets
        // any number of readers in). Each slice comes back in id order and they are joined
        // in slice order, so the result is the single scan's, row for row.
        //
        // Rows are only ever appended, and every slice stops at `last_id`, so a sync that
        // commits while this runs changes nothing here. `redecode` rewriting JSON under a
        // running read is the one thing that could show two versions; it is a manual
        // desktop command.
        let workers = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(1)
            .min(PARALLEL_READERS);
        let path = self.conn.path().filter(|p| !p.is_empty());
        if let (Some(path), true) = (path, workers > 1 && last_id >= PARALLEL_MIN_ROWS) {
            let step = last_id / workers as i64 + 1;
            let slices: Vec<(i64, i64)> = (0..workers as i64)
                .map(|w| (w * step, ((w + 1) * step).min(last_id)))
                .collect();
            let parts: Vec<Result<Vec<DecodedRow>>> = std::thread::scope(|scope| {
                let handles: Vec<_> = slices
                    .iter()
                    .map(|&(after, upto)| {
                        let sql = &sql;
                        scope.spawn(move || -> Result<Vec<DecodedRow>> {
                            let conn = Connection::open_with_flags(
                                path,
                                rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
                            )?;
                            read_decoded(&conn, sql, after, upto, serial)
                        })
                    })
                    .collect();
                handles
                    .into_iter()
                    .map(|h| h.join().expect("event reader panicked"))
                    .collect()
            });
            // Any slice failing (a second connection refused, say) falls through to the
            // plain scan below rather than returning part of the history.
            if parts.iter().all(|p| p.is_ok()) {
                let mut rows = Vec::with_capacity(parts.iter().flatten().map(Vec::len).sum());
                for part in parts {
                    rows.append(&mut part?);
                }
                return Ok(rows);
            }
        }
        read_decoded(&self.conn, &sql, 0, last_id, serial)
    }

    /// Decoded events of the given tags whose ring time lies in `[ds_lo, ds_hi]` and which
    /// were captured in `[cu_lo, cu_hi]`, as `(ring_timestamp, tag, decoded_json,
    /// captured_unix)` in id order.
    ///
    /// For reading one night's worth of a heavy stream (the sleep stager's beats, motion and
    /// temperature) without loading the whole history's JSON. The UNIQUE index begins
    /// `(serial, tag, ring_timestamp)`, so each tag is a range scan. Ring time restarts at
    /// every reboot, so rows from another boot can share the ds range; the capture range is
    /// what tells them apart.
    pub fn decoded_events_in_window(
        &self,
        serial: &str,
        tags: &[u8],
        (ds_lo, ds_hi): (i64, i64),
        (cu_lo, cu_hi): (i64, i64),
    ) -> Result<Vec<DecodedRow>> {
        let mut stmt = self.conn.prepare_cached(
            "SELECT ring_timestamp, tag, decoded_json, captured_unix FROM events \
             WHERE serial = ?1 AND tag = ?2 AND ring_timestamp BETWEEN ?3 AND ?4 \
             AND captured_unix BETWEEN ?5 AND ?6 AND decoded_json IS NOT NULL ORDER BY id",
        )?;
        let mut out = Vec::new();
        for &tag in tags {
            let rows = stmt.query_map(params![serial, tag as i64, ds_lo, ds_hi, cu_lo, cu_hi], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)? as u8,
                    r.get::<_, String>(2)?,
                    r.get::<_, i64>(3)?,
                ))
            })?;
            for row in rows {
                out.push(row?);
            }
        }
        Ok(out)
    }

    /// The raw bodies of one tag's events in a window of one boot, `(ring ds, body)` in
    /// arrival order. For streams whose decoding carries state from one event to the next —
    /// the raw PPG (`0x81`) is deltas off the last absolute sample — so the per-event JSON,
    /// decoded statelessly, cannot rebuild the waveform.
    pub fn raw_events_in_window(
        &self,
        serial: &str,
        tag: u8,
        (ds_lo, ds_hi): (i64, i64),
        (cu_lo, cu_hi): (i64, i64),
    ) -> Result<Vec<(i64, Vec<u8>)>> {
        let mut stmt = self.conn.prepare_cached(
            "SELECT ring_timestamp, body FROM events \
             WHERE serial = ?1 AND tag = ?2 AND ring_timestamp BETWEEN ?3 AND ?4 \
             AND captured_unix BETWEEN ?5 AND ?6 ORDER BY id",
        )?;
        let rows = stmt.query_map(params![serial, tag as i64, ds_lo, ds_hi, cu_lo, cu_hi], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, Vec<u8>>(1)?))
        })?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// How many events [`Store::decoded_events_in_window`] would return, without reading
    /// them: a cheap way to tell whether a window has gained data since it was last read.
    pub fn count_events_in_window(
        &self,
        serial: &str,
        tags: &[u8],
        (ds_lo, ds_hi): (i64, i64),
        (cu_lo, cu_hi): (i64, i64),
    ) -> Result<i64> {
        let mut stmt = self.conn.prepare_cached(
            "SELECT COUNT(*) FROM events WHERE serial = ?1 AND tag = ?2 \
             AND ring_timestamp BETWEEN ?3 AND ?4 AND captured_unix BETWEEN ?5 AND ?6 \
             AND decoded_json IS NOT NULL",
        )?;
        let mut n = 0;
        for &tag in tags {
            n += stmt.query_row(params![serial, tag as i64, ds_lo, ds_hi, cu_lo, cu_hi], |r| {
                r.get::<_, i64>(0)
            })?;
        }
        Ok(n)
    }

    /// A night staged earlier, if the cache has one for this window and model:
    /// `(input event count when staged, stage codes as JSON)`.
    pub fn stage_cache_get(&self, key: &StageCacheKey) -> Result<Option<(i64, String)>> {
        Ok(self
            .conn
            .query_row(
                "SELECT inputs, stages FROM sleep_stage_cache WHERE serial = ?1 AND start_ds = ?2 \
                 AND end_ds = ?3 AND capture_lo = ?4 AND model = ?5",
                params![key.serial, key.start_ds, key.end_ds, key.capture_lo, key.model],
                |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)),
            )
            .optional()?)
    }

    /// Keep a staged night. Replaces an earlier entry for the same window and model.
    pub fn stage_cache_put(&self, key: &StageCacheKey, inputs: i64, stages_json: &str) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO sleep_stage_cache \
             (serial, start_ds, end_ds, capture_lo, model, inputs, stages, computed_unix) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                key.serial,
                key.start_ds,
                key.end_ds,
                key.capture_lo,
                key.model,
                inputs,
                stages_json,
                now_unix()
            ],
        )?;
        Ok(())
    }

    /// Run `f` inside one read transaction, so everything it reads is a single consistent
    /// snapshot even while a sync is writing (WAL gives readers their own view).
    pub fn snapshot<T>(&self, f: impl FnOnce(&Store) -> Result<T>) -> Result<T> {
        self.conn.execute_batch("BEGIN")?;
        let out = f(self);
        let _ = self.conn.execute_batch("COMMIT");
        out
    }

    /// Run `f` inside one write transaction: all of it lands, or none of it does.
    pub fn transaction<T>(&self, f: impl FnOnce(&Store) -> Result<T>) -> Result<T> {
        self.conn.execute_batch("BEGIN IMMEDIATE")?;
        match f(self) {
            Ok(v) => {
                self.conn.execute_batch("COMMIT")?;
                Ok(v)
            }
            Err(e) => {
                let _ = self.conn.execute_batch("ROLLBACK");
                Err(e)
            }
        }
    }

    /// How many events are stored, across every device.
    pub fn total_events(&self) -> Result<u64> {
        let n: i64 = self
            .conn
            .query_row("SELECT COUNT(*) FROM events", [], |r| r.get(0))?;
        Ok(n as u64)
    }

    /// Every stored event exactly as the ring delivered it, in sync order. The decoded
    /// JSON and the name are left out on purpose: both are derived from `tag` and `body`
    /// (see [`Store::redecode`]), so a copy that carries only these is complete.
    pub fn for_each_raw_event(&self, f: &mut dyn FnMut(RawEvent) -> Result<()>) -> Result<()> {
        let mut stmt = self.conn.prepare(
            "SELECT serial, tag, ring_timestamp, body, captured_unix FROM events ORDER BY id",
        )?;
        let mut rows = stmt.query([])?;
        while let Some(r) = rows.next()? {
            f(RawEvent {
                serial: r.get(0)?,
                tag: r.get::<_, i64>(1)? as u8,
                ring_timestamp: r.get(2)?,
                body: r.get(3)?,
                captured_unix: r.get(4)?,
            })?;
        }
        Ok(())
    }

    /// Insert an event from its raw form, decoding it with the current decoders and
    /// keeping its original capture time. Exact duplicates are ignored, so importing the
    /// same events twice — or into a database that already synced them — adds nothing.
    /// Returns true if a row was added.
    pub fn insert_raw_event(&self, ev: &RawEvent) -> Result<bool> {
        let decoded = oura_protocol::events::decode_event_body(ev.tag, &ev.body)
            .map(|v| serde_json::to_string(&v).unwrap_or_default());
        let mut stmt = self.conn.prepare_cached(
            "INSERT OR IGNORE INTO events
               (serial, tag, name, ring_timestamp, body, decoded_json, captured_unix)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        )?;
        let changed = stmt.execute(params![
            ev.serial,
            ev.tag as i64,
            oura_protocol::events::event_name(ev.tag),
            ev.ring_timestamp,
            ev.body,
            decoded,
            ev.captured_unix,
        ])?;
        Ok(changed > 0)
    }

    /// Every device row, as `(serial, hardware_id, firmware, api_version, mac, updated_unix)`.
    #[allow(clippy::type_complexity)]
    pub fn device_rows(
        &self,
    ) -> Result<
        Vec<(
            String,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
            i64,
        )>,
    > {
        let mut stmt = self.conn.prepare(
            "SELECT serial, hardware_id, firmware, api_version, mac, updated_unix FROM device",
        )?;
        let rows = stmt
            .query_map([], |r| {
                Ok((
                    r.get(0)?,
                    r.get(1)?,
                    r.get(2)?,
                    r.get(3)?,
                    r.get(4)?,
                    r.get(5)?,
                ))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Take a device row from elsewhere, keeping whichever copy was updated more recently.
    pub fn merge_device_row(
        &self,
        serial: &str,
        hardware_id: Option<&str>,
        firmware: Option<&str>,
        api_version: Option<&str>,
        mac: Option<&str>,
        updated_unix: i64,
    ) -> Result<()> {
        self.conn.execute(
            "INSERT INTO device (serial, hardware_id, firmware, api_version, mac, updated_unix)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(serial) DO UPDATE SET
               hardware_id=COALESCE(excluded.hardware_id, device.hardware_id),
               firmware=COALESCE(excluded.firmware, device.firmware),
               api_version=COALESCE(excluded.api_version, device.api_version),
               mac=COALESCE(excluded.mac, device.mac),
               updated_unix=excluded.updated_unix
             WHERE excluded.updated_unix > device.updated_unix",
            params![
                serial,
                hardware_id,
                firmware,
                api_version,
                mac,
                updated_unix
            ],
        )?;
        Ok(())
    }

    /// Every sync cursor, as `(serial, next_cursor, last_sync_unix)`.
    pub fn sync_rows(&self) -> Result<Vec<(String, i64, i64)>> {
        let mut stmt = self
            .conn
            .prepare("SELECT serial, next_cursor, last_sync_unix FROM sync_state")?;
        let rows = stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Take a sync cursor from elsewhere if it is from a later sync than the one held.
    ///
    /// The later sync is the one that knows where the ring currently is. This is only safe
    /// alongside that copy's events — the cursor says "everything before here is stored",
    /// which is true of the merged database once those events are in it.
    pub fn merge_sync_row(
        &self,
        serial: &str,
        next_cursor: i64,
        last_sync_unix: i64,
    ) -> Result<()> {
        self.conn.execute(
            "INSERT INTO sync_state (serial, next_cursor, last_sync_unix)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(serial) DO UPDATE SET
               next_cursor=excluded.next_cursor,
               last_sync_unix=excluded.last_sync_unix
             WHERE excluded.last_sync_unix > sync_state.last_sync_unix",
            params![serial, next_cursor, last_sync_unix],
        )?;
        Ok(())
    }

    /// Every scalar reading, as `(serial, kind, value, unit, captured_unix)`, oldest first.
    #[allow(clippy::type_complexity)]
    pub fn reading_rows(&self) -> Result<Vec<(String, String, f64, Option<String>, i64)>> {
        let mut stmt = self
            .conn
            .prepare("SELECT serial, kind, value, unit, captured_unix FROM readings ORDER BY id")?;
        let rows = stmt
            .query_map([], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Insert a reading with its original capture time, unless that exact reading is
    /// already there. `readings` has no uniqueness rule of its own, so the check is here.
    /// Returns true if a row was added.
    pub fn merge_reading(
        &self,
        serial: &str,
        kind: &str,
        value: f64,
        unit: Option<&str>,
        captured_unix: i64,
    ) -> Result<bool> {
        let changed = self.conn.execute(
            "INSERT INTO readings (serial, kind, value, unit, captured_unix)
             SELECT ?1, ?2, ?3, ?4, ?5
             WHERE NOT EXISTS (
               SELECT 1 FROM readings
               WHERE serial = ?1 AND kind = ?2 AND value = ?3 AND captured_unix = ?5)",
            params![serial, kind, value, unit, captured_unix],
        )?;
        Ok(changed > 0)
    }

    /// Distinct device serials that have stored events.
    pub fn device_serials(&self) -> Result<Vec<String>> {
        let mut stmt = self
            .conn
            .prepare("SELECT DISTINCT serial FROM events ORDER BY serial")?;
        let rows = stmt
            .query_map([], |r| r.get(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Count stored events grouped by event name (descending).
    pub fn event_counts(&self, serial: &str) -> Result<Vec<(String, i64)>> {
        let mut stmt = self.conn.prepare(
            "SELECT name, COUNT(*) FROM events WHERE serial = ?1 GROUP BY name ORDER BY 2 DESC",
        )?;
        let rows = stmt
            .query_map(params![serial], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_sliced_read_returns_what_one_scan_does() {
        let dir = std::env::temp_dir().join(format!("oura-store-slices-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let store = Store::open(&dir.join("oura.db")).unwrap();
        // Enough rows to cross the threshold, across two tags and two serials, with gaps
        // in the ids where duplicates were refused.
        store
            .transaction(|s| {
                for i in 0..(PARALLEL_MIN_ROWS + 5_000) {
                    s.insert_raw_event(&RawEvent {
                        serial: if i % 97 == 0 {
                            "S2".into()
                        } else {
                            "S1".into()
                        },
                        tag: if i % 3 == 0 { 0x46 } else { 0x47 },
                        ring_timestamp: i / 2,
                        body: vec![(i % 200) as u8, 1, (i % 7) as u8, 0, 0, 0, 0, 0],
                        captured_unix: 1_700_000_000 + i,
                    })?;
                }
                Ok(())
            })
            .unwrap();
        let sql = "SELECT ring_timestamp, tag, decoded_json, captured_unix FROM events \
                   WHERE decoded_json IS NOT NULL AND id > ?1 AND id <= ?2 ORDER BY id";
        let one_scan = read_decoded(&store.conn, sql, 0, i64::MAX, None).unwrap();
        assert!(one_scan.len() as i64 >= PARALLEL_MIN_ROWS);
        assert_eq!(store.decoded_events().unwrap(), one_scan);

        let s1: Vec<DecodedRow> = {
            let sql = "SELECT ring_timestamp, tag, decoded_json, captured_unix FROM events \
                       WHERE decoded_json IS NOT NULL AND id > ?1 AND id <= ?2 AND +serial = ?3 \
                       ORDER BY id";
            read_decoded(&store.conn, sql, 0, i64::MAX, Some("S1")).unwrap()
        };
        assert_eq!(store.decoded_events_for_serial("S1").unwrap(), s1);

        // Only the asked-for tags keep their JSON; every row is still there.
        let some = store.decoded_events_with_json_for(None, &[0x46]).unwrap();
        assert_eq!(some.len(), one_scan.len());
        for (got, full) in some.iter().zip(&one_scan) {
            assert_eq!((got.0, got.1, got.3), (full.0, full.1, full.3));
            assert_eq!(
                got.2,
                if full.1 == 0x46 {
                    full.2.clone()
                } else {
                    String::new()
                }
            );
        }
        drop(store);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn opening_drops_the_redundant_index_and_compacting_keeps_every_row() {
        let dir = std::env::temp_dir().join(format!("oura-store-compact-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("oura.db");
        {
            // A database as older versions left it.
            let store = Store::open(&path).unwrap();
            store
                .conn
                .execute_batch("CREATE INDEX idx_events_serial_tag ON events(serial, tag)")
                .unwrap();
            for i in 0..2000i64 {
                store
                    .insert_raw_event(&RawEvent {
                        serial: "S1".into(),
                        tag: 0x46,
                        ring_timestamp: i,
                        body: vec![(i % 251) as u8; 12],
                        captured_unix: 1_700_000_000 + i,
                    })
                    .unwrap();
            }
        }
        let store = Store::open(&path).unwrap();
        let index_left: i64 = store
            .conn
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'idx_events_serial_tag'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(index_left, 0);
        let (before, after) = store.compact().unwrap();
        assert!(after < before, "{after} should be smaller than {before}");
        assert_eq!(store.total_events().unwrap(), 2000);
        // Duplicates are still refused without the index that was dropped.
        let again = RawEvent {
            serial: "S1".into(),
            tag: 0x46,
            ring_timestamp: 7,
            body: vec![7u8; 12],
            captured_unix: 1,
        };
        store.insert_raw_event(&again).unwrap();
        assert_eq!(store.total_events().unwrap(), 2000);
        drop(store);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn events_dedup_and_cursor_roundtrip() {
        let store = Store::open_in_memory().unwrap();
        let ev = RingEvent {
            tag: 0x43,
            name: "debug_event",
            timestamp: 42,
            body: vec![1, 2, 3],
            decoded: None,
        };
        assert!(store.insert_event("S1", &ev).unwrap());
        assert!(!store.insert_event("S1", &ev).unwrap()); // duplicate ignored

        store.set_cursor("S1", 1234).unwrap();
        assert_eq!(store.cursor("S1").unwrap(), 1234);

        let counts = store.event_counts("S1").unwrap();
        assert_eq!(counts, vec![("debug_event".to_string(), 1)]);
    }

    #[test]
    fn latest_reading_returns_newest() {
        let store = Store::open_in_memory().unwrap();
        assert!(store
            .latest_reading("S1", "battery_percent")
            .unwrap()
            .is_none());
        store
            .insert_reading("S1", "battery_percent", 80.0, "%")
            .unwrap();
        store
            .insert_reading("S1", "battery_percent", 9.0, "%")
            .unwrap();
        // Even inserted within the same second, the last write (higher id) wins.
        let (v, _t) = store
            .latest_reading("S1", "battery_percent")
            .unwrap()
            .unwrap();
        assert_eq!(v, 9.0);
        // Scoped by device and kind.
        assert!(store
            .latest_reading("S2", "battery_percent")
            .unwrap()
            .is_none());
        assert!(store.latest_reading("S1", "spo2").unwrap().is_none());
    }
}
