# Project handoff (read this first in a new session)

Last updated: 2026-10-01. Owner: Umaireeee. Repo: `Umaireeee/dechainer`. Default branch: `main-clean`.
The journal, the door and the guided-ride upgrade are all on `main-clean` (PR #3 merged; the ride upgrade follows it, see "State").

## What this project is
Two Android apps in one repo, built to cut compulsive phone use (and porn urges) and to protect study time.

1. **`:app` = Déchaîner** (fork of warleysr/dechainer). Device Owner app that suspends apps, schedules blocks, runs a Pomodoro with an honest log, private DNS, the urge lock, and forced removal (now **4 days**). Also has: Quick-start schedule presets, and the "urge action door" below.
2. **`:journal` = Urge Journal** (new). An adaptive interview for the moment of an urge or a slip, a rule-based coach, an optional AI "deep dive", a weekly review, history, backup, and a **Lock it in** button that asks Déchaîner to block.

Both are signed with the same key (release workflow), because the link between them is a signature-level permission.

## The link between the apps (the "door")
- Déchaîner exposes `io.github.warleysr.dechainer/.UrgeActionReceiver`, action `io.github.warleysr.dechainer.URGE_ACTION`, guarded by permission `io.github.warleysr.dechainer.permission.URGE_ACTION` (protectionLevel signature). See `URGE_ACTIONS.md`.
- Commands (since Phase 2): `IMPULSE_BLOCK` and `RIDE_LOCK` both start the **urge lock** (a fixed 10 minutes, minutes ignored, never extended), and `FOCUS_BLOCK` (25 to 120 min; committed focus block). The status provider's columns changed, see `URGE_ACTIONS.md`.
- Rules that must not change: commands only **tighten** (never unblock, never edit schedules or the recovery code), durations are capped, a running lock is never shortened or extended, ignored unless Device Owner.
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
- `.github/workflows/release.yml` = **Release build (tested)**, run by hand from the Actions tab on the wanted branch. Runs unit tests, builds and signs both apps, runs emulator tests for Déchaîner, uploads artifacts **DECHAINER-SCHEDULES-RELEASE-TESTED** and **URGE-JOURNAL-RELEASE**. Needs repo secrets `SIGNING_PASSWORD` and `SIGNING_KEY_B64` (already set; `make-key.yml` is gone, and never lose the key: updates must use the same one).
- Version codes come from the run number, passed as `-PversionCode` (and `-PversionNameSuffix`) to Gradle; both apps read them in their `build.gradle.kts` (no more `sed`). `build.yml` also builds the release APK and checks that no debug control is in the merged release manifest. Every action is pinned to a commit SHA.
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
- **Backups were missing the evening check-ins** (so a reinstall lost what the weekly deep dive reads about sleep, study, body and people): `Backup` now carries `days` (and each day's first move); import adds only days not already answered here.

New (all counted or typed on the phone; the only new thing sent to the AI is the reason, and the consent texts say so):
- **Why you're doing this** (`JournalStore.reason`, in its own small `why` prefs file because it is saved as it is typed; part of the backup; first section of Settings). Shown on every ride and after a slip (not on a crisis screen), and passed to both prompts as "Their reason ... (their own words)"; the prompts say to bring it back once, in their words, never as a stick. "Delete all my entries" leaves it, like the AI key and the about text.
- **Proof on the ride** (`Insights.proof`): "In 7 of your last 10 rides the urge passed or got weaker" when at least three rides exist and at least half worked, else "Urges you've ridden out so far: N", else nothing. Never opens a hard moment with a poor ratio.
- **Patterns** (`Patterns.summary`, `LogScreen` > "What your entries show"): the usual run-up (`Q.BEFORE` + feeling), the usual place, the busiest four hours (`Insights.busiestWindow`, shared with the Home heads-up), where and when slips happen, and the steps that helped, all from the entries on the phone. Needs 5 entries and 3 repeats, shows its counts, ignores "nothing in particular".
- **First move** (`DayLog.next`, 80 characters, JSON key `x`): a line on the evening card; next morning Home shows it with "Start a focus block with this", which opens the usual length question (25/50/90; a block bricks the phone, so it is never a silent one-tap) and passes the text as the intention. The text travels to Déchaîner as the new optional `intention` extra of `FOCUS_BLOCK` (`DoorAction.intention`, `UrgeActions.EXTRA_INTENTION`); the receiver sets it only if that request really started the block. Never sent to the AI.
- **Weekly look back reminder** (`ReminderSettings.weeklyMinute`, `Notifier.rearmWeekly`, `MainActivity.ACTION_REVIEW`): Sundays at the chosen time, only if `Insights.reviewReady` (3 real entries in 30 days), opens the weekly deep dive unless the person is in a ride, a question or a plan.
- **Setup card** also asks for a reason, someone to call and the evening reminder, offers Open settings, and can be hidden for good. Yesterday's first move shows until 17:00, when the evening check-in opens.
- **Déchaîner's brick screen** has "Urge hit? Ride it out": launches the journal with `action=ride` (its five-second countdown still applies). The journal's tile already worked during a brick; this is the visible path.

Tests: 159 pure-logic tests pass in a scratch Kotlin/JVM project (journal Model/Ai/tests plus Déchaîner's PomodoroCore, UsageMath, BlockSchedule, LockSafety, RideLock, UrgeActions and their tests). The Compose/Android parts are only compiled by CI and nothing has run on a phone.

Known limit, not fixed: **all entries, with their AI reports, live in one JSON string in SharedPreferences** (`JournalStore`, up to 2,000 entries). Every change rewrites the whole string with a synchronous commit and every `all()` parses it. Fine for a year of normal use; a journal approaching the limit would feel slow on save. The fix is to keep reports (the bulk) under their own keys or files, with a migration; do that before it hurts, not after.

First things to check on a phone: Settings > Why you're doing this, then a ride (the panel should appear between "Do one thing now" and "Your own rule"); the evening card's first move and the next morning's card (does the focus block's question say "You planned: ..."?); the Sunday reminder; the log's patterns after five or more entries with "What came just before?" answered; the brick screen's ride button while a block runs; a bedtime window with the journal's tile.

## Update: Déchaîner 2.0, Phase 1 "Safety foundation" (2026-10-01; see BLUEPRINT.md, section 14)
Work follows `BLUEPRINT.md` one phase at a time; this is Phase 1 only. Phases 2 to 7 are not started.

What exists now (all under `app/src/main/java/io/github/warleysr/dechainer/`):
- `lock/LockPlanner.kt` + `lock/LockModel.kt`: the pure `plan(now, zone, state)`: running holds, apps to suspend, restrictions, sites, brick or not, next wake time, and whether a stored focus block has expired. `lock/LockEngine.kt` is the Android side: `sync` (blocking) and `requestSync` (coalesced, `lock/SyncCoalescer.kt`) gather state, plan on the trusted clock and apply through `ScheduleEnforcer.applyPlan` (which is now only the apply side). Every wake-up calls it: boot, app update, alarms, receivers, `MainActivity.onResume`, the Focus tick, unlock (`USER_PRESENT`, registered in `DechainerApplication`), and Device Owner being granted.
- Brick expiry (R1): a block with end `<= now` is over whatever the alarms did. `Pomodoro.closeExpiredBlock` closes it through the alarm's own path; the block end is also a wake-up in the plan (the backup alarm through `ScheduleReceiver`).
- Crash-loop breaker (R2): `guard/CrashGuard.kt` (pure) and `guard/CrashHandler.kt` (installed first in `Application.onCreate`); `LockEngine.abortBrick`, never reachable from the UI.
- `lock/SystemGuard.kt`: automatic time and zone forced on, and `setUserControlDisabledPackages` for this app (Force stop and Clear data), on every pass. Date, time and zone are locked all the time when Device Owner (the old "Lock date and time" switch is now a permanent, disabled row). `DISALLOW_APPS_CONTROL` is in the recommended system rules but not switched on by itself.
- `clock/TrustedClock.kt` (+ pure `TrustedClockMath`): wall time that never goes backwards; checkpoint in `app_state` (`lastSeenWall` holds the trusted reading). Pomodoro, the limits' day start and the door's block end use it; alarms are aimed at wall time converted from it.
- `store/`: `DechainerDatabase` (SQLiteOpenHelper, WAL), pure `Migrations` (schema version 1 = `app_state` only), `AppStateRepository` (reads never write; `setAll` is a transaction; `runOnce` keeps one-off migrations behind a flag), `Store`. Other tables of blueprint section 7 arrive with the phase that needs them, each as a new migration step.
- Cold start diet: no eager `getApps()`; the locale reset, the un-hide migration and the browser-policy repair run once behind `app_state` flags; one sync per wake-up.
- `Rules.kt`: the blueprint's fixed numbers. Debug-only controls live in `app/src/debug` (see below).

Not in the blueprint but still live (carried unchanged): the old non-brick "lock apps during a session" option (`FOCUS_SESSION`). The focus block's end time still lives in the Pomodoro prefs; the urge lock and the punishment day are in `app_state` (see Phase 2).

Debug controls (debug builds only; the receiver is in the debug source set and CI fails if a release manifest contains it). On an emulator or spare phone with Device Owner set:
- `adb shell am broadcast -n io.github.warleysr.dechainer/.debug.DebugControlReceiver -a io.github.warleysr.dechainer.DEBUG_FOCUS_BLOCK --ei minutes 1` starts a 1 minute block (no 10 minute minimum).
- `... -a io.github.warleysr.dechainer.DEBUG_DROP_ALARMS` forgets the alarms that would end the block; then unlock or open the app: the block must end from the time alone.
- `... -a io.github.warleysr.dechainer.DEBUG_ABORT_BRICK` runs the abort.
- `... -a io.github.warleysr.dechainer.DEBUG_CRASH` crashes the app on purpose through the real handler. With a block running, send it twice within 5 minutes: the second crash must abort the block (apps released, home screen back).

Testing here: the sandbox has no Android SDK (`dl.google.com` is denied), so CI is the compiler. Pure logic runs in a scratch Kotlin/JVM project (Gradle 8.14 with the Maven Central mirror `maven-central.storage-download.googleapis.com`, source files picked from `app/src` by a list, working dir set to `app/`): 125 pure tests pass there. CI runs everything: 193 app tests (91 from before this phase, 102 new, 24 of them Robolectric). Robolectric is used here for the first time in this repo: the store, clock and Pomodoro tests use `@Config(sdk = [34], application = Application::class)` so the real Application does not start the engine; `LockEngineSyncTest` uses the real `DechainerApplication` because the blocking code reaches for it. Robolectric's Device Owner shadow does not read back applied restrictions or `getUserControlDisabledPackages`, so those are checked through the engine's own records and on the phone. The CI log lists every test result.

Install order (blueprint 14A): test this phase on an emulator or spare phone first; the daily phone only after the section 15 checks that apply (1, 5, 6, 7 for this phase).

## Update: Déchaîner 2.0, Phase 2 "One lock engine" (2026-10-01; see BLUEPRINT.md, sections 5 and 14)
Phase 2 only. Phase 3 (Today/Home, the Urge screen) is not started, so there is still no Home and no breathing screen: the panic button on the unlock screen starts the urge lock and the main screen shows `screens/LockedHomeScreen.kt` (end time and one calm sentence).

What exists now:
- **Modes** (`lock/LockModel.kt`): `SCHEDULE`, `DAILY_LIMIT`, `FOCUS_SESSION` (holds) and `URGE_LOCK`, `FOCUS_BLOCK`, `PUNISHMENT_DAY` (bricks, `LockMode.isBrick`). `RIDE_LOCK` and `IMPULSE_LOCK` are gone.
- **Allow sets** (`lock/LockAllow.kt`, D3 and D7): urge = alarm clock only (SMS and Emergency Info are blocked); punishment day = alarm plus the owner's study-app list, empty by default (`LockStateStore.setPunishmentOwnerApps`, no UI yet); focus block = alarm, emergency apps, the owner's list. Calls and the dialer are in `PhoneFacts.protectedApps`, so they are never blocked by anything.
- **Precedence** (`lock/LockPlanner.kt`, 5.3): holds only ever add. The blocked set of overlapping bricks is the union of what each blocks, which is the intersection of their allow sets. The phone unlocks when the last brick ends; `BrickStatus.primary` names the one ending last (tie: punishment, then urge, then focus). A schedule or limit ending changes none of that.
- **Urge lock** (`lock/UrgeLockRule.kt`, `LockEngine.startUrgeLock`): ten minutes (`Rules.URGE_LOCK_MS`), stored in `app_state` (`urgeLockStartedAt`, `urgeLockEndsAt`) before anything else, a second start never extends it, not started inside a focus block or a punishment day (`Covered`) or without Device Owner (`Unavailable`). Its end is a wake-up in the plan, and it is over by the time even if the alarm is lost.
- **Punishment day** (`PunishmentInput`, `LockStateStore.setPunishment`): a window `[startsAt, endsAt)` in `app_state` (`punishmentFrom`, `punishmentUntil`, `punishmentDate`). Nothing writes one yet except the debug build; the day evaluation is Phase 5. A day that has not started is a wake-up, so the brick is on at its start, not at the next unrelated sync.
- **Settings freeze** (`lock/SettingsFreeze.kt`, 5.5): checked inside the repositories that write settings (schedules, daily limits, app suspend and uninstall-block, Private DNS and its guard, Pomodoro settings/allow list/tags/targets, the security settings, the recovery code, forced removal, protections, the entry challenge, the study-app list), against the trusted clock. A manual focus block is refused on a punishment day. Reading, the urge flow and everything outside settings are never refused.
- **Entry challenge**: the old impulse lock's challenge (none, maths, words) is now `SecurityManager.EntryChallenge` and `screens/tabs/EntryChallengeScreen.kt`, with the preference key `impulse_lock_mode` unchanged so a stored choice survives.
- **Journal link**: `data/JournalLink.kt` holds the journal's package and ride extra (they used to live in `RideLock`). The journal is never suspended by any lock (`LockSafety.neverBlocked`), so it stays reachable under an urge lock; that is the one place where an urge lock leaves more open than "alarm only", and it is deliberate.
- **Removed** (grep-checked in `app/src`, no matches): `RideLock`, `ImpulseLockScreen`, `ImpulseLockViewModel`, `ImpulseAction` and every impulse getter and setter in `SecurityManager`, `startImpulseBlock`, `ACTION_IMPULSE_END`, `armImpulseEnd`, `armRideEnd`, the `security_prefs` listener in the enforcer, `LockSafety.brickTargets`, the `rideLockMillis` argument of `removalBlocked`, `Pomodoro.rearm(relaunchBrick)` (the reboot relaunch is `LockEngine.reopenIfBrick`, and covers every brick).
- **Crash-loop breaker** now also ends an urge lock and records a punishment day as ended by the system (`punishmentAbortedAt`).
- **Pin**: `MainActivity` pins for any brick and passes `BrickStatus.ownerApps` plus the alarm apps to `prepareBrick`; the brick home alias is on for every brick.

Debug controls added (debug builds only, same rules as before): `... -a io.github.warleysr.dechainer.DEBUG_URGE_LOCK --ei minutes 1` (1 minute urge lock, through the real rule) and `... -a io.github.warleysr.dechainer.DEBUG_PUNISHMENT_DAY --ei minutes 5` (5 minute punishment day starting now). A release build does not contain the receiver, and the engine entry points return without doing anything unless `BuildConfig.DEBUG`.

Judgement calls to confirm (BLUEPRINT.md says nothing or is ambiguous):
- 5.3 "win over" is read as "name and unlock", not "let a focus-allowed app through a limit or schedule". The other reading would loosen a lock, so it is not built.
- D3 blocks SMS and Emergency Info in an urge lock. A real emergency needs calls, which stay open, but the owner should know.
- The journal's `IMPULSE_BLOCK`/`RIDE_LOCK` now mean the fixed ten-minute lock. "Pause my apps for 30 minutes" no longer exists.
- Aborting a punishment day (crash-loop breaker only) ends it for the rest of that day.

Testing, Phase 2: CI (run 104, head 97c6825) passed all 263 app tests (193 before; 70 new or reworked, 51 of them Robolectric), the debug and release builds and the release-manifest check. The scratch pure suite ran 174 tests (`RideLock` stub removed; `JournalLink` and `UrgeActions` added to its list). New tests: `LockModesTest` (every row of 5.2 and the 5.3 rules), `UrgeLockRuleTest`, `FocusStartRuleTest`, `LockStateStoreTest`, `SettingsFreezeTest` and `SettingsFreezeDeviceTest` (a write during a punishment day is refused for each writer), and engine-level cases in `LockEngineSyncTest` (urge lock suspends and is not extended, ends by time without an alarm, punishment day holds and blocks an urge lock, the crash breaker ends both). Nothing here has run on a device: the pin, the brick home screen, the lock screen text, the alarm ringing under the lock and the real `setPackagesSuspended` effects are the owner's checks (section 15), on a spare phone first (14A).


## Update: Déchaîner 2.0, Phase 3 "Urge flow and AI" (2026-10-01; see BLUEPRINT.md, sections 6.1, 6.2, 8)
Phase 3 only. Phase 4 (focus sessions) is not started.

What exists now (under `app/src/main/java/io/github/warleysr/dechainer/`):
- **Home and menu** (`screens/home/HomeScreen.kt`): a large serif clock and one Urge button; a tap on the clock or a swipe up opens the menu sheet (Urge, Focus, Schedules, Apps and limits, Settings). During a punishment day it is the same screen with the end time and a calm sentence, and no menu. Today and Reports are not in the menu: their phases are not built. `viewmodels/Route.kt` + pure `NavStack` replace the string routes. Home is outside the entry gate; the menu's screens stay behind `LockScreen` (its stopgap panic button is gone).
- **Urge flow** (`screens/urge/UrgeFlowHost.kt`, `viewmodels/UrgeViewModel.kt`, `urge/UrgeFlow.kt`): choice (Ongoing | I slipped), breathing (4 s in, 6 s out, ring, ten prompts, personal reason replaces the fifth), writing ("Not now" keeps a counted stub), 3 to 5 AI questions or the 3 fixed ones (20 s limit), streamed deep dive, crisis card, delete. The screen is derived from the stored entry and the time (`UrgeFlowRules.screenFor`), so a killed app resumes in place (`resumable`, 60 minute window).
- **Store**: schema version 2 adds `urge_entry` (`store/UrgeEntryRepository.kt`). Status moves only along `UrgeFlowRules.canMove`, inside the write's transaction. The deep dive is saved and `raw_text` set NULL in one transaction. Unreadable rows are skipped and never written.
- **Promises kept in `UrgeFlow`**: entry stored then lock started before anything else; a store that cannot be opened never stops the lock (in-memory entry, id < 0); the note is stored the moment it is submitted, before any question; a second tap never extends the lock or makes a second entry; an urge lock found with no entry (the journal's door) gets one.
- **AI** (`ai/`): `Provider`, `SecretBox` (Keystore alias `dechainer_ai_key`; the journal's key is NOT carried over, re-enter it), `AiSettings` (writes obey the settings freeze), `AiClient` (https only, `INSECURE_URL` for http, per-call limits, streaming), `AiPrompts` + `QuestionsParser` + `MarkdownReply` (the three calls: questions, deep dive, weekly report; the weekly report's input builder is Phase 6), `AiGate` (no key, no consent or no network means no call).
- **Retry** (`urge/DeepDiveScheduler.kt`): unique WorkManager job, network required, exponential back-off, queued only when waiting can help (offline, busy). With no key, no consent or an owner-only error it ends and is queued again when settings change or the app opens.
- **Settings screen** "Urge and AI" (Settings > App): reason, one person to call (never sent to the AI), provider, key, model, https address, consent, and the privacy statement.
- **Tile and shortcut**: `UrgeTileService` and `res/xml/shortcuts.xml` open `MainActivity` (now `singleTop`) with `urge_source`; the activity starts the ongoing path (replays from recents are ignored). `FocusScreen`'s journal launcher is now the in-app Urge button.
- Dependency added: `androidx.work:work-runtime-ktx` 2.10.1 (the one the blueprint allows).

Judgement calls to confirm: the personal reason is not sent to the AI (the blueprint does not list it); with no key the entry sits in PENDING_DEEPDIVE and the note stays stored until a key exists or the owner deletes it (6.2 as written, and the screen says so); the crisis card also appears when only the AI flags the note, and the fixed questions are still offered; Today and Reports are absent from the menu; `Rules.LOCK_AFTER_SLIP` (D10) exists and is false.

Testing: pure logic ran in a scratch JVM project (93+ tests, `AiClientTest`, `AiPromptsTest`, `AiParsersTest`, `AiCallsTest`, `DeepDiveRetryTest`, `UrgeFlowRulesTest`, `UrgeJsonTest`, `NavStackTest`). Robolectric on CI: `UrgeEntryRepositoryTest`, `UrgeFlowTest` (the Phase 3 acceptance checks: offline still locks at once, fixed questions, entry saved; with a key the deep dive is saved and the note is NULL; a failed call keeps the text; a broken store does not stop the lock), `UrgeSettingsFreezeTest`. `ResourceReferencesTest` checks every `R.string`/`R.array`/`R.drawable` exists. Not run on a device: the Compose screens, the breathing animation, the tile, the shortcut, the pin during breathing, and any real AI call.

Owner checks still open (section 15, spare phone first, 14A): 1, 5, 6, 7 from Phase 1; 5 (alarm rings while suspended) matters for the urge lock; 8 once Phase 5 exists. New for this phase: add a key under Settings > Urge and AI and check a real deep dive and that the note is gone; airplane mode, tap Urge > Ongoing: the phone locks at once and the fixed questions show; set a crisis contact and check Call and Text open the dialler and messages; add the Quick Settings tile and the icon shortcut; start an urge inside a focus block and check no second lock starts.
