# Decimeter route, fixed-mount, and ZUPT verification · version 0.3.0

## Scenario

The deterministic regression replays 199 published Pixel 4 reference epochs from the 2021 Google Smartphone Decimeter Challenge. Position, altitude, speed, and course come from the bundled reference CSV. Because the mirrored raw fixture contains only 22 IMU samples, the test creates a deterministic 100 Hz fixed-mounted IMU stream from those route dynamics with deliberate accelerometer and gyro bias.

GNSS aiding is intentionally disabled for 20 moving fixes while every raw fix remains available to the UI. The same production navigation engine and Dashboard map accumulator used by the Android app process the route.

## Acceptance evidence

- Dashboard history retains 199 INS points and 199 raw-GNSS points from the initialization origin; the final plotted point is more than 100 m from that origin.
- During the forced aiding pause, raw GNSS remains visible, the UI reports `GNSS visible · aiding paused`, and INS–GNSS separation reaches 221.021 m.
- After GNSS aiding resumes, separation is 0.000 m by the third subsequent fix; maximum steadily aided position error is 7.310 m.
- Initialized roll and pitch remain fixed-mounted: their full-route ranges are 0.362° and 0.120°. Heading remains dynamic and changes by 177.238°.
- The zero-velocity update limits maximum horizontal speed over the route's confirmed stationary epochs to 0.0004 m/s.

The generated authoritative report is `navigation/build/reports/google-decimeter-route.txt`. This is deterministic regression evidence, not physical-phone accuracy qualification.
