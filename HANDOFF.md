# Project handoff (read this first in a new session)

Last updated: 2026-10-06. Owner: Umaireeee. Repo: `Umaireeee/dechainer`. Default branch: `main-clean`.
The full history of earlier sessions (the two-app project, every phase report) is in `docs/handoff-history.md`.

## What this is

Déchaîner 2.0: one Android app (`:app`, package `io.github.warleysr.dechainer`) that runs as Device Owner.
`BLUEPRINT.md` (version 1.2) is the source of truth; `CLAUDE.md` holds the rules for Claude Code.

- **Locks:** a 10-minute urge lock, focus blocks (manual, or weekly FOCUS timetable entries set once), block
  schedules and daily limits, all planned by the pure `LockPlanner` and applied by `LockEngine.sync`.
- **Urge flow:** breathing, writing, AI questions, a deep dive that sees the last 30 days through earlier deep
  dives (`ai/DeepDiveHistory.kt`), the note deleted once the deep dive exists.
- **No daily checklist (removed after 1.2 on the owner's decision):** the Today screen, the `day`/`goal` tables,
  the evening reminders, the rules onboarding, `activatedOn` and the rest-day link are gone. There is no rest
  day and no scheduled-focus skip. Deleting a finished urge, slip, session or deep dive is always allowed.
  See section 17C of the blueprint.
- **Deep dive (the one AI piece):** no word cap. It names the chain, the earliest link, the pattern,
  what the urge is really costing, what helped, a short line for the next time and if-then plans, and
  it keeps its memory of the last 30 days through earlier deep dives. The weekly, monthly and yearly
  reports, their progress graphs and the backup reminder were removed after 1.2 on the owner's decision.

## State at hand-off (2026-10-02)

Branch `ccr-0b16a937-euwxt4` holds, on top of `main-clean`:
1. The six unmerged commits of `ccr-ac71bc41-lqfs5j` (opening pattern lock, database creation race fix).
2. A deeper deep dive; then the weekly, monthly and yearly reports removed after 1.2 on the owner's decision,
   with the deep dive uncapped and expanded (section 17B of the blueprint).
3. The daily checklist (Today) removed entirely after 1.2 on the owner's decision, with a simpler delete,
   export and import, and a redesigned Home (section 17C of the blueprint).

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
   anywhere yet; there is no report left to record them in.
3. A home-screen widget for the urge button (the tile and icon shortcut exist).
