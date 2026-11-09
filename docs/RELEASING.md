# Releasing the app

The app is published as a signed APK on GitHub Releases. Google Play and other stores come later, with the same key.

## The app key

`humbleplanning-upload.jks` (alias `humbleplanning-upload`, read through `signing.properties`) is **the app key**: every APK
ever published must be signed with it. Android refuses an update signed with another key, so losing it means users can
only reinstall. Keep the file and its passwords in a vault, outside this computer.

Certificate SHA-256: `D5:A0:03:F6:E0:B3:93:79:06:EF:CB:59:25:15:1F:07:DF:61:9E:2D:A3:AE:36:98:BC:64:BB:6C:90:24:76:47`.
`tools/release.ps1` refuses an APK signed with anything else.

When the app goes on Google Play, see `PLAY_STORE.md`: Play App Signing is set up with this same key, so phones that
installed it from GitHub take the updates from Google Play and the other way round.

## Version numbers

In `app/build.gradle.kts`, before each release:

- `versionName`: `major.minor.patch`, for example `1.2.0`. The server compares it with its minimum app version.
- `versionCode`: `major * 10000 + minor * 100 + patch`, for example `10200`. It must always increase.

## Building

```powershell
.\tools\release.ps1
```

From a committed state, it builds the release APK, checks its signature and its certificate, and writes
`dist\humbleplanning-<version>.apk` with its `.apk.sha256`. Without `app\google-services.json` it stops: add `-NoPush` to
publish without real-time push, in which case the app checks its alerts about every 15 minutes.

## Publishing

1. Change the version and add its section to `CHANGELOG.md` in a pull request (`main` is protected, commits signed off), merge it, then tag the merged commit: `git pull`, `git tag v1.2.0` and `git push origin v1.2.0`.
2. On GitHub: Releases > Draft a new release, tag `v1.2.0`, title `Humble Planning 1.2.0`, the changes in a few lines.
3. Attach `humbleplanning-1.2.0.apk` and `humbleplanning-1.2.0.apk.sha256`, then Publish release.

The APK must be attached to the release itself, not only to a CI run: that is what Obtainium and the download link read.

## Updates on the phones

- **Obtainium** (an open-source app) follows the GitHub releases: the user adds the repository address once and is told
  of each new version.
- **Forcing an update**: on each Humble Planning server, Settings > General > Mobile app, set "Minimum app version" to
  the new version and "App download page" to `https://github.com/<owner>/<repository>/releases/latest`. An older app
  then shows "Update required" and its button opens that page.

## Checks before publishing

- `.\tools\build.ps1 testDebugUnitTest` and `.\tools\build.ps1 lintDebug`
- `.\tools\ui-tests\run.ps1` against the local test server (see `tools/test-server`)
- Install the APK from `dist\` on a phone over an older version: it must update without uninstalling.
