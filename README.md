# Déchaîner — Focus edition

A calm, minimal Android app for studying without your phone getting in the way: block apps on a schedule, and run a Pomodoro timer that keeps an honest log of your work.

> An unofficial fork of [Déchaîner](https://github.com/warleysr/dechainer) by **@warleysr**. All credit for the original app and its blocking engine goes to him. This build is not an official release and is not supported by him.

---

## What it does

### Focus: committed blocks that keep you honest
- **Focus block:** pick an end time and commit. Sessions and breaks (a long one every few sessions) run by themselves until then. All lengths are adjustable.
- **The phone is bricked for the whole block**, breaks included: only Déchaîner, incoming calls, your alarm clock, the Urge Journal, Quick Settings and any apps you allowed work. The clock is locked. **Nothing ends a block early**, not even your recovery code, so choose a length you can keep. Pause is there for physical work; the end time never moves.
- A short **chime** between phases, with **"Did you do the work?"** right on the notification. Your answer is logged.
- **Intention:** optionally write one line for the next session ("IAS 16, questions 1–10"). The question asks about exactly that.
- **Subjects and lectures:** tag each session (e.g. FAR, Tax, CAF 4), with an optional daily target per subject. From two-thirds of a lecture's length on, it asks how the lecture is going.
- **Daily goal:** a quiet progress bar for how many lectures you aim to finish each day.
- **Changeable chime sound:** any ringtone or your own file.

### Focus log
- A smooth line chart of your study time for the **last 14 days** or **last 12 weeks**, with your average marked.
- **Tap any point** to see that day's sessions: time, subject, intention, and whether you did the work.
- Hours **by subject**, so a neglected subject is obvious.
- **Export / import** as CSV (opens in Excel or Sheets), so your history survives a reinstall or a new phone.

### Blocking
- **Apps:** suspend any app with one switch. Suspended apps are greyed out, can't open, and their notifications are hidden.
- **Daily limits (Apps tab):** open an app's row to set how long it can be used each day. Once the time is used up, the app is suspended until midnight, and it stays suspended even if Usage access is switched off. Lowering a limit is free; raising or removing it takes your recovery code. Usage is read from Android's own usage log, so Déchaîner needs **Usage access** once (Settings → Status shows a Fix button). Nothing runs in the background to measure it.
- **Schedules:** block chosen apps, system features and websites at set times each week. "Allow only" mode flips it: only the apps you list work.
  - **Duplicate** a schedule, or **copy apps** from another, so you never pick the same apps twice.
  - The clock is locked while schedules are on, so a window can't be skipped by changing the time.
- **Impulse lock:** a panic button on the unlock screen that locks the app, and any apps you choose, for a set time.
- **Private DNS:** blocks adult sites across the whole phone. Choose from CleanBrowsing Family (strictest), CleanBrowsing Adult, AdGuard Family or Cloudflare Family.
- **Protections:** system rules, like blocking factory reset.
- **Recovery code and unlock delay:** anything that loosens a rule asks for your code, and optionally a waiting period.
- **Forced removal:** the escape hatch. It lifts everything after 4 days, without the code.
- **Quick-start presets:** Bedtime, Study hours, Exam week and Night detox open a pre-filled schedule for you to adjust.

### Urge Journal (companion app)
A second app in this repository, signed with the same key, for the moment an urge hits. One tap pauses your distraction apps and starts a ten-minute guided ride (breathing, one step, your own rule); a short check-in afterwards teaches the coach what actually works for you. A full log of every entry (chart, filters, CSV export) sits one tap away. It also offers an optional AI deep dive (bring your own key), a weekly deep dive with a Sunday reminder, your own "If ..., then ..." rules, a reason you write on a calm day that comes back during a ride and after a slip, your own record at the moment you need it, patterns counted from your entries on the phone, an evening check-in whose "first move for tomorrow" starts a focus block next morning, and a daily heads-up before the time of day your urges cluster. During a focus block, the Focus screen has an **Urge hit? Ride it out** button. Install Déchaîner first, then the journal. See the **[GUIDE](GUIDE.md)** (Step 11) and `URGE_ACTIONS.md`.

New here? Read the step-by-step **[GUIDE](GUIDE.md)**.

### Xiaomi, Redmi and POCO phones (MIUI / HyperOS)
These phones stop apps in the background unless told otherwise, which can delay alarms and the moment a block ends. For Déchaîner (and the Urge Journal):
1. *Settings → Apps → Manage apps → Déchaîner → **Autostart: On***.
2. On the same screen, *Battery saver* (or *Battery usage*) → **No restrictions**.
3. In *Recent apps*, lock the app (pull its card down or long-press it and tap the lock) so it isn't cleared.
Then open **Settings → Status** in Déchaîner: it should show no red items.

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
5. **Each block:** pick the subject, write your intention, and start a focus block for your study hours. When it chimes, answer honestly.
6. **Weekly:** check the log, see which subject is behind, and export a backup.

---

## Building

The source is committed directly (no patch files). The **CI** workflow runs the unit tests and builds a debug APK on every push. The **Release build** workflow additionally runs an emulator test and produces a signed APK; run it from the *Actions* tab and download the APK from the run's artifacts, or from the release it creates.

Locally: open the project in Android Studio, or run `./gradlew assembleDebug`.

---

## Privacy
Everything stays on your phone: no accounts, no analytics, no servers. The only network-related feature is the Private DNS setting, which hands your DNS lookups to the provider you choose.

---

## License
Apache License 2.0, the same as the original project. See [LICENSE](LICENSE).
