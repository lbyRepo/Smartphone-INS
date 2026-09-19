# Navigation reliability improvements — 2026-09-19

Scope: improvements developed on `master` version 0.3.5 and released as 0.3.6
(Android version code 10). The separate
`feature/master-ins-transfer-alignment` branch is not modified or merged.

## Changes

- GNSS velocity updates use only available, finite NED measurement axes. Missing
  Down is not treated as a zero-velocity observation. Automatic initialization
  uses finite priors, and reacquisition cannot rotate a missing Down component
  into all three ECEF components. Non-finite innovations are rejected.
- Covariance propagation now uses `F_v,theta = -[C_b^e f^b]x`, consistent with the
  existing left-multiplicative ECEF attitude correction. Numerical finite
  differences test the convention. Sequential scalar measurement updates account
  for preceding state corrections; NED updates use expanded Joseph form, including
  when attitude gain is suppressed.
- Position/velocity standard deviations use `sqrt(diag(C_e^n P (C_e^n)^T))`.
  Attitude uncertainty uses the local rotation-to-Euler Jacobian rather than
  labelling ECEF rotation uncertainty as roll/pitch/yaw uncertainty.
- Live CSV and binary logging have independent, bounded, background writers.
  Queue saturation drops the newest record and is visible in the UI. Open, write
  and flush failures are contained and reported, not thrown into the publisher.
  File shutdown drains the queue with a two-second wait limit. Reprocessing
  remains synchronous/lossless on its separate replay worker.
- IMU samples use a causal accelerometer hold limited to 20 ms. Missing, stale,
  future-relative-to-gyro, or non-finite acceleration is not integrated as fresh
  IMU. Out-of-order gyro events are discarded. Raw Data shows separate live
  accelerometer and gyro ages. Existing CSV quality flags preserve HELD samples
  during reprocessing; older logs without that optional column still load.
- README distinguishes the reference-driven Pixel 4 simulation from independent
  raw-sensor navigation accuracy validation.

## Compatibility

No binary frame, CRC, CSV column or dataset changes. The expected
navigation solution and uncertainty values can change because the filter math
has changed. Old trajectory figures must not be assumed to describe this build.

## Verification

- Navigation, protocol and app JVM tests: 37 passed, including 12 new cases.
- Debug APK and Android-test APK: assembled successfully.
- Protocol golden-frame tests remain passing.
- Medium Phone API 36.1 emulator: 27/27 service, replay and Compose tests passed.
  The service test decoded 500 consecutive CRC-valid TCP frames with continuous
  sequence numbers, a measured rate within 98–102 Hz, and no transport drops.
- 200% system font scale: 21/21 UI tests passed. The first run exposed test
  assumptions that lazy-list content was already on screen and that the service
  badge always used its full-size text. Tests now explicitly scroll to the content;
  compact service badges have full accessibility descriptions. Original font
  scale restored after testing.
- `git diff --check`: passed before the release commit.

## Remaining limits

- Phone hardware, sensor latency, thermal behavior and field accuracy still need
  measurement on physical devices; emulator rates are not hardware guarantees.
- The 20 ms IMU hold limit is explicit and tested, but should be evaluated against
  actual callback timing on supported phones. This is bounded causal pairing,
  not interpolated IMU resampling.
- Dropped queue records and failed logging are visible; the app cannot recover
  samples lost because of storage failure or recover sensor samples never logged.
- No independent reference/phone field dataset was supplied for accuracy testing.
- Transfer-alignment convergence thresholds and misalignment recovery require
  separate validation on the feature branch before merging; this work does not
  certify its current 28-degree yaw-uncertainty acceptance threshold.
