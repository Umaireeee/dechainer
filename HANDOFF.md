# Project handoff (read this first in a new session)

Last updated: 2026-09-29. Owner: Umaireeee. Repo: `Umaireeee/dechainer`. Default branch: `main-clean`.
The journal, the door and the guided-ride upgrade are all on `main-clean` (PR #3 merged; the ride upgrade follows it, see "State").

## What this project is
Two Android apps in one repo, built to cut compulsive phone use (and porn urges) and to protect study time.

1. **`:app` = Déchaîner** (fork of warleysr/dechainer). Device Owner app that suspends apps, schedules blocks, runs a Pomodoro with an honest log, private DNS, impulse lock, and forced removal (now **4 days**). Also has: Quick-start schedule presets, a simple local urge log (Settings), and the "urge action door" below.
2. **`:journal` = Urge Journal** (new). An adaptive interview for the moment of an urge or a slip, a rule-based coach, an optional AI "deep dive", a weekly review, history, backup, and a **Lock it in** button that asks Déchaîner to block.

Both are signed with the same key (release workflow), because the link between them is a signature-level permission.

## The link between the apps (the "door")
- Déchaîner exposes `io.github.warleysr.dechainer/.UrgeActionReceiver`, action `io.github.warleysr.dechainer.URGE_ACTION`, guarded by permission `io.github.warleysr.dechainer.permission.URGE_ACTION` (protectionLevel signature). See `URGE_ACTIONS.md`.
- Commands: `IMPULSE_BLOCK` (15 to 360 min; the panic button remotely; suspends the apps chosen in Déchaîner > Settings > Impulse lock, only if that is set to "Timer and suspend apps") and `FOCUS_BLOCK` (25 to 120 min; committed focus block).
- Rules that must not change: commands only **tighten** (never unblock, never edit schedules or the recovery code), durations are capped, a running impulse block is never shortened, ignored unless Device Owner.
- Install order matters: Déchaîner first, then the journal (both from the same signed run).

## Journal internals (`journal/src/main/java/io/github/warleysr/urgejournal/`)
- `Model.kt`: `Q`/`Opt` enums (labels are `q_*`/`opt_*` strings looked up by name), `QuestionTree`, `Coach` (rule-based plan: block length by strength and hour, steps, reasons, rules), `Entry` (+ JSON), `Insights` (week stats, 7-day bars, anonymous digest), `Plain` (English for prompts).
- `Ai.kt`: `Provider` (Google AI Studio default `gemini-3.8-flash`, OpenRouter, DeepSeek, OpenAI, Custom base URL), `AiSettings` (key, provider, model, about, deep, expandAll, per-provider consent), `AiClient` (OpenAI-style `/chat/completions`, `/models` loader, error mapping), `Prompt` (`SYSTEM`, depth addenda, `WEEKLY_SYSTEM`, weekly input without notes), `ReportParser` (lenient JSON), `Safety` (crisis keywords: shows care instead of a report).
- `Store.kt`: `JournalStore` (SharedPreferences JSON, export/import, weekly review cache) and `Door` (sends the broadcast, checks install and permission).
- `MainActivity.kt`: single-Activity state machine (HOME, INTERVIEW, NOTE, PLAN, DETAIL, SETTINGS, REVIEW). Replies are tied to `runId` so a slow reply cannot land on the wrong screen.
- `Screens.kt`, `Components.kt`, `Theme.kt`: Compose UI, "warm paper" dark palette matching Déchaîner, serif headings, ember button, 7-day chart.
- Tests: `journal/src/test/.../JournalLogicTest.kt` (logic, prompts, parsing, providers, strings check). Déchaîner tests are under `app/src/test`.

## Build and release
- `.github/workflows/build.yml` = **CI** (unit tests + debug APK) on every push.
- `.github/workflows/release.yml` = **Release build (tested)**, run by hand from the Actions tab on the wanted branch. Runs unit tests, builds and signs both apps, runs emulator tests for Déchaîner, uploads artifacts **DECHAINER-SCHEDULES-RELEASE-TESTED** and **URGE-JOURNAL-RELEASE**. Needs repo secrets `SIGNING_PASSWORD` and `SIGNING_KEY_B64` (already set; never re-run `make-key.yml`, and never lose the key: updates must use the same one).
- Version codes are stamped from the run number in the release workflow (both apps).
- The journal release build has R8 minify on (`isShrinkResources=false` on purpose: labels are looked up by name).

## State
- `main-clean` holds everything through PR #3 (the `:journal` module, the door, providers, weekly review, backup, HANDOFF).
- **Guided-ride upgrade** (built after PR #3; see the branch/PR it landed on): the journal's first tap is now
  **one tap = `IMPULSE_BLOCK` 30 min + a ten-minute guided ride** (`RideScreen`: breathing circle, one step, the person's own rule), *then* a check-in (`AfterScreen`: passed / weaker / still strong, what was tried), then an optional **3-question** interview (`QuestionTree.sequence(..., quick = true)`) and the plan.
  - Feedback loop: `Entry.tried` + `Entry.after`; `Coach.rank` reorders steps by the person's own success rate once a step has `Coach.MIN_TRIES` (3) tries; `Coach.rideStep` picks the ride's one step.
  - `Notifier.kt`: optional plain check-in notification ~20 min after a ride, optional daily heads-up (with a "pause my apps for an hour" action) from `Insights.hotWindow` (needs >= 6 entries in 30 days, >= 4 and >= 40% in one window), `ReminderReceiver` (re-arms after boot). Inexact alarms only; permission is asked from a tap in Settings/Home, never at the peak of an urge.
  - `MyPlan`: the person's own If-then rules (written/edited by them; the coach only suggests wording). Stored in `JournalStore`, shown on rides and plans, managed in Settings.
  - Softer slip framing: `Insights.cleanDays` ("X of the last N days without a slip") replaces the streak; `Insights.heavier` shows one dismissible care card; `Insights.shareText` is a counts-only share.
  - The AI key is encrypted with the Android Keystore (`SecretBox`); old plain keys migrate on first read. Backups are already off.
  - Déchaîner's unlock screen got a calmer heading and a "Ride it out with the Urge Journal" button while the panic block runs (launches the journal with extra `action=ride`).
- **Ride lock:** the journal also sends `RIDE_LOCK` (10 min; 30 for "still strong"): `RideLock` + a `BlockSource` in `ScheduleEnforcer` suspend every launcher app except calls/protected packages, alarm apps, emergency apps and the journal, with its own end alarm and the clock locked meanwhile. Only lengthens, never shortens; capped at 30 min (`UrgeActions.RIDE_*`).
- **Ease-of-use pass:** the ember starts on a 0.7 s hold (a quick tap only says "hold"); a stub entry is saved the moment a ride starts (`JournalStore.put` upserts by time), so leaving loses nothing; `RideTileService` (quick-settings tile) and a static launcher shortcut start a ride via `action=ride`; Home has a one-tap `FOCUS_BLOCK` (25/50/90); optional evening "did today go to plan?" reminder + Home card (`DayResult`, `Insights.planDays`); the risky window is now a sliding four-hour window (`Insights.hotWindow`). Déchaîner's unlock screen has an "Open the Urge Journal" button.
- The pure logic has JVM tests (`JournalLogicTest`, 43+). A scratch JVM project (Model.kt + Ai.kt without the Android classes + the test) compiled and passed locally; Compose/Android code is only compiled by CI.
- **Not yet confirmed by the user on a phone:** the whole ride flow, that "Pause my distraction apps" really suspends the chosen apps end to end, notification permission prompts, the new logo, and the release APK size.

## Constraints of the authoring sandbox (important)
- No Android SDK: nothing can be compiled or run locally. **CI is the only compile check**, so push small and check CI.
- `openrouter.ai` and Google's API are blocked from the sandbox, so AI calls can only be tested on the user's phone.
- The Bash tool's safety classifier is sometimes flaky; retry once, then use other tools. Deleting files was blocked once; deleting the remote branch via git failed (do it in the GitHub UI).
- Always end commits with the attribution lines the session asks for. Do not open or merge PRs unless the user asks.

## The user's preferences
- Wants a calm, classy, non-shaming tool; depth over brevity; actions proportionate (pause chosen apps, not a dumb phone).
- Runs the workflows and installs APKs himself; sends screenshots of problems. Prefers plain, short explanations of what to tap.
- Uses DeepSeek directly (Other/DeepSeek chip) or Google AI Studio's free tier. AI advice must stay non-diagnostic; crisis text gets care, not tips.
- Be honest: never promise it will "cure" anything or that no more updates are needed.

## Ideas not built yet (roughly by value)
1. **"Make this a rule" into Déchaîner**: turn a saved If-then rule into a real Déchaîner schedule (needs a new tighten-only door action plus a confirmation screen in Déchaîner).
2. Quick access without opening the app: home-screen widget, quick-settings tile or lock-screen shortcut that fires the ride (the ember is the only one-tap today).
3. Daily evening check-in reminder ("did you do the work?") tied to the study block.
4. Grayscale during rides or focus blocks (needs WRITE_SECURE_SETTINGS via Shizuku, as in upstream's ColorFilterController).
5. Bedtime mode as a one-tap preset; "friction" delays before short unblocks; a phone-charges-outside reminder.
6. Optional accountability beyond the counts-only share text (a weekly send to one trusted person).
7. Crash logging for release builds; on-device UI tests; tune the coach prompts after a week of real entries (the AI prompt now includes the ride result and what was tried).
8. A slip-in-focus-block flow inside Déchaîner itself (today the slip is logged in the journal).
9. Housekeeping: delete old remote branches in the GitHub UI; keep README/GUIDE in step.
