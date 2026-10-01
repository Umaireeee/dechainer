package io.github.warleysr.dechainer

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.ai.AiCalls
import io.github.warleysr.dechainer.ai.AiConfig
import io.github.warleysr.dechainer.ai.AiError
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.AiResult
import io.github.warleysr.dechainer.ai.Provider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.PunishmentInput
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.store.UrgeEntryRepository
import io.github.warleysr.dechainer.urge.Answer
import io.github.warleysr.dechainer.urge.AiAccess
import io.github.warleysr.dechainer.urge.DeepDiveResult
import io.github.warleysr.dechainer.urge.DeepDiveRetry
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeFlow
import io.github.warleysr.dechainer.urge.UrgeJson
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The urge flow end to end on the real store and the real lock engine, with the AI replaced by a
 * fake. These are the Phase 3 acceptance checks: with no network an ongoing urge still locks at
 * once, shows the fixed questions and saves the entry; with a key the deep dive is saved and the
 * note becomes NULL; a failed AI call keeps the text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DechainerApplication::class)
class UrgeFlowTest {
    private lateinit var ctx: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val games = "com.example.games"
    private val minute = 60_000L
    private val hour = 3_600_000L

    private val noNetwork: Nothing get() = throw AssertionError("The AI was called")

    private class FakeAi(var gate: AiGateResult = AiGateResult.OPEN) : AiAccess {
        override fun gate() = gate
        override fun config() = AiConfig(Provider.OPENAI, "https://api.example.com/v1", "key", "model")
    }

    private val threeQuestions = """{"support":false,"questions":[
        {"id":"a","type":"text","prompt":"What was on the screen?"},
        {"id":"b","type":"choice","prompt":"Where?","options":["Bed","Desk"]},
        {"id":"c","type":"scale","prompt":"How strong?"}]}"""

    /** Every time the retry job was queued: true for a fresh one. */
    private val queued = mutableListOf<Boolean>()

    private fun flow(
        ai: AiAccess,
        calls: AiCalls = AiCalls(chat = { _, _, _, _ -> noNetwork }, stream = { _, _, _, _, _ -> noNetwork }),
        repository: (() -> UrgeEntryRepository)? = null
    ) = UrgeFlow(
        ctx, calls, ai,
        repository = repository ?: { Store.urgeEntries(ctx) },
        enqueueRetry = { queued += it }
    )

    private fun calls(chat: String? = null, chatFails: AiError? = null, stream: String? = null, streamFails: AiError? = null) = AiCalls(
        chat = { _, _, _, _ -> if (chatFails != null) AiResult.Failed(chatFails) else AiResult.Ok(chat ?: "") },
        stream = { _, _, _, _, on ->
            if (streamFails != null) AiResult.Failed(streamFails) else AiResult.Ok(stream ?: "").also { on(stream ?: "") }
        }
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        listOf(
            "pomodoro", "schedule_state", "schedule_prefs", "security_prefs", "lock_settings", "crash_guard",
            "app_time_limits", "app_time_limits_reached", "ai", "urge_settings"
        ).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        queued.clear()
        Pomodoro.resetForTests()
        Store.clearForTests(ctx)
        LockStateStore.resetForTests()
        dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)

        val info = PackageInfo().apply {
            packageName = games
            applicationInfo = ApplicationInfo().apply { packageName = games; name = "Games" }
        }
        shadowOf(ctx.packageManager).installPackage(info)
        val launcher = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = games; name = "$games.Main"; applicationInfo = info.applicationInfo }
        }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), launcher)
        ScheduleEnforcer.invalidateProtectedPackages()
    }

    @After
    fun tearDown() {
        LockStateStore.resetForTests()
    }

    private fun makeDeviceOwner() = shadowOf(dpm).setDeviceOwner(admin)

    private val repo get() = Store.urgeEntries(ctx)

    private fun fresh(e: UrgeEntry): UrgeEntry = repo.get(e.id)!!

    private fun toAnswered(f: UrgeFlow, started: UrgeEntry, note: String = "I scrolled in bed after the argument"): UrgeEntry {
        var e = f.markWriting(started)
        e = f.submitNote(e, note)
        return f.saveAnswers(e, listOf(Answer("a", "What was on the screen?", "Videos")))
    }

    // ---- the ongoing path ----

    @Test
    fun anOngoingUrgeLocksAtOnceAndStoresItsEntryBeforeAnyNetworkCall() {
        makeDeviceOwner()
        val f = flow(FakeAi(AiGateResult.OFFLINE))   // any AI call would fail the test
        val e = f.startOngoing(UrgeSource.HOME)

        // The entry is counted even though nothing was written.
        assertTrue(e.id > 0)
        assertEquals(UrgeStatus.LOCKED, fresh(e).status)
        assertEquals(UrgeKind.URGE, e.kind)
        // The lock is stored with the same window the breathing uses: ten minutes, no more.
        val lock = LockStateStore.urge(ctx)
        assertEquals(e.lockEndedAt, lock.endsAt)
        assertEquals(e.lockStartedAt, LockStateStore.startedAt(ctx))
        assertEquals(10 * minute, lock.endsAt - e.lockStartedAt!!)
        assertEquals(e.lockStartedAt, fresh(e).lockStartedAt)
        assertEquals(e.lockEndedAt, fresh(e).lockEndedAt)

        // And the next wake-up really takes the apps away.
        LockEngine.sync(ctx)
        assertTrue("the urge lock suspended the app", dpm.isPackageSuspended(admin, games))
    }

    @Test
    fun withNoNetworkAnUrgeStillLocksShowsTheFixedQuestionsAndSavesTheEntry() {
        makeDeviceOwner()
        val f = flow(FakeAi(AiGateResult.OFFLINE))
        val started = f.startOngoing(UrgeSource.TILE)
        LockEngine.sync(ctx)
        assertTrue(dpm.isPackageSuspended(admin, games))

        // The lock ends; the owner writes.
        var e = f.markWriting(started)
        assertEquals(UrgeStatus.WRITING, e.status)
        e = f.submitNote(e, "I kept opening the phone in bed")
        assertEquals(UrgeStatus.QUESTIONS, e.status)
        assertEquals("the note is stored at once", "I kept opening the phone in bed", fresh(e).rawText)

        // No key, no network: the three fixed questions, without a call.
        val set = f.fetchQuestions(e, "I kept opening the phone in bed")
        assertFalse(set.fromAi)
        assertFalse(set.support)
        assertEquals(listOf("before", "feeling", "where"), set.questions.map { it.id })
        assertEquals("What was happening just before the urge started?", set.questions[0].prompt)
        e = f.saveQuestions(e, set.questions)
        assertEquals(set.questions, f.storedQuestions(fresh(e)))

        // The answers are saved; the deep dive cannot be made; the entry waits and the note stays.
        e = f.saveAnswers(e, listOf(Answer("before", "What was happening just before the urge started?", "Scrolling")))
        val result = f.deepDive(e)
        assertEquals(DeepDiveResult.Pending(AiGateResult.OFFLINE, null), result)
        val stored = fresh(e)
        assertEquals(UrgeStatus.PENDING_DEEPDIVE, stored.status)
        assertEquals("the text stays on the phone", "I kept opening the phone in bed", stored.rawText)
        assertNull(stored.deepDive)
    }

    @Test
    fun aSecondTapDuringTheBreathingIsTheSameUrgeAndNeverExtendsTheLock() {
        makeDeviceOwner()
        val f = flow(FakeAi(AiGateResult.OFFLINE))
        val first = f.startOngoing(UrgeSource.HOME)
        val endsAt = LockStateStore.urge(ctx).endsAt
        val second = f.startOngoing(UrgeSource.TILE)

        assertEquals(first.id, second.id)
        assertEquals(1, repo.count())
        assertEquals("not extended", endsAt, LockStateStore.urge(ctx).endsAt)
        assertEquals(first.lockEndedAt, second.lockEndedAt)
    }

    @Test
    fun withoutDeviceOwnerTheBreathingStillRunsTenMinutesAndNoLockIsStored() {
        val f = flow(FakeAi(AiGateResult.OFFLINE))
        val e = f.startOngoing(UrgeSource.HOME)

        assertEquals(10 * minute, e.lockEndedAt!! - e.lockStartedAt!!)
        assertEquals("nothing can be locked", 0L, LockStateStore.urge(ctx).endsAt)
        assertEquals(UrgeStatus.LOCKED, fresh(e).status)
    }

    @Test
    fun onAPunishmentDayTheBreathingRunsWithoutStartingASecondLock() {
        makeDeviceOwner()
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - hour, now + 5 * hour), "today")
        val f = flow(FakeAi(AiGateResult.OFFLINE))
        val e = f.startOngoing(UrgeSource.FOCUS)

        assertEquals("no second lock", 0L, LockStateStore.urge(ctx).endsAt)
        assertEquals(10 * minute, e.lockEndedAt!! - e.lockStartedAt!!)
        assertEquals(UrgeSource.FOCUS, fresh(e).source)
    }

    @Test
    fun aLockTheJournalStartedGetsItsCountedEntryAndBreathesForWhatIsLeft() {
        makeDeviceOwner()
        // Started from outside (the journal's door), with no entry.
        val started = LockEngine.startUrgeLock(ctx)
        val endsAt = LockStateStore.urge(ctx).endsAt
        assertEquals(0, repo.count())

        val e = flow(FakeAi(AiGateResult.OFFLINE)).startOngoing(UrgeSource.HOME)
        assertEquals(1, repo.count())
        assertEquals("never longer than the running lock", endsAt, e.lockEndedAt)
        assertEquals("not extended", endsAt, LockStateStore.urge(ctx).endsAt)
        assertNotNull(started)
    }

    // ---- a slip ----

    @Test
    fun aSlipStartsWritingWithNoLockAndNoBreathing() {
        makeDeviceOwner()
        val e = flow(FakeAi(AiGateResult.OFFLINE)).startSlip(UrgeSource.HOME)

        assertEquals(UrgeKind.SLIP, e.kind)
        assertEquals(UrgeStatus.WRITING, fresh(e).status)
        assertNull(e.lockStartedAt)
        assertEquals("no lock", 0L, LockStateStore.urge(ctx).endsAt)
        LockEngine.sync(ctx)
        assertFalse(dpm.isPackageSuspended(admin, games))
    }

    // ---- the AI ----

    @Test
    fun withAKeyTheDeepDiveIsSavedAndTheNoteBecomesNull() {
        makeDeviceOwner()
        val md = "## What happened\nYou scrolled in bed.\n## The earliest link\nThe argument."
        val f = flow(FakeAi(), calls(chat = threeQuestions, stream = md))
        val started = f.startOngoing(UrgeSource.HOME)

        var e = f.markWriting(started)
        e = f.submitNote(e, "I scrolled in bed after the argument")
        val set = f.fetchQuestions(e, "I scrolled in bed after the argument")
        assertTrue(set.fromAi)
        assertEquals(listOf("a", "b", "c"), set.questions.map { it.id })
        e = f.saveQuestions(e, set.questions)
        e = f.saveAnswers(e, listOf(Answer("a", "What was on the screen?", "Videos"), Answer("b", "Where?", "Bed")))
        assertEquals("the note is kept until the deep dive exists", "I scrolled in bed after the argument", fresh(e).rawText)

        val shown = mutableListOf<String>()
        val result = f.deepDive(e) { shown += it }
        assertEquals(DeepDiveResult.Saved(md), result)
        assertTrue("it streamed to the screen", shown.isNotEmpty())

        val stored = fresh(e)
        assertEquals(UrgeStatus.DONE, stored.status)
        assertEquals(md, stored.deepDive)
        assertNull("raw_text is NULL once the deep dive exists", stored.rawText)
        assertEquals("the answers stay", 2, UrgeJson.answersFromJson(stored.answersJson).size)
    }

    @Test
    fun aFailedAiCallKeepsTheTextAndTheEntryWaits() {
        val f = flow(FakeAi(), calls(streamFails = AiError.NETWORK))
        val e = toAnswered(f, f.startSlip(UrgeSource.HOME))

        val result = f.deepDive(e)
        assertEquals(DeepDiveResult.Pending(AiGateResult.OPEN, AiError.NETWORK), result)
        val stored = fresh(e)
        assertEquals(UrgeStatus.PENDING_DEEPDIVE, stored.status)
        assertEquals("I scrolled in bed after the argument", stored.rawText)
        assertNull(stored.deepDive)
    }

    @Test
    fun aReplyThatIsNotMarkdownKeepsTheTextToo() {
        val f = flow(FakeAi(), calls(stream = "{\"headline\":\"x\"}"))
        val e = toAnswered(f, f.startSlip(UrgeSource.HOME))
        val result = f.deepDive(e)
        assertTrue("$result", result is DeepDiveResult.Pending)
        assertEquals("I scrolled in bed after the argument", fresh(e).rawText)
    }

    @Test
    fun noKeyOrNoConsentNeverCallsTheAiAndKeepsTheText() {
        for (gate in listOf(AiGateResult.NO_KEY, AiGateResult.NO_CONSENT)) {
            val f = flow(FakeAi(gate))   // a call would throw
            val e = toAnswered(f, f.startSlip(UrgeSource.HOME))
            assertEquals(DeepDiveResult.Pending(gate, null), f.deepDive(e))
            assertEquals("I scrolled in bed after the argument", fresh(e).rawText)
        }
    }

    @Test
    fun unusableOrSlowQuestionsFallBackToTheFixedOnes() {
        val note = "I could not stop"
        val garbage = flow(FakeAi(), calls(chat = "no json"))
        val e = garbage.startSlip(UrgeSource.HOME)
        val set = garbage.fetchQuestions(e, note)
        assertFalse(set.fromAi)
        assertEquals(3, set.questions.size)

        val failing = flow(FakeAi(), calls(chatFails = AiError.SERVER)).fetchQuestions(e, note)
        assertFalse(failing.fromAi)
        assertEquals(listOf("before", "feeling", "where"), failing.questions.map { it.id })
    }

    @Test
    fun theCrisisCardComesFromTheKeywordCheckEvenWithNoAi() {
        val f = flow(FakeAi(AiGateResult.NO_KEY))
        val e = f.startSlip(UrgeSource.HOME)
        assertTrue(f.fetchQuestions(e, "I want to die").support)
        assertFalse(f.fetchQuestions(e, "I scrolled too long").support)
    }

    @Test
    fun theCrisisCardAlsoComesFromTheAisSupportReply() {
        val f = flow(FakeAi(), calls(chat = """{"support": true, "questions": []}"""))
        val e = f.startSlip(UrgeSource.HOME)
        val set = f.fetchQuestions(e, "una nota en otro idioma")
        assertTrue(set.support)
        assertEquals("the fixed questions are still offered", 3, set.questions.size)
    }

    // ---- the retry job ----

    @Test
    fun theRetryJobMakesTheDeepDiveLaterAndThenTheNoteIsGone() {
        val f = flow(FakeAi(AiGateResult.OFFLINE))
        val e = toAnswered(f, f.startSlip(UrgeSource.HOME))
        assertTrue(f.deepDive(e) is DeepDiveResult.Pending)
        assertTrue(f.hasPending())

        // Offline: the job asks to be tried again and changes nothing.
        assertEquals(DeepDiveRetry.Outcome.RETRY, f.retryPending())
        assertEquals("I scrolled in bed after the argument", fresh(e).rawText)

        // Online with a key: the job finishes it.
        val online = flow(FakeAi(), calls(stream = "## What happened\nYou scrolled."))
        assertEquals(DeepDiveRetry.Outcome.DONE, online.retryPending())
        val stored = fresh(e)
        assertEquals(UrgeStatus.DONE, stored.status)
        assertEquals("## What happened\nYou scrolled.", stored.deepDive)
        assertNull(stored.rawText)
        assertFalse(online.hasPending())
        assertEquals(DeepDiveRetry.Outcome.NOTHING, online.retryPending())
    }

    @Test
    fun theRetryJobWaitsForTheOwnerWhenThereIsNoKeyAndDoesNotLoop() {
        val f = flow(FakeAi(AiGateResult.NO_KEY))
        val e = toAnswered(f, f.startSlip(UrgeSource.HOME))
        assertEquals(DeepDiveRetry.Outcome.NEEDS_OWNER, f.retryPending())
        assertEquals(UrgeStatus.PENDING_DEEPDIVE, fresh(e).status)

        val refused = flow(FakeAi(), calls(streamFails = AiError.BAD_KEY))
        assertEquals(DeepDiveRetry.Outcome.NEEDS_OWNER, refused.retryPending())
        assertEquals("I scrolled in bed after the argument", fresh(e).rawText)
    }

    @Test
    fun anEntryTheScreenIsWorkingOnIsNotSentTwice() {
        val f = flow(FakeAi())   // a call would throw
        val e = toAnswered(f, f.startSlip(UrgeSource.HOME))
        assertTrue(UrgeFlow.inFlight.tryBegin(e.id))
        try {
            assertEquals(DeepDiveResult.Pending(AiGateResult.OPEN, null), f.deepDive(e))
            assertEquals(DeepDiveRetry.Outcome.RETRY, f.retryPending())
        } finally {
            UrgeFlow.inFlight.end(e.id)
        }
        assertEquals("I scrolled in bed after the argument", fresh(e).rawText)
    }

    // ---- skipping, deleting, resuming ----

    @Test
    fun notNowLeavesACountedStubWithNoText() {
        val f = flow(FakeAi(AiGateResult.OFFLINE))
        val started = f.startOngoing(UrgeSource.SHORTCUT)
        val e = f.skip(f.markWriting(started))
        assertEquals(UrgeStatus.SKIPPED, e.status)
        assertNull(fresh(e).rawText)
        assertEquals(1, repo.count())
    }

    @Test
    fun deletingAPendingEntryRemovesTheNoteWithIt() {
        val f = flow(FakeAi(AiGateResult.NO_KEY))
        val e = toAnswered(f, f.startSlip(UrgeSource.HOME))
        f.delete(e)
        assertNull(repo.get(e.id))
        assertFalse(f.hasPending())
    }

    @Test
    fun anUnfinishedEntryIsResumedAndAFinishedOneIsNot() {
        val f = flow(FakeAi(AiGateResult.OFFLINE))
        assertNull(f.resumable())
        val e = f.startSlip(UrgeSource.HOME)
        assertEquals(e.id, f.resumable()?.id)
        f.skip(e)
        assertNull(f.resumable())
    }

    @Test
    fun aKilledAppResumesAtTheQuestionsWithTheNoteAndQuestionsStored() {
        val f = flow(FakeAi(AiGateResult.OFFLINE))
        var e = f.startSlip(UrgeSource.HOME)
        e = f.submitNote(e, "kept")
        e = f.saveQuestions(e, f.fixedQuestions())
        // A new process: only the stored state is left.
        Store.resetForTests()
        val back = flow(FakeAi(AiGateResult.OFFLINE)).resumable()!!
        assertEquals(UrgeStatus.QUESTIONS, back.status)
        assertEquals("kept", back.rawText)
        assertEquals(3, flow(FakeAi()).storedQuestions(back)?.size)
    }

    @Test
    fun aNoteIsCappedWhereItIsStored() {
        val f = flow(FakeAi(AiGateResult.OFFLINE))
        val e = f.submitNote(f.startSlip(UrgeSource.HOME), "x".repeat(Rules.MAX_NOTE_CHARS + 500))
        assertEquals(Rules.MAX_NOTE_CHARS, fresh(e).rawText!!.length)
    }

    // ---- the retry job is queued only when waiting can help ----

    @Test
    fun theRetryJobIsQueuedWhenOfflineOrWhenTheServiceFailedForNow() {
        val offline = flow(FakeAi(AiGateResult.OFFLINE))
        offline.deepDive(toAnswered(offline, offline.startSlip(UrgeSource.HOME)))
        assertEquals(listOf(true), queued)

        queued.clear()
        val failing = flow(FakeAi(), calls(streamFails = AiError.SERVER))
        failing.deepDive(toAnswered(failing, failing.startSlip(UrgeSource.HOME)))
        assertEquals(listOf(true), queued)
    }

    @Test
    fun theRetryJobIsNotQueuedWhenOnlyTheOwnerCanFixIt() {
        for (gate in listOf(AiGateResult.NO_KEY, AiGateResult.NO_CONSENT)) {
            val f = flow(FakeAi(gate))
            f.deepDive(toAnswered(f, f.startSlip(UrgeSource.HOME)))
        }
        val refused = flow(FakeAi(), calls(streamFails = AiError.BAD_KEY))
        refused.deepDive(toAnswered(refused, refused.startSlip(UrgeSource.HOME)))
        assertTrue("queued: $queued", queued.isEmpty())
    }

    // ---- a broken store never stops the lock ----

    @Test
    fun aStoreThatCannotBeOpenedNeverStopsTheLockOrTheFlow() {
        makeDeviceOwner()
        // A database "file" that is really a directory: every read and write throws.
        val broken = { UrgeEntryRepository(DechainerDatabase(ctx, ctx.cacheDir.absolutePath)) }
        val f = flow(FakeAi(AiGateResult.OFFLINE), repository = broken)

        val e = f.startOngoing(UrgeSource.HOME)
        assertTrue("the entry is in memory only", e.id < 0)
        assertTrue("but the lock started", LockStateStore.urge(ctx).endsAt > TrustedClock.now(ctx))
        assertEquals(LockStateStore.urge(ctx).endsAt, e.lockEndedAt)
        LockEngine.sync(ctx)
        assertTrue(dpm.isPackageSuspended(admin, games))

        // The rest of the flow carries on in memory without throwing.
        var w = f.markWriting(e)
        assertEquals(UrgeStatus.WRITING, w.status)
        w = f.submitNote(w, "still writing")
        assertEquals(UrgeStatus.QUESTIONS, w.status)
        assertEquals("still writing", w.rawText)
        w = f.saveAnswers(w, listOf(Answer("a", "q", "a")))
        assertEquals(UrgeStatus.PENDING_DEEPDIVE, w.status)
        assertEquals(DeepDiveResult.Pending(AiGateResult.OFFLINE, null), f.deepDive(w))
        assertNull(f.resumable())
    }

    @Test
    fun entriesLeftHalfWayPastTheResumeWindowAreSettled() {
        val f = flow(FakeAi())
        val old = System.currentTimeMillis() - 3 * Rules.URGE_RESUME_WINDOW_MS
        val neverWritten = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, old)
        val answeredNever = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, old + 1)
        repo.markWriting(answeredNever); repo.saveNote(answeredNever, "I scrolled in bed")
        val recent = repo.insert(UrgeKind.SLIP, UrgeSource.HOME, System.currentTimeMillis())

        assertEquals(2, f.settleStale())

        assertEquals("no note: a counted stub", UrgeStatus.SKIPPED, repo.get(neverWritten)!!.status)
        val q = repo.get(answeredNever)!!
        assertEquals("a note never answered is owed its deep dive", UrgeStatus.PENDING_DEEPDIVE, q.status)
        assertEquals("I scrolled in bed", q.rawText)
        assertEquals("a recent one is still the screen's", UrgeStatus.WRITING, repo.get(recent)!!.status)
        assertEquals("settling twice changes nothing", 0, f.settleStale())
    }
}
