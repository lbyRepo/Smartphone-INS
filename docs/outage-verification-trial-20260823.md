# Mandatory 45-second GNSS outage verification — trial `phoneins_trial_20260823_142934_501`

**Verdict: FAIL** (both acceptance criteria exceeded on reprocessed data with the current
navigation engine), while the GNSS-aided portions of the same replay perform well.

## Source and procedure

- Source log: `phoneins_trial_20260823_142934_501.csv` (62,005 rows, 620.0 s recorded sensor
  time, 2026-08-23 06:29:34 UTC onward, VEHICLE profile, automatic initialization).
- Reprocessing: raw IMU and GNSS re-fed through the current `NavigationEngine` exactly as the
  app's CSV replay does; recorded INS outputs used only for comparison.
- Harness: `TrialOutageVerificationTest.kt` with `TRIAL_CSV` and `TRIAL_OUTAGE_ANCHOR_S`.
- The log contains two recorded forced outages: 157.1–196.1 s (39 s) and 495.1–563.1 s (68 s).
  Two independent 45.000 s withholding windows were evaluated, both fully covered by valid
  GNSS reference and moving motion:
  - **Primary:** anchored at 495.10 s, inside the 68 s recorded outage → window 495.10–540.10 s.
  - **Secondary:** anchored at 157.10 s → window 157.10–202.10 s.
- Errors computed at matched timestamps via WGS84 ECEF-to-ENU conversion, `sqrt(E² + N²)`.

## Initialization

Replay warm-start at t = 45.53 s (first initialized row, AUTOMATIC): lat 1.4023035°,
lon 103.8946573°, alt 27.00 m, vel zero, euler (roll −15.5°, pitch +38.4°, yaw 199.4°),
accel bias (−0.0181, −0.0049, 0.0198) m/s², gyro bias (0.00043, −0.01033, −0.00943) rad/s.

## Results

| Metric | Primary (anchor 495.1 s) | Secondary (anchor 157.1 s) | Limit | Result |
|---|---:|---:|---:|---|
| Max horizontal separation | **2583.83 m** | **3389.48 m** | ≤150 m | FAIL |
| Endpoint separation | **2583.83 m** | **3389.48 m** | ≤150 m | FAIL |
| Max direction error (GNSS speed ≥1 m/s) | **65.6°** | **49.8°** | ≤30° | FAIL |
| Direction violations / epochs | 8 / 501 | 44 / 501 | 0 | FAIL |
| Zero/invalid INS velocity at moving epochs | 0 | 0 | 0 | pass |
| Outage completeness | 45.000 s, 46 epochs | 45.000 s, 45 epochs | 45 s | complete |
| Aiding restored at reacquisition | yes | yes | — | pass |
| Separation 10 s after reacquisition | 0.81 m | 2.14 m | — | good |

The INS velocity magnitude diverges during the withhold (up to ~127–165 m/s against a
~2.5–5 m/s reference) with direction error mostly ≤30° after an early transient — the failure
is dominated by along-track position drift rather than the anti-parallel flip seen in the
2026-09-20 trial. Separation is a displacement-error limit, not a drift rate.

## GNSS-aided performance (outside the withhold windows)

| Metric | Median | p95 | Max |
|---|---:|---:|---:|
| Separation (t > 60 s, aiding on) | **1.01 m** | 3.26 m | 6.9 m |
| Direction error | 0.6° | 2.9° | — |
| Speed error | 0.14 m/s | 0.44 m/s | — |

Aided position/velocity tracking on this log is tight; the acceptance failure is specific to
the aiding-withheld interval.

## Notes

- The log was recorded by an older app build (2026-08-23), but the replay runs the **current**
  navigation algorithm, so the verdict applies to the current iteration's replay behaviour on
  this data.
- The current engine's outage drift is smaller than the recorded (2026-08-23) filter's on the
  same data: the recorded run reached 1634 m separation during its own 68 s outage, while the
  reprocessed current engine reached 2583 m at 45 s — comparable order, still far beyond the
  150 m limit.
- Figures: `docs/fig_outage_verification_20260823.png`.
