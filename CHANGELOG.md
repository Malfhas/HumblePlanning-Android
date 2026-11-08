# Changelog

All notable changes to Humble Planning for Android are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] - 2026-11-08

### Added

- Renaming a conversation with the assistant, as on the web.

### Fixed

- Profile photos show instead of the initials.
- On the team grid, the title of a task over several days stays in sight while the grid scrolls.
- The back gesture closes a screen with the same animation as its back button.
- The back button of a task no longer covers its project on phones with a tall status bar.
- The date picker no longer stutters when going back from the typed date to the calendar.

## [1.0.0] - 2026-11-08

First release of the native Android app of Humble Planning. It needs a Humble Planning server that includes the mobile API (`/api/mobile`).

### Added

- Sign-in with a password and a second factor, or by scanning the QR code of the web profile; the account's permissions apply to everything, and a role needs the "Use the mobile app" permission.
- Planning by day, week and month, and a team grid like the web schedule, with pinch to zoom, collapsible teams, public holidays and filters by person, team, project and status; live updates as the team changes the schedule.
- Upcoming tasks of the next 30 days.
- Creating and editing tasks: several people, half days or hours, recurrence, places, resources, notes and attachments.
- Alerts when someone changes one of your tasks, through Firebase Cloud Messaging when the server is set up for it, otherwise checked in the background about every 15 minutes; reminders before a task.
- The AI assistant of the server, with an approval before any change, and the gauge of the monthly quota as on the web; once the quota is reached, the conversation stays readable and says when it resets.
- Your own tasks readable offline, encrypted on the phone.
- Signed-in devices listed and revocable; update required when the server sets a minimum app version; blocking screens for maintenance and for a withdrawn permission.
- Phones and tablets, light and dark themes, Material You colours, English, French, Spanish and German.

[1.0.1]: https://github.com/Malfhas/HumblePlanning-Android/releases/tag/v1.0.1
[1.0.0]: https://github.com/Malfhas/HumblePlanning-Android/releases/tag/v1.0.0
