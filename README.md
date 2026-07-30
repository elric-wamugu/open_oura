# open_oura

Reverse-engineering the Oura ring BLE protocol, plus an independent, **cloud-free**
client that reads your data straight from the ring.

Tested live against a Ring 3 Horizon and a Ring 5 (pairing, auth, and event sync
confirmed on both). Designed for Ring 3/4/5, which share the same GATT layout,
packet framing, and authentication flow.

## Fork: dashboard improvements (`dashboard-improvements` branch)

This fork extends the web dashboard so more of it works **without Oura's
proprietary PyTorch models** (which aren't bundled), plus performance, UX, and
correctness work. Validated on a Ring 3 Heritage (`BLB_03`).

### Changes (new capabilities)

- **Native sleep staging** — when no SleepNet output is available, the hypnogram
  is assembled from the ring's own `sleep_phase_data` events (logged as a burst
  a couple of hours after wake, matched to each night by wake time). Unblocks the
  sleep-stages view, sleep efficiency, the overnight polysomnograph, and sleep
  architecture — no model needed.
- **Native blood oxygen** — rings that emit `spo2_event` (summarized SpO₂ %)
  rather than the `spo2_r_pi_event` R-ratio now render Blood Oxygen from those
  percentages directly.
- **Streaming sync progress** — `POST /api/sync` streams Server-Sent Events; the
  header shows a live determinate progress bar ("N events · X KB left on ring")
  instead of a spinner.
- **Private remote access** — `OURA_DASH_ALLOWED_HOSTS` opts extra `Host` values
  past the loopback guard, for reaching the dashboard over **Tailscale** (default
  stays loopback-only; never expose it publicly — no auth, health data + ring key).
- **Material Design 3 theme** with a persisted light/dark toggle.
- **Live battery** — captured at each sync and shown with its age (`98% · 2h`),
  instead of a stale value scraped from old `debug_data` events.

### Improvements

- **Sync speed** — SQLite WAL + `synchronous=NORMAL`: fsync per checkpoint, not per
  INSERT (~20× faster on a large drain; the dashboard can also read while syncing).
- **Timestamp accuracy** — the activity/sleep timeline is anchored by mapping the
  ring's counter (`ds`) back from each boot-epoch's newest event at ~10 ds/sec.
  Sleep and activity now land at the right hours (e.g. sleep `23:03–09:09`, not a
  compressed afternoon window). See the note on the ring having no real clock below.
- **Activity ridge** now uses a 12-hour AM/PM hour axis (`12AM · 3AM … 12AM`,
  thinned to every 6 h on narrow screens) instead of a raw `0–24` scale.
- **Honest gating** — Cardiovascular age says "needs Oura's CVA model, which isn't
  bundled" instead of the misleading "enable cva_ppg"; the SpO₂ subtitle no longer
  claims R-ratio calibration when the data is a direct percentage.

### Still needs the proprietary model

**Cardiovascular / vascular age (CVA)** is a learned neural net on raw PPG — there
is no formula substitute, so it stays gated until you supply Oura's model + a torch
venv. The raw `cva_raw_ppg_data` is captured regardless.

> **Note — the ring has no reliable clock.** It emits no `time_sync`/`rtc_beacon`
> anchor and its `ds` counter pauses when the ring is off/dead. Timestamps are
> accurate for continuously-worn stretches; history spanning a power loss or a day
> off the wrist can still compress. Wear it continuously and sync often for the best
> results. (A per-sync-batch `captured_unix` anchor was tried and reverted — it
> compressed every buffered overnight sleep into the morning sync window.)

## What you can recover

Straight from the ring, with no Oura account: device info, battery, live heart rate
(IBI to BPM), latest HR / SpO2, and the full history-event stream. That stream
carries raw PPG/IBI/temperature/motion/SpO2 samples plus the ring's **on-device**
sleep stages, activity MET levels, and HRV.

The ring itself does **not** emit the 0-100 Readiness / Sleep / Activity / Stress
scores. But those are **not** computed in
Oura's cloud either: they're computed **on the phone** by the native `ecore`
engine and a set of on-device PyTorch models (the same `.pt` we run here), then
uploaded; the cloud only stores and syncs them back. So they're reproducible
offline. The one genuine cloud-only step is **workout auto-classification**
(`POST /api/activity-tagging/v2`). See
[`docs/data-recovery-map.md`](docs/data-recovery-map.md) and
[`docs/algorithms/README.md`](docs/algorithms/README.md).

## Repository map

- **`crates/`**: the Rust client, split by concern (`oura-protocol` decode,
  `oura-link` fetch, `oura-analysis` metrics, `oura-store` SQLite, `oura-cli`).
  Start here: [`crates/README.md`](crates/README.md) and
  [`docs/architecture.md`](docs/architecture.md).
- **`tools/`**: Python research bench for protocol exploration. `oura_protocol.py`
  (full command matrix, auth, danger-gated ops, JSONL capture) and
  `oura_realtime_listener.py`.
- **`docs/`**: protocol and reverse-engineering reference (index below).
- **`reverse/`, `captures/`**: local-only, gitignored. The decompiled app and raw
  captures (which may contain serials, MACs, and auth keys).

## Quick start (Rust client)

```bash
cargo build --release
./target/release/oura scan
./target/release/oura --key-file key.hex info
```

See [`crates/README.md`](crates/README.md) for all commands (`scan`, `pair`,
`info`, `sync`, `latest`, `live-hr`, `accel`, `viz`, `game`, `features`, `rdata`,
`events`, `redecode`, `sleep-analyze`, `sessions`, `dashboard`) and the auth-key
details. `oura dashboard` serves a local web health dashboard (sleep, cardio,
SpO2, activity, device) at `http://127.0.0.1:8090` (see [`dashboard/`](dashboard/));
`oura viz` opens a real-time 3D motion visualizer; `oura game` is a tilt-controlled
asteroid game driven by the ring.

## Research bench (Python)

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python tools/oura_protocol.py --list
```

State-changing and destructive commands are hidden behind `--include-state` and
`--include-danger`. On macOS, grant Bluetooth permission to the terminal.

## Documentation

- [`docs/horizon-ring3-protocol-cheatsheet.md`](docs/horizon-ring3-protocol-cheatsheet.md):
  the protocol command reference (requests, responses, auth, features), Ring 3.
- [`docs/android-app-reversing.md`](docs/android-app-reversing.md): app internals,
  BLE constants, the auth operations, key generation, and nonce encryption.
- [`docs/data-recovery-map.md`](docs/data-recovery-map.md): what the ring emits vs
  what only the cloud computes.
- [`docs/sync-orchestration.md`](docs/sync-orchestration.md): when and how the app
  pulls each data channel, and the minimal client sync recipe.
- [`docs/ring-5-observations.md`](docs/ring-5-observations.md): Ring 5 BLE surface
  and first-contact findings.
- [`docs/ring-features.md`](docs/ring-features.md): the feature capabilities, runtime
  modes, what's on by default, and which event each enabled feature produces (incl.
  what `experimental` does — and doesn't).
- [`docs/model-runners.md`](docs/model-runners.md): running Oura's decrypted on-device
  models on your synced data — what runs (activity, sleep, CVA, SpO2) vs what's blocked.
- [`docs/cva-cardiovascular-age.md`](docs/cva-cardiovascular-age.md): decoding the raw
  PPG (`cva_raw_ppg_data` 0x81) and running the cardiovascular-age model.
- [`docs/spo2-calibration.md`](docs/spo2-calibration.md): turning the SpO2 R-ratio into
  a percentage with Oura's own calibration.
- [`docs/firmware-update.md`](docs/firmware-update.md): the DFU/OTA opcodes, the
  working cloud download pipeline + codename map, per-device encryption status, and
  why the firmware key is unreachable (device-resident; not brute-forceable).
- [`docs/security-observations.md`](docs/security-observations.md): findings-only
  notes on the model/firmware encryption (the "what", not the "how" — no keys,
  endpoints, or procedures).
- [`docs/architecture.md`](docs/architecture.md): the fetch/interpret/apply crate
  layering and where to add things.
- [`docs/algorithms/README.md`](docs/algorithms/README.md): the on-device ecore
  metric algorithms (scores, sleep, baselines) and their porting status.
- [`docs/native-decoder.md`](docs/native-decoder.md): porting event-body decoders
  from the native `libringeventparser.so` (how the byte layouts were recovered with
  Ghidra).

## Safety and secrets

- Prefer passive, read-only requests. reset / DFU / factory-reset / flight-mode are
  gated behind explicit flags; do not send them during normal use.
- App-gated operations need the ring's 16-byte auth key (re-sent each connection).
  Captures and keys are gitignored. Never commit a key.

## Prior art

ringverse Oura Ring 4 BLE notes:
<https://github.com/ringverse/protocol/blob/main/oura/BLE.md>
