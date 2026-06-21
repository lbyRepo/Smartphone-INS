# Public replay fixture

The replay test includes a small Pixel 4 fixture from Google's 2021 Smartphone Decimeter Challenge, mirrored by the open-source [Stanford NavLab `gnss_lib_py` project](https://github.com/Stanford-NavLab/gnss_lib_py/tree/main/data/unit_test/google_decimeter_2021):

- `Pixel4_GnssLog.txt` — Android accelerometer, gyro, magnetometer, GNSS fix, and raw GNSS records.
- `Pixel4_ground_truth.csv` — corresponding reference positions.

The test replays the Android IMU records through the production navigation engine, applies only GPS-provider fixes as GNSS aiding, compares each aided solution with the nearest ground-truth epoch, and fails if any horizontal error reaches 10 metres. The fixture is deliberately small and is a regression/compatibility trace rather than a substitute for full-route physical qualification.
