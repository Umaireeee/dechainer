<div align="center">

# ⏳ Déchaîner
### *Schedules & System-Level Focus Engine*

[![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
[![Setup](https://img.shields.io/badge/Root-Not%20Required-blue?style=flat-square)](#requirements)
[![Privilege](https://img.shields.io/badge/Engine-Device%20Owner-orange?style=flat-square)](#how-it-works)
[![License](https://img.shields.io/badge/License-GPL--3.0-lightgrey?style=flat-square)](LICENSE)

An unofficial build of [Déchaîner](https://github.com/warleysr/dechainer) by **@warleysr**, engineered with recurring time windows and system-level app suspension[span_0](start_span)[span_0](end_span).

[**Download APK**](https://github.com) • [**Full User Manual**](USER-GUIDE.md)[span_1](start_span)[span_1](end_span)

---

</div>

### 💡 How It Works

Most digital blockers politely ask you to close an app, leaving easy bypasses open[span_2](start_span)[span_2](end_span). 

**Déchaîner** adopts Android's enterprise **Device Owner** role—the same protocol corporate IT departments use on managed hardware[span_3](start_span)[span_3](end_span). When a schedule or restriction triggers, Android suspends the target applications directly in the operating system: icons grey out and cannot launch[span_4](start_span)[span_4](end_span). Everything stays strictly on your device—no accounts, no cloud sync, and no tracking[span_5](start_span)[span_5](end_span).

---

> [!CAUTION]
> ### 🛑 Crucial Safety Rules (Read First)
> 
> * **Pen & Paper Required:** Write your uppercase recovery code on physical paper[span_6](start_span)[span_6](end_span). There is no reset email, no cloud sync, and no support desk[span_7](start_span)[span_7](end_span). Lose it, and your only recourse is an irreversible 48-hour cooldown[span_8](start_span)[span_8](end_span).
> * **Account Clearance:** Android security policies require removing all accounts (Google, Samsung, Xiaomi) during the initial setup handshake[span_9](start_span)[span_9](end_span). You can log back into them immediately after Device Owner status is confirmed[span_10](start_span)[span_10](end_span).
> * **Leave "Lock While Active" Off Initially:** A locked schedule cannot be overridden, edited, or removed—even with your master recovery code[span_11](start_span)[span_11](end_span). Run your routines unlocked for a week before engaging hard locks[span_12](start_span)[span_12](end_span).

---

### 📋 Prerequisites

| Requirement | Specification |
| :--- | :--- |
| **Operating System** | Android 11 or newer[span_13](start_span)[span_13](end_span) |
| **Bridge Utility** | [Shizuku](https://shizuku.rikka.app/) (Free via Play Store or GitHub)[span_14](start_span)[span_14](end_span) |
| **Time Needed** | ~15 minutes[span_15](start_span)[span_15](end_span) |
| **Root Status** | **Not required** (works cleanly via Wireless Debugging)[span_16](start_span)[span_16](end_span) |

---

### 🚀 Setup Guide

#### 1. Prepare Shizuku
1. Install **Shizuku** from Google Play or GitHub[span_17](start_span)[span_17](end_span).
2. Open Shizuku and start it via **Wireless Debugging** in your phone's Developer Options[span_18](start_span)[span_18](end_span).
3. Ensure Shizuku displays the status banner: **"Shizuku is running"**[span_19](start_span)[span_19](end_span).

#### 2. Clear Existing Accounts
1. Launch **Déchaîner** and navigate to **Config** *(bottom right)* → **Owner privileges**[span_20](start_span)[span_20](end_span).
2. Tap the account alert to jump into System Settings and remove all synced accounts[span_21](start_span)[span_21](end_span).

#### 3. Authorize Device Owner
1. Return to Déchaîner's **Owner privileges** checklist until all indicators show ready[span_22](start_span)[span_22](end_span).
2. Tap **Grant privileges**[span_23](start_span)[span_23](end_span).
3. Generate your **Recovery Code** and **write it on paper now**[span_24](start_span)[span_24](end_span).
4. Enable the **Accessibility Service** when prompted to allow on-screen keyword filtering and in-app sub-screen blocking[span_25](start_span)[span_25](end_span).

#### 4. Reconnect Accounts
> [!IMPORTANT]
> Head straight to your phone's native **Settings → Accounts** and sign back into Google, email, and messaging services **now**, before turning on any account-locking restrictions[span_26](start_span)[span_26](end_span).

---

### 🛡️ Your First 15 Minutes

Avoid over-configuring immediately. Follow this simple baseline[span_27](start_span)[span_27](end_span):

* [ ] **Plug the Reinstall Loop:** Head to **Restrictions** and enable **Disallow install apps** and **Disallow add user**[span_28](start_span)[span_28](end_span).
* [ ] **Suspend One Target:** Open **Apps**, tap your primary source of mindless scrolling, and toggle **Suspend**[span_29](start_span)[span_29](end_span).
* [ ] **Live With It:** Spend 48 to 72 hours adapting to the friction before scheduling multi-app lockouts[span_30](start_span)[span_30](end_span).

---

### ⏳ The 48-Hour Emergency Exit

If you lose your code or encounter an unresolvable conflict:

1. Open **Config → Forced removal**[span_31](start_span)[span_31](end_span).
2. Confirm the prompt to trigger a mandatory **48-hour delay**[span_32](start_span)[span_32](end_span).
3. After 48 hours elapses, the system drops Device Owner status and uninstalls cleanly[span_33](start_span)[span_33](end_span).

The deliberate waiting period eliminates late-night impulse bypasses while guaranteeing you can never permanently brick your device[span_34](start_span)[span_34](end_span).

---

### 📚 Documentation & Reference

For advanced features including Chromium URL blocklists, group allowances, keyword filters, and recurring time windows, read the comprehensive [USER-GUIDE.md](USER-GUIDE.md)[span_35](start_span)[span_35](end_span).

<details>
<summary><b>Credits & Upstream</b></summary>
<br>

* Original core engine and architecture by [**@warleysr**](https://github.com/warleysr)[span_36](start_span)[span_36](end_span).
* Recurring schedules engine and system-level process suspension modifications developed in this repository fork[span_37](start_span)[span_37](end_span).
</details>
