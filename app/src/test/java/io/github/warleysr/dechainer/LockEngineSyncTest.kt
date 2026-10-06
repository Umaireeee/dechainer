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
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockMode
import io.github.warleysr.dechainer.lock.LockRestrictions
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.UrgeStart
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `LockEngine.sync` end to end on stored state, with a Device Owner shadow: what the owner's phone
 * does at a wake-up. The real [DechainerApplication] is the application here, because the blocking
 * code reaches for it. The engine runs on its own thread and `sync` waits for a pass that began after
 * the call, so each assertion sees the result of the state it just stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DechainerApplication::class)
class LockEngineSyncTest {
    private lateinit var ctx: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val games = "com.example.games"
    private val minute = 60_000L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        listOf(
            "pomodoro", "schedule_state", "schedule_prefs", "security_prefs", "lock_settings", "crash_guard",
            "app_time_limits", "app_time_limits_reached"
        ).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        Pomodoro.resetForTests()
        Store.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        LockStateStore.resetForTests()

        dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)

        // One installed app with a launcher icon, so a brick has something to take away.
        val info = PackageInfo().apply {
            packageName = games
            applicationInfo = ApplicationInfo().apply { packageName = games; name = "Games" }
        }
        shadowOf(ctx.packageManager).installPackage(info)
        val launcher = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = games; name = "$games.Main"; applicationInfo = info.applicationInfo }
        }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), launcher
        )
        // The launcher list and the protected list are cached for a few minutes.
        ScheduleEnforcer.invalidateProtectedPackages()
    }

    private fun makeDeviceOwner() = shadowOf(dpm).setDeviceOwner(admin)

    /** A focus block as the Pomodoro stores it: a running 25 minute session inside a block. */
    private fun storeBlock(phaseEndsAt: Long, blockEndsAt: Long) {
        ctx.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).edit(commit = true) {
            putString("phase", "FOCUS")
            putLong("ends_at", phaseEndsAt)
            putLong("paused_remaining", 0L)
            putLong("phase_started_at", phaseEndsAt - 25 * minute)
            putInt("focus_done_in_cycle", 0)
            putInt("planned_minutes", 25)
            putLong("block_ends_at", blockEndsAt)
        }
        // Read it again, as a new process would.
        Pomodoro.resetForTests()
    }

    @Test
    fun aBlockWhoseAlarmNeverFiredIsEndedAndItsAppsReleasedByTheNextWakeUp() {
        makeDeviceOwner()
        val now = TrustedClock.now(ctx)

        // A block is running: the brick takes the app away.
        storeBlock(phaseEndsAt = now + 10 * minute, blockEndsAt = now + 30 * minute)
        LockEngine.sync(ctx)
        assertTrue("the brick suspended the app", dpm.isPackageSuspended(admin, games))
        assertTrue(Pomodoro.brickActive())

        // The end alarm is lost. Nothing ever closes the block; the wake-up only has the stored
        // state and the clock. The block's end is now in the past.
        storeBlock(phaseEndsAt = now - 40 * minute, blockEndsAt = now - 30 * minute)
        Pomodoro.ensureLoaded(ctx)
        assertTrue("the stored state still says a block is running", Pomodoro.state.value.inBlock)
        assertFalse("but the time already says it is over", Pomodoro.brickActive())
        LockEngine.sync(ctx)

        assertFalse("the app is released", dpm.isPackageSuspended(admin, games))
        assertFalse(Pomodoro.brickActive())
        assertFalse("and the stored block is closed", Pomodoro.state.value.inBlock)
        assertEquals(0L, ctx.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).getLong("block_ends_at", -1L))
    }

    @Test
    fun aBlockThatIsStillRunningSurvivesAnotherWakeUp() {
        makeDeviceOwner()
        val now = TrustedClock.now(ctx)
        storeBlock(phaseEndsAt = now + 10 * minute, blockEndsAt = now + 30 * minute)
        repeat(3) { LockEngine.sync(ctx) }
        assertTrue(dpm.isPackageSuspended(admin, games))
        assertTrue(Pomodoro.state.value.inBlock)
    }

    @Test
    fun anEndedBlockIsClosedFromTheTimeEvenWithoutDeviceOwner() {
        val now = TrustedClock.now(ctx)
        storeBlock(phaseEndsAt = now - 40 * minute, blockEndsAt = now - 30 * minute)
        LockEngine.sync(ctx)
        assertFalse(Pomodoro.state.value.inBlock)
        assertTrue("nothing was ever taken without Device Owner", ScheduleEnforcer.ownedApps(ctx).isEmpty())
    }

    @Test
    fun theBricksSafetyRestrictionsComeAndGoWithTheBlockAndTheDateLockStays() {
        makeDeviceOwner()
        val now = TrustedClock.now(ctx)
        storeBlock(phaseEndsAt = now + 10 * minute, blockEndsAt = now + 30 * minute)
        LockEngine.sync(ctx)
        // Robolectric's Device Owner shadow does not reflect applied restrictions back, so this checks
        // the engine's own record of what it applied: a key is only written there after
        // addUserRestriction returned without throwing. What the OS then does is the owner's check.
        val during = ownedRestrictions()
        assertTrue(during.containsAll(LockRestrictions.BRICK))
        assertTrue(LockRestrictions.DATE_TIME in during)

        storeBlock(phaseEndsAt = now - 40 * minute, blockEndsAt = now - 30 * minute)
        LockEngine.sync(ctx)
        val after = ownedRestrictions()
        assertTrue("the brick's restrictions are released with it", after.none { it in LockRestrictions.BRICK })
        assertTrue("date, time and zone stay locked with nothing running", LockRestrictions.DATE_TIME in after)
    }

    private fun ownedRestrictions(): Set<String> =
        ctx.getSharedPreferences("schedule_state", Context.MODE_PRIVATE).getStringSet("owned_restrictions", emptySet()) ?: emptySet()

    @Test
    fun theEnginePassForcesAutomaticTimeAndTimeZone() {
        makeDeviceOwner()
        LockEngine.sync(ctx)
        assertTrue(dpm.getAutoTimeEnabled(admin))
        assertTrue(dpm.getAutoTimeZoneEnabled(admin))
        // Force stop and Clear data (setUserControlDisabledPackages) cannot be read back in Robolectric;
        // that one is the owner's check on the phone (blueprint section 15, check 1).
    }

    // ---- The urge lock and the daily checklist, end to end ----

    @Test
    fun anUrgeLockTakesTheAppAwayAndASecondTapNeverExtendsIt() {
        makeDeviceOwner()
        val first = LockEngine.startUrgeLock(ctx)
        assertTrue("it started: $first", first is UrgeStart.Started)
        val endsAt = (first as UrgeStart.Started).endsAt
        LockEngine.sync(ctx)
        assertTrue("the brick suspended the app", dpm.isPackageSuspended(admin, games))
        assertEquals(LockMode.URGE_LOCK, LockEngine.status.value?.primary)
        assertEquals(endsAt, LockEngine.status.value?.endsAt)
        assertTrue(LockEngine.brickRunning(ctx))

        assertEquals(UrgeStart.AlreadyRunning(endsAt), LockEngine.startUrgeLock(ctx))
        assertEquals("the end did not move", endsAt, LockStateStore.urge(ctx).endsAt)
    }

    @Test
    fun anUrgeLockWhoseTimeHasPassedIsOverEvenIfNoAlarmEverFired() {
        makeDeviceOwner()
        val now = TrustedClock.now(ctx)
        LockStateStore.setUrge(ctx, startedAt = now - 11 * minute, endsAt = now - minute)
        LockEngine.sync(ctx)
        assertFalse("the app is released", dpm.isPackageSuspended(admin, games))
        assertEquals(null, LockEngine.status.value)
        assertFalse(LockEngine.brickRunning(ctx))
    }

    @Test
    fun anUrgeLockWithoutDeviceOwnerLocksNothingAndSaysSo() {
        val result = LockEngine.startUrgeLock(ctx)
        assertTrue("$result", result is UrgeStart.Unavailable)
        assertEquals(0L, LockStateStore.urge(ctx).endsAt)
        assertFalse(LockEngine.brickRunning(ctx))
    }

    @Test
    fun everySyncClosesTheDaysThatEndedAndAShortDayNeverLocksThePhone() {
        makeDeviceOwner()
        val zone = TrustedClock.zone()
        val today = io.github.warleysr.dechainer.day.DayWindow.dateOf(TrustedClock.now(ctx), zone)
        // Rules confirmed three days ago, and no plan was ever written: every day fell short.
        Store.appState(ctx).set(io.github.warleysr.dechainer.store.AppStateKeys.ACTIVATED_ON, today.minusDays(3).toString())
        LockEngine.sync(ctx)
        val days = Store.days(ctx)
        val yesterday = days.day(today.minusDays(1))
        assertTrue("the sync ran the day evaluation", yesterday?.evaluated == true)
        assertEquals(io.github.warleysr.dechainer.day.Violation.PLAN_MISSING, yesterday?.violation)
        assertFalse("nothing is locked because of it", dpm.isPackageSuspended(admin, games))
        assertEquals(null, LockEngine.status.value)
    }

    @Test
    fun theCrashBreakerEndsAnUrgeLockOnRecord() {
        makeDeviceOwner()
        LockEngine.startUrgeLock(ctx)
        LockEngine.sync(ctx)
        LockEngine.abortBrick(ctx)
        assertEquals(null, LockEngine.status.value)
        assertEquals(0L, LockStateStore.urge(ctx).endsAt)
        LockEngine.sync(ctx)
        assertFalse("and it does not come back on the next pass", LockEngine.brickRunning(ctx))
    }

    private fun missedFocusEndIsSettledBeforeDayEvaluation(crossMidnight: Boolean) {
        makeDeviceOwner()
        val zone = TrustedClock.zone()
        val today = io.github.warleysr.dechainer.day.DayWindow.dateOf(TrustedClock.now(ctx), zone)
        val yesterday = today.minusDays(if (crossMidnight) 2L else 1L)
        val start = yesterday.atTime(if (crossMidnight) 23 else 21, 30).atZone(zone).toInstant().toEpochMilli()
        val end = start + 60 * minute
        val days = Store.days(ctx)
        assertTrue(days.savePlan(yesterday, listOf(
            io.github.warleysr.dechainer.day.NewGoal("Focus", io.github.warleysr.dechainer.day.GoalType.FOCUS_MINUTES, 40),
            io.github.warleysr.dechainer.day.NewGoal("No slip", io.github.warleysr.dechainer.day.GoalType.NO_SLIP),
            io.github.warleysr.dechainer.day.NewGoal("Read chapter")
        ), start - 24 * 3_600_000L))
        days.setGoal(days.goals(yesterday).last().id, io.github.warleysr.dechainer.day.GoalState.DONE,
            io.github.warleysr.dechainer.day.ResolvedBy.USER)
        Store.appState(ctx).set(io.github.warleysr.dechainer.store.AppStateKeys.ACTIVATED_ON, yesterday.toString())
        val session = Store.focus(ctx).insert(io.github.warleysr.dechainer.store.StoredSession(start,
            io.github.warleysr.dechainer.focus.FocusSource.MANUAL, io.github.warleysr.dechainer.focus.Flavor.SPECIAL,
            "Study", start, end, null, 0, null))
        val state = io.github.warleysr.dechainer.focus.FocusFlow.begin(
            io.github.warleysr.dechainer.focus.FocusSource.MANUAL, io.github.warleysr.dechainer.focus.Flavor.SPECIAL,
            "Study", start, end)!!.state.copy(sessionId = session)
        Store.appState(ctx).set(io.github.warleysr.dechainer.store.AppStateKeys.FOCUS_FLOW, state.toJson())
        io.github.warleysr.dechainer.focus.FocusRunner.resetForTests()
        LockEngine.sync(ctx)
        assertEquals(60, Store.focus(ctx).session(session)!!.focusedMinutes)
        assertEquals(io.github.warleysr.dechainer.day.GoalState.DONE,
            days.goals(yesterday).first { it.type == io.github.warleysr.dechainer.day.GoalType.FOCUS_MINUTES }.state)
        assertEquals(3, days.day(yesterday)!!.doneCount)
        assertTrue(days.day(yesterday)!!.evaluated)
        LockEngine.sync(ctx)
        assertEquals(3, days.day(yesterday)!!.doneCount)
    }

    @Test fun missedEveningFocusEndSettlesBeforeImmutableDailyResult() = missedFocusEndIsSettledBeforeDayEvaluation(false)
    @Test fun missedCrossMidnightFocusEndSettlesBeforeImmutableDailyResult() = missedFocusEndIsSettledBeforeDayEvaluation(true)
}
