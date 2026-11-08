# Privacy policy: Humble Planning for Android

**Publisher:** [Indumia](https://indumia.com/) · **Contact:** contact@indumia.com

Humble Planning for Android is a client for a Humble Planning server, usually run by your employer or organisation. That organisation decides what the server stores and is responsible for it. Its own privacy policy applies to your account and your planning data.

## What the app handles

| Data | Why | Where it goes |
|---|---|---|
| Sign-in identifier and password | Signing in | Only to the server address you entered. Never stored on the phone |
| Session of your account | Staying signed in | Encrypted on the phone, sent only to that server |
| Planning data, profile, attachments, conversations with the assistant | The app's purpose | Between the phone and your server. A copy of your own tasks is kept encrypted on the phone for offline reading |
| Device name, app version, language | Listing your signed-in devices, keeping the app compatible | To your server |
| Push token | Real-time alerts | To your server and, to deliver a push, to Google Firebase Cloud Messaging, possibly through the publisher's push relay, which stores nothing |

A push only holds an alert identifier: the content of alerts is read from your server, not sent through Google or the relay.

The publisher runs no server receiving your planning data and uses no advertising and no analytics. The AI assistant is optional and provided by your server: your questions go to your server, which sends them to the AI provider its administrator chose.

## Permissions

| Permission | Use |
|---|---|
| Camera | Scanning the sign-in QR code, only when you open the scanner. Images are neither stored nor sent |
| Notifications | Showing alerts and reminders |
| Alarms and reminders | Showing a reminder at the exact time |
| Run at start-up | Scheduling your reminders again after the phone restarts |

## Your choices

Alerts can be turned off in the app's settings or in your web profile. Signing out deletes the data kept on the phone, as does uninstalling the app. To access or delete your account data, contact your organisation's administrator of the server.

## Security

The connection to the server uses HTTPS. Data kept on the phone is encrypted with a key held by the Android Keystore, and excluded from device backups.
