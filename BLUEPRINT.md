# Déchaîner 2.0: Build Blueprint

Version 1.1 · 2026-10-01 · Owner: Umair · Reader: Claude Code (and the owner)

This file is the single source of truth for building Déchaîner 2.0. It merges the two existing apps in this repo (`app` = Déchaîner, `journal` = Urge Journal) into one app. Where this file and the code disagree, stop and report; do not guess.

---

## 0. How to use this file

Put this file at the repo root as `BLUEPRINT.md`. Add a `CLAUDE.md` containing one line: `Read BLUEPRINT.md before every task and follow its rules.`

Kickoff prompt for each session:

> Read BLUEPRINT.md completely. Do Phase N only. Before editing, list the files you will touch and every place where the code contradicts the blueprint. Commit in small steps. When the phase's acceptance checks pass, stop and report what you did, what you could not verify, and what the owner must test on the phone.

### Rules for Claude Code

1. **One phase per session** (section 14). Do not start the next phase.
2. **Never change** `applicationId` (`io.github.warleysr.dechainer`), the signing config, `minSdk` (30) or `targetSdk` (36). Device Owner follows the app id; changing it breaks the owner's phone.
3. **The compiler may be CI only.** The owner may have no Android SDK locally. Keep commits small, push often, and read the GitHub Actions result. Do not add annotation processors (no Room, no KSP, no kapt). Use plain SQLite.
4. **Pure logic first.** Every rule in this file that decides something (what is locked, which day is a punishment day, when a report is due) lives in a Kotlin function with no Android types, and has unit tests before any UI uses it.
5. **Truth comes from stored data and a trusted clock, never from an alarm firing.** Alarms are only wake-ups. Every wake-up (boot, unlock, app open, alarm, receiver) calls `LockEngine.sync()`, which recomputes everything from stored data.
6. **No new permissions** except those listed in section 9.4. `INTERNET` is allowed because the AI client lives in this app now.
7. **All user-visible text goes in `strings.xml`.** Copy rules are in section 12.
8. **Stop and ask the owner before:** deleting any stored-data migration that touches schedules, changing the meaning of a security setting, adding a permission, adding a dependency that is not listed here, or deleting anything marked "verify before deleting" when references remain.
9. **Never put secrets in the repo.** The AI key is sealed with the Android Keystore (existing `SecretBox` in `Ai.kt`; port it).
10. **Do not add:** analytics, crash reporting services, accounts, cloud sync, streak counters, confetti, badges or any gamification.
11. **Do not change the defaults D1 to D19 on your own.** If you think one is wrong, say so in your phase report and keep building to the written default.
12. **Read section 14A before installing any build on the owner's daily phone.** A bug in a brick can lock the phone for hours.

---

## 1. What we are building

One Android app that runs as Device Owner and does two jobs:

1. **Lock the phone when it is needed.** Scheduled focus sessions, a 10-minute urge lock, a punishment day, recurring app-block schedules and daily time limits, all on one lock engine.
2. **Show the owner the truth about himself.** It records urges, slips, focus check-ins and a daily checklist, and an AI turns that data into a deep dive after every urge and a weekly report that shows where he is heading.

Principles:

- **Calm and minimal.** Dark warm theme, serif headings, one clear action per screen. No clutter. Not cringey.
- **Tightening is easy; loosening is hard.** Anything that makes the lock stricter is one tap. Anything that weakens it needs the recovery code and the unlock delay.
- **The lock never waits on the network or the AI.**
- **Private by default.** Data stays on the phone. Only derived data goes to the AI the owner chose, and the raw urge text is deleted once its deep dive exists.
- **No shaming.** Locked-screen text, AI output and notifications are firm, plain and kind.

Non-goals: launcher (Blackout stays a separate project), cloud sync, multi-user, social features, iOS.

---

## 2. Defaults the blueprint author chose (owner may veto any)

The owner did not answer these, so the blueprint picks a default. Each is a constant or a setting in one place, so a veto is a one-line change.

| # | Topic | Default | Note |
|---|---|---|---|
| D1 | App name | "Déchaîner 2.0" shown as "Déchaîner" | App id unchanged |
| D2 | Quick Settings tile and icon shortcut | Both start the ongoing urge path with no question first | Section 6.2 |
| D3 | Allowed during urge lock | Incoming calls and dialer, alarm clock. Everything else blocked, including SMS and the Emergency Info app | `LockAllow.URGE` |
| D4 | Crisis card | Kept. Call or text one saved person. Stored on the phone, never sent to the AI | Section 6.2 |
| D5 | Personal reason | One line the owner writes on a calm day, shown as a breathing prompt | Section 6.2 |
| D6 | Goals per day | At least 3, at most 7. Carry-over of unfinished goals only on tap | `MIN_GOALS`, `MAX_GOALS` |
| D7 | Allowed on a punishment day | Incoming calls and dialer, alarm clock (a suspended clock app may not ring). Study-app allow-list exists but is empty and is protected by the recovery code | `LockAllow.PUNISHMENT` |
| D8 | Back-to-back guard | A punishment day never triggers another punishment day. Its own result is recorded only | Section 6.4 |
| D9 | Rest day | One declared rest day per rolling 7 days, chosen in the evening window | `MAX_REST_PER_WEEK = 1` |
| D10 | Lock after a slip | Off (a slip goes straight to writing). Setting exists | `LOCK_AFTER_SLIP = false` |
| D11 | Locked-screen text after "not ready" | "Okay. The phone stays locked until your focus time ends. Your call." | Owner's original wording is in section 12 |
| D12 | One timetable | Block schedules and scheduled focus sessions share one weekly timetable; each entry has a type | Section 6.3 |
| D13 | Pre-session prompt timeout | 2 minutes, then defaults to "special" (stricter) | Section 6.3 |
| D14 | Unanswered check-in | Recorded as UNANSWERED after 5 minutes; no consequence | Section 6.3 |
| D15 | Special session check-in | One yes/no at the end of the window, so the data point is not lost | Section 6.3 |
| D16 | Second evening reminder | 23:00, on the same notification channel | Section 10 |
| D17 | Auto-measured goals | Goal types MANUAL, FOCUS_MINUTES(n), NO_SLIP | Section 6.4 |
| D18 | Rest day | Skips scheduled FOCUS sessions. BLOCK schedules and daily limits still run. A rest day never cancels a punishment | Sections 5.3, 6.4 |
| D19 | Activation | Enforcement starts when setup finishes, not at the first plan | Section 6.4 |

---

## 3. Starting point

Repo: `app` (Déchaîner, package `io.github.warleysr.dechainer`) and `journal` (Urge Journal, package `io.github.warleysr.urgejournal`). Licence: Apache 2.0, same as the original project. Keep `LICENSE` and mark changed files.

Stack (from `gradle/libs.versions.toml`): Kotlin 2.4.10, AGP 8.13.2, Compose BOM 2025.12.01, Material 3, minSdk 30, targetSdk 36, Shizuku 13.1.5, Timber, JUnit4, Robolectric, Kotest, MockK, coroutines-test.

New dependency allowed: `androidx.work:work-runtime-ktx` (weekly report job and deep-dive retry). Nothing else without asking.

### Keep and reuse (read before changing)

- Lock core: `data/ScheduleEnforcer.kt`, `data/AppBlockEngine.kt`, `data/Blocker.kt`, `data/LockSafety.kt`, `data/TimeLimits.kt`, `data/UsageMath.kt`, `data/DnsGuard.kt`, `data/BrowserRestrictionsManager.kt`, `data/FullScreenAlerts.kt`, `data/DeviceAdmin.kt`, `data/DeviceOwnerRepository.kt`.
- Focus core: `focus/PomodoroCore.kt` (pure phases, already unit-tested), `focus/Pomodoro.kt` (state, alarms, notifications, committed blocks with `brickBlocks = true`, `setIntention`, `answer(id, done)`), `focus/PomodoroReceiver.kt`, `activities/PomodoroEndActivity.kt`.
- Security: `security/SecurityManager.kt`, `security/ForcedRemovalClock.kt`, `security/RecoveryCodeHash.kt`, `security/UnlockDelay.kt`, `screens/common/Recovery*.kt`, `UnlockDelayDialog.kt`, `screens/setup/*`.
- Theme: `ui/theme/*` (Color, Type, Motion, CalmCard, Theme).
- Receivers: `DechainerApplication.kt`, `DechainerDeviceAdminReceiver.kt`, `ScheduleReceiver.kt`.
- Journal code to port into `app`: `Ai.kt` (providers, SecretBox key sealing, consent, prompts, Safety), `Notifier.kt` (reminders), `RideTileService.kt` (becomes the Urge tile), `Model.kt` (read for shapes).

### Removal candidates (verify before deleting: grep for references, build, then delete)

- Door: `UrgeActions.kt`, `UrgeActionReceiver.kt`, `RideStatusProvider.kt`, and the journal's `Door`. One app means no door.
- `RideLock.kt` and the impulse-lock code (`ImpulseLockScreen.kt`, `ImpulseLockViewModel.kt`, impulse parts of `SecurityManager`): merged into the urge lock.
- `LegacyCleanup.kt`, `DechainerPolicyUpdateReceiver.kt`, `utils/LocaleUtils.kt`, the hidden-app migration in `Blocker.kt` (`migrateFromHiding`, `releaseAllHidden`) and the legacy impulse adoption in `ScheduleEnforcer.syncLocked`.
- `screens/challenges/ChallengeScreens.kt` (math or word challenge) and `activities/LockScreen.kt` (biometric gate and 5-minute relock). The recovery code gate stays; the biometric entry gate goes.
- Focus extras not wanted: subject tags, targets, lecture progress, daily lecture goal, the focus log chart and CSV export (`FocusLogScreen.kt` parts, tag and lecture code in `Pomodoro.kt`). Keep: intention (becomes "purpose"), the yes/no answer, session log rows.
- Whole journal module after porting: `Screens.kt`, `LogScreen.kt`, `Components.kt` (reuse pieces if useful), `Share.kt`, `Store.kt` (replaced by SQLite), `MainActivity.kt`, `Theme.kt`.
- Dead functions listed in the audit: `isBrowser`, `isTorrentApp`, `getPossibleTorrentApps`, `supportsRestrictions`, `isBlockTorrentsEnabled` and setter, `isHeldBySchedule`, `setActiveImpulseSuspension`, `DnsGuard.isActive`, `UsageMath.foregroundMillis`, `RecoveryCodeHash.isHash`.

---

## 4. Vocabulary

| Term | Meaning |
|---|---|
| Brick | The phone is pinned to this app as the HOME app, every other app is suspended except the mode's allow set, and the safety restrictions are on (existing mechanism behind `brickBlocks`). Quick Settings and incoming calls stay reachable as today. |
| Urge lock | A 10-minute brick started by the Urge flow. |
| Focus block | A committed brick over a time range, containing Pomodoro phases or a continuous special session. Same thing as a scheduled Pomodoro: a focus session in brick mode. |
| Punishment day | A whole local calendar day in brick mode, calls and alarm only. |
| Day | One local calendar day with its goals and result. |
| Plan | The goals written for a day (3 to 7). |
| Evening window | 20:00:00 to 23:59:59 local, trusted clock. |
| Trusted clock | `TrustedClock.now()`, section 9.2. |
| Tighten / loosen | Section 9.1. |

---

## 5. The lock engine

### 5.1 Principle

`LockEngine.plan(now, state): LockPlan` is a pure function. `LockEngine.sync(context)` reads state, calls `plan`, and applies the result through the existing `ScheduleEnforcer` and `Blocker` code. `sync` is idempotent and is called on every wake-up. Ownership bookkeeping (write ownership before suspending, release only what this app suspended) stays exactly as it is today.

### 5.2 Modes

| Mode | Starts | Length | Open while it runs | Can end early? |
|---|---|---|---|---|
| URGE_LOCK | Owner chooses "ongoing" in the Urge flow (the tile skips the question) | 10 minutes | This app, incoming calls and dialer, alarm clock | No |
| FOCUS_BLOCK | Owner picks an end time (max 8 hours), or a FOCUS timetable entry starts it | Until the end time | This app, incoming calls and dialer, alarm clock, Quick Settings, apps the owner allowed | No. The end time never moves |
| PUNISHMENT_DAY | 00:00 after a day that violated the checklist rules | Until 23:59:59 of that day | This app, incoming calls and dialer, alarm clock | No. Settings are frozen |
| SCHEDULE | A BLOCK timetable entry | The entry's window | Everything except the listed apps, or only the listed apps in allow-only mode | Only with the recovery code, unless the entry locks itself |
| DAILY_LIMIT | Minutes used per app | Until midnight | Everything else | Only by raising the limit with the recovery code |

### 5.3 Precedence

- Brick modes (URGE_LOCK, FOCUS_BLOCK, PUNISHMENT_DAY) win over SCHEDULE and DAILY_LIMIT.
- If several brick modes overlap, the allow set is the intersection of their allow sets, and the brick ends only when all of them end.
- An URGE flow started during a FOCUS_BLOCK or PUNISHMENT_DAY does not start a second lock. The breathing screen still runs for 10 minutes.
- A second tap during a running URGE_LOCK never extends it.
- A FOCUS timetable entry that starts during PUNISHMENT_DAY or REST day is skipped. One that starts during an URGE_LOCK starts when the lock ends if the entry's window is still open.

### 5.4 Self-healing (audit items R1 and R2, mandatory)

- **Brick expiry.** `plan` treats any brick whose end time is `<= now` as over. Do not rely on `Pomodoro.onPhaseAlarm` alone. Call `LockEngine.sync` from `MainActivity.onResume`, from the Focus tick and from every receiver. Arm a backup alarm at `endTime + 1s` through `ScheduleReceiver`, as `armImpulseEnd` does today.
- **Crash-loop breaker.** In `DechainerApplication`, install an uncaught-exception handler that counts crashes with a synchronous `commit()`. On the second crash within 5 minutes while any brick is active, run `abortBrick()`: clear the preferred home, remove brick restrictions, clear brick end times, release suspended apps. It guards against bugs only and is never reachable from the UI.
- **Force stop and Clear data.** At startup, when Device Owner, call `dpm.setUserControlDisabledPackages(admin, listOf(packageName))` (API 30). Keep `DISALLOW_APPS_CONTROL` in the system rules as a second layer.
- **Cold start diet.** Remove the eager `AppRepository.getApps()` from `DechainerApplication.onCreate`. Run any remaining one-off migration once behind a stored flag. One `sync` per wake-up.

### 5.5 Settings freeze

While PUNISHMENT_DAY is active, every settings write is refused at the repository layer (not only hidden in the UI). Reading is allowed. The Today screen (writing the plan, resolving goals), the Urge flow and Reports stay usable.

---

## 6. Flows

### 6.1 Home and navigation

- **Home:** a large serif clock and one button, "Urge". Nothing else.
- A tap on the clock or a swipe up opens the **menu** (bottom sheet): Urge, Today, Focus, Schedules, Apps and limits, Reports, Settings.
- During any brick the same home screen is shown, with the end time and the reason in small text under the clock ("Locked until 14:30"). Tabs, menu items that change settings, and sign-out-style controls are hidden. The Urge button stays.
- Navigation uses a small `Route` type (title, icon, isRoot) instead of strings repeated in `when` blocks.

### 6.2 Urge flow

Entry points: the home Urge button, the Quick Settings tile (`UrgeTileService`, ported from `RideTileService`), a static icon shortcut. The tile and shortcut skip the question and start the ongoing path.

```
Urge button -> choice: "Ongoing" | "I slipped"

Ongoing:
  1. create UrgeEntry(kind=URGE, status=LOCKED)          // counted even if never written
  2. start URGE_LOCK (10 minutes)                        // before any network call
  3. breathing screen for the full 10 minutes
  4. lock ends -> writing screen
  5. submit text -> questions (AI, or 3 fixed if offline / no key / error)
  6. answers -> deep dive (AI)
  7. save deep dive, then delete raw_text

I slipped:
  1. create UrgeEntry(kind=SLIP)
  2. no lock, no breathing (LOCK_AFTER_SLIP = false)
  3. writing screen -> questions -> deep dive -> delete raw_text
```

Rules:

- The lock starts the moment "ongoing" is chosen, before any network call.
- Breathing: a circle, inhale 4 s, exhale 6 s, a ring showing time left, one text prompt per minute (list below). No exit control. If the owner has set a personal reason (D5), it replaces prompt 5.
- Writing is allowed to be skipped ("Not now"): the entry stays as a counted stub with status SKIPPED.
- Questions come after the text is submitted: 3 to 5 AI questions (section 8), or the 3 fixed ones. If the AI call takes longer than 20 seconds, show the fixed questions.
- The deep dive is saved first; only then `raw_text` is set to NULL. If the AI fails, the text stays, status is PENDING_DEEPDIVE, and a WorkManager retry (network required) runs until it succeeds or the owner deletes the entry. Show the owner that the text is still stored.
- Crisis net: run `Safety.needsSupport` (existing, English keywords) on the text, and the prompts also tell the AI to answer in the care shape when a note shows risk in any language. On a hit show the support card: call or text one saved person, saved on the phone only, never sent to the AI.
- Inside a FOCUS_BLOCK or PUNISHMENT_DAY the breathing runs without starting a second lock.

Breathing prompts (one per minute, in order):

1. Let your shoulders drop. You only have to get through this minute.
2. Find where the urge sits in your body. Watch it, don't fight it.
3. Urges rise, peak and pass. This one is already changing.
4. Breathe out slower than you breathe in.
5. Remember why you set this lock up when you were thinking clearly. (Replaced by the personal reason if set.)
6. Name it quietly: this is an urge, not an instruction.
7. You don't have to decide anything for the next few minutes.
8. Notice what your hands are doing. Let them rest.
9. The strongest part is usually behind you.
10. Almost there. Stay with the breath until the lock ends.

Fixed questions (fallback, same for urge and slip):

1. What was happening just before the urge started?
2. How were you feeling, in a word or two?
3. What was the urge offering you: a way to put something off, to get away from a feeling, or something else? (Changed in 1.2; the time is already stored with the entry.)

### 6.3 Focus sessions (scheduled Pomodoro = focus session in brick mode)

**Timetable.** One weekly timetable (`BlockSchedule` gains a `type`): `BLOCK` (existing: block or allow-only listed apps) or `FOCUS` (a focus window: days, start, end). Migrate existing schedules to `BLOCK`. A FOCUS entry starts a FOCUS_BLOCK at its start time using the existing `Pomodoro.startBlock(context, endsAt)` path, and `rearm` restarts it after a reboot for the remaining window.

**Manual start.** Focus screen: pick an end time (min 10 minutes, max 8 hours), write what the session is for, choose usual or special. Starts a FOCUS_BLOCK.

**Before a scheduled session.** The brick starts on schedule regardless of any answer. A full-screen prompt on top of the brick asks:

- "Usual day or something special today?" (Usual / Special)
- "What is this session for?" (free text, minimum 3 characters, with today's goals as a pick list)

If there is no answer within 2 minutes the session runs as SPECIAL with an empty purpose (D13).

**Usual.** Pomodoro phases from `PomodoroCore` (FOCUS, SHORT_BREAK, LONG_BREAK, settings as today, `brickBlocks = true` so the phone stays pinned through breaks). At the end of each FOCUS phase: a chime and a full-screen check-in, "Did you do the work?" with Yes and No (also as notification actions).

- **Yes:** carry on as scheduled.
- **No:** start the **reset**, inside the block (the block's end time does not move; the Pomodoro phase clock pauses). The phone stays pinned the whole time.
  1. 5 minutes of meditation (reuse the breathing screen and prompts).
  2. 5 minutes of "Go outside or do something physical" with a quiet countdown.
  3. Prompt: "Are you ready now?" Yes / No.
     - **Yes:** resume the usual phases.
     - **No:** show a large prompt with the text in D11, then switch to **plain timer**: the remaining block time as a countdown, no breaks, no chimes, no check-ins, until the block ends.
- **Unanswered after 5 minutes:** record UNANSWERED, carry on as scheduled (D14).

**Special.** One continuous brick for the whole window, no alarms, no breaks, no mid-session check-ins. One yes/no at the end of the window (D15).

**During the block.** The block screen shows the time remaining and the purpose, plus the Urge button. When the block ends the engine releases the lock, the session is logged and the pending end-of-block yes/no is shown.

**Logging.** `focus_session` and `focus_checkin` rows (section 7). The state machine for usual sessions and the reset lives in a pure `FocusFlow` object with unit tests (every transition above, including timeouts).

### 6.4 Daily checklist and punishment day

**Plan and window.**

- Each evening, 20:00:00 to 23:59:59 local, the owner writes tomorrow's plan: 3 to 7 goals, each one line. "Carry over unfinished goals" pre-fills from today on tap only.
- In the same window the owner resolves today's goals. A goal is DONE or NOT_DONE. Manual goals may be ticked DONE at any time during the day; NOT_DONE can only be set in the window.
- The owner may declare tomorrow a REST day in the window (D9).
- At 23:59:59 tomorrow's plan locks (no edit, no delete). Today's goals stay resolvable only until 23:59:59.

**Goal types (D17).**

| Type | Resolution |
|---|---|
| MANUAL | The owner marks DONE or NOT_DONE |
| FOCUS_MINUTES(n) | Ticks DONE live when today's completed focus minutes reach n. At 23:59:59, if not reached, NOT_DONE by AUTO |
| NO_SLIP | At 23:59:59, DONE if there were zero SLIP entries today, else NOT_DONE by AUTO |

**Activation.** Enforcement starts when setup finishes (`activatedOn` = the local date onboarding was confirmed). It does not wait for a first plan, because that would let skipping the first plan switch the whole rule off. The first evening window after activation requires a plan for the next day; nothing is reviewed or punished before it, and there are no goals to review that day. If no plan is written in that first window, the next day is a punishment day. Onboarding explains the rules in plain words and requires one explicit confirmation.

**Evaluation (pure `DayEvaluator`).** For day D, at the first wake-up after 00:00 of D, evaluate day D-1 and the plan for D:

```
if D-1.kind == REST            -> no violation from D-1's results
if D-1.kind == PUNISHMENT      -> record D-1's result, never violate (back-to-back guard)
else violation if ANY of:
  - no plan for D with >= MIN_GOALS goals       (PLAN_MISSING)
  - any manual goal of D-1 unresolved at 23:59:59 (UNRESOLVED)
  - done_count * 2 < total_count                 (UNDER_HALF)
if violation                   -> D.kind = PUNISHMENT   // overrides a declared REST day
```

Rules:

- A missing plan still counts when D-1 was a REST day (the plan is always required). The exemption covers only D-1's results.
- A declared REST day waives only that day's own goals and review. It never cancels a punishment: if the rules above find a violation, D is a punishment day even if it was declared REST. This stops the rest day being used as a get-out card after a bad day.
- 50% or more done: nothing happens, scheduled focus sessions and schedules run as normal, and the owner is free outside them.
- A day with zero goals (for example the day before activation) can never be UNDER_HALF. Only PLAN_MISSING for the next day can apply.
- Evaluation is idempotent and stored (`day.evaluated`, `day.violation`). Re-running it on any wake-up gives the same answer.
- Punishment applies to the whole of D, never longer.

**Punishment day behaviour.** PUNISHMENT_DAY brick from 00:00 to 23:59:59. The home screen shows the date, the reason in one calm sentence, and the time left. Available: Urge flow, Today (so the owner can still write tomorrow's plan in the evening window), Reports (read only). Hidden or refused: Settings, schedule edits, any loosening.

**Editing and deletion guards.**

- Today's plan cannot be edited or deleted after 23:59:59 of the day before it.
- Deleting data never cancels, resets or shortens a punishment (punishment state lives in `app_state` and `day`, which are not deletable through the data-delete UI).
- Goal text older than the latest generated weekly report can be deleted. Goals in the open week cannot.

### 6.5 Weekly report

- **Anchor.** `weekAnchor` = start of the local day of the first data point (first urge or slip, first check-in, or first goal). Period k = `[anchor + 7k days, anchor + 7(k+1) days)`.
- **Trigger.** When a period ends, a WorkManager unique job `weekly-report-k` runs with a NETWORK_CONNECTED constraint and exponential backoff. Re-enqueue on boot and on app open when a period is due and has no report. Generate only if the period has at least one data point. A due report is built the next time the phone is on and online.
- **Inputs (derived only, never raw urge text):** per urge or slip: kind, time, answers, deep dive; per focus session: date, minutes, purpose, yes/no answers; per day: goals, resolution, result, day kind; the summaries of the last 4 weekly reports.
- **Output sections:** Week at a glance (numbers), Urge and slip chains (patterns in time, place, trigger, earliest link), Focus (yes rate, minutes, purposes), Progress (daily checklist results, a flag when goals look trivial or when ticks and focus minutes disagree), Where you are heading (this week against the last four), Follow-up on last week's advice, Next week (3 specific actions).
- **Delivery.** Save the report, then post the "Weekly report ready" notification that opens it in the app.

### 6.6 Delete and backup

- Delete: any single urge entry, focus session, deep dive or old report (rules in 6.4). "Wipe all data" needs the recovery code and still does not reset punishment, rest-day counters, `activatedOn` or `weekAnchor` state.
- Export: Settings writes the whole store to one JSON file the owner chooses (Storage Access Framework). Import reads it back. Uninstalling removes the store.

---

## 7. Data

SQLite via `SQLiteOpenHelper`, WAL on, schema versioned with explicit migrations, small repository classes, no codegen. All writes are transactions. A failed read must never overwrite stored data (keep the existing defensive behaviour: a row that fails to parse is kept untouched).

| Table | Columns |
|---|---|
| `urge_entry` | id, created_at, kind (URGE or SLIP), source (HOME, TILE, SHORTCUT, FOCUS), lock_started_at, lock_ended_at, status (LOCKED, WRITING, QUESTIONS, PENDING_DEEPDIVE, DONE, SKIPPED), raw_text NULL, questions_json NULL, answers_json NULL, deep_dive NULL |
| `focus_session` | id, source (MANUAL, SCHEDULED), flavor (USUAL, SPECIAL), purpose, started_at, planned_end_at, ended_at, focused_minutes, outcome (COMPLETED, PLAIN_TIMER, ENDED_EARLY_BY_SYSTEM) |
| `focus_checkin` | id, session_id, at, answer (YES, NO, UNANSWERED), reset_result (NONE, READY, NOT_READY) |
| `day` | date (YYYY-MM-DD, PK), kind (NORMAL, REST, PUNISHMENT), plan_written_at, resolved_at, done_count, total_count, evaluated, violation NULL |
| `goal` | id, day_date, position, text, type (MANUAL, FOCUS_MINUTES, NO_SLIP), target_minutes NULL, state (OPEN, DONE, NOT_DONE), resolved_by (USER, AUTO) |
| `weekly_report` | id, period_start, period_end, created_at, status, body_md, summary_json |
| `app_state` | key, value. Holds `weekAnchor`, `activatedOn`, `lastSeenWall`, boot-count checkpoints, rest-day usage, brick end times. Not deletable through data-delete |

Existing settings (`security_prefs` and others) stay in SharedPreferences, but replace the ~25 literal `getSharedPreferences("security_prefs")` calls in `SecurityManager` with one accessor and key constants.

Pomodoro's JSON session log (up to 5,000 sessions in one string) is replaced by `focus_session`. On first run after the update, import any existing log rows once, then stop writing the old log.

---

## 8. AI layer

Port from `Ai.kt`: providers, endpoint validation, `SecretBox` (Keystore sealing), per-provider consent, streaming and timeouts. Reject plain `http://` endpoints up front with a clear message. Network access is used only by this client.

Three calls, each with a pure function that builds the request and a pure function that parses the reply (unit tests for parsing, including malformed output):

1. **`generateQuestions(kind, text)`** returns JSON `{ "support": false, "questions": [ { "id", "type": "choice|text|scale", "prompt", "options"? } ] }` with 3 to 5 questions that refer to concrete details in the text, never generic ones. If the text shows risk in any language, return `{ "support": true }`.
2. **`deepDive(entry, answers)`** returns Markdown, at most 250 words: what happened as a chain, the earliest link, what helped or failed, at most two concrete changes for next time. A slip uses non-shaming wording. No diagnoses, no moralising, no clichés.
3. **`weeklyReport(inputs)`** returns Markdown with the sections in 6.5, and says plainly when self-reported data looks inconsistent.

Prompt rules for all three: the owner's text is data inside clear delimiters, never instructions; reply in the language the owner wrote in; keep the tone firm, plain and kind.

If there is no key, no network or an error: the fixed questions, status PENDING_DEEPDIVE, and the retry job. The urge is saved either way.

Privacy: only derived data goes to the AI (section 6.5). Raw urge text goes to the AI only for the questions and the deep dive, then is deleted locally. Checklist goal text is kept and sent with the weekly report. Say this once in the AI setup screen.

---

## 9. Security and anti-bypass

### 9.1 Tighten or loosen

| Instant, no code | Needs the recovery code, then the unlock delay |
|---|---|
| Start an urge lock or a focus block | Edit or delete a schedule or FOCUS entry |
| Add a BLOCK entry | Raise or remove a daily limit |
| Lower a daily limit | Change any security setting |
| Add an app to a block list | Add an app to an allow list (allow-only entries, punishment allow-list, focus allow-list) |
| Declare tomorrow a REST day (within the weekly limit) | Change MIN_GOALS, the 50% threshold, the evening window, the rest-day limit, mandatory mode, any `LockAllow` set |
| | Wipe all data, remove Device Owner |

Loosening a checklist rule is queued and takes effect from the start of the next week, so it can never be used to escape tonight's rules. A running urge lock, focus block or punishment day cannot be loosened by anything.

### 9.2 Trusted clock

The checklist window and day boundaries depend on time, so time must not be editable.

- `TrustedClock.now()` returns wall time but never goes backwards: store `lastSeenWall` on every wake-up; if wall time is earlier than `lastSeenWall - 2 min`, use `lastSeenWall + (elapsedRealtime delta since that wake-up)`. Use boot-count checkpoints as `ForcedRemovalClock` does.
- When Device Owner, keep date, time and time zone locked all the time, not only during blocks: `DISALLOW_CONFIG_DATE_TIME`, `setAutoTimeEnabled(admin, true)`, `setAutoTimeZoneEnabled(admin, true)`. Verify on the phone that this works.
- Day boundaries use the local time zone of the trusted clock.
- `DayEvaluator`, `WeekMath` and `LockEngine.plan` take `now` as a parameter and never read the system clock themselves.

### 9.3 Keep and harden

- Recovery code: salted PBKDF2 hash, shown once at setup. Tell the owner to give it to a trusted person and never keep it on the phone or in a notebook next to it.
- 4-day forced removal stays, with its boot-count clock.
- Private DNS adult-site filter stays (`DnsGuard`). System rules stay and must include: no factory reset, no safe boot, no USB debugging while bricked, no uninstall of this app, no VPN changes, no adding users or profiles, `DISALLOW_APPS_CONTROL`. The owner verifies these on the phone (section 15).
- Allow lists can only shrink without the recovery code.
- Remove unused device-admin policies from `device_admin_policies.xml` (wipe-data, reset-password, expire-password and others a Device Owner does not need).
- R8: keep rules for restriction labels looked up by name (`RestrictionsTab`, `SchedulesScreen`) stay protected by `keep.xml`. Add `assembleRelease` to `build.yml`. Pin third-party actions to a commit SHA, add `permissions: contents: read` to `build.yml`, replace the `sed` version stamping with `-PversionCode=${{ github.run_number }}`, delete the "Add feature patch" step and `make-key.yml`.
- Dependencies: after `LockScreen.kt` is removed, drop `androidx.biometric` and `biometric-compose` if nothing else uses them (they are on an alpha), and drop `appcompat` if nothing else needs it.

### 9.4 Permissions

Existing permissions stay. `INTERNET` and `ACCESS_NETWORK_STATE` stay because of the AI client. Allowed additions: none beyond `POST_NOTIFICATIONS` and `USE_FULL_SCREEN_INTENT` if not already declared. Usage access stays for daily limits.

---

## 10. Notifications

Three channels only. Everything else the two apps sent today is removed.

| Channel | When | Text |
|---|---|---|
| Weekly report | A report is saved | "Your weekly report is ready" (opens it) |
| Focus | End of a FOCUS phase in a USUAL session | "Focus done. Did you do the work?" with Yes and No actions. Silent for SPECIAL sessions |
| Evening checklist | 20:00 and 23:00 | 20:00: "Write tomorrow's goals and close today". 23:00 (only if still open): "One hour left to finish your checklist" |

Keep notification permission granted through Device Owner where possible (verify). During the evening window, if the checklist is not finished, show the checklist when the phone is unlocked until it is done.

---

## 11. Setup

First run walks the owner through, in order:

1. Remove accounts temporarily (Android only allows Device Owner with none signed in).
2. Grant Device Owner through Shizuku (steps shown in the app) or the one ADB command.
3. Add accounts back.
4. Allow notifications, and full-screen alerts on Android 14 and newer.
5. Grant Usage access (daily limits).
6. On Xiaomi, Redmi and POCO phones: Autostart on, battery set to no restrictions, app locked in recents.
7. Generate the recovery code, confirm it was saved elsewhere.
8. Optional: add an AI key. Without it, urges get the fixed questions and no deep dives.
9. Onboarding of the checklist rules in plain words, with one confirmation.

Afterwards one **Setup status** card in Settings lists each check with a tick or a Fix button. Reuse `screens/setup/*`.

---

## 12. Design and copy

- **Look:** warm dark background, serif headings, the ember accent, as in the current themes. Take colours and type from `ui/theme/*`; the journal's 14 duplicate colours go away. Soft fades between screens (`Motion.kt`). Eight-point spacing. One primary action per screen.
- **Home:** the clock is the largest element. The Urge button is quiet but unmistakable. Nothing else competes.
- **Voice:** plain words, short sentences, no exclamation marks, no emoji, no slogans, no streaks or scores shown as rewards. Firm and kind. Never shame a slip.
- **Accessibility:** every tappable icon has a content description (the audit counted 36 icons with none). Minimum 48 dp touch targets. Text scales with system font size. Contrast meets AA on the dark theme.
- **Locked-screen text after "not ready" (D11):** "Okay. The phone stays locked until your focus time ends. Your call." The owner's original was "Okay Buddy, it's your own choice to ruin your life. Good luck with it". The blueprint author recommends dropping "ruin your life" because shame after a slip tends to feed the next one. Keep the string in `strings.xml` so it is a one-line change.

---

## 13. Migrations

- Schedules: add `type = BLOCK` to every existing schedule.
- Pomodoro log: import existing sessions into `focus_session` once, then stop using the old store.
- Journal data is not migrated. The owner exports from the old journal first if he wants a copy.
- Install the new build over the old Déchaîner (same app id and key) so Device Owner carries over, then uninstall the journal app.

---

## 14. Build phases

Each phase ends with: unit tests green, CI green (debug build, release build, tests), and a short report. Do not start a phase until the owner says so.

### Phase 1: Safety foundation

- Section 5.4 (brick expiry, crash-loop breaker, Force stop and Clear data block, cold-start diet).
- `TrustedClock` and date, time and time zone lock (9.2).
- SQLite store skeleton with `app_state` and migrations, repository classes, tests with Robolectric.
- `LockEngine.plan` as a pure function over the existing modes (SCHEDULE, DAILY_LIMIT, FOCUS_BLOCK) with tests; `sync` wired into every wake-up path.
- CI: `assembleRelease`, tests, SHA-pinned actions, version stamping fix, remove `make-key.yml` and the patch step.
- Accept: a block whose alarm never fires still ends on the next wake-up (test); two crashes during a brick abort it (test); Force stop is disabled for the app on a Device Owner phone (owner verifies).

### Phase 2: One lock engine

- Add URGE_LOCK and PUNISHMENT_DAY modes, precedence and allow sets (`LockAllow`), settings freeze at repository level.
- Remove `RideLock`, impulse lock and the door pieces that become unused, with grep checks.
- Accept: `plan` tests cover every row of 5.2 and the precedence rules in 5.3; a settings write during PUNISHMENT_DAY is refused.

### Phase 3: Urge flow and AI

- Home screen, menu sheet, Urge choice, breathing, writing, questions, deep dive, delete text, crisis card, Urge tile and shortcut.
- Port `Ai.kt` and its tests; three calls with parsers; fixed-question fallback; WorkManager retry for PENDING_DEEPDIVE.
- Accept: with airplane mode on, an ongoing urge still locks at once, shows fixed questions and saves the entry; with a key, the deep dive is saved and `raw_text` becomes NULL; a failed AI call keeps the text.

### Phase 4: Focus sessions

- `BlockSchedule.type`, timetable UI with BLOCK and FOCUS entries, scheduled start and `rearm` after reboot.
- Pre-session prompt, purpose, `FocusFlow` (usual, special, check-ins, reset, plain timer) with full unit tests, `focus_session` and `focus_checkin` storage, one-time import of the old log.
- Accept: every transition in 6.3 has a test; a scheduled session starts with no interaction and runs as SPECIAL if the prompt is ignored.

### Phase 5: Daily checklist and punishment

- Today screen, goal types, auto-resolution, `DayEvaluator` with full tests (every branch in 6.4 including REST, back-to-back guard, activation, boundaries at 23:59:59 and 00:00), rest day, evening notifications, show-checklist-on-unlock.
- Accept: tests prove a punishment day never follows a punishment day, a missing plan punishes, 2 of 4 goals does not, 1 of 3 does, re-running evaluation changes nothing, declaring REST never cancels a punishment, and skipping the first plan does not switch enforcement off.

### Phase 6: Weekly report and data tools

- `WeekMath` with tests, WorkManager job, input builder, report generation, Reports screen with the progress line of daily results, delete rules, export and import.
- Accept: the anchor and period maths are tested around local midnight, month ends and a time zone change; a report is built after the phone was off at the due time; no raw urge text is ever in the request (test on the builder).

### Phase 7: Setup, cleanup, polish

- Setup guide and status card, notification channels reduced to three, all removals in section 3, journal module deleted, strings, accessibility labels, final look, release build smoke test.
- Accept: `grep` finds no references to removed code; the release APK installs over the old build, launches twice, starts and ends a 10-minute urge lock and a short focus block, and survives a process kill during a brick.

---

## 14A. Safe testing protocol (read before installing any build on the daily phone)

A bug in a brick can lock the phone for hours or a whole day, and Claude Code cannot run a phone. So:

1. **Test Phases 1 and 2 on an emulator or a spare Android phone first**, with no accounts signed in, using the same Device Owner command as the README. The owner's daily phone gets a build only after the checks below pass.
2. **Debug-only controls** (guarded by `BuildConfig.DEBUG`, absent from release builds, with a test or a manifest grep proving it): a broadcast that runs `abortBrick()`, and broadcasts that start a 1-minute URGE_LOCK, a 1-minute FOCUS_BLOCK and a 5-minute PUNISHMENT_DAY. USB debugging stays usable in debug builds so these can be sent with adb.
3. **Durations come from `Rules`.** A debug build may shorten them. A release build must ignore every override.
4. **Before the daily phone gets a new build:** the 4-day forced removal works on the test device, the recovery code is stored with a trusted person (not on the phone), and the owner knows how to boot the phone into recovery mode.
5. **Install order on the daily phone:** the Phase 1 build first (no new lock modes), used for a few days. Later phases only after the owner has run the section 15 checks that apply to them.

---

## 15. Owner checks on the phone (cannot be tested in CI)

Claude Code must list which of these are still unchecked at the end of every phase.

1. Settings, Apps, Déchaîner: Force stop and Clear data are disabled.
2. Boot into recovery mode: note what a data wipe asks for. Device Owner cannot block it on every phone.
3. Xiaomi Dual apps and Second space: confirm a cloned app does not escape suspension.
4. VPN settings and a browser's own secure DNS cannot bypass the Private DNS filter.
5. With the Clock app suspended, an alarm still rings. If not, add the clock to every allow set permanently.
6. Date, time and time zone cannot be changed.
7. Notification permission stays granted.
8. A punishment day survives a reboot and a process kill, and ends at midnight.
9. The weekly report arrives after the phone was off at the due time.
10. The recovery code is stored with a trusted person, not on the phone.

---

## 16. Definition of done

- All seven phases accepted by the owner.
- Unit tests cover every decision function: `LockEngine.plan`, `FocusFlow`, `DayEvaluator`, `WeekMath`, `TrustedClock`, AI request builders and parsers.
- CI runs unit tests, `assembleDebug` and `assembleRelease` on every push.
- The app has one module, one lock engine, one store, one design system, and three notification channels.

---

## 17A. Changes in 1.2 (owner's polish pass after Phase 7)

- **D4 vetoed by the owner:** the saved crisis contact is gone (setting, storage, tests). The crisis card keeps its text and gets one button that opens the phone app. `Safety.needsSupport` and the AI risk shape are unchanged.
- **App lock (new, section 9):** an optional PIN (4 to 12 digits, weak ones refused) or pattern (4 or more dots) that is separate from the phone's lock. Salted PBKDF2 hash, wrong tries counted with a synchronous write and slowed after the fourth (30 s, 1 min, 5 min, 15 min, 1 h), asked again after 30 s out of sight. Home, the Urge flow, the Quick Settings tile and a running brick are never behind it (principle: an urge can always be started at once). Changing it needs the current secret; removing it or "I forgot it" goes through the recovery code gate (loosening). All writes obey the settings freeze. This reverses the Phase 7 removal of the entry gate only for the screens behind Home, and only when the owner turns it on.
- **Deep dives stay readable (6.2, 6.6):** new Deep dives screen and entry screen (menu, Reports, Your data). They are read only and stay open during a punishment day and an urge lock.
- **AI prompts (section 8):** the questions follow five jobs (first link, the need, the pull, the turning point, the next step) and the deep dive has five sections (What happened, The earliest link, What helped and what didn't, Your plan, Hold on to this), still at most 250 words. Both prompts forbid invented details and guessing missing answers. The requests now also carry the time of day and up to four short "earliest link" lines from earlier deep dives of the last three weeks (derived lines the AI wrote, never a raw note); the weekly report is unchanged. The owner's reason is still never sent: it is shown under the deep dive on the phone. The third fixed question now asks what the urge was offering.
- **Questions limit:** the AI questions now get 45 seconds (was 20; reasoning models and the longer prompt tripped it and the app silently showed the standard questions) and the questions screen says whether they were written from the note or why the standard ones are shown.
- **Audit pass (owner: "my last edit"):**
  - **The daily evaluation was never called.** `DayEngine.runPass` existed but no pass called it, so no punishment day could start and the 20:00 and 23:00 reminders were never armed. It now runs in every lock pass. So that days that were never enforced are not punished after the fact, the first pass on a phone records `dayEngineLiveFrom` (today) and enforcement counts from the later of that and `activatedOn`.
  - **Manual goals tick only in the evening window (changes 6.4):** DONE and NOT_DONE both only from 20:00:00 to 23:59:59 of the goal's own day, enforced in `DayRepository.markByOwner`; writing the plan and declaring or withdrawing a rest day are enforced in the repository too. Measured goals (focus minutes, no slips) still close by themselves.
  - **Recovery code:** wrong tries are counted and slowed like the app lock (free four, then 30 s, 1 min, 5 min, 15 min, 1 h), and the right code is refused during a wait.
  - **Trusted clock:** a forward jump of the wall clock that the running time cannot explain is ignored, but only when automatic time is off (with it on, a jump is a network correction and is followed) and only within one boot.
  - **Urge start (changes D2):** MainActivity is exported, so a bare extra from any app could start the lock. The Quick Settings tile now sends a one-time in-memory token; the icon shortcut cannot carry a secret and opens the urge choice (Ongoing or I slipped) instead of locking at once.
  - Also: a model reply cut off at its length cap fails instead of being saved half written; stale half-finished urge entries are settled at start; the recovery-session countdown redraws again; Today can write focus-minute and no-slip goals.
- **Today:** Save plan gives feedback (saved, what is missing, window closed).
- **Second AI pass (owner: "not deep enough", and the reply came in Russian):** the reply language is now a setting (Urge and AI, default English, empty means follow the note) and is named in every request, because "the language the person wrote in" made a Russian note produce a Russian deep dive. The deep dive has six sections (What happened, The earliest link, What the urge was really after, What was on your side, Your plan, Hold on to this) and **at most 400 words** (section 8 said 250; owner decision). It may offer one hedged guess about the need underneath, backed by two facts, and may only call something a pattern if a listed earlier entry shows it. The requests also carry today's checklist goals (text and state) so the plan can shrink a real task; goal text already goes to the weekly report. The questions are five: the moment before, the permission sentence, the need, the pull (urge) or the after-feeling (slip), the next-step rule. The personal reason is still not sent.

---

## 17. Changes in 1.1

- Activation now happens when setup finishes. Before, skipping the first plan would have switched the checklist rule off for good (D19).
- A declared rest day can no longer cancel a punishment. Before, declaring tomorrow a rest day at 23:58 after a bad day would have dodged the punishment (D18).
- Added the safe testing protocol (14A): spare device first, debug-only abort and short-duration controls, install order for the daily phone.
- Added rules 11 and 12 for Claude Code.
