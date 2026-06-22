# Smartphone INS

Android 13+ phone INS demonstrator with a 100 Hz binary output stream, WGS84/NED output, automatic or manual initialization, deterministic replay, explicit stationary constraints, and simulator-focused UI validation.

Version 0.2.1 adds robust per-axis GNSS update acceptance, consistent-fix position/velocity reacquisition, stationary position/velocity/attitude constraints, and an offline Dashboard map comparing INS and raw GNSS tracks. The UI only says `GNSS-aided` when the required correction axes were actually applied; a rejected correction is shown explicitly. While both GNSS and IMU indicate rest, the filter learns gyro bias and holds attitude so roll, pitch, and yaw cannot spin through GNSS/ZUPT covariance coupling; detected motion releases that hold immediately.

Open this folder in Android Studio Narwhal or build from the terminal:

```bash
./gradlew :navigation:test :protocol:test :app:validateDebugScreenshotTest :app:assembleDebug
```

The engineering APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. The 48 host-rendered visual baselines live under `app/src/screenshotTestDebug/reference` and cover every primary screen in light/dark, portrait/landscape, and 100/130/200% font scale. See `docs/protocol.md` for the wire format and golden frame, and `tools/receiver` for the reference TCP/AOA decoder.

To run the end-to-end Android tests on the installed `Medium Phone API 36.1` emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

For a separately provisioned Gradle-managed API 36 device:

```bash
sdkmanager --install "system-images;android-36;google_apis;arm64-v8a"
./gradlew :app:mediumPhoneApi36DebugAndroidTest
```

Those tests start the real foreground service, replay the packaged public Pixel 4 trace, assert raw IMU/magnetometer/GNSS reach application state, and measure 500 production TCP frames for runtime rate, sequence continuity, CRC, and queue drops.

Version 0.2.1 passes all 11 managed-device tests on the API 36 Medium Phone. The Android service delivered 500 consecutive frames at 99.964 Hz with zero sequence loss or queue drops. The map-origin test proves that the origin remains at initialization while the INS solution moves; see `outputs/emulator-runtime-v0.2.1.txt` in the packaged workspace deliverables.

The stationary navigation regression runs 12,001 biased IMU samples at 100 Hz with 121 noisy valid 3D GNSS fixes at 1 Hz. It asserts bounded position and velocity plus less than 0.2° roll, pitch, and yaw change after settling. Its report is generated at `navigation/build/reports/stationary-regression.txt`.
