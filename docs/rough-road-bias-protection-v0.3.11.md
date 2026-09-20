# Rough-road bias protection — v0.3.11

Road vibration is not a rapidly varying sensor bias. Increasing bias random-walk
noise can make a filter absorb transient residuals into biases, leaving a bad
correction when GNSS aiding is switched off. This revision restores the earlier
bias noise amplitudes and qualifies bias learning instead.

## Filter changes

- `ACCEL_BIAS_PROCESS_NOISE_MPS2_PER_SQRT_SECOND = 0.002`.
- `GYRO_BIAS_PROCESS_NOISE_RADPS_PER_SQRT_SECOND = 0.0002`.
- Their squares multiplied by `dt` enter the Q bias diagonals. Attitude process
  noise is unchanged at `0.004`.
- `ImuQualityMonitor` examines a 500 ms window, requiring at least 20 fresh samples
  spanning 400 ms. It holds learning on acceleration-vector jumps above 2 m/s²,
  rate-vector jumps above 0.15 rad/s, acceleration RMS above 0.8 m/s², angular-rate
  RMS above 0.08 rad/s, gravity-norm deviation above 1 m/s², or rate above 0.35 rad/s.
- Clipping, non-finite/stale samples, reversed timestamps and gaps above 50 ms also
  hold learning. Recovery requires two seconds of qualified windows. A measurement
  timestamp must be within 100 ms of the latest IMU sample to teach biases.
- Android samples at 98% of a sensor's reported maximum range are marked as
  possibly clipped. This is a warning, not proof of saturation. Clipped samples
  cannot enter initial bias calibration.
- When held, accelerometer and gyro bias gain rows are zeroed **before** the
  expanded Joseph covariance update. Valid GNSS position and velocity updates
  remain active; bias uncertainty continues propagating. Finite fresh motion
  samples are still integrated: no median filter or spike deletion is applied.
- This filter already decouples GNSS corrections from attitude. Gyro bias gain
  rows now remain zero for GNSS/ZUPT measurements even in clean intervals, avoiding
  indirect learning from attitude-related velocity errors. Gyro bias can still be
  estimated during initialization and by the clean, confirmed-stationary gyro
  mean. This deliberately sacrifices in-motion gyro-bias adaptation.
- Gravity tilt correction requires clean, IMU-steady data plus GNSS-confirmed
  stillness, or fresh steady-velocity evidence. Moving evidence requires two fixes
  0.1–1.5 s apart, speed uncertainty at most 0.5 m/s, velocity-change acceleration
  below 0.3 m/s², and body rate below 0.08 rad/s. It expires after 1.2 s and is not
  used during forced GNSS outage. This is a conservative heuristic, not a general
  dynamic attitude observer.

## Display and compatibility

The Raw Data page reports held/clean bias-learning status. Existing `quality_flags`
in CSV and the 224-byte USB/TCP frame carry bit 17 (`BIAS_LEARNING_HELD`) and bit 18
(`IMU_CLIPPED`). Bit 17 denotes the quality hold on all bias learning; absence does
not mean in-motion gyro adaptation is enabled. No columns or frame bytes were
added. CSV reprocessing restores clipping metadata when present and recomputes
the learning gate; older logs lack clipping metadata and default to not clipped.

## Validation and limitations

Regression coverage includes shock/RMS vibration, timing faults, clipping,
clean recovery, bias-gain isolation with continued GNSS corrections, covariance
symmetry/nonnegative sampled quadratic forms, integration of real acceleration
without false gravity tilt, and a rough/clean synthetic comparison followed by
a five-second outage. The existing route/attitude/reacquisition test is retained
without relaxing its acceptance criteria.

The reference-route regression still exhibits substantial dead-reckoning error:
222.8 m maximum INS–GNSS separation in its 20-second outage, recovering to 0.79 m
after three reacquisition fixes. Passing its bounded-attitude/reacquisition checks
does not establish improved outage accuracy. The synthetic bump test isolates bias
contamination; real trial comparisons remain necessary.

## Map UI adjustment in this build

Street-map zoom buttons use separate 48 dp targets in a right-side column, not
osmdroid's bottom overlay. Attribution remains fully visible and linked, in a
compact 12 sp footer below the map. Plus/minus are vector strokes so large system
font settings cannot clip them. Pinch zoom and Fit track are retained.

## Verification record

- 60 navigation/protocol/app JVM tests passed.
- Full 34-test API 36.1 emulator suite passed, including 100 Hz TCP streaming,
  replay, initialization, bias-status text and map controls at 200% font scale.
- 54 Compose screenshot checks passed.
- After visually detecting native tile drawing outside its layout, added explicit
  clipping; reran the two map-control emulator tests, app unit tests, screenshot
  checks and debug assembly successfully.
- Earlier interrupted/loaded-emulator runs had initialization timing failures;
  the complete rerun passed. The new bias-status test initially omitted the
  initialized quality bit in its fixture; that fixture was corrected.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (version 0.3.11, code 15).
- Emulator screenshots: `outputs/map-controls-v0.3.11-1.0.png` and
  `outputs/map-controls-v0.3.11-2.0.png`.
- No physical bicycle validation or Git commit was performed.

These are engineering safeguards, not demonstrated bicycle-navigation accuracy.
Clipped/aliased motion cannot be recovered; persistent acceleration can still be
hard to distinguish from gravity or bias. Mount rigidity, GNSS latency/lever arm,
heading observability and sensor temperature also matter. Field logs are needed
to select thresholds and compare outage errors against the previous build.
