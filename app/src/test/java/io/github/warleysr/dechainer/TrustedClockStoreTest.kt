package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.security.ForcedRemovalClock
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The trusted clock with its real store: the checkpoint is written, and a later process builds on it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrustedClockStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Store.resetForTests()
        TrustedClock.resetForTests()
        context.deleteDatabase(DechainerDatabase.FILE_NAME)
    }

    @After
    fun tearDown() {
        Store.resetForTests()
        TrustedClock.resetForTests()
        context.deleteDatabase(DechainerDatabase.FILE_NAME)
    }

    @Test
    fun aWakeUpWritesTheCheckpointBeforeReturning() {
        val before = System.currentTimeMillis()
        val t = TrustedClock.checkpoint(context)
        assertTrue(t >= before)
        val state = Store.appState(context)
        assertEquals(t, state.getLong(AppStateKeys.LAST_SEEN_WALL))
        assertNotNull(state.getLong(AppStateKeys.LAST_SEEN_ELAPSED))
        assertNotNull(state.getInt(AppStateKeys.LAST_SEEN_BOOT))
    }

    @Test
    fun aStoredCheckpointInTheFutureIsNotGoneBackBehind() {
        // Another "process" stored a reading an hour ahead of the wall clock: the wall clock was
        // moved back since. The clock must carry on from the stored reading.
        val future = System.currentTimeMillis() + 3_600_000L
        Store.appState(context).setAll(
            mapOf(
                AppStateKeys.LAST_SEEN_WALL to future.toString(),
                AppStateKeys.LAST_SEEN_ELAPSED to SystemClock.elapsedRealtime().toString(),
                AppStateKeys.LAST_SEEN_BOOT to ForcedRemovalClock.UNKNOWN_BOOT.toString()
            )
        )
        val t = TrustedClock.checkpoint(context)
        assertTrue("trusted $t must not be behind the stored $future", t >= future)
        assertTrue("and it is only a little ahead of it", t < future + 60_000L)
    }

    @Test
    fun aHalfWrittenCheckpointIsIgnoredNotTrusted() {
        Store.appState(context).set(AppStateKeys.LAST_SEEN_WALL, (System.currentTimeMillis() + 3_600_000L).toString())
        val before = System.currentTimeMillis()
        val t = TrustedClock.checkpoint(context)
        assertTrue("falls back to the wall time", t in before..(System.currentTimeMillis() + 1))
    }

    @Test
    fun nowNeverGoesBackwardsAcrossCalls() {
        var previous = TrustedClock.now(context)
        repeat(50) {
            val t = TrustedClock.now(context)
            assertTrue(t >= previous)
            previous = t
        }
    }
}
