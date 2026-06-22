# Public replay fixture

The replay test includes a small Pixel 4 fixture from Google's 2021 Smartphone Decimeter Challenge, mirrored by the open-source [Stanford NavLab `gnss_lib_py` project](https://github.com/Stanford-NavLab/gnss_lib_py/tree/main/data/unit_test/google_decimeter_2021):

- `Pixel4_GnssLog.txt` — Android accelerometer, gyro, magnetometer, GNSS fix, and raw GNSS records.
- `Pixel4_ground_truth.csv` — corresponding reference positions.

The version 0.3.0 route scenario uses all 199 reference epochs from `Pixel4_ground_truth.csv`, including their published position, altitude, speed, and course. It deliberately keeps raw GNSS visible while withholding GNSS corrections for epochs 105–124, then resumes corrections to verify filter reacquisition and Dashboard history.

The mirrored `Pixel4_GnssLog.txt` contains only 22 accelerometer and 22 gyro samples, so it cannot supply inertial data for the full 199-second route. The route regression therefore synthesizes a deterministic 100 Hz, screen-up, forward-aligned, fixed-mounted IMU stream from the published speed/course dynamics and adds known biases and noise. This tests application behavior, navigation state transitions, and fixed-mount constraints; it is not a claim of navigation accuracy on an original full Challenge IMU log or a substitute for physical-phone qualification.
