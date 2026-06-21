# Physical-device verification status

No compatible physical phone or Android Open Accessory host was available in this environment. The items below are therefore explicit release gates, not claimed results.

## Required phone matrix

Test at least one Pixel 8-class phone and one Galaxy S24-class phone. Record exact model, Android build, sensor names, maximum sensor rates, GNSS capabilities, battery temperature, and any throttling.

For each phone:

1. Grant and deny location/notification permissions on separate clean installs.
2. Confirm automatic and manual initialization, raw IMU/magnetometer/GNSS display, screen-off foreground operation, and process recreation.
3. Stream for 30 minutes at 100 Hz and record receiver frame count, observed rate, p99 inter-frame gap, sequence loss, CRC failures, app crashes, and thermal state.
4. Exercise USB-tethered TCP, then AOA attach, permission grant, detach, and reconnect with the Python receiver.
5. Record representative vehicle and forward-aligned pedestrian routes, including a GNSS outage, and score against a defensible ground-truth source.

## Acceptance record

| Device / test | Status | Evidence |
|---|---|---|
| Pixel 8-class sensors and 30-minute stream | Not run | Physical device required |
| Galaxy S24-class sensors and 30-minute stream | Not run | Physical device required |
| USB-tethered TCP | Not run | Physical device required |
| AOA grant/detach/reconnect and 100 Hz decode | Not run | AOA host and phone required |
| Vehicle route accuracy | Not run | Field test required |
| Pedestrian route accuracy | Not run | Field test required |

The host-side production TCP test and public Pixel 4 fixture are useful regression evidence, but they do not establish physical-phone timing, AOA compatibility, thermal stability, or universal navigation accuracy.
