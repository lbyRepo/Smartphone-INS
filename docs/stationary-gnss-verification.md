# Stationary GNSS aiding verification · version 0.3.2

## Defects corrected

1. Android commonly reports a reliable near-zero speed without a bearing while stationary. Version 0.1.0 discarded that velocity observation because it required both speed and bearing. Version 0.2.0 accepts reliable near-zero speed as a zero-velocity measurement.
2. The filter previously counted any two accepted NED measurement axes as a valid horizontal update. An accepted East and Down update could therefore hide a rejected, drifting North update. Version 0.2.0 uses an explicit N/E/D acceptance mask and requires both North and East before claiming horizontal velocity aiding.
3. A statistically accepted update could have a small Kalman gain and leave the solution far outside the GNSS accuracy envelope. After an accepted update, version 0.2.0 bounds the true horizontal position and velocity vector magnitudes—not merely each axis—to three reported GNSS standard deviations.
4. A large inertial error could cause valid GNSS measurements to be rejected indefinitely. Version 0.2.0 reacquires from consecutive mutually consistent fixes while retaining isolated-outlier rejection.
5. The UI previously described every fresh fix as `GNSS-aided`. It now distinguishes `APPLIED`, `STATIONARY HOLD`, `REJECTED`, stale, and awaiting-correction states.
6. GNSS position and zero-velocity updates previously coupled into the attitude error state strongly enough to rotate a stationary solution even though its position remained bounded. Version 0.3.2 isolates attitude from GNSS position/velocity and stationary position/ZUPT cross-coupling, removes the fixed attitude latch, and lets roll/pitch follow gyro integration with gravity-based stabilization.

## Stationary regression

The automated test uses:

- 120 seconds of data.
- 12,001 IMU samples at 100 Hz.
- Constant accelerometer and gyro biases plus deterministic sensor noise.
- 121 noisy, valid 3D GNSS fixes at 1 Hz.
- Zero GNSS velocity, including the no-bearing-at-rest edge case.
- Constant gyro bias plus deterministic noise on all three gyro axes.

Acceptance requirements:

- Final horizontal position error below 3 m.
- Final horizontal speed below 0.08 m/s.
- Final vertical speed below 0.10 m/s.
- Roll, pitch, and yaw each change by less than 0.2° from 30 to 120 seconds.
- GNSS position-aiding and stationary-constraint flags asserted.
- Position and velocity aiding applied on all 121 valid 3D fixes.
- No rejected-update flag on the final valid fix.

The generated numerical result is saved in `navigation/build/reports/stationary-regression.txt`.

## Additional edge cases

- Isolated implausible GNSS position outlier remains rejected and cannot claim aiding.
- Three consistent 3D fixes reacquire position after a large inertial outage drift.
- Consistent moving GNSS fixes reacquire both horizontal velocity components after a large velocity innovation.
- Invalid or grossly inaccurate 3D fixes cannot claim aiding.
- GNSS outage remains visibly flagged as dead reckoning.
- Stationary phone Euler output changes when the phone is physically rolled or tilted.
- GNSS course aiding resumes on movement without reintroducing magnetometer heading correction.

## Dashboard map

The Dashboard includes an offline local metric map with independently labelled INS and raw GNSS markers and trails. Its coordinate origin is latched to the INS initialization position, so both tracks visibly move away from a fixed origin rather than being recentered on the newest sample. It auto-scales from ±10 m upward and does not require an API key or network connection. It also reports the current INS–GNSS separation and the active three-sigma bound, with an explicit `AIDING LIMIT EXCEEDED` state if the filter ever claims aiding outside that envelope.
