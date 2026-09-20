# Smartphone INS

Android smartphone INS/GNSS demonstrator for rapid navigation prototyping.

This project turns an Android phone into a compact inertial-navigation experiment platform: it reads onboard IMU, magnetometer, and GNSS measurements; performs INS/GNSS fusion; displays the live solution; streams a 100 Hz binary output; and logs trial data for post-processing.

It was built as a rapid AI-assisted engineering prototype: the emphasis is on moving quickly from idea to working demonstrator while keeping the implementation testable, inspectable, and honest about phone-grade sensor limitations.

## GitHub description

> Android INS/GNSS demonstrator that reads phone IMU/GNSS sensors, outputs a 100 Hz navigation stream, logs CSV trial data, and supports replay/post-processing.

## Features

- Android 13+ Kotlin / Jetpack Compose app.
- Foreground navigation service for continued sensor processing.
- Raw accelerometer, gyroscope, magnetometer, and GNSS display.
- WGS84 position, NED velocity, Euler attitude, bias, uncertainty, timing, and quality-state display.
- Position map with a street-map basemap (cached online raster tiles, © OpenStreetMap contributors) or a plain grid, INS/GNSS trails, heading arrow, and scale bar.
- Transfer alignment (master INS): in Setup, the Master INS mode initializes the slave from a master INS over the MST1 TCP protocol (port 29501), drives position from the master, and velocity-matches master velocity/attitude to estimate the mounting angle. Promotion to ready is covariance-driven, not time-boxed; GNSS resumes only once ready.
- Optional causal IMU smoothing window for mechanization (`NavigationEngine.IMU_SMOOTHING_WINDOW_SAMPLES`, default `1` = disabled). Quality and stillness monitoring always use raw samples. A/B outage verification showed neutral-to-slightly-worse drift, so it is retained only as a diagnostic knob, not as a drift fix.
- Automatic initialization with a 15-second stationary window using gravity for coarse roll/pitch alignment.
- Manual initialization with user-entered WGS84 coordinates and Euler angles.
- Heading strategy that avoids continuous magnetometer dependence:
  - magnetometer may initialize yaw;
  - moving heading corrections are driven by GNSS velocity/course aiding.
- Vehicle and pedestrian motion profiles.
- 100 Hz binary INS output over TCP and Android Open Accessory-oriented transport.
- Default-on CSV trial logging for field evaluation.
- On-device CSV trial reprocessing with recent-log selection, file import, seeking, and variable playback speed.
- Deterministic replay/simulator support for repeatable UI and navigation testing.
- Python receiver and MATLAB plotting script for downstream analysis.

## Project status

Current app version: `0.3.19`.

Version 0.3.19 repairs coupled estimation in the 15-state ECEF error-state filter. Previously,
bias learning was an on/off switch: while the IMU quality monitor withheld learning (rough road,
vibration, clipped or aliased samples, recovery), the attitude/bias correction channel was
suppressed with it, so the filter could stop observing its own mounting and then integrate a
mounting error as motion. Learning is now an explicit gain policy. The strict IMU-quality gate
gives full gains; physically plausible rough motion inside a 180 s settling window after
initialization gets capped adaptation (accelerometer bias at `0.4` gain and only in quasi-steady
motion, gyro bias at full gain); anything else holds the bias gains exactly while position,
velocity and attitude observations stay coupled in Joseph form under the existing 99.9 % NIS
gates. At standstill the gravity observation corrects attitude only, because tilt and
accelerometer bias are not separable there. No process-noise amplitude, gate, threshold or outage
length was changed, and no covariance was made artificially more certain.

Outage acceptance for this version: **three of the four mandated 45 s windows pass, one fails** —
the failing synthetic window is reported rather than relaxed. Pre-repair figures below come from
the archived reports in `docs/` (same trials and forced outages, anchors as recorded there).
Bicycle anchor 110.248 s: 95.04 m /
24.5°, 0 violations (before: 1656.26 m / 179.2°). Van anchor 600 s (near-vertical mount): 65.86 m
/ 11.1°, 0 violations (before: 755.11 m). Van anchor 900 s: 72.28 m / 13.9°, 0 violations (before:
1025.54 m / 87.7°). Synthetic `DecimeterRouteScenario` 100–145 s: 242.76 m / 37.96°, 4 of 42
violations (before: 567.65 m / 176.9°, 35 violations) — the residual is a ~0.2 m/s² along-track
error that the route's closing stop exposes, because outage isolation clears the stationary latches
by design. Full evidence, plots and diagnosis:
[filter repair verification](outputs/filter-repair-v0.3.19/VERIFICATION.md).

Street-map zoom controls now use small transparent icons in the grey footer below
the map while retaining 48 dp touch targets. OpenStreetMap attribution remains on
the footer's right side, leaving the map itself unobstructed.

The deterministic simulator now stays stationary until **Setup → Start simulated
motion** is pressed after initialization. **Init → Reset filter** restarts its
stationary state. Simulator truth starts at 1.3521° N, 103.8198° E, 25 m, level and
north-facing; use that pose for manual simulator alignment. Manual bias estimation
requires at least 3 seconds / 300 fresh, stable IMU samples and rejects known motion.
IMU-only stillness cannot distinguish rest from constant translational velocity.

Bias process noise is configured in `NavigationEngine` by
`ACCEL_BIAS_PROCESS_NOISE_MPS2_PER_SQRT_SECOND` (`0.002`) and
`GYRO_BIAS_PROCESS_NOISE_RADPS_PER_SQRT_SECOND` (`0.0002`). Version 0.3.11 restores
these pre-tuning amplitudes: bumps should not be treated as rapid bias drift.
Their squares times `dt` enter the corresponding Q diagonals. Attitude amplitude
remains `0.004`. A rolling IMU quality check pauses bias learning during rough
motion, possible clipping, timing faults and clean-data recovery while retaining
valid GNSS position/velocity corrections. Gravity tilt aiding requires clean,
GNSS-confirmed stationary or low-acceleration steady motion. Gyro bias learning
requires confirmed stillness; it no longer follows moving GNSS residuals in this
attitude-decoupled filter. Raw shows bias-learning status; CSV/USB quality
bits 17/18 carry hold/clipping information without changing frame size or columns.
See [rough-road protection](docs/rough-road-bias-protection-v0.3.11.md) for limits.

Version 0.3.9 replaces the Dashboard initialization banner with one compact,
colour-coded indicator and a plain GNSS aiding switch. Alignment details remain
on Setup; logging details remain in the Setup trial logging section.

Version 0.3.8 streamlines the Dashboard, adds a landscape map/summary layout,
labels individual measurement axes, and distinguishes automatic CSV logging from
optional binary recording. Uninitialized uncertainty is explicitly marked rather
than displayed as zero.

Street view uses standard OpenStreetMap raster tiles, with visible attribution,
an identifying user agent, and local caching. It requires internet for uncached
areas and is a best-effort service, not a guaranteed offline map. No bulk tile
downloads are supported. If loading fails, choose **Use Grid instead**; navigation
and logging continue independently. The standard map is light-coloured in both
app themes. See the [tile usage policy](https://operations.osmfoundation.org/policies/tiles/).

This is an engineering demonstrator, not a certified navigation product. The emulator and replay tests validate app behavior, UI state transitions, protocol handling, logging, and deterministic scenarios. Actual sensor quality, thermal behavior, sustained rates, USB behavior, and navigation performance must be validated on physical phones and real routes.

## Repository layout

```text
.
├── app/          Android application, foreground service, Compose UI
├── navigation/   INS/GNSS fusion models, navigation engine, replay tests
├── protocol/     Binary protocol encoder/decoder and golden-frame tests
├── tools/
│   ├── receiver/ Python TCP/AOA decoder and CSV logger
│   └── matlab/   MATLAB post-processing plots for logged CSV files
└── docs/         Protocol, verification notes, datasets, hardware guidance
```

## Navigation algorithm map

If you want to inspect or modify the navigation solution, start with these four files:

- [`NavigationEngine.kt`](navigation/src/main/kotlin/com/example/phoneins/navigation/NavigationEngine.kt) — 15-state ECEF error-state EKF, INS propagation, initialization, GNSS measurement corrections, stationary constraints, and course-based heading aiding.
- [`NavigationMath.kt`](navigation/src/main/kotlin/com/example/phoneins/navigation/NavigationMath.kt) — WGS84/ECEF/NED coordinate transformations, quaternion and matrix operations, gravity, and attitude calculations.
- [`Models.kt`](navigation/src/main/kotlin/com/example/phoneins/navigation/Models.kt) — IMU, magnetometer, GNSS, initialization, quality, and navigation-solution data types.
- [`DecimeterRouteScenario.kt`](navigation/src/main/kotlin/com/example/phoneins/navigation/DecimeterRouteScenario.kt) — deterministic public-dataset replay support used by the debug app and regression tests; it is not part of the core fusion algorithm.

## Requirements

- Android Studio Narwhal 2025.1.4 or newer.
- Android SDK 36.
- JDK 21.
- Android device or emulator running Android 13+ (`minSdk 33`).
- Optional: MATLAB for post-processing logged trial CSV files.

The project was developed with:

- `compileSdk 36`
- `targetSdk 36`
- `minSdk 33`
- Kotlin / Jetpack Compose
- Gradle wrapper included in the repository

## Build

From the repository root:

```bash
./gradlew :navigation:test :protocol:test :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a broader local verification pass:

```bash
./gradlew :navigation:test :protocol:test :app:validateDebugScreenshotTest :app:assembleDebug
```

## Run on a phone

Install the debug APK with Android Studio or ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then:

1. Open **Smartphone INS**.
2. Grant location and notification permissions when prompted.
3. Start the foreground service.
4. Choose automatic initialization or enter manual initialization values.
5. Keep the phone still during the 15-second automatic initialization window.
6. View the Dashboard (INS), Raw Data, and Setup screens. Initialization, sensor source, trial logging, replay and connection details live in Setup.

For indoor demos where GNSS is weak, use the deterministic replay mode so the UI, logging, and output pipeline can still be exercised repeatably.

## CSV trial logging

CSV logging is enabled by default when the navigation service starts. Each service run creates a dated file:

```text
phoneins_trial_YYYYMMDD_HHMMSS_SSS.csv
```

On Android devices, logs are written under the app-specific Documents directory:

```text
/sdcard/Android/data/com.example.phoneins/files/Documents/trial_logs/
```

Retrieve logs with:

```bash
adb pull /sdcard/Android/data/com.example.phoneins/files/Documents/trial_logs/
```

Each CSV row corresponds to the app’s 100 Hz output stream and includes:

- INS solution: latitude, longitude, altitude, velocity, Euler angles, uncertainty, quality flags.
- Estimated accelerometer and gyroscope biases.
- Raw IMU measurements.
- GNSS solution, fix age, accuracy, velocity, satellite count, and validity flags.

### Reprocess a logged trial

Open **Setup → CSV trial replay** to select one of the recent completed logs, or choose a CSV through Android's document picker. The app rebuilds the navigation solution from the logged raw IMU and GNSS using the current algorithm.

Replay supports pause, seek, restart, and `0.5×`, `1×`, `2×`, and `10×` speeds. The Dashboard, Raw Data page, position map, TCP, and USB output follow the reprocessed solution. Results are saved separately without modifying the source log:

```text
phoneins_reprocessed_<source-name>_YYYYMMDD_HHMMSS_SSS.csv
```

After replay completes, the final state remains visible. Use **Return to live sensors** to restore the previous live or simulated source and initialize it again.

## MATLAB post-processing

Use the MATLAB script:

```text
tools/matlab/plot_phone_ins_log.m
```

Example:

```matlab
plot_phone_ins_log("phoneins_trial_20260623_201139.csv")
```

The script creates:

1. A 3 × 3 INS solution figure:
   - latitude, longitude, altitude;
   - velocity north/east/down;
   - roll, pitch, yaw.
2. A 2 × 3 raw IMU figure:
   - accelerometer x/y/z;
   - gyroscope x/y/z.
3. A 2 × 3 INS-versus-GNSS comparison:
   - latitude, longitude, altitude;
   - velocity north/east/down.

## 100 Hz protocol and receiver

The app publishes a versioned binary INS frame at 100 Hz. The protocol includes sequence tracking and CRC checking.

See:

- `docs/protocol.md` for the binary frame specification.
- `docs/golden-frame-v1.bin` for the golden binary frame.
- `tools/receiver/phone_ins_receiver.py` for the Python decoder / CSV logger.

Typical TCP receiver workflow:

```bash
adb forward tcp:29500 tcp:29500
python3 tools/receiver/phone_ins_receiver.py --tcp 127.0.0.1:29500 --csv out.csv
```

## Testing

Unit and protocol tests:

```bash
./gradlew :navigation:test :protocol:test
```

Build the app:

```bash
./gradlew :app:assembleDebug
```

Run connected Android tests on an available emulator/device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Run screenshot validation:

```bash
./gradlew :app:validateDebugScreenshotTest
```

The test suite includes deterministic navigation/replay checks, stationary alignment regressions, protocol golden-frame checks, UI state tests, and screenshot baselines covering light/dark themes, orientation, and font-scale variants.

## Hardware validation notes

The bundled Pixel 4 route replay is a **reference-driven simulation**, not an independent
phone-accuracy benchmark. `DecimeterRouteScenario` synthesizes the IMU from the reference
route and supplies reference-derived GNSS fixes. Its error plots demonstrate filter and
outage behavior under those assumptions; they must not be presented as measured Pixel 4
navigation accuracy. An independent assessment requires synchronized recorded raw IMU/GNSS
and a separate reference trajectory, including mounting, timing and lever-arm calibration.

Live CSV and binary recording use bounded background queues (1,000 records per file).
Slow storage cannot block the 100 Hz publisher: queue overflow drops the newest record
and increments **Dropped log records** on the Setup page. Storage failures stop the affected
logger and display **Logging error** without stopping navigation. A shutdown drains accepted
records, waiting up to two seconds; an unfinished drain is reported and the file may still
be incomplete. CSV algorithm replay remains lossless and writes synchronously on its own
worker. The CSV columns and INS1 binary layout are unchanged.

Live IMU pairing uses a causal accelerometer hold of at most 20 ms. Older/missing samples
are marked HELD and are not integrated. The Raw Data page shows separate live accelerometer
and gyroscope ages. Reported position/velocity uncertainty is transformed from ECEF into
NED, and attitude uncertainty into Euler angles (which become ill-conditioned near ±90° pitch).

Simulator validation is useful for application behavior, but it is not authoritative for physical sensor performance. For real-world use, validate on actual phones, including:

- IMU and GNSS availability.
- Screen-off foreground operation.
- Thermal behavior during sustained operation.
- Sustained fresh-sample rate.
- 30-minute 100 Hz streaming.
- TCP over real USB tethering.
- AOA attach, permission, detach, and reconnect behavior.
- Representative vehicle and pedestrian routes.

Do not assume a universal phone-grade navigation accuracy guarantee. Navigation performance depends strongly on device sensors, mounting, motion profile, GNSS conditions, thermal behavior, and route dynamics.

## Notes on AI-assisted workflow

This repository was developed as a rapid prototyping exercise using an AI-assisted engineering loop:

```text
idea → implementation → build/test → emulator inspection → bug fix → repeat
```

The intent was not to remove engineering judgment, but to compress the time between concept, working prototype, validation evidence, and the next experiment.
