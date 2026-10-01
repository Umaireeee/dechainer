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
import io.github.warleysr.dechainer.lock.LockRestrictions
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
            "pomodoro", "schedule_state", "schedule_prefs", "security_prefs", "ride_lock",
            "app_time_limits", "app_time_limits_reached"
        ).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        Pomodoro.resetForTests()

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
        assertFalse("nothing was ever suspended without Device Owner", dpm.isPackageSuspended(admin, games))
    }

    @Test
    fun theBricksSafetyRestrictionsComeAndGoWithTheBlockAndTheDateLockStays() {
        makeDeviceOwner()
        val now = TrustedClock.now(ctx)
        storeBlock(phaseEndsAt = now + 10 * minute, blockEndsAt = now + 30 * minute)
        LockEngine.sync(ctx)
        val during = dpm.getUserRestrictions(admin)
        assertTrue(during.getBoolean(LockRestrictions.SAFE_BOOT))
        assertTrue(during.getBoolean(LockRestrictions.FACTORY_RESET))
        assertTrue(during.getBoolean(LockRestrictions.DATE_TIME))

        storeBlock(phaseEndsAt = now - 40 * minute, blockEndsAt = now - 30 * minute)
        LockEngine.sync(ctx)
        val after = dpm.getUserRestrictions(admin)
        assertFalse(after.getBoolean(LockRestrictions.SAFE_BOOT))
        assertFalse(after.getBoolean(LockRestrictions.FACTORY_RESET))
        assertTrue("date, time and zone stay locked with nothing running", after.getBoolean(LockRestrictions.DATE_TIME))
    }

    @Test
    fun theEnginePassForcesAutomaticTimeAndDisablesForceStopForThisApp() {
        makeDeviceOwner()
        LockEngine.sync(ctx)
        assertTrue(dpm.getAutoTimeEnabled(admin))
        assertTrue(dpm.getAutoTimeZoneEnabled(admin))
        assertTrue(ctx.packageName in dpm.getUserControlDisabledPackages(admin))
    }
}
