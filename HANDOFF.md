# Project handoff (read this first in a new session)

Last updated: 2026-10-07. Owner: Umaireeee. Repo: `Umaireeee/dechainer`. Default branch: `main-clean`.
The full history of earlier sessions (the two-app project, every phase report) is in `docs/handoff-history.md`.

## What this is

Déchaîner 2.0: one Android app (`:app`, package `io.github.warleysr.dechainer`) that runs as Device Owner.
`BLUEPRINT.md` is the source of truth and `CLAUDE.md` holds the rules for Claude Code. The code has moved past the
blueprint in places, so where they disagree, report before changing anything.

- **Locks:** a full-screen **blackout** (formerly the urge lock: 1 minute to 12 hours, a pure-black countdown,
  incoming calls only, no early exit and never extended), focus blocks (manual, or weekly FOCUS timetable entries
  set once), block schedules and daily limits, all planned by the pure `LockPlanner` and applied by `LockEngine.sync`.
- **Blackout triggers:** the Home button, the Quick Settings tile, the icon shortcut, and the assistant gesture
  (`ACTION_ASSIST`, once Déchaîner is set as the default assistant app).
- **No AI and no reports:** the deep dive, the questions, the weekly/monthly/yearly reports and their code are gone.
  Nothing is sent off the phone.
- **No daily checklist (removed after 1.2 on the owner's decision):** the Today screen, the `day`/`goal` tables, the
  evening reminders, the rules onboarding, `activatedOn` and the rest-day link are gone. Deleting a finished blackout
  or focus session is always allowed.

## State at hand-off

CI (unit tests, debug and release builds) is the only full compiler. `tools/pure-tests.sh` compiles and runs the
pure-logic tests on a plain JVM and works in the sandbox: run it before every push. `release.yml` is run by hand from
the Actions tab to build, emulator-test and sign a release APK.

BLUEPRINT section 15 lists the owner's on-phone checks; check what is still open before saying a phase is done.

## Sandbox constraints

- No Android SDK and no access to Google's Maven: Gradle cannot build here. Use `tools/pure-tests.sh`, then push and
  read the GitHub Actions result.

## The owner's preferences

- Calm, classy, non-shaming; plain short explanations of what to tap; no gamification, no streaks.
- Builds and installs APKs from the Actions tab himself (`release.yml`, run by hand) and sends screenshots.
- Be honest: never promise a cure, say what was not verified.

## Known limits and ideas (by value)

1. Any app that declares a home-screen activity is never suspended (`ScheduleEnforcer.readProtectedPackages`):
   installing a launcher with a browser is a way around schedules outside a brick. Protecting only the default
   launcher has its own trap (a suspended default home); needs a careful design and a phone to test on.
2. Two crashes within five minutes during a brick end it (the crash-loop breaker, by design). Aborts are not shown
   anywhere yet.
3. A home-screen widget for the Blackout button (the tile and icon shortcut exist).
