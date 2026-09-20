# Repository instructions

## Commits and versions

- Use Conventional Commit messages, for example `fix(navigation): prevent invalid GNSS updates`.
- Every commit that changes application, navigation, protocol, or tooling code must also
  increment `versionCode` and update `versionName` in `app/build.gradle.kts`.
- Use a patch version increment for fixes unless the user requests a different version.
- Keep the current app version in `README.md` consistent with the Gradle configuration.
- Run relevant tests and assemble the debug APK after changing code or the app version.
- Historical artifact names and verification records should keep their original versions;
  do not rename old release artifacts to imply they were rebuilt.
- The main development branch is named `master`. Do not rename it or push commits unless asked.

## Emulator hand-off after each task

- When a task that changed app behavior is done, build the debug APK, install it on the running
  emulator with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, and launch
  `com.example.phoneins/.MainActivity` so the user can inspect the result.
- Re-grant runtime permissions with `adb shell pm grant` after a fresh install clears them
  (location fine/coarse, notifications).
- Verify the activity actually resumed (`adb shell dumpsys activity activities`) and say which
  screen or flow to look at; leave the emulator on that state when practical.
