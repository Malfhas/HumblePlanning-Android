# Google Play publication checklist

## Before the first upload

1. Create the app in the Play Console, with the package name `com.humbleplanning.app`. It cannot be changed afterwards.
2. **Play App Signing with the app's own key.** The APKs already published on GitHub are signed with `humbleplanning-upload.jks` (see `RELEASING.md`): when setting up Play App Signing, choose to use your own key and upload that one (the Console gives the export tool and its command), so the same signature carries on from GitHub to Google Play. Then create a **new, separate upload key** for sending bundles to the Console; a lost upload key can be replaced, the app key cannot.
3. Create a free Firebase project, add the Android app `com.humbleplanning.app`, place `google-services.json` in `app/`.
4. Host `docs/PRIVACY.md`, once completed, at a public address.
5. Deploy the server version with the mobile API on the server used for the review.
6. New personal developer accounts must run a closed test before production. Check the Console for the current requirement.

## Build

Increase `versionCode` and `versionName` in `app/build.gradle.kts`, then:

```powershell
.\tools\build.ps1 bundleRelease
```

Upload `app/build/outputs/bundle/release/app-release.aab`.

To force an update afterwards: Settings > General > Mobile app > Minimum app version on the server.

## App content answers

| Form | Answer |
|---|---|
| Ads | None |
| App access | Restricted: give the reviewer a server address and a test account, and turn off that account's second factor |
| Target audience | Adults, professional use |
| Content rating | Business or productivity, no sensitive content |
| News, government, health, finance | No |
| Exact alarm declaration (`SCHEDULE_EXACT_ALARM`) | User-chosen task reminders at a precise time. The user grants it from the settings; without it, reminders are approximate |
| Account deletion | Accounts are created by each server's administrator, the app has no sign-up. Check the current policy wording, and point to the administrator in the privacy policy |

### Data safety form

The publisher receives nothing: data goes to the server chosen by the user. Google's form counts data leaving the device in general, so declare what the app transmits to the user's server.

| Category | Collected | Shared | Purpose |
|---|---|---|---|
| Name, e-mail, phone, user ID | Yes | No | App functionality, account management |
| Calendar events (tasks) | Yes | No | App functionality |
| Files (attachments) | Yes, optional | No | App functionality |
| Messages to the assistant | Yes, optional | Yes, to the AI provider of the user's server | App functionality |
| Device ID (Firebase installation ID, for push) | Yes | No | App functionality |
| Encrypted in transit | Yes | | |
| Deletion on request | Through the server's administrator | | |

Have someone check these answers against the current form before submitting: they engage the publisher.

## Listing

**Name:** Humble Planning

**English, short description:**
Your team's planning on your phone, live, from your Humble Planning server.

**English, full description:**
Humble Planning is the open-source team planning application. This app connects to your organisation's Humble Planning server.

See your planning by day, week or month, filter by person, team, project or status, and watch it update as your team changes it. Create and edit tasks with several assignees, recurrence, attachments, places and resources.

Get alerts as soon as a task changes, set reminders before a task starts, and ask the AI assistant when your server offers it. Your own tasks stay available offline.

The app follows the permissions of your account. It needs a Humble Planning server: ask your administrator for its address, or sign in by scanning the QR code in your web profile.

Open source under the AGPL-3.0 license.

**Français, description courte :**
Le planning de votre équipe sur votre téléphone, en direct, depuis votre serveur Humble Planning.

**Français, description complète :**
Humble Planning est l'application open source de planning d'équipe. Cette application se connecte au serveur Humble Planning de votre organisation.

Consultez votre planning par jour, semaine ou mois, filtrez par personne, équipe, projet ou statut, et voyez-le se mettre à jour dès que votre équipe le modifie. Créez et modifiez des tâches avec plusieurs personnes, des récurrences, des pièces jointes, des lieux et des ressources.

Recevez une alerte dès qu'une tâche change, programmez des rappels avant le début d'une tâche, et interrogez l'assistant IA quand votre serveur le propose. Vos tâches restent consultables hors connexion.

L'application respecte les droits de votre compte. Elle nécessite un serveur Humble Planning : demandez son adresse à votre administrateur, ou connectez-vous en scannant le QR code de votre profil web.

Open source sous licence AGPL-3.0.

## Assets still to provide

| Asset | Requirement |
|---|---|
| App icon | 512 x 512 PNG, exported from the adaptive icon |
| Feature graphic | 1024 x 500 |
| Phone screenshots | At least 2 |
| Tablet screenshots | Recommended |
