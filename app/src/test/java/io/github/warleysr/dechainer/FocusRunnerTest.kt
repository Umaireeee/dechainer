package io.github.warleysr.dechainer

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.ScheduleRepository
import io.github.warleysr.dechainer.focus.CheckinAnswer
import io.github.warleysr.dechainer.focus.Flavor
import io.github.warleysr.dechainer.focus.FlowStage
import io.github.warleysr.dechainer.focus.FocusRunner
import io.github.warleysr.dechainer.focus.FocusSource
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.focus.ResetResult
import io.github.warleysr.dechainer.focus.SessionOutcome
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.models.BlockSchedule
import io.github.warleysr.dechainer.models.ScheduleType
import io.github.warleysr.dechainer.store.Store
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
import java.time.LocalTime

/**
 * The focus flow end to end on the stored state: a block started by hand or by the timetable, the
 * prompt, the check-in, the reset, the plain timer, the end, and what is written down. Time is passed
 * to [FocusRunner.advance] directly, so the timeouts are tested without waiting for them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DechainerApplication::class)
class FocusRunnerTest {
    private lateinit var ctx: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val minute = 60_000L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // The lock engine runs each pass on its own thread (a Pomodoro change asks for one). A pass a
        // previous test left in flight would read that test's context and clobber the shared Pomodoro
        // and focus-flow state mid-test; drain it here, then reset, so each test starts from scratch.
        LockEngine.sync(ctx)
        listOf(
            "pomodoro", "schedule_state", "schedule_prefs", "security_prefs", "lock_settings", "crash_guard",
            "app_time_limits", "app_time_limits_reached"
        ).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        // The real Application's startup work opens the database on its own thread: empty the tables, do not delete the file under it.
        Store.resetForTests()
        Store.clearForTests(ctx)
        TrustedClock.resetForTests()
        LockStateStore.resetForTests()
        Pomodoro.resetForTests()
        dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
    }

    private fun makeDeviceOwner() = shadowOf(dpm).setDeviceOwner(admin)

    private val repo get() = Store.focus(ctx)

    /** The one stored session. */
    private fun only() = repo.sessions().single()

    private fun start(flavor: Flavor = Flavor.USUAL, minutes: Long = 120, purpose: String? = "Tax chapter 4"): Boolean =
        FocusRunner.startManual(ctx, TrustedClock.now(ctx) + minutes * minute, flavor, purpose)

    // ---- Starting by hand ----

    @Test
    fun aUsualBlockStartsItsPhasesAndStoresASession() {
        assertTrue(start())
        val flow = FocusRunner.flow.value!!
        assertEquals(FlowStage.RUNNING, flow.stage)
        assertEquals(Flavor.USUAL, flow.flavor)
        assertTrue(Pomodoro.state.value.inBlock)
        assertTrue("the first focus session is running", Pomodoro.state.value.isRunning)
        val row = only()
        assertEquals(flow.sessionId, row.id)
        assertEquals("Tax chapter 4", row.purpose)
        assertEquals(Flavor.USUAL, row.flavor)
        assertEquals(FocusSource.MANUAL, row.source)
        assertNull("not ended yet", row.endedAt)
    }

    @Test
    fun aSpecialBlockIsOneContinuousBrickWithNoPhase() {
        assertTrue(start(Flavor.SPECIAL, purpose = "Mock exam"))
        assertEquals(FlowStage.SPECIAL, FocusRunner.flow.value!!.stage)
        assertTrue(Pomodoro.state.value.inBlock)
        assertTrue("no phase runs", Pomodoro.state.value.isIdle)
        assertTrue(Pomodoro.brickActive())
    }

    @Test
    fun aStartIsRefusedWithoutAPurposeOrOutsideTenMinutesToEightHours() {
        assertFalse(start(purpose = "ab"))
        assertFalse(start(purpose = null))
        assertFalse(start(minutes = 9))
        assertFalse(start(minutes = 8 * 60 + 5))
        assertEquals(0, repo.count())
        assertNull(FocusRunner.flow.value)
        assertTrue(start(minutes = 11))
    }

    @Test
    fun anEightHourBlockIsTheLongestAllowed() {
        assertTrue(start(Flavor.SPECIAL, minutes = 8 * 60, purpose = "Mock exam"))
    }

    @Test
    fun aSecondStartLeavesTheRunningBlockAlone() {
        assertTrue(start(Flavor.SPECIAL, minutes = 180, purpose = "Mock exam"))
        val end = Pomodoro.state.value.blockEndsAt
        assertFalse(start(Flavor.USUAL, minutes = 30))
        assertEquals(end, Pomodoro.state.value.blockEndsAt)
        assertEquals(1, repo.count())
        assertEquals(Flavor.SPECIAL, FocusRunner.flow.value!!.flavor)
    }

    // ---- The check-in and the reset ----

    @Test
    fun aFinishedFocusPhaseAsksAndYesCarriesOn() {
        start()
        FocusRunner.onFocusPhaseEnded(ctx, 25)
        assertEquals(FlowStage.CHECKIN, FocusRunner.flow.value!!.stage)
        assertTrue(FocusRunner.answer(ctx, true))
        assertEquals(FlowStage.RUNNING, FocusRunner.flow.value!!.stage)
        val checkins = repo.checkins(only().id)
        assertEquals(listOf(CheckinAnswer.YES), checkins.map { it.answer })
        assertEquals("the log reads it as done", true, Pomodoro.log.value.single().done)
    }

    @Test
    fun noPausesThePhaseClockAndStartsTheReset() {
        start()
        assertTrue(Pomodoro.state.value.isRunning)
        FocusRunner.onFocusPhaseEnded(ctx, 25)
        FocusRunner.answer(ctx, false)
        assertEquals(FlowStage.RESET_MEDITATION, FocusRunner.flow.value!!.stage)
        assertTrue("the phase clock is paused", Pomodoro.state.value.isPaused)
        assertTrue("the phone stays locked", Pomodoro.brickActive())
        assertTrue("nothing is written until the reset settles", repo.checkins(only().id).isEmpty())
    }

    @Test
    fun readyAfterTheResetResumesThePhasesAndRecordsTheNoAsReady() {
        start()
        FocusRunner.onFocusPhaseEnded(ctx, 25)
        FocusRunner.answer(ctx, false)
        val blockEnd = Pomodoro.state.value.blockEndsAt
        FocusRunner.advance(ctx, TrustedClock.now(ctx) + 11 * minute)
        assertEquals(FlowStage.RESET_ASK, FocusRunner.flow.value!!.stage)

        assertTrue(FocusRunner.ready(ctx, true))
        assertEquals(FlowStage.RUNNING, FocusRunner.flow.value!!.stage)
        assertTrue("the phase clock runs again", Pomodoro.state.value.isRunning)
        assertEquals("the block's end did not move", blockEnd, Pomodoro.state.value.blockEndsAt)
        val checkin = repo.checkins(only().id).single()
        assertEquals(CheckinAnswer.NO, checkin.answer)
        assertEquals(ResetResult.READY, checkin.reset)
    }

    @Test
    fun notReadyEndsInAPlainTimerWithNoMorePhasesAndTheBlockStillHoldsToItsEnd() {
        assertTrue("the block starts", start(minutes = 120))
        FocusRunner.onFocusPhaseEnded(ctx, 25)
        FocusRunner.answer(ctx, false)
        val blockEnd = Pomodoro.state.value.blockEndsAt
        FocusRunner.advance(ctx, TrustedClock.now(ctx) + 11 * minute)
        FocusRunner.ready(ctx, false)
        assertEquals(FlowStage.NOT_READY, FocusRunner.flow.value!!.stage)
        assertEquals(ResetResult.NOT_READY, repo.checkins(only().id).single().reset)

        FocusRunner.advance(ctx, TrustedClock.now(ctx) + 12 * minute)
        assertEquals(FlowStage.PLAIN_TIMER, FocusRunner.flow.value!!.stage)
        assertTrue("no phase runs", Pomodoro.state.value.isIdle)
        assertEquals(blockEnd, Pomodoro.state.value.blockEndsAt)
        assertTrue(Pomodoro.brickActive())

        FocusRunner.advance(ctx, blockEnd)
        assertNull("the flow is over", FocusRunner.flow.value)
        assertFalse("the block is closed", Pomodoro.state.value.inBlock)
        val row = only()
        assertEquals(SessionOutcome.PLAIN_TIMER, row.outcome)
        assertEquals(blockEnd, row.endedAt)
        assertEquals(25, row.focusedMinutes)
    }

    @Test
    fun aCheckinNobodyAnswersIsRecordedAsUnansweredAndTheBlockCarriesOn() {
        start()
        FocusRunner.onFocusPhaseEnded(ctx, 25)
        FocusRunner.advance(ctx, TrustedClock.now(ctx) + 5 * minute + 1)
        assertEquals(FlowStage.RUNNING, FocusRunner.flow.value!!.stage)
        assertEquals(listOf(CheckinAnswer.UNANSWERED), repo.checkins(only().id).map { it.answer })
        assertTrue(Pomodoro.brickActive())
    }

    // ---- The end ----

    @Test
    fun aSpecialBlockAsksOneQuestionAfterItsEndAndTheAnswerIsStored() {
        start(Flavor.SPECIAL, minutes = 60, purpose = "Mock exam")
        val end = Pomodoro.state.value.blockEndsAt
        FocusRunner.advance(ctx, end)
        assertFalse("the lock is released", Pomodoro.brickActive())
        val flow = FocusRunner.flow.value!!
        assertEquals(FlowStage.FINAL_ASK, flow.stage)
        assertEquals(SessionOutcome.COMPLETED, only().outcome)
        assertEquals(60, only().focusedMinutes)

        assertTrue(FocusRunner.answer(ctx, true))
        assertNull(FocusRunner.flow.value)
        assertEquals(listOf(CheckinAnswer.YES), repo.checkins(only().id).map { it.answer })
    }

    @Test
    fun anUnansweredFinalQuestionIsRecordedAsUnansweredAfterFiveMinutes() {
        start(Flavor.SPECIAL, minutes = 60, purpose = "Mock exam")
        val end = Pomodoro.state.value.blockEndsAt
        FocusRunner.advance(ctx, end)
        FocusRunner.advance(ctx, end + 5 * minute)
        assertNull(FocusRunner.flow.value)
        assertEquals(listOf(CheckinAnswer.UNANSWERED), repo.checkins(only().id).map { it.answer })
    }

    @Test
    fun aUsualBlockWhoseEndPassedWhileThePhoneWasOffIsEndedFromTheTimeAndLoggedOnce() {
        start(minutes = 60)
        val end = Pomodoro.state.value.blockEndsAt
        FocusRunner.advance(ctx, end + 30 * minute)
        FocusRunner.advance(ctx, end + 31 * minute)
        assertFalse(Pomodoro.state.value.inBlock)
        assertEquals(1, repo.count())
        assertNotNull(only().endedAt)
        assertEquals(SessionOutcome.COMPLETED, only().outcome)
    }

    @Test
    fun theFlowSurvivesTheProcessBeingKilled() {
        start(Flavor.SPECIAL, purpose = "Mock exam")
        val before = FocusRunner.flow.value
        FocusRunner.resetForTests()
        FocusRunner.ensureLoaded(ctx)
        assertEquals(before, FocusRunner.flow.value)
    }

    @Test
    fun theCrashBreakerClosesTheSessionAsEndedBySystem() {
        makeDeviceOwner()
        start()
        LockEngine.abortBrick(ctx)
        assertNull(FocusRunner.flow.value)
        assertEquals(SessionOutcome.ENDED_EARLY_BY_SYSTEM, only().outcome)
        assertFalse(Pomodoro.brickActive())
    }

    // ---- The timetable ----

    /** A FOCUS entry whose window opened five minutes ago and closes in 55, every day. */
    private fun storeOpenFocusEntry(minutesLeft: Int = 55): BlockSchedule {
        val nowLocal = LocalTime.now(TrustedClock.zone())
        val start = (nowLocal.hour * 60 + nowLocal.minute - 5 + 1440) % 1440
        val end = (nowLocal.hour * 60 + nowLocal.minute + minutesLeft) % 1440
        val entry = BlockSchedule("study", "Study", startMinute = start, endMinute = end, type = ScheduleType.FOCUS)
        assertTrue(ScheduleRepository.upsert(ctx, entry))
        return entry
    }

    @Test
    fun aFocusEntryThatIsOpenStartsABlockWithNoInteractionAndRunsAsSpecialIfTheOnePromptIsIgnored() {
        makeDeviceOwner()
        storeOpenFocusEntry()

        LockEngine.sync(ctx)
        val prompt = FocusRunner.flow.value!!
        assertEquals("the brick starts on schedule whatever anyone answers", FlowStage.PROMPT, prompt.stage)
        assertTrue(Pomodoro.brickActive())
        assertEquals(LockMode.FOCUS_BLOCK, LockEngine.status.value?.primary)
        assertEquals(FocusSource.SCHEDULED, only().source)

        // Nobody answers. Two minutes on, it runs as special, with no purpose.
        FocusRunner.advance(ctx, prompt.startedAt + 2 * minute)
        val special = FocusRunner.flow.value!!
        assertEquals(FlowStage.SPECIAL, special.stage)
        assertEquals(Flavor.SPECIAL, special.flavor)
        assertEquals("", special.purpose)
        assertEquals(Flavor.SPECIAL, only().flavor)
        assertTrue(Pomodoro.brickActive())
    }

    @Test
    fun answeringThePromptUsualStartsThePhasesAndStoresThePurpose() {
        makeDeviceOwner()
        storeOpenFocusEntry()
        LockEngine.sync(ctx)
        assertFalse("too short a purpose is not taken", FocusRunner.choose(ctx, Flavor.USUAL, "ab"))
        assertEquals(FlowStage.PROMPT, FocusRunner.flow.value!!.stage)

        assertTrue(FocusRunner.choose(ctx, Flavor.USUAL, "Audit notes"))
        assertEquals(FlowStage.RUNNING, FocusRunner.flow.value!!.stage)
        assertTrue(Pomodoro.state.value.isRunning)
        assertEquals("Audit notes", only().purpose)
        assertEquals(Flavor.USUAL, only().flavor)
    }

    @Test
    fun aWindowIsStartedOnceHoweverManyWakeUpsSeeIt() {
        makeDeviceOwner()
        storeOpenFocusEntry()
        repeat(3) { LockEngine.sync(ctx) }
        assertEquals(1, repo.count())
        // Even when its block is over and the window is still open, it does not start again.
        val end = Pomodoro.state.value.blockEndsAt
        FocusRunner.advance(ctx, end)
        LockEngine.sync(ctx)
        assertEquals(1, repo.count())
    }

    @Test
    fun noBlockStartsWithoutDeviceOwner() {
        storeOpenFocusEntry()
        LockEngine.sync(ctx)
        assertNull(FocusRunner.flow.value)
        assertFalse(Pomodoro.state.value.inBlock)
    }

    // ---- The old log (blueprint 13) ----

    @Test
    fun theOldLogInThePreferencesIsImportedOnceAndLeftWhereItIs() {
        val old = "1700000000000,25,1,FAR,Revenue;1700003600000,30,0"
        ctx.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).edit(commit = true) { putString("log", old) }
        Pomodoro.resetForTests()
        Pomodoro.ensureLoaded(ctx)
        assertEquals(listOf(true, false), Pomodoro.log.value.map { it.done })
        assertEquals(2, repo.count())

        Pomodoro.resetForTests()
        Pomodoro.ensureLoaded(ctx)
        assertEquals("not imported twice", 2, repo.count())
        assertEquals(2, Pomodoro.log.value.size)
        assertEquals("the old string is not touched, and nothing writes to it any more",
            old, ctx.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).getString("log", null))
    }
}
