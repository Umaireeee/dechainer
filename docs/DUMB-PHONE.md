# Study mode and lockdown (replaces the all-day dumb-phone lock)

What the app does, and what to check before trusting it. Decided by the owner on 2026-10-25.

## The rules
- **Study mode** is started by hand: pick an end time (presets or a clock time, 10 minutes to 12 hours) and
  hold the button. Until then the phone is locked like the old dumb phone: only calls, SMS, camera, Settings,
  the alarm clock, emergency apps and **Gemini** work; every other app with an icon is paused. The Google app
  is **hidden** (not paused) so Gemini keeps working. No sideloading, installs, uninstalling, second space,
  safe mode, USB debugging or factory reset from Settings. The phone is never pinned, so the hotspot stays
  reachable. When the end time passes, the phone is normal again.
- **Changing a running session:** it can only be **extended**, instantly. It cannot be shortened or ended
  early (there is no recovery code). The only way out is the 7-day wait below.
- **Lockdown** pauses only the apps the owner picks, until an end time. While it runs the list only grows;
  the apps are remembered for the next one. Uninstalling apps is blocked while it runs.
- **Adult-site DNS filter:** armed at the first study session or lockdown and **never turned off** after that.
- **Always on:** no date/time change, no VPN or Private DNS settings, no adding users, this app cannot be
  uninstalled, and Force stop / Clear data are blocked for this app.
- **Outside study mode and lockdown** other apps can be uninstalled and installed as usual ("no app control"
  is only applied while one runs; an older phone has the old permanent rule lifted once).
- **Exit:** the 7-day wait (5 minutes in the debug build), started from Settings, cancellable. It takes the
  app off the phone. Block schedules stored by an older build are ignored once the protection is armed.

## Known limits (be honest about these)
- **Not tested on a phone:** Gemini may not work with the Google app hidden on every device or version.
- Never run on the owner's daily phone before the checks below; Xiaomi/HyperOS may ignore some restrictions.
- A factory reset from recovery mode cannot be prevented by any app.
- The Cold Turkey password must not be kept in Google Password Manager (reachable from Settings).

## Before installing on a daily phone (BLUEPRINT.md 14A)
1. Install the **debug** build (5-minute wait) on a spare phone or emulator. Start a 10-minute study session:
   check a call, an SMS, the camera, the hotspot and **Gemini** (Google app hidden), extend it, then let it end
   and check the phone is normal. Check that other apps can be uninstalled outside a session but not during
   one, and that this app never can. Start a lockdown and check only the picked apps are paused.
2. Run the 5-minute wait to the end. Only then install the release build.
