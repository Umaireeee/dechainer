package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.PunishmentInput
import io.github.warleysr.dechainer.security.AppLock
import io.github.warleysr.dechainer.security.AppLockKind
import io.github.warleysr.dechainer.security.AppLockRules
import io.github.warleysr.dechainer.security.UnlockResult
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app lock on a phone: a hashed secret, counted wrong tries that survive a restart, and the freeze. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLockTest {
    private lateinit var ctx: Context
    private val prefs = listOf("app_lock_prefs", "lock_settings")

    private fun clean() {
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        prefs.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        LockStateStore.resetForTests()
        AppLock.resetForTests()
    }

    @Before
    fun setUp() { ctx = ApplicationProvider.getApplicationContext(); clean() }

    @After
    fun tearDown() = clean()

    @Test
    fun withNoLockTheAppIsNeverLocked() {
        assertFalse(AppLock.isEnabled(ctx))
        assertFalse(AppLock.isLocked(ctx))
        assertNull(AppLock.kind(ctx))
    }

    @Test
    fun aPinCanBeSetAndLocksTheNextTime() {
        assertTrue(AppLock.set(ctx, AppLockKind.PIN, "4821"))
        assertTrue(AppLock.isEnabled(ctx))
        assertEquals(AppLockKind.PIN, AppLock.kind(ctx))
        assertFalse("whoever set it is already in", AppLock.isLocked(ctx))
        AppLock.lock()
        assertTrue(AppLock.isLocked(ctx))
        assertEquals(UnlockResult.Unlocked, AppLock.tryUnlock(ctx, "4821"))
        assertFalse(AppLock.isLocked(ctx))
    }

    @Test
    fun aNewProcessStartsLockedAndRemembersTheLock() {
        AppLock.set(ctx, AppLockKind.PATTERN, "01258")
        AppLock.resetForTests() // what a restart does: the memory is gone, the preferences stay
        assertTrue(AppLock.isLocked(ctx))
        assertEquals(AppLockKind.PATTERN, AppLock.kind(ctx))
        assertEquals(UnlockResult.Unlocked, AppLock.tryUnlock(ctx, "01258"))
    }

    @Test
    fun theSecretIsStoredAsAHashNeverAsText() {
        AppLock.set(ctx, AppLockKind.PIN, "4821")
        val all = ctx.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE).all.values.joinToString("|")
        assertFalse("4821" in all)
        assertTrue(all.contains("v1$"))
    }

    @Test
    fun weakOrBadSecretsAreRefused() {
        assertFalse(AppLock.set(ctx, AppLockKind.PIN, "1234"))
        assertFalse(AppLock.set(ctx, AppLockKind.PIN, "11"))
        assertFalse(AppLock.set(ctx, AppLockKind.PATTERN, "012"))
        assertFalse(AppLock.set(ctx, AppLockKind.PATTERN, "0120"))
        assertFalse(AppLock.set(ctx, AppLockKind.PATTERN, "01239x"))
        assertFalse(AppLock.isEnabled(ctx))
    }

    @Test
    fun wrongTriesAreCountedAndTheFifthMakesTheAppWait() {
        AppLock.set(ctx, AppLockKind.PIN, "4821"); AppLock.lock()
        for (n in 1..4) {
            val r = AppLock.tryUnlock(ctx, "0001") as UnlockResult.Wrong
            assertEquals(AppLockRules.freeTriesLeft(n), r.freeTriesLeft)
            assertEquals(0L, r.waitMs)
        }
        val fifth = AppLock.tryUnlock(ctx, "0001") as UnlockResult.Wrong
        assertEquals(30_000L, fifth.waitMs)
        assertTrue(AppLock.waitRemainingMs(ctx) > 0L)
        // While it waits even the right secret is refused.
        assertTrue(AppLock.tryUnlock(ctx, "4821") is UnlockResult.WaitFirst)
        assertTrue(AppLock.isLocked(ctx))
    }

    @Test
    fun theCountSurvivesARestart() {
        AppLock.set(ctx, AppLockKind.PIN, "4821"); AppLock.lock()
        repeat(5) { AppLock.tryUnlock(ctx, "0001") }
        AppLock.resetForTests()
        assertEquals(5, AppLock.failures(ctx))
        assertTrue(AppLock.tryUnlock(ctx, "4821") is UnlockResult.WaitFirst)
    }

    @Test
    fun aRightTryClearsTheCount() {
        AppLock.set(ctx, AppLockKind.PIN, "4821"); AppLock.lock()
        repeat(3) { AppLock.tryUnlock(ctx, "0001") }
        assertEquals(UnlockResult.Unlocked, AppLock.tryUnlock(ctx, "4821"))
        assertEquals(0, AppLock.failures(ctx))
    }

    @Test
    fun removingItTurnsTheLockOff() {
        AppLock.set(ctx, AppLockKind.PIN, "4821"); AppLock.lock()
        assertTrue(AppLock.remove(ctx))
        assertFalse(AppLock.isEnabled(ctx))
        assertFalse(AppLock.isLocked(ctx))
        assertEquals(0, AppLock.failures(ctx))
    }

    @Test
    fun onAPunishmentDayTheLockCannotBeSetChangedOrRemoved() {
        AppLock.set(ctx, AppLockKind.PIN, "4821")
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - 3_600_000L, now + 3_600_000L), "today")
        assertFalse(AppLock.set(ctx, AppLockKind.PIN, "7391"))
        assertFalse(AppLock.remove(ctx))
        assertTrue(AppLock.isEnabled(ctx))
        AppLock.lock()
        assertEquals("reading and trying still work", UnlockResult.Unlocked, AppLock.tryUnlock(ctx, "4821"))
    }
}
