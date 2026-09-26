# Déchaîner — Focus edition

A calm, minimal Android app for studying without your phone getting in the way: block apps on a schedule, and run a Pomodoro timer that keeps an honest log of your work.

> An unofficial fork of [Déchaîner](https://github.com/warleysr/dechainer) by **@warleysr**. All credit for the original app and its blocking engine goes to him. This build is not an official release and is not supported by him.

---

## What it does

### Focus: a Pomodoro that keeps you honest
- Focus sessions and breaks, with a long break every few sessions. All lengths are adjustable.
- **An alarm that rings until you stop it** when a session or break ends. It works with the screen off and the app closed.
- Breaks and the next session **wait for you to start them**, or start automatically if you prefer.
- **"Did you do the work?"** When a session ends, a full-screen Yes/No question appears, even over the lock screen. Your answer is logged.
- **Intention:** optionally write one line before starting ("IAS 16, questions 1–10"). The end-of-session question asks about exactly that.
- **Subjects:** tag each session (e.g. FAR, Tax, CAF 4), with an optional daily target per subject.
- **Daily goal:** a quiet progress bar for how many sessions you aim for each day.
- **Changeable alarm sound:** any ringtone or your own file.

### Focus log
- A smooth line chart of your study time for the **last 14 days** or **last 12 weeks**, with your average marked.
- **Tap any point** to see that day's sessions: time, subject, intention, and whether you did the work.
- Hours **by subject**, so a neglected subject is obvious.
- **Export / import** as CSV (opens in Excel or Sheets), so your history survives a reinstall or a new phone.

### Blocking
- **Apps:** suspend any app with one switch. Suspended apps are greyed out, can't open, and their notifications are hidden.
- **Schedules:** block chosen apps, system features and websites at set times each week. "Allow only" mode flips it: only the apps you list work.
  - **Duplicate** a schedule, or **copy apps** from another, so you never pick the same apps twice.
  - The clock is locked while schedules are on, so a window can't be skipped by changing the time.
- **Impulse lock:** a panic button on the unlock screen that locks the app, and any apps you choose, for a set time.
- **Private DNS:** blocks adult sites across the whole phone. Choose from CleanBrowsing Family (strictest), CleanBrowsing Adult, AdGuard Family or Cloudflare Family.
- **Protections:** system rules, like blocking factory reset.
- **Recovery code and unlock delay:** anything that loosens a rule asks for your code, and optionally a waiting period.
- **Forced removal:** the escape hatch. It lifts everything after 48 hours, without the code.

### Battery
Nothing runs in the background and nothing watches your screen: **there's no accessibility service**. Schedules, the impulse lock and the Pomodoro all run on exact alarms, and the timer's countdown is drawn by Android itself.

---

## Requirements
- Android 11 or newer.
- **Device owner permission.** This is what lets the app suspend apps and apply system rules. It's granted once, over ADB or Shizuku.

---

## Installation

1. **Install the APK** from the [Releases](../../releases) page.
2. **Remove all accounts** (Google and others) temporarily: *Settings → Passwords & accounts*. Android only allows a device owner to be set when no accounts are signed in.
3. **Grant device owner**, in one of two ways:
   - **Shizuku:** open the app, go to *Settings → Owner privileges*, and follow the steps.
   - **ADB, from a computer:**
     ```
     adb shell dpm set-device-owner io.github.warleysr.dechainer/.DechainerDeviceAdminReceiver
     ```
4. **Add your accounts back.**
5. **Allow notifications** when asked. The Pomodoro rings through them.

> ⚠️ **Don't uninstall to update.** Install new versions over the old one. Uninstalling removes device owner and wipes your settings. Export your focus log regularly, just in case.

### Updating
If the installer says *"You can't install the app"*, a restriction is blocking installs. Temporarily turn off "unknown sources" or "installing apps" in *Settings → Protections*, or wait for any schedule that blocks installs to end. Then install the update, and turn the restriction back on afterwards.

---

## A simple daily setup

1. **Subjects:** on the Focus screen, tap *Add subject* and create your subjects. Give each one a daily target.
2. **Timer:** in the timer settings, set focus to your lecture length (e.g. 60 min) and set a daily goal (e.g. 4).
3. **Study schedule:** create one schedule for your study hours with *Allow only* on. List just your lecture app, notes app, PDF reader and one AI chat app. Everything else is suspended automatically.
4. **DNS:** in *Settings → Private DNS*, choose **CleanBrowsing Family**.
5. **Each session:** pick the subject, write your intention, press Start. When it rings, answer honestly.
6. **Weekly:** check the log, see which subject is behind, and export a backup.

---

## Building

Builds run on GitHub Actions. The workflow applies the newest `schedules_vNN.patch` from the repo root to the committed source, runs the unit tests and an emulator test, and produces a signed APK.

1. Put the latest patch file in the repo root.
2. Run the release workflow from the *Actions* tab.
3. Download the APK from the run's artifacts, or from the release it creates.

Building locally with Android Studio also works: apply the patch with `patch -p1 < schedules_vNN.patch`, delete `app/src/main/assets/models`, then build.

---

## Privacy
Everything stays on your phone: no accounts, no analytics, no servers. The only network-related feature is the Private DNS setting, which hands your DNS lookups to the provider you choose.

---

## License
Apache License 2.0, the same as the original project. See [LICENSE](LICENSE).
