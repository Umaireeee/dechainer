# Déchaîner 2.0

A calm, minimal Android app that locks your phone when you need it locked. It runs as Device Owner: one lock engine covers a full-screen blackout, scheduled focus sessions, recurring app-block schedules and daily time limits.

> An unofficial fork of [Déchaîner](https://github.com/warleysr/dechainer) by **@warleysr**. All credit for the original app and its blocking engine goes to him. This build is not an official release and is not supported by him.

The design rule: **tightening is easy, loosening is hard.** Anything that makes the lock stricter is one tap. Anything that weakens it needs your recovery code and the unlock delay. The lock never waits on the network.

---

## What it does

### Home and the Blackout button
Home is a large clock and one button, **Blackout**. Tap the clock or swipe up for the menu: Blackout, Focus, Schedules, Apps and limits, Settings. The same screen is shown during any lock, with the end time under the clock.

- **Blackout:** the phone locks at once for a length you choose (1 minute to 12 hours, 10 minutes by default). The screen turns full-screen pure black with one dim monospace countdown in the middle, and only incoming calls get through: the notification shade, Home, Recents, the power menu, the alarm clock and every other app are blocked. Nothing ends it early and a second tap never extends it.
- Triggers, from anywhere: the **Blackout** button, the **Quick Settings tile**, the icon **shortcut**, or the **assistant gesture** (long-press / corner swipe) once Déchaîner is set as your default assistant app.
- The countdown is driven by a monotonic trusted clock and the stored end time, so rebooting does not reset it.

### Focus sessions
A focus block is a committed lock until an end time you pick (10 minutes to 8 hours), or a **FOCUS** entry in the weekly timetable that starts it by itself. A usual session runs Pomodoro phases with a check-in, "Did you do the work?", after each focus phase; answering No starts a ten-minute reset (meditation, then something physical) inside the block. A special session is one continuous lock with a single question at the end. The end time never moves.

### Blocking
- **Apps:** suspend any app with one switch; suspended apps are greyed out and cannot open.
- **Daily limits:** set how long an app may be used each day; it is suspended until midnight when the time is used up. Lowering a limit is free; raising or removing it needs your recovery code.
- **Schedules:** block chosen apps, system features and websites at set times each week, or allow only the apps you list.
- **Private DNS:** blocks adult sites across the whole phone.
- **Protections:** system rules, such as blocking factory reset.
- **Lock when opening:** Déchaîner asks for its own pattern (a 3×3 dot pattern you draw, at least 4 dots) before it shows anything. You draw it the first time; it is separate from your phone's lock and from the recovery code. Your data is private and asks for the pattern again unless you drew it in the last five minutes. Five wrong tries in a row make the next try wait, longer each time. Turning the lock off, or changing the pattern, needs the recovery code, and the recovery code also clears a forgotten pattern.
- **Recovery code and unlock delay**, and **forced removal** (the escape hatch: it lifts everything after 4 days, without the code).
- Date, time and time zone are locked, and Force stop and Clear data are blocked for the app.

New here? Read the step-by-step **[GUIDE](GUIDE.md)**. The rules the app is built to are in [BLUEPRINT.md](BLUEPRINT.md).

### Xiaomi, Redmi and POCO phones (MIUI / HyperOS)
These phones stop apps in the background unless told otherwise, which can delay alarms and the moment a lock ends:
1. *Settings → Apps → Manage apps → Déchaîner → **Autostart: On***.
2. On the same screen, *Battery saver* (or *Battery usage*) → **No restrictions**.
3. In *Recent apps*, lock the app so it isn't cleared.
Then open **Settings**: the Setup status card should show no Fix buttons.

### Battery
There is no accessibility service and nothing watches your screen. Locks run on exact alarms, and every wake-up (boot, unlock, app open, alarm) recomputes the whole lock from stored data.

---

## Requirements
- Android 11 or newer.
- **Device owner permission.** This is what lets the app suspend apps and apply system rules. It is granted once, over ADB or Shizuku.

---

## Installation

1. **Install the APK** from the [Releases](../../releases) page.
2. **Remove all accounts** (Google and others) temporarily: *Settings → Passwords & accounts*. Android only allows a device owner when no accounts are signed in.
3. **Grant device owner**, in one of two ways:
   - **Shizuku:** open the app, go to *Settings → Device Owner*, and follow the steps.
   - **ADB, from a computer:**
     ```
     adb shell dpm set-device-owner io.github.warleysr.dechainer/.DechainerDeviceAdminReceiver
     ```
4. **Add your accounts back.**
5. Follow the first-run steps: notifications, usage access and the recovery code.

> ⚠️ **Don't uninstall to update.** Install new versions over the old one. Uninstalling removes device owner and wipes your data. Keep a backup file from **Settings → Your data**. If you are moving from the old two-app version, install this build over Déchaîner first, then uninstall the old Urge Journal; its entries are not migrated, so export them from the old journal first if you want a copy.

> Test a new build on an emulator or a spare phone before your daily phone (see BLUEPRINT.md section 14A). A bug in a lock can keep the phone locked for hours.

### Updating
If the installer says *"You can't install the app"*, a restriction is blocking installs. Turn off "unknown sources" or "installing apps" in *Settings → Protections*, or wait for any schedule that blocks installs to end. Then install the update.

---

## Building

The source is committed directly (no patch files). The **CI** workflow runs the unit tests and builds the debug and release APKs on every push. The **Release build** workflow additionally runs emulator tests and produces a signed APK; run it from the *Actions* tab and download the APK from the run's artifacts, or from the release it creates.

Locally: open the project in Android Studio, or run `./gradlew assembleDebug`.

---

## Privacy
Everything stays on your phone: no accounts, no analytics, no servers of ours, and no AI. The app never sends your data anywhere. The Private DNS setting is the only thing that talks to the outside world, and only to hand your DNS lookups to the provider you choose.

---

## License
Apache License 2.0, the same as the original project. See [LICENSE](LICENSE).
