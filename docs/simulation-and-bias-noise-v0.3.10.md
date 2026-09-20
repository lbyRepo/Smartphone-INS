# Stationary simulation and bias process noise — v0.3.10

Version code: 14. Branch: `feature/map-basemap-ui-polish`.

## Simulator and alignment

The debug simulator stays at rest indefinitely until **More → Start simulated
motion** is pressed after initialization. **Init → Reset filter** returns it to
stationary and requires initialization again. Motion cannot be started during CSV
or Pixel 4 replay, or before successful alignment. Once started, the scenario
accelerates from 0 to 8 m/s over four seconds, travels straight, turns for eight
seconds, then continues straight. Positions integrate the same velocity supplied
to GNSS, and lateral IMU acceleration is speed × yaw rate. Height is constant.

Simulator truth: 1.3521° N, 103.8198° E, 25 m ellipsoid height, initially level and
north-facing. Use those manual inputs. Automatic alignment retains its 15-second
duration. Manual initialization now requires a recent approximately three-second
window with at least 300 fresh IMU samples; sparse/stale, non-finite or moving
samples and fresh GNSS speed ≥0.5 m/s are rejected. No gravity/zero-rate fallback
is substituted when samples are missing. Stillness checks cannot distinguish
constant translational motion from rest without external velocity information.

## Bias Q tuning

In `NavigationEngine.propagateCovariance`, the 15-state ordering is position,
velocity, ECEF small-angle attitude error, body accelerometer bias, body gyro bias.
The diagonal process-noise increment is `Qd[i,i] = amplitude[i]^2 * dt`.

| Constant | Old | New | Units |
|---|---:|---:|---|
| ACCEL_BIAS_PROCESS_NOISE_MPS2_PER_SQRT_SECOND | 0.002 | 0.004 | (m/s²)/√s |
| GYRO_BIAS_PROCESS_NOISE_RADPS_PER_SQRT_SECOND | 0.0002 | 0.0004 | (rad/s)/√s |

Both bias blocks receive four times their previous process covariance. At a 0.01 s
step, each accelerometer-bias diagonal receives 1.6e-7 (m/s²)² and each gyro-bias
diagonal 1.6e-9 (rad/s)². Attitude process-noise amplitude is unchanged at 0.004
rad/√s. Bias Q permits estimates to vary faster when measurements constrain them;
it is not a guaranteed accuracy improvement or a replacement for observability.

## Regression coverage

Verification passed: navigation/protocol/app unit tests, 31 emulator tests,
54 screenshot checks, and debug APK assembly.

- Indefinite stationary simulator, explicit motion start and repeated-start behavior.
- Position derivative versus GNSS velocity before, during and after turns.
- Thirty-second initialized stationary filter with GNSS aiding on and off.
- Bias-window rejection for missing, stale, non-fresh and moving data.
- Covariance increment equals the configured bias amplitude squared times dt.
- Android manual alignment, stationary automatic alignment, explicit movement,
  moving-alignment rejection, reset and aiding suspend/resume.
- Existing protocol, replay, 100 Hz TCP, UI and screenshot suites.

No binary protocol or CSV-schema changes. Physical-device tuning and navigation
accuracy require field evaluation. APK: `app/build/outputs/apk/debug/app-debug.apk`.
