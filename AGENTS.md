# Repository instructions

## Commits and versions

- Use Conventional Commit messages, for example `fix(navigation): prevent invalid GNSS updates`.
- Every commit that changes application, navigation, protocol, or tooling code must also
  increment `versionCode` and update `versionName` in `app/build.gradle.kts`.
- Use a patch version increment for fixes unless the user requests a different version.
- Keep the current app version in `README.md` consistent with the Gradle configuration.
- Run relevant tests and assemble the debug APK after changing code or the app version.
- Historical artifact names and verification records should keep their original versions;
  do not rename old release artifacts to imply they were rebuilt.
- The main development branch is named `master`. Do not rename it or push commits unless asked.

## Emulator hand-off after each task

- When a task that changed app behavior is done, build the debug APK, install it on the running
  emulator with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, and launch
  `com.example.phoneins/.MainActivity` so the user can inspect the result.
- Re-grant runtime permissions with `adb shell pm grant` after a fresh install clears them
  (location fine/coarse, notifications).
- Verify the activity actually resumed (`adb shell dumpsys activity activities`) and say which
  screen or flow to look at; leave the emulator on that state when practical.

## Mandatory 45-second GNSS outage verification

Run this verification whenever changes affect sensor processing, initialization, navigation,
GNSS aiding, or replay. Use logged or playback sensor data and the current navigation algorithm.

### Procedure

- Reprocess raw IMU and GNSS measurements. Recorded INS outputs must not substitute for
  recomputing the navigation solution.
- Select a reproducible moving segment with valid GNSS reference measurements covering the
  full outage. Record the source file and chosen interval before evaluating results.
- Initialize and establish GNSS-aided navigation, then disable aiding for exactly **45 seconds
  of recorded sensor time**, independent of playback speed or wall-clock time.
- Continue collecting raw GNSS for comparison. Verify that it does not correct INS position,
  velocity, heading, or stationary constraints during the outage.
- Measure the outage endpoint before reacquisition, then restore aiding and verify that
  corrections resume.

### Acceptance criteria

- **Horizontal separation:** maximum INS–GNSS horizontal separation throughout the outage,
  including the endpoint before reacquisition, must be **≤150 m**. Compute errors at matched
  timestamps using WGS84 ECEF-to-local-ENU conversion and `sqrt(E² + N²)`.
- **Direction:** at each valid matched GNSS epoch where horizontal GNSS speed is **≥1 m/s**,
  the angle between the INS and GNSS horizontal velocity vectors must be **≤30°**. Zero or
  invalid INS velocity at those epochs fails the check.
- Compare against the contemporaneous GNSS velocity vector, including during turns.
- Missing reference coverage, an incomplete outage, or insufficient moving data makes the
  result **inconclusive**, never a pass.
- Describe 150 m as a displacement-error limit, not a drift rate.

### Evidence and failure handling

- Save a verification report containing the source, replay interval, initialization, maximum
  and endpoint horizontal error, maximum direction error, evaluated sample count,
  reacquisition result, and explicit pass/fail/inconclusive status.
- Include trajectory and error-versus-time plots with the outage boundaries marked.
- Report failed checks explicitly. Do not relax thresholds, shorten the outage, or change
  the selected interval merely to obtain a pass.
- This rule establishes a verification requirement; its presence does not mean the current
  application has passed it. Documentation-only changes do not themselves require this test.
