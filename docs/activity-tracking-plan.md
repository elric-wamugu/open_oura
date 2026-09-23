# Tracked activities — classification and routes

A plan for recording a walk, a run or a ride with a route on the map, and for labelling it
honestly. Written 2026-09-23; nothing here is built yet.

The short version: **the ring cannot do either of these, and trying to make it is the wrong
starting point.** The phone can do both, and doing the route first makes the classification
almost free.

---

## What the ring cannot give us

Worth stating plainly, because two of these are counter-intuitive.

**No GPS.** The Gen 3 has no GNSS receiver — no antenna, no power budget for one. Nothing in
the protocol carries a coordinate, and `grep -riE '\bgps\b|latitude|FusedLocation'` over the
whole repo returns nothing but HTTP routing and prose. Any route comes from the phone or it
does not exist.

**No cadence, which is the discriminator you would reach for first.** Walking runs around
110 steps/min and running around 170, so cadence separates them cleanly — and we do not have
it. `activity_steps` is *estimated from MET bands*, which is why the numbers land on
multiples of 105 and 150; see "Per-bucket steps are estimated, not counted" in
`clients-web-and-ios.md`. The ring does emit `real_step_event_feature_1`/`_2`, but those are
undecoded raw feature vectors (`_status: part1_raw`), model inputs rather than counts.

**No labels, deliberately.** `activity[]` comes from Oura's `automatic_activity_detection`
model and is permanently empty without the torch models. `effort[]` fills the gap from
`ehr_trace_event` timing, and `clients-web-and-ios.md` is explicit that those sessions
**"carry no label and must not be shown as if they did"**. Printing "Running" off a
heuristic over MET and HR is precisely what that line exists to prevent.

What the ring *does* have, and what stays valuable: continuous HR at quality-gated beat
level, MET intensity per minute, `ehr_acm_intensity_event`, and the fact that it is on your
finger 24 hours a day whether or not you remembered to start anything. This database
holds **184 effort sessions** as of 2026-09-23 — the 54 quoted in
`clients-web-and-ios.md` was the count when that section was written.

## Why the route comes first

Track the position and classification stops being a guess. **Speed separates the three
activities almost perfectly** — roughly under 7 km/h, 7–16, and above 16 — and the same
trace gives a real distance in place of the MET-derived `distance_m` estimate.

More importantly it changes what is being claimed. Labelling a GPS track as "cycling" is a
statement about something the phone observed. Labelling an `effort_session` as "cycling" is
a guess about something only the ring saw, dressed up as a fact. The first is compatible
with how this project talks about its data; the second is not.

The ring's contribution then attaches by timestamp — HR through the session, effort
intensity, the recovery cost afterwards — which is the part it is genuinely better at than
a phone in a pocket.

## The asymmetry to design around

The ring detects effort continuously, and has been doing so the whole time. The
phone will only have a route for sessions where tracking was running.

So the model is **two kinds of thing that sometimes meet**, not one:

- an `effort_session` the ring noticed, possibly with no route and no label
- a tracked activity the phone recorded, with a route, a speed profile and a label

A tracked activity overlapping an effort session in time should be presented as one thing.
One with no overlap is still a real activity. An effort session with no track is still a
real effort. **None of the three should be silently dropped or silently merged**, which is
the failure mode to watch for in the UI.

---

## Architectural invariants

1. **Classification is computed in Rust, like everything else.** The speed→label rule goes
   into `oura-summary` and both clients render it. It is exactly the kind of threshold that
   drifted when the battery bands lived in two places.
2. **No Play Services.** Use the platform `LocationManager` with `GPS_PROVIDER`, not
   `FusedLocationProviderClient`. The fused provider is better — it blends cell, Wi-Fi and
   sensors — but it lives in Google Play Services, which this app already declined for the
   auto-sync trigger. Taking the dependency here would make that earlier refusal pointless.
3. **No background location.** Tracking starts because the user started it. Never
   `ACCESS_BACKGROUND_LOCATION`: it is the permission Google scrutinises hardest, it is not
   needed for a foreground-service-backed session, and an app that can silently follow you
   is not what this one is.
4. **A track is data like any other.** It goes in `oura.db`, it is rendered from the
   summary, and it exports to Health Connect the same way a night does.

## The open decision: map tiles

**Every tile provider is a network call.** OSM, Mapbox, Google — all of them mean sending
the coordinates of the area you ran through to a server. The footer of this app says
*"Everything here is computed on this device. Nothing left it."* Fetching tiles for a route
past your house would make that sentence false.

The DNA explorer's PGS fetch is currently the *only* outbound request in the entire app, and
it is documented as such, fetches public score definitions rather than personal data, and
happens on explicit user action. A tile request is a worse trade: it is personal data, it is
continuous, and it happens just by looking at the screen.

Three options, in the order I would try them:

1. **Draw the track on a blank canvas.** Polyline, start and end markers, a scale bar, north
   arrow. No tiles, no network, no compromise. You see the shape of the run, not the
   streets. A day's work against a week's, and it answers whether streets are actually
   wanted.
2. **Bundle offline tiles** for a chosen region. Truthful and it looks like a map. Hundreds
   of megabytes and a build-time chore, and it fails the moment you travel.
3. **Fetch tiles and say so loudly**, as a second documented exception with a visible
   indicator and a setting that defaults to off.

**Recommendation: (1) first.** It is cheap, it keeps the claim intact, and a route's shape
carries most of what a person looks at a map for.

---

## Phase 0 — decide, and prove the sensor

1. **[you]** Choose the tile strategy above. Everything else is unaffected by the answer,
   so this does not block the phases below — but the UI phase needs it settled.
2. **[agent]** A throwaway screen that requests `ACCESS_FINE_LOCATION`, starts a
   `LocationManager` GPS subscription at 1 Hz, and prints fix count, accuracy and speed.
3. **[you]** Walk, then run, then ride a few minutes each with it open. Report the numbers.

Phase 0 exists because the whole plan rests on an untested assumption: that a phone in a
pocket gives usable speed at 1 Hz. Urban canyons, pockets and cheap fixes all degrade it.
**Measure before building on it.**

## Phase 1 — record a track

1. **[agent]** `TrackingService`, a foreground service of type `location` — the same shape
   as `RingSyncService`, which is already the template for a long-running job with a
   notification.
2. **[agent]** A `tracks` / `track_points` pair of tables in `oura.db` (`oura-store`), one
   row per fix: time, lat, lon, altitude, accuracy, speed.
3. **[agent]** Start/stop UI, elapsed time, live distance, current pace.
4. **[you]** Record a real walk and a real ride. Check the distance against something you
   trust.

**Filter the fixes.** Raw GPS distance over-reads badly: jitter while stationary accumulates
into hundreds of metres. Drop fixes with accuracy worse than ~20 m, and ignore movement
below a floor. This is where naive implementations produce a 6 km walk that was 4.

## Phase 2 — classify, in the brain

1. **[agent]** `oura-summary` gains a `tracked_activity` block: distance, duration, moving
   time, mean and max speed, elevation gain, and a **label** from the speed distribution.
2. **[agent]** Use the median of *moving* speed, not the mean of everything — a coffee stop
   should not turn a ride into a walk.
3. **[agent]** Where speed is ambiguous (fast running against slow cycling both sit near
   16 km/h), fall back on what the ring saw. Cycling with hands on the bars produces
   sustained high HR with almost no hand motion, which is a genuinely distinctive signature
   on a finger-worn sensor.
4. **[agent]** Emit a confidence, and render "unlabelled" rather than guessing when it is
   low. The existing rule about effort sessions applies here too.
5. **[agent]** Unit tests over synthetic speed traces, in `oura-summary`.

## Phase 3 — render, and join to the ring

1. **[agent]** Track view: the polyline, the stats, a speed-over-distance chart, and the
   ring's HR for the same window on a shared cursor — the polysomnograph's idea applied to
   a route.
2. **[agent]** Join tracked activities to `effort_sessions` by time overlap, and present a
   matched pair as one thing while keeping unmatched ones of both kinds visible.
3. **[you]** Check a ride where you know the route against what it draws.

## Phase 4 — export

1. **[agent]** Write tracked activities to Health Connect as `ExerciseSessionRecord`, with
   the fixes as an `ExerciseRoute` — both present in the `connect-client` version already
   depended on. The plumbing from `HealthExport` is there — the permissions, the date-keyed
   `clientRecordId`, the chunked insert — so this is mostly a new record type and one more
   write permission.
2. **[agent]** GPX export, because a route people cannot get out of an app is a route they
   do not really own.

---

## What this deliberately does not do

- **Classify the ring's own `effort_sessions`.** They stay unlabelled. A heuristic over MET
  and HR would be a guess presented as a fact, which is the one thing the sleep and effort
  work has been careful to avoid.
- **Track in the background.** No silent following. The user starts a session.
- **Replace the ring's activity data.** Daily steps, MET profile and effort sessions carry
  on as they are. A tracked activity is an addition, not a correction.

## Known gaps this creates

Android-only, by nature: the web dashboard has no GPS and no phone to carry. Per
`CLAUDE.md` this belongs in the "Known gaps (Android-only, by nature)" section of
`clients-web-and-ios.md` once any of it ships — with the exception of the classification
rule, which lives in `oura-summary` and which the web should render for any track it is
given.

## Open questions

- Does a pocketed phone give usable 1 Hz speed? **Phase 0 answers this.** If it does not,
  Phase 2's speed-first classification needs rethinking before anything else is built.
- Is a track without map tiles actually satisfying to look at? Cheap to find out, and the
  answer decides whether options (2) and (3) are ever worth their cost.
- Swimming and indoor work have no GPS either. Out of scope here; worth remembering before
  the UI implies every activity gets a route.
