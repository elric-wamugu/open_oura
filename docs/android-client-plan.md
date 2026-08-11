# Android client — phased implementation plan

Android becomes the **primary** client: native Jetpack Compose + Glance home-screen widgets,
reusing the Rust core unchanged. iOS drops to **maintenance** (no new features; its SwiftUI
code becomes a porting reference, not a sync obligation). The web dashboard stays as the
desktop client.

Each step is tagged **[you]** (needs a human: installs, GUI, hardware, accounts) or
**[agent]** (can be written without an Android SDK present).

---

## Target device

**Google Pixel 5, Android 14 (API 34)** — `arm64-v8a`, Snapdragon 765G, 8 GB RAM. Android 14
is this device's final OS version, so API 34 is the ceiling and there is no Android 15
behaviour to design around.

Consequences that run through the whole plan:

- **ABI is `arm64-v8a` only.** Same as the Apple Silicon emulator, so one Rust target
  (`aarch64-linux-android`) covers device and emulator both.
- **`compileSdk 35`, `targetSdk 34`, `minSdk 26`.** Target the device's own API level —
  there is no way to test Android 15 behaviour changes on hardware you own.
- **API 34 makes foreground-service types mandatory.** A BLE sync service *must* declare
  `android:foregroundServiceType="connectedDevice"` and hold
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, or Android 14 throws at `startForeground()`.
  This is a requirement here, not a nicety.
- **`build_summary` measured at 8.0 s on the Pixel 5** over 847k events (vs 3.0 s on the
  M2 — 2.7× slower, better than the 3–5× I first assumed). Cold start from the cached
  snapshot is **1.29 s**, so the cache is worth ~6× and is load-bearing rather than an
  optimisation. Any manual refresh needs a visible progress state.
- **Good news for Phase 4:** Pixels run the AOSP reference Bluetooth stack, so you avoid the
  worst vendor-specific GATT quirks. This is the easiest Android hardware to get BLE working
  on.

## Architectural invariants

These are the rules that keep a third client from rotting the way the two-client split
already threatened to:

1. **All computation lives in Rust (`oura-summary`).** Kotlin only renders. Every metric
   added must go into the shared brain first — never into a client.
2. **Do not port `Reports.swift`'s `Sleep.metrics` / `Sleep.smooth` / `Sleep.autonomic`.**
   That Swift duplication exists because `build_summary` used to return empty
   `stages_full`/`metrics` under `NoModelRunner`. Since the native hypnogram fallback
   (`stages_from_phase_data`, lib.rs ~932) Rust computes them itself — confirmed by the web
   dashboard, which runs entirely model-free and still gets `stages_full`, `metrics` and
   `autonomic` populated. Android consumes them directly.
3. **Widgets never compute.** `build_summary` is ~3.0 s over 846k events on an M-series Mac
   (**10–20 s on the Pixel 5**, growing with history). Widgets read a small cached
   projection only.
4. **The ring auth key never touches a plain file.** Keystore-backed storage only.

## What is reused vs new

| Layer | Status |
|---|---|
| Rust core, decode, storage, `build_summary` | **Reused unchanged** |
| UniFFI surface (`oura-core`, 280 lines) | **Reused unchanged** — Kotlin is a first-class UniFFI 0.28 target |
| `Models.swift` (121 lines) | Port ~1:1 to kotlinx.serialization data classes |
| `Reports.swift` views, `OuraApp.swift`, `Components/Theme` (~1,400 lines of SwiftUI) | Port to Compose — declarative→declarative, near mechanical |
| `BLETransport.swift` (424 lines) + `RingSync.swift` (315) | **Reference only** — rewrite against Android GATT |
| `SleepStaging/CvaModel/ActivityModel.swift` (278 lines) | **Skip entirely** — no models; the native Rust paths cover sleep + SpO2 |

`oura-core` already declares `crate-type = ["lib", "staticlib", "cdylib"]`; the `cdylib` is
what Android needs. No Cargo change is required to start.

---

## Phase 0 — toolchain and a proving loop

**Goal:** call `core_version()` from a Kotlin app on a device. Nothing else.
**Exit criteria:** a stub Activity prints the Rust crate version.

Nothing is installed today: no `ANDROID_HOME`, no NDK, no `adb`, no Android Studio, no
`cargo-ndk`, no android Rust targets.

1. **[you]** Install Android Studio — on Apple Silicon take the **Mac (64-bit, ARM)** build,
   not the Intel one. The NDK is **not** bundled: after install go to *SDK Manager → SDK
   Tools*, tick **Show Package Details** (otherwise you only get the default, often older,
   NDK), then install a recent **NDK (Side by side)** — 27.x or newer is a good default —
   and **CMake**. (16 KB page alignment, which r27 added, is an Android 15 / Play Store
   concern and does **not** apply to a side-loaded app on Android 14; a recent NDK is
   simply future-proofing, not a hard floor.)
   Get the installed version for `ANDROID_NDK_HOME` with `ls ~/Library/Android/sdk/ndk`.
2. **[you]** Add to `~/.zshrc` (this shell does not read `~/.profile`):
   ```
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"
   export PATH="$PATH:$ANDROID_HOME/platform-tools:$HOME/.cargo/bin"
   ```
3. **[you]** Install the Rust side:
   ```bash
   cargo install cargo-ndk && rustup target add aarch64-linux-android
   ```
   `aarch64` covers **both** targets on an Apple Silicon Mac: every real phone, and the
   emulator, which runs arm64-v8a system images natively here. Add
   `x86_64-linux-android` only if you ever need an x86 emulator or a Chromebook. Skip
   32-bit `armeabi-v7a` — it is long dead on current hardware.
4. **[you]** Create the Android project in Android Studio: *Empty Activity (Compose)*, package
   `org.openoura.android`, location `apps/android`, **minSdk 26**, Kotlin DSL.
   Do this in the GUI — the generated Gradle wrapper and version catalog are fiddly to
   hand-write and Studio keeps them current. Set `compileSdk 35` / `targetSdk 34` to match
   the Pixel 5's Android 14. An emulator is optional given you have the device — if you do
   create one, pick an **arm64-v8a** system image, since x86_64 images run under emulation
   on Apple Silicon and are painfully slow.
5. **[agent] — DONE.** `apps/android/build-aar.sh` is written and verified end to end against
   NDK 30.0.15729638: it cross-compiles `oura-core` for `arm64-v8a` (2.9 MB `.so`,
   `rusqlite`'s bundled SQLite included, ~70 s cold) and emits 1,974 lines of Kotlin
   bindings byte-identical to those generated from the host dylib. It does:
   ```bash
   cargo ndk -t arm64-v8a -o apps/android/app/src/main/jniLibs \
     build --release -p oura-core
   cargo run --bin uniffi-bindgen -- generate \
     --library target/aarch64-linux-android/release/liboura_core.so \
     --language kotlin --out-dir apps/android/app/src/main/java
   ```
   (The `uniffi-bindgen` bin already exists in `oura-core` — the same one iOS uses, just
   `--language kotlin`.)
6. **[agent]** Add the JNA dependency the generated Kotlin bindings require:
   `implementation("net.java.dev.jna:jna:5.14.0@aar")` — the `@aar` classifier matters,
   the plain JAR will not load on Android. Also add `apps/android/app/src/main/java` as a
   source dir if Studio does not pick it up, and consider a Gradle `preBuild` hook that
   runs `build-aar.sh` so the bindings can never go stale against the Rust.

   The Kotlin surface this produces, ready to call: `coreVersion()`, `summaryJson(dbPath,
   tzOffset)`, `quickSummaryJson(dbPath)`, `rmssd(...)`, the `RingSession` class with
   `suspend fun sync(dbPath, keyHex, progress): SyncReport` and `pushFrame(...)`, plus the
   `BleWriter` and `SyncProgressListener` interfaces for Kotlin to implement.
7. **[you]** Prepare the Pixel 5: *Settings → About phone → tap **Build number** seven times*
   to unlock Developer options, then *Settings → System → Developer options → **USB
   debugging** on*. Plug it into the Mac and accept the "Allow USB debugging?" fingerprint
   prompt. Verify with `adb devices` — it must say `device`, not `unauthorized`.
8. **[you]** Run `apps/android/build-aar.sh`, then build and run the stub **on the Pixel**.
   Prefer the real device over the emulator throughout: it is the actual target, it is the
   only way to test BLE later, and it sidesteps emulator/host-arch mismatches entirely.
9. **[you]** Confirm the printed version matches `core_version()`.

**Watch for:** `rusqlite` is `bundled`, so it compiles SQLite from source with the NDK
toolchain. If it fails, it is almost always a missing `CC`/`AR` for the target — cargo-ndk
sets these, so run the build *through* cargo-ndk, never bare `cargo build --target`.
(Verified working on NDK 30 — no patching needed.)

**The trap that cost real time, now handled in the script:** the workspace release profile
sets `strip = true` (Cargo.toml ~23). Stripping removes `.symtab`, and UniFFI's library-mode
bindgen reads its `UNIFFI_META_*` entries from `.symtab` — `.dynsym` survives stripping but
is not enough. So `uniffi-bindgen generate --library <stripped .so>` **exits 0 and writes
nothing at all**: a silent no-op that looks exactly like success until Gradle fails to
resolve the bindings. `build-aar.sh` sets `CARGO_PROFILE_RELEASE_STRIP=false` for its own
build and hard-fails if the `.kt` comes out empty. This is very likely why the iOS side
ships *pre-generated, checked-in* bindings (`apps/ios/generated/oura_core.swift`, dated
July 18) rather than generating them during the build.

---

## Phase 1 — data layer and first screen (no BLE) — **DONE**

**Goal:** render real data from a side-loaded database.
**Exit criteria:** the vitals tiles show your real HRV/RHR/efficiency/SpO2 on device. ✅

Verified on the Pixel against the same `oura.db` the web dashboard reads, and the two agree
exactly: HRV 37 ms / baseline 41.3, resting HR 61 / baseline 64.6, efficiency 66%, SpO₂ 97%,
17 nights, 23 short periods excluded, 25.8 days, identical digest string.

Two notes for whoever picks this up:

- **Scoped storage.** An app cannot read `/sdcard/Download` on Android 11+, and
  `READ_EXTERNAL_STORAGE` no longer grants it. Import goes through the Storage Access
  Framework (`ActivityResultContracts.OpenDocument`), so the user picks the file and the
  app needs no storage permission at all. For development, bypass it entirely:
  ```
  adb push oura.db /data/local/tmp/oura.db
  adb shell run-as org.openoura.android cp /data/local/tmp/oura.db files/oura.db
  ```
- **Nothing about sleep metrics was ported to Kotlin.** `NightRow.metrics`, `stagesFull`
  and `autonomic` decode straight from what Rust computed, exactly as invariant 2 requires.

1. **[you]** Push a copy of the database to the Pixel:
   ```bash
   adb push oura.db /sdcard/Download/oura.db
   ```
   Then in-app (or via `adb shell run-as`) copy it into `context.filesDir`. The Rust core
   takes a plain filesystem path, so app-private storage is the target — mirroring iOS,
   which keeps it in Application Support. The file is ~148 MB, so the push takes a moment;
   remember the `-wal`/`-shm` sidecars are not needed (a clean copy is self-contained).
2. **[agent]** Kotlin data models: port `Models.swift` to kotlinx.serialization data classes
   (`Summary`, `Device`, `Vitals`, `Trend`, `NightRow`, `DailyStat`, `Fitness`, `Cardio`).
   Use `ignoreUnknownKeys = true` so the brain can add fields without breaking the client.
3. **[agent]** `SummaryRepository`:
   - `refresh()` — calls `summary_json(dbPath, tzOffset)` on `Dispatchers.Default`, writes
     the raw string to `filesDir/summary.json`, parses, emits.
   - `observe()` — a `StateFlow<Summary?>` seeded from the cached file so cold start is
     instant.
   - A `WidgetSnapshot` projection (a dozen fields) persisted separately for Glance.
   The full payload is **136 KB** (essentially all `nights`), which parses in milliseconds —
   a plain JSON file is sufficient; Proto DataStore would be over-engineering.
4. **[agent]** Compose theme from the web client's Material 3 tokens in
   `dashboard/web/styles.css` — the palette, the teal accent, and the light/dark pairs
   transfer directly. Enable dynamic color (Material You) as an option, not the default,
   so the brand palette survives.
5. **[agent]** The vitals tile row: HRV, resting HR, sleep efficiency, blood oxygen —
   matching the web's `renderTiles` including the comparison bars.
6. **[you]** Run and confirm the numbers match the web dashboard exactly.

**Note:** this phase deliberately has no Bluetooth. It proves the whole render path against
real data while the risky part is still ahead.

---

## ⚠ The profile is NOT in the database

`oura-summary::profile_path` reads **`profile.json` sitting next to the .db**, and falls
back to a generic 30-year-old / 75 kg / 1.78 m default when it is missing. Copying only
`oura.db` onto a device therefore silently changes every demographic-derived figure:
Jackson VO₂max, the Tanaka predicted HR max, Schofield BMR and so active/total kcal. Sleep,
HRV, RHR, SpO₂ and step counts are unaffected, which is what makes it easy to miss —
the app looked right and was quietly wrong (187 bpm predicted max instead of 184;
Aug 3 energy 253/1986 kcal instead of 222/1852).

Android ships a profile editor (`ProfileStore` + `ProfileScreen`, reachable from the top
bar) that writes the same `profile.json` beside the database. Anything that provisions a
database onto a device must provision the profile with it.

## Phase 2 — the full Compose UI port

**Goal:** feature parity with the web dashboard's main page.
**Exit criteria:** day card, sleep report, activity report all render from the snapshot.

1. **[agent]** Day card (`dayCard` / `TodayCard`): sleep summary line, hypnogram strip,
   stage percentages, activity line, movement ridge.
2. **[agent]** Sleep report: polysomnograph with the hypnogram plus aligned signal lanes,
   clinical metrics grid, autonomic-by-stage grid, interpretation text. The SwiftUI
   `Polysomnograph` is the reference; its drag scrubber maps onto Compose
   `pointerInput { detectDragGestures }` almost directly.
3. **[agent]** Activity report: stat strip, the movement chart **with the scrubber**
   (`activity_steps` per-bucket steps + MET readout), intensity metrics, peak HR card and
   its verdict note. Keep `peakHrVerdict`'s 95%/85% thresholds identical to `app.js` and
   `Reports.swift`.
4. **[agent]** Cardiovascular panel in its reframed form: resting HR leads with the trend
   sparkline; VO₂max demoted to a labelled demographic baseline.
5. **[agent]** Vascular age panel with the honest CVA-not-bundled message.
6. **[agent]** Day browser → per-day detail.
7. **[you]** Compare each screen against the web dashboard side by side.

**Charts:** write these as Compose `Canvas` composables, but factor each one so the drawing
logic can also render into a `Bitmap` — Phase 3 needs exactly that, and retrofitting is
painful.

---

## Phase 3 — Glance home-screen widgets — **BUILT**

**Goal:** widgets on the home screen, updating after each sync.
**Exit criteria:** three widgets, correct data, no jank, no battery complaints.

1. **[agent]** Add `androidx.glance:glance-appwidget`.
2. **[agent]** Three widgets, all reading `WidgetSnapshot` only:
   - **Small** — resting HR + HRV vs baseline.
   - **Medium** — last night: efficiency, stage bar, SpO₂.
   - **Medium** — today: steps, peak HR.
3. **[agent] — CONFIRMED, and sharper than expected.** App Widgets are `RemoteViews`: no
   Canvas, so anything proportional must be drawn into a `Bitmap` and passed as an `Image`.
   The binding constraint turned out not to be Canvas but **weights**: Glance exposes only
   `defaultWeight()` (equal shares, no numeric weight), so a four-segment stage bar built
   from boxes renders four *equal* blocks whatever the percentages are — wrong in a way that
   looks deliberate. `stageBarBitmap` draws it instead.
4. **[agent]** Update triggers — explicit, never `updatePeriodMillis` (30-minute floor and
   unreliable): after a sync completes, from a periodic `WorkManager` job, and on
   `ACTION_APPWIDGET_UPDATE`.
5. **[you]** Add each widget to your home screen — placement cannot be driven over adb.
6. **[you]** Check *Settings → Battery → App battery usage* after ~24 h.

**Data path:** widgets read `widget.json` (~600 bytes) written by `SummaryRepository` after
each successful recompute, never the 207 KB summary and never the Rust core — a widget
update can fire with the app closed and must be cheap. `WidgetUpdates.refreshAll` repaints
on refresh; a six-hourly `WorkManager` job is the backstop. `updatePeriodMillis` is 0 by
design: 30-minute floor, coalesced, and impossible to trigger on demand.

---

## Phase 4 — BLE sync

**Goal:** sync directly from the ring. This is the risky long pole.
**Exit criteria:** a full incremental sync from your Ring 3 with progress and resume.

The Rust contract is already fixed and tiny: implement `BleWriter.write(ByteArray)`, feed
inbound notifications to `RingSession.push_frame(ByteArray)`, then `await sync(...)`.

Ring GATT identifiers (from `BLETransport.swift`):

| Role | UUID |
|---|---|
| Service | `98ED0001-A541-11E4-B6A0-0002A5D5C51B` |
| Write | `98ED0002-…` |
| Notify (merge all) | `98ED0003/0004/0005/0006-…` |

1. **[you]** Add `BLUETOOTH_SCAN` (with `neverForLocation`), `BLUETOOTH_CONNECT`, and
   `FOREGROUND_SERVICE_CONNECTED_DEVICE` to the manifest; wire the runtime permission
   request. Test on a real device — emulators have no Bluetooth.
2. **[agent] — DONE and verified on the Pixel 5.** `ble/BleTransport.kt` + `ble/RingProbe.kt`.
   Proof: `Oura Ring Gen3 · -72 dBm · MTU 203 · 1 notify · the ring replied`, the frame being
   `09 12 02000003040301000105000c <mac bytes>` → firmware 3.4.3, MAC `<ring MAC>`.

   **The one that cost the most: the ring requires a BLE bond.** Without it the link
   *establishes* and the ring drops it ~110 ms later, which the stack surfaces as a bare
   `GATT status 133` — indistinguishable from a dozen unrelated causes, and it sends you
   chasing range, contention and stack races instead. The tell is not in logcat but in
   `adb shell dumpsys bluetooth_manager`, under `shim::legacy::acl`:
   `disconnect_reason: REMOTE_DEVICE_TERMINATED_CONNECTION_POWER_OFF` with a ~100 ms
   lifetime. CoreBluetooth bonds implicitly, which is why `sync-orchestration.md` lists
   "CONNECT (BLE connect + bond)" as one step and the desktop client never had to care.
   `createBond()` + waiting for `BOND_BONDED` fixes it outright. **Reach for `dumpsys` early
   on any 133** — the GATT status is nearly information-free by comparison.

   The rest, as expected:
   - Scan **unfiltered** and match in the callback — the ring's name arrives late.
   - Notifications need **both** `setCharacteristicNotification(...)` *and* an explicit CCCD
     descriptor write (`00002902-…`). iOS does this implicitly; Android does not.
   - `requestMtu(517)` before discovery; this ring negotiates down to **203**.
   - Merge every notify characteristic — but note the **Gen3 Heritage exposes only one**
     (`…0003`). The four-characteristic case is Ring 5. Filter by property, not a UUID list.
   - Do every GATT operation one at a time — Android silently drops concurrent ops.
   - Not the cause here, but worth keeping: a settle delay after `stopScan`, retries, and
     `autoConnect=true` as a last attempt. RSSI turned out to be a red herring (−87 and −72
     behaved identically once bonded).
3. **[agent] — DONE.** `ble/RingSyncService` (type `connectedDevice`) + `ble/RingSync.kt`.
   Verified against the real ring: `sync done: 10480 events, 10480 new, cursor 28366777`,
   cursor 28,250,624 → 28,366,777, ~70 s drain, summary rebuilt in 5.6 s.
   Two bridge details worth keeping: Rust calls `BleWriter.write` **synchronously and
   fire-and-forget** from its own runtime, so writes are queued and drained by one pump
   coroutine (instant callback, and the one-op-at-a-time GATT rule still holds); and the
   frame collector must start **before** `sync()`, because the transport buffers from
   connect and setup-phase frames would otherwise be lost.

   **Two bugs found and fixed by syncing for real, both of which looked fine:**
   - `RingSession::sync` never captured the live battery the way the CLI's `cmd_sync` does,
     so a phone-only sync left `battery_pct`/`battery_as_of` frozen at whatever the desktop
     last wrote. Fixed in `oura-core`; the reading now stamps at the moment of the sync
     (`battery_as_of` 21:29:09 for a sync that finished 21:29:09). `synced_hm`/`fresh_hours`
     were never affected — they come from `sync_state.last_sync_unix`, which `set_cursor`
     already updates.
   - The service built its **own** `SummaryRepository`, so it refreshed the cache file and
     its own `StateFlow` while the Activity kept rendering an older one: `summary.json` on
     disk said 21:18 / cursor 28366777 while the screen still showed 18:04. Now a process
     singleton, which also stops two instances racing on `summary.json`.
4. **[agent] — DONE.** Key storage: `RingKeyStore` + `RingKeyScreen`, reached from the
   profile screen. **Deviation:** not `EncryptedSharedPreferences` — `androidx.security:
   security-crypto` deprecated that API and pulls in Tink for one 32-character string.
   Instead an AES/GCM key generated *inside* the Keystore (non-exportable) encrypts the key
   and only the ciphertext reaches SharedPreferences. Verified on device: the displayed
   fingerprint matched an independently computed SHA-256 prefix, `shared_prefs/ring_key.xml`
   holds ciphertext only, and the key survived a process restart.
   Both backup configs now exclude it — a restored blob is undecryptable on another device
   and would make the app look configured when it is not. They also exclude `filesDir`, so
   Auto Backup stops shipping `oura.db` to Google's cloud, which a cloud-free client should
   never have been doing.
5. **[you]** Pair on the desktop client first and transfer the existing key; do not
   re-pair the ring from Android in Phase 4 (pairing rewrites the ring's auth key and can
   lock out the desktop client).
   **Measured 2026-08-11 (`oura peek`):** acking does not delete, so desktop and phone can
   both sync the same ring with independent cursors. But retention is a rolling ~8.5–9 day
   window (~8 MB), so **67% of the existing history lives only in the desktop `oura.db`** —
   the phone must be seeded by importing that database, and can never rebuild it from the ring.
6. **[you]** Run a real sync, wearing the ring, and confirm the event count matches.
7. **[agent]** On sync success: refresh the snapshot, then update widgets.

**Expect trouble here.** GATT 133 errors, bonding quirks, and per-vendor stack differences
are normal. Budget more time for this phase than for Phases 2 and 3 combined.

---

## Phase 5 — optional extras

Only once the above is solid:

- **Health Connect** export (sleep sessions, resting HR, steps) so other apps can read it.
- **Scheduled background sync** via WorkManager with a constraint on the ring being nearby.
- **Incremental `build_summary`.** The 3-second cost grows linearly with history. The fix is
  persisting per-day rollups in SQLite so a summary is an incremental update rather than a
  full re-scan of every event. Worth doing before the database gets much larger.

---

## Documentation changes this forces

- **`CLAUDE.md`** — the "two clients, keep them in sync" rule becomes: web ↔ Android are the
  active pair; iOS is frozen at maintenance and does not gate a feature as done.
- **`docs/clients-web-and-ios.md`** — becomes a three-column map, iOS marked frozen. Also
  correct the stale claim that iOS must recompute sleep metrics "because the FFI
  `stages_full`/`metrics` are empty under `NoModelRunner`" — no longer true.

## Reference measurements (2026-07-31, real `oura.db`)

- `build_summary`: **~3.0 s** cold over 846,765 events / 148 MB; cached thereafter.
- Summary payload: **136 KB**, ~90% of it `nights`.
- UniFFI surface: `summary_json`, `quick_summary_json` (device/data-health only, cheap),
  `rmssd`, `core_version`, `RingSession{new, push_frame, sync}`.
- Web API surface the Android app replaces: `/api/summary`, `/api/sync`, `/api/profile`,
  `/api/feature`, `/api/ring-key`.
