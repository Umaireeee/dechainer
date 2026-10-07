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
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.store.UrgeEntryRepository
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeFlow
import io.github.warleysr.dechainer.urge.UrgeSettings
import io.github.warleysr.dechainer.urge.UrgeSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The blackout flow end to end on the real store and the real lock engine: the lock starts at once
 * and the blackout runs for the chosen length. There is no AI, no note, no questions and no deep dive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DechainerApplication::class)
class UrgeFlowTest {
    private lateinit var ctx: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val games = "com.example.games"
    private val minute = 60_000L

    private fun flow(repository: (() -> UrgeEntryRepository)? = null) =
        UrgeFlow(ctx, repository = repository ?: { Store.urgeEntries(ctx) })

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        listOf(
            "pomodoro", "schedule_state", "schedule_prefs", "security_prefs", "lock_settings", "crash_guard",
            "app_time_limits", "app_time_limits_reached", "ai", "urge_settings"
        ).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        Pomodoro.resetForTests()
        Store.clearForTests(ctx)
        LockStateStore.resetForTests()
        dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)

        val info = PackageInfo().apply {
            packageName = games
            applicationInfo = ApplicationInfo().apply { packageName = games; name = "Games" }
        }
        shadowOf(ctx.packageManager).installPackage(info)
        val launcher = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = games; name = "$games.Main"; applicationInfo = info.applicationInfo }
        }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), launcher)
        ScheduleEnforcer.invalidateProtectedPackages()
    }

    @After
    fun tearDown() {
        LockStateStore.resetForTests()
    }

    private fun makeDeviceOwner() = shadowOf(dpm).setDeviceOwner(admin)

    private val repo get() = Store.urgeEntries(ctx)

    private fun fresh(e: UrgeEntry): UrgeEntry = repo.get(e.id)!!

    @Test
    fun anUrgeLocksAtOnceAndStoresItsEntry() {
        makeDeviceOwner()
        val e = flow().startUrge(UrgeSource.HOME)

        assertTrue(e.id > 0)
        assertEquals(UrgeSource.HOME, fresh(e).source)
        val lock = LockStateStore.urge(ctx)
        assertEquals(e.lockEndedAt, lock.endsAt)
        assertEquals(e.lockStartedAt, LockStateStore.startedAt(ctx))
        assertEquals(10 * minute, lock.endsAt - e.lockStartedAt!!)
        assertEquals(e.lockStartedAt, fresh(e).lockStartedAt)
        assertEquals(e.lockEndedAt, fresh(e).lockEndedAt)

        // And the next wake-up really takes the apps away.
        LockEngine.sync(ctx)
        assertTrue("the urge lock suspended the app", dpm.isPackageSuspended(admin, games))
    }

    @Test
    fun theChosenLengthIsUsed() {
        makeDeviceOwner()
        UrgeSettings(ctx).setDurationMinutes(25)
        val e = flow().startUrge(UrgeSource.TILE)
        assertEquals(25 * minute, e.lockEndedAt!! - e.lockStartedAt!!)
        assertEquals(25 * minute, LockStateStore.urge(ctx).endsAt - e.lockStartedAt!!)
    }

    @Test
    fun aSecondTapDuringTheBlackoutIsTheSameUrgeAndNeverExtendsTheLock() {
        makeDeviceOwner()
        val f = flow()
        val first = f.startUrge(UrgeSource.HOME)
        val endsAt = LockStateStore.urge(ctx).endsAt
        val second = f.startUrge(UrgeSource.TILE)

        assertEquals(first.id, second.id)
        assertEquals(1, repo.count())
        assertEquals("not extended", endsAt, LockStateStore.urge(ctx).endsAt)
        assertEquals(first.lockEndedAt, second.lockEndedAt)
    }

    @Test
    fun withoutDeviceOwnerTheBlackoutStillRunsAndNoLockIsStored() {
        val e = flow().startUrge(UrgeSource.HOME)
        assertEquals(10 * minute, e.lockEndedAt!! - e.lockStartedAt!!)
        assertEquals("nothing can be locked", 0L, LockStateStore.urge(ctx).endsAt)
    }

    @Test
    fun aLockStartedOutsideGetsItsCountedEntryAndBreathesForWhatIsLeft() {
        makeDeviceOwner()
        // Started from outside (the tile) with no entry.
        LockEngine.startUrgeLock(ctx)
        val endsAt = LockStateStore.urge(ctx).endsAt
        assertEquals(0, repo.count())

        val e = flow().startUrge(UrgeSource.HOME)
        assertEquals(1, repo.count())
        assertEquals("never longer than the running lock", endsAt, e.lockEndedAt)
        assertEquals("not extended", endsAt, LockStateStore.urge(ctx).endsAt)
    }

    @Test
    fun aRunningLockIsResumedAndAnEndedOneIsNot() {
        makeDeviceOwner()
        val f = flow()
        assertNull(f.resumable())
        val e = f.startUrge(UrgeSource.HOME)
        assertEquals(e.id, f.resumable()?.id)

        // Move the stored lock into the past: the app no longer resumes it.
        LockStateStore.setUrge(ctx, startedAt = TrustedClock.now(ctx) - 20 * minute, endsAt = TrustedClock.now(ctx) - minute)
        repo.setLockWindow(e.id, TrustedClock.now(ctx) - 20 * minute, TrustedClock.now(ctx) - minute)
        assertNull(f.resumable())
    }

    @Test
    fun aStoreThatCannotBeOpenedNeverStopsTheLock() {
        makeDeviceOwner()
        // A database "file" that is really a directory: every read and write throws.
        val broken = { UrgeEntryRepository(DechainerDatabase(ctx, ctx.cacheDir.absolutePath)) }
        val e = flow(broken).startUrge(UrgeSource.HOME)

        assertTrue("the entry is in memory only", e.id < 0)
        assertTrue("but the lock started", LockStateStore.urge(ctx).endsAt > TrustedClock.now(ctx))
        assertEquals(LockStateStore.urge(ctx).endsAt, e.lockEndedAt)
        LockEngine.sync(ctx)
        assertTrue(dpm.isPackageSuspended(admin, games))
    }
}
