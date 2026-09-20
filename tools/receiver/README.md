# Python receiver

No packages are needed for TCP or file decoding. Android Open Accessory mode additionally needs `pyusb` and a system `libusb`.

```bash
python3 phone_ins_receiver.py --tcp 127.0.0.1:29500 --duration 30 --csv capture.csv --raw capture.insbin
python3 phone_ins_receiver.py --file capture.insbin --csv decoded.csv
python3 phone_ins_receiver.py --aoa --csv capture.csv
```

For the Android emulator, run `adb forward tcp:29500 tcp:29500` before using the TCP command.

## Master INS reference sender (`master_ins_sender.py`)

Encodes and streams deterministic MST1 master-reference frames to the phone's master TCP server on port 29501 (`adb forward tcp:29501 tcp:29501` first), answering clock-sync requests so the phone can time-tag references. Replace `demo_reference()` with a real master INS feed to drive transfer alignment from a PC.
