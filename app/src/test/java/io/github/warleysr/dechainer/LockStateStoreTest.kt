package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.PunishmentInput
import io.github.warleysr.dechainer.lock.SettingsFreeze
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

/** Where the urge lock and the punishment day live: `app_state`, with the study-app list in preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockStateStoreTest {
    private lateinit var ctx: Context
    private val hour = 3_600_000L

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
    fun withNothingStoredThereIsNoUrgeLockAndNoPunishmentDay() {
        assertEquals(UrgeInput(), LockStateStore.urge(ctx))
        assertFalse(LockStateStore.punishment(ctx).activeAt(TrustedClock.now(ctx)))
        assertFalse(SettingsFreeze.isFrozen(ctx))
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
    fun aPunishmentDayIsStoredAsItsWindowAndItsDate() {
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - hour, now + hour), "2026-10-01")
        val read = LockStateStore.punishment(ctx)
        assertEquals(now - hour, read.startsAt)
        assertEquals(now + hour, read.endsAt)
        assertTrue(read.activeAt(now))
        assertEquals("2026-10-01", Store.appState(ctx).get(AppStateKeys.PUNISHMENT_DATE))
    }

    @Test
    fun theFreezeFollowsTheWindowAndNothingElse() {
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now + hour, now + 2 * hour), "later")
        assertFalse("not started", SettingsFreeze.isFrozen(ctx))
        LockStateStore.setPunishment(ctx, PunishmentInput(now - hour, now + hour), "now")
        assertTrue("running", SettingsFreeze.isFrozen(ctx))
        LockStateStore.setPunishment(ctx, PunishmentInput(now - 2 * hour, now - hour), "earlier")
        assertFalse("over", SettingsFreeze.isFrozen(ctx))
    }

    @Test
    fun theFreezeNeverStopsAPunishmentFromBeingRecordedOrChangedByTheSystem() {
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - hour, now + hour), "now")
        // Recording a second one while frozen goes through: only settings are refused.
        LockStateStore.setPunishment(ctx, PunishmentInput(now - hour, now + 3 * hour), "now")
        assertEquals(now + 3 * hour, LockStateStore.punishment(ctx).endsAt)
    }

    @Test
    fun theSystemCanEndAPunishmentDayAndMarksItButNeverLengthensOne() {
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - hour, now + hour), "now")
        LockStateStore.endPunishmentBySystem(ctx, now)
        assertEquals(now, LockStateStore.punishment(ctx).endsAt)
        assertFalse(SettingsFreeze.isFrozen(ctx))
        assertEquals(now.toString(), Store.appState(ctx).get(AppStateKeys.PUNISHMENT_ABORTED_AT))
        // Asked again at a later time, it does not stretch the window back out.
        LockStateStore.endPunishmentBySystem(ctx, now + hour)
        assertEquals(now, LockStateStore.punishment(ctx).endsAt)
    }

    @Test
    fun theStudyAppListIsEmptyByDefaultAndChangesOnlyWhenNotFrozen() {
        assertTrue(LockStateStore.punishmentOwnerApps(ctx).isEmpty())
        assertTrue(LockStateStore.setPunishmentOwnerApps(ctx, setOf("notes")))
        assertEquals(setOf("notes"), LockStateStore.punishment(ctx).ownerApps)

        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - hour, now + hour), "now")
        assertFalse("refused on a punishment day", LockStateStore.setPunishmentOwnerApps(ctx, setOf("notes", "games")))
        assertEquals(setOf("notes"), LockStateStore.punishmentOwnerApps(ctx))
    }
}
