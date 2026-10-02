package io.github.warleysr.dechainer.focus

import io.github.warleysr.dechainer.lock.LockStateStore
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import io.github.warleysr.dechainer.BuildConfig
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.activities.MainActivity
import io.github.warleysr.dechainer.activities.PomodoroEndActivity
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.store.StoredSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * The Pomodoro timer: focus, then a break (a long one every few sessions), with an alarm at the
 * end of each. Every finished focus session goes into a dated log, with your answer to
 * "Did you do the work?".
 *
 * Battery: nothing runs while the timer does. One exact alarm wakes the phone at the end of the
 * phase, and the countdown in the notification is drawn by Android itself (a chronometer), so the
 * notification is posted once per phase, not once per second.
 */
object Pomodoro {
    const val ACTION_PHASE_END = "io.github.warleysr.dechainer.POMODORO_PHASE_END"
    const val ACTION_ANSWER = "io.github.warleysr.dechainer.POMODORO_ANSWER"
    /** Starts whatever phase is waiting (the break, or the next focus session). */
    const val ACTION_START_NEXT = "io.github.warleysr.dechainer.POMODORO_START_NEXT"
    const val ACTION_STOP_ALARM = "io.github.warleysr.dechainer.POMODORO_STOP_ALARM"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_DONE = "done"

    private const val PREFS = "pomodoro"
    private const val K_PHASE = "phase"
    private const val K_ENDS_AT = "ends_at"
    private const val K_PAUSED = "paused_remaining"
    private const val K_STARTED = "phase_started_at"
    private const val K_CYCLE = "focus_done_in_cycle"
    private const val K_PLANNED = "planned_minutes"
    private const val K_BLOCK = "block_ends_at"
    private const val K_FOCUS = "focus_minutes"
    private const val K_SHORT = "short_minutes"
    private const val K_LONG = "long_minutes"
    private const val K_EVERY = "long_every"
    private const val K_AUTO_FOCUS = "auto_start_focus"
    private const val K_AUTO_BREAK = "auto_start_breaks"
    private const val K_LOG = "log"
    private const val K_PENDING = "pending_question"
    private const val K_INTENTION = "intention"
    private const val K_LOCK = "lock_apps"
    private const val K_BRICK = "brick_blocks"
    private const val K_ALLOWED = "allowed_apps"

    /** Enough for years of daily use; the oldest go first. */
    private const val LOG_LIMIT = 5000

    /** The one Focus channel (blueprint 10). The quiet timer and the questions without sound are silenced per notification. */
    private const val CHANNEL_FOCUS = "focus_v3"
    private val OLD_CHANNELS = listOf("focus_timer", "focus_alarm_v1", "focus_chime_v1", "focus_chime_v2")
    private const val ID_TIMER = 8101
    private const val ID_ALARM = 8102
    private const val RC_ALARM = 21
    private const val RC_OPEN = 22
    private const val RC_YES = 23
    private const val RC_NO = 24
    private const val RC_START = 25
    private const val RC_STOP = 27
    private const val RC_QUESTION = 26
    private const val ID_FLOW = 8103
    private const val RC_FLOW_OPEN = 31
    private const val RC_FLOW_YES = 32
    private const val RC_FLOW_NO = 33

    private val lock = Any()
    @Volatile private var loaded = false

    /** Remembered from the first call that brings one, so the trusted clock can be read without the global Application. */
    @Volatile private var appContext: Context? = null

    private val _state = MutableStateFlow(PomodoroState())
    private val _settings = MutableStateFlow(PomodoroSettings())
    private val _log = MutableStateFlow<List<FocusSession>>(emptyList())
    private val _pending = MutableStateFlow<Long?>(null)
    private val _intention = MutableStateFlow("")
    private val _allowed = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<PomodoroState> = _state.asStateFlow()
    val settings: StateFlow<PomodoroSettings> = _settings.asStateFlow()
    val log: StateFlow<List<FocusSession>> = _log.asStateFlow()
    /** The finished focus session still waiting for "Did you do the work?", if any. */
    val pendingQuestion: StateFlow<Long?> = _pending.asStateFlow()
    /** What the next (or current) focus session is for, in your words. Cleared once it's logged. */
    val intention: StateFlow<String> = _intention.asStateFlow()
    /** Apps that keep working during a locked focus session. */
    val allowedApps: StateFlow<Set<String>> = _allowed.asStateFlow()

    /**
     * True while a focus block holds the phone: from its start until its end time. The end time is
     * read against the trusted clock, so a block whose end alarm never fired is already over here
     * (blueprint 5.4, R1); [closeExpiredBlock] then tidies the stored state.
     */
    fun brickActive(): Boolean = _state.value.blockActiveAt(now())

    /**
     * The old "lock apps during a session" option: a focus session outside a block, running or
     * paused, that holds the phone. Pausing keeps the lock (so a pause can't be used to scroll);
     * breaks and idle release it.
     */
    fun sessionLockActive(): Boolean {
        val s = _state.value
        return _settings.value.lockApps && !s.inBlock && s.phase == Phase.FOCUS && !s.isIdle
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads the saved state once per process. Cheap to call again. */
    fun ensureLoaded(context: Context) {
        appContext = context.applicationContext
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val p = prefs(context)
            _settings.value = PomodoroSettings(
                focusMinutes = p.getInt(K_FOCUS, 25),
                shortBreakMinutes = p.getInt(K_SHORT, 5),
                longBreakMinutes = p.getInt(K_LONG, 15),
                longBreakEvery = p.getInt(K_EVERY, 4),
                autoStartBreaks = p.getBoolean(K_AUTO_BREAK, false),
                autoStartFocus = p.getBoolean(K_AUTO_FOCUS, false),
                lockApps = p.getBoolean(K_LOCK, false),
                brickBlocks = p.getBoolean(K_BRICK, true)
            ).clamped()
            _allowed.value = p.getStringSet(K_ALLOWED, emptySet())?.toSet() ?: emptySet()
            _state.value = PomodoroState(
                phase = runCatching { Phase.valueOf(p.getString(K_PHASE, null) ?: "") }.getOrDefault(Phase.FOCUS),
                endsAt = p.getLong(K_ENDS_AT, 0L),
                pausedRemaining = p.getLong(K_PAUSED, 0L),
                phaseStartedAt = p.getLong(K_STARTED, 0L),
                focusDoneInCycle = p.getInt(K_CYCLE, 0),
                plannedMinutes = p.getInt(K_PLANNED, 0),
                blockEndsAt = p.getLong(K_BLOCK, 0L)
            )
            _log.value = loadLog(context, p)
            _pending.value = p.getLong(K_PENDING, 0L).takeIf { it > 0L }
            _intention.value = p.getString(K_INTENTION, "") ?: ""
            loaded = true
        }
    }

    // ---- What the screen calls ----

    fun start(context: Context) {
        dismissAlarm(context)
        change(context) { current ->
            PomodoroCore.start(current, now(), _settings.value).let {
                if (it.inBlock && it.endsAt > it.blockEndsAt) it.copy(endsAt = it.blockEndsAt) else it
            }
        }
    }

    /**
     * Commits a focus block until [endsAt]: sessions and breaks run by themselves until then,
     * apps lock during each session, and leaving early takes the recovery code. False if the
     * timer is busy or there isn't room for even one session.
     */
    fun startBlock(context: Context, endsAt: Long, phases: Boolean = true): Boolean {
        ensureLoaded(context)
        val left = endsAt - now()
        // Blueprint 5.2: a block is at least ten minutes and at most eight hours.
        if (left < Rules.FOCUS_BLOCK_MIN_MS || left > Rules.FOCUS_BLOCK_MAX_MS) return false
        val total = (left / 60_000L).toInt()
        val first = BlockPlanner.plan(total, _settings.value).firstOrNull() ?: return false
        var started = false
        // The idle check is made inside the change, under the lock: a second request that arrives
        // a moment later sees the block the first one started and leaves it alone.
        change(context) { current ->
            val next =
                if (phases) PomodoroCore.beginBlockIfIdle(current, endsAt, now(), first.second)
                else PomodoroCore.beginContinuousBlockIfIdle(current, endsAt)
            started = next != current
            next
        }
        if (started) dismissAlarm(context)
        return started
    }

    /**
     * The usual phases begin in a block that has been running as one stretch (a scheduled block whose
     * prompt was answered "usual"): the first session now, sized to what is left. Nothing happens if
     * there is no such block, or too little of it left for a session (it then stays one stretch).
     */
    fun startPhases(context: Context) {
        ensureLoaded(context)
        change(context) { current ->
            val left = ((current.blockEndsAt - now()) / 60_000L).toInt()
            val first = BlockPlanner.plan(left, _settings.value, current.focusDoneInCycle).firstOrNull()
                ?: return@change current
            PomodoroCore.startPhasesInBlock(current, now(), first.second)
        }
    }

    /** The phases end and the block runs on to its end as one stretch (the plain timer). */
    fun dropPhases(context: Context) = change(context) { PomodoroCore.dropPhasesInBlock(it) }

    fun pause(context: Context) = change(context) { PomodoroCore.pause(it, now()) }

    /** Ends the timer. Never a focus block: once committed, only its end time ends it. */
    fun stop(context: Context) = change(context) { if (it.inBlock) it else PomodoroCore.stop(it) }

    /** Ends the current phase early. A skipped focus session is not counted or logged. */
    fun skip(context: Context) = change(context) { if (it.inBlock) it else nextState(it, completed = false) }

    /** The state after [current]'s phase ends: the block's plan while in one, the usual cycle otherwise. */
    private fun nextState(current: PomodoroState, completed: Boolean): PomodoroState {
        if (!current.inBlock) return PomodoroCore.advance(current, now(), _settings.value, completed)
        val doneAfter = when {
            current.phase == Phase.FOCUS && completed -> current.focusDoneInCycle + 1
            current.phase == Phase.LONG_BREAK -> 0
            else -> current.focusDoneInCycle
        }
        // Measured from the real clock, not the plan: if the phone was off across a phase edge,
        // the steps you missed are dropped rather than replayed (no catch-up focus after a gap),
        // so a block can end before its end time after a long absence. Deliberate.
        val remaining = ((current.blockEndsAt - now()) / 60_000L).toInt()
        val step = BlockPlanner.next(current.phase, doneAfter, remaining, _settings.value)
            ?: return when {
                // The plan is used up but the block's end has not come: it runs on as one stretch. The end
                // time never moves, and the phone is not released early (blueprint 5.2).
                now() < current.blockEndsAt - 1_000L ->
                    PomodoroState(phase = Phase.FOCUS, focusDoneInCycle = doneAfter, blockEndsAt = current.blockEndsAt)
                else -> PomodoroState(phase = Phase.FOCUS, focusDoneInCycle = doneAfter)   // block complete
            }
        return PomodoroCore.startFor(
            PomodoroState(phase = step.first, focusDoneInCycle = doneAfter, blockEndsAt = current.blockEndsAt),
            now(), step.second
        )
    }

    fun updateSettings(context: Context, new: PomodoroSettings) {
        ensureLoaded(context)
        val s = new.clamped()
        synchronized(lock) {
            _settings.value = s
            prefs(context).edit {
                putInt(K_FOCUS, s.focusMinutes)
                putInt(K_SHORT, s.shortBreakMinutes)
                putInt(K_LONG, s.longBreakMinutes)
                putInt(K_EVERY, s.longBreakEvery)
                putBoolean(K_AUTO_FOCUS, s.autoStartFocus)
                putBoolean(K_AUTO_BREAK, s.autoStartBreaks)
                putBoolean(K_LOCK, s.lockApps)
                putBoolean(K_BRICK, s.brickBlocks)
            }
        }
        LockEngine.requestSync(context.applicationContext)
    }

    fun toggleAllowedApp(context: Context, pkg: String) {
        ensureLoaded(context)
        synchronized(lock) {
            _allowed.value = if (pkg in _allowed.value) _allowed.value - pkg else _allowed.value + pkg
            prefs(context).edit { putStringSet(K_ALLOWED, _allowed.value) }
        }
        LockEngine.requestSync(context.applicationContext)
    }

    /** Your answer to "Did you do the work?" for session [id]. */
    fun answer(context: Context, id: Long, done: Boolean) {
        ensureLoaded(context)
        val ctx = context.applicationContext
        recordAnswer(ctx, id, done)
        synchronized(lock) {
            if (_pending.value == id) _pending.value = null
            prefs(ctx).edit(commit = true) {
                if (_pending.value == null) remove(K_PENDING)
            }
        }
        notificationManager(ctx).cancel(ID_ALARM)
    }

    /** Stores the yes or no for session [id] as a check-in row and brings the log up to date. */
    private fun recordAnswer(ctx: Context, id: Long, yes: Boolean) {
        try {
            Store.focus(ctx).addCheckin(id, now(), if (yes) CheckinAnswer.YES else CheckinAnswer.NO, ResetResult.NONE)
        } catch (e: Exception) {
            Timber.e(e, "Answer not stored")
        }
        reloadLog(ctx)
    }

    /**
     * Reads the log again from the store. A store that cannot be read leaves the log as it is: the
     * store is only ever written to one session at a time, so nothing is lost by this.
     */
    internal fun reloadLog(ctx: Context) {
        try {
            val fresh = Store.focus(ctx).log().takeLast(LOG_LIMIT)
            synchronized(lock) { _log.value = fresh }
        } catch (e: Exception) {
            Timber.w(e, "Focus log not reloaded")
        }
    }

    /** The log from the store. The first time, the old log in the preferences is imported (blueprint 13) and left where it is, unwritten from then on. */
    private fun loadLog(ctx: Context, p: android.content.SharedPreferences): List<FocusSession> = try {
        val repo = Store.focus(ctx)
        Store.appState(ctx).runOnce("focus_log_import") {
            repo.importLegacy(FocusLogMath.decode(p.getString(K_LOG, null)))
            true
        }
        repo.log().takeLast(LOG_LIMIT)
    } catch (e: Exception) {
        Timber.e(e, "Focus log not readable; showing none for now")
        emptyList()
    }

    /** A session that ran outside a flow (a plain Pomodoro phase) goes in the store as one finished row. */
    private fun storeFinished(ctx: Context, entry: FocusSession) {
        try {
            Store.focus(ctx).insert(
                StoredSession(
                    id = entry.id, source = FocusSource.MANUAL, flavor = Flavor.USUAL, purpose = entry.intention.orEmpty(),
                    startedAt = entry.id, plannedEndAt = entry.id + entry.minutes * 60_000L,
                    endedAt = entry.id + entry.minutes * 60_000L, focusedMinutes = entry.minutes, outcome = SessionOutcome.COMPLETED
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Finished session not stored")
        }
    }

    fun setIntention(context: Context, text: String) {
        ensureLoaded(context)
        synchronized(lock) {
            _intention.value = text.take(80)
            prefs(context).edit { putString(K_INTENTION, _intention.value) }
        }
    }

    /** Removes one session from the log for good (a test run, a mistaken start). */
    fun deleteSession(context: Context, id: Long) {
        ensureLoaded(context)
        val ctx = context.applicationContext
        try {
            Store.focus(ctx).delete(id)
        } catch (e: Exception) {
            Timber.e(e, "Session not deleted")
            return
        }
        synchronized(lock) {
            _log.value = _log.value.filterNot { it.id == id }
            if (_pending.value == id) _pending.value = null
            prefs(ctx).edit(commit = true) {
                if (_pending.value == null) remove(K_PENDING)
            }
        }
    }

    fun session(id: Long): FocusSession? = _log.value.firstOrNull { it.id == id }

    /**
     * Opens Android's own settings for the alarm, where the sound (any ringtone or your own
     * file), vibration and volume behaviour can be changed. Android keeps a channel's sound under
     * the user's control, so this is the reliable way to change it.
     */
    fun openAlarmSoundSettings(context: Context) {
        ensureChannels(context.applicationContext)
        val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_FOCUS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Some phones lack the per-channel screen: fall back to the app's notification page.
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
        }
    }

    /** Clears the ringing notification (after Start is tapped on it). */
    fun dismissAlarm(context: Context) = notificationManager(context.applicationContext).cancel(ID_ALARM)

    // ---- What the alarm and the system call ----

    /**
     * The phase's time is up: log it, ring, and move on to the next phase. [silent] skips the
     * chime and the question screen (the question stays pending for the Focus tab), for a block
     * found over long after its end. [requestSync] is false when the caller is the lock engine
     * itself and is already in the middle of a pass.
     */
    fun onPhaseAlarm(context: Context, silent: Boolean = false, requestSync: Boolean = true) {
        ensureLoaded(context)
        val ctx = context.applicationContext
        // Outside every lock: the flow's own state is read inside the change below.
        FocusRunner.ensureLoaded(ctx)
        val finished: PomodoroState
        synchronized(lock) {
            finished = _state.value
            val blockOver = finished.inBlock && now() >= finished.blockEndsAt - 1_000L
            // A stale alarm (the timer was paused, stopped or skipped since) does nothing.
            if (!blockOver && (!finished.isRunning || finished.endsAt > now() + 1_000L)) return
        }
        var loggedId: Long? = null
        var flowedMinutes: Int? = null
        var stored: FocusSession? = null
        change(ctx, requestSync) { current ->
            if (current != finished) return@change current
            // A block that ran out while paused (or running as one stretch) just ends: nothing was finished.
            if (current.inBlock && !current.isRunning) {
                return@change PomodoroState(phase = Phase.FOCUS, focusDoneInCycle = current.focusDoneInCycle)
            }
            if (current.phase == Phase.FOCUS) {
                // The length planned at Start, not end minus start (that would count any pause as
                // focus) and not today's setting (which could have changed since). Older saved
                // sessions from before this was recorded fall back to the setting.
                val minutes = current.plannedMinutes.takeIf { it > 0 } ?: _settings.value.focusMinutes
                if (current.inBlock && FocusRunner.hasFlow()) {
                    // A block with a flow (blueprint 6.3): the session is the block's, and the flow asks the
                    // question and logs the answer. Nothing goes in the log per phase.
                    flowedMinutes = minutes
                } else {
                    val entry = FocusSession(
                        id = current.phaseStartedAt.takeIf { it > 0 } ?: now(),
                        minutes = minutes,
                        intention = FocusLogMath.cleanIntention(_intention.value)
                    )
                    // A fresh intention for the next session.
                    _intention.value = ""
                    _log.value = (_log.value + entry).takeLast(LOG_LIMIT)
                    _pending.value = entry.id
                    loggedId = entry.id
                    stored = entry
                    prefs(ctx).edit(commit = true) {
                        putLong(K_PENDING, entry.id)
                        remove(K_INTENTION)
                    }
                }
            }
            nextState(current, completed = true)
        }
        stored?.let { storeFinished(ctx, it) }
        val next = _state.value
        flowedMinutes?.let { FocusRunner.onFocusPhaseEnded(ctx, it) }
        if (silent) {
            Unit
        } else if (finished.inBlock && finished.isIdle) {
            // A block that ran as one stretch (a special day, or the plain timer): no chimes, no questions.
            Unit
        } else if (finished.inBlock && flowedMinutes != null) {
            // The flow rings the check-in itself, with its own buttons.
            Unit
        } else if (finished.inBlock) {
            // Inside a block nothing waits for you: a soft chime, and the question on the
            // notification to answer when convenient. No full-screen interruption.
            chime(ctx, finished, next, loggedId)
        } else if (finished.phase == Phase.FOCUS) {
            ringFocusDone(ctx, loggedId, next)
            showQuestion(ctx, loggedId)
        } else {
            ringBreakDone(ctx, next)
        }
    }

    /**
     * Closes a focus block whose end time has passed although nothing closed it: the alarm never
     * fired (blueprint 5.4, R1). It takes the alarm's own path, so the finished session is logged
     * and its question left pending, but quietly when the end is long past. True if a block was closed.
     */
    fun closeExpiredBlock(context: Context, atMs: Long = now()): Boolean {
        ensureLoaded(context)
        val stale = synchronized(lock) { _state.value }
        if (!stale.blockExpiredAt(atMs)) return false
        onPhaseAlarm(context, silent = atMs - stale.blockEndsAt > Rules.LATE_BLOCK_END_MS, requestSync = false)
        // The alarm path ends a block it finds over; this makes sure one it could not step is closed too.
        change(context, requestSync = false) { if (it.blockExpiredAt(atMs)) PomodoroCore.stop(it) else it }
        return true
    }

    /**
     * Debug builds only (blueprint 14A): a block of [minutes] that skips the usual 10 minute
     * minimum, for testing a brick on an emulator. Refuses in a release build.
     */
    fun debugStartBlock(context: Context, minutes: Int): Boolean {
        if (!BuildConfig.DEBUG) return false
        ensureLoaded(context)
        val endsAt = now() + minutes * 60_000L
        var started = false
        change(context) { current ->
            val next = PomodoroCore.beginBlockIfIdle(current, endsAt, now(), minutes)
            started = next != current
            next
        }
        if (started) dismissAlarm(context)
        return started
    }

    /** Debug builds only: forget the alarm that ends the phase, as if it had been lost. */
    fun debugCancelAlarm(context: Context) {
        if (BuildConfig.DEBUG) cancelAlarm(context.applicationContext)
    }

    /** The end of the block as stored on disk, or 0. Read straight from the preferences: the crash-loop breaker uses it. */
    fun storedBlockEndsAt(context: Context): Long = prefs(context).getLong(K_BLOCK, 0L)

    /**
     * Drops a running block without any of its end-of-block steps: for the crash-loop breaker and the
     * debug abort (blueprint 5.4). Never throws, and takes no lock: it may run inside an
     * uncaught-exception handler, where waiting on another thread could hang the process.
     */
    fun abortBlock(context: Context) {
        val ctx = context.applicationContext
        try {
            prefs(ctx).edit(commit = true) {
                putString(K_PHASE, Phase.FOCUS.name)
                putLong(K_ENDS_AT, 0L)
                putLong(K_PAUSED, 0L)
                putLong(K_STARTED, 0L)
                putInt(K_PLANNED, 0)
                putLong(K_BLOCK, 0L)
            }
        } catch (e: Throwable) {
            Timber.e(e, "Block state not cleared on disk")
        }
        if (loaded) _state.value = PomodoroCore.stop(_state.value)
        try {
            cancelAlarm(ctx)
            notificationManager(ctx).cancel(ID_TIMER)
            notificationManager(ctx).cancel(ID_ALARM)
        } catch (e: Throwable) {
            Timber.w(e, "Block alarm and notifications not cleared")
        }
    }

    /** After a reboot or an app update: the alarm is gone, so put it back — or catch up if it passed. */
    fun rearm(context: Context) {
        ensureLoaded(context)
        val s = _state.value
        when {
            s.isRunning && s.endsAt <= now() -> onPhaseAlarm(context)
            s.isRunning -> { armAlarm(context, s.endsAt); showTimer(context, s) }
            s.isPaused && s.inBlock && s.blockEndsAt <= now() -> onPhaseAlarm(context)
            s.isPaused && s.inBlock -> { armAlarm(context, s.blockEndsAt); showTimer(context, s) }
            s.isPaused -> showTimer(context, s)
            else -> Unit
        }
    }

    // ---- Plumbing ----

    /** The trusted clock (blueprint 9.2): every stored time here is on it. */
    private fun now(): Long = appContext?.let { TrustedClock.now(it) } ?: TrustedClock.now()

    /** For tests: forget what was loaded, so the next call reads the preferences again. */
    internal fun resetForTests() {
        synchronized(lock) { loaded = false }
        FocusRunner.resetForTests()
    }

    private inline fun change(context: Context, requestSync: Boolean = true, transform: (PomodoroState) -> PomodoroState) {
        ensureLoaded(context)
        val ctx = context.applicationContext
        val new: PomodoroState
        synchronized(lock) {
            new = transform(_state.value)
            if (new == _state.value) return
            _state.value = new
            prefs(ctx).edit(commit = true) {
                putString(K_PHASE, new.phase.name)
                putLong(K_ENDS_AT, new.endsAt)
                putLong(K_PAUSED, new.pausedRemaining)
                putLong(K_STARTED, new.phaseStartedAt)
                putInt(K_CYCLE, new.focusDoneInCycle)
                putInt(K_PLANNED, new.plannedMinutes)
                putLong(K_BLOCK, new.blockEndsAt)
            }
        }
        when {
            new.isRunning -> armAlarm(ctx, new.endsAt)
            new.inBlock -> armAlarm(ctx, new.blockEndsAt)   // paused inside a block
            else -> cancelAlarm(ctx)
        }
        // Locking or releasing apps is the lock engine's job; tell it something changed.
        if (requestSync) LockEngine.requestSync(ctx)
        if (new.isIdle) notificationManager(ctx).cancel(ID_TIMER) else showTimer(ctx, new)
    }

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, RC_ALARM,
        Intent(ctx, PomodoroReceiver::class.java).setAction(ACTION_PHASE_END),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openAppIntent(ctx: Context) = PendingIntent.getActivity(
        ctx, RC_OPEN,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * An alarm-clock alarm: exact to the second and allowed through Doze, which a timer needs —
     * the ordinary "allow while idle" kind can be held back for minutes when the phone has been
     * still for a while, and a 5-minute break would end late.
     */
    private fun armAlarm(ctx: Context, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(ctx)
        // Stored times are on the trusted clock; alarms fire on wall time. They differ only while the wall clock is behind.
        val wallAt = TrustedClock.toWall(at, ctx)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(wallAt, openAppIntent(ctx)), pi)
        } catch (e: SecurityException) {
            Timber.w(e, "Exact alarm refused; falling back")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wallAt, pi)
        }
    }

    private fun cancelAlarm(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx))
    }

    private fun notificationManager(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels(ctx: Context) {
        val nm = notificationManager(ctx)
        // The three channels the app used to have for focus are folded into one.
        OLD_CHANNELS.forEach { if (nm.getNotificationChannel(it) != null) nm.deleteNotificationChannel(it) }
        if (nm.getNotificationChannel(CHANNEL_FOCUS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_FOCUS, ctx.getString(R.string.focus_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.focus_channel_desc)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 250, 400)
                }
            )
        }
    }

    private fun phaseLabel(ctx: Context, phase: Phase) = ctx.getString(
        when (phase) {
            Phase.FOCUS -> R.string.focus_phase_focus
            Phase.SHORT_BREAK -> R.string.focus_phase_short
            Phase.LONG_BREAK -> R.string.focus_phase_long
        }
    )

    /** The quiet, ongoing notification. Posted once per phase; Android counts the seconds down. */
    private fun showTimer(ctx: Context, s: PomodoroState) {
        try {
            ensureChannels(ctx)
            val b = NotificationCompat.Builder(ctx, CHANNEL_FOCUS)
                .setSmallIcon(R.drawable.ic_notification_time_warning)
                .setContentTitle(phaseLabel(ctx, s.phase))
                .setContentIntent(openAppIntent(ctx))
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (s.isRunning) {
                b.setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true).setWhen(TrustedClock.toWall(s.endsAt, ctx))
            } else {
                val left = s.pausedRemaining / 1000
                b.setContentText(ctx.getString(R.string.focus_paused_left, "%d:%02d".format(left / 60, left % 60)))
                    .setShowWhen(false)
            }
            notificationManager(ctx).notify(ID_TIMER, b.build())
        } catch (e: Exception) {
            Timber.w(e, "Timer notification not shown")
        }
    }

    private fun broadcast(ctx: Context, code: Int, action: String) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, PomodoroReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Rings until stopped: a button, an answer, starting the next phase, or swiping it away. */
    private fun ring(ctx: Context, b: NotificationCompat.Builder) {
        val n = b.build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        notificationManager(ctx).notify(ID_ALARM, n)
    }

    /** The question's buttons on a notification. */
    private fun addQuestionActions(ctx: Context, b: NotificationCompat.Builder, id: Long) {
        b.addAction(0, ctx.getString(R.string.focus_answer_yes), answerIntent(ctx, id, true))
        b.addAction(0, ctx.getString(R.string.focus_answer_no), answerIntent(ctx, id, false))
    }

    private fun answerIntent(ctx: Context, id: Long, done: Boolean) = PendingIntent.getBroadcast(
        ctx, if (done) RC_YES else RC_NO,
        Intent(ctx, PomodoroReceiver::class.java).setAction(ACTION_ANSWER)
            .putExtra(EXTRA_SESSION_ID, id).putExtra(EXTRA_DONE, done),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun questionIntent(ctx: Context, id: Long?) = PendingIntent.getActivity(
        ctx, RC_QUESTION,
        Intent(ctx, PomodoroEndActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_SESSION_ID, id ?: 0L),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * Between phases of a block: rings on the alarm stream for 5 seconds (noticeable with
     * headphones on another device), with the question when a session ended.
     */
    private fun chime(ctx: Context, finished: PomodoroState, next: PomodoroState, id: Long?) {
        try {
            val nm = notificationManager(ctx)
            val title = when {
                !next.inBlock -> ctx.getString(R.string.focus_block_done)
                next.phase == Phase.FOCUS -> ctx.getString(R.string.focus_block_next_focus, next.plannedMinutes)
                else -> ctx.getString(R.string.focus_block_next_break, phaseLabel(ctx, next.phase).lowercase(), next.plannedMinutes)
            }
            val b = NotificationCompat.Builder(ctx, CHANNEL_FOCUS)
                .setSmallIcon(R.drawable.ic_notification_time_warning)
                .setContentTitle(title)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // Stops by itself after 5 s: the block runs on, nothing to dismiss.
                .setTimeoutAfter(5_000L)
                .setContentIntent(openAppIntent(ctx))
            if (finished.phase == Phase.FOCUS && id != null) {
                b.setContentText(ctx.getString(R.string.focus_question))
                addQuestionActions(ctx, b, id)
            }
            // Repeats until the timeout, a tap or a swipe.
            val n = b.build()
            n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
            nm.notify(ID_ALARM, n)
        } catch (e: Exception) {
            Timber.w(e, "Block chime not shown")
        }
    }

    /** Focus is over: ring, and ask the question right on the notification too. */
    private fun ringFocusDone(ctx: Context, id: Long?, next: PomodoroState) {
        try {
            ensureChannels(ctx)
            val breakText = ctx.getString(
                if (next.isRunning) R.string.focus_done_break_started else R.string.focus_done_break_ready,
                phaseLabel(ctx, next.phase).lowercase(),
                _settings.value.minutesFor(next.phase)
            )
            val b = NotificationCompat.Builder(ctx, CHANNEL_FOCUS)
                .setSmallIcon(R.drawable.ic_notification_time_warning)
                .setContentTitle(ctx.getString(R.string.focus_done_title))
                .setContentText(breakText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(breakText))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(questionIntent(ctx, id))
            // Android 14+ can refuse the full-screen alert. Without it the notification and its
            // Yes/No buttons still work; Settings shows a one-time hint about the permission.
            if (io.github.warleysr.dechainer.data.FullScreenAlerts.isAllowed(ctx)) {
                b.setFullScreenIntent(questionIntent(ctx, id), true)
            }
            if (id != null) addQuestionActions(ctx, b, id)
            b.addAction(0, ctx.getString(R.string.focus_stop_alarm), broadcast(ctx, RC_STOP, ACTION_STOP_ALARM))
            ring(ctx, b)
        } catch (e: Exception) {
            Timber.w(e, "Focus alarm not shown")
        }
    }

    private fun ringBreakDone(ctx: Context, next: PomodoroState) {
        try {
            ensureChannels(ctx)
            val b = NotificationCompat.Builder(ctx, CHANNEL_FOCUS)
                .setSmallIcon(R.drawable.ic_notification_time_warning)
                .setContentTitle(ctx.getString(R.string.focus_break_over_title))
                .setContentText(
                    ctx.getString(if (next.isRunning) R.string.focus_break_over_started else R.string.focus_break_over_ready)
                )
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(openAppIntent(ctx))
            if (!next.isRunning) {
                b.addAction(0, ctx.getString(R.string.focus_start_focus), broadcast(ctx, RC_START, ACTION_START_NEXT))
            }
            b.addAction(0, ctx.getString(R.string.focus_stop_alarm), broadcast(ctx, RC_STOP, ACTION_STOP_ALARM))
            ring(ctx, b)
        } catch (e: Exception) {
            Timber.w(e, "Break alarm not shown")
        }
    }

    // ---- What the focus flow asks (blueprint 6.3, 10) ----

    private fun flowOpenIntent(ctx: Context) = PendingIntent.getActivity(
        ctx, RC_FLOW_OPEN,
        Intent(ctx, io.github.warleysr.dechainer.activities.FocusFlowActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun flowAnswerIntent(ctx: Context, yes: Boolean) = PendingIntent.getBroadcast(
        ctx, if (yes) RC_FLOW_YES else RC_FLOW_NO,
        Intent(ctx, PomodoroReceiver::class.java).setAction(FocusRunner.ACTION_ANSWER).putExtra(EXTRA_DONE, yes),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * A question from the flow: the prompt, a check-in or the final yes/no. [loud] chimes once (the
     * Focus channel); otherwise it is silent, as a special session's last question is (blueprint 10).
     * It stays until answered or until the flow cancels it ([cancelFlowQuestion]); the full-screen
     * activity is started by the caller, and [fullScreen] only lets the notification carry it too.
     */
    internal fun postFlowQuestion(ctx: Context, title: String, text: String?, yesNo: Boolean, loud: Boolean, fullScreen: Boolean) {
        try {
            ensureChannels(ctx)
            val b = NotificationCompat.Builder(ctx, CHANNEL_FOCUS)
                .setSmallIcon(R.drawable.ic_notification_time_warning)
                .setContentTitle(title)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setContentIntent(flowOpenIntent(ctx))
            if (loud) b.setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            else b.setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
            if (text != null) b.setContentText(text)
            if (fullScreen && io.github.warleysr.dechainer.data.FullScreenAlerts.isAllowed(ctx)) {
                b.setFullScreenIntent(flowOpenIntent(ctx), true)
            }
            if (yesNo) {
                b.addAction(0, ctx.getString(R.string.focus_answer_yes), flowAnswerIntent(ctx, true))
                b.addAction(0, ctx.getString(R.string.focus_answer_no), flowAnswerIntent(ctx, false))
            }
            notificationManager(ctx).notify(ID_FLOW, b.build())
        } catch (e: Exception) {
            Timber.w(e, "Flow question not shown")
        }
    }

    internal fun cancelFlowQuestion(ctx: Context) {
        try {
            notificationManager(ctx).cancel(ID_FLOW)
        } catch (e: Exception) {
            Timber.w(e, "Flow question not cleared")
        }
    }

    /** Opens the full-screen flow page over everything, as the old question screen is. Falls back to the notification. */
    internal fun showFlowScreen(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(ctx, io.github.warleysr.dechainer.activities.FocusFlowActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
            Timber.w(e, "Flow screen not opened; the notification asks instead")
        }
    }

    /**
     * Brings up the full-screen question. Device Owner apps may open screens from the background;
     * if the system refuses anyway, the notification above still carries the Yes/No buttons, and
     * the Focus tab asks the next time you open the app.
     */
    private fun showQuestion(ctx: Context, id: Long?) {
        if (id == null) return
        try {
            ctx.startActivity(
                Intent(ctx, PomodoroEndActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_SESSION_ID, id)
            )
        } catch (e: Exception) {
            Timber.w(e, "Question screen not opened; the notification asks instead")
        }
    }
}
