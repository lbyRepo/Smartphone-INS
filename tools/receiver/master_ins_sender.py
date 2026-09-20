#!/usr/bin/env python3
"""MST1 master-reference sender and decoder for Smartphone INS TCP :29501.

Use `adb forward tcp:29501 tcp:29501`, then run this utility on the Mac. By
default it sends a deterministic straight-turn-straight reference. Integrators
can import encode_reference() and replace demo_reference() with their master INS.
"""
from __future__ import annotations

import argparse
import math
import socket
import struct
import threading
import time
import zlib

MAGIC = b"MST1"
VERSION = 1
HEADER = struct.Struct("<4sBBHHHIQq")
TYPE_REFERENCE, TYPE_SYNC_REQUEST, TYPE_SYNC_RESPONSE = 1, 2, 3
REFERENCE_PAYLOAD = struct.Struct("<II3d3d4d3d3f3fQ")
REQUIRED_FLAGS = 0x1F


def frame(message_type: int, sequence: int, monotonic_ns: int, utc_ns: int, payload: bytes) -> bytes:
    head = HEADER.pack(MAGIC, VERSION, message_type, HEADER.size, len(payload), 0,
                       sequence & 0xFFFFFFFF, monotonic_ns, utc_ns)
    body = head + payload
    return body + struct.pack("<I", zlib.crc32(body) & 0xFFFFFFFF)


def encode_reference(sequence: int, monotonic_ns: int, latitude: float, longitude: float,
                     altitude: float, velocity_ned: tuple[float, float, float],
                     quaternion_wxyz: tuple[float, float, float, float],
                     angular_rate: tuple[float, float, float]) -> bytes:
    payload = REFERENCE_PAYLOAD.pack(
        REQUIRED_FLAGS, 0, latitude, longitude, altitude, *velocity_ned,
        *quaternion_wxyz, *angular_rate, 0.05, 0.05, 0.10, 0.10, 0.10, 0.20,
        2_000_000,
    )
    return frame(TYPE_REFERENCE, sequence, monotonic_ns, time.time_ns(), payload)


def recv_frame(sock: socket.socket) -> tuple[int, int, bytes]:
    header = recv_exact(sock, HEADER.size)
    magic, version, message_type, header_bytes, payload_bytes, _, sequence, _, _ = HEADER.unpack(header)
    if magic != MAGIC or version != VERSION or header_bytes != HEADER.size or payload_bytes > 4096:
        raise ValueError("invalid MST1 header")
    payload = recv_exact(sock, payload_bytes)
    crc_bytes = recv_exact(sock, 4)
    if struct.unpack("<I", crc_bytes)[0] != zlib.crc32(header + payload) & 0xFFFFFFFF:
        raise ValueError("MST1 CRC mismatch")
    return message_type, sequence, payload


def recv_exact(sock: socket.socket, count: int) -> bytes:
    data = bytearray()
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError("master link closed")
        data.extend(chunk)
    return bytes(data)


def demo_reference(elapsed: float, speed: float) -> tuple[float, tuple[float, float, float], float]:
    if elapsed < 7.0:
        heading = 0.0
    elif elapsed < 10.0:
        heading = math.radians(30.0 * (elapsed - 7.0) / 3.0)
    else:
        heading = math.radians(30.0)
    yaw_rate = math.radians(10.0) if 7.0 <= elapsed < 10.0 else 0.0
    return heading, (speed * math.cos(heading), speed * math.sin(heading), 0.0), yaw_rate


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=29501)
    parser.add_argument("--rate", type=float, default=50.0)
    parser.add_argument("--speed", type=float, default=8.0)
    args = parser.parse_args()
    with socket.create_connection((args.host, args.port)) as sock:
        send_lock = threading.Lock()
        def respond_to_sync() -> None:
            while True:
                message_type, sync_sequence, payload = recv_frame(sock)
                if message_type == TYPE_SYNC_REQUEST:
                    phone_send_ns = struct.unpack("<Q", payload)[0]
                    master_receive_ns = time.monotonic_ns()
                    master_send_ns = time.monotonic_ns()
                    reply = struct.pack("<QQQ", phone_send_ns, master_receive_ns, master_send_ns)
                    with send_lock:
                        sock.sendall(frame(TYPE_SYNC_RESPONSE, sync_sequence, master_send_ns, 0, reply))
        threading.Thread(target=respond_to_sync, name="mst1-sync", daemon=True).start()
        start = time.monotonic()
        sequence = 0
        next_send = start
        while True:
            now = time.monotonic()
            if now >= next_send:
                master_ns = time.monotonic_ns()
                heading, velocity, yaw_rate = demo_reference(now - start, args.speed)
                q = (math.cos(heading / 2.0), 0.0, 0.0, math.sin(heading / 2.0))
                with send_lock:
                    sock.sendall(encode_reference(sequence, master_ns, 1.3521, 103.8198, 25.0, velocity, q, (0.0, 0.0, yaw_rate)))
                sequence += 1
                next_send += 1.0 / args.rate
            sleep = next_send - time.monotonic()
            if sleep > 0:
                time.sleep(min(sleep, 0.002))


if __name__ == "__main__":
    main()
