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
- Automatic initialization with a 15-second stationary window using gravity for coarse roll/pitch alignment.
- Manual initialization with user-entered WGS84 coordinates and Euler angles.
- Heading strategy that avoids continuous magnetometer dependence:
  - magnetometer may initialize yaw;
  - moving heading corrections are driven by GNSS velocity/course aiding.
- Vehicle and pedestrian motion profiles.
- 100 Hz binary INS output over TCP and Android Open Accessory-oriented transport.
- Default-on CSV trial logging for field evaluation.
- Deterministic replay/simulator support for repeatable UI and navigation testing.
- Python receiver and MATLAB plotting script for downstream analysis.

## Project status

Current app version: `0.3.4`.

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
6. View the Dashboard, Raw Data, Initialization, and Sessions/Settings screens.

For indoor demos where GNSS is weak, use the deterministic replay mode so the UI, logging, and output pipeline can still be exercised repeatably.

## CSV trial logging

CSV logging is enabled by default when the navigation service starts. Each service run creates a dated file:

```text
phoneins_trial_YYYYMMDD_HHMMSS.csv
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
adb forward tcp:28500 tcp:28500
python3 tools/receiver/phone_ins_receiver.py --host 127.0.0.1 --port 28500 --csv out.csv
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
