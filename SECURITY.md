# Security policy

## Supported versions

Security fixes are released for the latest version of the app. Please keep it up to date: [Obtainium](https://github.com/ImranR98/Obtainium) tells you of each new release, and administrators can require a minimum app version on their server.

| Version | Supported |
| --- | --- |
| 1.0.x | ✅ |
| < 1.0 | ❌ |

## Reporting a vulnerability

**Please do not report security vulnerabilities in public issues, discussions or pull requests.**

Report them privately through GitHub: [Report a vulnerability](https://github.com/Malfhas/HumblePlanning-Android/security/advisories/new) (*Security* tab → *Report a vulnerability*). If you cannot use GitHub, write to **contact@indumia.com** with `[security]` in the subject.

A vulnerability of the server (`/api/mobile` and every other route) belongs to the [server's security policy](https://github.com/Malfhas/HumblePlanning/security/policy). When in doubt, report it here: we forward it.

Please include:

- the app version (*Settings* in the app, or the sign-in screen), the Android version and the device;
- the version of the Humble Planning server, if it matters;
- a description of the vulnerability and its impact;
- the steps to reproduce it, or a proof of concept;
- any suggestion for a fix.

## What to expect

- An acknowledgement within 5 business days.
- An assessment of the report, and regular updates until it is resolved.
- A fix released as a new version, signed with the same key as every release.
- A published security advisory once the fix is available, crediting you unless you prefer to stay anonymous.

We ask you to give us a reasonable time to release a fix before any public disclosure, and at most 90 days from your report. Please do not access, modify or delete data that does not belong to you, and do not degrade the service of a server you do not own (including the public demo).

## Scope

In scope: the app, the APKs published in this repository's releases, and how the app stores and sends data (session, offline copy, push token, QR code sign-in).

Out of scope: vulnerabilities that require a rooted or already compromised phone, or a compromised server or administrator account; unsupported versions; builds that were not published here; issues caused by a configuration that the documentation advises against; and reports from automated scanners without a demonstrated impact.

## Hardening recommendations

- Install the app from this repository's releases only, and check that its signing certificate has the SHA-256 fingerprint given in the [README](README.md#installing).
- Serve the Humble Planning server over HTTPS: the app refuses any other address.
- Keep a screen lock on the phone. Signing out, or revoking the device from the web profile, deletes the data kept on the phone.
- Administrators: set a minimum app version after a security release, and give the "Use the mobile app" permission only to the roles that need it.
