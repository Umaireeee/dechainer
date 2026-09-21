# Déchaîner (Schedules build) — complete guide

An unofficial build of [Déchaîner](https://github.com/warleysr/dechainer) by **@warleysr**, with a
recurring-schedules feature added and all blocking moved to system level. All credit for the app
goes to the original author. This is not an official release and is not supported by him.

Written for someone who has never used a blocker before. Sections 1–5 get you running. The rest is
reference — read it when you need it.

---

## Contents

1. [Read this first](#1-read-this-first)
2. [What the app actually does](#2-what-the-app-actually-does)
3. [Requirements](#3-requirements)
4. [Installation and setup](#4-installation-and-setup)
5. [Your first 15 minutes](#5-your-first-15-minutes)
6. [Restrictions tab](#6-restrictions-tab)
7. [Apps tab](#7-apps-tab)
8. [Groups](#8-groups)
9. [Schedules](#9-schedules)
10. [Config tab — every setting](#10-config-tab--every-setting)
    - [Status screen](#status-screen)
    - [Usage warnings](#usage-warnings)
11. [Impulse lock and the panic button](#11-impulse-lock-and-the-panic-button)
12. [Getting out](#12-getting-out)
13. [Troubleshooting](#13-troubleshooting)
14. [FAQ](#14-faq)
15. [Building it yourself](#15-building-it-yourself)

---

## 1. Read this first

**Write your recovery code on paper.** Not in a note on this phone. There is no reset, no email, no
support desk. Lose it and the only way out is a 48-hour wait.

**"Lock while active" cannot be undone while it is running.** Not with the recovery code, not by
uninstalling, not by removing the app's privileges. Don't turn it on until you have lived with a
schedule for a few days.

**Setup removes all accounts from the phone.** Google, Samsung, Xiaomi, everything. This is an
Android rule, not the app's choice, and it happens before anything is installed. Back up anything
you care about first. You can sign back in afterwards **only** if you don't enable the
"prohibit modifying accounts" restriction.

**This is a personal build.** It has been tested on an emulator by CI and on one real phone. If you
use it, you accept that you might have to sit out a 48-hour wait.

---

## 2. What the app actually does

Most blockers are apps politely asking you not to open things. You tap "unblock" and they let you.

Déchaîner makes itself the phone's **Device Owner** — the role an employer's IT department holds on
a work phone. From there it can suspend apps at operating-system level, switch off system features,
and refuse to be uninstalled. Your 1 a.m. self genuinely cannot click through it.

That power is the point and also the risk. Everything in section 1 follows from it.

**Everything stays on the phone.** No account, no server, no sync, no analytics.

### Vocabulary

| Term | Meaning |
|---|---|
| **Device Owner** | The privileged role the app holds. Granted once, at setup. |
| **Suspend** | Android greys the app out and refuses to launch it. Done by the system, not by an overlay. |
| **Restriction** | A system-level switch (installing apps, adding users, Bluetooth, and so on). |
| **Recovery code** | Your password for loosening anything. Unlocks a 10-minute session. |
| **Accessibility service** | An optional extra layer that sees which app is in the foreground. |
| **Forced removal** | The emergency exit. 48-hour wait. |

---

## 3. Requirements

- **Android 11 or newer**
- A phone you can remove all accounts from
- **Shizuku** installed (free, from Play Store or GitHub)
- 15 minutes

Rooted phones work but aren't required. Shizuku is the normal path.

---

## 4. Installation and setup

### 4.1 Get the APK

Download the APK from this repository's **Releases** page, or build it yourself (section 15).

If you already have an older build of this app installed, and you have **Disallow install apps**
switched on, turn that off with your recovery code before installing the update. Android will
refuse the install and will not tell you why.

### 4.2 Install Shizuku and start it

Shizuku gives ordinary apps the ability to run privileged commands. Déchaîner uses it to grant
itself Device Owner.

1. Install **Shizuku**.
2. Start it. On Android 11+ you can do this without a computer, using **Wireless debugging** in
   Developer options. Shizuku's own in-app guide walks through it — Déchaîner also has a
   **"See how to setup"** link that opens it.
3. Shizuku must be **running** each time you need it. It stops on reboot; you restart it the same
   way. Once Déchaîner has Device Owner you don't need Shizuku again.

### 4.3 Phone-specific preparation

Do this before the Device Owner step, or it will fail with an unhelpful error.

- **Xiaomi / Redmi / POCO:** in Developer options, enable both **USB debugging** and
  **USB debugging (Security settings)**. The second one is Xiaomi-only and easy to miss.
- **Samsung:** disable or uninstall **Secure Folder**. Android treats it as a second user profile,
  and Device Owner can't be granted while one exists.

### 4.4 Remove all accounts

Android refuses to grant Device Owner if any account exists on the device.

Déchaîner detects this and shows you the list under **"There can be no accounts on the device"**,
with a button to remove them. Do that. A factory reset also works but is rarely necessary.

### 4.5 Grant Device Owner

1. Open Déchaîner → **Config** tab (bottom right) → **Owner privileges**.
2. The screen walks through: Shizuku installed → Shizuku running → permission granted → accounts
   removed.
3. If another app already holds Device Owner, the screen offers to remove it. Only one app can have
   it at a time.
4. Tap **Grant privileges**.

You should now see the privileges confirmed. If this step fails, most of the app still works, but
schedules don't — the Schedules screen will say so.

### 4.6 Set your recovery code

The app asks for one. **Write it on paper now.** It's uppercase letters. You'll need it every time
you loosen anything.

### 4.7 Turn on the accessibility service

The app will offer it. Say yes.

What needs it: forbidden words, visual blocking, advanced blocking (blocking screens inside apps),
and the impulse-lock app suspension.

What does **not** need it: schedules, time limits, time windows, group limits, suspending apps, and
system restrictions. Those are enforced by the operating system and hold with the service switched
off.

### 4.8 Check it all worked

**Config → Status.** Every line under **Health** should be green. Anything that isn't has a **Fix**
button that opens the exact Settings page. Do this now rather than finding out a week later that
your schedules were firing late.

---

## 5. Your first 15 minutes

Do these in order. Don't skip to schedules.

**Step 1 — stop the reinstall loop.** Go to **Restrictions** and turn on:

- **Disallow install apps**
- **Disallow add user**

These two matter more than anything else in the app. Without the first, every block is one
reinstall away from useless. Without the second, a new user profile is a clean phone.

**Step 2 — block one app.** Go to **Apps**, tap the app you waste most time on, turn on **Suspend**.
It's now greyed out in your launcher and won't open.

**Step 3 — live with it for a few days.** Resist the urge to configure everything tonight. That's
how people end up locked out of something they needed and uninstalling the whole idea.

Turning protections **on** never asks for your recovery code. Turning them **off** always does.

---

## 6. Restrictions tab

System-level switches, grouped into sections. On until you turn them off. Turning one off needs the
recovery code.

The ones worth understanding:

| Restriction | Why it matters |
|---|---|
| **Disallow install apps** | Stops reinstalling what you blocked. The single most important switch here. |
| **Disallow add user** | Stops a second user profile being used as a clean phone. |
| **Disallow modify accounts** | Stops accounts being added or removed. **Turn this on only after you have signed back into any account you need** — otherwise you'll have to loosen it again. |
| **Prohibit factory reset** | Stops a reset being used to escape. Serious commitment; think before enabling. |
| **Prohibit network settings reset** | Stops DNS and network settings being reset to defaults. |
| **Prohibit system error dialogs** | Hides crash dialogs, which can otherwise be a route out of a blocked screen. |

There are many more, covering Bluetooth, Wi-Fi configuration, tethering, USB transfer, screen
capture and so on. Each has a short description in the app. Start with the two in section 5 and add
others only when you find a specific hole.

Schedules can apply any of these **temporarily**, for a window only. See section 9.

---

## 7. Apps tab

A searchable list of every installed app. **Show system apps** reveals the rest. Apps the app
protects (itself, launcher, dialer) are marked and cannot be blocked.

Tap any app for **Manage app restrictions**:

| Control | What it does |
|---|---|
| **Suspend** | Blocked now, indefinitely. Greyed out in the launcher. |
| **Block** | Hidden from the launcher entirely, not just greyed. |
| **Prevent uninstall** | The app cannot be removed. Useful on Déchaîner itself. |
| **Set time limit** | A daily allowance, e.g. 30 minutes. When it runs out the app is blocked until tomorrow. |
| **Time between app openings** | A forced wait before you can reopen it. Very effective against reflex reopening. |
| **Explicit content** | Turns on the on-device image scanner for this app. |
| **Time windows** | The hours during which the app may be opened at all. Outside them it is blocked. |

**All of these block at system level in this build.** The original blocked time limits and time
windows by pushing a screen in front of the app when the accessibility service noticed it — which
meant stopping that service let the app open normally. Here the app is suspended by Android itself,
so it holds with the service off, across reboots, and whatever launches it. The explanation screens
remain; they now explain rather than block.

---

## 8. Groups

A group applies one limit, or one set of hours, to several apps at once.

**To create one:** Apps tab → **⋮ menu** (top right) → **Manage groups** → create, name it, add
apps. Inside the group you can set a **time limit** and **time windows**.

A group time limit is **shared**: one hour across all of them, not one hour each. When it runs out,
**every app in the group** is blocked until tomorrow — not just the one you had open.

### The overlap rule — read this

An app can have its own hours **and** its group's hours. It gets the **overlap** of the two, never
the looser one.

- Group 18:00–22:00 + app's own 20:00–23:00 → that app works **20:00–22:00 only**.
- Group 18:00–22:00 + app's own 09:00–12:00 → these never overlap, so that app ends up with **no
  restriction at all**.

That second case surprises everyone. It is the original app's behaviour and this build matches it
deliberately, so the blocking and the on-screen explanation never disagree.

**In practice:** to set hours for a whole group, set them on the **group**, and leave the individual
apps' own hours empty unless you specifically want one app tighter still.

---

## 9. Schedules

**Config → Schedules.** The feature this build adds.

A schedule is a repeating window — say 22:00–06:00, Monday to Friday. While it is open it:

- **suspends the apps** you picked, at system level
- **applies the restrictions** you picked, for the duration only
- **blocks the websites** you picked, in Chrome and other Chromium browsers

When the window closes it releases **only what it applied**. Anything you suspended by hand, or
restricted permanently, is untouched.

### Creating one

1. **New schedule**. Creating never asks for your recovery code — it only adds blocking.
2. Name it.
3. Pick days. Shortcuts for Every day / Weekdays / Weekends.
4. Pick start and end times. An end earlier than the start runs past midnight; the screen says so.
5. Pick at least one of apps, services or websites.
6. Decide on **Lock while active** (below).
7. Save.

### Lock while active

**Off:** you can edit or disable the schedule any time, with your recovery code.

**On:** while the window is open the schedule is frozen — not editable, not disableable, not
deletable, recovery code or not. It also blocks removing the app's Device Owner privileges, so you
can't escape by dismantling the app. It thaws when the window ends.

Two guards, enforced when you save:

- A set of locked schedules leaving **less than an hour free across the whole week** is refused. The
  check wraps Sunday night into Monday morning, so splitting a lock at midnight doesn't get past it.
- A long locked window needs an extra confirmation.

These stop the worst accidents. They do not stop a 12-hour lock you simply regret.

### Date and time lock

While any schedule is enabled, the phone is forced to automatic time and the date/time settings are
blocked. Otherwise winding the clock forward would skip every window.

Turning this off needs the recovery code, and is refused while a locked window is open.

### What keeps it reliable

Enforcement doesn't depend on one timer. It re-runs from an exact alarm at each window boundary, on
boot, when the clock or time zone changes, after an app update, and every 30 seconds from the
accessibility service if it's on. Reinstall a blocked app mid-window and it is suspended again.

### Suggested first schedule

Your worst two or three apps, plus their websites, 22:00–07:00, every day, **Lock while active
off**. Turn the lock on a week later if the shape feels right.

---

## 10. Config tab — every setting

| Item | What it does |
|---|---|
| **Status** | What's blocked right now, why, and whether blocking is working. See below. |
| **Owner privileges** | The setup screen from section 4. Also where privileges are removed. |
| **Private DNS** | Pins the phone's DNS provider, so you can point it at a filtering resolver and not have it switched back. |
| **Browser restrictions** | A permanent website blocklist plus browser permission management. Works in Chrome and Chromium-based browsers; most others ignore managed settings. |
| **Advanced blocking** | Blocks specific *screens* inside apps rather than whole apps — YouTube Shorts while keeping YouTube, Instagram Reels while keeping messages. Needs accessibility. |
| **Blocked activities** | The list of screens blocked by the above. |
| **Visual blocking** | On-device detection of explicit images, GIFs and video. The model ships inside the APK; nothing leaves the phone. Needs accessibility. |
| **Forbidden words** | Blocks content containing words you choose, wherever they appear on screen. Needs accessibility. |
| **Block Torrent apps** | Automatically blocks newly installed torrent clients. |
| **Language** | App language. |
| **Schedules** | Section 9. |
| **Usage warning** | Notifications before a time limit runs out. See below. |
| **Impulse lock** | Section 11. |
| **Keyboard type** | Restricts which keyboard may be used. Matters more than it sounds: a keyboard with built-in GIF or web search is a hole through everything else. |
| **Change recovery code** | Asks for the current one first. |
| **Forced removal** | Section 12. |

### Status screen

The place to look when something seems wrong. Two sections:

**Health** — the conditions blocking depends on, each green or red:

| Check | If it's red |
|---|---|
| Device Owner | Nothing can be blocked at system level. |
| Accessibility service | Forbidden words, visual blocking and in-app screen blocking stop. Schedules, limits and windows keep working. |
| Exact alarms | Schedule and window boundaries can fire late. |
| Battery optimisation off | Android can put the app to sleep between checks. |
| Notifications | Usage warnings can't be shown. Nothing else is affected. |

Tap **Fix** on any red line to go straight to the Settings page for it, then tap refresh.

On Xiaomi, Redmi and POCO phones the screen also reminds you about MIUI's own battery manager,
which Android can't see and can't report. Set **Battery saver → No restrictions** and turn on
**Autostart** for Déchaîner, or it can be killed in the background regardless of everything above.

**Blocked right now** — every suspended or hidden app, with every reason it's blocked and when each
one ends:

- Schedule "Nights" · until 07:00
- Outside allowed hours · opens Tue 18:00
- Daily limit reached · until tomorrow
- Reopening cooldown · until 14:32
- Impulse lock
- Explicit content · until 15:00
- Suspended by you
- Hidden by you

An app can show several at once. It only opens when every one of them has ended.

The screen only reads — nothing on it can loosen a block.

### Usage warnings

**Config → Usage warning.** A notification before an app's or group's daily time limit runs out,
so it doesn't end mid-scroll without warning.

You set **stages** — for example 15 minutes silent, 5 minutes with sound, 1 minute vibrating. Each
fires once per limit per day. Android will ask for notification permission the first time.

The warning is cleared automatically the moment the limit is reached and the app is blocked.

---

## 11. Impulse lock and the panic button

**Config → Impulse lock.** Two separate things configured on one screen.

### The challenge to open Déchaîner

| Setting | What you must do to get in |
|---|---|
| **Off** | Nothing, opens immediately |
| **Normal** | Solve 5 addition problems with 2-digit numbers |
| **Hard** | Type 32 words correctly, one at a time |

This is friction against opening the app on impulse to undo something. It is not a password — the
recovery code is.

### The panic button

On the main screen: **"I'm having impulses — lock everything right now."**

It locks Déchaîner itself for a duration you choose, **15 minutes to 6 hours**, and there is no way
to undo it before the timer runs out.

Two modes:

- **Timer only** — blocks access to Déchaîner until the timer ends.
- **Timer and suspend apps** — also suspends a list of apps you pick, for the same period. Needs
  accessibility.

Use it when you feel a craving starting, before you start negotiating with yourself.

---

## 12. Getting out

**Recovery code** unlocks a 10-minute session. Anything that reduces protection asks for it;
anything that adds protection never does. Change it under Config.

**Forced removal** is the emergency exit. It releases everything and removes Device Owner after a
**48-hour wait**. The wait is the feature — it is long enough that the impulse that started it has
passed.

It works even while a schedule is locked. That's deliberate: a blocker you genuinely cannot escape
is a trap, and the wait is what makes the escape safe rather than instant.

---

## 13. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Something isn't working and you don't know what | **Config → Status.** Anything red under Health is the cause. |
| "Get owner privileges first" | Setup didn't complete. Config → Owner privileges. |
| Device Owner fails on a Samsung | Secure Folder counts as a second user. Remove it. Section 4.3. |
| Device Owner fails on a Xiaomi | Enable **USB debugging (Security settings)** too. Section 4.3. |
| Shizuku says it isn't running | Shizuku stops on reboot. Restart it via wireless debugging. Not needed once Device Owner is granted. |
| Device Owner grant fails | An account still exists on the device, or another app already holds Device Owner. The setup screen shows both. |
| Can't install an app or an update | **Disallow install apps** is on. Turn it off with your recovery code, install, turn it back on. |
| A window opened a few minutes late | The phone dozed the app. It self-heals at the next check. Allow the app to ignore battery optimisation, and allow exact alarms. |
| A blocked app still opens | The accessibility service is off, so only system-level blocks are running. Turn it back on. |
| Websites aren't blocked | You're in a browser that ignores managed settings. Use Chrome or another Chromium-based browser. |
| An app in a group isn't restricted | Its own hours don't overlap the group's. Section 8. |
| Don't know why an app is blocked | **Config → Status** lists every reason and when each ends. |
| Blocks stop overnight on a Xiaomi phone | MIUI battery manager. Battery saver → No restrictions, Autostart on. |
| Can't change the clock | Expected while a schedule is enabled. Section 9. |
| Locked out and it's genuinely a problem | Forced removal. 48 hours. |

---

## 14. FAQ

**Can I uninstall it normally?**
No. That's the point. Use forced removal.

**Does it send my data anywhere?**
No. No account, no server, no analytics. The image scanning model runs on the phone.

**Will it survive a reboot?**
Yes. Blocks are reapplied at boot.

**What if the app crashes or I clear its data?**
Device Owner and the system restrictions survive. Blocks are reapplied when it starts again.

**Can I use it without the accessibility service?**
Yes, with reduced features. Schedules, time limits, time windows, group limits, app suspension and
system restrictions all keep working — that's the main change in this build.

**Can I sign back into Google after setup?**
Yes, as long as you haven't turned on **Disallow modify accounts**. Sign in first, then enable it.

**Is this safe to use as my only phone?**
Only if you've read section 1 and you're comfortable with the 48-hour exit.

---

## 15. Building it yourself

This repository is the upstream source plus a `.patch` file that is applied at build time, which
keeps it easy to merge new upstream releases.

- `schedules_v11.patch` — the schedules feature, the system-level blocking changes, and the Status
  screen
- `.github/workflows/release3.yml` — builds, signs and tests

To build:

1. Fork this repository.
2. Add two repository secrets under **Settings → Secrets and variables → Actions**:
   `SIGNING_KEY_B64` and `SIGNING_PASSWORD`. The "Make signing key (run once)" workflow generates
   them.
3. **Actions → Release build v3 (tested) → Run workflow.**
4. When every job is green, download the APK from **Artifacts**.

The build applies the highest-numbered `.patch` in the repository root, stamps the version from the
run number (so every build installs as an upgrade over the last), runs unit tests, then runs the
app on two emulators. The emulator tests never enable the accessibility service, so anything
they pass is enforced by the operating system.

Building locally without the patch pipeline: apply the patch to a clean checkout with
`patch -p1 < schedules_v11.patch`, then `./gradlew assembleDebug`.

---

## Credits

Déchaîner is by **[@warleysr](https://github.com/warleysr/dechainer)**. This build only adds
schedules and changes how existing features enforce their blocks. Please report bugs in the
original app to the original repository, and bugs in the schedules feature here.
