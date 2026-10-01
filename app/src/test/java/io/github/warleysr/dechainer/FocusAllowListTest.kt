package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A running focus block cannot be loosened: no app can be added to its allow list and its settings do not change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FocusAllowListTest {
    private lateinit var ctx: Context

    private fun clean() {
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        listOf("pomodoro", "lock_settings").forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        LockStateStore.resetForTests()
        Pomodoro.resetForTests()
    }

    @Before fun setUp() { ctx = ApplicationProvider.getApplicationContext(); clean() }
    @After fun tearDown() = clean()

    @Test fun anAppCanBeAddedOutsideABlockAndRemovedAnytime() {
        assertTrue(Pomodoro.toggleAllowedApp(ctx, "notes"))
        assertEquals(setOf("notes"), Pomodoro.allowedApps.value)
        assertTrue(Pomodoro.toggleAllowedApp(ctx, "notes"))
        assertTrue(Pomodoro.allowedApps.value.isEmpty())
    }

    @Test fun duringABlockNothingCanBeAddedButAnAppCanStillBeTakenOff() {
        assertTrue(Pomodoro.toggleAllowedApp(ctx, "notes"))
        val ends = TrustedClock.now(ctx) + 60 * 60_000L
        assertTrue(Pomodoro.startBlock(ctx, ends, phases = false))
        assertTrue(Pomodoro.brickActive())

        assertFalse("adding loosens a running block", Pomodoro.toggleAllowedApp(ctx, "instagram"))
        assertEquals(setOf("notes"), Pomodoro.allowedApps.value)
        assertTrue("taking one off tightens", Pomodoro.toggleAllowedApp(ctx, "notes"))
        assertTrue(Pomodoro.allowedApps.value.isEmpty())
    }

    @Test fun duringABlockTheSettingsDoNotChange() {
        val before = Pomodoro.settings.value
        assertTrue(Pomodoro.startBlock(ctx, TrustedClock.now(ctx) + 60 * 60_000L, phases = false))
        Pomodoro.updateSettings(ctx, before.copy(focusMinutes = 50, brickBlocks = false))
        assertEquals(before, Pomodoro.settings.value)
    }
}
