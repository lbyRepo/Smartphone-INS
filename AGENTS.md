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
