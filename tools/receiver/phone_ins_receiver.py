#!/usr/bin/env python3
"""Reference receiver for Smartphone INS protocol v1 (TCP, file, or Android AOA)."""

from __future__ import annotations

import argparse
import csv
import socket
import struct
import sys
import time
import zlib
from pathlib import Path

MAGIC = b"INS1"
HEADER_BYTES = 36
PAYLOAD_BYTES = 184
FRAME_BYTES = 224


def frames_from_stream(read):
    buffer = bytearray()
    while True:
        chunk = read(16_384)
        if not chunk:
            return
        buffer.extend(chunk)
        while True:
            start = buffer.find(MAGIC)
            if start < 0:
                del buffer[:-3]
                break
            if start:
                del buffer[:start]
            if len(buffer) < HEADER_BYTES:
                break
            header_length, payload_length = struct.unpack_from("<HH", buffer, 6)
            length = header_length + payload_length + 4
            if header_length != HEADER_BYTES or payload_length != PAYLOAD_BYTES:
                del buffer[0]
                continue
            if len(buffer) < length:
                break
            frame = bytes(buffer[:length])
            del buffer[:length]
            expected = struct.unpack_from("<I", frame, length - 4)[0]
            if zlib.crc32(frame[:-4]) & 0xFFFFFFFF != expected:
                print("CRC failure; frame discarded", file=sys.stderr)
                continue
            yield frame


def decode(frame):
    magic, version, msg_type, header_len, payload_len, _, sequence, mono_ns, utc_ms, flags = struct.unpack_from(
        "<4sBBHHHIQqI", frame, 0
    )
    o = HEADER_BYTES
    init_source, profile, fix_status, satellites, gnss_age_ms = struct.unpack_from("<BBBBI", frame, o); o += 8
    latitude, longitude, altitude = struct.unpack_from("<ddd", frame, o); o += 24
    velocity = struct.unpack_from("<fff", frame, o); o += 12
    euler = struct.unpack_from("<fff", frame, o); o += 12
    position_std = struct.unpack_from("<fff", frame, o); o += 12
    velocity_std = struct.unpack_from("<fff", frame, o); o += 12
    attitude_std = struct.unpack_from("<fff", frame, o); o += 12
    imu_ns = struct.unpack_from("<Q", frame, o)[0]; o += 8
    acceleration = struct.unpack_from("<fff", frame, o); o += 12
    angular_rate = struct.unpack_from("<fff", frame, o); o += 12
    gnss_ns = struct.unpack_from("<Q", frame, o)[0]; o += 8
    gnss_lla = struct.unpack_from("<ddd", frame, o); o += 24
    gnss_velocity = struct.unpack_from("<fff", frame, o); o += 12
    accuracies = struct.unpack_from("<ffff", frame, o)
    return {
        "sequence": sequence, "monotonic_ns": mono_ns, "utc_ms": utc_ms, "flags": flags,
        "init_source": init_source, "profile": profile, "fix_status": fix_status,
        "satellites": satellites, "gnss_age_ms": gnss_age_ms,
        "latitude_deg": latitude, "longitude_deg": longitude, "altitude_m": altitude,
        "velocity_n_mps": velocity[0], "velocity_e_mps": velocity[1], "velocity_d_mps": velocity[2],
        "roll_deg": euler[0], "pitch_deg": euler[1], "yaw_deg": euler[2],
        "position_std_n_m": position_std[0], "position_std_e_m": position_std[1], "position_std_d_m": position_std[2],
        "velocity_std_n_mps": velocity_std[0], "velocity_std_e_mps": velocity_std[1], "velocity_std_d_mps": velocity_std[2],
        "roll_std_deg": attitude_std[0], "pitch_std_deg": attitude_std[1], "yaw_std_deg": attitude_std[2],
        "imu_ns": imu_ns, "ax_mps2": acceleration[0], "ay_mps2": acceleration[1], "az_mps2": acceleration[2],
        "gx_radps": angular_rate[0], "gy_radps": angular_rate[1], "gz_radps": angular_rate[2],
        "gnss_ns": gnss_ns, "gnss_latitude_deg": gnss_lla[0], "gnss_longitude_deg": gnss_lla[1], "gnss_altitude_m": gnss_lla[2],
        "gnss_vn_mps": gnss_velocity[0], "gnss_ve_mps": gnss_velocity[1], "gnss_vd_mps": gnss_velocity[2],
        "gnss_horizontal_accuracy_m": accuracies[0], "gnss_vertical_accuracy_m": accuracies[1],
        "gnss_speed_accuracy_mps": accuracies[2], "gnss_bearing_accuracy_deg": accuracies[3],
    }


def open_tcp(address):
    host, port = address.rsplit(":", 1)
    sock = socket.create_connection((host, int(port)), timeout=10)
    sock.settimeout(None)
    return sock, sock.recv


def open_aoa():
    try:
        import usb.core
        import usb.util
    except ImportError as error:
        raise SystemExit("AOA requires pyusb and a system libusb installation") from error
    device = next(iter(usb.core.find(find_all=True)), None)
    if device is None:
        raise SystemExit("No USB device found")
    protocol = device.ctrl_transfer(0xC0, 51, 0, 0, 2)
    if not protocol:
        raise SystemExit("Connected device does not support Android Open Accessory")
    for index, value in enumerate(("Android INS", "PhoneINS", "100 Hz navigation receiver", "1.0", "https://example.invalid/phoneins", "0001")):
        device.ctrl_transfer(0x40, 52, 0, index, value.encode() + b"\0")
    device.ctrl_transfer(0x40, 53, 0, 0, None)
    time.sleep(2)
    device = usb.core.find(idVendor=0x18D1, custom_match=lambda d: d.idProduct in (0x2D00, 0x2D01))
    if device is None:
        raise SystemExit("Phone did not re-enumerate in AOA mode")
    device.set_configuration()
    interface = device.get_active_configuration()[(0, 0)]
    endpoint = next(ep for ep in interface if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN)
    return device, lambda size: bytes(endpoint.read(size, timeout=10_000))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--tcp", metavar="HOST:PORT")
    source.add_argument("--aoa", action="store_true")
    source.add_argument("--file", type=Path)
    parser.add_argument("--csv", type=Path, help="Decoded CSV output")
    parser.add_argument("--raw", type=Path, help="Raw frame output")
    parser.add_argument("--duration", type=float, default=0, help="Stop after N seconds (0 means indefinitely)")
    args = parser.parse_args()

    owner = None
    if args.tcp:
        owner, read = open_tcp(args.tcp)
    elif args.aoa:
        owner, read = open_aoa()
    else:
        owner = args.file.open("rb")
        read = owner.read

    csv_file = args.csv.open("w", newline="") if args.csv else None
    raw_file = args.raw.open("wb") if args.raw else None
    writer = None
    start = time.monotonic(); count = 0; missing = 0; previous = None
    try:
        for frame in frames_from_stream(read):
            packet = decode(frame)
            if writer is None and csv_file:
                writer = csv.DictWriter(csv_file, fieldnames=packet.keys()); writer.writeheader()
            if writer: writer.writerow(packet)
            if raw_file: raw_file.write(frame)
            if previous is not None:
                missing += (packet["sequence"] - previous - 1) & 0xFFFFFFFF
            previous = packet["sequence"]
            count += 1
            elapsed = time.monotonic() - start
            if count % 100 == 0:
                print(f"{count} frames · {count / elapsed:.2f} Hz · {missing} missing · GNSS age {packet['gnss_age_ms']} ms")
            if args.duration and elapsed >= args.duration:
                break
    finally:
        if owner: owner.close()
        if csv_file: csv_file.close()
        if raw_file: raw_file.close()
    elapsed = max(time.monotonic() - start, 1e-9)
    print(f"Final: {count} frames in {elapsed:.3f} s = {count / elapsed:.3f} Hz; {missing} missing")


if __name__ == "__main__":
    main()
