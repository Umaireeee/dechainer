package io.github.warleysr.dechainer.focus

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
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.activities.MainActivity
import io.github.warleysr.dechainer.activities.PomodoroEndActivity
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
    const val ACTION_LECTURE = "io.github.warleysr.dechainer.POMODORO_LECTURE"
    const val EXTRA_LECTURE = "lecture_answer"
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
    private const val K_TAGS = "tags"          // newline-separated, in the order you added them
    private const val K_TAG = "current_tag"
    private const val K_INTENTION = "intention"
    private const val K_GOAL = "daily_goal"
    private const val K_TARGETS = "subject_targets"
    private const val K_LOCK = "lock_apps"
    private const val K_LECTURE_MIN = "lecture_minutes"
    private const val K_BRICK = "brick_blocks"
    private const val K_PROGRESS = "lecture_progress"
    private const val K_LECTURE_ASK = "lecture_ask"
    private const val K_ALLOWED = "allowed_apps"
    private const val TAG_LIMIT = 12

    /** Enough for years of daily use; the oldest go first. */
    private const val LOG_LIMIT = 5000

    private const val CHANNEL_TIMER = "focus_timer"
    private const val CHANNEL_ALARM = "focus_alarm_v1"
    private const val ID_TIMER = 8101
    private const val ID_ALARM = 8102
    private const val RC_ALARM = 21
    private const val RC_OPEN = 22
    private const val RC_YES = 23
    private const val RC_NO = 24
    private const val RC_START = 25
    private const val RC_STOP = 27
    private const val RC_QUESTION = 26

    private val lock = Any()
    @Volatile private var loaded = false

    private val _state = MutableStateFlow(PomodoroState())
    private val _settings = MutableStateFlow(PomodoroSettings())
    private val _log = MutableStateFlow<List<FocusSession>>(emptyList())
    private val _pending = MutableStateFlow<Long?>(null)
    private val _tags = MutableStateFlow<List<String>>(emptyList())
    private val _tag = MutableStateFlow<String?>(null)
    private val _intention = MutableStateFlow("")
    private val _targets = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _allowed = MutableStateFlow<Set<String>>(emptySet())
    private val _progress = MutableStateFlow<Map<String, LectureProgress>>(emptyMap())
    private val _lectureAsk = MutableStateFlow<Long?>(null)

    val state: StateFlow<PomodoroState> = _state.asStateFlow()
    val settings: StateFlow<PomodoroSettings> = _settings.asStateFlow()
    val log: StateFlow<List<FocusSession>> = _log.asStateFlow()
    /** The finished focus session still waiting for "Did you do the work?", if any. */
    val pendingQuestion: StateFlow<Long?> = _pending.asStateFlow()
    /** Your subjects, e.g. FAR, Tax. */
    val tags: StateFlow<List<String>> = _tags.asStateFlow()
    /** The subject the next finished session is logged under, or null for none. */
    val currentTag: StateFlow<String?> = _tag.asStateFlow()
    /** What the next (or current) focus session is for, in your words. Cleared once it's logged. */
    val intention: StateFlow<String> = _intention.asStateFlow()
    /** Sessions a day you aim for per subject, e.g. FAR → 2. Subjects without one aren't listed. */
    val targets: StateFlow<Map<String, Int>> = _targets.asStateFlow()
    /** Apps that keep working during a locked focus session. */
    val allowedApps: StateFlow<Set<String>> = _allowed.asStateFlow()
    /** Each subject's current lecture ("" for no subject). */
    val lectureProgress: StateFlow<Map<String, LectureProgress>> = _progress.asStateFlow()
    /** The finished session whose question is "How's the lecture?" rather than "Did you do the work?". */
    val lectureAsk: StateFlow<Long?> = _lectureAsk.asStateFlow()

    /**
     * True while a focus session holds the phone: lock on, in focus, running or paused. Pausing
     * keeps the lock (so a pause can't be used to scroll); breaks and idle release it.
     */
    fun focusLockActive(): Boolean {
        val s = _state.value
        return brickActive() || ((_settings.value.lockApps || s.inBlock) && s.phase == Phase.FOCUS && !s.isIdle)
    }

    /** True for the whole of a focus block, sessions and breaks alike: every block bricks the phone. */
    fun brickActive(): Boolean = _state.value.inBlock

    /**
     * True while the clock must stay put: during a locked session, and for a whole focus block
     * (breaks included), since moving the time would end either early.
     */
    fun holdsClock(): Boolean = focusLockActive() || _state.value.inBlock

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads the saved state once per process. Cheap to call again. */
    fun ensureLoaded(context: Context) {
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
                dailyGoal = p.getInt(K_GOAL, 0),
                lockApps = p.getBoolean(K_LOCK, false),
                lectureMinutes = p.getInt(K_LECTURE_MIN, 90),
                brickBlocks = p.getBoolean(K_BRICK, true)
            ).clamped()
            _progress.value = LectureMath.decode(p.getString(K_PROGRESS, null))
            _lectureAsk.value = p.getLong(K_LECTURE_ASK, 0L).takeIf { it > 0L }
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
            _log.value = FocusLogMath.decode(p.getString(K_LOG, null))
            _pending.value = p.getLong(K_PENDING, 0L).takeIf { it > 0L }
            _tags.value = (p.getString(K_TAGS, null) ?: "").split('\n').mapNotNull { FocusLogMath.cleanTag(it) }.distinct()
            _tag.value = p.getString(K_TAG, null)?.takeIf { it in _tags.value }
            _intention.value = p.getString(K_INTENTION, "") ?: ""
            _targets.value = FocusLogMath.decodeTargets(p.getString(K_TARGETS, null)).filterKeys { it in _tags.value }
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
    fun startBlock(context: Context, endsAt: Long): Boolean {
        ensureLoaded(context)
        val total = ((endsAt - now()) / 60_000L).toInt()
        val first = BlockPlanner.plan(total, _settings.value).firstOrNull() ?: return false
        var started = false
        // The idle check is made inside the change, under the lock: a second request that arrives
        // a moment later sees the block the first one started and leaves it alone.
        change(context) { current ->
            val next = PomodoroCore.beginBlockIfIdle(current, endsAt, now(), first.second)
            started = next != current
            next
        }
        if (started) dismissAlarm(context)
        return started
    }

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
            ?: return PomodoroState(phase = Phase.FOCUS, focusDoneInCycle = doneAfter)   // block complete
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
                putInt(K_GOAL, s.dailyGoal)
                putBoolean(K_LOCK, s.lockApps)
                putInt(K_LECTURE_MIN, s.lectureMinutes)
                putBoolean(K_BRICK, s.brickBlocks)
            }
        }
        io.github.warleysr.dechainer.data.ScheduleEnforcer.requestSyncAll(context.applicationContext)
    }

    fun toggleAllowedApp(context: Context, pkg: String) {
        ensureLoaded(context)
        synchronized(lock) {
            _allowed.value = if (pkg in _allowed.value) _allowed.value - pkg else _allowed.value + pkg
            prefs(context).edit { putStringSet(K_ALLOWED, _allowed.value) }
        }
        io.github.warleysr.dechainer.data.ScheduleEnforcer.requestSyncAll(context.applicationContext)
    }

    /** Your answer to "Did you do the work?" for session [id]. */
    fun answer(context: Context, id: Long, done: Boolean) {
        ensureLoaded(context)
        val ctx = context.applicationContext
        synchronized(lock) {
            _log.value = _log.value.map { if (it.id == id) it.copy(done = done) else it }
            if (_pending.value == id) _pending.value = null
            if (_lectureAsk.value == id) {
                _lectureAsk.value = null
                prefs(ctx).edit { remove(K_LECTURE_ASK) }
            }
            prefs(ctx).edit(commit = true) {
                putString(K_LOG, FocusLogMath.encode(_log.value))
                if (_pending.value == null) remove(K_PENDING)
            }
        }
        notificationManager(ctx).cancel(ID_ALARM)
    }

    // ---- Subjects ----

    fun addTag(context: Context, raw: String) {
        ensureLoaded(context)
        val tag = FocusLogMath.cleanTag(raw) ?: return
        synchronized(lock) {
            if (_tags.value.any { it.equals(tag, ignoreCase = true) } || _tags.value.size >= TAG_LIMIT) return
            _tags.value = _tags.value + tag
            _tag.value = tag
            saveTags(context)
        }
    }

    /** Removes a subject from the picker. Sessions already logged under it keep it. */
    fun removeTag(context: Context, tag: String) {
        ensureLoaded(context)
        synchronized(lock) {
            _tags.value = _tags.value - tag
            _targets.value = _targets.value - tag
            if (_tag.value == tag) _tag.value = null
            saveTags(context)
        }
    }

    fun selectTag(context: Context, tag: String?) {
        ensureLoaded(context)
        synchronized(lock) {
            _tag.value = tag?.takeIf { it in _tags.value }
            saveTags(context)
        }
    }

    fun setTarget(context: Context, tag: String, sessions: Int) {
        ensureLoaded(context)
        synchronized(lock) {
            if (tag !in _tags.value) return
            val n = sessions.coerceIn(0, 10)
            _targets.value = if (n == 0) _targets.value - tag else _targets.value + (tag to n)
            saveTags(context)
        }
    }

    private fun saveTags(context: Context) = prefs(context).edit {
        putString(K_TAGS, _tags.value.joinToString("\n"))
        putString(K_TARGETS, FocusLogMath.encodeTargets(_targets.value))
        if (_tag.value == null) remove(K_TAG) else putString(K_TAG, _tag.value)
    }

    fun setIntention(context: Context, text: String) {
        ensureLoaded(context)
        synchronized(lock) {
            _intention.value = text.take(80)
            prefs(context).edit { putString(K_INTENTION, _intention.value) }
        }
    }

    // ---- Backup ----

    /** Writes the whole log to [uri] as CSV. True if it worked. */
    fun exportTo(context: Context, uri: android.net.Uri): Boolean {
        ensureLoaded(context)
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(FocusLogMath.toCsv(_log.value).toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) {
            Timber.w(e, "Export failed")
            false
        }
    }

    /**
     * Adds the sessions in the CSV at [uri] that aren't in the log yet, and any subjects they use.
     * Nothing already in the log is changed. Returns how many were added, or -1 if unreadable.
     */
    fun importFrom(context: Context, uri: android.net.Uri): Int {
        ensureLoaded(context)
        val incoming = try {
            context.contentResolver.openInputStream(uri)?.use { FocusLogMath.fromCsv(it.readBytes().toString(Charsets.UTF_8)) }
                ?: return -1
        } catch (e: Exception) {
            Timber.w(e, "Import failed")
            return -1
        }
        synchronized(lock) {
            val before = _log.value.size
            _log.value = FocusLogMath.merge(_log.value, incoming).takeLast(LOG_LIMIT)
            val newTags = incoming.mapNotNull { it.tag }.distinct().filter { t -> _tags.value.none { it.equals(t, true) } }
            _tags.value = (_tags.value + newTags).take(TAG_LIMIT)
            prefs(context).edit(commit = true) { putString(K_LOG, FocusLogMath.encode(_log.value)) }
            saveTags(context)
            return (_log.value.size - before).coerceAtLeast(0)
        }
    }

    /**
     * The answer to "How's the lecture?" for session [id]. It also answers "Did you do the work?":
     * done and in progress mean yes, procrastinating means no. Done counts one lecture.
     */
    fun answerLecture(context: Context, id: Long, answer: LectureAnswer) {
        ensureLoaded(context)
        val ctx = context.applicationContext
        synchronized(lock) {
            val session = _log.value.firstOrNull { it.id == id } ?: return
            val key = session.tag ?: ""
            _log.value = _log.value.map {
                if (it.id != id) it
                else it.copy(
                    done = answer != LectureAnswer.PROCRASTINATING,
                    lectures = if (answer == LectureAnswer.DONE) 1 else it.lectures
                )
            }
            val updated = LectureMath.answer(_progress.value[key] ?: LectureProgress(), answer, session.minutes)
            _progress.value = _progress.value + (key to updated)
            if (_pending.value == id) _pending.value = null
            if (_lectureAsk.value == id) _lectureAsk.value = null
            prefs(ctx).edit(commit = true) {
                putString(K_LOG, FocusLogMath.encode(_log.value))
                putString(K_PROGRESS, LectureMath.encode(_progress.value))
                if (_pending.value == null) remove(K_PENDING)
                remove(K_LECTURE_ASK)
            }
        }
        notificationManager(ctx).cancel(ID_ALARM)
    }

    /** How many lectures session [id] finished. Settable any time from the log. */
    fun setLectures(context: Context, id: Long, lectures: Int) {
        ensureLoaded(context)
        synchronized(lock) {
            _log.value = _log.value.map { if (it.id == id) it.copy(lectures = lectures.coerceIn(0, 20)) else it }
            prefs(context).edit(commit = true) { putString(K_LOG, FocusLogMath.encode(_log.value)) }
        }
    }

    /** Removes one session from the log for good (a test run, a mistaken start). */
    fun deleteSession(context: Context, id: Long) {
        ensureLoaded(context)
        synchronized(lock) {
            _log.value = _log.value.filterNot { it.id == id }
            if (_pending.value == id) _pending.value = null
            if (_lectureAsk.value == id) _lectureAsk.value = null
            prefs(context).edit(commit = true) {
                putString(K_LOG, FocusLogMath.encode(_log.value))
                if (_pending.value == null) remove(K_PENDING)
                if (_lectureAsk.value == null) remove(K_LECTURE_ASK)
            }
        }
    }

    fun session(id: Long): FocusSession? = _log.value.firstOrNull { it.id == id }

    /**
     * Opens Android's own settings for the alarm, where the sound (any ringtone or your own
     * file), vibration and volume behaviour can be changed. Android keeps a channel's sound under
     * the user's control, so this is the reliable way to change it.
     */
    fun openAlarmSoundSettings(context: Context, chime: Boolean = false) {
        ensureChannels(context.applicationContext)
        if (chime) ensureChimeChannel(context.applicationContext)
        val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, if (chime) CHANNEL_CHIME else CHANNEL_ALARM)
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

    /** The phase's time is up: log it, ring, and move on to the next phase. */
    fun onPhaseAlarm(context: Context) {
        ensureLoaded(context)
        val ctx = context.applicationContext
        val finished: PomodoroState
        synchronized(lock) {
            finished = _state.value
            val blockOver = finished.inBlock && now() >= finished.blockEndsAt - 1_000L
            // A stale alarm (the timer was paused, stopped or skipped since) does nothing.
            if (!blockOver && (!finished.isRunning || finished.endsAt > now() + 1_000L)) return
        }
        var loggedId: Long? = null
        change(ctx) { current ->
            if (current != finished) return@change current
            // A block that ran out while paused just ends: the paused session wasn't finished.
            if (current.inBlock && !current.isRunning) {
                return@change PomodoroState(phase = Phase.FOCUS, focusDoneInCycle = current.focusDoneInCycle)
            }
            if (current.phase == Phase.FOCUS) {
                // The length planned at Start, not end minus start (that would count any pause as
                // focus) and not today's setting (which could have changed since). Older saved
                // sessions from before this was recorded fall back to the setting.
                val minutes = current.plannedMinutes.takeIf { it > 0 } ?: _settings.value.focusMinutes
                val entry = FocusSession(
                    id = current.phaseStartedAt.takeIf { it > 0 } ?: now(),
                    minutes = minutes,
                    tag = _tag.value,
                    intention = FocusLogMath.cleanIntention(_intention.value)
                )
                // A fresh intention for the next session.
                _intention.value = ""
                _log.value = (_log.value + entry).takeLast(LOG_LIMIT)
                _pending.value = entry.id
                loggedId = entry.id
                val key = entry.tag ?: ""
                val progressed = LectureMath.afterSession(_progress.value[key] ?: LectureProgress(), entry.minutes)
                _progress.value = _progress.value + (key to progressed)
                _lectureAsk.value = if (LectureMath.shouldAsk(progressed, _settings.value.lectureMinutes)) entry.id else null
                prefs(ctx).edit(commit = true) {
                    putString(K_LOG, FocusLogMath.encode(_log.value))
                    putLong(K_PENDING, entry.id)
                    putString(K_PROGRESS, LectureMath.encode(_progress.value))
                    if (_lectureAsk.value != null) putLong(K_LECTURE_ASK, entry.id) else remove(K_LECTURE_ASK)
                    remove(K_INTENTION)
                }
            }
            nextState(current, completed = true)
        }
        val next = _state.value
        if (finished.inBlock) {
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

    /** After a reboot or an app update: the alarm is gone, so put it back — or catch up if it passed. */
    fun rearm(context: Context, relaunchBrick: Boolean = false) {
        ensureLoaded(context)
        // Only after a reboot or update (the pin is gone then). On any other wake-up it would drag
        // you out of an allowed app back to this screen.
        if (relaunchBrick && brickActive()) {
            // The pin doesn't survive a reboot; reopening Déchaîner puts it back. Blocking holds
            // meanwhile anyway (suspension is the layer under the pin).
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Timber.w(e, "Couldn't reopen the brick screen")
            }
        }
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

    private fun now() = System.currentTimeMillis()

    private inline fun change(context: Context, transform: (PomodoroState) -> PomodoroState) {
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
        // Locking or releasing apps is the blocking engine's job; tell it something changed.
        io.github.warleysr.dechainer.data.ScheduleEnforcer.requestSyncAll(ctx)
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
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openAppIntent(ctx)), pi)
        } catch (e: SecurityException) {
            Timber.w(e, "Exact alarm refused; falling back")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx))
    }

    private fun notificationManager(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels(ctx: Context) {
        val nm = notificationManager(ctx)
        if (nm.getNotificationChannel(CHANNEL_TIMER) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_TIMER, ctx.getString(R.string.focus_channel_timer), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.focus_channel_timer_desc)
                    setShowBadge(false)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARM, ctx.getString(R.string.focus_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.focus_channel_alarm_desc)
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
            val b = NotificationCompat.Builder(ctx, CHANNEL_TIMER)
                .setSmallIcon(R.drawable.ic_notification_time_warning)
                .setContentTitle(phaseLabel(ctx, s.phase))
                .setContentIntent(openAppIntent(ctx))
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (s.isRunning) {
                b.setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true).setWhen(s.endsAt)
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

    private fun lectureIntent(ctx: Context, id: Long, a: LectureAnswer) = PendingIntent.getBroadcast(
        ctx, 40 + a.ordinal,
        Intent(ctx, PomodoroReceiver::class.java).setAction(ACTION_LECTURE)
            .putExtra(EXTRA_SESSION_ID, id).putExtra(EXTRA_LECTURE, a.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** The question's buttons on a notification, whichever question it is. */
    private fun addQuestionActions(ctx: Context, b: NotificationCompat.Builder, id: Long) {
        if (_lectureAsk.value == id) {
            b.addAction(0, ctx.getString(R.string.lecture_done), lectureIntent(ctx, id, LectureAnswer.DONE))
            b.addAction(0, ctx.getString(R.string.lecture_in_progress), lectureIntent(ctx, id, LectureAnswer.IN_PROGRESS))
            b.addAction(0, ctx.getString(R.string.lecture_procrastinating), lectureIntent(ctx, id, LectureAnswer.PROCRASTINATING))
        } else {
            b.addAction(0, ctx.getString(R.string.focus_answer_yes), answerIntent(ctx, id, true))
            b.addAction(0, ctx.getString(R.string.focus_answer_no), answerIntent(ctx, id, false))
        }
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

    // v2: a channel's sound can't change once created, and v1 used the quiet notification stream.
    private const val CHANNEL_CHIME = "focus_chime_v2"

    private fun ensureChimeChannel(ctx: Context) {
        val nm = notificationManager(ctx)
        if (nm.getNotificationChannel(CHANNEL_CHIME) != null) return
        nm.deleteNotificationChannel("focus_chime_v1")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CHIME, ctx.getString(R.string.focus_channel_chime), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.focus_channel_chime_desc)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            }
        )
    }

    /**
     * Between phases of a block: rings on the alarm stream for 5 seconds (noticeable with
     * headphones on another device), with the question when a session ended.
     */
    private fun chime(ctx: Context, finished: PomodoroState, next: PomodoroState, id: Long?) {
        try {
            val nm = notificationManager(ctx)
            ensureChimeChannel(ctx)
            val title = when {
                !next.inBlock -> ctx.getString(R.string.focus_block_done)
                next.phase == Phase.FOCUS -> ctx.getString(R.string.focus_block_next_focus, next.plannedMinutes)
                else -> ctx.getString(R.string.focus_block_next_break, phaseLabel(ctx, next.phase).lowercase(), next.plannedMinutes)
            }
            val b = NotificationCompat.Builder(ctx, CHANNEL_CHIME)
                .setSmallIcon(R.drawable.ic_notification_time_warning)
                .setContentTitle(title)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // Stops by itself after 5 s: the block runs on, nothing to dismiss.
                .setTimeoutAfter(5_000L)
                .setContentIntent(openAppIntent(ctx))
            if (finished.phase == Phase.FOCUS && id != null) {
                b.setContentText(ctx.getString(if (_lectureAsk.value == id) R.string.lecture_question else R.string.focus_question))
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
            val b = NotificationCompat.Builder(ctx, CHANNEL_ALARM)
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
            // Three lecture answers already fill the notification; tapping any stops the ring too.
            if (id == null || _lectureAsk.value != id) {
                b.addAction(0, ctx.getString(R.string.focus_stop_alarm), broadcast(ctx, RC_STOP, ACTION_STOP_ALARM))
            }
            ring(ctx, b)
        } catch (e: Exception) {
            Timber.w(e, "Focus alarm not shown")
        }
    }

    private fun ringBreakDone(ctx: Context, next: PomodoroState) {
        try {
            ensureChannels(ctx)
            val b = NotificationCompat.Builder(ctx, CHANNEL_ALARM)
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
