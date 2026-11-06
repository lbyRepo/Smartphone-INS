# Repository instructions

## Commits and versions

- Never create, amend, merge, cherry-pick, or otherwise generate Git commits. Never push.
- The agent may stage explicitly reviewed files and propose Conventional Commit titles and
  descriptions. The user creates commits.
- Every proposed commit-sized change set, including documentation-only changes, must increment
  `versionCode` and update `versionName` in `app/build.gradle.kts` before staging.
- Restaging the same proposed change set does not require another version increment.
- Preserve unrelated working-tree and staged changes; stage named files or reviewed hunks.
- Report test failures explicitly; staging does not imply verification passed.
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

### Source data

The operator's real phone trials are stored in `trial_data/`. Prefer them over synthetic data for
this verification, and quote the SHA-256 alongside a result so it can be tied to exact bytes.
They are gitignored on purpose: they hold real location traces and are far larger than Git
hosting limits, so never commit them.

| Log | Recorded trial | Rows | SHA-256 |
|---|---|---:|---|
| `trial_data/van/phoneins_trial_20260728_134941.csv` | 2026-07-28, 1462.6 s, VEHICLE, near-vertical phone mount | 146,260 | `39387bf4b3c01f2bfec68d225ec6d5feed4635c4d767bb8a83c2888c37a98056` |
| `trial_data/bike/phoneins_trial_20260823_142934_501.csv` | 2026-08-23, 620.0 s, recorded VEHICLE profile | 62,005 | `3f78cd064b34fc5ba0e16f757fdb3019453c35ad264086fe6334b4819d05a51a` |

- Established anchors: **600.000 s** and **900.000 s** in the 2026-07-28 van log (moving, full
  GNSS reference, stable mount); the 2026-08-23 log contains two recorded forced outages at
  157.10 s (39 s) and 495.10 s (68 s) to anchor inside.
- Hosted history and previous numbers: `docs/outage-verification-trial-20260728.md`,
  `docs/outage-verification-trial-20260823.md`, and the current-engine record
  `docs/filter-repair-verification-v0.3.19.md`.
- The 2026-09-20 bicycle trial used for the passing bicycle window
  (`docs/outage-verification-trial-20260920.md`) is **not** in this checkout; ask the operator
  for it before re-running that window.
- Treat these files as read-only evidence: never edit, trim or regenerate them, and never let
  their recorded INS columns stand in for a recomputed solution.
- If `trial_data/` is missing, the verification cannot be completed — report that instead of
  substituting another source, and the deterministic `SyntheticOutageAcceptanceTest` fixture
  counts only as a supplement, never as a replacement.

Run an anchor like this, recording the source file and interval before evaluating results:

```bash
TRIAL_CSV="$PWD/trial_data/van/phoneins_trial_20260728_134941.csv" TRIAL_OUTAGE_ANCHOR_S=600.000 \
  ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "*TrialOutageVerificationTest*"
```

- `cleanTestDebugUnitTest` is required between runs: the environment variables are not Gradle
  task inputs, so a plain re-run is skipped as up-to-date.
- The harness fails the build on a violated limit and writes
  `app/build/reports/trial-outage/<log>-<anchor>/verification_summary.txt` plus its per-epoch CSV.

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
