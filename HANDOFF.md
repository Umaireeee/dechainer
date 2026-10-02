# Project handoff (read this first in a new session)

Last updated: 2026-10-02. Owner: Umaireeee. Repo: `Umaireeee/dechainer`. Default branch: `main-clean`.
The full history of earlier sessions (the two-app project, every phase report) is in `docs/handoff-history.md`.

## What this is

Déchaîner 2.0: one Android app (`:app`, package `io.github.warleysr.dechainer`) that runs as Device Owner.
`BLUEPRINT.md` (version 1.2) is the source of truth; `CLAUDE.md` holds the rules for Claude Code.

- **Locks:** a 10-minute urge lock, focus blocks (manual, or weekly FOCUS timetable entries set once), block
  schedules and daily limits, all planned by the pure `LockPlanner` and applied by `LockEngine.sync`.
- **Urge flow:** breathing, writing, AI questions, a deep dive that sees the last 30 days through earlier deep
  dives (`ai/DeepDiveHistory.kt`), the note deleted once the deep dive exists.
- **Daily checklist (Today):** 3 to 7 goals planned each evening (manual, focus minutes or no slip), one rest day
  a week. Each finished day is recorded with its counts and why it fell short. **There is no punishment day**
  (removed in 1.2 on the owner's decision).
- **Reports:** weekly (anchored on the first data point), monthly and yearly (calendar), all from derived data
  only, plus progress graphs (28 days, 12 weeks, 12 months) and a backup reminder.

## State at hand-off (2026-10-02)

Branch `ccr-0b16a937-euwxt4` holds, on top of `main-clean`:
1. The six unmerged commits of `ccr-ac71bc41-lqfs5j` (opening pattern lock, database creation race fix).
2. Punishment day and settings freeze removed; day evaluation wired into every sync (it was never called before,
   so no evening reminders were armed); rest day skips scheduled focus; Today screen polish.
3. Deeper deep dives and weekly reports.
4. Monthly and yearly reports (schema 6, `period_report`), progress graphs, backup reminder.

CI (unit tests, debug and release builds) is the only full compiler. `tools/pure-tests.sh` compiles and runs
the pure-logic tests on a plain JVM (about 200 tests) and works in the sandbox: run it before every push.

**Nothing of 2.0 has run on a phone yet.** BLUEPRINT section 15 lists the owner's checks; all are open.

## Sandbox constraints

- No Android SDK and no access to Google's Maven: Gradle cannot build here. Use `tools/pure-tests.sh`, then push
  and read the GitHub Actions result.
- AI providers are not reachable from the sandbox; AI calls are tested with fakes only.

## The owner's preferences

- Calm, classy, non-shaming; plain short explanations of what to tap; no gamification, no streaks.
- Builds and installs APKs from the Actions tab himself (`release.yml`, run by hand) and sends screenshots.
- Uses DeepSeek or Google AI Studio. AI output stays non-diagnostic; crisis text gets care, not tips.
- Be honest: never promise a cure, say what was not verified.

## Known limits and ideas (by value)

1. Any app that declares a home-screen activity is never suspended (`ScheduleEnforcer.readProtectedPackages`):
   installing a launcher with a browser is a way around schedules outside a brick. Protecting only the default
   launcher has its own trap (a suspended default home); needs a careful design and a phone to test on.
2. Two crashes within five minutes during a brick end it (the crash-loop breaker, by design). Aborts are not shown
   anywhere yet; showing them in the weekly report would make the escape visible.
3. The weekly report is anchored on the first data point while monthly and yearly are calendar periods; the
   graphs use Monday weeks. Fine in practice; noted in case it confuses.
4. A sick or travel day (declared ahead, like a rest day) if the one rest day a week proves too tight.
5. Home-screen widget for the urge button (the tile and icon shortcut exist).
