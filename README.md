<div align="center">

# ⏳ Déchaîner
### *Complete Zero-to-Hero Guide & Reference Manual*

[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://android.com)
[![Shizuku Required](https://img.shields.io/badge/Shizuku-Wireless%20ADB-blue?style=for-the-badge&logo=googleplay&logoColor=white)](https://shizuku.rikka.app/)
[![Privilege](https://img.shields.io/badge/Mode-Device%20Owner-orange?style=for-the-badge)](https://developer.android.com/work/dpm/device-owner)
[![Privacy](https://img.shields.io/badge/Telemetry-100%25%20Offline-success?style=for-the-badge)](#frequently-asked-questions)

An unofficial build of [Déchaîner](https://github.com/warleysr/dechainer) by **@warleysr**, enhanced with recurring schedules and deep system-level app suspension[span_0](start_span)[span_0](end_span).

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
> **Do not save it in a notes app or a screenshot on this device[span_1](start_span)[span_1](end_span).** There is no "Forgot Password" option, no recovery email, and no customer support desk[span_2](start_span)[span_2](end_span). If you lose this code, your only exit path is an unskippable 48-hour cooldown[span_3](start_span)[span_3](end_span).

> [!NOTE]
> ### ℹ️ Account Sign-Out Is Safe & Temporary
> Android rules require that zero accounts exist on the phone when granting Device Owner status[span_4](start_span)[span_4](end_span). **This does not delete your cloud data** (Google Photos, Drive, and contacts are untouched); it simply signs you out locally[span_5](start_span)[span_5](end_span). You can log back into your accounts immediately after completing setup[span_6](start_span)[span_6](end_span).

> [!WARNING]
> ### ⚠️ Leave "Lock While Active" Turned Off Initially
> When a schedule has this setting enabled, it cannot be modified, shortened, or cancelled—even with your master recovery code[span_7](start_span)[span_7](end_span). Run your routines unlocked for a week before engaging hard locks[span_8](start_span)[span_8](end_span).

---

## 2. How It Actually Works

Standard blocker apps run as background overlays that monitor what you open[span_9](start_span)[span_9](end_span). They are easy to bypass by clearing cache, revoking permissions, or rebooting[span_10](start_span)[span_10](end_span).

**Déchaîner** adopts Android's enterprise **Device Owner** role—the same administrative privilege used by corporate IT departments on managed work devices[span_11](start_span)[span_11](end_span):
* **System-level suspension:** Android itself disables the app package, greys out the launcher icon, and blocks background execution[span_12](start_span)[span_12](end_span).
* **Tamper resistance:** The operating system refuses uninstallation requests directly at the package manager level[span_13](start_span)[span_13](end_span).
* **Zero telemetry:** Everything stays offline on your hardware—no cloud sync, no tracking, and no external servers[span_14](start_span)[span_14](end_span).

### 📖 Key Terms

| Term | What It Means |
| :--- | :--- |
| `Device Owner` | The system-level administrator privilege granted via Shizuku[span_15](start_span)[span_15](end_span). |
| `Suspend` | Android disables the app icon, greys it out, and blocks opening[span_16](start_span)[span_16](end_span). |
| `Block` | Android hides the app icon completely from your launcher[span_17](start_span)[span_17](end_span). |
| `Restriction` | A device policy switch (e.g., disabling new app installs)[span_18](start_span)[span_18](end_span). |
| `Recovery Code` | Your master password (uppercase letters) for 10-minute adjustment sessions[span_19](start_span)[span_19](end_span). |
| `Accessibility Service` | An optional layer that inspects on-screen text and sub-activities (e.g., Reels)[span_20](start_span)[span_20](end_span). |
| `Forced Removal` | The emergency exit that revokes Device Owner after a strict 48-hour delay[span_21](start_span)[span_21](end_span). |

---

## 3. Pre-Flight Checklist

Before starting, complete these three setup requirements:

1. **Android Version:** Ensure your phone is running **Android 11 or newer**[span_22](start_span)[span_22](end_span).
2. **Wi-Fi Connection:** Connect to an active Wi-Fi network (or another phone's mobile hotspot) to enable Android's Wireless Debugging.
3. **Enable Developer Options:**
   * Go to **Settings** $\rightarrow$ **About Phone**.
   * Locate **Build Number** (on Xiaomi, tap **OS Version**; on Samsung, go to **Software Information** $\rightarrow$ **Build Number**).
   * Tap it **7 times** quickly until the system prompts: *"You are now a developer!"*
4. **Manufacturer-Specific Adjustments:**
   * **Xiaomi / Redmi / POCO:** In Developer Options, enable both **USB Debugging** and **USB Debugging (Security settings)**.
   * **Samsung:** Disable or uninstall **Secure Folder**, as Android treats it as an active secondary user profile that blocks Device Owner setup.

---

## 4. Step-by-Step Installation

### Step 1: Download the App
Grab the latest `.apk` from the **Releases** page of this repository[span_23](start_span)[span_23](end_span). If your browser shows *"File might be harmful"*, tap **Download anyway** and allow installation from unknown sources.

### Step 2: Initialize Shizuku
1. Install **Shizuku** from Google Play or GitHub[span_24](start_span)[span_24](end_span).
2. Connect to Wi-Fi, open Shizuku, and tap **Pairing** under *Start via Wireless Debugging*.
3. Tap **Developer options**, enable **Wireless debugging**, and tap the text to open its details.
4. Tap **Pair device with pairing code** and enter the 6-digit code into Shizuku's notification tray banner.
5. Return to Shizuku and tap **Start**. Verify that the main screen displays **"Shizuku is running"**[span_25](start_span)[span_25](end_span).

### Step 3: Remove Existing Accounts
1. Open **Déchaîner** $\rightarrow$ tap the **Config** tab (bottom right) $\rightarrow$ **Owner privileges**[span_26](start_span)[span_26](end_span).
2. Tap the account alert under **"There can be no accounts on the device"**[span_27](start_span)[span_27](end_span).
3. Remove each registered account (Google, WhatsApp, Samsung, Xiaomi)[span_28](start_span)[span_28](end_span). 
4. Return to Déchaîner until the account checklist item displays a green indicator[span_29](start_span)[span_29](end_span).

### Step 4: Grant Device Owner Privileges
1. In Déchaîner's **Owner privileges** screen, verify all prerequisites are marked green[span_30](start_span)[span_30](end_span).
2. Tap **Grant privileges**[span_31](start_span)[span_31](end_span).
3. Choose your uppercase **Recovery Code** and **write it on physical paper now**[span_32](start_span)[span_32](end_span).
4. Enable the **Accessibility Service** when prompted[span_33](start_span)[span_33](end_span).

### Step 5: Sign Back Into Your Accounts
> [!IMPORTANT]
> Open your phone's native **Settings $\rightarrow$ Accounts** and log back into your Google and personal accounts immediately[span_34](start_span)[span_34](end_span). Complete this step **before** turning on any account-locking restrictions[span_35](start_span)[span_35](end_span).

---

## 5. Your First 15 Minutes

Avoid over-configuring immediately. Follow this simple baseline[span_36](start_span)[span_36](end_span):

1. **Close the Reinstall Loophole:** Go to **Restrictions** and turn on:
   * `Disallow install apps` (stops impulse re-downloading)[span_37](start_span)[span_37](end_span).
   * `Disallow add user` (stops creating clean guest profiles)[span_38](start_span)[span_38](end_span).
2. **Suspend One Target:** Open **Apps**, tap your primary distraction, and enable **Suspend**[span_39](start_span)[span_39](end_span). The app icon will turn grey in your launcher and refuse to open[span_40](start_span)[span_40](end_span).
3. **Live With It:** Spend 48 to 72 hours adapting to the friction before scheduling multi-app lockouts[span_41](start_span)[span_41](end_span).

---

## 6. Restrictions Tab

System-level policy switches that remain enforced permanently until loosened with your recovery code[span_42](start_span)[span_42](end_span):

| Policy Switch | Why It Matters |
| :--- | :--- |
| `Disallow install apps` | Prevents package installs via Google Play or APK files[span_43](start_span)[span_43](end_span). |
| `Disallow add user` | Disables secondary user profiles and guest accounts[span_44](start_span)[span_44](end_span). |
| `Disallow modify accounts` | Locks adding/removing accounts[span_45](start_span)[span_45](end_span). **Enable only after logging into Google**[span_46](start_span)[span_46](end_span). |
| `Prohibit factory reset` | Blocks resetting the device via system settings[span_47](start_span)[span_47](end_span). |
| `Prohibit network settings reset`| Prevents reverting DNS filters and Wi-Fi policies to defaults[span_48](start_span)[span_48](end_span). |
| `Prohibit system error dialogs` | Hides crash notifications, removing an escape route from locked views[span_49](start_span)[span_49](end_span). |

---

## 7. Apps Tab

Tap any application in the list to configure its rules[span_50](start_span)[span_50](end_span):

* `Suspend`: Greys out the icon and completely blocks execution immediately[span_51](start_span)[span_51](end_span).
* `Block`: Hides the app icon from your launcher interface entirely[span_52](start_span)[span_52](end_span).
* `Prevent uninstall`: Locks the package against deletion[span_53](start_span)[span_53](end_span).
* `Set time limit`: Sets a daily usage quota[span_54](start_span)[span_54](end_span). Once depleted, the app suspends until midnight[span_55](start_span)[span_55](end_span).
* `Time between app openings`: Enforces a mandatory cooldown between launches[span_56](start_span)[span_56](end_span).
* `Time windows`: Defines allowed operating hours; the app remains suspended outside this window[span_57](start_span)[span_57](end_span).

---

## 8. Groups & Shared Limits

Create a group via **Apps** $\rightarrow$ menu (**⋮**) $\rightarrow$ **Manage groups** to pool allowances across multiple apps[span_58](start_span)[span_58](end_span).

* **Shared Limits:** A 1-hour limit on a group of 3 apps means 60 minutes total across all 3, not 1 hour each[span_59](start_span)[span_59](end_span).
* **The Overlap Rule:** If an individual app has hours set *and* belongs to a group with hours set, it receives the **overlap** of the two[span_60](start_span)[span_60](end_span):
  * Group `18:00–22:00` + App `20:00–23:00` $\rightarrow$ Works **20:00–22:00 only**[span_61](start_span)[span_61](end_span).
  * Group `18:00–22:00` + App `09:00–12:00` $\rightarrow$ No overlap, so the app gets **no restriction at all**[span_62](start_span)[span_62](end_span).
  * *Best Practice:* Set time windows on the **Group**, leaving individual app hours empty[span_63](start_span)[span_63](end_span).

---

## 9. Schedules (Recurring Windows)

Navigate to **Config $\rightarrow$ Schedules** to create repeating focus blocks (e.g., 22:00–07:00 on weekdays)[span_64](start_span)[span_64](end_span).

During an active schedule:
* Target apps are automatically suspended at the system level[span_65](start_span)[span_65](end_span).
* Selected device restrictions are temporarily enforced[span_66](start_span)[span_66](end_span).
* Specified websites are blocked across Chromium browsers[span_67](start_span)[span_67](end_span).

> [!CAUTION]
> **Lock While Active:** When enabled, the schedule cannot be edited, shortened, or removed—even with your master recovery code[span_68](start_span)[span_68](end_span). Device Owner revocation is also blocked until the window ends[span_69](start_span)[span_69](end_span).

---

## 10. Config Tab Reference

| Setting | Operational Details |
| :--- | :--- |
| `Owner privileges` | Setup wizard and standard revocation screen[span_70](start_span)[span_70](end_span). |
| `Private DNS` | Pins your DNS provider (e.g., NextDNS) to block adult domains network-wide[span_71](start_span)[span_71](end_span). |
| `Browser restrictions` | Enforces URL blocklists inside Google Chrome and Chromium browsers[span_72](start_span)[span_72](end_span). |
| `Advanced blocking` | Blocks specific screens inside apps (e.g., YouTube Shorts, Reels)[span_73](start_span)[span_73](end_span). |
| `Visual blocking` | Offline on-device neural detection for sensitive imagery and video[span_74](start_span)[span_74](end_span). |
| `Forbidden words` | Scans on-screen text and blocks views containing designated terms[span_75](start_span)[span_75](end_span). |
| `Block Torrent apps` | Automatically detects and suspends BitTorrent clients[span_76](start_span)[span_76](end_span). |
| `Forced removal` | The 48-hour emergency failsafe[span_77](start_span)[span_77](end_span). |

---

## 11. Impulse Lock & Panic Button

Configured under **Config $\rightarrow$ Impulse lock** to add deliberate resistance[span_78](start_span)[span_78](end_span):

* **Startup Challenge:** Forces you to solve 5 two-digit addition problems or type 32 words correctly before opening Déchaîner's settings[span_79](start_span)[span_79](end_span).
* **Panic Button:** Accessible on the main screen[span_80](start_span)[span_80](end_span). Instantly locks Déchaîner's configuration panel (and optionally suspends distraction apps) for **15 minutes to 6 hours**[span_81](start_span)[span_81](end_span). Cannot be undone until the timer expires[span_82](start_span)[span_82](end_span).

---

## 12. Getting Out (Emergency Exits)

* **Recovery Code:** Unlocks a 10-minute maintenance session to make adjustments[span_83](start_span)[span_83](end_span).
* **Forced Removal (The 48-Hour Failsafe):** Available under **Config $\rightarrow$ Forced removal**[span_84](start_span)[span_84](end_span). Initiates a mandatory 48-hour countdown before revoking Device Owner status and unlocking the system[span_85](start_span)[span_85](end_span). It remains accessible even during locked schedules so you can never permanently brick your device[span_86](start_span)[span_86](end_span).

---

## 13. Troubleshooting & Brand Fixes

| Problem | Cause | Resolution |
| :--- | :--- | :--- |
| **"Get owner privileges first"** | Incomplete setup[span_87](start_span)[span_87](end_span). | Open **Config $\rightarrow$ Owner privileges** and verify all items show green[span_88](start_span)[span_88](end_span). |
| **Shizuku reports not running** | Phone restarted[span_89](start_span)[span_89](end_span). | Re-run Wireless Debugging in Shizuku[span_90](start_span)[span_90](end_span). (Not needed once Device Owner is active)[span_91](start_span)[span_91](end_span). |
| **Device Owner grant fails** | Residual account or profile[span_92](start_span)[span_92](end_span). | Remove all accounts[span_93](start_span)[span_93](end_span). Samsung: Delete Secure Folder. Xiaomi: Enable "USB Debugging (Security settings)". |
| **App updates fail to install** | `Disallow install apps` is on[span_94](start_span)[span_94](end_span). | Unlock with your recovery code, toggle it off, update your app, and toggle it back on[span_95](start_span)[span_95](end_span). |
| **Window triggered late** | Battery optimization[span_96](start_span)[span_96](end_span). | Set Déchaîner's battery usage to **Unrestricted** in system settings[span_97](start_span)[span_97](end_span). |
| **Websites not blocked** | Non-Chromium browser[span_98](start_span)[span_98](end_span). | Use Chrome, Brave, or Chromium-based browsers; other engines ignore enterprise filtering[span_99](start_span)[span_99](end_span). |

---

## 14. Frequently Asked Questions

**Can I uninstall the app from the home screen?**  
No[span_100](start_span)[span_100](end_span). Device Owner status blocks standard package removal[span_101](start_span)[span_101](end_span). Use the in-app removal option or wait out the 48-hour Forced Removal countdown[span_102](start_span)[span_102](end_span).

**Does it send telemetry to remote servers?**  
No[span_103](start_span)[span_103](end_span). The app operates completely offline without accounts, analytics, or external calls[span_104](start_span)[span_104](end_span).

**Do protections survive a reboot?**  
Yes[span_105](start_span)[span_105](end_span). Android re-enforces system-level suspensions and policies during system boot[span_106](start_span)[span_106](end_span).

**Can I run it without the Accessibility Service?**  
Yes[span_107](start_span)[span_107](end_span). System-level restrictions, schedules, and app suspensions operate via Device Owner independently of the accessibility service[span_108](start_span)[span_108](end_span).

---

## 15. Building It Yourself

This repository builds the upstream project with an integrated patch file applied during compilation[span_109](start_span)[span_109](end_span):
* `schedules_v10.patch`: Adds recurring schedules and low-level system suspension[span_110](start_span)[span_110](end_span).
* `.github/workflows/release3.yml`: Automation script for compiling and testing[span_111](start_span)[span_111](end_span).

```bash
# Build locally
patch -p1 < schedules_v10.patch
./gradlew assembleDebug
```[span_112](start_span)[span_112](end_span)

---

<div align="center">

Core engine by [**@warleysr**](https://github.com/warleysr) • Recurring schedules and system-level enforcement maintained in this build[span_113](start_span)[span_113](end_span).

</div>
