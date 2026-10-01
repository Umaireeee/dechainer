# Project handoff (read this first in a new session)

Last updated: 2026-10-01. Owner: Umaireeee. Repo: `Umaireeee/dechainer`. Default branch: `main-clean`.
The journal, the door and the guided-ride upgrade are all on `main-clean` (PR #3 merged; the ride upgrade follows it, see "State").

## What this project is
Two Android apps in one repo, built to cut compulsive phone use (and porn urges) and to protect study time.

1. **`:app` = Déchaîner** (fork of warleysr/dechainer). Device Owner app that suspends apps, schedules blocks, runs a Pomodoro with an honest log, private DNS, impulse lock, and forced removal (now **4 days**). Also has: Quick-start schedule presets, and the "urge action door" below.
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
- **Ride lock: tried and removed on request.** An all-apps lock (`RIDE_LOCK` door command, `RideLock`, an enforcer source) was built and then deliberately reverted; Déchaîner is back to its state at PR #3 plus nothing. A ride pauses only the apps chosen in Déchaîner > Settings > Impulse lock (`IMPULSE_BLOCK`). It could be rebuilt from git history (commit b34821f) if wanted.
- **Ease-of-use pass:** the ember starts on a tap again (a 0.7 s hold-to-start, a one-tap FOCUS_BLOCK button and an evening "did today go to plan?" check-in were built and then removed on request; see commit e76004a); a stub entry is saved the moment a ride starts (`JournalStore.put` upserts by time), so leaving loses nothing; `RideTileService` (quick-settings tile) and a static launcher shortcut start a ride via `action=ride`; the risky window is now a sliding four-hour window (`Insights.hotWindow`).
- **Full log** (`LogScreen`): Home > "See every entry". 14-day / 12-week chart (tap a bar to scope to that day or week, `Insights.weeks`/`entriesIn`), feelings tally, filter (`LogFilter`), every entry grouped by day (tap to open the detail and come back to the log), and a CSV export (`CsvExport`).
- **Déchaîner's own Urge log was removed on request** (screen, storage, Settings row, strings, tests). The door (`UrgeActionReceiver`) stays: it is what lets the journal pause apps. Entries saved in the old Déchaîner urge log are no longer reachable.
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
10. Evening check-in filled in from Déchaîner's focus log ("2 h 10 min of focus today" next to "Studied"): needs a `focus_minutes_today` column on `RideStatusProvider` and a background read in the journal.
11. Move the AI reports out of the entries string (see "Known limit" in the 2026-10-01 update).

## Update: restored on request (after the first merge)
The all-apps ride lock (`RIDE_LOCK`, Déchaîner's `RideLock` + enforcer source; Déchaîner has no new visible UI for it), the 0.7 s hold-to-start ember, the one-tap FOCUS_BLOCK button and the evening "did today go to plan?" check-in are back in the journal. Déchaîner's own Urge log stays removed and its unlock screen has no journal buttons.

## Update: bug-fix and quality pass (2026-09-30)
- **Focus intention restored:** the one-line intention field was lost when the patches became source; it is back on the Focus screen (`Pomodoro.setIntention`), under the subject chips.
- **Daily limits can't be dodged by switching off Usage access:** apps that ran out today are recorded (`app_time_limits_reached`, `LimitMath.carried`) and stay suspended until midnight even when the usage log can't be read. Removing the limit (recovery code) still frees them.
- **Impulse lock locks the clock** while it runs, like the ride lock and focus blocks (after a reboot it can only go by the wall clock).
- **The Urge Journal stays usable during a brick:** excluded from brick suspension and on the lock-task list, opened from its Quick Settings tile.
- **Journal copy fixed:** the "Start a focus block?" dialog no longer claims the recovery code can end it (a block bricks the phone to the end).
- **AI replies cut off by the network** are kept when what arrived already reads as a report (`AiClient.usablePartial`).
- Check-in reminders skip rides older than six hours; deleting a focus session clears its pending lecture question.
- README/GUIDE/URGE_ACTIONS describe the app as it is: focus blocks (brick), daily limits only (cooldowns, windows and groups were removed earlier).
- Local compile check: `dl.google.com` (Google Maven) is blocked from the sandbox, so AGP cannot run; the pure-logic tests of both apps can be run in a scratch Kotlin/JVM project against the Maven Central mirror `maven-central.storage-download.googleapis.com`. CI remains the only full compile check.


## Update: loophole pass on the gates (2026-09-30)
Read in full: RecoveryGate, UnlockDelay, ConfigTab, the schedule list/editor/view model, Device Owner removal, LockScreen/MainActivity, Protections, ImpulseLockScreen, BrowserRestrictionsManager. Sound as they were: recovery code (hashed, unlock delay can't be shortened by a reboot), schedule locks (re-checked at save), Device Owner removal (code + refused during locked schedule / brick / ride), impulse and focus settings (session-gated / idle only). Fixed:
- **Unlocking was forever:** `authenticated` never reset. Now an impulse lock (including one the journal starts) sends you back to the countdown and ends any recovery session; a challenge finished after it started doesn't let you in; and after more than 5 minutes in the background the app asks to be unlocked again (`RELOCK_AFTER_MS`).
- **Protections could open a gap in a locked window:** switching off a restriction a schedule/block holds (the clock lock) now re-syncs at once, so it is put straight back.
- **Newly installed browsers** get the blocklist, SafeSearch and the secure-DNS lock right after install (package listener in `DechainerApplication`), instead of resolving around the DNS filter until the next re-apply. Only while Déchaîner's process is alive; it usually is.

## Update: crisis support and prompt fixes (2026-09-30)
- **Crisis no longer ends the conversation.** Before, a note matching `Safety` showed one paragraph and nothing else. Now the plan screen leads with `SupportCard` (three steps for the next minutes, **Call / Text** the saved person with a ready "can you call me?" message, and *Open the phone app*), hides blocking and urge tips (blocking could cut off the person to message), and the AI still answers: the user message says the note was flagged, and the system prompt has a fixed crisis shape (headline, three steps, encouragement, one question; everything else empty).
- **Someone to call** (`SupportContact`, Settings, first section): name + number, on the phone only, never sent to the AI.
- **Ride lock** leaves the default SMS app open (calls were already open).
- **Weekly review** now gets the previous 7 days' counts and what its previous review (3+ days old) suggested (`Prompt.PreviousReview`), and is told to follow up honestly.
- The dead `"why"` key is gone from both schemas (the parser still reads it from old saved reports).

## Update: final polish before the owner's subscription ended (2026-09-30)
- Journal backup (`Backup` in Model.kt) now holds entries **and** the person's own rules (`{"version":2,"entries":[...],"plans":[...]}`); bare entry lists from older backups still import. Import opens a file picker (clipboard fallback).
- Crisis entries reopened from the log show `SupportCard` too.
- Forced removal: when ready, the dialog's second button only closes it; cancelling the finished wait is a separate red button.
- Word challenge field has autocorrect off.

### State at hand-off
- Everything above is merged to `main-clean`; CI (unit tests + debug build of both apps) is green. **Nothing has been confirmed on a phone yet.** First things to check: the brick + journal tile, the impulse lock sending you out of Déchaîner, the 5-minute relock, daily limits with Usage access switched off, the crisis card's Call/Text buttons, and backup export/import of rules.
- The coach prompt went through five rounds against a real model, and the owner's own upgrade was merged in (see git history). Lessons: never quote a banned phrase or a label for a forbidden thing (it gets echoed back); give the model the exact fact it would otherwise guess; structural rules ("leave X empty when ...") beat gentle ones.
- To test the prompt without a phone: build `Prompt.systemFor(true)` + `Prompt.user(...)` in a scratch JVM project (Model.kt + Ai.kt + a stub for `androidx.core.content.edit`, against android.jar, using the Maven Central mirror `maven-central.storage-download.googleapis.com`) and paste the output into a chat.

## Update: growth features (2026-09-30, on the owner's request)
- **`Q.BEFORE` "What came just before?"** (scrolling, avoiding a task, lying awake, alone after a hard moment, tired/hungry, nothing), in both the quick and the full interview; `Coach.plan` adds PHONE_OUT / SMALL_TASK from it; the prompt names it as an input.
- **Evening life check-in:** `DayLog(result, areas: Set<Area>, note)` replaces the bare `DayResult` per day (old stored days still read). `Area` = SLEPT, STUDIED, MOVED, CONNECTED. `Insights.lifeFacts` gives counts only (never the note) to the deep dive (last 3 days) and the weekly review (7 days, which is now told to look at the whole week).
- **Kept deep dives:** `Kept(id, kind, text)`, `JournalStore.keep/kept/unkeep`, a "Keep this deep dive" button inside `ReportView(keep = ...)`, `KeptScreen`, Home button; included in backups.
- **Talk to your coach:** `TalkScreen`, `TALK_SYSTEM` (same voice, honesty, crisis and injection rules), `Prompt.talkUser` (their text, About, 30-day digest, 7-day life counts, their rules; flagged crisis words). Every finished talk is kept automatically.

## Update: weekly deep dive instead of a coach chat (2026-09-30, owner's decision)
- "Talk to your coach" was removed on request (a chat inside an urge app is one more screen to escape into). `Kept.Kind.TALK` and `kept_talk` stay so talks kept earlier still read.
- The weekly review is now the **weekly deep dive**: `Prompt.weeklyUser` lists every real entry of the last 7 days via `Prompt.entryLine` (day, time, urge/slip, every answer including what came before, ride result, what was tried, result, the note trimmed to 300 chars), and `WEEKLY_SYSTEM` asks for chains (before -> feeling -> place -> hour -> ending), what worked vs failed, and strategies aimed at the earliest link. Notes are now sent for the weekly deep dive; the consent text says so. Evening lines are still never sent.
- Also in this round: delete an entry (Detail > Delete this entry), kept headline shown once, check-in facts say "marked" (an unmarked box is not a zero), and every prompt says one idea once, no judging the person, patterns need several days.

## Update: audit pass and effectiveness features (2026-10-01, on the owner's request to "find things to improve, correct and optimize, and better features")
Both apps were read again screen by screen. Fixed:
- **The journal can never be suspended by any block of Déchaîner.** An allow-only bedtime window (all presets are allow-only) suspended every app not on its list, the journal included, at night, which is exactly when the safety net is needed. `LockSafety.neverBlocked` (Déchaîner, system UI, the phone, the journal) now seeds `ScheduleEnforcer.readProtectedPackages`, so no schedule, daily limit, focus lock or impulse lock can suspend it (it is also hidden from the Apps tab and from the schedule and impulse-lock pickers, so nothing offers a choice that would do nothing). The brick and the ride lock already left it open.
- **Slow paths:** the app picker decoded every icon on every recomposition (now once per row, lazily: `AppItem.iconLoader`); the Apps tab list was loaded once and went stale (it now reloads on resume); each package event reloaded every icon (the receiver now only invalidates caches); the ember and the breathing circle recomposed every frame (now `graphicsLayer` lambdas); `label()` did a resource-name lookup on every recomposition (now remembered).
- **Ember:** a scroll that takes over the gesture is no longer counted as a tap (the "hold it" hint used to appear while scrolling past it).
- **Journal Home goes stale after hours in the background** (greeting, evening card, counts): it now refreshes whenever the app comes to the front (`MainActivity.resumeTick`).

New (all counted or typed on the phone; the only new thing sent to the AI is the reason, and the consent texts say so):
- **Why you're doing this** (`JournalStore.reason`, in its own small `why` prefs file because it is saved as it is typed; part of the backup; first section of Settings). Shown on every ride and after a slip (not on a crisis screen), and passed to both prompts as "Their reason ... (their own words)"; the prompts say to bring it back once, in their words, never as a stick. "Delete all my entries" leaves it, like the AI key and the about text.
- **Proof on the ride** (`Insights.proof`): "In 7 of your last 10 rides the urge passed or got weaker" when at least three rides exist and at least half worked, else "Urges you've ridden out so far: N", else nothing. Never opens a hard moment with a poor ratio.
- **Patterns** (`Patterns.summary`, `LogScreen` > "What your entries show"): the usual run-up (`Q.BEFORE` + feeling), the usual place, the busiest four hours (`Insights.busiestWindow`, shared with the Home heads-up), where and when slips happen, and the steps that helped, all from the entries on the phone. Needs 5 entries and 3 repeats, shows its counts, ignores "nothing in particular".
- **First move** (`DayLog.next`, 80 characters, JSON key `x`): a line on the evening card; next morning Home shows it with "Start a focus block with this", which opens the usual length question (25/50/90; a block bricks the phone, so it is never a silent one-tap) and passes the text as the intention. The text travels to Déchaîner as the new optional `intention` extra of `FOCUS_BLOCK` (`DoorAction.intention`, `UrgeActions.EXTRA_INTENTION`); the receiver sets it only if that request really started the block. Never sent to the AI.
- **Weekly look back reminder** (`ReminderSettings.weeklyMinute`, `Notifier.rearmWeekly`, `MainActivity.ACTION_REVIEW`): Sundays at the chosen time, only if `Insights.reviewReady` (3 real entries in 30 days), opens the weekly deep dive unless the person is in a ride, a question or a plan.
- **Setup card** also asks for a reason and someone to call, offers Open settings, and can be hidden for good.
- **Déchaîner's brick screen** has "Urge hit? Ride it out": launches the journal with `action=ride` (its five-second countdown still applies). The journal's tile already worked during a brick; this is the visible path.

Tests: 158 pure-logic tests pass in a scratch Kotlin/JVM project (journal Model/Ai/tests plus Déchaîner's PomodoroCore, UsageMath, BlockSchedule, LockSafety, RideLock, UrgeActions and their tests). The Compose/Android parts are only compiled by CI and nothing has run on a phone.

Known limit, not fixed: **all entries, with their AI reports, live in one JSON string in SharedPreferences** (`JournalStore`, up to 2,000 entries). Every change rewrites the whole string with a synchronous commit and every `all()` parses it. Fine for a year of normal use; a journal approaching the limit would feel slow on save. The fix is to keep reports (the bulk) under their own keys or files, with a migration; do that before it hurts, not after.

First things to check on a phone: Settings > Why you're doing this, then a ride (the panel should appear between "Do one thing now" and "Your own rule"); the evening card's first move and the next morning's card (does the focus block's question say "You planned: ..."?); the Sunday reminder; the log's patterns after five or more entries with "What came just before?" answered; the brick screen's ride button while a block runs; a bedtime window with the journal's tile.
