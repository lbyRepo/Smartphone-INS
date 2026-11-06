# Reliability work, v0.3.20 — incomplete implementation

The five-fix plan is not complete. This change set implements the no-commit/version rules,
outage status/reacquisition latch, initial cancellable seek handling, recorded-time deadlines,
transport worker shutdown and queue clearing, Gradle trial inputs, source hashes and non-finite
velocity rejection. The app version is 0.3.20 / 24.

Still required before describing the plan as delivered:

- Shared replay event ordering and exact start/endpoint propagation with interpolated ECEF
  reference, two-second gap checks, and an explicit inconclusive result.
- Fully serialized replay controls and atomic generation-guarded publication; overlapping
  seek/stop/return-to-sensors regression tests.
- Complete per-connection transport generation handling and reconnect/drop-accounting tests.
- Initialization/replay emulator scenarios and final trajectory/error plots.

Fresh current-harness results (sampled checks, not exact endpoint acceptance): van 600–645 s
65.86 m / 11.1 degrees; van 900–945 s 72.28 m / 13.9 degrees. Both sampled checks pass.
August bicycle 157.10–202.10 s fails at 1061.54 m / 133.9 degrees; 495.10–540.10 s
fails at 168.83 m / 51.7 degrees. No interval or threshold was changed.

Van source SHA-256: `39387bf4b3c01f2bfec68d225ec6d5feed4635c4d767bb8a83c2888c37a98056`.
August bicycle source SHA-256: `3f78cd064b34fc5ba0e16f757fdb3019453c35ad264086fe6334b4819d05a51a`.
These are the read-only files under trial_data; September data was not used.

Navigation regression: 40 of 41 pass; the existing synthetic accuracy test fails. Protocol
tests including the new idle restart test pass. App unit tests including the new deadline
test pass (opt-in trial test is skipped in ordinary suite). Debug APK assembly succeeds.
The existing sampled harness's coverage limitations still apply to these results.

Focused emulator run: all five selected tests pass (four GNSS-toggle UI tests and the 100 Hz
TCP test). Full initialization/replay instrumentation was not run.

Do not interpret staging as readiness to release. No Git commit was created.
