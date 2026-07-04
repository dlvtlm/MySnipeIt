# MySnipeIt — Project Context for Claude

> Onboarding doc for new Claude sessions. Read this first to skip exploration. Update on every meaningful commit (see "Maintenance" at the bottom).

## What this is

Android tactical operator app that talks to a **Raspberry Pi 5** mounted on a remote sniper/sensor rig. The phone/tablet is the operator HUD: it shows the Pi's live RTSP video, overlays ML target detections, displays sensor telemetry (rangefinder, temp/humidity, GPS, wind, servo angles, compass), and sends commands (lock/unlock target, calibrate, manual target, e-stop) back to the Pi over WebSocket + HTTP.

- **Platform:** Android, `minSdk 26`, `targetSdk/compileSdk 34`, landscape-only, immersive (system bars hidden).
- **UI:** 100% Jetpack Compose, Material3, custom "tactical" palette (dark + light).
- **Form factor:** Designed for 1280×800 tablet landscape; phone-landscape (<840dp wide) falls back to compact layouts via `Responsive.kt`.
- **Package:** `com.example.mysnipeit`
- **App name string:** `MySnipeIt`

## Tech stack

- **Language:** Kotlin 2.0.21, JVM target 1.8
- **Build:** Gradle Kotlin DSL, AGP 8.12.1, version catalog at `gradle/libs.versions.toml` (note: catalog is partially used — `app/build.gradle.kts` still hardcodes most deps directly)
- **UI:** Jetpack Compose (BOM `2023.10.01`), Material3, Navigation Compose 2.7.4, `kotlin-parcelize`, `kotlin.plugin.compose`
- **Architecture:** Single-Activity → `SniperViewModel` (AndroidViewModel) → `SniperRepository` → `RaspberryPiClient`. State via `StateFlow`, collected with `collectAsStateWithLifecycle`. No DI framework wired up yet (Hilt is in the version catalog but not applied).
- **Networking:** Retrofit 2.9 + Gson + OkHttp 4.12 (declared, but actual Pi comms use raw `HttpURLConnection` + `Java-WebSocket 1.5.3`). Gson is the JSON parser in use.
- **Video:** Media3 ExoPlayer 1.2.0 with RTSP source (`androidx.media3:media3-exoplayer-rtsp`). Legacy ExoPlayer 2.19.1 also present — prefer Media3 for new code.
- **Maps:** `play-services-maps` + `maps-compose` 4.3.0, plus `play-services-location` (FusedLocationProvider). Google Maps API key injected via Secrets Gradle plugin as `${MAPS_API_KEY}` in the manifest — put it in `local.properties` as `MAPS_API_KEY=...`.
- **Permissions runtime:** Accompanist Permissions 0.32.0. Location permission is requested manually in `MainActivity` (not via Accompanist there).
- **JSON:** Gson 2.10.1 (kotlinx-serialization is in the catalog but not applied).
- **Persistence:** `SharedPreferences` only ("snipeit" prefs: `dark_theme` boolean, `cartridge_id` + `rifle_id` strings for the ballistic loadout, `tripod_world_bearing_deg` float + `tripod_calibrated_at_ms` long for the operator-calibrated mic-array world-bearing offset and its capture timestamp). No Room, no DataStore.

## Repo layout

```
MySnipeIt/
├── app/
│   ├── build.gradle.kts            # All app deps (mostly hardcoded, not via catalog)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml     # Permissions, MAPS_API_KEY, landscape-only MainActivity
│       └── java/com/example/mysnipeit/
│           ├── MainActivity.kt                       # Single Activity, hosts Compose root, perms, immersive mode
│           ├── viewmodel/SniperViewModel.kt          # All app state, nav, device list, theme toggle
│           ├── data/
│           │   ├── ballistics/
│           │   │   ├── TargetLocalizer.kt             # Pure fn: Pi sensors → target world coords; RigGeometry constants
│           │   │   └── FiringSolutionSolver.kt        # Pure fn: sniper GPS + target + cartridge/rifle/atmosphere → AZ / hold-over / windage / TOF
│           │   ├── location/DeviceLocationProvider.kt # FusedLocationProvider wrapper → StateFlow<LatLng?>
│           │   ├── models/                            # All data classes (Device, SensorData, Target, ShootingSolution, SystemStatus, BallisticProfiles)
│           │   ├── network/
│           │   │   ├── RaspberryPiClient.kt          # WS + HTTP client to RPi; pacer + IoU tracker + EMA smoother + keepalive
│           │   │   ├── WifiBinder.kt                 # Force traffic onto WiFi (RPi AP has no internet)
│           │   │   ├── WifiPerfLock.kt               # FULL_LOW_LATENCY WifiLock + wake lock held during the live view (suppresses WiFi power-save)
│           │   │   └── Networktester.kt              # TCP-based ping/port-scan utility (currently unused, kept for diag)
│           │   └── repository/SniperRepository.kt    # Thin pass-through over RaspberryPiClient
│           └── ui/
│               ├── home/HomeScreen.kt                # Landing "secure terminal"
│               ├── device/DeviceSelectionScreen.kt   # List of 4 hard-coded devices
│               ├── map/MapScreen.kt                  # Google Map w/ device pins + user location
│               ├── dashboard/
│               │   ├── DashboardScreen.kt            # Operator HUD chrome around the video
│               │   ├── TacticalVideoPlayer.kt        # ExoPlayer RTSP + bbox overlays + reticles
│               │   ├── LoadoutDialog.kt              # Cartridge + rifle profile picker (MENU → Loadout)
│               │   └── MockVideoFeed.kt              # Plays bundled field_video.mp4 when no RTSP
│               ├── diagnostics/DiagnosticsScreen.kt  # Live sensor / status dump
│               ├── components/TacticalCompass.kt     # Custom azimuth compass widget
│               └── theme/
│                   ├── Theme.kt                      # MySniperItTheme; exposes LocalTactical + LocalIsDarkTheme
│                   ├── Color.kt                      # TacticalDark + TacticalLight palettes (+ deprecated aliases)
│                   ├── Type.kt                       # Inter + JetBrains Mono fonts
│                   ├── TacticalComponents.kt         # Shared Chip, TopBar, Bracket button, etc.
│                   └── Responsive.kt                 # isCompactWidth() / responsiveDp() — 840dp breakpoint
│       └── res/
│           ├── raw/field_video.mp4                   # Demo video for MockVideoFeed
│           ├── raw/maps_style_tactical_dark.json     # Dark map style
│           ├── font/                                 # Inter + JetBrains Mono ttf files
│           └── ... (standard mipmap/values/xml)
├── build.gradle.kts                 # Root plugins (apply false)
├── settings.gradle.kts              # rootProject = "MySnipeIt", includes :app
└── gradle/libs.versions.toml        # Version catalog (partially used)
```

## Architecture & data flow

```
RPi5 ──WS:8555──► RaspberryPiClient ──StateFlow──► SniperRepository ──StateFlow──► SniperViewModel ──collectAsState──► Composables
     ◄──WS:8555── (commands: select_target lock/unlock, set_servo_angles slew — via sendWsCommand)
     ──HTTP:8000◄── (calibrate, manual target, emergency_stop — Pi has no HTTP server yet, no-ops)
     ──RTSP:8554──► TacticalVideoPlayer (ExoPlayer RTSP)
```

### Key state flows (read these in any new screen)

Exposed from `SniperViewModel`:
- `uiState: StateFlow<SniperUiState>` — `currentScreen`, `selectedDeviceId`, `selectedTargetId`, `previousScreen`, etc.
- `availableDevices` — **hardcoded list of 4 devices** (Device 1–4) with fake GPS coords in the Negev region. Device 3 uses `WifiBinder.FALLBACK_GATEWAY` (`10.42.1.1`) as its IP — this is the real RPi.
- `userAltitudeM: StateFlow<Double?>` — device altitude in metres MSL when the GPS fix has a vertical component; null otherwise. Fed into the firing-solution solver as the sniper's elevation.
- `firingSolution: StateFlow<FiringSolution?>` — app-computed solution from `latchedSensorData` + `userLocation`/`userAltitudeM` + selected cartridge/rifle. Recomputes reactively via `combine()`. The Pi's `shooting_solution` WS message is still parsed by `RaspberryPiClient` but is NOT consumed by the UI — `firingSolution` replaces it.
- `sensorData: StateFlow<SensorData?>` — RAW stream straight from the Pi; consumed by the Diagnostics LIVE SENSORS pane so the operator sees actual valid flags.
- `latchedSensorData: StateFlow<SensorData?>` — sticky version of `sensorData`: each sub-frame holds its last VALID reading for up to `sensorLatchTimeoutMs` (default 5 s; tunable on `SniperViewModel`) before falling back to "—". Dashboard consumes this. Wind speed + direction latch independently (two valid flags), and the compass only latches when `heading_deg` is non-null so a missing heading is never substituted as `0°`.
- `sensorHistory: StateFlow<List<SensorData>>` — rolling window of the last 10 raw frames (oldest first). Powers the Diagnostics LIVE SENSORS history strip.
- `acousticEvent: StateFlow<AcousticEvent?>` — latest TDOA detection from the Pi's 4-mic module (separate WS message, not part of `ddl_frame`). Single-slot — dedupe/timeout/alert state is in `activeAudioAlert` below.
- `tripodWorldBearingDeg: StateFlow<Double?>` — operator-calibrated world bearing of the tripod-forward direction (= mic 0° axis world bearing). Persisted in SharedPreferences. Set via dashboard MENU → Calibrate Bearing (`CalibrateBearingDialog`) when the operator centres the camera at servo 90° — the latched compass at that moment IS this value (since the compass is on the moving head). Null when never calibrated. The saved value also **expires** after `tripodCalibrationTimeoutMs` (default 90 min, tunable) — `isCalibrationValid()` / `effectiveTripodWorldBearingDeg()` gate its USE (the value isn't wiped, just ignored once stale). When uncalibrated OR expired, audio alerts still fire but show the RELATIVE mic angle instead of a world bearing (the SLEW still works — it only needs the raw azimuth).
- `tripodCalibratedAtMs: StateFlow<Long?>` — wall-clock timestamp of the most recent successful calibration. Drives the "Last calibrated: X ago" line in the dialog and the `CalibrationAgeChip` in the dashboard top bar (tone bands: < 30 min On, 30 min–timeout Warn, ≥ timeout Danger + "CAL EXP"). The red band coincides with `tripodCalibrationTimeoutMs` — chip-red == expired.
- `activeAudioAlert: StateFlow<AudioAlert?>` — derived alert state for the dashboard. Built from `acousticEvent` + `effectiveTripodWorldBearingDeg()` + `uiState.selectedTargetId`. Computes the world bearing via `worldBearingFromAcousticEvent` when the calibration is valid (sets `AudioAlert.isWorldBearing=true`); otherwise the alert carries the raw mic azimuth and `isWorldBearing=false` (UI shows "REL <angle>"). Dedupe/debounce key on the **raw mic azimuth** (always present, calibration-independent): dedupes same-source events within ±15° (refreshing in place), auto-dismisses 20 s after the FIRST event of a dedupe group (`audioAlertTimeoutMs`, tunable), debounces re-fires for 30 s after explicit DISMISS, and flips `isInteractive` based on whether a target is selected (locked → passive chip, unlocked → full card). Setters: `acceptAudioAlert()` (sends the slew via raw azimuth) and `dismissAudioAlert()` (arms the debounce).
- `detectedTargets: StateFlow<List<DetectedTarget>>` — post-pacer/tracker output, NOT raw WS payload
- `shootingSolution: StateFlow<ShootingSolution?>`
- `systemStatus: StateFlow<SystemStatus>`
- `streamReady: StateFlow<Boolean>` + `rtspStreamUrl: StateFlow<String?>`
- `userLocation: StateFlow<LatLng?>` — device GPS, null until permission granted + first fix
- `darkTheme: StateFlow<Boolean>` — persisted to SharedPreferences
- `selectedCartridge: StateFlow<CartridgeProfile>` + `selectedRifle: StateFlow<RifleProfile>` — ballistic loadout, persisted to SharedPreferences, chosen via dashboard MENU → Loadout (`LoadoutDialog`). Presets live in `BallisticProfiles`.
- `forceMockMode: StateFlow<Boolean>` — operator-triggered offline test path; setter `setForceMockMode(true)` disconnects from any real Pi and starts the in-app mock generator. Wired to Diagnostics → MOCK MODE. The mock anchors the synthetic Pi ~100 m east of the operator's own GPS so the ballistic calculator stays in range regardless of where the device is.

### Navigation

No Nav Compose graph — `SniperApp` does a manual `when (uiState.currentScreen)` switch over an `AppScreen` enum: `HOME → DEVICE_SELECTION/MAP → DASHBOARD → DIAGNOSTICS`. ViewModel exposes `navigateToX()` functions. `goBackFromDashboard()` uses `previousScreen` to return to whichever entry path was used (map vs device list).

## The Pi protocol (critical)

`RaspberryPiClient` is where almost all integration complexity lives. Read this whole file before touching networking code. **It has a long JSDoc footer at the bottom showing exact JSON shapes — do not invent shapes.**

### Ports
- **WebSocket:** `ws://<ip>:8555` — bidirectional. Inbound (Pi→app): sensor data, target detections, shooting solutions, `acoustic_event`, `stream_ready`, system status. Outbound (app→Pi): commands via `RaspberryPiClient.sendWsCommand()` with envelope `{type:"command", command, params, timestamp}`. The Pi parses these in its WS receive handler (`websocket_server.c` → `ddl_bridge_handle_command`) and dispatches to the servo event bus. Outbound commands: `select_target` (lock/unlock → servo LOCK / SCAN events) and `set_servo_angles` (acoustic slew → `ddl_servo_set_target` + NOISE_DETECTED event, which slews + resumes the autonomous scan).
- **HTTP commands:** `POST http://<ip>:8000/api/command` with `{command, params}` — `RaspberryPiClient.sendCommand()`. **The Pi has NO HTTP server**, so these are currently no-ops (`calibrate_system`, `set_manual_target`, `emergency_stop` from `SniperRepository` go here and land nowhere). Kept for when/if the Pi adds an HTTP server; all commands that actually need to work go over the WS instead.
- **RTSP video:** `rtsp://<ip>:8554/<stream_name>` (stream name comes from `stream_ready` event)

### Incoming WS message types (handled in `handleWebSocketMessage`)
- `sensor_data` — nested `ddl_frame` with `distance` / `temperature_humidity` / `servo` / `gps` / `compass` / `wind` sub-frames. Each sub-frame has a `valid` flag (except `servo`, which has none, and `wind`, which has two: `speed_valid` and `direction_valid` independently). Dashboard hides values when `valid=false`. Wind speed + direction are shown on the bottom sensor strip; compass + servo are parsed but NOT displayed (kept for the future ballistics calculator). See `SensorData.kt` for the helper extensions (`distanceM()`, `gpsLatLon()`, `windSpeedMps()`, `windDirectionDeg()`, `compassHeadingDeg()`, etc.) — always use them, don't access nested fields directly.
- `target_detection` — array of `{id, class, confidence, bbox{x,y,width,height}}` in pixel coords against a **1920×1080** video frame. Hardcoded resolution in `TacticalVideoPlayer.kt` (`VIDEO_WIDTH/VIDEO_HEIGHT`).
- `shooting_solution` — `{targetId, azimuth, elevation, windageAdjustment, elevationAdjustment, confidence, timestamp}`.
- `stream_ready` — `{rtsp_port, stream_name}` → builds `rtspStreamUrl` and flips `streamReady`.
- `system_status` — direct deserialize into `SystemStatus`.
- `acoustic_event` — `{type, timestamp_us, azimuth_deg, confidence, peak_amplitude, duration_ms, valid}`. Single TDOA detection from the Pi's 4-mic module. `azimuth_deg` is in the **mic-array's own frame** (the array is bolted to the fixed tripod, doesn't move with the servos). World bearing is computed via `worldBearingFromAcousticEvent(event, tripodWorldBearingDeg)` where the second argument is the operator-calibrated `tripodWorldBearingDeg` (NOT the live compass — the compass is on the moving head, so it can't tell us where the mic array is pointing once the head has moved). Until calibration runs (or after it expires), the world bearing is null and the alert shows the RELATIVE mic angle instead — the alert still fires and SLEW still works (slew only needs the raw azimuth + 90° → servo).

### Detection pipeline (do not break this)

Raw WS detections do NOT go straight to UI state. They flow:

1. **`detectionQueue` (Channel)** — receives every burst from the Pi (Pi can dump 130 detections in 300ms).
2. **`startDetectionPacer()` coroutine** — drains the channel to the **most recent** item only (coalesces bursts), then enforces `PACER_MIN_OUTPUT_INTERVAL_MS = 100ms` between emissions.
3. **`matchAndSmooth()` (IoU tracker)** — greedy IoU matching (threshold `0.3`) between incoming bboxes and currently-tracked targets. Assigns stable visual IDs (`T1`, `T2`, ...) that **persist across frames**, because the Pi's own `id` swaps between people when it relabels by confidence rank.
4. **EMA smoothing** — bbox + confidence smoothed with `alpha = 0.7` (high responsiveness, light jitter reduction).
5. **Staleness watchdog (`startStalenessChecker`)** — clears bboxes if no WS detection received for `5s` (so the overlay doesn't freeze if the detector dies).
6. **Tracked-target timeout** — a tracked target survives `600ms` without a match before being dropped.
7. **WS keepalive** — pings every `20s` to prevent NAT idle timeout. Unknown message types fall through silently on the C server, so the Pi doesn't need to handle "ping".

If you change any of these constants, search for the JSDoc comments above them — there's design rationale to preserve.

### Mock fallback

If `connectWebSocket` throws OR `connect()` itself fails, `startMockDataGeneration()` runs a 1.5s tick that fakes sensor data + 2 targets (T1 HUMAN, T2 VEHICLE) + a random shooting solution. Useful for UI dev without the Pi.

There's also a `MockVideoFeed` that plays `res/raw/field_video.mp4` when no RTSP is available.

### WiFi binding (`WifiBinder`)

The RPi5 AP "MyHotspot" has **no internet upstream**. Android prefers cellular when WiFi has no internet, so all RPi sockets get routed to cellular and fail. `WifiBinder.bindToWifi()` requests a WiFi network with `removeCapability(NET_CAPABILITY_INTERNET)` and binds the process to it. `getGatewayIp()` reads the DHCP gateway (little-endian int → IPv4 string), falling back to `10.42.1.1`. `release()` is called on disconnect and in `onCleared()`.

## UI conventions

- **Palette access:** `val t = LocalTactical.current` at the top of every composable. Never hardcode colors. Use `t.base / t.panel / t.ink / t.accent / t.on / t.danger / t.bboxTracked / t.bboxLocked` etc.
- **Dark/light awareness:** `LocalIsDarkTheme.current` for non-palette decisions (e.g. picking dark vs light Google Maps JSON).
- **Bbox colors:** `bboxTracked` (orange) and `bboxLocked` (red) are intentionally **identical hex in both palettes** because they sit over the live video, not the UI.
- **Typography:** Use `JetBrainsMono` for tactical/monospace text (HUD chips, status), Inter for body. Both exposed from `theme/Type.kt`.
- **Shared components:** `TacticalComponents.kt` exports `TopBar`, `Chip`, `ChipTone`, `Bracket` button, `Lbl`, `ThemeToggle`, `TopBarIconButton`. Reuse these — don't rebuild them per screen.
- **Compact layout:** Use `isCompactWidth()` / `responsiveDp(tablet, compact)` to branch on phone-landscape (<840dp). Default reference size is 1280×800.
- **Deprecated color aliases:** `MilitaryDarkBackground`, `StatusConnected`, etc. in `Color.kt` are legacy — do not use in new code, and prefer migrating any usage you touch.

## Permissions (manifest)

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `RECORD_AUDIO`, `WAKE_LOCK`, `VIBRATE`. Cleartext traffic enabled (`usesCleartextTraffic="true"`) — required for the RPi's plain HTTP/RTSP.

Runtime permission flow: `MainActivity.ensureLocationPermission()` checks `ACCESS_FINE_LOCATION`; on grant it calls `viewModel.onLocationPermissionGranted()` which starts the `DeviceLocationProvider`. Deny path leaves `userLocation` null forever (map hides marker; future ballistics degrades gracefully).

## Build / run

- Open in Android Studio (project uses AGP 8.12.1 → Studio Hedgehog+ or newer).
- Add `MAPS_API_KEY=AIza...` to `local.properties` (gitignored).
- Run on a landscape Android 8.0+ device. Phone works but tablet is the target.
- For RPi testing: phone must be joined to the RPi's WiFi AP (`MyHotspot`, gateway `10.42.1.1`). WifiBinder handles routing.
- For UI-only dev: just run and ignore connection errors — mock data kicks in automatically after a few seconds.

## Known gotchas

- **No `versionCatalog` discipline** — `app/build.gradle.kts` hardcodes most versions instead of using `libs.versions.toml`. Catalog has Hilt/serialization entries that are unused. If you migrate to catalog, also remove the duplicates from `app/build.gradle.kts`.
- **Two ExoPlayer generations coexist** — `com.google.android.exoplayer:exoplayer 2.19.1` AND `androidx.media3:media3-exoplayer 1.2.0`. New code should use Media3.
- **`testHttpApi` and `NetworkTester` are unused** — kept around for future diagnostic UI work. The commented block in `RaspberryPiClient.connect()` shows the original gated-by-port-scan flow.
- **`SystemStatus` from WS doesn't match `SystemStatus` data class precisely** — Gson parses field-by-field, missing fields become defaults. If you add fields, double-check both sides.
- **Hardcoded video resolution** — 1920×1080 in `TacticalVideoPlayer.kt`. If the Pi ever changes resolution, this breaks bbox scaling.
- **Stream resilience lives in `TacticalVideoPlayer.kt`** — the RTSP session wedges after the Pi soft-AP's periodic Wi-Fi blackouts (frames stop even though transport recovers) and ExoPlayer won't self-heal. A stall watchdog polls playback progress every 500 ms and, if it hasn't advanced for `STALL_TIMEOUT_MS` (3 s) while playing, rebuilds the RTSP source (`buildRtspMediaSource`) with exponential backoff — doing programmatically what a manual back-out-and-reconnect does, and resetting accumulated latency. Transport is forced to **RTP-over-TCP** (`setForceUseRtpTcp(true)`); a UDP A/B toggle is a planned follow-up. `WifiPerfLock` (FULL_LOW_LATENCY WifiLock + wake lock) is held for the life of the player to fight client-side Wi-Fi power-save. **Video is decoupled from the WS control channel**: a WS close no longer clears `rtspStreamUrl`/`streamReady` (only an explicit `disconnect()` does), and the player loads/watches on `streamReady` alone — so a control-channel blip can't tear down video. The dashboard shows two separate chips: **LINK** (WS/control state) and **VIDEO** (driven by real frame flow via the player's `onVideoHealthChanged` callback, not WS state).
- **`previousScreen` nav is a hack** — manual back-stack tracking instead of Nav Compose. Tolerable for 5 screens, would need replacing if nav gets richer.
- **Almost no tests** — `ExampleInstrumentedTest`/`ExampleUnitTest` are unmodified AS templates. The one real suite is `TargetLocalizerTest` (pure-math geodesy for the ballistics localizer).
- **`RigGeometry` constants reflect the real rig** — `COMPASS_ON_FIXED_BASE = false` (the compass is bolted to the moving camera arm, so its reading IS the camera's pointing direction — servo pan is NOT added on top), servo pan/tilt centered at 90°, declination 0, and `MIC_TO_SERVO_OFFSET_DEG = 90.0` (mic↔servo, used for the SLEW path; per Pi-side spec, mic 0° aligns to servo 90°). There's no compass↔mic-array constant — the acoustic-bearing path uses an operator-calibrated `tripodWorldBearingDeg` (set via MENU → Calibrate Bearing) instead of a static constant, because the compass is on the moving head while the mics are on the fixed tripod, so their relationship isn't a mechanical constant.
- **Hardcoded device list** — `availableDevices` in `SniperViewModel` is a fixed 4 entries. Real device discovery isn't implemented.
- **Strings are mostly inlined** — `res/values/strings.xml` only has `app_name`. Most UI strings (chip labels, button text, etc.) are hardcoded literals in Composables. Not translation-ready.
- **`compass.heading_deg` can be JSON `null`** — the Pi's C `build_json` emits the literal token `null` (not a number) when the magnetometer hasn't fixed yet. `CompassFrame.headingDeg` is therefore `Float?`. Always read it via `compassHeadingDeg()` which gates on both `valid` and non-null; never treat a missing heading as `0°` (true north).

## Maintenance — keep this doc current

When you (Claude or human) make changes, update the relevant section here in the same commit. Specifically:

- **Add a new screen?** Update "Repo layout" + "Navigation" + the `AppScreen` enum reference.
- **Add a new WS message type?** Update "Incoming WS message types" with the exact JSON shape.
- **Change the detection pipeline constants?** Update the "Detection pipeline" numbered list.
- **Add a new dependency?** Update "Tech stack" and note whether it's via the catalog or hardcoded.
- **Add a new permission?** Update "Permissions (manifest)".
- **Discover a new gotcha?** Add it to "Known gotchas". Remove gotchas as they get fixed.
- **Refactor architecture (e.g. add Hilt, switch to Nav Compose, add DataStore)?** Update "Architecture & data flow" and bump the relevant subsections.

The goal: any new Claude session that reads this file should be able to make a sensible code change in 5 minutes without re-exploring the codebase. If you find yourself re-exploring something you "should have known", that's a signal this doc is missing it — add it.
