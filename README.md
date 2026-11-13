<div align="center">
<img src="docs/logo.png" width="128" alt="Déchaîner logo">

# Déchaîner

**A hard-to-bypass Android barrier against distracting apps and digital addiction.**

Device Owner restrictions and a lightweight Accessibility Service working together,<br>
built so that your future self can't simply turn it off.

[![Release](https://img.shields.io/github/v/release/warleysr/dechainer?style=flat-square&color=2e6b3f)](https://github.com/warleysr/dechainer/releases/latest)
[![Android](https://img.shields.io/badge/Android-11%2B-3ddc84?style=flat-square&logo=android&logoColor=white)](#installation)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7f52ff?style=flat-square&logo=kotlin&logoColor=white)
[![License](https://img.shields.io/github/license/warleysr/dechainer?style=flat-square&color=b8860b)](LICENSE)

[**Download the latest APK**](https://github.com/warleysr/dechainer/releases/latest) · [How it works](#how-it-works) · [Features](#features) · [Installation](#installation) · [Recovery](#recovery-and-safety)

</div>

>[!TIP]  
>"In your struggle against sin, you have not yet resisted to the point of shedding your blood." - Hebrews 12:4

<br>

> [!CAUTION]
> This software implements deep system-level modifications and is designed to be difficult to bypass. Proceed only if you fully understand the implications of Device Owner privileges. Read [Recovery and Safety](#recovery-and-safety) before installing.

## Why Déchaîner?

**Déchaîner** (pronounced [/de.ʃɛ.ne/](https://en.wiktionary.org/wiki/d%C3%A9cha%C3%AEner)) is a French verb meaning *to unleash, unchain, let loose*. Most blockers are one settings screen away from being disabled in a weak moment. Déchaîner is built the other way around:

| | Typical blocker | Déchaîner |
|---|---|---|
| Can be uninstalled on impulse | Yes | No, Device Owner prevents it |
| VPN / private DNS can be changed to get around it | Usually | Blocked at the OS level |
| Turning it off | A toggle | A 16-character recovery code stored on paper |
| Your data | Often sent to a server | Never leaves the device: the app has no network permission |

## How it works

A regular Android app lives in a sandbox. It can't stop you from changing system settings, and it can be uninstalled with two taps. That's why most blockers are so easy to get around.

Déchaîner is granted two special powers that ordinary apps never get:

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>1. Device Owner</h3>
      <p>The same level of control companies use to manage work phones. It lets Déchaîner:</p>
      <ul>
        <li>lock system settings such as VPN, private DNS, factory reset and app installs</li>
        <li>suspend apps, and stop any app from being uninstalled, including itself</li>
      </ul>
    </td>
    <td width="50%" valign="top">
      <h3>2. Accessibility Service</h3>
      <p>The same access screen readers use. Déchaîner only listens for <em>which app or screen came to the front</em>, never to what is on screen, so it stays idle while you use an app. It lets Déchaîner:</p>
      <ul>
        <li>enforce time limits and allowed hours per app</li>
        <li>block specific screens inside an app</li>
      </ul>
    </td>
  </tr>
</table>

Both are granted once, during setup, through [Shizuku](https://shizuku.rikka.app/). From then on, the Accessibility Service is protected against being turned off, and only your **recovery code** can take either power away. That code lives on a piece of paper, not in your head.

## Highlights

| | |
|---|---|
| 🛡️ **Device Owner restrictions** | Block VPNs, private DNS changes, factory reset, app installs, USB debugging and dozens more `UserManager` policies. |
| ⏱️ **Time limits** | Daily limits per app or group, per weekday, with a lock screen when time is up and an optional cooldown before reopening. |
| 🗓️ **Schedule** | Pick the hours and days an app can be used with a clock picker. Outside them it is blocked. |
| 🌙 **Downtime** | Suspend apps or whole groups on a schedule, such as at bedtime. They stop completely and come back when it ends. |
| ⏸️ **Suspend** | One tap to suspend an app, or select several and suspend or group them together. |
| 🚧 **Activity blocking** | Block individual screens inside an app, such as a Reels tab. |
| 🚨 **Impulse lock** | A panic button on the lock screen that locks Déchaîner (and chosen apps) for 15 minutes to 6 hours, resistant to clock changes. |

## Features

<details>
<summary><b>Apps</b></summary>

- Apps are grouped by this week's real screen time, not by Android's "system app" flag, since apps like YouTube or Chrome are marked as system on many phones:
  - **With rules:** apps that already have a limit, schedule, suspension or group rule.
  - **Most used this week**, then **Rarely used**, **Not used this week** and **Other system tools** (collapsed).
- Search, filter chips (All, Limited, Suspended, System), a one-tap suspend switch on every row, and long-press multi-select to suspend or group several apps at once.
- Groups share a limit and a schedule across their apps. Déchaîner suggests groups from your installed apps (social, games, video) and creates one in a tap.
- Ranking needs usage access. Déchaîner tries to grant it through Shizuku, and otherwise links to the Settings screen. Without it, apps are listed A–Z.

</details>

<details>
<summary><b>Time limits</b></summary>

- A daily limit per app or per group, optionally different for each weekday. When both an app and its group have a limit, whichever runs out first applies.
- A lock screen once the limit is reached, and an optional minimum cooldown before an app can be reopened.
- Time is tracked with a single timer while a limited app is open and saved every 5 minutes, so a killed service loses at most that much.

</details>

<details>
<summary><b>Schedule</b></summary>

- Each app or group can have several time slots, each with a start and end chosen on a clock, and the days it applies to. A slot that ends before it starts runs into the next day.
- Quick presets: Evenings, After work, Weekends.
- Every slot has an on/off switch. An app with slots but none for today is closed all day.
- Making a schedule stricter applies after a short undo window; allowing more time needs the recovery code.

</details>

<details>
<summary><b>Downtime</b></summary>

- A downtime rule suspends chosen apps and groups while its windows run, on the days you pick, and restores them afterwards. Unlike allowed hours, which block an app when you open it, suspended apps stop entirely and their notifications stop too.
- Windows use the same clock picker, days and on/off switches as the schedule; a window that ends before it starts runs past midnight. Presets: Bedtime, Work hours, Weekends.
- It costs no battery while idle: one exact alarm is set for the next start or end. It is re-armed after a reboot, an update or a clock change.
- Downtime only gives back the apps it suspended itself, so it never ends a suspension you made by hand or one from the impulse lock.
- Shortening a window, dropping a day or removing apps needs the recovery code; making downtime stricter applies after a short undo window.

</details>

<details>
<summary><b>Activity blocker</b></summary>

- Blocks specific Android screens (activities) by class name, with a log of recently accessed activities to help identify which ones to block.

</details>

<details>
<summary><b>Impulse lock and the unlock delay</b></summary>

- A panic button on the lock screen, reachable even before authenticating: starts a 15-minute to 6-hour lock on Déchaîner itself, optionally also suspending a user-chosen list of apps for the same duration.
- The timer resists system clock changes and survives the app or its service being restarted.
- Opening Déchaîner requires biometric or device authentication. Entering the recovery code can also require an **unlock delay**: playing Tetris for a configurable number of minutes (time only counts while actually playing). When the time is up the game keeps going until you tap "Continue", so the current piece or line can be finished. Switch it on in the Protection tab under Impulse lock.

</details>

<details>
<summary><b>System restrictions (Device Owner)</b></summary>

- Every Android system restriction (`UserManager` policy) can be toggled on. Three are pre-selected as recommended: block VPN configuration, block private DNS configuration, and block factory reset. Dozens of others are available in a searchable list (blocking app installs/uninstalls, safe boot, USB/ADB debugging, adding accounts, and more, depending on Android version).
- Per-app restriction editor: any restriction an individual app declares (via `RestrictionsManager`) can be inspected and applied to that app specifically.

</details>

<details>
<summary><b>Other safeguards</b></summary>

- Optional shuffled keypad, so the recovery code can't be memorized by watching finger position.
- Any configuration change that loosens a rule requires the recovery code, which unlocks a 10-minute session (renewable) so it isn't asked for on every single action.
- A log of every suspension and why it happened.

</details>

## Installation

**Requirements:** Android 11 or newer.

Becoming Device Owner requires a bridge between user space and system space. Follow these steps precisely:

1. **Install [Shizuku](https://shizuku.rikka.app/).** It's used to run the required ADB commands on the device itself.
2. **Enable Wireless Debugging** in Developer Options and pair it with Shizuku.
3. **Download and open Déchaîner** from the [latest release](https://github.com/warleysr/dechainer/releases/latest), and grant it access to Shizuku.
4. **Become Device Owner.** In the Protection tab, follow the prompts to register the app as Device Owner. Déchaîner automatically bypasses the setup completion check so there is **no need to remove user accounts** from the device.
5. **Enable the Accessibility Service.** Still in the Protection tab, turn on *Advanced blocking* (also applied through Shizuku). It's required for time limits, schedules and activity blocking. App installation is blocked while this service is off, so a new app can't be installed in the meantime.

## Recovery and Safety

During setup, Déchaîner generates a unique **16-character recovery code**. It's the only ordinary way to disable restrictions or uninstall the app without a full device wipe (if a wipe is even permitted by your active settings).

A step-by-step wizard sets it up, both on first setup and whenever you generate a new code: write it down on paper, type it back from the paper to confirm every letter, then choose a safe place to store it.

> [!IMPORTANT]
> - **Write it on paper.** The key must be recorded by hand, on a physical piece of paper.
> - **Store it somewhere hard to reach**, such as a high shelf or a different building.
> - **Never store it digitally.** Not in notes, emails or cloud storage: you may end up blocking the very tools needed to retrieve it.

### If the code is lost

The Protection tab offers a **forced removal** option as a deliberate last resort. It starts a 48-hour timer, and once it expires, Device Owner privileges can be removed without the recovery code. The delay exists so it can't be used as a quick bypass, and it can be cancelled at any time before it completes.

> [!WARNING]
> **Déchaîner is designed to be uncompromising.** Activating full system restrictions and then losing your recovery code may leave you permanently unable to change system settings or restore the device until the 48-hour forced removal completes. This self-lockout is intentional: it's what stops your future self from relapsing. Besides the recovery code and forced removal, there is no other backdoor.

## License

Released under the [Apache License 2.0](LICENSE).

**Disclaimer:** this software is provided "as is", without warranty of any kind. The developers are not liable for any data loss, system instability, or permanent device lockouts resulting from the use of Device Owner privileges.
