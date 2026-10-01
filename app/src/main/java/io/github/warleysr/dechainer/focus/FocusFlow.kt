package io.github.warleysr.dechainer.focus

import io.github.warleysr.dechainer.Rules
import org.json.JSONObject

/** Blueprint 6.3. A usual day runs Pomodoro phases with check-ins; a special day is one continuous brick. */
enum class Flavor { USUAL, SPECIAL }

enum class FocusSource { MANUAL, SCHEDULED }

enum class CheckinAnswer { YES, NO, UNANSWERED }

enum class ResetResult { NONE, READY, NOT_READY }

enum class SessionOutcome { COMPLETED, PLAIN_TIMER, ENDED_EARLY_BY_SYSTEM }

/**
 * Where a focus block is, as the owner sees it. The block itself (its end time, the pin, the
 * suspended apps) is the lock engine's; this is only what goes on inside it.
 *
 * - [PROMPT]: a scheduled block has started and waits for "usual or special" (D13).
 * - [RUNNING]: a usual block, Pomodoro phases running.
 * - [CHECKIN]: a focus phase ended; "Did you do the work?" is waiting (D14).
 * - [RESET_MEDITATION], [RESET_OUTSIDE], [RESET_ASK]: the reset after a "No".
 * - [NOT_READY]: "not ready" was answered; the message shows, then [PLAIN_TIMER].
 * - [PLAIN_TIMER]: the remaining block time as a countdown, nothing else.
 * - [SPECIAL]: one continuous brick.
 * - [FINAL_ASK]: the block is over and one yes/no is still waiting (D15).
 * - [DONE]: nothing left.
 */
enum class FlowStage {
    PROMPT, RUNNING, CHECKIN, RESET_MEDITATION, RESET_OUTSIDE, RESET_ASK, NOT_READY, PLAIN_TIMER, SPECIAL, FINAL_ASK, DONE;

    /** The block is still holding the phone in this stage. */
    val inBlock: Boolean get() = this != FINAL_ASK && this != DONE
}

/**
 * One focus block's story so far. Times are epoch millis on the trusted clock. [flavor] is null
 * only while [FlowStage.PROMPT] waits. [stageStartedAt] anchors the stage's own timeout, so a late
 * wake-up lands where an on-time one would have.
 */
data class FlowState(
    val stage: FlowStage,
    val source: FocusSource,
    val flavor: Flavor?,
    val purpose: String,
    val startedAt: Long,
    val plannedEndAt: Long,
    val stageStartedAt: Long,
    /** Minutes of completed focus phases (a special block counts its whole window when it ends). */
    val focusedMinutes: Int = 0,
    /** When the "No" that started a reset was given; its check-in row is written when the reset settles. */
    val noAnsweredAt: Long = 0L,
    /** The id of the stored session row, 0 until it exists. */
    val sessionId: Long = 0L
) {
    fun toJson(): String = JSONObject().apply {
        put("stage", stage.name)
        put("source", source.name)
        put("flavor", flavor?.name ?: JSONObject.NULL)
        put("purpose", purpose)
        put("startedAt", startedAt)
        put("plannedEndAt", plannedEndAt)
        put("stageStartedAt", stageStartedAt)
        put("focusedMinutes", focusedMinutes)
        put("noAnsweredAt", noAnsweredAt)
        put("sessionId", sessionId)
    }.toString()

    companion object {
        /** Reads what [toJson] wrote. Null if it cannot be read: the caller then keeps what it had. */
        fun fromJson(text: String?): FlowState? {
            if (text.isNullOrBlank()) return null
            return try {
                val o = JSONObject(text)
                FlowState(
                    stage = FlowStage.valueOf(o.getString("stage")),
                    source = FocusSource.valueOf(o.getString("source")),
                    flavor = if (o.isNull("flavor")) null else Flavor.valueOf(o.getString("flavor")),
                    purpose = o.optString("purpose", ""),
                    startedAt = o.getLong("startedAt"),
                    plannedEndAt = o.getLong("plannedEndAt"),
                    stageStartedAt = o.getLong("stageStartedAt"),
                    focusedMinutes = o.optInt("focusedMinutes", 0),
                    noAnsweredAt = o.optLong("noAnsweredAt", 0L),
                    sessionId = o.optLong("sessionId", 0L)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** What the Android side must do because of a step. The flow decides; it never touches the phone. */
sealed interface FlowEffect {
    /** Begin the usual Pomodoro phases now, sized to what is left of the block. */
    data object StartPhases : FlowEffect

    /** No more phases: the block runs on as one stretch until its end. */
    data object StopPhases : FlowEffect

    /** Freeze the phase clock where it is (the reset). */
    data object PausePhases : FlowEffect

    /** Carry on from where the clock was frozen. */
    data object ResumePhases : FlowEffect

    /** A focus phase ended: chime, and ask "Did you do the work?" on a full screen and a notification. */
    data object RingCheckin : FlowEffect

    /** The block is over and the last yes/no is waiting (D15). */
    data object RingFinalAsk : FlowEffect

    data class RecordCheckin(val at: Long, val answer: CheckinAnswer, val reset: ResetResult) : FlowEffect

    data class EndSession(val at: Long, val outcome: SessionOutcome, val focusedMinutes: Int) : FlowEffect

    /** The purpose or flavor became known (or fell back to its default): update the stored row. */
    data object UpdateSession : FlowEffect
}

data class FlowStep(val state: FlowState, val effects: List<FlowEffect> = emptyList())

/**
 * The state machine of blueprint 6.3, with no Android in it: every transition and every timeout
 * is a function of the state and a time passed in. The caller applies the effects.
 *
 * Time moves the machine only through [tick], which can cross several stages in one call (a phone
 * that was off), always landing where on-time wake-ups would have, in order.
 */
object FocusFlow {

    /** A purpose as stored: one line, trimmed, at most 80 characters, at least [Rules.FOCUS_PURPOSE_MIN_CHARS]; else null. */
    fun cleanPurpose(raw: String?): String? {
        val text = FocusLogMath.cleanIntention(raw) ?: return null
        return text.takeIf { it.length >= Rules.FOCUS_PURPOSE_MIN_CHARS }
    }

    /**
     * A block starts. A manual start has chosen already ([flavor] and a valid [purpose]); a
     * scheduled one waits at the prompt, whatever it is given. Null if a manual start has no valid purpose.
     */
    fun begin(source: FocusSource, flavor: Flavor?, purpose: String?, now: Long, plannedEndAt: Long): FlowStep? {
        if (source == FocusSource.SCHEDULED) {
            return FlowStep(
                FlowState(FlowStage.PROMPT, source, null, "", now, plannedEndAt, now)
            )
        }
        val clean = cleanPurpose(purpose) ?: return null
        val chosen = flavor ?: return null
        return choose(FlowState(FlowStage.PROMPT, source, null, "", now, plannedEndAt, now), chosen, clean, now, withUpdate = false)
    }

    /** "Usual" or "Special", with the purpose, from the prompt. Anything else, or a short purpose, changes nothing. */
    fun onChosen(s: FlowState, flavor: Flavor, purpose: String?, now: Long): FlowStep {
        if (s.stage != FlowStage.PROMPT) return FlowStep(s)
        val clean = cleanPurpose(purpose) ?: return FlowStep(s)
        return choose(s, flavor, clean, now, withUpdate = true)
    }

    private fun choose(s: FlowState, flavor: Flavor, purpose: String, now: Long, withUpdate: Boolean): FlowStep {
        val next = s.copy(
            stage = if (flavor == Flavor.USUAL) FlowStage.RUNNING else FlowStage.SPECIAL,
            flavor = flavor, purpose = purpose, stageStartedAt = now
        )
        val effects = buildList {
            if (flavor == Flavor.USUAL) add(FlowEffect.StartPhases)
            if (withUpdate) add(FlowEffect.UpdateSession)
        }
        return FlowStep(next, effects)
    }

    /**
     * A focus phase of [minutes] ended in a usual block. Rings the check-in. A check-in still
     * waiting from the phase before is closed as unanswered first. At the block's own end the
     * question is the final one, asked after the lock is gone.
     */
    fun onFocusPhaseEnded(s: FlowState, minutes: Int, now: Long): FlowStep {
        if (s.stage != FlowStage.RUNNING && s.stage != FlowStage.CHECKIN) return FlowStep(s)
        val focused = s.focusedMinutes + minutes
        val effects = mutableListOf<FlowEffect>()
        if (s.stage == FlowStage.CHECKIN) effects += FlowEffect.RecordCheckin(now, CheckinAnswer.UNANSWERED, ResetResult.NONE)
        if (now >= s.plannedEndAt - END_SLACK_MS) {
            effects += FlowEffect.EndSession(s.plannedEndAt, SessionOutcome.COMPLETED, focused)
            effects += FlowEffect.RingFinalAsk
            return FlowStep(s.copy(stage = FlowStage.FINAL_ASK, focusedMinutes = focused, stageStartedAt = maxOf(now, s.plannedEndAt)), effects)
        }
        effects += FlowEffect.RingCheckin
        return FlowStep(s.copy(stage = FlowStage.CHECKIN, focusedMinutes = focused, stageStartedAt = now), effects)
    }

    /** The owner's yes or no to "Did you do the work?" (a check-in, or the last question after the block). */
    fun onAnswer(s: FlowState, yes: Boolean, now: Long): FlowStep = when (s.stage) {
        FlowStage.CHECKIN ->
            if (yes) {
                FlowStep(
                    s.copy(stage = FlowStage.RUNNING, stageStartedAt = now),
                    listOf(FlowEffect.RecordCheckin(now, CheckinAnswer.YES, ResetResult.NONE))
                )
            } else {
                // The phase clock stops; the reset runs inside the block, whose end does not move.
                FlowStep(
                    s.copy(stage = FlowStage.RESET_MEDITATION, stageStartedAt = now, noAnsweredAt = now),
                    listOf(FlowEffect.PausePhases)
                )
            }
        FlowStage.FINAL_ASK -> FlowStep(
            s.copy(stage = FlowStage.DONE),
            listOf(FlowEffect.RecordCheckin(now, if (yes) CheckinAnswer.YES else CheckinAnswer.NO, ResetResult.NONE))
        )
        else -> FlowStep(s)
    }

    /** "Are you ready now?" at the end of the reset. */
    fun onReady(s: FlowState, ready: Boolean, now: Long): FlowStep {
        if (s.stage != FlowStage.RESET_ASK) return FlowStep(s)
        return if (ready) {
            FlowStep(
                s.copy(stage = FlowStage.RUNNING, stageStartedAt = now),
                listOf(FlowEffect.ResumePhases, FlowEffect.RecordCheckin(s.noAnsweredAt, CheckinAnswer.NO, ResetResult.READY))
            )
        } else {
            FlowStep(
                s.copy(stage = FlowStage.NOT_READY, stageStartedAt = now),
                listOf(FlowEffect.StopPhases, FlowEffect.RecordCheckin(s.noAnsweredAt, CheckinAnswer.NO, ResetResult.NOT_READY))
            )
        }
    }

    /**
     * Time passes. Applies every timeout and the block's end that fall at or before [now], in the
     * order they fell, so a wake-up that comes late still records what on-time ones would have.
     */
    fun tick(start: FlowState, now: Long): FlowStep {
        var s = start
        val effects = mutableListOf<FlowEffect>()
        // Each pass moves at least one stage forward; a flow has few of them, the cap is only a seat belt.
        repeat(MAX_TICK_STEPS) {
            if (s.stage == FlowStage.DONE) return FlowStep(s, effects)
            val deadline = stageDeadline(s)
            val blockOver = s.stage.inBlock && s.plannedEndAt <= now
            val stageFirst = deadline != null && deadline <= now && (!s.stage.inBlock || deadline <= s.plannedEndAt)
            when {
                stageFirst -> {
                    val step = timeout(s, deadline!!)
                    s = step.state
                    effects += step.effects
                }
                blockOver -> {
                    val step = endBlock(s)
                    s = step.state
                    effects += step.effects
                }
                else -> return FlowStep(s, effects)
            }
        }
        return FlowStep(s, effects)
    }

    /** The system ended the block early (the crash-loop breaker): the session is closed on record as such. */
    fun abort(s: FlowState, now: Long): FlowStep =
        if (s.stage == FlowStage.DONE) FlowStep(s)
        else FlowStep(
            s.copy(stage = FlowStage.DONE),
            listOf(FlowEffect.EndSession(now, SessionOutcome.ENDED_EARLY_BY_SYSTEM, s.focusedMinutes))
        )

    /** The next moment [tick] will have something to do, or null when only the owner can move the flow on. */
    fun nextWake(s: FlowState): Long? {
        if (s.stage == FlowStage.DONE) return null
        val deadline = stageDeadline(s)
        return if (!s.stage.inBlock) deadline else listOfNotNull(deadline, s.plannedEndAt).min()
    }

    /** When this stage times out by itself, or null if it only ends by an answer or the block's end. */
    fun stageDeadline(s: FlowState): Long? = when (s.stage) {
        FlowStage.PROMPT -> s.stageStartedAt + Rules.FOCUS_PROMPT_TIMEOUT_MS
        FlowStage.CHECKIN, FlowStage.FINAL_ASK -> s.stageStartedAt + Rules.FOCUS_CHECKIN_TIMEOUT_MS
        FlowStage.RESET_MEDITATION -> s.stageStartedAt + Rules.FOCUS_RESET_MEDITATION_MS
        FlowStage.RESET_OUTSIDE -> s.stageStartedAt + Rules.FOCUS_RESET_OUTSIDE_MS
        FlowStage.NOT_READY -> s.stageStartedAt + Rules.FOCUS_NOT_READY_MESSAGE_MS
        else -> null
    }

    /** A stage's own timeout, at the moment [at] it fell due. */
    private fun timeout(s: FlowState, at: Long): FlowStep = when (s.stage) {
        // D13: no answer in two minutes, so the session runs as special (the stricter one), with no purpose.
        FlowStage.PROMPT -> FlowStep(
            s.copy(stage = FlowStage.SPECIAL, flavor = Flavor.SPECIAL, purpose = "", stageStartedAt = at),
            listOf(FlowEffect.UpdateSession)
        )
        // D14: no answer in five minutes is recorded as unanswered, with no consequence.
        FlowStage.CHECKIN -> FlowStep(
            s.copy(stage = FlowStage.RUNNING, stageStartedAt = at),
            listOf(FlowEffect.RecordCheckin(at, CheckinAnswer.UNANSWERED, ResetResult.NONE))
        )
        FlowStage.RESET_MEDITATION -> FlowStep(s.copy(stage = FlowStage.RESET_OUTSIDE, stageStartedAt = at))
        FlowStage.RESET_OUTSIDE -> FlowStep(s.copy(stage = FlowStage.RESET_ASK, stageStartedAt = at))
        FlowStage.NOT_READY -> FlowStep(s.copy(stage = FlowStage.PLAIN_TIMER, stageStartedAt = at))
        FlowStage.FINAL_ASK -> FlowStep(
            s.copy(stage = FlowStage.DONE),
            listOf(FlowEffect.RecordCheckin(at, CheckinAnswer.UNANSWERED, ResetResult.NONE))
        )
        else -> FlowStep(s)
    }

    /** The block's end time has come. */
    private fun endBlock(s: FlowState): FlowStep {
        val end = s.plannedEndAt
        // To the nearest minute: a block that began a moment after its window opened still counts the whole window.
        val wholeWindow = ((end - s.startedAt + 30_000L) / 60_000L).toInt().coerceAtLeast(0)
        return when (s.stage) {
            // The window ended before anyone answered: it ran as special after all.
            FlowStage.PROMPT, FlowStage.SPECIAL -> FlowStep(
                s.copy(stage = FlowStage.FINAL_ASK, flavor = Flavor.SPECIAL, stageStartedAt = end, focusedMinutes = wholeWindow),
                listOf(FlowEffect.EndSession(end, SessionOutcome.COMPLETED, wholeWindow), FlowEffect.RingFinalAsk)
            )
            FlowStage.RUNNING -> FlowStep(
                s.copy(stage = FlowStage.DONE),
                listOf(FlowEffect.EndSession(end, SessionOutcome.COMPLETED, s.focusedMinutes))
            )
            // The question was still open: it is asked once more after the lock is gone.
            FlowStage.CHECKIN -> FlowStep(
                s.copy(stage = FlowStage.FINAL_ASK, stageStartedAt = end),
                listOf(FlowEffect.EndSession(end, SessionOutcome.COMPLETED, s.focusedMinutes), FlowEffect.RingFinalAsk)
            )
            // The block ended inside the reset: the "No" is on record, with no reset result.
            FlowStage.RESET_MEDITATION, FlowStage.RESET_OUTSIDE, FlowStage.RESET_ASK -> FlowStep(
                s.copy(stage = FlowStage.DONE),
                listOf(
                    FlowEffect.RecordCheckin(s.noAnsweredAt, CheckinAnswer.NO, ResetResult.NONE),
                    FlowEffect.EndSession(end, SessionOutcome.COMPLETED, s.focusedMinutes)
                )
            )
            FlowStage.NOT_READY, FlowStage.PLAIN_TIMER -> FlowStep(
                s.copy(stage = FlowStage.DONE),
                listOf(FlowEffect.EndSession(end, SessionOutcome.PLAIN_TIMER, s.focusedMinutes))
            )
            FlowStage.FINAL_ASK, FlowStage.DONE -> FlowStep(s)
        }
    }

    /** The question for a last answer, or an answer from a notification, arrives this close to the block's end and still counts as at it. */
    private const val END_SLACK_MS = 1_000L

    private const val MAX_TICK_STEPS = 12
}
