package io.github.warleysr.dechainer.viewmodels

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.ai.AiError
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.DeepDiveResult
import io.github.warleysr.dechainer.urge.Question
import io.github.warleysr.dechainer.urge.QuestionSet
import io.github.warleysr.dechainer.urge.Safety
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeFlow
import io.github.warleysr.dechainer.urge.UrgeFlowRules
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeScreen
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/** The questions step of the flow, as the screen sees it. */
sealed interface QuestionsUi {
    data object None : QuestionsUi
    data object Loading : QuestionsUi
    data class Ready(val questions: List<Question>) : QuestionsUi
}

/** The deep dive step of the flow, as the screen sees it. */
sealed interface DeepDiveUi {
    data object Idle : DeepDiveUi

    /** Being written; [partial] is what has arrived so far. */
    data class Writing(val partial: String) : DeepDiveUi
    data class Ready(val markdown: String) : DeepDiveUi

    /** Not made now: the note is still on the phone. */
    data class Pending(val gate: AiGateResult, val error: AiError?) : DeepDiveUi
}

data class UrgeUiState(
    /** The "Ongoing | I slipped" choice is showing. */
    val choosing: Boolean = false,
    val entry: UrgeEntry? = null,
    val questions: QuestionsUi = QuestionsUi.None,
    val deepDive: DeepDiveUi = DeepDiveUi.Idle,
    /** The crisis card is showing (the keyword check or the AI flagged the note). */
    val support: Boolean = false,
    /** Started from the locked screen: only the lock and the breathing run; the private steps wait for the pattern. */
    val holdPrivate: Boolean = false
) {
    /** The flow owns the screen while this is true. */
    val active: Boolean get() = choosing || entry != null
}

/**
 * Drives the urge flow (blueprint 6.2) for the single Activity. The entry, not the screen, is the
 * truth: every step is stored by [UrgeFlow] before the screen moves on, and [UrgeFlowRules.screenFor]
 * says which screen an entry belongs on, so a killed app opens back into the same place.
 */
class UrgeViewModel(app: Application) : AndroidViewModel(app) {
    private val flow = UrgeFlow(app)
    private val _state = MutableStateFlow(UrgeUiState())
    val state: StateFlow<UrgeUiState> = _state.asStateFlow()

    /** What the owner has typed so far, kept here so a rotation does not lose it. */
    var noteDraft by mutableStateOf("")
    val answerDraft = mutableStateMapOf<String, String>()

    /** True from the moment a start is accepted until its entry is on screen, so two taps never make two starts. */
    private val starting = AtomicBoolean(false)

    /** Where the urge was started from; the choice screen passes it on to the entry. */
    private var choiceSource = UrgeSource.HOME

    fun openChoice(source: UrgeSource = UrgeSource.HOME) {
        choiceSource = source
        _state.update { if (it.entry == null) it.copy(choosing = true) else it }
    }

    fun chooseOngoing() = startOngoing(choiceSource)

    fun chooseSlip() = startSlip(choiceSource)

    fun closeChoice() = _state.update { it.copy(choosing = false) }

    /** "Ongoing". The entry and the lock come first; nothing here waits on the network. */
    fun startOngoing(source: UrgeSource, holdPrivate: Boolean = false) {
        if (!starting.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entry = flow.startOngoing(source)
                resetDrafts()
                _state.value = UrgeUiState(entry = entry, holdPrivate = holdPrivate)
            } catch (e: Exception) {
                Timber.e(e, "Urge start failed")
            } finally {
                starting.set(false)
            }
        }
    }

    /** "I slipped": no lock, straight to writing. */
    fun startSlip(source: UrgeSource) {
        if (!starting.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entry = flow.startSlip(source)
                resetDrafts()
                _state.value = UrgeUiState(entry = entry)
            } catch (e: Exception) {
                Timber.e(e, "Slip start failed")
            } finally {
                starting.set(false)
            }
        }
    }

    /**
     * An urge lock is running but this flow has no entry for it (the app was reopened, say): every lock gets its counted entry, and the breathing runs for what is left of it.
     */
    fun adoptRunningLock(holdPrivate: Boolean = false) {
        val s = _state.value
        if (s.entry != null || s.choosing || starting.get()) return
        // Just stepped aside for the lock screen: the lock that is ending is not a new one to adopt.
        if (SystemClock.elapsedRealtime() < suppressAdoptUntil) return
        startOngoing(UrgeSource.HOME, holdPrivate)
    }

    private var suppressAdoptUntil = 0L

    /**
     * The breathing of an urge started from the locked screen is over: the flow steps aside and the
     * lock screen shows. The entry is left as it is, so the pattern brings it back at the writing
     * step ([resumeIfAny]).
     */
    fun leaveForLock() {
        resetDrafts()
        suppressAdoptUntil = SystemClock.elapsedRealtime() + 10_000L
        _state.value = UrgeUiState()
    }

    /** Opens back into an unfinished entry, if there is one; called when the app comes to the front. */
    fun resumeIfAny() {
        val s = _state.value
        if (s.entry != null || s.choosing || !starting.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entry = flow.resumable() ?: return@launch
                resetDrafts()
                _state.value = UrgeUiState(entry = entry)
                if (entry.status == UrgeStatus.QUESTIONS) beginQuestions(entry, entry.rawText.orEmpty())
            } finally {
                starting.set(false)
            }
        }
    }

    /** The writing screen is up: the entry leaves LOCKED. */
    fun writingShown() {
        val e = _state.value.entry ?: return
        if (e.status != UrgeStatus.LOCKED) return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = flow.markWriting(e)
            _state.update { if (it.entry?.id == e.id) it.copy(entry = updated) else it }
        }
    }

    /** The note is submitted: it is stored first, then the questions are fetched (or the fixed ones shown). */
    fun submitNote() {
        val s = _state.value
        val e = s.entry ?: return
        val text = noteDraft
        // A second tap while the first is being handled does nothing.
        if (s.questions != QuestionsUi.None || !UrgeFlowRules.noteReady(text)) return
        _state.update { it.copy(questions = QuestionsUi.Loading, support = Safety.needsSupport(text)) }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = flow.submitNote(e, text)
            _state.update { it.copy(entry = saved) }
            beginQuestions(saved, text)
        }
    }

    /** "Not now": the entry stays as a counted stub and the flow ends. */
    fun skipNote() {
        val e = _state.value.entry ?: return
        viewModelScope.launch(Dispatchers.IO) {
            flow.skip(e)
            finishNow()
        }
    }

    private suspend fun beginQuestions(entry: UrgeEntry, note: String) {
        _state.update { it.copy(questions = QuestionsUi.Loading) }
        val stored = flow.storedQuestions(entry)
        val set: QuestionSet = if (stored != null) {
            QuestionSet(stored, fromAi = true, support = Safety.needsSupport(note))
        } else {
            // The call runs on its own, so the 20 second limit can give up on it without waiting for it to return.
            val call = viewModelScope.async(Dispatchers.IO) { flow.fetchQuestions(entry, note) }
            withTimeoutOrNull(Rules.AI_QUESTIONS_TIMEOUT_MS) { call.await() }
                ?: QuestionSet(flow.fixedQuestions(), fromAi = false, support = Safety.needsSupport(note))
        }
        val saved = if (stored == null) flow.saveQuestions(entry, set.questions) else entry
        _state.update { it.copy(entry = saved, questions = QuestionsUi.Ready(set.questions), support = it.support || set.support) }
    }

    /** The answers are in: stored first (the deep dive is owed from here), then the deep dive is made. */
    fun submitAnswers() {
        val s = _state.value
        val e = s.entry ?: return
        if (s.deepDive != DeepDiveUi.Idle) return
        val questions = (s.questions as? QuestionsUi.Ready)?.questions.orEmpty()
        val answers = questions.map { Answer(it.id, it.prompt, answerDraft[it.id].orEmpty()) }
        _state.update { it.copy(deepDive = DeepDiveUi.Writing("")) }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = flow.saveAnswers(e, answers)
            _state.update { it.copy(entry = saved) }
            val result = flow.deepDive(saved) { partial ->
                _state.update { cur -> if (cur.deepDive is DeepDiveUi.Writing) cur.copy(deepDive = DeepDiveUi.Writing(partial)) else cur }
            }
            _state.update {
                when (result) {
                    is DeepDiveResult.Saved ->
                        it.copy(
                            entry = saved.copy(status = UrgeStatus.DONE, deepDive = result.markdown, rawText = null),
                            deepDive = DeepDiveUi.Ready(result.markdown)
                        )
                    is DeepDiveResult.Pending -> it.copy(deepDive = DeepDiveUi.Pending(result.gate, result.error))
                }
            }
        }
    }

    /** Leaves the flow; a deep dive still owed carries on in the retry job. */
    fun finish() {
        finishNow()
    }

    /** Deletes the entry and everything saved with it, then leaves the flow. */
    fun deleteEntry() {
        val e = _state.value.entry ?: return finishNow()
        viewModelScope.launch(Dispatchers.IO) {
            flow.delete(e)
            finishNow()
        }
    }

    private fun finishNow() {
        resetDrafts()
        _state.value = UrgeUiState()
    }

    private fun resetDrafts() {
        noteDraft = ""
        answerDraft.clear()
    }

    /** Which screen the current entry belongs on, at [now]. Null when there is no entry. */
    fun screenAt(now: Long): UrgeScreen? = _state.value.entry?.let { UrgeFlowRules.screenFor(it, now) }

    /** Whether the entry is a slip, for the writing screen's wording. */
    fun isSlip(): Boolean = _state.value.entry?.kind == UrgeKind.SLIP
}
