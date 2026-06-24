# Smartphone INS

Android 13+ phone INS demonstrator with a 100 Hz binary output stream, WGS84/NED output, automatic or manual initialization, deterministic replay, explicit stationary constraints, and simulator-focused UI validation.

Version 0.3.4 adds default-on CSV trial logging for field evaluation. Every navigation-service run creates a dated file named like `phoneins_trial_YYYYMMDD_HHMMSS.csv` and logs one row per 100 Hz output tick with INS solution, bias estimates, raw IMU, and GNSS data. A MATLAB post-processing script is available at `tools/matlab/plot_phone_ins_log.m`.

On Android devices, CSV logs are written under the app-specific Documents folder:

```text
/sdcard/Android/data/com.example.phoneins/files/Documents/trial_logs/
```

For example, retrieve logs with:

```bash
adb pull /sdcard/Android/data/com.example.phoneins/files/Documents/trial_logs/
```

Version 0.3.3 added a deliberate 15-second automatic initialization window. During that window, the app averages the gravity vector for coarse roll/pitch alignment, reports progress on the Dashboard, and clearly shows whether initialization is not completed, in progress, or complete. Magnetometer use remains limited to initial yaw, and moving heading is still corrected through GNSS course aiding.

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

Version 0.3.4 passes the navigation and protocol unit-test suite and builds the engineering APK. The previous 0.3.1 managed-device run passed all 14 API 36 Medium Phone tests, delivering 500 consecutive frames at 100.606 Hz with zero sequence loss or queue drops. In addition to route/history assertions, a rendered-pixel regression requires a real multi-segment INS polyline, preventing a history counter or marker from masquerading as a working plot.

The stationary navigation regression runs 12,001 biased IMU samples at 100 Hz with 121 noisy valid 3D GNSS fixes at 1 Hz. It asserts bounded position and zero velocity plus less than 0.2° roll, pitch, and yaw change after settling. A separate regression now verifies that a stationary phone's reported Euler roll and pitch change when the phone is physically rolled or tilted. The Decimeter route report uses a handheld-attitude acceptance band instead of requiring roll/pitch to remain frozen. Reports are generated under `navigation/build/reports`.
