## What does this change?

<!-- A short description of the change and why it is needed. -->

Closes #

## How was it tested?

<!-- Steps you followed, automated tests added or updated, devices or emulators used. -->

## Screenshots

<!-- For interface changes: before and after, phone and tablet if the layout differs, light and dark if colours change. -->

## Checklist

- [ ] One topic only, branched from `main`.
- [ ] `./gradlew :app:testDebugUnitTest` and `./gradlew :app:lintDebug` pass.
- [ ] User-facing texts are rows of `tools/textes.mjs`, in all four languages, and `node tools/textes.mjs` leaves no change.
- [ ] Changes to screens or to what the app sends were tried on an emulator against the test server (`tools/ui-tests/run.ps1`).
- [ ] Documentation updated when behaviour or configuration changes (`README.md`, `docs/`).
- [ ] No secrets, credentials or personal data.
- [ ] Every commit is signed off (`git commit -s`, see [CONTRIBUTING.md](https://github.com/Malfhas/HumblePlanning-Android/blob/main/CONTRIBUTING.md#developer-certificate-of-origin-dco)).
