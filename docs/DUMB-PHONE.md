# Dumb-phone mode (v2.0.0-dumbphone)

What the app does once the lock is started, and what to check before trusting it.

## The rules
- **Locked all day except 21:00 to 22:30** (every day). Locked: only calls, SMS, camera, Settings, the
  alarm clock, SIM Toolkit and the hotspot (notification shade) work; every other app with an icon is paused.
- **Open window 21:00 to 22:30:** nothing is paused; store installs and updates are allowed. An app installed
  then is paused at the next lock.
- **Always on, at every hour:** adult-site DNS filter (rotates per network, falls back to automatic DNS if
  none works), no sideloading, no uninstalling, no VPN, no second space / extra users / work or clone
  profiles, no safe mode, no USB debugging, no factory reset from Settings, no date/time change.
- **Start:** hold the button on the Start screen. For 10 minutes the lock can be undone.
- **Exit:** after the undo window the only way out is the 7-day wait (5 minutes in the debug build),
  started from Settings and cancellable. There is no recovery code.

## Known limits (be honest about these)
- Never run on the owner's daily phone before the first release; Xiaomi/HyperOS may ignore some restrictions.
- Play Store was seen still open while locked (Android probably refuses to pause it); installs stay refused
  outside the open window, so it cannot change anything.
- A black-and-white screen was tried and dropped: it needs a permission Android did not give the app.
- A factory reset from recovery mode cannot be prevented by any app.
- The Cold Turkey password must not be kept in Google Password Manager (reachable from Settings).
- Banking apps and Gmail only work in the open window.

## Before installing on a daily phone (BLUEPRINT.md 14A)
1. Install the **debug** build (5-minute wait) on a spare phone or emulator. Start the lock, check the
   hotspot, a call, an SMS and the camera under the lock, then check the open window by changing nothing
   but waiting, and run the 5-minute wait to the end.
2. Only then install the release build. After starting, the 10-minute undo is the last easy way out.
