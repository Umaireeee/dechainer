package io.github.warleysr.dechainer

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.PunishmentInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The freeze on the writers that need Device Owner and the real Application (the Apps tab, Private DNS). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DechainerApplication::class)
class SettingsFreezeDeviceTest {
    private lateinit var ctx: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val games = "com.example.games"
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("lock_settings", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        LockStateStore.resetForTests()
        dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
        shadowOf(dpm).setDeviceOwner(admin)
        shadowOf(ctx.packageManager).installPackage(
            PackageInfo().apply {
                packageName = games
                applicationInfo = ApplicationInfo().apply { packageName = games }
            }
        )
    }

    private fun day(startsAgo: Long, endsIn: Long) {
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - startsAgo, now + endsIn), "today")
    }

    @Test
    fun anAppCannotBeSuspendedOrReleasedByHandOnAPunishmentDay() {
        day(startsAgo = hour, endsIn = hour)
        AppRepository.setAppSuspended(games, true)
        assertFalse(dpm.isPackageSuspended(admin, games))

        day(startsAgo = 3 * hour, endsIn = -hour)   // over
        AppRepository.setAppSuspended(games, true)
        assertTrue(dpm.isPackageSuspended(admin, games))

        day(startsAgo = hour, endsIn = hour)
        AppRepository.setAppSuspended(games, false)
        assertTrue("releasing it by hand is refused too", dpm.isPackageSuspended(admin, games))
    }

    @Test
    fun thePrivateDnsFilterCannotBeChangedOnAPunishmentDay() {
        day(startsAgo = hour, endsIn = hour)
        val refused = DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
        assertEquals(refused, DeviceOwnerRepository.setPrivateDNS("dns.example"))
        assertEquals(refused, DeviceOwnerRepository.setPrivateDnsAutomatic())
    }
}
