# Compact Dashboard — v0.3.9

Version `0.3.9`, code `13`, on `feature/map-basemap-ui-polish`.
Prior v0.3.8 work was committed first as `36c7c06` (`fix(ui): improve dashboard readability and map reliability`).

## Changes

- One content-width initialization indicator: grey Not initialized, amber Initializing,
  green Initialized, red Initialization failed. No duplicate waiting banner.
- Plain GNSS aiding switch, On = corrections enabled, Off = forced outage.
  It sits below the Position map and directly above INS solution; the separate
  Dashboard speed/heading summary cards have been removed.
  No explanatory card or help button. Raw fixes remain available when aiding is off.
- 14 sp labels, small indicator padding, accessible switch state and 48 dp touch target.
- Alignment progress, coarse angles and error details appear on Init. Logging details
  remain on More; genuine navigation/logging warnings may still appear on Dashboard.
- Explicit initialization errors survive generic status-message updates and clear on
  retry, success, reset and source transitions. Waiting or motion restart is not failure.

## Visual checks

Inspected portrait, landscape, dark 200% font Dashboard previews and the four-state
indicator gallery in light/dark themes. No indicator/switch clipping observed.
The indicator text/background contrast ranges from 7.02:1 to 8.88:1.
All primary-screen baseline configurations remain available under
`app/src/screenshotTestDebug/reference/`; four additional indicator previews cover
light/dark at 100% and 200% font scale.

## Verification commands

Result: unit tests and debug assembly passed; 30 emulator tests and 54 screenshot
checks passed with no failures.

`./gradlew :navigation:test :protocol:test :app:testDebugUnitTest :app:assembleDebug`

`./gradlew :app:connectedDebugAndroidTest :app:validateDebugScreenshotTest`

Emulator checks include switch dispatch in both directions, disabled state and
200% reachability, no duplicate initialization banner, Init-page details, and a
real service failed-initialization → retry → success → reset sequence.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
No navigation-filter, binary-frame or CSV-schema changes; physical-device accuracy
and field-streaming performance are outside this UI verification.
