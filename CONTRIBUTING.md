# Contributing to Humble Planning for Android

Thank you for your interest in Humble Planning! Bug reports, ideas, translations and code are all welcome. This guide explains how to get started and what we check before a change is merged.

By taking part in this project, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to contribute

- **Report a bug**: open an [issue](https://github.com/Malfhas/HumblePlanning-Android/issues/new/choose) with the bug report form. Please search existing issues first. A bug of the server or of the web application belongs to the [server repository](https://github.com/Malfhas/HumblePlanning/issues).
- **Suggest an idea**: open a feature request, or start a conversation in the [Discussions](https://github.com/Malfhas/HumblePlanning/discussions) of the main repository if the idea needs shaping.
- **Ask a question**: use the [Discussions](https://github.com/Malfhas/HumblePlanning/discussions) rather than issues.
- **Translate**: the app is available in English, French, Spanish and German. Improving a translation is a great first contribution (see [Translations](#translations)).
- **Write code**: pick an issue labelled `good first issue` or `help wanted`, or propose your own change.
- **Report a security vulnerability**: never in a public issue. Follow the [security policy](SECURITY.md).

For anything larger than a small fix, please open an issue or a discussion first, so that we can agree on the approach before you spend time on it.

## Development setup

Requirements: JDK 21, the Android SDK with platform 37 (or Android Studio), Node.js (for the texts and the test tools), Docker (for the test server) and Git.

```bash
git clone https://github.com/<your-account>/HumblePlanning-Android.git
cd HumblePlanning-Android
./gradlew :app:assembleDebug        # Windows: .\tools\build.ps1
./gradlew :app:installDebug         # on an emulator or a phone
```

`local.properties` gives the path of the Android SDK (Android Studio writes it). A debug build needs no key and no Firebase project.

The app needs a Humble Planning server with the mobile API. The disposable test server of `tools/test-server` builds one from a checkout of the [server](https://github.com/Malfhas/HumblePlanning); see "Tests" in the [README](README.md#tests). Debug builds reach it from the emulator at `http://10.0.2.2:3100`.

## Project conventions

- **Language**: code, comments, identifiers, log messages and test names are in English.
- **User-facing text** never lives in the code: every message is a row of `tools/textes.mjs`, in **all four languages** (English, French, Spanish and German), and `node tools/textes.mjs` generates the `strings.xml` files. Never edit `strings.xml` by hand. If you cannot translate a message, copy the English text into the other columns and say so in your pull request.
- **Permissions**: the server decides. The app hides what the account may not do (`data/Access.kt`), but never relies on that alone.
- **Security**: no secrets, credentials or personal data in the code, the tests or the fixtures. `signing.properties`, keys and `app/google-services.json` are never committed.
- **Scope**: one topic per pull request. Unrelated refactoring, formatting or dependency changes make a review much harder.

## Checks before opening a pull request

The same checks run automatically on every pull request; running them locally saves a round trip:

```bash
node tools/textes.mjs && git diff --exit-code app/src/main/res   # texts generated from tools/textes.mjs
./gradlew :app:testDebugUnitTest                                   # unit tests
./gradlew :app:lintDebug                                           # Android lint (no errors allowed)
```

For a change to a screen or to what the app sends to the server, also run the end-to-end tests on an emulator against the test server (`.\tools\ui-tests\run.ps1`), and the API campaign (`node tools/api-tests/run.mjs`) if the server side is involved.

## Pull requests

1. Fork the repository and create a branch from `main` (for example `fix/grid-scroll-on-tablet` or `feat/month-filters`).
2. Make your change, with tests when it changes behaviour.
3. Sign off every commit (see [Developer Certificate of Origin](#developer-certificate-of-origin-dco)).
4. Open a pull request against `main` and fill in the template: what changes, why, how you tested it, and screenshots for interface changes (phone, and tablet when the layout differs).

Pull requests are squash-merged: the title of the pull request becomes the commit message on `main`, so make it a clear one-line summary. The maintainer may ask for changes, and keeps the final decision on what is merged.

`CHANGELOG.md` is updated by the maintainer when a release is prepared.

## Developer Certificate of Origin (DCO)

Humble Planning uses the [Developer Certificate of Origin](DCO) instead of a contributor license agreement. You keep the copyright of your contribution, which is distributed under the project license (AGPL-3.0-or-later). By signing off a commit, you certify that you wrote it or otherwise have the right to submit it under that license.

To sign off, add the `-s` flag when you commit:

```bash
git commit -s -m "Fix the week number on the grid header"
```

Git appends a line with the name and e-mail address of your Git configuration:

```
Signed-off-by: Jane Doe <jane.doe@example.com>
```

Use your real name or a consistent public identity, and an e-mail address you can be contacted at; anonymous contributions cannot be accepted. A check on each pull request verifies that every commit is signed off by its author. To fix a pull request after the fact:

```bash
git commit --amend -s --no-edit          # last commit only
git rebase --signoff main                # every commit of the branch
git push --force-with-lease
```

## AI-assisted contributions

AI coding assistants are welcome here: the maintainer uses them to develop Humble Planning too. The same rules apply to every contribution, however it was written: you remain responsible for what you submit, so make sure you understand every line, that it is tested and passes the checks, and that you have the right to submit it under the project license, as certified by your DCO sign-off. Mentioning the tools you used in the pull request is appreciated, not required.

## Translations

Translations live in `tools/textes.mjs`, one row per text with its English, French, Spanish and German versions. To improve a translation, edit the values (never the keys), run `node tools/textes.mjs` and commit both the script and the generated `strings.xml` files. To add a language, open an issue first: the server needs it too.

## License

By contributing, you agree that your contributions are licensed under the [GNU Affero General Public License v3.0 or later](LICENSE).
