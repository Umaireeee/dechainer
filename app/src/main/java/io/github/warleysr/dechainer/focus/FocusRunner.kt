package io.github.warleysr.dechainer.focus

import android.content.Context
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.ScheduleRepository
import io.github.warleysr.dechainer.lock.FocusStart
import io.github.warleysr.dechainer.lock.FocusTimetable
import io.github.warleysr.dechainer.lock.FocusWindow
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.store.StoredSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * The Android side of the focus flow (blueprint 6.3). [FocusFlow] decides; this stores the flow
 * (in `app_state`, so it survives a reboot or a process kill), applies what the flow asks for to the
 * Pomodoro timer, the log and the notifications, and starts the FOCUS timetable entries that are due.
 *
 * Time moves the flow only through [advance], which every lock-engine pass calls (blueprint 5.1:
 * the wake-ups are not trusted to arrive, so each one recomputes from stored data), and through the
 * owner's own answers, which also let time catch up.
 *
 * Locking: [lock] guards the flow's state and its row in `app_state`, and is never held while the
 * Pomodoro timer or the log is touched, so it cannot be one half of a deadlock with them.
 */
object FocusRunner {
    const val ACTION_ANSWER = "io.github.warleysr.dechainer.FOCUS_FLOW_ANSWER"

    /** Settled windows kept, newest last: far more than can be open at once. */
    private const val SETTLED_KEEP = 40

    private val lock = Any()
    private val startLock = Any()

    @Volatile private var loaded = false
    private val _flow = MutableStateFlow<FlowState?>(null)

    /** The running (or just ended, waiting for its last answer) focus block's flow, or null. For the screens. */
    val flow: StateFlow<FlowState?> = _flow.asStateFlow()

    /** Reads the stored flow once per process. A store that cannot be read leaves it empty and is tried again next time. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try {
                _flow.value = FlowState.fromJson(Store.appState(context.applicationContext).get(AppStateKeys.FOCUS_FLOW))
                loaded = true
            } catch (e: Exception) {
                Timber.e(e, "Focus flow not readable")
            }
        }
    }

    /** True while a flow exists, whichever stage it is in. Reads memory only: safe from inside another lock. */
    fun hasFlow(): Boolean = _flow.value != null

    /** The next time the flow has something to do by itself, or 0. A wake-up for the lock engine's plan. */
    fun nextWake(context: Context): Long {
        ensureLoaded(context)
        return _flow.value?.let { FocusFlow.nextWake(it) } ?: 0L
    }

    // ---- Starting ----

    /**
     * The owner started a block from the Focus screen: ends at [endsAt], usual or special, for
     * [purpose]. False if it was refused: a block already running, a length outside
     * 10 minutes to 8 hours, or no purpose of at least three characters.
     */
    fun startManual(context: Context, endsAt: Long, flavor: Flavor, purpose: String?): Boolean {
        val ctx = context.applicationContext
        ensureLoaded(ctx)
        Pomodoro.ensureLoaded(ctx)
        if (FocusFlow.cleanPurpose(purpose) == null) return false
        return synchronized(startLock) {
            // The block first (it holds the phone), as one stretch; the flow then says whether phases follow.
            if (!Pomodoro.startBlock(ctx, endsAt, phases = false)) return@synchronized false
            val now = TrustedClock.now(ctx)
            val step = FocusFlow.begin(FocusSource.MANUAL, flavor, purpose, now, endsAt) ?: return@synchronized false
            open(ctx, step)
            true
        }
    }

    /** A FOCUS timetable entry's window is due: the block starts with no one asked first, and the prompt waits on top of it. */
    private fun startScheduled(ctx: Context, window: FocusWindow): Boolean {
        Pomodoro.ensureLoaded(ctx)
        return synchronized(startLock) {
            if (!Pomodoro.startBlock(ctx, FocusTimetable.blockEnd(window), phases = false)) return@synchronized false
            val now = TrustedClock.now(ctx)
            val step = FocusFlow.begin(FocusSource.SCHEDULED, null, null, now, window.endsAt) ?: return@synchronized false
            open(ctx, step)
            true
        }
    }

    /** Stores a new flow with its session row, then applies what its first step asks for. */
    private fun open(ctx: Context, first: FlowStep) {
        // A question from the block before, still waiting after its lock ended, is closed as unanswered.
        val previous = synchronized(lock) { _flow.value }
        if (previous != null && previous.stage == FlowStage.FINAL_ASK) {
            apply(ctx, previous, listOf(FlowEffect.RecordCheckin(first.state.startedAt, CheckinAnswer.UNANSWERED, ResetResult.NONE)))
        }
        val id = try {
            Store.focus(ctx).insert(
                StoredSession(
                    id = first.state.startedAt, source = first.state.source,
                    // Until the prompt is answered the stored flavor is the default it falls back to (D13).
                    flavor = first.state.flavor ?: Flavor.SPECIAL, purpose = first.state.purpose,
                    startedAt = first.state.startedAt, plannedEndAt = first.state.plannedEndAt,
                    endedAt = null, focusedMinutes = 0, outcome = null
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Focus session not stored; the block still runs")
            0L
        }
        val state = first.state.copy(sessionId = id)
        synchronized(lock) { store(ctx, state) }
        // Pass the old flow in: if it was waiting on its last question, publishAndApply clears that
        // stale notification, so its Yes/No buttons cannot act on the new block.
        publishAndApply(ctx, previous = previous, step = FlowStep(state, first.effects))
        Pomodoro.reloadLog(ctx)
        if (state.stage == FlowStage.PROMPT) {
            Pomodoro.postFlowQuestion(
                ctx, ctx.getString(R.string.focus_flow_prompt_title), ctx.getString(R.string.focus_flow_prompt_text),
                yesNo = false, loud = true, fullScreen = true
            )
            Pomodoro.showFlowScreen(ctx)
        }
        LockEngine.requestSync(ctx)
    }

    // ---- The owner's answers ----

    /** "Usual" or "Special", with the purpose, at the prompt. False if the purpose is too short or the prompt is gone. */
    fun choose(context: Context, flavor: Flavor, purpose: String?): Boolean {
        if (FocusFlow.cleanPurpose(purpose) == null) return false
        return transition(context) { s, now -> FocusFlow.onChosen(s, flavor, purpose, now) }
    }

    /** The yes or no to "Did you do the work?", a check-in or the last question. */
    fun answer(context: Context, yes: Boolean): Boolean = transition(context) { s, now -> FocusFlow.onAnswer(s, yes, now) }

    /** "Are you ready now?" at the end of the reset. */
    fun ready(context: Context, ready: Boolean): Boolean = transition(context) { s, now -> FocusFlow.onReady(s, ready, now) }

    /** A focus phase of [minutes] just ended in a block with a flow. Called by the Pomodoro alarm's own path. */
    internal fun onFocusPhaseEnded(context: Context, minutes: Int) {
        transition(context) { s, now -> FocusFlow.onFocusPhaseEnded(s, minutes, now) }
    }

    // ---- Wake-ups ----

    /**
     * Brings the flow up to [now] and starts the FOCUS entry that is due, if any. Called at the start of
     * every lock-engine pass, before the plan is read. True if anything changed, so the pass should
     * read its inputs again.
     */
    fun advance(context: Context, now: Long): Boolean {
        val ctx = context.applicationContext
        ensureLoaded(ctx)
        Pomodoro.ensureLoaded(ctx)
        var changed = false
        // A block whose end has passed is closed through the alarm's own path first, so the focus phase
        // that was running is delivered to the flow before the flow's time moves on (never the other way round).
        if (Pomodoro.state.value.blockExpiredAt(now)) {
            try {
                Pomodoro.closeExpiredBlock(ctx, now)
                changed = true
            } catch (e: Throwable) {
                Timber.e(e, "Expired block not closed from the flow")
            }
        }
        if (transition(ctx, at = now) { s, t -> FocusFlow.tick(s, t) }) changed = true
        if (startDueEntry(ctx, now)) changed = true
        return changed
    }

    /** The system ended the block early (the crash-loop breaker). Never throws, and holds no lock another thread could be stuck behind for long. */
    fun abort(context: Context) {
        val ctx = context.applicationContext
        try {
            ensureLoaded(ctx)
            transition(ctx) { s, now -> FocusFlow.abort(s, now) }
        } catch (e: Throwable) {
            Timber.e(e, "Focus flow not closed on abort")
        }
    }

    /** The FOCUS entry whose window is open and not yet acted on starts its block (6.3, 5.3). */
    private fun startDueEntry(ctx: Context, now: Long): Boolean {
        if (Pomodoro.state.value.inBlock) return false
        // Nothing can be locked without Device Owner, so nothing is started (and nothing is settled either).
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return false
        val schedules = ScheduleRepository.getSchedules(ctx)
        if (schedules.none { it.isFocus }) return false
        val settled = readSettled(ctx)
        val due = FocusTimetable.due(
            now = now, zone = TrustedClock.zone(), schedules = schedules, settledKeys = settled,
            urgeEndsAt = LockStateStore.urge(ctx).endsAt
        ) ?: return false
        return when (val decision = due.decision) {
            FocusStart.Now -> {
                if (!startScheduled(ctx, due.window)) return false
                settle(ctx, settled, due.window.key)
                true
            }
            // The urge lock's end is a wake-up of its own; the start is decided again then.
            is FocusStart.At -> false
            is FocusStart.Skip -> {
                Timber.d("Focus entry %s skipped: %s", due.window.scheduleId, decision.reason)
                settle(ctx, settled, due.window.key)
                false
            }
        }
    }

    private fun readSettled(ctx: Context): Set<String> = try {
        (Store.appState(ctx).get(AppStateKeys.FOCUS_SETTLED) ?: "").lines().filter { it.isNotBlank() }.toSet()
    } catch (e: Exception) {
        Timber.w(e, "Settled focus windows not readable")
        emptySet()
    }

    private fun settle(ctx: Context, settled: Set<String>, key: String) {
        try {
            val kept = (settled - key).toList().takeLast(SETTLED_KEEP - 1) + key
            Store.appState(ctx).set(AppStateKeys.FOCUS_SETTLED, kept.joinToString("\n"))
        } catch (e: Exception) {
            Timber.e(e, "Focus window not marked as handled")
        }
    }

    /** For tests: forget the flow held in memory, so the next call reads the store again. */
    internal fun resetForTests() = synchronized(lock) {
        loaded = false
        _flow.value = null
    }

    // ---- Plumbing ----

    /**
     * One change to the flow: [step] is computed from the stored state, time is let to catch up, and
     * the result is stored; then, outside the lock, its effects are applied. True if anything changed.
     */
    private fun transition(context: Context, at: Long? = null, step: (FlowState, Long) -> FlowStep): Boolean {
        val ctx = context.applicationContext
        ensureLoaded(ctx)
        val now = at ?: TrustedClock.now(ctx)
        val before: FlowState
        val result: FlowStep
        synchronized(lock) {
            before = _flow.value ?: return false
            // Time catches up first, so an answer given after a timeout lands after that timeout: a
            // late check-in is recorded UNANSWERED, a late prompt choice runs as special (D13/D14).
            val caught = FocusFlow.tick(before, now)
            val stepped = step(caught.state, now)
            val settled = FocusFlow.tick(stepped.state, now)
            result = FlowStep(settled.state, caught.effects + stepped.effects + settled.effects)
            if (result.state == before && result.effects.isEmpty()) return false
            store(ctx, result.state)
        }
        publishAndApply(ctx, before, result)
        return true
    }

    /** Keeps [state] in memory and in `app_state`; a finished flow is removed. A store that fails still leaves the memory right. */
    private fun store(ctx: Context, state: FlowState) {
        _flow.value = if (state.stage == FlowStage.DONE) null else state
        try {
            val appState = Store.appState(ctx)
            if (state.stage == FlowStage.DONE) appState.remove(AppStateKeys.FOCUS_FLOW)
            else appState.set(AppStateKeys.FOCUS_FLOW, state.toJson())
        } catch (e: Exception) {
            Timber.e(e, "Focus flow not stored")
        }
    }

    private fun publishAndApply(ctx: Context, previous: FlowState?, step: FlowStep) {
        apply(ctx, step.state, step.effects)
        // A question is cleared the moment the flow leaves the stage that asked it.
        val asking = setOf(FlowStage.PROMPT, FlowStage.CHECKIN, FlowStage.FINAL_ASK)
        if (previous != null && previous.stage in asking && step.state.stage != previous.stage) {
            Pomodoro.cancelFlowQuestion(ctx)
        }
        LockEngine.requestSync(ctx)
    }

    private fun apply(ctx: Context, state: FlowState, effects: List<FlowEffect>) {
        val repo by lazy { Store.focus(ctx) }
        var logChanged = false
        for (effect in effects) {
            try {
                when (effect) {
                    FlowEffect.StartPhases -> Pomodoro.startPhases(ctx)
                    FlowEffect.StopPhases -> Pomodoro.dropPhases(ctx)
                    FlowEffect.PausePhases -> Pomodoro.pause(ctx)
                    FlowEffect.ResumePhases -> Pomodoro.start(ctx)
                    FlowEffect.RingCheckin -> {
                        Pomodoro.postFlowQuestion(
                            ctx, ctx.getString(R.string.focus_flow_checkin_title), state.purpose.ifBlank { null },
                            yesNo = true, loud = true, fullScreen = true
                        )
                        Pomodoro.showFlowScreen(ctx)
                    }
                    FlowEffect.RingFinalAsk -> {
                        // Silent for a special session (blueprint 10): a notification only, no sound and no screen on top.
                        val loud = state.flavor == Flavor.USUAL
                        Pomodoro.postFlowQuestion(
                            ctx, ctx.getString(R.string.focus_flow_final_title), state.purpose.ifBlank { null },
                            yesNo = true, loud = loud, fullScreen = loud
                        )
                        if (loud) Pomodoro.showFlowScreen(ctx)
                    }
                    is FlowEffect.RecordCheckin -> if (state.sessionId > 0L) {
                        repo.addCheckin(state.sessionId, effect.at, effect.answer, effect.reset)
                        logChanged = true
                    }
                    is FlowEffect.EndSession -> if (state.sessionId > 0L) {
                        repo.end(state.sessionId, effect.at, effect.outcome, effect.focusedMinutes)
                        logChanged = true
                    }
                    FlowEffect.UpdateSession -> if (state.sessionId > 0L) {
                        repo.updateChoice(state.sessionId, state.flavor ?: Flavor.SPECIAL, state.purpose)
                        logChanged = true
                    }
                }
            } catch (e: Exception) {
                // One effect failing must not stop the others: the lock and the flow are what matter.
                Timber.e(e, "Focus flow effect %s not applied", effect)
            }
        }
        if (logChanged) Pomodoro.reloadLog(ctx)
    }
}
