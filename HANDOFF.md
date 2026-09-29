# Project handoff (read this first in a new session)

Last updated: 2026-09-29. Owner: Umaireeee. Repo: `Umaireeee/dechainer`. Default branch: `main-clean`.
Working branch: `ccr-15667a04-h0f1td` (holds work newer than `main-clean`; **not yet merged**, see "State").

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
- Merged to `main-clean` via PR #1 (repo cleanup, source committed directly, old patches removed) and PR #2 (presets, urge log, 4-day forced removal, GUIDE).
- On branch `ccr-15667a04-h0f1td` and **not merged yet**: the urge action door, the whole `:journal` module, providers, weekly review, backup, About-you, depth settings. CI was green through commit `3a64b4f`; commit `4ce6659` (deeper deep dives, no duplicate built-in steps) was pushed and its CI was still being checked.
- The user has run the journal on a phone: the DeepSeek deep dive works and reads well; the block button showed and the setup checklist exists. **Not yet confirmed by the user:** that "Pause my distraction apps" really suspends the chosen apps end to end, the new logo, and the release APK size (was 43 MB before removing extended icons and enabling R8).

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
1. **"Make this a rule"** button: turn the AI's "If X, then Y" into a Déchaîner schedule (needs a new tighten-only door action plus a confirmation screen).
2. Quick access: home-screen widget, quick-settings tile, or a notification action to log an urge in two taps.
3. Daily evening check-in reminder ("did you do the work?") tied to the study block.
4. Grayscale during focus blocks or urge blocks (needs WRITE_SECURE_SETTINGS via Shizuku, as in upstream's ColorFilterController).
5. Bedtime mode as a one-tap preset; "friction" delays before short unblocks.
6. Optional accountability: share the weekly review (share sheet exists for the older urge log; add for the journal).
7. Encrypt the stored AI key with the Android Keystore; crash logging for release builds.
8. On-device UI tests; tune the coach prompts after a week of real entries.
9. Housekeeping: merge the branch to `main-clean` once the user confirms it works on the phone, then delete the old branch in the GitHub UI, update `README.md` and `GUIDE.md`.
