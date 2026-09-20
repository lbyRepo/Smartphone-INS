# Mandatory 45-second GNSS outage verification — trial `phoneins_trial_20260920_132220_974`

**Verdict: FAIL** (both acceptance criteria exceeded on reprocessed data with the current
navigation engine). This report also diagnoses the field symptom reported with the trial:
after the forced outage the INS velocity pointed opposite to the GNSS velocity.

## Source and procedure

- Source log: `phoneins_trial_20260920_132220_974.csv` (21,726 rows, 217.25 s recorded sensor
  time, 2026-09-20 05:22:20–05:25:58 UTC, VEHICLE profile, automatic initialization).
- Reprocessing: raw IMU and GNSS rows were re-fed through the current `NavigationEngine`
  exactly as the app's CSV replay does (`CsvReplayIndex`/`processCsvReplayRow` semantics,
  warm-start from the logged initialization). Recorded INS outputs were used only for
  comparison, never as the evaluation subject.
- Harness: `app/src/test/.../TrialOutageVerificationTest.kt` (opt-in via `TRIAL_CSV`).
- Aiding withhold: **45.000 s of recorded sensor time**, anchored at the recorded forced-outage
  start, t = 109.33 s (05:24:10.313 UTC) → 154.33 s. Raw GNSS continued to be collected with
  `observeGnssWithoutAiding` during the withhold; it did not correct position, velocity,
  heading or stationary constraints.
- Reference: contemporaneous GNSS fixes (1 Hz, valid throughout the window; vehicle moving at
  ~2.5–4 m/s). Errors computed at matched timestamps via WGS84 ECEF-to-ENU conversion with
  `sqrt(E² + N²)`.

## Initialization

Replay warm-start at t = 20.46 s (the log's first initialized row, source AUTOMATIC):
lat 1.4080599°, lon 103.9084199°, alt 22.00 m, vel zero, euler (roll −19.6°, pitch +1.1°,
yaw 211.4°), accel bias (−0.0009, 0.0089, −0.0226) m/s², gyro bias (−0.00255, −0.01249,
0.00118) rad/s.

## Results (current engine, 45 s withhold)

| Metric | Measured | Limit | Result |
|---|---:|---:|---|
| Max horizontal separation during outage | **1656.26 m** | ≤150 m | FAIL |
| Endpoint separation (before reacquisition, t = 154.30 s) | **1656.26 m** | ≤150 m | FAIL |
| Max INS/GNSS velocity direction error (GNSS speed ≥1 m/s) | **179.2°** | ≤30° | FAIL |
| Direction violations / evaluated epochs | **45 / 191** | 0 | FAIL |
| Zero/invalid INS velocity at moving epochs | 0 | 0 | pass |
| Outage completeness | 45.000 s, 46 matched GNSS epochs | 45 s | complete |
| Aiding restored at reacquisition | yes (t = 154.33 s); separation back to 0.58 m within 10 s | — | pass |

Separation is a displacement-error limit, not a drift rate. The INS velocity flipped to
anti-parallel within ~1–2 s of the withhold and then diverged at ~2 m/s², reaching ~79 m/s
against a ~3 m/s reference (figures `fig1_verification_overview.png`, `fig2_mechanism.png`).
At reacquisition the accepted fixes pulled the solution back to 0.58 m within 10 s.

## Context: the two logged forced outages (trial as flown)

The trial itself contained two shorter forced outages, and the same failure is visible in the
recorded INS at trial time, so the defect is not an artifact of reprocessing:

- Outage 1, 19 s (109.3–128.3 s): direction error 7.6° → 172.3°, INS speed 1.3 → 22.7 m/s
  (~2 m/s² anti-parallel), separation at endpoint ~159–165 m; snapped back at reacquisition.
- Outage 2, 12 s (152.3–164.3 s): velocity flipped at ~153 s; INS speed reached 23.6 m/s
  against ~2.6 m/s reference (~160–165° direction error), separation ~167 m at endpoint.

## Diagnosis

1. **Not the bias estimates themselves.** The estimated biases are small and frozen for almost
   the entire trial (accel ≤0.023 m/s², gyro ≤0.0125 rad/s; `BIAS_LEARNING_HELD` from ~30 s —
   the rough-road hold working as designed). They cannot produce a 2 m/s² error.
2. **Mount attitude changed right after initialization.** During the 15 s alignment (2–22 s)
   mean body gravity was (ax≈+0.15, ay≈+3.3, az≈−9.3) m/s² — matching the aligned
   roll −19.6°/pitch +1.1°. From ~22 s (motion start) mean gravity moved to
   (ax≈+3.3…+4.8, ay≈0.3…1.9) m/s² — the phone slipped/rotated in its mount; the top-edge
   tilt moved from ~1° to ~20–30°.
3. **Persistent attitude inconsistency.** Comparing 10 s raw-accel-implied attitude with the
   filter's attitude: the pitch error grows from ~2–5° while aided (masked) to ~12° during the
   withhold and ~20° late in the run. The filter's pitch reads 37–49° while the raw specific
   force implies 25–33°.
4. **Gravity leakage is the driver.** A pitch/roll attitude error θ leaks g·sin(θ) into the
   horizontal channels: the measured along-body acceleration error during the outage is
   ~1.5–2 m/s², anti-parallel to motion. While GNSS aiding is active the 1 Hz velocity/position
   corrections mask this leakage (pre-outage direction error was only 0.4–0.7° at fix epochs);
   the moment aiding is withheld the ~2.5–3.6 m/s velocity flips through zero within 1–2 s and
   then diverges. This is why the INS pointed "opposite" to GNSS velocity.
5. **Recovery after reacquisition is fast on this trial.** The first accepted fixes collapse
   the 1656 m error to 0.58 m within 10 s (`GNSS_UPDATE_REJECTED` is never set). The residual
   risk is the persistent attitude inconsistency itself, which re-appears whenever aiding is
   withheld again.

## Failure handling

No thresholds were relaxed and the withhold interval was not shortened to obtain a pass. The
result stands as a FAIL for the current iteration on this data.

## Candidate follow-ups (not applied here)

- Detect initialization-vs-actual-mount inconsistency: the warm-start attitude disagrees with
  the observed specific-force attitude by >10° within seconds of motion; a consistency check
  (or re-alignment prompt) after motion onset would catch the mount slip.
- Strengthen in-motion roll/pitch observability (the magnetometer lock-out and the
  stillness-gated gravity tilt correction leave pitch/roll effectively uncorrected under
  vibration).
