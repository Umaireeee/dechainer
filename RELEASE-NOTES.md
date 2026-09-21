## Déchaîner 1.2.0 + Schedules

An unofficial personal build of [Déchaîner](https://github.com/warleysr/dechainer) by @warleysr,
built on the latest upstream, including its new usage-warning notifications. All credit for the
app goes to the original author.

---

### New: Schedules

Block things on a repeating timetable.

- Pick days and a start/end time, then choose any mix of **apps, system restrictions and websites**.
- Windows crossing midnight work (22:00 → 06:00). Overlapping schedules work.
- **Lock while active** — a schedule can be frozen while it's running: not editable, not
  disableable, not deletable, even with your recovery code. It unlocks when the window ends.
- While any schedule is on, the **clock is forced to automatic** so you can't skip a window by
  changing the time.
- When a window ends, only what that schedule applied is released. Anything you blocked yourself
  stays blocked.

### Changed: blocking that can't be dismissed

Upstream blocks by putting a screen in front of an app when its accessibility service notices it.
Turn that service off and the app opens normally.

In this build these five now **suspend the app at the system level** — Android refuses to open it
at all, whatever launches it, service running or not, across reboots:

- Daily time limits
- Time between app openings
- App time windows
- Group time windows
- Group time limits — and hitting a shared group limit now blocks **the whole group**, not just
  the one app you had open

The explanation screens stay; they just explain why rather than doing the blocking.

### New: Status screen

**Config → Status** answers the two questions the app couldn't before.

- **Is blocking working?** Five checks — Device Owner, accessibility, exact alarms, battery
  optimisation, notifications — each green or red, with a **Fix** button that opens the right
  Settings page. On Xiaomi phones it also flags MIUI's separate battery manager, which Android
  can't detect.
- **Why is this app blocked?** Every blocked app, every reason, and when each one ends — schedule,
  time window, daily limit, cooldown, impulse lock, explicit content, or blocked by you.

Read-only: nothing on it can loosen a block.

### Safety

You can't accidentally trap yourself:

- A set of locked schedules leaving under an hour free all week is refused.
- Long locked windows need an extra confirmation.
- Removing Device Owner is blocked while a schedule is locked — but **forced removal still works**,
  with its 48-hour wait. That's the escape hatch and it stays open.
- Six different things can block an app now, and none of them releases an app another still wants
  blocked.

### Fixes

- The "this lock got longer" warning was backwards — it fired when you shortened a schedule and
  stayed quiet when you extended one.
- Saving or cancelling in the schedule editor closed two screens instead of one.
- The Apps tab could free an app that a locked schedule was holding. That bypass is closed.
- The app still requested **microphone permission** for a voice feature that had been removed.
  Gone.
- Every build now gets its own version number, so your phone can tell updates apart.

---

### Requirements

- Android 11 or newer
- Device Owner, set up via ADB or Shizuku before adding any account to the device

### Installing

Same signing key as before, so it installs over the top and keeps your schedules, settings and
recovery code. Don't uninstall first.

**If "Disallow install apps" is on, turn it off with your recovery code first** — Android refuses
the install and won't tell you why.

### Tested before release

CI runs the app on an emulator with the accessibility service switched off, so everything below is
the system doing the blocking:

- a schedule window blocks its apps, applies its restrictions and locks the clock
- the window releases itself on time, with no app restart
- an app outside its time window is blocked, then released when the window opens
- the signed APK installs, takes Device Owner and launches without crashing

### Good to know

An app can have its own hours **and** inherit its group's hours. It gets the **overlap** of the two,
never the looser one. If the two don't overlap at all, that app ends up unrestricted — so keep an
app's own hours inside its group's, or leave them unset.
