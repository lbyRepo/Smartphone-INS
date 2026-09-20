# Smartphone INS binary protocol v1

Every solution is a fixed 224-byte little-endian frame. The app emits one frame every 10 ms. USB and TCP are byte streams, so consumers must search for the `INS1` magic and validate the CRC before decoding.

## Envelope (36 bytes)

| Offset | Type | Field |
|---:|---|---|
| 0 | `char[4]` | `INS1` |
| 4 | `uint8` | Version (`1`) |
| 5 | `uint8` | Message type (`1`, combined solution) |
| 6 | `uint16` | Header bytes (`36`) |
| 8 | `uint16` | Payload bytes (`184`) |
| 10 | `uint16` | Reserved (`0`) |
| 12 | `uint32` | Sequence, modulo 2³² |
| 16 | `uint64` | Solution monotonic time, ns since boot |
| 24 | `int64` | Unix time, milliseconds |
| 32 | `uint32` | Quality flags |

## Solution payload (184 bytes)

Offsets below are absolute frame offsets. All vectors are ordered X/Y/Z according to their named frame. NED means North/East/Down; raw body axes are Forward/Right/Down (phone screen up, top edge forward).

| Offset | Type | Field | Unit |
|---:|---|---|---|
| 36 | `uint8` | Initialization source: none=0, automatic=1, manual=2, replay=3 | — |
| 37 | `uint8` | Motion profile: vehicle=0, pedestrian=1 | — |
| 38 | `uint8` | Fix: none=0, 2D=1, 3D=2, stale=3 | — |
| 39 | `uint8` | Satellites used | SV |
| 40 | `uint32` | GNSS age (`0xffffffff` if unavailable) | ms |
| 44 | `float64[3]` | INS latitude, longitude, ellipsoid altitude | deg, deg, m |
| 68 | `float32[3]` | INS velocity N/E/D | m/s |
| 80 | `float32[3]` | Roll, pitch, yaw from true North | deg |
| 92 | `float32[3]` | Position 1σ N/E/D | m |
| 104 | `float32[3]` | Velocity 1σ N/E/D | m/s |
| 116 | `float32[3]` | Attitude 1σ roll/pitch/yaw | deg |
| 128 | `uint64` | Raw IMU monotonic timestamp | ns |
| 136 | `float32[3]` | Raw acceleration body F/R/D | m/s² |
| 148 | `float32[3]` | Raw angular rate body F/R/D | rad/s |
| 160 | `uint64` | Raw GNSS monotonic timestamp | ns |
| 168 | `float64[3]` | Raw GNSS latitude, longitude, ellipsoid altitude | deg, deg, m |
| 192 | `float32[3]` | Raw GNSS velocity N/E/D | m/s |
| 204 | `float32` | GNSS horizontal accuracy (68%) | m |
| 208 | `float32` | GNSS vertical accuracy (68%) | m |
| 212 | `float32` | GNSS speed accuracy (68%) | m/s |
| 216 | `float32` | GNSS bearing accuracy (68%) | deg |
| 220 | `uint32` | IEEE CRC-32 over bytes 0–219 | — |

Invalid measurements are quiet NaN with the corresponding validity bit cleared.

The final four bytes are the unsigned IEEE CRC-32 of the preceding 220 bytes. `protocol/src/main/.../InsProtocol.kt` and `tools/receiver/phone_ins_receiver.py` are the normative codecs.

## Quality bits

| Bit | Meaning |
|---:|---|
| 0 | Initialized |
| 1 | Degraded |
| 2 | IMU fresh |
| 3 | IMU held |
| 4 | GNSS position valid |
| 5 | GNSS horizontal velocity valid |
| 6 | GNSS Down velocity derived and valid |
| 7 | GNSS fresh |
| 8 | Heading course-aided |
| 9 | Dead reckoning |
| 10 | Manual initialization |
| 11 | Output overrun |
| 12 | Mock GNSS |
| 13 | Magnetometer used during initialization |
| 14 | Stationary zero-velocity/position constraint active |
| 15 | Most recent fresh GNSS correction rejected |
| 16 | Raw GNSS visible while aiding is intentionally suspended |
| 17 | Bias learning held: IMU motion/quality window or clean-data recovery; GNSS position/velocity may remain aided |
| 18 | Latest raw IMU sample is near the sensor's advertised measurement limit (possible clipping) |

Bits 17–18 were added in v0.3.11 without changing frame size/version or CSV columns.
CSV replay restores clipping from bit 18, but recomputes the bias-learning hold
from raw samples. Old logs without this metadata cannot recover hardware clipping
that was not recorded. Large finite motion samples are still propagated, not discarded.

## Master INS reference protocol (`MST1`)

The optional external master uses a separate bidirectional TCP server on port `29501`; normal `INS1` output remains on `29500`. All fields are little-endian and every message ends with IEEE CRC-32. The common 32-byte header is `MST1`, version, type, header length, payload length, reserved, sequence, monotonic nanoseconds, and UTC nanoseconds.

- Type 1 is a fixed 180-byte reference frame carrying validity flags, WGS84 position, NED velocity, body-to-NED quaternion (`w,x,y,z`), body angular rate, velocity/attitude uncertainties, time uncertainty, and CRC.
- Type 2 is the phone clock-sync request carrying phone transmit time.
- Type 3 is the master response carrying the echoed phone transmit time plus master receive/transmit times. The phone uses the NTP four-timestamp offset and requires uncertainty below 10 ms.

References must be monotonic, no older than 100 ms, and sustained at 20 Hz or faster. [`master_ins_sender.py`](../tools/receiver/master_ins_sender.py) is the reference Python encoder, decoder, clock-sync responder, and deterministic sender.

[`master-reference-golden-v1.hex`](master-reference-golden-v1.hex) is the normative 180-byte MST1 reference frame (SHA-256 `cd15b19f164f09a04b0d656075079e151531b66d556ea7678265d2090217985a`) asserted by the Kotlin protocol tests.

## Golden frame

[`golden-frame-v1.bin`](golden-frame-v1.bin) is a normative 224-byte frame generated by the Kotlin codec and asserted byte-for-byte in `InsProtocolTest`. SHA-256: `d2a2736a7c082f314da79d88998838e2d924e867fecf5bbe0d38c674cb8bb86d`.
