package io.github.warleysr.dechainer.urge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.ai.AiCalls
import io.github.warleysr.dechainer.ai.AiConfig
import io.github.warleysr.dechainer.ai.AiError
import io.github.warleysr.dechainer.ai.AiGate
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.AiResult
import io.github.warleysr.dechainer.ai.AiSettings
import io.github.warleysr.dechainer.ai.DeepDiveInput
import io.github.warleysr.dechainer.ai.MarkdownOutcome
import io.github.warleysr.dechainer.ai.QuestionsOutcome
import io.github.warleysr.dechainer.ai.QuestionsReply
import io.github.warleysr.dechainer.ai.needsOwner
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.store.UrgeEntryRepository
import timber.log.Timber
import java.time.Instant

/** The questions the owner is shown, where they came from, and whether the crisis card goes with them. */
data class QuestionSet(val questions: List<Question>, val fromAi: Boolean, val support: Boolean)

/** What came of asking for a deep dive. */
sealed interface DeepDiveResult {
    /** The deep dive exists (saved, and the note deleted, when the store was available). */
    data class Saved(val markdown: String) : DeepDiveResult

    /**
     * Not made now. The note is still on the phone and the retry job (if it can help) is queued.
     * [gate] says why the AI was not called; [error] is what the call failed with, when it was made.
     */
    data class Pending(val gate: AiGateResult, val error: AiError?) : DeepDiveResult
}

/** What the flow needs to know about the AI: whether a call may go out, and what to call. Injectable so the flow is tested without a Keystore or a network. */
interface AiAccess {
    fun gate(): AiGateResult
    fun config(): AiConfig
}

/** The real thing: the owner's settings and the phone's network. */
class DeviceAiAccess(context: Context) : AiAccess {
    private val ctx = context.applicationContext
    private val settings = AiSettings(ctx)

    override fun gate(): AiGateResult = AiGate.check(settings.configured, settings.consent, UrgeFlow.isOnline(ctx))

    override fun config(): AiConfig = settings.toConfig()
}

/**
 * The urge flow's actions (blueprint 6.2), tying the pure rules to the lock engine, the store and
 * the AI. Blocking: call it off the main thread. Two promises hold throughout:
 *
 * - The lock never waits on the network or the AI. [startOngoing] stores the entry and starts the
 *   lock before anything else, and a store that cannot be written never stops the lock.
 * - The note is never deleted before its deep dive is saved ([io.github.warleysr.dechainer.store.UrgeEntryRepository.saveDeepDive]
 *   does both in one transaction).
 *
 * If the store is unavailable the flow carries on in memory with an entry whose id is negative: the
 * owner still gets the lock, the breathing and the questions, and nothing is persisted.
 */
class UrgeFlow(
    context: Context,
    private val calls: AiCalls = AiCalls(),
    private val ai: AiAccess = DeviceAiAccess(context),
    private val repository: () -> UrgeEntryRepository = { Store.urgeEntries(context) },
    /** Queues the retry job; `replace` starts a fresh one. Replaced in tests. */
    private val enqueueRetry: (replace: Boolean) -> Unit = { replace -> DeepDiveScheduler.enqueue(context, replace) }
) {
    private val ctx = context.applicationContext

    private val repo get() = repository()

    // ---- starting ----

    /**
     * The ongoing path (6.2): the entry is created, the lock is started, and the breathing window is
     * worked out from what the lock rule decided. A second tap while this urge's breathing still runs
     * returns that same entry: it never extends the lock and never makes a second entry.
     */
    fun startOngoing(source: UrgeSource): UrgeEntry {
        val first = TrustedClock.now(ctx)
        runCatching { UrgeFlowRules.resumable(repo.unfinished(), first) }.getOrNull()
            ?.takeIf { UrgeFlowRules.screenFor(it, first) == UrgeScreen.BREATHING }
            ?.let { return it }

        val id = createEntry(UrgeKind.URGE, source, first)
        val decision = LockEngine.startUrgeLock(ctx)
        val now = TrustedClock.now(ctx)
        val window = UrgeFlowRules.breathingFor(decision, now, lockStartedAt = LockStateStore.startedAt(ctx).takeIf { it > 0L })
        if (id >= 0L) runCatching { repo.setLockWindow(id, window.startsAt, window.endsAt) }
            .onFailure { Timber.w(it, "Urge lock window not stored") }
        return UrgeEntry(id, first, UrgeKind.URGE, source, window.startsAt, window.endsAt, UrgeStatus.LOCKED, null, null, null, null)
    }

    /** "I slipped": no lock (D10) and no breathing; straight to writing. */
    fun startSlip(source: UrgeSource): UrgeEntry {
        val now = TrustedClock.now(ctx)
        val id = createEntry(UrgeKind.SLIP, source, now)
        var start: Long? = null
        var end: Long? = null
        if (Rules.LOCK_AFTER_SLIP) {
            LockEngine.startUrgeLock(ctx)
            val ends = LockStateStore.urge(ctx).endsAt
            if (ends > now) {
                start = now; end = ends
                if (id >= 0L) runCatching { repo.setLockWindow(id, now, ends) }
            }
        }
        return UrgeEntry(id, now, UrgeKind.SLIP, source, start, end, UrgeStatus.WRITING, null, null, null, null)
    }

    /** The entry the app opens back into after being closed or killed, if there is one. */
    fun resumable(): UrgeEntry? =
        runCatching { UrgeFlowRules.resumable(repo.unfinished(), TrustedClock.now(ctx)) }
            .onFailure { Timber.w(it, "Unfinished urge entries not readable") }
            .getOrNull()

    private fun createEntry(kind: UrgeKind, source: UrgeSource, at: Long): Long =
        try {
            repo.insert(kind, source, at)
        } catch (e: Exception) {
            // The lock must start anyway: an entry that cannot be stored is a log line, not a reason to wait.
            Timber.e(e, "Urge entry not stored; carrying on in memory")
            -1L
        }

    // ---- the steps ----

    /** The writing screen is up: the entry leaves LOCKED. */
    fun markWriting(entry: UrgeEntry): UrgeEntry =
        step(entry, entry.copy(status = UrgeStatus.WRITING)) { repo.markWriting(entry.id) }

    /** "Not now": the entry stays as a counted stub. */
    fun skip(entry: UrgeEntry): UrgeEntry =
        step(entry, entry.copy(status = UrgeStatus.SKIPPED, rawText = null)) { repo.skip(entry.id) }

    /** The note is stored at once, before any question is asked. */
    fun submitNote(entry: UrgeEntry, text: String): UrgeEntry {
        val note = text.trim().take(Rules.MAX_NOTE_CHARS)
        return step(entry, entry.copy(status = UrgeStatus.QUESTIONS, rawText = note)) { repo.saveNote(entry.id, note) }
    }

    /** Stores the questions the owner is about to see (the entry keeps them for a later retry). */
    fun saveQuestions(entry: UrgeEntry, questions: List<Question>): UrgeEntry =
        step(entry, entry.copy(questionsJson = UrgeJson.questionsToJson(questions))) { repo.saveQuestions(entry.id, questions) }

    /** The answers are in: the deep dive is owed from here on. */
    fun saveAnswers(entry: UrgeEntry, answers: List<Answer>): UrgeEntry {
        val clean = answers.map { it.copy(answer = it.answer.trim().take(Rules.MAX_ANSWER_CHARS)) }
        return step(entry, entry.copy(status = UrgeStatus.PENDING_DEEPDIVE, answersJson = UrgeJson.answersToJson(clean))) {
            repo.saveAnswers(entry.id, clean)
        }
    }

    /** Deletes the entry (and with it any note still stored). */
    fun delete(entry: UrgeEntry) {
        if (entry.id >= 0L) runCatching {
            val tools = io.github.warleysr.dechainer.report.DataTools(ctx, TrustedClock.zone()) { TrustedClock.now(ctx) }
            if (tools.deleteUrge(entry.id) == io.github.warleysr.dechainer.report.DeleteResult.NOT_ALLOWED) {
                repo.erasePrivateText(entry.id)
            }
        }.onFailure { Timber.w(it, "Urge entry not deleted") }
    }

    /** Applies a stored change, then reads the entry back; without a store, the in-memory copy stands. */
    private fun step(entry: UrgeEntry, local: UrgeEntry, persist: () -> Boolean): UrgeEntry {
        if (entry.id < 0L) return local
        val written = runCatching { persist() }.onFailure { Timber.w(it, "Urge entry %d not written", entry.id) }.getOrDefault(false)
        if (!written) return local.copy(id = -1L)
        return runCatching { repo.get(entry.id) }.getOrNull() ?: local.copy(id = -1L)
    }

    // ---- the questions ----

    /** The three fixed questions, from the app's own text. */
    fun fixedQuestions(): List<Question> = FixedQuestions.build(
        listOf(
            ctx.getString(R.string.urge_fixed_q1),
            ctx.getString(R.string.urge_fixed_q2),
            ctx.getString(R.string.urge_fixed_q3)
        )
    )

    /** The questions saved with an entry, or null if none were stored. */
    fun storedQuestions(entry: UrgeEntry): List<Question>? = UrgeJson.questionsFromJson(entry.questionsJson)

    /**
     * 3 to 5 questions from the AI, or the three fixed ones when there is no key, no consent, no
     * network, an error, or an unusable reply. The crisis card goes with them when the keyword check
     * or the AI flags the note. The caller applies the 20 second limit; the client's own timeouts
     * cut the call off at the same time.
     */
    fun fetchQuestions(entry: UrgeEntry, note: String): QuestionSet {
        val keywordHit = Safety.needsSupport(note)
        val fixed = fixedQuestions()
        if (gate() != AiGateResult.OPEN) return QuestionSet(fixed, fromAi = false, support = keywordHit)
        return when (val out = calls.generateQuestions(ai.config(), entry.kind, note)) {
            is QuestionsOutcome.Failed -> QuestionSet(fixed, false, keywordHit)
            is QuestionsOutcome.Reply -> when (val r = out.reply) {
                QuestionsReply.Support -> QuestionSet(fixed, false, support = true)
                QuestionsReply.Unusable -> QuestionSet(fixed, false, keywordHit)
                is QuestionsReply.Questions -> QuestionSet(r.list, true, keywordHit)
            }
        }
    }

    // ---- the deep dive ----

    /**
     * Makes the deep dive for an entry whose answers are saved. On success it is saved and the note
     * deleted in one transaction. On failure the note stays, the entry stays PENDING_DEEPDIVE, and the
     * retry job is queued when waiting could help.
     */
    fun deepDive(entry: UrgeEntry, onText: (String) -> Unit = {}): DeepDiveResult {
        val stored = entry.id >= 0L
        if (stored && !inFlight.tryBegin(entry.id)) return DeepDiveResult.Pending(AiGateResult.OPEN, null)
        try {
            val gate = gate()
            if (gate != AiGateResult.OPEN) {
                if (gate == AiGateResult.OFFLINE) enqueueRetry(true)
                return DeepDiveResult.Pending(gate, null)
            }
            return when (val out = calls.deepDive(ai.config(), inputFor(entry), onText)) {
                is MarkdownOutcome.Ok -> {
                    val saved = stored && runCatching { repo.saveDeepDive(entry.id, out.markdown) }
                        .onFailure { Timber.e(it, "Deep dive not saved") }.getOrDefault(false)
                    if (saved) DeepDiveResult.Saved(out.markdown)
                    else {
                        if (stored) enqueueRetry(true)
                        DeepDiveResult.Pending(AiGateResult.OPEN, AiError.STORAGE)
                    }
                }
                is MarkdownOutcome.Failed -> {
                    if (!out.error.needsOwner) enqueueRetry(true)
                    DeepDiveResult.Pending(AiGateResult.OPEN, out.error)
                }
            }
        } finally {
            if (stored) inFlight.end(entry.id)
        }
    }

    /** The retry job: every entry still waiting gets another go. */
    fun retryPending(): DeepDiveRetry.Outcome {
        val pending = runCatching { repo.pendingDeepDives() }.getOrDefault(emptyList())
        return DeepDiveRetry.run(
            pending = pending,
            gate = gate(),
            inFlight = inFlight,
            generate = { e ->
                when (val out = calls.deepDive(ai.config(), inputFor(e))) {
                    is MarkdownOutcome.Ok -> AiResult.Ok(out.markdown)
                    is MarkdownOutcome.Failed -> AiResult.Failed(out.error)
                }
            },
            save = { id, md -> runCatching { repo.saveDeepDive(id, md) }.getOrDefault(false) }
        )
    }

    /** Whether any entry is waiting for its deep dive. */
    fun hasPending(): Boolean = runCatching { repo.pendingDeepDives().isNotEmpty() }.getOrDefault(false)

    private fun inputFor(e: UrgeEntry): DeepDiveInput {
        val note = e.rawText.orEmpty()
        return DeepDiveInput(
            kind = e.kind,
            at = Instant.ofEpochMilli(e.createdAt).atZone(TrustedClock.zone()),
            note = note,
            answers = UrgeJson.answersFromJson(e.answersJson),
            flagged = Safety.needsSupport(note),
            // The last month as earlier deep dives summed it up, so a repeat is named and an old plan followed up.
            history = runCatching {
                io.github.warleysr.dechainer.ai.DeepDiveHistory.from(repo.all(), e.id, e.createdAt, TrustedClock.zone())
            }.getOrElse {
                Timber.w(it, "History not readable; the deep dive goes without it")
                io.github.warleysr.dechainer.ai.DeepDiveHistory.NONE
            }
        )
    }

    private fun gate(): AiGateResult = ai.gate()

    companion object {
        /** Shared by the screen and the retry job so one entry is never sent twice at once. */
        val inFlight = InFlight()

        fun isOnline(context: Context): Boolean = try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }
}
