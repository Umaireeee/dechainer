# Déchaîner 2.0

A calm, minimal Android app that locks your phone when you need it locked, and shows you the truth about yourself. It runs as Device Owner and does two jobs:

1. **Lock the phone when it is needed:** a ten-minute urge lock, scheduled focus sessions, recurring app-block schedules and daily time limits, all on one lock engine.
2. **Show you the truth about yourself:** it records urges, slips, focus check-ins and a daily checklist, and an AI you choose turns that data into a deep dive after every urge and weekly, monthly and yearly reports, with progress graphs.

> An unofficial fork of [Déchaîner](https://github.com/warleysr/dechainer) by **@warleysr**. All credit for the original app and its blocking engine goes to him. This build is not an official release and is not supported by him.

The design rule: **tightening is easy, loosening is hard.** Anything that makes the lock stricter is one tap. Anything that weakens it needs your recovery code and the unlock delay. The lock never waits on the network or the AI.

---

## What it does

### Home and the Urge button
Home is a large clock and one button, **Urge**. Tap the clock or swipe up for the menu: Urge, Today, Focus, Schedules, Apps and limits, Reports, Settings. The same screen is shown during any lock, with the end time under the clock.

- **Ongoing:** the phone locks at once for ten minutes (calls, the dialer and the alarm clock stay open; nothing ends it early and a second tap never extends it). A breathing screen runs for the full ten minutes, then you may write what happened, answer three to five questions (from the AI, or three fixed ones when it is offline), and get a short deep dive. The raw text is deleted once its deep dive is saved.
- **I slipped:** straight to writing, then questions and a deep dive. No shaming.
- The Quick Settings tile and the icon shortcut start the ongoing path without a question.
- If a note shows risk, a card offers to call or text one person you saved. That number never leaves the phone.

### Focus sessions
A focus block is a committed lock until an end time you pick (10 minutes to 8 hours), or a **FOCUS** entry in the weekly timetable that starts it by itself. A usual session runs Pomodoro phases with a check-in, "Did you do the work?", after each focus phase; answering No starts a ten-minute reset (meditation, then something physical) inside the block. A special session is one continuous lock with a single question at the end. The end time never moves.

### The daily checklist
Each evening, 20:00 to 23:59, you write tomorrow's 3 to 7 goals and close today. A goal can be ticked by you, or measured (focus minutes reached, or no slip that day); unfinished goals can be carried over with one tap. Each finished day is recorded honestly: how much was done and, if it fell short, why. Nothing locks the phone because of it; the results show on Today, in the graphs and in the reports. One rest day in any seven days skips that day's goals and scheduled focus sessions.

### Reports and progress graphs
When a week, a calendar month or a year with something recorded ends, the AI you chose writes a report from your numbers, answers, deep dives, focus sessions and goals: what went well, the patterns that repeat (time of day, day of the week, the earliest link), how the period compares with the ones before, and a few if-then plans for the next one. It never sees your raw urge notes. A report due while the phone was off is made the next time the phone is on and online. **Reports** opens on graphs of urges, slips, focus hours and goals done over 28 days, 12 weeks or 12 months; tap a bar to see that period, or show them as a table. It reminds you to save a backup when the last one is a month old. Under **Your data** you can delete entries, back everything up to a file, read a backup back in, or wipe what the rules allow.

### Blocking
- **Apps:** suspend any app with one switch; suspended apps are greyed out and cannot open.
- **Daily limits:** set how long an app may be used each day; it is suspended until midnight when the time is used up. Lowering a limit is free; raising or removing it needs your recovery code.
- **Schedules:** block chosen apps, system features and websites at set times each week, or allow only the apps you list.
- **Private DNS:** blocks adult sites across the whole phone.
- **Protections:** system rules, such as blocking factory reset.
- **Lock when opening:** Déchaîner asks for its own pattern (a 3×3 dot pattern you draw, at least 4 dots) before it shows anything. You draw it the first time; it is separate from your phone's lock and from the recovery code. An **Urge** button on the opening screen starts the urge lock and the breathing without drawing anything; the writing, questions and deep dive are private and wait until you draw the pattern. Urge entries, deep dives and reports are private: they ask for the pattern again unless you drew it in the last five minutes. Five wrong tries in a row make the next try wait, longer each time. Turning the lock off, or changing the pattern, needs the recovery code, and the recovery code also clears a forgotten pattern.
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
5. Follow the first-run steps: notifications, usage access, the recovery code, an optional AI key, and the checklist rules.

> ⚠️ **Don't uninstall to update.** Install new versions over the old one. Uninstalling removes device owner and wipes your data. Keep a backup file from **Reports → Your data**. If you are moving from the old two-app version, install this build over Déchaîner first, then uninstall the old Urge Journal; its entries are not migrated, so export them from the old journal first if you want a copy.

> Test a new build on an emulator or a spare phone before your daily phone (see BLUEPRINT.md section 14A). A bug in a lock can keep the phone locked for hours.

### Updating
If the installer says *"You can't install the app"*, a restriction is blocking installs. Turn off "unknown sources" or "installing apps" in *Settings → Protections*, or wait for any schedule that blocks installs to end. Then install the update.

---

## Building

The source is committed directly (no patch files). The **CI** workflow runs the unit tests and builds the debug and release APKs on every push. The **Release build** workflow additionally runs emulator tests and produces a signed APK; run it from the *Actions* tab and download the APK from the run's artifacts, or from the release it creates.

Locally: open the project in Android Studio, or run `./gradlew assembleDebug`.

---

## Privacy
Everything stays on your phone: no accounts, no analytics, no servers of ours. Only derived data goes to the AI provider you choose, with your key and your consent: the questions and deep dive get the note you just wrote (then it is deleted), the deep dive also gets a short summary of your last month (when earlier urges happened, the earliest link and plan their deep dives found), and the reports get numbers, answers, deep dives, sessions and checklist goals, never raw notes. Your crisis contact is never sent. The Private DNS setting hands your DNS lookups to the provider you choose.

---

## License
Apache License 2.0, the same as the original project. See [LICENSE](LICENSE).
