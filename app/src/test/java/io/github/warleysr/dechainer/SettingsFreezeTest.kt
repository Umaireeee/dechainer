package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.ScheduleRepository
import io.github.warleysr.dechainer.data.TimeLimits
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.PunishmentInput
import io.github.warleysr.dechainer.models.BlockSchedule
import io.github.warleysr.dechainer.security.SecurityManager
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

/**
 * The settings freeze (blueprint 5.5), at the repository layer: every function that writes a
 * setting refuses on a punishment day, and nothing refuses reading. Acceptance check of Phase 2:
 * a settings write during PUNISHMENT_DAY is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsFreezeTest {
    private lateinit var ctx: Context
    private val hour = 3_600_000L

    private val prefsToClear = listOf(
        "schedule_prefs", "app_time_limits", "security_prefs", "recovery_prefs", "pomodoro", "dns_guard", "lock_settings"
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        prefsToClear.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        LockStateStore.resetForTests()
        Pomodoro.resetForTests()
    }

    @After
    fun tearDown() {
        Pomodoro.abortBlock(ctx)
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        prefsToClear.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        LockStateStore.resetForTests()
        Pomodoro.resetForTests()
    }

    private fun startDay() {
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - hour, now + hour), "today")
    }

    private fun endDay() {
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - 3 * hour, now - hour), "today")
    }

    private fun schedule(id: String) = BlockSchedule(id, id, packages = setOf("games"), startMinute = 60, endMinute = 120)

    @Test
    fun schedulesCannotBeAddedEditedOrDeletedOnAPunishmentDay() {
        assertTrue(ScheduleRepository.upsert(ctx, schedule("a")))
        startDay()
        assertFalse(ScheduleRepository.upsert(ctx, schedule("b")))
        assertFalse(ScheduleRepository.upsert(ctx, schedule("a").copy(name = "renamed")))
        assertFalse(ScheduleRepository.delete(ctx, "a"))
        // Reading is allowed, and nothing changed.
        val read = ScheduleRepository.getSchedules(ctx)
        assertEquals(listOf("a"), read.map { it.id })
        assertEquals("a", read.single().name)
    }

    @Test
    fun schedulesChangeAgainOnceTheDayIsOver() {
        startDay()
        assertFalse(ScheduleRepository.upsert(ctx, schedule("a")))
        endDay()
        assertTrue(ScheduleRepository.upsert(ctx, schedule("a")))
        assertEquals(1, ScheduleRepository.getSchedules(ctx).size)
    }

    @Test
    fun dailyLimitsCannotBeChangedOnAPunishmentDayNotEvenLowered() {
        TimeLimits.set(ctx, "games", 60)
        startDay()
        TimeLimits.set(ctx, "games", 30)
        TimeLimits.set(ctx, "games", 0)
        TimeLimits.set(ctx, "chrome", 10)
        assertEquals(mapOf("games" to 60), TimeLimits.all(ctx))
    }

    @Test
    fun securitySettingsCannotBeChangedOnAPunishmentDay() {
        SecurityManager.setUnlockDelayMinutes(ctx, 5)
        SecurityManager.setShuffleKeyboardEnabled(ctx, true)
        startDay()
        SecurityManager.setUnlockDelayMinutes(ctx, 0)
        SecurityManager.setShuffleKeyboardEnabled(ctx, false)
        SecurityManager.saveRecoveryCode(ctx, "ABCDEFGHIJKLMNOP")
        assertEquals(5, SecurityManager.getUnlockDelayMinutes(ctx))
        assertTrue(SecurityManager.isShuffleKeyboardEnabled(ctx))
        assertFalse("no recovery code was saved", SecurityManager.hasRecoveryCode(ctx))
        endDay()
        SecurityManager.saveRecoveryCode(ctx, "ABCDEFGHIJKLMNOP")
        assertTrue(SecurityManager.hasRecoveryCode(ctx))
    }

    @Test
    fun theEntryChallengeCannotBeChangedOnAPunishmentDayAndKeepsItsOldStoredKey() {
        SecurityManager.setEntryChallenge(ctx, SecurityManager.EntryChallenge.NORMAL)
        // The key predates the rename, so an owner's choice survives the update.
        assertEquals("NORMAL", ctx.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).getString("impulse_lock_mode", null))
        startDay()
        SecurityManager.setEntryChallenge(ctx, SecurityManager.EntryChallenge.OFF)
        assertEquals(SecurityManager.EntryChallenge.NORMAL, SecurityManager.getEntryChallenge(ctx))
        endDay()
        SecurityManager.setEntryChallenge(ctx, SecurityManager.EntryChallenge.HARD)
        assertEquals(SecurityManager.EntryChallenge.HARD, SecurityManager.getEntryChallenge(ctx))
    }

    @Test
    fun theFourDayRemovalCannotBeStartedOnAPunishmentDay() {
        startDay()
        SecurityManager.startForcedRemoval(ctx)
        assertEquals("not started", -1L, SecurityManager.getForcedRemovalRemainingTime(ctx))
        endDay()
        SecurityManager.startForcedRemoval(ctx)
        assertTrue(SecurityManager.getForcedRemovalRemainingTime(ctx) > 0L)
    }

    @Test
    fun focusSettingsAndTheFocusAllowListCannotBeChangedOnAPunishmentDay() {
        Pomodoro.ensureLoaded(ctx)
        val before = Pomodoro.settings.value
        startDay()
        Pomodoro.updateSettings(ctx, before.copy(focusMinutes = 50))
        Pomodoro.toggleAllowedApp(ctx, "notes")
        Pomodoro.addTag(ctx, "Maths")
        assertEquals(before, Pomodoro.settings.value)
        assertTrue(Pomodoro.allowedApps.value.isEmpty())
        assertTrue(Pomodoro.tags.value.isEmpty())
        endDay()
        Pomodoro.toggleAllowedApp(ctx, "notes")
        assertEquals(setOf("notes"), Pomodoro.allowedApps.value)
    }

    @Test
    fun aManualFocusBlockCannotBeStartedOnAPunishmentDay() {
        startDay()
        assertFalse(Pomodoro.startBlock(ctx, TrustedClock.now(ctx) + 60 * 60_000L))
        assertFalse(Pomodoro.state.value.inBlock)
        endDay()
        assertTrue(Pomodoro.startBlock(ctx, TrustedClock.now(ctx) + 60 * 60_000L))
    }

    @Test
    fun theDnsFilterChoiceCannotBeChangedOnAPunishmentDay() {
        DnsGuard.remember(ctx, "dns.one.example")
        startDay()
        DnsGuard.forget(ctx)
        DnsGuard.remember(ctx, "dns.two.example")
        assertEquals("dns.one.example", DnsGuard.pinnedHost(ctx))
    }

    @Test
    fun theStudyAppListCannotBeChangedOnAPunishmentDay() {
        startDay()
        assertFalse(LockStateStore.setPunishmentOwnerApps(ctx, setOf("notes")))
        assertTrue(LockStateStore.punishmentOwnerApps(ctx).isEmpty())
    }
}
