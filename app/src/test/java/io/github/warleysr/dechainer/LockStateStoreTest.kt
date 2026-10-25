package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.LockdownInput
import io.github.warleysr.dechainer.lock.StudyInput
import io.github.warleysr.dechainer.lock.UrgeInput
import io.github.warleysr.dechainer.store.AppStateKeys
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

/** Where the urge lock lives: `app_state`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockStateStoreTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        ctx.getSharedPreferences("lock_settings", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        LockStateStore.resetForTests()
    }

    @After
    fun tearDown() {
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        LockStateStore.resetForTests()
    }

    @Test
    fun withNothingStoredThereIsNoUrgeLock() {
        assertEquals(UrgeInput(), LockStateStore.urge(ctx))
    }

    @Test
    fun anUrgeLockIsStoredWithItsStartAndEndAndSurvivesTheDatabaseBeingReopened() {
        LockStateStore.setUrge(ctx, startedAt = 1_000L, endsAt = 601_000L)
        Store.resetForTests()
        LockStateStore.resetForTests()
        assertEquals(UrgeInput(601_000L), LockStateStore.urge(ctx))
        assertEquals(1_000L, LockStateStore.startedAt(ctx))
    }

    @Test
    fun clearingTheUrgeLockRemovesBothValues() {
        LockStateStore.setUrge(ctx, 1_000L, 601_000L)
        LockStateStore.clearUrge(ctx)
        assertEquals(UrgeInput(), LockStateStore.urge(ctx))
        assertNull(Store.appState(ctx).get(AppStateKeys.URGE_LOCK_STARTED))
    }

    @Test
    fun anUnreadableValueIsNoUrgeLockAndIsLeftExactlyAsItWas() {
        Store.appState(ctx).set(AppStateKeys.URGE_LOCK_ENDS, "not a time")
        assertEquals(UrgeInput(), LockStateStore.urge(ctx))
        assertEquals("not a time", Store.appState(ctx).get(AppStateKeys.URGE_LOCK_ENDS))
    }

    @Test
    fun withNothingStoredThereIsNoStudySessionAndNoLockdown() {
        assertEquals(StudyInput(), LockStateStore.study(ctx))
        assertEquals(LockdownInput(), LockStateStore.lockdown(ctx))
    }

    @Test
    fun aStudySessionIsStoredAndSurvivesTheDatabaseBeingReopened() {
        LockStateStore.setStudy(ctx, 5_000_000L)
        Store.resetForTests()
        LockStateStore.resetForTests()
        assertEquals(StudyInput(5_000_000L), LockStateStore.study(ctx))
    }

    @Test
    fun anExtendedStudySessionReplacesTheEndTime() {
        LockStateStore.setStudy(ctx, 5_000_000L)
        LockStateStore.setStudy(ctx, 9_000_000L)
        assertEquals(StudyInput(9_000_000L), LockStateStore.study(ctx))
    }

    @Test
    fun anUnreadableStudyValueIsNoSessionAndIsLeftExactlyAsItWas() {
        Store.appState(ctx).set(AppStateKeys.STUDY_ENDS, "not a time")
        assertEquals(StudyInput(), LockStateStore.study(ctx))
        assertEquals("not a time", Store.appState(ctx).get(AppStateKeys.STUDY_ENDS))
    }

    @Test
    fun aLockdownIsStoredWithItsEndAndItsApps() {
        LockStateStore.setLockdown(ctx, 7_000_000L, setOf("com.whatsapp", "com.google.android.youtube"))
        Store.resetForTests()
        LockStateStore.resetForTests()
        assertEquals(
            LockdownInput(7_000_000L, setOf("com.whatsapp", "com.google.android.youtube")),
            LockStateStore.lockdown(ctx)
        )
    }

    @Test
    fun aLockdownKeepsItsAppsForTheNextOneAndItsEndIsOnlyAPastTime() {
        // Nothing clears a lockdown when it ends: its end time simply passes, and the list stays for next time.
        LockStateStore.setLockdown(ctx, 1_000L, setOf("a", "b"))
        val stored = LockStateStore.lockdown(ctx)
        assertEquals(setOf("a", "b"), stored.apps)
        assertEquals(1_000L, stored.endsAt)
    }

    @Test
    fun clearingStudyAndLockdownRemovesThem() {
        LockStateStore.setStudy(ctx, 5_000_000L)
        LockStateStore.setLockdown(ctx, 7_000_000L, setOf("a"))
        LockStateStore.clearStudy(ctx)
        LockStateStore.clearLockdown(ctx)
        assertEquals(StudyInput(), LockStateStore.study(ctx))
        assertEquals(LockdownInput(), LockStateStore.lockdown(ctx))
        assertNull(Store.appState(ctx).get(AppStateKeys.STUDY_ENDS))
        assertNull(Store.appState(ctx).get(AppStateKeys.LOCKDOWN_APPS))
    }

    @Test
    fun theUrgeLockStudyAndLockdownDoNotTouchEachOther() {
        LockStateStore.setUrge(ctx, 1_000L, 601_000L)
        LockStateStore.setStudy(ctx, 5_000_000L)
        LockStateStore.clearStudy(ctx)
        assertEquals(UrgeInput(601_000L), LockStateStore.urge(ctx))
    }

    @Test
    fun theUpdateRemovesWhatAnOlderBuildStoredForThePunishmentDay() {
        Store.appState(ctx).setAll(mapOf("punishmentFrom" to "1", "punishmentUntil" to "2", "punishmentDate" to "2026-10-01"))
        ctx.getSharedPreferences("lock_settings", Context.MODE_PRIVATE).edit(commit = true) { putStringSet("punishment_allowed_apps", setOf("notes")) }
        LockStateStore.clearLegacyPunishment(ctx)
        AppStateKeys.LEGACY_PUNISHMENT_KEYS.forEach { assertNull(it, Store.appState(ctx).get(it)) }
        assertFalse(ctx.getSharedPreferences("lock_settings", Context.MODE_PRIVATE).contains("punishment_allowed_apps"))
        // The urge lock is not touched by it.
        LockStateStore.setUrge(ctx, 1_000L, 601_000L)
        LockStateStore.clearLegacyPunishment(ctx)
        assertEquals(UrgeInput(601_000L), LockStateStore.urge(ctx))
    }
}
