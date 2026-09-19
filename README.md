<div align="center">

# ⏳ Déchaîner
### *Complete Zero-to-Hero Guide & Reference Manual*

[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://android.com)
[![Shizuku Required](https://img.shields.io/badge/Shizuku-Wireless%20ADB-blue?style=for-the-badge&logo=googleplay&logoColor=white)](https://shizuku.rikka.app/)
[![Privilege](https://img.shields.io/badge/Mode-Device%20Owner-orange?style=for-the-badge)](https://developer.android.com/work/dpm/device-owner)
[![Privacy](https://img.shields.io/badge/Telemetry-100%25%20Offline-success?style=for-the-badge)](#frequently-asked-questions)

An unofficial build of [Déchaîner](https://github.com/warleysr/dechainer) by **@warleysr**, enhanced with recurring schedules and deep system-level app suspension.

---

</div>

## 📑 Table of Contents

* [1. Critical Safety Rules](#1-critical-safety-rules)
* [2. How It Actually Works](#2-how-it-actually-works)
* [3. Pre-Flight Checklist](#3-pre-flight-checklist)
* [4. Step-by-Step Installation](#4-step-by-step-installation)
* [5. Your First 15 Minutes](#5-your-first-15-minutes)
* [6. Restrictions Tab](#6-restrictions-tab)
* [7. Apps Tab](#7-apps-tab)
* [8. Groups & Shared Limits](#8-groups--shared-limits)
* [9. Schedules (Recurring Windows)](#9-schedules-recurring-windows)
* [10. Config Tab Reference](#10-config-tab-reference)
* [11. Impulse Lock & Panic Button](#11-impulse-lock--panic-button)
* [12. Getting Out (Emergency Exits)](#12-getting-out-emergency-exits)
* [13. Troubleshooting & Brand Fixes](#13-troubleshooting--brand-fixes)
* [14. Frequently Asked Questions](#14-frequently-asked-questions)
* [15. Building It Yourself](#15-building-it-yourself)

---

## 1. Critical Safety Rules

> [!CAUTION]
> ### 🛑 Write Your Recovery Code on Paper
> **Do not save it in a notes app or a screenshot on this device.** There is no "Forgot Password" option, no recovery email, and no customer support desk. If you lose this code, your only exit path is an unskippable 48-hour cooldown.

> [!NOTE]
> ### ℹ️ Account Sign-Out Is Safe & Temporary
> Android rules require that zero accounts exist on the phone when granting Device Owner status. **This does not delete your cloud data** (Google Photos, Drive, and contacts are untouched); it simply signs you out locally. You can log back into your accounts immediately after completing setup.

> [!WARNING]
> ### ⚠️ Leave "Lock While Active" Turned Off Initially
> When a schedule has this setting enabled, it cannot be modified, shortened, or cancelled—even with your master recovery code. Run your routines unlocked for a week before engaging hard locks.

---

## 2. How It Actually Works

Standard blocker apps run as background overlays that monitor what you open. They are easy to bypass by clearing cache, revoking permissions, or rebooting.

**Déchaîner** adopts Android's enterprise **Device Owner** role—the same administrative privilege used by corporate IT departments on managed work devices:
* **System-level suspension:** Android itself disables the app package, greys out the launcher icon, and blocks background execution.
* **Tamper resistance:** The operating system refuses uninstallation requests directly at the package manager level.
* **Zero telemetry:** Everything stays offline on your hardware—no cloud sync, no tracking, and no external servers.

### 📖 Key Terms

| Term | What It Means |
| :--- | :--- |
| `Device Owner` | The system-level administrator privilege granted via Shizuku. |
| `Suspend` | Android disables the app icon, greys it out, and blocks opening. |
| `Block` | Android hides the app icon completely from your launcher. |
| `Restriction` | A device policy switch (e.g., disabling new app installs). |
| `Recovery Code` | Your master password (uppercase letters) for 10-minute adjustment sessions. |
| `Accessibility Service` | An optional layer that inspects on-screen text and sub-activities (e.g., Reels). |
| `Forced Removal` | The emergency exit that revokes Device Owner after a strict 48-hour delay. |

---

## 3. Pre-Flight Checklist

Before starting, complete these setup requirements:

1. **Android Version:** Ensure your phone is running **Android 11 or newer**.
2. **Wi-Fi Connection:** Connect to an active Wi-Fi network (or another phone's mobile hotspot) to enable Android's Wireless Debugging.
3. **Enable Developer Options:**
   * Go to **Settings** $\rightarrow$ **About Phone**.
   * Locate **Build Number** (on Xiaomi/Redmi, tap **OS Version**; on Samsung, go to **Software Information** $\rightarrow$ **Build Number**).
   * Tap it **7 times** quickly until the system prompts: *"You are now a developer!"*
4. **Manufacturer-Specific Adjustments:**
   * **Xiaomi / Redmi / POCO:** In Developer Options, enable both **USB Debugging** and **USB Debugging (Security settings)**.
   * **Samsung:** Disable or uninstall **Secure Folder**, as Android treats it as an active secondary user profile that blocks Device Owner setup.

---

## 4. Step-by-Step Installation

### Step 1: Download the App
Grab the latest `.apk` from the **Releases** page of this repository. If your browser shows *"File might be harmful"*, tap **Download anyway** and allow installation from unknown sources.

### Step 2: Initialize Shizuku
1. Install **Shizuku** from Google Play or GitHub.
2. Connect to Wi-Fi, open Shizuku, and tap **Pairing** under *Start via Wireless Debugging*.
3. Tap **Developer options**, enable **Wireless debugging**, and tap the text to open its details.
4. Tap **Pair device with pairing code** and enter the 6-digit code into Shizuku's notification tray banner.
5. Return to Shizuku and tap **Start**. Verify that the main screen displays **"Shizuku is running"**.

### Step 3: Remove Existing Accounts
1. Open **Déchaîner** $\rightarrow$ tap the **Config** tab (bottom right) $\rightarrow$ **Owner privileges**.
2. Tap the account alert under **"There can be no accounts on the device"**.
3. Remove each registered account (Google, WhatsApp, Samsung, Xiaomi). 
4. Return to Déchaîner until the account checklist item displays a green indicator.

### Step 4: Grant Device Owner Privileges
1. In Déchaîner's **Owner privileges** screen, verify all prerequisites are marked green.
2. Tap **Grant privileges**.
3. Choose your uppercase **Recovery Code** and **write it on physical paper now**.
4. Enable the **Accessibility Service** when prompted.

### Step 5: Sign Back Into Your Accounts
> [!IMPORTANT]
> Open your phone's native **Settings $\rightarrow$ Accounts** and log back into your Google and personal accounts immediately. Complete this step **before** turning on any account-locking restrictions.

---

## 5. Your First 15 Minutes

Avoid over-configuring immediately. Follow this simple baseline:

1. **Close the Reinstall Loophole:** Go to **Restrictions** and turn on:
   * `Disallow install apps` (stops impulse re-downloading).
   * `Disallow add user` (stops creating clean guest profiles).
2. **Suspend One Target:** Open **Apps**, tap your primary distraction, and enable **Suspend**. The app icon will turn grey in your launcher and refuse to open.
3. **Live With It:** Spend 48 to 72 hours adapting to the friction before scheduling multi-app lockouts.

---

## 6. Restrictions Tab

System-level policy switches that remain enforced permanently until loosened with your recovery code:

| Policy Switch | Why It Matters |
| :--- | :--- |
| `Disallow install apps` | Prevents package installs via Google Play or APK files. |
| `Disallow add user` | Disables secondary user profiles and guest accounts. |
| `Disallow modify accounts` | Locks adding/removing accounts. **Enable only after logging into Google**. |
| `Prohibit factory reset` | Blocks resetting the device via system settings. |
| `Prohibit network settings reset`| Prevents reverting DNS filters and Wi-Fi policies to defaults. |
| `Prohibit system error dialogs` | Hides crash notifications, removing an escape route from locked views. |

---

## 7. Apps Tab

Tap any application in the list to configure its rules:

* `Suspend`: Greys out the icon and completely blocks execution immediately.
* `Block`: Hides the app icon from your launcher interface entirely.
* `Prevent uninstall`: Locks the package against deletion.
* `Set time limit`: Sets a daily usage quota. Once depleted, the app suspends until midnight.
* `Time between app openings`: Enforces a mandatory cooldown between launches.
* `Time windows`: Defines allowed operating hours; the app remains suspended outside this window.

---

## 8. Groups & Shared Limits

Create a group via **Apps** $\rightarrow$ menu (**⋮**) $\rightarrow$ **Manage groups** to pool allowances across multiple apps.

* **Shared Limits:** A 1-hour limit on a group of 3 apps means 60 minutes total across all 3, not 1 hour each.
* **The Overlap Rule:** If an individual app has hours set *and* belongs to a group with hours set, it receives the **overlap** of the two:
  * Group `18:00–22:00` + App `20:00–23:00` $\rightarrow$ Works **20:00–22:00 only**.
  * Group `18:00–22:00` + App `09:00–12:00` $\rightarrow$ No overlap, so the app gets **no restriction at all**.
  * *Best Practice:* Set time windows on the **Group**, leaving individual app hours empty.

---

## 9. Schedules (Recurring Windows)

Navigate to **Config $\rightarrow$ Schedules** to create repeating focus blocks (e.g., 22:00–07:00 on weekdays).

During an active schedule:
* Target apps are automatically suspended at the system level.
* Selected device restrictions are temporarily enforced.
* Specified websites are blocked across Chromium browsers.

> [!CAUTION]
> **Lock While Active:** When enabled, the schedule cannot be edited, shortened, or removed—even with your master recovery code. Device Owner revocation is also blocked until the window ends.

---

## 10. Config Tab Reference

| Setting | Operational Details |
| :--- | :--- |
| `Owner privileges` | Setup wizard and standard revocation screen. |
| `Private DNS` | Pins your DNS provider (e.g., NextDNS) to block adult domains network-wide. |
| `Browser restrictions` | Enforces URL blocklists inside Google Chrome and Chromium browsers. |
| `Advanced blocking` | Blocks specific screens inside apps (e.g., YouTube Shorts, Reels). |
| `Visual blocking` | Offline on-device neural detection for sensitive imagery and video. |
| `Forbidden words` | Scans on-screen text and blocks views containing designated terms. |
| `Block Torrent apps` | Automatically detects and suspends BitTorrent clients. |
| `Forced removal` | The 48-hour emergency failsafe. |

---

## 11. Impulse Lock & Panic Button

Configured under **Config $\rightarrow$ Impulse lock** to add deliberate resistance:

* **Startup Challenge:** Forces you to solve 5 two-digit addition problems or type 32 words correctly before opening Déchaîner's settings.
* **Panic Button:** Accessible on the main screen. Instantly locks Déchaîner's configuration panel (and optionally suspends distraction apps) for **15 minutes to 6 hours**. Cannot be undone until the timer expires.

---

## 12. Getting Out (Emergency Exits)

* **Recovery Code:** Unlocks a 10-minute maintenance session to make adjustments.
* **Forced Removal (The 48-Hour Failsafe):** Available under **Config $\rightarrow$ Forced removal**. Initiates a mandatory 48-hour countdown before revoking Device Owner status and unlocking the system. It remains accessible even during locked schedules so you can never permanently brick your device.

---

## 13. Troubleshooting & Brand Fixes

| Problem | Cause | Resolution |
| :--- | :--- | :--- |
| **"Get owner privileges first"** | Incomplete setup. | Open **Config $\rightarrow$ Owner privileges** and verify all items show green. |
| **Shizuku reports not running** | Phone restarted. | Re-run Wireless Debugging in Shizuku. (Not needed once Device Owner is active). |
| **Device Owner grant fails** | Residual account or profile. | Remove all accounts. Samsung: Delete Secure Folder. Xiaomi: Enable "USB Debugging (Security settings)". |
| **App updates fail to install** | `Disallow install apps` is on. | Unlock with your recovery code, toggle it off, update your app, and toggle it back on. |
| **Window triggered late** | Battery optimization. | Set Déchaîner's battery usage to **Unrestricted** in system settings. |
| **Websites not blocked** | Non-Chromium browser. | Use Chrome, Brave, or Chromium-based browsers; other engines ignore enterprise filtering. |

---

## 14. Frequently Asked Questions

**Can I uninstall the app from the home screen?**  
No. Device Owner status blocks standard package removal. Use the in-app removal option or wait out the 48-hour Forced Removal countdown.

**Does it send telemetry to remote servers?**  
No. The app operates completely offline without accounts, analytics, or external calls.

**Do protections survive a reboot?**  
Yes. Android re-enforces system-level suspensions and policies during system boot.

**Can I run it without the Accessibility Service?**  
Yes. System-level restrictions, schedules, and app suspensions operate via Device Owner independently of the accessibility service.

---

## 15. Building It Yourself

This repository builds the upstream project with an integrated patch file applied during compilation:
* `schedules.patch`: Adds recurring schedules and low-level system suspension.
* `.github/workflows/release3.yml`: Automation script for compiling and testing.
