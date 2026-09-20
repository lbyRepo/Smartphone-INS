# Mandatory 45-second GNSS outage verification — trial `phoneins_trial_20260728_134941`

**Verdict: FAIL** on separation, **PASS** on the direction criterion at the primary anchor
(with a secondary anchor violating direction at 15 of 1147 epochs). This van trial is the
cleanest algorithm isolation so far: the mount attitude was stable for the entire 24.4-minute
drive, so the mount-slip confound from the 2026-09-20 trial is absent — and a 755 m / 45 s
drift remains.

## Source and procedure

- Source log: `phoneins_trial_20260728_134941.csv` (146,260 rows, 1462.6 s ≈ 24.4 min recorded
  sensor time, VEHICLE profile, automatic initialization). The phone was mounted **vertically
  on a phone stand** (operator report), consistent with the raw data: mean body-X specific
  force ≈ +9.65 m/s² from the alignment window through t = 1200 s — a stable, near-vertical
  attitude with no mount slip anywhere in the trial.
- Replay: raw IMU/GNSS re-fed through the current `NavigationEngine` via the app's CSV replay
  path. The log contains **no recorded forced outages**, so the 45.000 s withholding windows
  were anchored at two reproducible moving segments with full GNSS reference coverage:
  - **Primary:** 600.0–645.0 s (vehicle at ~17–22 m/s).
  - **Secondary:** 900.0–945.0 s.
- Errors computed at matched timestamps via WGS84 ECEF-to-ENU conversion, `sqrt(E² + N²)`.

## Initialization

Replay warm-start at t = 17.91 s (first initialized row, AUTOMATIC): lat 1.2880310°,
lon 103.7871824°, alt 85.77 m, vel zero, euler (roll −41.7°, pitch **+81.6°**, yaw 1.0°),
accel bias (−0.0384, −0.0048, 0.0053) m/s², gyro bias (0.00576, −0.00248, 0.00454) rad/s.
The near-vertical pitch is consistent with the observed gravity vector (|a| ≈ 9.74 m/s²,
ax ≈ +9.65 m/s²).

## Results

| Metric | Primary (anchor 600 s) | Secondary (anchor 900 s) | Limit | Result |
|---|---:|---:|---:|---|
| Max horizontal separation | **755.11 m** | **1025.54 m** | ≤150 m | FAIL |
| Endpoint separation | **755.11 m** | **1025.54 m** | ≤150 m | FAIL |
| Max direction error (GNSS speed ≥1 m/s) | **16.9°** | **87.7°** | ≤30° | PASS / FAIL |
| Direction violations / epochs | 0 / 1147 | 15 / ~1147 | 0 | PASS / FAIL |
| Zero/invalid INS velocity at moving epochs | 0 | 0 | 0 | pass |
| Outage completeness | 45.000 s, 45 epochs | 45.000 s | 45 s | complete |
| Aiding restored at reacquisition | yes; 0.46 m within 10 s | yes; 0.72 m within 10 s | — | pass |

The during-outage velocity error grows at a mean **~0.73 m/s² along track** (INS speed ramps
16.7 → 49 m/s against an ~18–22 m/s reference) — smaller than the ~2 m/s² seen in the
mount-slip trials (1656 m / 3389 m drifts) but still more than five times the displacement
limit.

## GNSS-aided performance (outside the withhold window)

| Metric | Median | p95 | Max |
|---|---:|---:|---:|
| Separation (t > 30 s, aiding on) | **0.70 m** | 2.94 m | 4.4 m |
| Direction error | 0.0° | 0.50° | — |

Aided tracking on this trial is excellent. The vertical mount itself was handled correctly by
the alignment and the filter — the attitude stayed consistent with the observed gravity vector
for the entire run.

## Interpretation

1. **The mount-slip confound is absent, and a 755 m / 45 s drift remains.** This isolates an
   algorithm-level limitation beyond the initialization/mount mismatch documented for the
   2026-09-20 trial.
2. **Near-singular Euler attitude is a plausible contributor.** The mount sits at 81.6° pitch —
   close to the ±90° region where the project's own documentation notes Euler-angle
   conditioning becomes ill-conditioned. Any correction path that reasons through Euler angles
   (e.g., the gravity tilt stabilization) degrades as the mount approaches vertical. The
   direction criterion passing at the primary anchor while separation fails points at an
   along-track (body-forward) acceleration error of ~0.7 m/s² rather than a heading error.
3. **Residual accelerometer bias is small** (≤0.038 m/s²) and cannot explain 0.73 m/s²; with
   bias learning held under motion, the along-track error most likely comes from attitude error
   and/or unmodelled specific-force dynamics (vibration rectification through the near-vertical
   geometry).
4. Secondary anchor (900 s) showed worse direction behaviour (max 87.7°, 15 violations),
   suggesting the error grows with time-since-initialization or depends on the driving segment.

## Failure handling

No thresholds were relaxed and the withhold windows were selected before evaluation. The
verdict for the current iteration on this data is FAIL (primary and secondary).

## Candidate follow-ups (not applied here)

- Exercise the mechanization with quaternions end-to-end near vertical mounts (avoid Euler
  conditioning in any correction path).
- Use the van log as the regression baseline for an outage-drift fix: 755 m / 45 s with a
  stable mount is the number to beat.
- Field-verify whether the phone stand transmits engine/vibration energy that rectifies into
  the near-vertical attitude channel.
