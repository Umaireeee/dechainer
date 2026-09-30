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
    /** Lectures you aim to finish each day; 0 means no goal. */
    val dailyGoal: Int = 0,
    /** How long one lecture usually takes. The first "How's the lecture?" comes at two-thirds. */
    val lectureMinutes: Int = 90,
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
        longBreakEvery = longBreakEvery.coerceIn(EVERY_RANGE),
        dailyGoal = dailyGoal.coerceIn(GOAL_RANGE),
        lectureMinutes = lectureMinutes.coerceIn(LECTURE_RANGE)
    )

    companion object {
        val FOCUS_RANGE = 5..90
        val SHORT_RANGE = 1..30
        val LONG_RANGE = 5..60
        val EVERY_RANGE = 2..8
        val GOAL_RANGE = 0..20
        val LECTURE_RANGE = 30..240
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
        if (!state.isIdle) state
        else startFor(PomodoroState(phase = Phase.FOCUS, blockEndsAt = endsAt), now, firstSessionMinutes)

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

/**
 * One finished focus session. [done] is the answer to "Did you do the work?" — null until given.
 * [tag] is the subject it was for (e.g. "FAR"), if one was picked.
 */
data class FocusSession(
    val id: Long,          // its start time, epoch millis: unique and sortable
    val minutes: Int,
    val done: Boolean? = null,
    val tag: String? = null,
    /** What you planned to do in it, in your words. */
    val intention: String? = null,
    /** Lectures finished in this session, as you reported them. A long lecture can span sessions. */
    val lectures: Int = 0
) {
    fun date(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(id).atZone(zone).toLocalDate()
}

/** A day in the log. */
data class FocusDay(val date: LocalDate, val sessions: List<FocusSession>) {
    val count get() = sessions.size
    val doneCount get() = sessions.count { it.done == true }
    val minutes get() = sessions.sumOf { it.minutes }
    val lectures get() = sessions.sumOf { it.lectures }
}

object FocusLogMath {
    /** Newest day first, each day's sessions newest first. */
    fun byDay(sessions: List<FocusSession>, zone: ZoneId = ZoneId.systemDefault()): List<FocusDay> =
        sessions.groupBy { it.date(zone) }
            .map { (date, list) -> FocusDay(date, list.sortedByDescending { it.id }) }
            .sortedByDescending { it.date }

    /** Minutes on each of the last [days] days, oldest first, ending with [today]. Empty days count as 0. */
    fun dailyMinutes(
        sessions: List<FocusSession>, today: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault(),
        value: (FocusSession) -> Int = { it.minutes }
    ): List<Pair<LocalDate, Int>> {
        val byDate = sessions.groupBy { it.date(zone) }
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            d to (byDate[d]?.sumOf(value) ?: 0)
        }
    }

    /** Minutes in each of the last [weeks] weeks (Monday to Sunday), oldest first, ending with this week. */
    fun weeklyMinutes(
        sessions: List<FocusSession>, today: LocalDate, weeks: Int, zone: ZoneId = ZoneId.systemDefault(),
        value: (FocusSession) -> Int = { it.minutes }
    ): List<Pair<LocalDate, Int>> {
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val byWeek = sessions.groupBy { val d = it.date(zone); d.minusDays((d.dayOfWeek.value - 1).toLong()) }
        return (weeks - 1 downTo 0).map { back ->
            val monday = thisMonday.minusWeeks(back.toLong())
            monday to (byWeek[monday]?.sumOf(value) ?: 0)
        }
    }

    /** Sessions from [from] through [to], inclusive. */
    fun between(sessions: List<FocusSession>, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()) =
        sessions.filter { val d = it.date(zone); !d.isBefore(from) && !d.isAfter(to) }

    // ---- Backup: a plain CSV that opens in Excel or Sheets ----

    private const val CSV_HEADER = "date,time,start_millis,minutes,did_the_work,subject,lectures,intention"

    fun toCsv(sessions: List<FocusSession>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append(CSV_HEADER).append('\n')
        val timeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        sessions.sortedBy { it.id }.forEach { s ->
            val at = Instant.ofEpochMilli(s.id).atZone(zone)
            val done = when (s.done) { true -> "yes"; false -> "no"; null -> "" }
            // Tags and intentions are stored without commas, so no quoting is needed.
            append(at.toLocalDate()).append(',').append(at.format(timeFormat)).append(',')
                .append(s.id).append(',').append(s.minutes).append(',').append(done).append(',')
                .append(cleanTag(s.tag) ?: "").append(',').append(s.lectures).append(',')
                .append(cleanIntention(s.intention) ?: "").append('\n')
        }
    }

    /** Reads a backup made by [toCsv]. Rows it can't understand are skipped, never fatal. */
    fun fromCsv(text: String): List<FocusSession> {
        // Backups from before lectures were counted have no lectures column: read those as 0.
        val withLectures = text.lineSequence().firstOrNull { it.trim().startsWith("date,") }
            ?.split(',')?.map { it.trim() }?.getOrNull(6) == "lectures"
        return text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("date,") }
        .mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 5) return@mapNotNull null
            val id = f[2].trim().toLongOrNull() ?: return@mapNotNull null
            val minutes = f[3].trim().toIntOrNull()?.takeIf { it in 1..600 } ?: return@mapNotNull null
            val done = when (f[4].trim().lowercase()) { "yes" -> true; "no" -> false; else -> null }
            val lectures = if (withLectures) f.getOrNull(6)?.trim()?.toIntOrNull()?.coerceIn(0, 20) ?: 0 else 0
            // An intention that somehow held commas comes back joined, not cut short.
            val rest = f.drop(if (withLectures) 7 else 6).joinToString(" ")
            FocusSession(id, minutes, done, cleanTag(f.getOrNull(5)), cleanIntention(rest), lectures)
        }.toList()
    }

    /** Adds [incoming] sessions that aren't already there (matched by start time). Oldest first. */
    fun merge(existing: List<FocusSession>, incoming: List<FocusSession>): List<FocusSession> {
        val have = existing.map { it.id }.toHashSet()
        return (existing + incoming.filter { it.id !in have }.distinctBy { it.id }).sortedBy { it.id }
    }

    // ---- Per-subject daily targets ----

    /**
     * Sessions that count toward a goal: every one except those you answered "No" to. An
     * unanswered session still counts until you say otherwise.
     */
    fun countsTowardGoal(s: FocusSession) = s.done != false

    /** For each subject with a target: (subject, lectures finished today, target), in [order]. */
    fun subjectProgress(today: List<FocusSession>, targets: Map<String, Int>, order: List<String>): List<Triple<String, Int, Int>> =
        order.mapNotNull { tag ->
            val target = targets[tag] ?: 0
            if (target <= 0) null else Triple(tag, today.filter { it.tag == tag }.sumOf { it.lectures }, target)
        }

    /**
     * The average over a chart page, counting only the points on or after your first session
     * (for weeks: the week it fell in). Empty days before you started don't drag it down.
     */
    fun pageAverage(points: List<Pair<LocalDate, Int>>, firstSession: LocalDate?, weekly: Boolean): Int =
        pageAverageExact(points, firstSession, weekly).toInt()

    /** The same average unrounded: lectures average things like 1.5 a day. */
    fun pageAverageExact(points: List<Pair<LocalDate, Int>>, firstSession: LocalDate?, weekly: Boolean): Float {
        if (firstSession == null) return 0f
        val start = if (weekly) firstSession.minusDays((firstSession.dayOfWeek.value - 1).toLong()) else firstSession
        val counted = points.filter { !it.first.isBefore(start) }
        return if (counted.isEmpty()) 0f else counted.sumOf { it.second }.toFloat() / counted.size
    }

    /** Targets as stored: "tag=2" per line. */
    fun encodeTargets(targets: Map<String, Int>): String =
        targets.filter { it.value > 0 }.entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun decodeTargets(text: String?): Map<String, Int> =
        (text ?: "").lines().mapNotNull { line ->
            val i = line.lastIndexOf('=')
            if (i <= 0) null else {
                val tag = cleanTag(line.substring(0, i)) ?: return@mapNotNull null
                val n = line.substring(i + 1).trim().toIntOrNull()?.coerceIn(0, 10) ?: return@mapNotNull null
                if (n > 0) tag to n else null
            }
        }.toMap()

    /** Sessions from Monday of [today]'s week through [today]. */
    fun thisWeek(sessions: List<FocusSession>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<FocusSession> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return sessions.filter { val d = it.date(zone); !d.isBefore(monday) && !d.isAfter(today) }
    }

    /** A tag safe to store and show: no separators, trimmed, at most 20 characters. Null if empty. */
    fun cleanTag(raw: String?): String? = clean(raw, 20)

    /** An intention safe to store: same rules as a tag, up to 80 characters. */
    fun cleanIntention(raw: String?): String? = clean(raw, 80)

    private val SEPARATORS = Regex("[,;\\n\\r]")
    private val SPACES = Regex("\\s+")

    private fun clean(raw: String?, max: Int): String? =
        raw?.replace(SEPARATORS, " ")?.replace(SPACES, " ")?.trim()?.take(max)?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** [value] per tag (minutes by default), most first; tags with nothing are left out. */
    fun minutesByTag(sessions: List<FocusSession>, value: (FocusSession) -> Int = { it.minutes }): List<Pair<String?, Int>> =
        sessions.groupBy { it.tag }.map { (tag, list) -> tag to list.sumOf(value) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

    /**
     * Compact storage: "id,minutes,d[,tag[,intention[,lectures]]]" per session, d = 1 yes, 0 no, - unanswered; ';'
     * between. Entries from before tags existed have three fields and still read fine.
     */
    fun encode(sessions: List<FocusSession>): String = sessions.joinToString(";") {
        val d = when (it.done) { true -> "1"; false -> "0"; null -> "-" }
        val tag = cleanTag(it.tag)
        val intention = cleanIntention(it.intention)
        when {
            it.lectures > 0 -> "${it.id},${it.minutes},$d,${tag ?: ""},${intention ?: ""},${it.lectures}"
            intention != null -> "${it.id},${it.minutes},$d,${tag ?: ""},$intention"
            tag != null -> "${it.id},${it.minutes},$d,$tag"
            else -> "${it.id},${it.minutes},$d"
        }
    }

    fun decode(text: String?): List<FocusSession> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size !in 3..6) return@mapNotNull null
            val id = f[0].toLongOrNull() ?: return@mapNotNull null
            val minutes = f[1].toIntOrNull() ?: return@mapNotNull null
            val done = when (f[2]) { "1" -> true; "0" -> false; else -> null }
            FocusSession(
                id, minutes, done,
                tag = f.getOrNull(3)?.let { cleanTag(it) },
                intention = f.getOrNull(4)?.let { cleanIntention(it) },
                lectures = f.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 20) ?: 0
            )
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

/** How a lecture checkpoint was answered. */
enum class LectureAnswer { DONE, IN_PROGRESS, PROCRASTINATING }

/**
 * One subject's current lecture: focus minutes put into it so far, and the point at which it was
 * last asked about (0 = not yet).
 */
data class LectureProgress(val minutes: Int = 0, val askedAt: Int = 0)

/**
 * The lecture rules. A lecture takes about [PomodoroSettings.lectureMinutes] of focus; from
 * two-thirds of that on, a finished session asks "How's the lecture?", and again every
 * [RECHECK] minutes after. Each subject keeps its own progress.
 */
object LectureMath {
    const val RECHECK = 30

    fun firstCheck(lectureMinutes: Int) = lectureMinutes * 2 / 3

    /** Adds a finished session's minutes to the lecture. */
    fun afterSession(p: LectureProgress, sessionMinutes: Int) = p.copy(minutes = p.minutes + sessionMinutes)

    /** Whether the session that just ended should ask about the lecture. */
    fun shouldAsk(p: LectureProgress, lectureMinutes: Int): Boolean =
        p.minutes >= firstCheck(lectureMinutes) && (p.askedAt == 0 || p.minutes - p.askedAt >= RECHECK)

    /**
     * Done: the lecture counts and the subject starts a fresh one. In progress: ask again after
     * 30 more minutes. Procrastinating: that session's time didn't go into the lecture, so it's
     * taken back out, but earlier real progress stays; again 30 more real minutes before asking.
     */
    fun answer(p: LectureProgress, a: LectureAnswer, sessionMinutes: Int): LectureProgress = when (a) {
        LectureAnswer.DONE -> LectureProgress()
        LectureAnswer.IN_PROGRESS -> p.copy(askedAt = p.minutes)
        LectureAnswer.PROCRASTINATING -> {
            val kept = (p.minutes - sessionMinutes).coerceAtLeast(0)
            LectureProgress(minutes = kept, askedAt = kept)
        }
    }

    /** Stored as "subject=minutes:askedAt" per line; the untagged subject is stored as "". */
    fun encode(map: Map<String, LectureProgress>): String =
        map.filter { it.value.minutes > 0 }.entries.joinToString("\n") { "${it.key}=${it.value.minutes}:${it.value.askedAt}" }

    fun decode(text: String?): Map<String, LectureProgress> =
        (text ?: "").lines().mapNotNull { line ->
            val i = line.lastIndexOf('=')
            if (i < 0) return@mapNotNull null
            val parts = line.substring(i + 1).split(':')
            val m = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val a = parts.getOrNull(1)?.toIntOrNull() ?: 0
            line.substring(0, i) to LectureProgress(m.coerceAtLeast(0), a.coerceAtLeast(0))
        }.toMap()
}

