# open_oura

Reverse-engineering the Oura ring BLE protocol, plus an independent, **cloud-free**
client that reads your data straight from the ring.

Tested live against a Ring 3 Horizon and a Ring 5 (pairing, auth, and event sync
confirmed on both). Designed for Ring 3/4/5, which share the same GATT layout,
packet framing, and authentication flow.

## Fork: two clients, one Rust brain

Two branches, both forks of `Th0rgal/open_oura` and both synced with upstream `main`:

| Branch | What it holds |
| --- | --- |
| `dashboard-improvements` | The web dashboard + everything in `crates/` (the shared brain) |
| `android-client` | Branches off the above and adds `apps/android/` — the native Compose client |

Every computed metric lives once in **`crates/oura-summary`** and both clients render the
same JSON. Validated end to end on a Ring 3 Heritage (`BLB_03`) and a Pixel 5. See
`docs/clients-web-and-ios.md` for the feature ↔ feature map and
`docs/android-client-plan.md` for the Android phases.

### Changes (new capabilities)

- **Native sleep staging** — with no SleepNet output the hypnogram is assembled from the
  ring's own `sleep_phase_data`, unblocking stages, efficiency, the polysomnograph and
  sleep architecture with no model.
- **Native blood oxygen** — rings emitting `spo2_event` (direct %) rather than the
  `spo2_r_pi_event` R-ratio render Blood Oxygen from those percentages.
- **Native Android client** (`apps/android/`) — Jetpack Compose on the same Rust core
  through UniFFI Kotlin. Day card, sleep and activity reports, day browser, profile editor,
  battery panel, effort sessions. No Rust changes were needed: `oura-core` already declared
  `cdylib` and `oura-link` already delegated BLE to the platform.
- **Home-screen widgets** (Android) — three Glance widgets: Vitals, Last night, Today. They
  read a ~600-byte projection written after each recompute, never the core.
- **Per-bucket steps + movement scrubber** — `activity_steps` gives 96 × 15-min step counts
  from the same per-minute rate as the daily total, so a hover (web) or drag (Android)
  reports "14:30–14:45 · 525 steps · 1.32 MET" and cannot contradict the day figure.
- **Peak sustained heart rate** — the largest 30-second rolling median of the quality-gated
  beat series, reported against a Tanaka age-predicted maximum. Not a raw daily max, which
  would only ever report the worst PPG artefact.
- **Battery log + discharge runs** — the ring's own `battery_level_changed` events (percent
  *and* voltage) charted across charge cycles, with per-run %/h and a normalised "hours from
  full". Far richer than the handful of sync-time samples.
- **Effort sessions** — `ehr_trace_event` fires while the ring believes you are exercising.
  Its payload is undecoded, but *when* it fires is the signal, so clustering it yields
  sessions with HR and intensity. This fills a list that was otherwise permanently empty,
  since labelled sessions need Oura's AAD model.
- **Streaming sync progress**, **private remote access** via `OURA_DASH_ALLOWED_HOSTS`,
  **Material Design 3** with a persisted light/dark toggle, and **live battery** captured at
  sync with its age.

### Improvements

- **Timestamps stop moving.** The old model pinned each boot epoch to its newest event and
  extrapolated back at 10 ds/sec, so a sync that stopped before draining the buffer slid
  every earlier timestamp forward — the same night could read `10:02–20:56` in one view and
  `23:57–09:47` in the next. `build_summary` now fits an offset per epoch
  (`fit_ds_offsets`): each sync bounds the offset from above, and the fit is the running
  minimum from the newest ds backwards. Partial drains self-correct, and today's sync cannot
  move last week's night. Verified: 0 of 15 shared nights move between an early and a
  complete view. Unit-tested.
- **A bedtime period under 90 minutes is not a night.** The ring logs one for any stillness,
  so a quiet evening produced 10–40 minute "nights" — 20 of 33 were junk, and since
  `nights[0]` drives the sleep tile, the HRV/RHR baselines and sleep debt, a fragment
  landing after the real sleep corrupted all of them. Excluded periods are published as
  `device.short_periods_excluded` rather than quietly dropped.
- **Fitness leads with resting HR.** VO₂max is a Jackson regression on age, sex and weight —
  no ring data reaches it, so it cannot respond to training. It is now a labelled
  demographic baseline beneath the resting-HR trend, which is measured and does move.
  Vascular age folds inside the Cardiovascular panel.
- **One battery figure.** The top bar and the battery panel disagreed (69% vs 74%) because
  percent came from the sync-time read and voltage from a debug event. The brain now takes
  whichever source carries the later timestamp, and both travel together.
- **Sync speed** — SQLite WAL + `synchronous=NORMAL`: fsync per checkpoint, not per INSERT,
  and the dashboard can read while a sync writes.
- **Collapsible reference panels** (Battery, Device & data health) with the choice
  remembered, and a day browser that reads identically on both clients.
- **Honest gating throughout** — CVA says "needs Oura's CVA model, which isn't bundled";
  per-bucket steps say they are estimated from movement intensity; effort sessions are never
  dressed up as labelled workouts.

### Corrections worth knowing

Two things this fork previously asserted turned out to be wrong. The code and docs now say so:

- **The `ds` counter does not pause when the ring is off.** Measured over a month of real
  data it runs at **9.96 ds/sec** and tracks wall clock. The apparent pausing was partial
  drains — sync-to-sync rates alternate between ~3 and ~500 ds/sec, which is a sync that
  stopped short followed by one that caught up.
- **Battery life did not degrade.** Full-charge runs fell from ~165 h to ~77 h, but nothing
  was switched on: every sensor stream has emitted since day one. The ring is simply working
  3.5× harder (12,530 → 44,413 events/day), and the flattering early runs span days it was
  barely worn.

### Still needs the proprietary model

**Cardiovascular / vascular age (CVA)** is a learned net on raw PPG with no formula
substitute, so it stays gated until you supply Oura's model and a torch venv. Note that
`cva_raw_ppg_data` is ~5.7% of synced bytes and yields **no metric at all** without it — the
cheapest feature to switch off if you want the battery back.

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
[`docs/data-recovery-map.md`](docs/data-recovery-map.md),
[`docs/algorithms/README.md`](docs/algorithms/README.md), and
[`docs/model-runners.md`](docs/model-runners.md) for what runs.

> **Those PyTorch models are Oura's proprietary IP and are NOT included in this
> repo** (gitignored under `notes/models/`). The runners reference them by path; you
> decrypt and supply your own locally. Nothing model-related is committed or pushed.

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
