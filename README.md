# Smartphone INS

Android 13+ phone INS demonstrator with a 100 Hz binary output stream, WGS84/NED output, automatic or manual initialization, deterministic replay, explicit stationary constraints, and simulator-focused UI validation.

Version 0.3.1 fixes a Dashboard rendering defect where history counters advanced but the Canvas kept displaying only a stationary marker. The map now hands immutable trail snapshots to an explicitly invalidated native plot surface, draws a solid INS history and dashed raw-GNSS history, fits the accumulated route, marks initialization/latest positions, and reports displacement from initialization. Its packaged Pixel 4 route replay still deliberately pauses GNSS aiding and reacquires it so divergence and correction remain visible.

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

Those tests start the real foreground service, replay the packaged 199-epoch Pixel 4 reference route, assert raw IMU/magnetometer/GNSS reach application state, exercise deliberate aiding suspension and reacquisition, and measure 500 production TCP frames for runtime rate, sequence continuity, CRC, and queue drops.

Version 0.3.1 passes all 14 managed-device tests on the API 36 Medium Phone. The Android service delivered 500 consecutive frames at 100.606 Hz with zero sequence loss or queue drops. In addition to route/history assertions, a rendered-pixel regression requires a real multi-segment INS polyline, preventing a history counter or marker from masquerading as a working plot.

The stationary navigation regression runs 12,001 biased IMU samples at 100 Hz with 121 noisy valid 3D GNSS fixes at 1 Hz. It asserts bounded position and zero velocity plus less than 0.2° roll, pitch, and yaw change after settling. The separate Decimeter route report proves roll/pitch remain within 0.4°/0.2° while heading changes by more than 177°. Reports are generated under `navigation/build/reports`.
