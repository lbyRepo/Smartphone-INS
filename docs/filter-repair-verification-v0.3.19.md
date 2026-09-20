# Coupled-filter repair · 0.3.19 · 45-second GNSS outage verification

**Scope.** Restore consistent coupled estimation in the 15-state ECEF error-state INS filter so
that any fixed phone mounting — including the near-vertical mount used in the 2026‑07‑28 van
trial — survives a 45 s GNSS withhold without a filter re-tune of process noise, gates or
outage length. No interface, CSV schema, master-reference protocol or 224-byte output frame was
changed. The optional IMU smoothing window is **disabled by default** in this version
(`IMU_SMOOTHING_WINDOW_SAMPLES = 1`); it is retained only as a diagnostic knob.

**Verdict summary — three of the four mandated windows pass, one fails.**

| Window | Source | Withhold | Max separation (≤150 m) | Max direction error (≤30°) | Violations | Verdict |
|---|---|---:|---:|---:|---:|---|
| Bicycle, anchor 110.248 s | `trial.csv` (2026‑09‑20 log) | 45.000 s | 95.04 m | 24.5° | 0 / 45 | **PASS** |
| Van, anchor 600.000 s | `trial.csv` (2026‑07‑28 log) | 45.000 s | 65.86 m | 11.1° | 0 / 45 | **PASS** |
| Van, anchor 900.000 s | `trial.csv` (2026‑07‑28 log) | 45.000 s | 72.28 m | 13.9° | 0 / 45 | **PASS** |
| Synthetic DecimeterRouteScenario 100–145 s | deterministic fixture | 45.000 s | **242.76 m** | **37.96°** | 4 / 42 | **FAIL** |

The failing window is reported as a failure, not relaxed: no interval, gate or limit was changed
to obtain a pass. See [Remaining failure](#remaining-failure-synthetic-100145-s) for the
mechanism. 150 m is a displacement-error limit for the withhold, not a drift rate.

## What the repair changed

Before the repair the filter treated bias learning as an on/off switch: whenever the IMU quality
monitor withheld bias learning (rough road, vibration, clipped samples, aliasing, recovery), the
attitude/bias correction channel was suppressed together with it, so the filter could stop
observing its own mounting and then integrate a mounting error as if it were motion. The repair
replaces the switch with an explicit, frame-consistent gain policy:

| Condition (evaluated per GNSS fix) | Accelerometer-bias gain | Gyro-bias gain |
|---|---|---|
| Strict `ImuQualityMonitor.canLearn` gate | 1.0 | 1.0 |
| Plausible rough motion inside the 180 s settling window after initialization (`biasAdaptationAllowed`) and quasi-steady (`biasAdaptationSteady`) | 0.4 | 1.0 |
| Plausible rough motion inside the settling window but not quasi-steady | 0.0 (held exactly) | 1.0 |
| Outside the settling window, or implausible/aliased data | 0.0 (held exactly) | 0.0 (held exactly) |

Position, velocity and attitude observations stay fully coupled in every one of these states, in
Joseph form, under the existing 99.9 % NIS gates and dimension-appropriate measurement models. At
standstill the gravity observation corrects attitude only, because tilt and accelerometer bias are
not separable there. Constants:

- `NavigationEngine.BIAS_CALIBRATION_WINDOW_NS = 180 s`
- `NavigationEngine.ADAPTATION_ACCEL_BIAS_GAIN_SCALE = 0.4`
- `NavigationEngine.ADAPTATION_GYRO_BIAS_GAIN_SCALE = 1.0`
- `ImuQualityMonitor`: adaptation window 5 s (checked at 20 Hz), accel RMS ≤ 5.0 m/s², rate RMS
  ≤ 1.5 rad/s, large-jump fraction < 0.02, lag‑1 accel autocorrelation ≥ 0, recovery 1 s; steady
  gate mean |ω| ≤ 0.25 rad/s and |‖a‖ − g| ≤ 1.5 m/s².

No process-noise amplitude was increased (`ACCEL_BIAS_PROCESS_NOISE…= 0.002`,
`GYRO_BIAS_PROCESS_NOISE…= 0.0002`, attitude `0.004` unchanged), no gate was widened, no smoothing
was enabled, and no outage window was shortened.

## Method

For every window the raw IMU/GNSS rows were **re-fed through the current `NavigationEngine`** along
the app's own replay path (`CsvReplayIndex` / `processCsvReplayRow` semantics, warm start from the
logged initialization); recorded INS outputs were used only for comparison, never as the subject.
Aiding was withheld for exactly 45.000 s of recorded sensor time. Raw GNSS continued to be
collected throughout and was verified not to correct INS position, velocity, heading or stationary
constraints (zero epochs with GNSS correction flags while withheld; `GNSS_POSITION_VALID` clear;
biases bit-identical before and after). Errors are computed at matched timestamps by WGS84
ECEF→local-ENU conversion and `sqrt(E² + N²)`; direction error is the angle between the INS and
contemporaneous GNSS horizontal velocity vectors at epochs where GNSS speed ≥ 1 m/s.

Sources (sha256):

- `c1309d29244b2fec745d03e38ac192ec80dfbcc15dced8c7514e5335c1559a6e` — bicycle log replay
  (`/tmp/ins_verify/trial.csv`, 21 726 rows)
- `39387bf4b3c01f2bfec68d225ec6d5feed4635c4d767bb8a83c2888c37a98056` — van log replay
  (`/tmp/ins_verify3/trial.csv`, 146 260 rows)

Harnesses: `app/src/test/java/com/example/phoneins/service/TrialOutageVerificationTest.kt`
(env `TRIAL_CSV`, `TRIAL_OUTAGE_ANCHOR_S`) and
`navigation/src/test/kotlin/com/example/phoneins/navigation/SyntheticOutageAcceptanceTest.kt`
(asserts the limits, so a violated limit fails the task and writes the report).

Initialization for all three logged windows is the logged warm start at t = 17.91 s (van) /
20.46 s (bicycle) — the van mount is the near-vertical one (pitch +81.6°), which is why it is the
mounting-regression window.

## Results

Per-window plots (trajectory, separation and direction with the outage boundaries marked) are in
``; full per-epoch records in `epochs/` (gzipped CSV, one row per 10 ms replay epoch with
the matched GNSS epochs carrying the reference position/velocity); harness summaries verbatim in
`summaries/`.

- `docs/fig_filter_repair_v0.3.19_bicycle-110.248s-outage.png` — INS overshoots the reference path; 95.04 m at the
  endpoint, direction error peaks at 24.5°, no violation.
- `docs/fig_filter_repair_v0.3.19_van-600s-outage.png` — near-vertical mount; separation grows to 65.86 m, direction
  11.1°.
- `docs/fig_filter_repair_v0.3.19_van-900s-outage.png` — second van anchor; 72.28 m, 13.9°.
- `docs/fig_filter_repair_v0.3.19_synthetic-100-145s-outage.png`,
  `docs/fig_filter_repair_v0.3.19_synthetic-100-145s-speed.png`,
  `docs/fig_filter_repair_v0.3.19_synthetic-100-145s-bias-attitude.png` — failing window: 242.76 m and 37.96°, with the
  INS speed error at the route stop, the ~2° pitch drift across the withhold and the lateral
  accelerometer bias held at −0.0977 m/s².
- `docs/fig_filter_repair_v0.3.19_comparison-before-after.png` — before/after summary.

Reacquisition behaved correctly in all four windows: aiding resumed, and the separation returned
to sub-metre within the first ten seconds (0.45–0.67 m logged windows; 1.288 m within three fixes
for the synthetic window).

### Reproducibility

All four windows were re-run from scratch against the frozen 0.3.19 engine after this report's
numbers were first recorded, and reproduced **exactly** — bicycle 95.04 m / 24.5°, van 600 s
65.86 m / 11.1°, van 900 s 72.28 m / 13.9°, synthetic 242.760 m / 37.959°. The filter is
deterministic for a fixed input order, so these figures are the current algorithm's output, not a
remembered run.

### Before vs after (same windows, pre-repair figures from `docs/`)

| Window | Before: max separation | Before: max direction | After: max separation | After: max direction |
|---|---:|---:|---:|---:|
| Bicycle 110.248 s | 1656.26 m (`docs/outage-verification-trial-20260920.md`) | 179.2° (45 violations) | 95.04 m | 24.5° (0) |
| Van 600 s | 755.11 m (`docs/outage-verification-trial-20260728.md`) | 16.9° (0) | 65.86 m | 11.1° (0) |
| Van 900 s | 1025.54 m (`docs/outage-verification-trial-20260728.md`) | 87.7° (15 violations) | 72.28 m | 13.9° (0) |
| Synthetic 100–145 s | 567.65 m (v0.3.15 harness record) | 176.9° (35 violations) | 242.76 m | 37.96° (4 violations) |

Both van anchors and the bicycle window moved from fail to pass. The synthetic window improved by
2.3× in displacement and no longer spins the velocity vector around, but still fails.

## Remaining failure (synthetic 100–145 s)

Measured: 242.76 m maximum/endpoint separation (limit 150 m), 37.96° maximum direction error with
4 of 42 moving epochs over 30°, 0 zero-velocity epochs, 0 hidden corrections, reacquisition 1.288 m.

Diagnosis (from the epoch record):

- The residual is a small, persistent **along-track** error: roughly a 1° pitch-equivalent
  misalignment (the estimated pitch drifts from +0.75° to −1.3° across the withhold), i.e. about
  0.2 m/s² of un-modelled acceleration, plus a lateral accelerometer bias held at entry
  (`aby ≈ −0.0977 m/s²`, see `docs/fig_filter_repair_v0.3.19_synthetic-100-145s-bias-attitude.png`).
- Because the synthetic route ends in a **stop**, that error compounds into an INS speed of
  ~8–9 m/s while the reference is at 0.26 m/s (`docs/fig_filter_repair_v0.3.19_synthetic-100-145s-speed.png`). The stop cannot
  pull the solution back: by design the outage isolation path clears the stationary latches and
  never applies a zero-velocity observation while aiding is withheld.
- Consequently the endpoint — the worst epoch — is set by the route's stop, not by a growing
  divergence. The logged windows, which do not end in a stop, pass.

This is a genuine, reproducible acceptance failure with a known mechanism; it is left in place
rather than masked. The candidates that would address it (a gravity/Earth-rotation model overhaul,
or an explicit mounting-calibration step) were explicitly deferred, and forcing a pass by raising
process noise or relaxing a gate is out of scope for this repair.

## Regression coverage added

`navigation/src/test/kotlin/com/example/phoneins/navigation/FilterCovarianceRegressionTest.kt`
(7 tests, all passing):

- NED/ECEF covariance-reset round trip for position and velocity with deliberately unequal
  horizontal/vertical/down accuracies, at near-equator and mid latitude (values asserted against
  the fix's own accuracies, so a wrong longitude or frame fails).
- Covariance symmetry, finiteness and positive semidefiniteness after initialization, every GNSS
  update, and reset.
- Outage isolation: biases bit-identical, no position aiding, dead-reckoning flag set, dead
  reckoning keeps integrating a sustained force under raw zero-speed fixes, and reacquisition
  restores the observed state and recovers the outage displacement.
- Unobservable-state uncertainty is not falsely reduced (attitude and bias uncertainty must not
  shrink without an observation).
- Near-vertical mounting (roll 81°, pitch −40°) stays bounded and finite under aiding.
- Finite-difference check of the mechanization attitude/force coupling against the analytic
  left-ECEF error coupling `dv/dθ = −[C f]×` (agreement to 1e‑6).
- Holding the bias gains at zero leaves both bias states bit-identical while the velocity state is
  still corrected.

## Reproduction

```bash
# Logged windows (the van/bicycle logs are the sha256-identified copies above)
TRIAL_CSV=/tmp/ins_verify/trial.csv  TRIAL_OUTAGE_ANCHOR_S=110.248 \
  ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "*TrialOutageVerificationTest*"
TRIAL_CSV=/tmp/ins_verify3/trial.csv TRIAL_OUTAGE_ANCHOR_S=600.000 \
  ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "*TrialOutageVerificationTest*"
TRIAL_CSV=/tmp/ins_verify3/trial.csv TRIAL_OUTAGE_ANCHOR_S=900.000 \
  ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "*TrialOutageVerificationTest*"

# Synthetic window (fails on the limits by design and writes the report)
./gradlew :navigation:cleanTest :navigation:test --tests "*SyntheticOutageAcceptanceTest*"

# Plots for this report (local artifact; writes outputs/filter-repair-v0.3.19/plots)
python3 outputs/filter-repair-v0.3.19/make_plots.py
```

`cleanTestDebugUnitTest` / `cleanTest` are required between runs because the environment variables
are not Gradle task inputs, so a plain re-run would be skipped as up-to-date.
