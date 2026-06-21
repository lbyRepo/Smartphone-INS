# Smartphone INS

Android 13+ phone INS demonstrator with a 100 Hz binary output stream, WGS84/NED output, automatic or manual initialization, deterministic replay, and simulator-focused UI validation.

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
