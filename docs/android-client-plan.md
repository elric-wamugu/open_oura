# Android client — phased implementation plan

Android becomes the **primary** client: native Jetpack Compose + Glance home-screen widgets,
reusing the Rust core unchanged. iOS drops to **maintenance** (no new features; its SwiftUI
code becomes a porting reference, not a sync obligation). The web dashboard stays as the
desktop client.

Each step is tagged **[you]** (needs a human: installs, GUI, hardware, accounts) or
**[agent]** (can be written without an Android SDK present).

---

## Target device

**Google Pixel 9a, Android 17 (API 37)** — `arm64-v8a`, Tensor G4, 8 GB RAM.

The phases below were built and measured on the original target, a **Pixel 5 on Android 14
(API 34)**, so the timings and the `compileSdk 35` / `targetSdk 34` steps in them are that
device's history. The app moved to `compileSdk 37` / `targetSdk 37` for the Pixel 9a.

Consequences that run through the whole plan:

- **ABI is `arm64-v8a` only.** Same as the Apple Silicon emulator, so one Rust target
  (`aarch64-linux-android`) covers device and emulator both.
- **`compileSdk 37`, `targetSdk 37`, `minSdk 26`.** Target the device's own API level —
  never one whose behaviour changes you cannot test on hardware you own.
- **Native libraries must be 16 KB page-aligned.** Android 15+ devices can run with 16 KB
  pages, and a 4 KB-aligned `.so` can fail to load there. NDK r28+ aligns by default; check an
  APK with `zipalign -c -P 16 -v 4 app-debug.apk`.
- **API 34 makes foreground-service types mandatory.** A BLE sync service *must* declare
  `android:foregroundServiceType="connectedDevice"` and hold
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, or Android 14+ throws at `startForeground()`.
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
4. **[agent] — DONE, then superseded by the Material 3 Expressive redesign** (see that
   section below). The theme is now a full M3 scheme generated from the web's teal, with
   dynamic color (Material You) as a setting rather than the default, so the brand palette
   survives.
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

Android ships a profile editor (`ProfileStore` + `ProfileCard`, on the You tab) that writes
the same `profile.json` beside the database. Anything that provisions a
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

**Redesigned 2026-11-16: two widgets, both rings.** The three text widgets above were
replaced:

- **Last night and today** (`StageRingWidget`): last night's sleep stages as a ring —
  deep, light, REM, awake, clockwise from twelve o'clock, each stage's share of the circle
  its share of the night — with hours asleep in the middle (hours in bed for an unscored
  night) and the night's sleep efficiency under it. Then, in this order: **Steps, Distance
  (km), Ring charge**. HRV and resting heart rate are not shown, by choice.
- **Ring battery** (`BatteryWidget`): the charge as an arc, coloured on the brain's band.
- **Steps today** (`StepsWidget`, added the same day): the stage ring widget with its two
  halves swapped. The ring is today's steps towards a **daily step target**, with the
  distance under the count; the figures are **Asleep, Sleep efficiency, Ring charge**. It
  shares the card, the layouts and the refresh button with the stage ring (`RingCard`).
  The target is a setting — You → Goals → Daily step target, 7,000 by default, kept in
  `Goals` — and it is a preference, not a metric: `oura-summary` knows nothing of it and
  the web has no counterpart. The ring stops at full; a day past the target is a full ring
  and the count says the rest.

What shaped them:

- **Rings are bitmaps** for the same reason the stage bar was: RemoteViews cannot draw an
  arc. The geometry is pure and tested (`RingGeometry.kt`); only the drawing is Android's.
- **`SizeMode.Exact`, not `Responsive`.** With `Responsive`, `LocalSize` is the *declared*
  size that was matched, not the space granted, and a ring sized from it came out a
  fraction of its cell. The layout is chosen from the real size: figures beside the ring
  when wide (≥ 230 dp), under it when tall (≥ 190 dp), ring alone otherwise.
- **The receiver kept its old name.** The launcher ties a placed widget to its receiver's
  class, so the stage ring is served by `ActivityWidgetReceiver` — the old "Today" widget
  — and one already on a home screen changes in place. The Vitals and Last night
  receivers are gone; a launcher removes widgets whose provider no longer exists.
- **A fixed dark card, not the theme.** Near-black with light text in both modes, and
  not following the "Wallpaper colours" setting. The first version took its container from
  the app's scheme, and a tinted (green, or wallpaper-coloured) card fought with the stage
  colours drawn on it. A translucent card was the alternative considered: it shows the
  wallpaper through, but a widget cannot blur what is behind it, so text over a bright or
  busy wallpaper would not be reliably readable. The ring-charge figure is the one thing
  that changes colour — amber and red on the brain's bands.
- **A refresh button in the corner** (`WidgetSync.kt`): syncs with the ring and redraws
  without opening the app. While it runs the icon
  turns. A widget cannot run a Compose animation, so the turning icon is a plain layout
  (`widget_sync_spinner.xml`) holding a `ProgressBar` whose indeterminate drawable is the
  icon inside a `<rotate>`. "Syncing" is a timestamp that lapses after 90 s, not a flag,
  so a worker the system never ran cannot leave it turning.
- **The tap runs the sync in the foreground service, not the worker.** The first version
  queued the background worker, and on the Pixel 9a a tap took about 40 s to show fresh
  figures: the recompute alone was 17.8–19.5 s, against about 3 s with the app on screen,
  because a process with nothing in the foreground is confined to the small cores. A
  widget tap is one of the moments Android allows a foreground-service start from the
  background, so `SyncFromWidget` starts `RingSyncService` — the service the app's own
  Sync button uses — and the same tap now takes **8 s** (sync 3.3 s for 109 events,
  recompute 4.1 s). The service's notification shows while it runs. Without the Bluetooth
  permissions or a ring key, or if Android refuses the start, it falls back to the worker
  as `SyncTrigger.WIDGET`. The scheduled syncs still recompute in the background at the
  slow rate; nobody is waiting on those.
- **A widget on screen has to be told its data changed.** Glance keeps a composition alive
  for a while after a draw or a tap, and `updateAll` only recomposes it — the code that
  loaded the snapshot does not run again. The first version read the snapshot once, up
  front, so a tap synced the ring and the widget went on showing what it had. The widgets
  now read inside the composition, keyed on `WidgetBus.version`, which every snapshot
  write and every start or end of a widget sync bumps.
- **The tap handler needs a keep rule.** Glance creates an `ActionCallback` by class name;
  R8 keeps the class and would strip its constructor. See `rules.keep`.
- **Icons** beside each figure are the app's own, tinted for the dark card; the ruler for
  Distance (`ic_sym_straighten`) is new.
- **Distance** is the brain's `distance_m` (steps × 0.762 m, ecore's own factor), already
  shown on the web's activity report. Nothing new is computed.

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
- ~~**Incremental `build_summary`.**~~ **Not needed — the cost was a query plan, not the
  re-scan.** By 2026-09-25 this had reached 78 s on the phone and 54 s on an M-series Mac,
  and the rollup rewrite looked unavoidable. It was not: `idx_events_serial_tag` is
  selective for tag but not for serial (one ring, so `serial = ?` matches every row),
  SQLite chose it anyway, and sorting 1.87M rows back into `id` order needed a
  disk-spilling TEMP B-TREE. Suppressing the index for that one query took the phone to
  **4.5 s** and the Mac to **6.3 s**. JSON parsing — the other obvious suspect, 138 MB of
  it decoded in seven separate passes — turned out to cost under a second a pass. Revisit
  rollups only if a measurement says so; `cargo run --release -p oura-summary --example
  profile_summary -- oura.db` is that measurement. **It is being asked again:** the
  Pixel 9a took 7.5–8.3 s by 2026-10-29 and 8.9–9.6 s by 2026-11-15 — see "Release build
  and profiling" below. **And answered the same way on 2026-11-16:** three of
  the stages were doing repeated work, not necessary work — see "Recompute, second pass"
  below. Still no rollups.

---

## Release build and profiling — **DONE, measured on the Pixel 9a 2026-11-15**

### The release build

Until now the phone ran the debug build: no R8, and debuggable, so ART neither applies the
baseline profiles Compose ships nor compiles ahead of time. `./gradlew :app:assembleRelease`
now produces `app/build/outputs/apk/release/app-release.apk`: **5.2 MB against 23 MB**
(4.0 MB of dex against 42 MB), with the libraries' baseline profile packaged in
`assets/dexopt/`.

- **R8 on, names kept.** `-dontobfuscate`: shrinking and inlining are where the speed is,
  and renaming would cost readable stack traces and risk the saved navigation back stack,
  which is stored by serialised class name. The app's own rules are in
  `app/src/main/keepRules/rules.keep` — JNA and the generated `uniffi` package are kept
  whole, because `liboura_core.so` and JNA's dispatch library reach them by name.
- **Signed with the debug keystore, on purpose.** Android installs an update only over a
  matching signature, and the installed copy is signed with that key. A separate release key
  would force an uninstall, which deletes `oura.db`. With one key either build installs over
  the other: `adb install -r app-release.apk`. Check the certificate first with
  `apksigner verify --print-certs`, and never uninstall to get past a signature error.
- **Not debuggable, so `run-as` stops working** while the release build is installed. That
  takes away pulling `oura.db` off the phone and the adb push import. Install the debug build
  over it to get them back; the data survives. The You tab's About row says "(debug)" on a
  debug build, since both carry the same version.
- **`profileable`**, so simpleperf and Perfetto attach over adb to the code that really runs.
- **After a sideload** the baseline profile is compiled at the next idle maintenance window.
  `adb shell cmd package compile -f -m speed-profile org.openoura.android` does it at once.
- **Not done:** an app-specific baseline profile, which needs a macrobenchmark module.
- **Two library rules had to be completed.** Room 2.5.0 and WorkManager 2.9.1 keep
  `RoomDatabase` and `InputMerger` subclasses but not their no-argument constructors, which
  both libraries call by reflection, and R8 strips a constructor nothing calls directly. The
  first release build crashed at startup creating WorkManager's `WorkDatabase`; the second
  ran, but every job failed with "Could not create Input Merger" — no background sync, no
  widget refresh, and nothing on screen to say so. Both rules are in `rules.keep`. After
  adding or bumping a dependency, look in `app/build/outputs/mapping/release/usage.txt` for
  a removed `<init>()` on anything created by class name, and read logcat on the first run.

Cold start to the first frame (`am start -W`, process killed between runs):

| Build | Cold start | Cache parse on the main thread |
| --- | --- | --- |
| debug | 1.4–5.3 s over 16 launches | 0.54–2.8 s (timed on 5 of them) |
| release, as installed (`verify`) | 271–294 ms over 7 | 104–113 ms |
| release, profile compiled (`speed-profile`) | 200–224 ms over 7 | 76–80 ms |

On the debug build the first frame arrives about 0.9 s plus the cache parse, and the parse
swings five-fold for the same file. It runs in ART's switch interpreter inside `onCreate`,
so it is at the mercy of where the scheduler puts a launching app. That last part is a
guess: the launch was never slow while simpleperf was sampling it, which fits CPU placement
but does not prove it. The release build makes it moot.

### Where a recompute goes

`build_summary` times its own stages and ships them as `timings_ms` (milliseconds):

| Stage | What it covers |
| --- | --- |
| `read_events` | Opening the database and reading every decoded event into memory |
| `clock` | Splitting into boot epochs and fitting each one's ring-time → wall-clock offsets |
| `night_signals` | Finding the bedtime periods and collecting each night's HR, HRV, temp, SpO₂ and motion |
| `models` | The model runner (nothing on the phone: `NoModelRunner`) |
| `sleep` | Per-night metrics, series and sleep debt |
| `activity` | The MET profile, steps and calories per day |
| `heart` | Quality-gated beats, peak sustained HR and effort sessions |
| `device` | Vitals trends, stream and event counts, battery, the device block |
| `total` | All of the above |

The Android app logs it after every recompute, slowest first, with what it cost on top:

```
OpenOura: summary_json: 838 KB in 8331 ms — read_events …, night_signals …; other …; parse … ms
OpenOura: summary cache: 838 KB read and parsed in … ms on main
```

`other` is serialising the summary and carrying the string across the FFI; `parse` is
kotlinx.serialization turning it back into objects. On the desktop,
`cargo run --release -p oura-summary --example profile_summary -- oura.db 3` prints the
same stages.

Measured on the Pixel 9a over a 1.03 GB database (ms; the core is the same
`liboura_core.so` in both builds, so only `parse` differs between them):

| Run | `read_events` | `heart` | `night_signals` | `sleep` | `clock` | `device` | `activity` | other | total | parse |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| debug | 4386 | 1770 | 1549 | 823 | 358 | 226 | 106 | 334 | 9556 | 154 |
| release, seconds after an install | **22367** | 1799 | 1515 | 832 | 352 | 229 | 104 | 326 | 27527 | 54 |
| release | 4267 | 1753 | 1529 | 843 | 329 | 220 | 102 | 338 | 9384 | 37 |
| release, after a sync | 3823 | 1771 | 1525 | 864 | 327 | 203 | 106 | 292 | 8916 | 35 |

What that says:

- **Reading the events is 43–46% of a recompute**, and the only stage that moves. It took
  22 s once, straight after an install, with every other stage unchanged — so it is bound
  by the disk, and by whether the 1 GB file is still in the page cache. Reading less is the
  only fix; a faster parse would not touch it.
- **The three passes that parse event JSON** (`heart`, `night_signals`, `sleep`) are another
  4.1 s between them, steady to within 3%.
- **It grows about half a second a week**: 7.5–8.3 s on 2026-10-29, 8.9–9.6 s on 2026-11-15.

Found on the way, and what became of each (2026-11-16):

- **Cold start parsed the cache on the main thread** — about 38% of a release cold start.
  **Fixed:** `SummaryRepository.load` reads and parses on `Dispatchers.Default`, so the
  first frame no longer waits for it. The first frame is now the loading state, replaced
  as soon as the parse lands.
- **Every app open parsed the cache twice**, once to draw and once in the app-open sync
  worker to work out quiet hours. **Fixed:** the repository keeps the parsed summary with
  the cache file's modification time and parses again only when the file has changed; a
  lock makes two callers arriving together share one parse.
- **A launch with a stale cache and a sync due recomputes twice.** **Left as it is.** The
  first recompute is only wasted when the sync that follows brings something in, which is
  not known in advance: skipping it would leave a stale summary on screen for the length
  of a scan (up to 25 s) whenever the ring is out of reach. At 3 s a recompute instead of
  9.5, on the rare launch that is both stale and due, the waste is cheaper than the wait.
- **The Sync button had no accessibility label** when collapsed to its icon. **Fixed:**
  the icon carries "Sync" whenever the text beside it is not shown.

Both things left unmeasured here — the memory held by every event's JSON at once, and how
much of `night_signals` was spent on events whose tag it then ignored — are answered in
"Recompute, second pass" below.

### Recompute, second pass (2026-11-16)

The stage timings said where to look, and what they pointed at was not the size of the
history but work done over and over inside three stages. Fixed, with the summary proven
unchanged (below), on the same 1,033 MB / 5,655,007-event database, desktop, ms:

| | `read_events` | `heart` | `night_signals` | `sleep` | `clock` | `device` | `activity` | total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| before | 1209 | 1582 | 1166 | 1031 | 313 | 225 | 80 | 5606 |
| after | 670 | 590 | 290 | 40 | 330 | 220 | 80 | 2250 |

And on the Pixel 9a, same database, where it matters (three runs each):

| | `read_events` | `heart` | `night_signals` | `sleep` | `clock` | `device` | `activity` | other | total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| before (2026-11-15) | 3823–4386 | 1753–1771 | 1525–1549 | 823–864 | 327–358 | 203–226 | 102–106 | 292–338 | 8916–9556 |
| after | 1039–1132 | 716–734 | 380–407 | 45–53 | 313–359 | 212–269 | 106–116 | 228–272 | 3093–3273 |

The summary the phone wrote afterwards is identical to the one the old code produced on
the desktop from a copy of the same database.

- **`sleep`, 1031 → 40.** Each night without a model hypnogram looked for its
  `sleep_phase_data` by walking every event on record: nights × events, so it grew with the
  *square* of the days recorded. The few thousand phase events are now collected once.
- **`night_signals`, 1166 → 290.** It found the night for, and parsed the JSON of, every
  event that fell inside any night, then ignored all but six tags. It checks the tag first.
- **`heart`, 1582 → 560.** The 30-second sustained-peak window allocated and fully sorted
  itself once per beat; it now selects the median in a reused buffer. And each effort
  session filtered every beat on record to find its own; the beats are sorted, so it
  bisects.
- **`read_events`, 4 s → 1.1 s on the phone.** There it was two thirds of what remained,
  and it was the CPU walking rows rather than the disk. The ids are dense and the table is
  their B-tree, so the store now splits the id range into four slices and reads them on
  four read-only connections at once (WAL admits any number of readers), joining them in
  slice order — the single scan's result, row for row, which a test asserts. Four is the
  phone's count of fast cores. Rows are only appended and every slice stops at the id that
  was newest when the read began, so a sync committing meanwhile changes nothing; only
  `redecode` run during a read could show two versions of a row.
- **Less held in memory.** `build_summary` asks the store for the JSON of only the twelve
  event types it parses (`JSON_EVENTS`). The rest still come back as rows, for the clock
  fit and the counts, with the JSON left empty: about 140 MB of 250 MB not copied out of
  the database. On its own it did not make the read faster — walking the rows is the cost,
  not copying the strings.

**How "unchanged" was established**, and how to do it again: `profile_summary` takes a
third argument and writes the summary there without the three fields that differ between
any two runs (`timings_ms`, `generated_at`, `device.fresh_hours`). Two runs of the old code
gave byte-identical files, and the new code's file is identical to those.

```bash
cargo run --release -p oura-summary --example profile_summary -- oura.db 3 before.json
# …change something…
cargo run --release -p oura-summary --example profile_summary -- oura.db 3 after.json
cmp before.json after.json
```

**What is left** is spread thin: on the phone the read is a third, the heart stage a
quarter, and nothing else is over 400 ms. The read still walks all 5.66 M rows to return
the 3.19 M decoded ones.

**Why still not incremental.** A summary that reuses old days and recomputes recent ones
is the only thing that makes a recompute stop growing, and it remains undone on purpose:

- Old results are not provably frozen. The ring-time → wall-clock fit is a running minimum
  taken backwards from the newest sync, so a later sync can in principle move an earlier
  night. In practice the counter's drift (minutes a day) dwarfs the bounds' slack and it
  does not, but "in practice" is what produced nights that shifted between two views of
  the same data before.
- Every section of the summary would need its own merge rule (baselines, sleep debt,
  battery cycles, effort sessions, trends), in the one function both clients trust.
- At 3.2 s on the phone, growing linearly, the measurement does not ask for it yet.

### The database on disk

Where 1,033 MB (5,655,007 events) went, from SQLite's `dbstat`:

| | MB |
| --- | --- |
| `events` table | 641 |
| — of which `decoded_json` | 250 |
| — `name` (the event's name, repeated on every row) | 104 |
| — `body` (what the ring actually sent) | 67 |
| — `serial` (one ring, repeated on every row) | 62 |
| the UNIQUE constraint's index (serial, tag, ring_timestamp, body) | 254 |
| `idx_events_serial_tag` | 137 |

- **`idx_events_serial_tag` is gone.** The UNIQUE index starts with the same two columns,
  so it serves every lookup the narrower one could. `Store::open` drops it from existing
  databases (0.3 s here). That alone only frees pages inside the file, which SQLite keeps
  and reuses as the history grows.
- **Compacting gives the space back**: `oura compact` on the desktop, You → Ring & data →
  Compact database on Android. It is SQLite's `VACUUM`: 1,033 → 868 MB, in 3.6 s here and
  18 s on the Pixel 9a, same summary before and after. It needs about twice the database's size free while it runs,
  so the Android app checks first and refuses during a sync. Manual on purpose.
- **Not done: a slimmer schema.** `name` and `serial` could become small integers and
  save roughly another 230 MB (table and index), and `decoded_json` another 250 MB if it
  were decoded on read. But `events` is read directly, by column name, by the Python model
  runners (`tools/run_*_model.py`, `tools/build_daily.py`) and the iOS app
  (`EventStore.swift`, `CvaModel.swift`), so that is a migration of every reader plus a
  rewrite of each user's only database — its own piece of work.

### Measuring heart rate now (2026-11-16)

A "Measure heart rate" card on Today, between Vitals and Cardiovascular, opens a screen
that reads the pulse live for 30 seconds.

- **The ring could, but not the way the code asked.** `OuraClient::live_heart_rate` put
  daytime HR in `CONNECTED_LIVE` and waited for per-beat notifications. On this Ring 3 the
  mode change is accepted and nothing follows: the first measurement from the phone ran
  its 30 seconds and received no live frames at all. The notifications are indications of
  the feature's *latest value*, and the ring only sends them to a subscriber, so the
  method now also sends `SetFeatureSubscription(DAYTIME_HR, LATEST)` — after which the
  same ring sent 27 in 30 s — and drops it again on the way out. `oura live-hr` on the
  desktop gets the same fix. The ring sends about one reading a second, not one per beat.
- **A measurement reports what the ring sent**, not only the beats (`HeartRateReport`:
  notifications seen, how many were live-HR ones, the ring's answer to the mode change,
  the first few raw). That is what turned "no reading" into the finding above, and it is
  logged every time.
- **`live_heart_rate_until`** is that method made fit for a person: it can be stopped
  early, checking a flag four times a second whether or not beats are arriving (a ring off
  the finger sends none, and Stop has to work then too), and it restores a mode it is
  given rather than always `AUTOMATIC`. `RingSession::live_heart_rate` reads that mode from
  `feature_modes.json`, so a measurement cannot switch daytime HR on for someone who had
  it off.
- **Stop, do not cancel.** The Kotlin side ends a measurement with
  `RingSession::stop_heart_rate` and waits for the call to return. Cancelling the
  coroutine would drop the Rust future before it restores the mode. The exception is
  while still looking for the ring, when nothing has been asked of it yet and the search
  is simply abandoned.
- **One link at a time.** A measurement refuses to start during a sync, and the app's Sync
  button, the background sync worker and the widget's refresh all stand back while one
  runs.
- **What is shown.** Each beat's figure comes from that beat's interval alone, so the raw
  stream jitters and occasionally contains a mistimed beat. The screen shows the median of
  the last five; the result's average, low and high are taken over that smoothed series,
  and fewer than eight beats is reported as no reading.
- **Not saved as history.** Nothing goes into `oura.db` and Health Connect is not told;
  the last result alone is kept, for the card.
- **Tried on the ring, 2026-11-16:** 26 readings in 30 s, result 77 bpm (75–79), no
  freeze. Not checked: Stop mid-measurement, leaving the screen mid-measurement, the ring
  off the finger, and that the ring's mode reads back as restored afterwards.
- **Off the main thread, in its own scope.** The first build ran the measurement in the
  Activity's scope and froze the app the moment it connected: the BLE bridge waits for
  GATT callbacks that Android delivers on the main thread, so a write made from the main
  thread waited for a reply that could not arrive until it stopped waiting. Every thread
  was asleep, which is what gave it away. Syncs and feature toggles already ran on
  `Dispatchers.IO` for this; `LiveHeartRate` now has its own IO scope, which also means a
  rotation cannot cancel a measurement half-way and strand the ring in live mode.

### Sleep stages worked out on the phone (2026-11-20)

The app has no sleep model, so until now a night's hypnogram was the ring's own
`sleep_phase_data`. Nights are now staged on the phone by the stager from upstream
Th0rgal/open_oura#36 (`oura-analysis::sleepstaging`, see
[staging-classifier.md](algorithms/staging-classifier.md)) from the night's beat intervals
(`0x60`), motion (`0x72`) and skin temperature (`0x75`).

- **Android only.** `oura-core::summary_json_staged` turns it on; `summary_json`, which iOS
  calls, and the desktop dashboard are unchanged and still show the ring's own stages when
  no model ran. In `build_summary_with` it is `SummaryOptions::stage_sleep`.
- **Precedence:** a model's stages, then the stager's, then the ring's. Each night says which
  in `stages_source` ("model", "stager", "ring", "none"), and the sleep page says it in a
  line under "Sleep architecture". The stager's output is not smoothed again; it has its own
  post-processing.
- **Once a night.** Results go into the `sleep_stage_cache` table, keyed by the night's ring
  times, the earliest capture time of its boot (ring times repeat across reboots), and the
  model name. A night within three days of the newest data is checked against a fresh count
  of its input events and staged again if it has gained some.
- **Time budget.** A recompute spends at most `STAGE_BUDGET_MS` (10 s) on nights not yet in
  the cache, newest first. On the Pixel 9a that staged 19 nights; the first recompute with
  124 nights left most for later ones, which work through the rest while the ring's stages
  stand in. A recompute with everything cached spends about 0.1 s on it.
- **Reading a night's inputs** goes straight to the store (`decoded_events_in_window`) rather
  than through the summary's main read, which no longer carries beat-interval JSON (it is
  80 MB of it).
- **Dating.** The stager's own clock uses the ring's `time_sync` events, which this database
  has almost none of; none are passed, so it falls back to the summary's fitted clock for
  every event.

How it compares, on the same 124 nights from one Ring 3: the stager and the ring's own stages
agree on 65% of 30-second epochs. The stager gives less deep sleep (12.7% of the night
against 18.2%), more light (48.3% against 42.8%) and less REM (14.8% against 18.1%).
Neither is ground truth. The stager's author reports 92.6% agreement with the official app's
stages on 14 nights from one person; how the ring's own stages compare with the app is
unknown, and so, for this user, is which of the two is closer.

---

## Material 3 Expressive UI — **DONE** (2026-10-12)

The dashboard-era look (one hand-rolled palette, hairline-bordered cards, monospace
numbers, a single home screen with sub-screens) was replaced with Material 3 Expressive.
Android-only and visual: no metric or data changes, so nothing to mirror on web or iOS.

- **Dependencies.** Expressive is public only from `material3` **1.5.0-beta01**; the stable
  BOM still maps to 1.4.0, which keeps it internal. That beta is pinned explicitly and is
  the one pre-release dependency. Navigation 3 (1.2.0) runs the tabs; Glance 1.2.0 plus
  `glance-material3` themes the widgets.
- **Colour.** `apps/android/gen-theme.py` generates the whole scheme from the dashboard teal
  `#0D9488` (Fidelity variant, 2025 colour spec) into `ui/theme/BrandSchemes.kt` and
  `res/values{,-night}/brand_colors.xml`, plus the warn / High / Low / Normal families.
  Re-run it with `uv run apps/android/gen-theme.py`; `BrandSchemeContrastTest` fails if a
  re-run makes any text role unreadable. Sleep stages and status colours live in
  `DataColors` and never follow Material You; charts read `chartColors()`. A
  "Wallpaper colours" switch on the You tab (`ThemePrefs`) swaps in the system's dynamic
  scheme for the app and the widgets.
- **Type.** M3's scale with the emphasized styles and tabular figures everywhere; monospace
  (`CodeType`) only where the characters are the content — key hex, identifiers, wordmark.
- **Navigation.** Four tabs — **Today, History, Ring, You** — each its own saveable back
  stack (`ui/nav/Navigator.kt`, covered by `NavigatorTest`). Back pops within a tab, goes to
  Today from another tab's root, and leaves the app from Today's root; reselecting a tab
  returns it to its root. Tab roots fade through, pushed screens slide with the expressive
  springs, predictive back included.
- **Shell.** `ui/app/OpenOuraApp.kt`: `ShortNavigationBar`, a Sync extended FAB on Today and
  Ring (folds while scrolling, `LoadingIndicator` while syncing), snackbars for transient
  outcomes (sync done, capability written, export finished), and per-screen top app bars
  via `ScreenScaffold` (large flexible titles on tab roots). Entries read live state through
  delegated Compose state — Navigation 3 builds an entry once, when it is pushed.
- **Components.** Segmented list items for grouped rows, `ExpandablePanel` (corners spring
  rounder as it opens) for Battery and Device & data, `ConnectedChoice` (connected toggle
  buttons) for Male/Female and Sleep/Activity, shaped icons on `MaterialShapes` cookies,
  `LinearWavyProgressIndicator` for sync progress.
- **Widgets.** `ColorProviders(BrandLight, BrandDark)` or `DynamicThemeColorProviders`, the
  launcher's widget radius, the headline figure on a primary-container pill. The stage bar
  stays a bitmap and picks its palette for the mode in force when drawn.
- **Previews.** `ui/preview/` — light/dark `@PreviewLightDark` for the vital tile, day card,
  sync card, Device & data and the Today screen, on fixed fixture data.

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
