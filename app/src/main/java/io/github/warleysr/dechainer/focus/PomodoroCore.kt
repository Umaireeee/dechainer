package io.github.warleysr.dechainer.focus

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The pure rules of the Pomodoro, with no Android in them, so they can be unit-tested. */
enum class Phase { FOCUS, SHORT_BREAK, LONG_BREAK }

data class PomodoroSettings(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    /** A long break comes after this many focus sessions. */
    val longBreakEvery: Int = 4,
    /** Start the break by itself when focus ends. Off: the alarm rings and the break waits for you. */
    val autoStartBreaks: Boolean = false,
    /** Start the next focus session by itself when a break ends. Off: it waits for you. */
    val autoStartFocus: Boolean = false,
    /** Suspend everything except your allowed apps while focus runs (and while it's paused). */
    val lockApps: Boolean = false,
    /**
     * Focus blocks brick the phone: pinned to Déchaîner for the whole block, breaks included,
     * with only Quick Settings (airplane mode, mobile data, hotspot) and incoming calls left.
     */
    val brickBlocks: Boolean = true
) {
    fun minutesFor(phase: Phase) = when (phase) {
        Phase.FOCUS -> focusMinutes
        Phase.SHORT_BREAK -> shortBreakMinutes
        Phase.LONG_BREAK -> longBreakMinutes
    }

    fun clamped() = copy(
        focusMinutes = focusMinutes.coerceIn(FOCUS_RANGE),
        shortBreakMinutes = shortBreakMinutes.coerceIn(SHORT_RANGE),
        longBreakMinutes = longBreakMinutes.coerceIn(LONG_RANGE),
        longBreakEvery = longBreakEvery.coerceIn(EVERY_RANGE)
    )

    companion object {
        val FOCUS_RANGE = 5..90
        val SHORT_RANGE = 1..30
        val LONG_RANGE = 5..60
        val EVERY_RANGE = 2..8
    }
}

/**
 * Where the timer is. [endsAt] is wall-clock millis while running, 0 otherwise; [pausedRemaining]
 * is the time left while paused, 0 otherwise. Idle means neither: waiting for Start.
 * [focusDoneInCycle] counts finished focus sessions since the last long break.
 */
data class PomodoroState(
    val phase: Phase = Phase.FOCUS,
    val endsAt: Long = 0L,
    val pausedRemaining: Long = 0L,
    val phaseStartedAt: Long = 0L,
    val focusDoneInCycle: Int = 0,
    /** The phase's length as planned when it started; what the log records. 0 before a start. */
    val plannedMinutes: Int = 0,
    /** End of a committed focus block (wall-clock millis), or 0 when no block is running. */
    val blockEndsAt: Long = 0L
) {
    val inBlock get() = blockEndsAt > 0L

    /** A block is recorded and its end is still ahead of [now]: the brick holds. */
    fun blockActiveAt(now: Long) = inBlock && blockEndsAt > now

    /**
     * A block is still recorded although its end is at or before [now]: the alarm that should have
     * closed it never did (blueprint 5.4, R1). The time, not the alarm, decides that it is over.
     */
    fun blockExpiredAt(now: Long) = inBlock && blockEndsAt <= now

    val isRunning get() = endsAt > 0L
    val isPaused get() = !isRunning && pausedRemaining > 0L
    val isIdle get() = !isRunning && !isPaused

    fun remaining(now: Long, settings: PomodoroSettings): Long = when {
        isRunning -> (endsAt - now).coerceAtLeast(0L)
        isPaused -> pausedRemaining
        else -> settings.minutesFor(phase) * 60_000L
    }
}

object PomodoroCore {
    /** The break that follows a finished focus session, given how many are now done in the cycle. */
    fun breakAfterFocus(focusDoneInCycle: Int, settings: PomodoroSettings): Phase =
        if (focusDoneInCycle > 0 && focusDoneInCycle % settings.longBreakEvery == 0) Phase.LONG_BREAK
        else Phase.SHORT_BREAK

    fun start(state: PomodoroState, now: Long, settings: PomodoroSettings): PomodoroState = when {
        state.isRunning -> state
        state.isPaused -> state.copy(endsAt = now + state.pausedRemaining, pausedRemaining = 0L)
        // A block with no phase running (it runs as one stretch, or its plan is used up): a plain start
        // must not begin a session in it. [startPhasesInBlock] is the deliberate way.
        state.inBlock -> state
        else -> state.copy(
            endsAt = now + settings.minutesFor(state.phase) * 60_000L,
            pausedRemaining = 0L,
            phaseStartedAt = now,
            plannedMinutes = settings.minutesFor(state.phase)
        )
    }

    /** Starts [state]'s phase now for exactly [minutes] (a block sizes its phases to fit). */
    fun startFor(state: PomodoroState, now: Long, minutes: Int): PomodoroState = state.copy(
        endsAt = now + minutes * 60_000L,
        pausedRemaining = 0L,
        phaseStartedAt = now,
        plannedMinutes = minutes
    )

    fun pause(state: PomodoroState, now: Long): PomodoroState =
        if (!state.isRunning) state
        else state.copy(endsAt = 0L, pausedRemaining = (state.endsAt - now).coerceAtLeast(1_000L))

    /**
     * The state that starts a committed block, or [state] itself when the timer is not idle. The
     * idle check lives here, next to the change, so two quick block requests can never replace a
     * long block that is already running.
     */
    fun beginBlockIfIdle(state: PomodoroState, endsAt: Long, now: Long, firstSessionMinutes: Int): PomodoroState =
        if (!state.isIdle || state.inBlock) state
        else startFor(PomodoroState(phase = Phase.FOCUS, blockEndsAt = endsAt), now, firstSessionMinutes)

    /**
     * The state that starts a block that runs as one stretch to [endsAt] (a special day, or a scheduled
     * block still waiting at its prompt): no phase runs. [state] itself when the timer is not idle or
     * already in a block, for the same reason as [beginBlockIfIdle].
     */
    fun beginContinuousBlockIfIdle(state: PomodoroState, endsAt: Long): PomodoroState =
        if (!state.isIdle || state.inBlock) state
        else PomodoroState(phase = Phase.FOCUS, blockEndsAt = endsAt)

    /** The usual phases begin inside a block that ran as one stretch: the first session now, [minutes] long. Anything else is left alone. */
    fun startPhasesInBlock(state: PomodoroState, now: Long, minutes: Int): PomodoroState =
        if (!state.inBlock || !state.isIdle) state else startFor(state.copy(phase = Phase.FOCUS), now, minutes)

    /** The phases end and the block runs on to its end as one stretch (the plain timer, or a plan used up). */
    fun dropPhasesInBlock(state: PomodoroState): PomodoroState =
        if (!state.inBlock) state
        else PomodoroState(phase = Phase.FOCUS, focusDoneInCycle = state.focusDoneInCycle, blockEndsAt = state.blockEndsAt)

    /** Back to an idle focus session, keeping the cycle count. */
    fun stop(state: PomodoroState): PomodoroState =
        PomodoroState(phase = Phase.FOCUS, focusDoneInCycle = state.focusDoneInCycle)

    /**
     * The phase that comes after [state]'s one. [completed] is false when it was skipped: a
     * skipped focus session isn't counted and isn't logged.
     */
    fun advance(state: PomodoroState, now: Long, settings: PomodoroSettings, completed: Boolean): PomodoroState =
        when (state.phase) {
            Phase.FOCUS -> {
                val done = if (completed) state.focusDoneInCycle + 1 else state.focusDoneInCycle
                val next = breakAfterFocus(done, settings)
                // The cycle count resets when the long break *ends*, so during it the dots stay full.
                val idleBreak = PomodoroState(phase = next, focusDoneInCycle = done)
                if (settings.autoStartBreaks) start(idleBreak, now, settings) else idleBreak
            }
            Phase.SHORT_BREAK, Phase.LONG_BREAK -> {
                val cycle = if (state.phase == Phase.LONG_BREAK) 0 else state.focusDoneInCycle
                val idle = PomodoroState(phase = Phase.FOCUS, focusDoneInCycle = cycle)
                if (settings.autoStartFocus) start(idle, now, settings) else idle
            }
        }
}

/** One finished focus session. [done] is the answer to "Did you do the work?" — null until given. */
data class FocusSession(
    val id: Long,          // its start time, epoch millis: unique and sortable
    val minutes: Int,
    val done: Boolean? = null,
    /** What you planned to do in it, in your words. */
    val intention: String? = null
) {
    fun date(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(id).atZone(zone).toLocalDate()
}

/** A day in the log. */
data class FocusDay(val date: LocalDate, val sessions: List<FocusSession>) {
    val count get() = sessions.size
    val doneCount get() = sessions.count { it.done == true }
    val minutes get() = sessions.sumOf { it.minutes }
}

object FocusLogMath {
    /** Newest day first, each day's sessions newest first. */
    fun byDay(sessions: List<FocusSession>, zone: ZoneId = ZoneId.systemDefault()): List<FocusDay> =
        sessions.groupBy { it.date(zone) }
            .map { (date, list) -> FocusDay(date, list.sortedByDescending { it.id }) }
            .sortedByDescending { it.date }

    /** An intention safe to store: one line, no separators, up to 80 characters. */
    fun cleanIntention(raw: String?): String? = clean(raw, 80)

    private val SEPARATORS = Regex("[,;\\n\\r]")
    private val SPACES = Regex("\\s+")

    private fun clean(raw: String?, max: Int): String? =
        raw?.replace(SEPARATORS, " ")?.replace(SPACES, " ")?.trim()?.take(max)?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Reads the old stored log ("id,minutes,d[,tag[,intention[,lectures]]]" per session, ';' between), which
     * is imported into the store once. The subject and the lecture count are not kept any more.
     */
    fun decode(text: String?): List<FocusSession> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size !in 3..6) return@mapNotNull null
            val id = f[0].toLongOrNull() ?: return@mapNotNull null
            val minutes = f[1].toIntOrNull() ?: return@mapNotNull null
            val done = when (f[2]) { "1" -> true; "0" -> false; else -> null }
            FocusSession(id, minutes, done, intention = f.getOrNull(4)?.let { cleanIntention(it) })
        }
    }
}

/**
 * A committed focus block: from now until a fixed end time, sessions and breaks run by
 * themselves. The same rule decides both the preview and each live step, so what you see before
 * committing is what happens (pauses only shorten what's left, since the end time never moves).
 */
object BlockPlanner {
    /** A session shorter than this isn't worth starting; the block ends instead. */
    const val MIN_SESSION = 10

    /**
     * What comes after [finished] with [remainingMinutes] left in the block, given
     * [focusDoneInCycle] (already counting a just-finished session). Null when the block is over.
     * A break is only planned if a real session can still follow it: no block ends on a break.
     */
    fun next(finished: Phase, focusDoneInCycle: Int, remainingMinutes: Int, s: PomodoroSettings): Pair<Phase, Int>? =
        if (finished == Phase.FOCUS) {
            val b = PomodoroCore.breakAfterFocus(focusDoneInCycle, s)
            val len = minOf(s.minutesFor(b), remainingMinutes)
            if (remainingMinutes - len < MIN_SESSION) null else b to len
        } else {
            if (remainingMinutes < MIN_SESSION) null else Phase.FOCUS to minOf(s.focusMinutes, remainingMinutes)
        }

    /** The whole block laid out for [totalMinutes], starting with a session. Empty if too short. */
    fun plan(totalMinutes: Int, s: PomodoroSettings, focusDoneInCycle: Int = 0): List<Pair<Phase, Int>> {
        if (totalMinutes < MIN_SESSION) return emptyList()
        val out = mutableListOf(Phase.FOCUS to minOf(s.focusMinutes, totalMinutes))
        var left = totalMinutes - out[0].second
        var cycle = focusDoneInCycle + 1
        var last = Phase.FOCUS
        while (true) {
            val step = next(last, cycle, left, s) ?: break
            out += step
            left -= step.second
            last = step.first
            if (step.first == Phase.FOCUS) cycle += 1
            if (step.first == Phase.LONG_BREAK) cycle = 0
        }
        return out
    }
}
