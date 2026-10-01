package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first Phase 1 acceptance check on the real stored state: a block whose end alarm never fired
 * is ended by the next wake-up, which only has the stored data and the clock to go on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PomodoroExpiryTest {
    private lateinit var context: Context
    private val minute = 60_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Store.resetForTests()
        TrustedClock.resetForTests()
        context.deleteDatabase(DechainerDatabase.FILE_NAME)
        context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        Pomodoro.resetForTests()
    }

    @After
    fun tearDown() {
        Store.resetForTests()
        TrustedClock.resetForTests()
        context.deleteDatabase(DechainerDatabase.FILE_NAME)
        context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        Pomodoro.resetForTests()
    }

    /** A focus block as the Pomodoro stores it: a running 25 minute session inside a block. */
    private fun storeBlock(phaseEndsAt: Long, blockEndsAt: Long) {
        context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).edit(commit = true) {
            putString("phase", "FOCUS")
            putLong("ends_at", phaseEndsAt)
            putLong("paused_remaining", 0L)
            putLong("phase_started_at", phaseEndsAt - 25 * minute)
            putInt("focus_done_in_cycle", 0)
            putInt("planned_minutes", 25)
            putLong("block_ends_at", blockEndsAt)
        }
    }

    private fun storedBlockEnd() = context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).getLong("block_ends_at", -1L)

    @Test
    fun aBlockWhoseAlarmNeverFiredIsEndedByTheNextWakeUp() {
        val now = TrustedClock.now(context)
        storeBlock(phaseEndsAt = now - 40 * minute, blockEndsAt = now - 30 * minute)
        Pomodoro.ensureLoaded(context)

        // The stored state still says a block is running...
        assertTrue(Pomodoro.state.value.inBlock)
        // ...but the time already says it is over: nothing is bricked.
        assertFalse(Pomodoro.brickActive())

        // The wake-up closes it.
        assertTrue(Pomodoro.closeExpiredBlock(context, now))
        assertFalse(Pomodoro.state.value.inBlock)
        assertTrue(Pomodoro.state.value.isIdle)
        assertEquals("the stored block is cleared on disk", 0L, storedBlockEnd())
        assertFalse(Pomodoro.brickActive())

        // The focus session that ran is logged, with its question left pending for the Focus tab.
        assertEquals(1, Pomodoro.log.value.size)
        assertNotNull(Pomodoro.pendingQuestion.value)
    }

    @Test
    fun closingItAgainDoesNothing() {
        val now = TrustedClock.now(context)
        storeBlock(now - 40 * minute, now - 30 * minute)
        Pomodoro.ensureLoaded(context)
        assertTrue(Pomodoro.closeExpiredBlock(context, now))
        assertFalse(Pomodoro.closeExpiredBlock(context, now))
        assertEquals("not logged twice", 1, Pomodoro.log.value.size)
    }

    @Test
    fun aBlockThatIsStillRunningIsLeftAlone() {
        val now = TrustedClock.now(context)
        storeBlock(phaseEndsAt = now + 10 * minute, blockEndsAt = now + 60 * minute)
        Pomodoro.ensureLoaded(context)
        assertTrue(Pomodoro.brickActive())
        assertFalse(Pomodoro.closeExpiredBlock(context, now))
        assertTrue(Pomodoro.state.value.inBlock)
        assertEquals(now + 60 * minute, storedBlockEnd())
    }

    @Test
    fun theCrashLoopAbortDropsARunningBlock() {
        val now = TrustedClock.now(context)
        storeBlock(phaseEndsAt = now + 10 * minute, blockEndsAt = now + 60 * minute)
        Pomodoro.ensureLoaded(context)
        assertTrue(Pomodoro.brickActive())
        assertEquals(now + 60 * minute, Pomodoro.storedBlockEndsAt(context))

        Pomodoro.abortBlock(context)

        assertFalse(Pomodoro.brickActive())
        assertFalse(Pomodoro.state.value.inBlock)
        assertEquals("cleared on disk, so a restart does not bring it back", 0L, Pomodoro.storedBlockEndsAt(context))
        assertTrue("an abort logs nothing", Pomodoro.log.value.isEmpty())
    }

    @Test
    fun theAbortWorksEvenIfTheStateWasNeverLoadedInThisProcess() {
        val now = TrustedClock.now(context)
        storeBlock(now + 10 * minute, now + 60 * minute)
        // No ensureLoaded: the crash may have happened before the Pomodoro was ever read.
        Pomodoro.abortBlock(context)
        assertEquals(0L, Pomodoro.storedBlockEndsAt(context))
        Pomodoro.ensureLoaded(context)
        assertFalse(Pomodoro.brickActive())
    }
}
