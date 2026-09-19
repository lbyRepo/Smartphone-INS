# UI verification — v0.3.8

Version: `0.3.8` (`versionCode 12`). Branch: `feature/map-basemap-ui-polish`.

## Changes

- Compact Dashboard initialization/navigation/logging status, prominent speed and heading.
- Side-by-side map and summary on wide screens; stacked, scrollable layout with large fonts.
- Expandable outage explanation and a prominent **Resume GNSS aiding** action while active.
- Explicit X/Y/Z and N/E/D measurement labels; uninitialized uncertainties no longer appear as zero.
- Shorter initialization instructions with expandable detail; initialization errors remain visible.
- Automatic CSV logging status, elapsed file time, errors and filename separated from optional binary recording.
- Standard OpenStreetMap tiles replace the provider that returned API-key placeholder tiles. Attribution stays on the map; loading/failure messages offer a Grid fallback.
- Trail/auto-fit updates compare coordinates rather than capped history counts; scale bars choose a size that fits.

## Verification

- Navigation, protocol and app unit tests pass, including capped-history geometry and scale-bar regression tests.
- Emulator suite: 28 tests on Medium Phone API 36.1, including navigation, manual validation, map switching, replay controls, outage semantics and 200% font-scale reachability.
- Screenshot validation: 50 passing previews (four primary destinations × light/dark × portrait/landscape × 100/130/200% fonts, plus two outage states). Grid previews include non-empty INS/GNSS tracks.
- Representative rendered Dashboard, landscape, 200% text, Raw Data, Initialization and More screens were visually inspected. Content below the viewport remains scrollable.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Native emulator street-map smoke check: standard tiles rendered successfully with visible attribution and no API-key placeholder. Capture: `outputs/v0.3.8-map-smoke.png` (stopped/uninitialized state; not a live route test).

Screenshot baselines are under `app/src/screenshotTestDebug/reference/`.

## Limits

Host-rendered screenshots use Grid mode; they do not validate internet tile delivery.
Tile availability detection checks the viewport centre and is not a guarantee that every edge tile has loaded.
Standard street tiles remain light-coloured in dark app mode. Internet/cached coverage is best-effort; Grid is the offline fallback.
No physical-device, navigation-accuracy, thermal or sustained field-streaming claims are made by this UI verification.
No protocol layout or navigation-filter changes are included.
