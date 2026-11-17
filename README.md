# Euler's Lab

> **Archived — November 2026.** Development is paused; I plan to come back to it at the end of February 2027. The code builds and the last version (2.0.0) runs on my own devices, but issues and pull requests are closed while the repository is archived.

**The alarm you can't talk your way out of, and the focus and study system that guards the rest of the day.**

Euler's Lab (formerly **WakeProof**, renamed after the mathematician Leonhard Euler, 1707–1783) is my personal study and research instrument for **Android 14+ phones, tablets, Wear OS watches and an Ubuntu GNOME Wayland laptop**. It is sideloaded and not published on Google Play. Alarms have no dismiss button: ringing stops only after the phone's camera counts your push-ups, you answer your due flashcards, or you solve an unbroken run of mental arithmetic.

The Android application ID is still `com.ogenrwotdaniel.wakeproof` (as are the Kotlin packages and the watch and clipboard wire names), so existing installs, sync and watch pairing keep working.

- **Project page:** https://ogenrwotdaniel.github.io/eulers-lab/
- **Privacy policy:** https://ogenrwotdaniel.github.io/eulers-lab/privacy/
- **Documentation (PDF):** https://ogenrwotdaniel.github.io/eulers-lab/eulers_lab_documentation.pdf

---

## What it does

### Alarms and wake-up missions
- **Reliable delivery:** `AlarmManager.setAlarmClock()`, full-screen lock-screen alarms, a foreground ringing service, weekly repeats, and a Direct Boot copy so alarms ring even before the first unlock after a reboot.
- **One ringing device:** alarms sync between phone and tablet, but only the phone rings; the tablet stays silent unless told otherwise.
- **Locked 03:00 push-up alarm:** one uneditable daily alarm, 10 push-ups counted on-device by CameraX and ML Kit Pose Detection. No snooze, no emergency dismiss; if the camera fails, 10 sums in a row instead. It survives reboots, holds the volume up, and a paired watch rings two minutes later if the phone missed it. No frame is stored or sent.
- **Tomorrow's top three (19:40):** a fixed evening alarm that stops when you write the three things that matter most tomorrow. They show on Home the next day and sync to your other devices.
- **Flashcard review and mental-math missions:** answer up to 10 due SM-2 cards, or 1–10 arithmetic problems in a row (with an optional Euler mental-calculation mode: squares, determinants, powers of 2, modular arithmetic, totients and series).

### Focus sessions and study
- **Nuclear Focus:** fixed 1 h, 1 h 30, 2 h and 3 h sessions, or a session timed exactly to a calendar event, with **no pause and no stop**. Everything outside your study apps is walled for the whole session, breaks included, on phone, tablet and watch, with Do Not Disturb on. A session started on one device can be continued on another, and a block finished on both counts once.
- **Session summaries:** after each session the phone asks for ten minutes writing down what you studied (definitions, the key derivation, the next open question) and a photo of the page. Photos stay on the device (90 days) and are counted per course in the weekly report.
- **Forest and streak:** each focus block grows a tree that withers if you drift to another app; the streak counts verified focus minutes against a daily goal that rises by itself in the two weeks before an exam.
- **Study sounds:** seven seamless ambient loops made for the app, or your own music played inside the app. No Spotify or other music app.
- **Flashcards and AI help:** SM-2 spaced repetition, exam countdowns, parked thoughts (an *Adversaria* ledger of open questions), recall notes after each block, and an on-device weekly report. With your own Google Gemini key: card drafting from notes or photos, and a study chat with an Explain mode and a Socratic "Euler referee" mode.

### Distraction walls, launcher and devices
- **Fixed walls:** social, video, messaging, portal and browser apps open only **12:25–12:50 every day**. A night wall shuts everything but calls, Clock, Calendar and the camera (`18:00–08:00` on the phone, `20:00–03:00` on the tablet), and no session starts between 20:00 and 03:00. Exam weeks shut gated apps for the seven days before an exam. Walls are fixed in code; per-app caps can only be tightened. Notifications from walled apps are removed and their media paused.
- **Minimal home screen:** an optional dark, text-only launcher with fixed tool pins, today's calendar agenda with one-tap focus sessions, a Blackboard-only study browser, and an "other apps" list that reshuffles every four seconds.
- **Wear OS companion (`:wear`):** its own Nuclear Focus timer, tiles and a complication, a mirror of the phone's alarm, the backup alarm, its own walls for gated apps, and direct Firestore sync.
- **Cloud Sync and Euler's Lab Clip:** optional Firestore sync with tombstones (`docs/CLOUD_SYNC.md`), and an end-to-end encrypted (AES-256-GCM) clipboard for text and screenshots between Android and the Ubuntu laptop (`docs/CLIPBOARD.md`, `tools/clip/`).

---

## Tech stack

- **Modules:** `:app` (`minSdk 34`, `targetSdk 36`, `compileSdk 37`) and `:wear` (`minSdk 30`, `targetSdk 35`)
- **Build:** Gradle 9.8.2, AGP 9.5.1 with built-in Kotlin 2.4 (do **not** apply `org.jetbrains.kotlin.android`)
- **App:** Kotlin coroutines and Flow, Jetpack Compose with Material 3, Hilt, Room (WAL) and DataStore, WorkManager, Glance widget; Compose for Wear OS on the watch
- **Vision and AI:** CameraX, ML Kit Pose Detection and Barcode Scanning, optional Google Gemini (`gemini-3.8-flash`) through Retrofit and OkHttp
- **Laptop tools:** Python daemon and GNOME Shell extension for the clipboard (`tools/clip/`), sound generators (`tools/sounds/`)

## Building and testing

```bash
./gradlew :app:assembleDebug :wear:assembleDebug          # phone/tablet and watch APKs
./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest  # unit tests
./gradlew :app:lintDebug :wear:lintDebug                  # Android lint
(cd tools/clip && python3 -m pytest -q)                   # clipboard daemon tests
```

Cloud Sync needs your own `app/google-services.json` (see [`docs/CLOUD_SYNC.md`](docs/CLOUD_SYNC.md)); without it the app builds with sync switched off. AI features take a Google Gemini API key entered in **Settings**.

## Coming back in February 2027

Before building again: update the Gradle wrapper, AGP and dependencies, check the Android and Wear OS target-SDK requirements of the day, and run the three Gradle commands above. The watch's walls need usage, overlay and notification-listener access granted over adb.

## License

Euler's Lab is free software licensed under the [GNU General Public License v3.0](LICENSE).
