package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.security.PendingAction
import io.github.warleysr.dechainer.security.SecurityManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class PlayStoreUpdateManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PlayStoreUpdateManager.resetForTest(context)
        context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PlayStoreUpdateManager.resetForTest(context)
        context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `PlayStoreUpdateConfig handles normal and midnight-wrapping windows cleanly`() {
        val overnight = PlayStoreUpdateConfig(
            blockPlayStoreUi = true,
            silentAutoUpdateEnabled = true,
            windowStartMinute = 2 * 60,
            windowEndMinute = 5 * 60
        )
        assertEquals(180, overnight.windowDurationMinutes())
        assertFalse(overnight.isWithinWindow(1 * 60 + 59))
        assertTrue(overnight.isWithinWindow(2 * 60))
        assertTrue(overnight.isWithinWindow(3 * 60 + 30))
        assertFalse(overnight.isWithinWindow(5 * 60))

        val wrapping = PlayStoreUpdateConfig(
            blockPlayStoreUi = true,
            silentAutoUpdateEnabled = true,
            windowStartMinute = 23 * 60,
            windowEndMinute = 2 * 60
        )
        assertEquals(180, wrapping.windowDurationMinutes())
        assertFalse(wrapping.isWithinWindow(22 * 60 + 59))
        assertTrue(wrapping.isWithinWindow(23 * 60))
        assertTrue(wrapping.isWithinWindow(0))
        assertTrue(wrapping.isWithinWindow(1 * 60 + 59))
        assertFalse(wrapping.isWithinWindow(2 * 60))
    }

    @Test
    fun `isLooserThan gates weakening moves behind timelock and allows tightening immediately`() {
        val off = PlayStoreUpdateConfig()
        val armed = PlayStoreUpdateConfig(
            blockPlayStoreUi = true,
            silentAutoUpdateEnabled = true,
            windowStartMinute = 2 * 60,
            windowEndMinute = 5 * 60,
            screenOffOnly = true
        )

        // Enabling 24/7 UI block + screen-off silent window is allowed immediately
        assertFalse(armed.isLooserThan(off))
        // Shrinking window duration from 3h to 2h is tightening
        assertFalse(armed.copy(windowStartMinute = 2 * 60, windowEndMinute = 4 * 60).isLooserThan(armed))
        // Enabling UI block alone is tightening
        assertFalse(off.copy(blockPlayStoreUi = true).isLooserThan(off))

        // Turning off 24/7 UI block is looser
        assertTrue(armed.copy(blockPlayStoreUi = false).isLooserThan(armed))
        // Disabling screen-off-only requirement is looser
        assertTrue(armed.copy(screenOffOnly = false).isLooserThan(armed))
        // Widening window duration from 3h to 4h is looser
        assertTrue(armed.copy(windowEndMinute = 6 * 60).isLooserThan(armed))
        // Enabling silent window while leaving UI block OFF is looser
        assertTrue(off.copy(silentAutoUpdateEnabled = true).isLooserThan(off))
    }

    @Test
    fun `config and PendingAction UpdatePlayStorePolicy round-trip cleanly`() {
        val original = PlayStoreUpdateConfig(
            blockPlayStoreUi = true,
            silentAutoUpdateEnabled = true,
            windowStartMinute = 23 * 60 + 15,
            windowEndMinute = 3 * 60 + 45,
            screenOffOnly = true
        )
        val parsed = PlayStoreUpdateConfig.fromJsonString(original.toJsonString())
        assertEquals(original, parsed)

        val action = PendingAction.UpdatePlayStorePolicy(original.toJsonString())
        val decoded = PendingAction.fromJson(action.type, action.toPayload())
        assertEquals(action, decoded)
    }

    @Test
    fun `shouldWindowBeOpen requires window, unattended screen when screenOffOnly, and no impulse lock`() {
        val cfg = PlayStoreUpdateConfig(
            blockPlayStoreUi = true,
            silentAutoUpdateEnabled = true,
            windowStartMinute = 2 * 60,
            windowEndMinute = 5 * 60,
            screenOffOnly = true
        )

        assertTrue(PlayStoreUpdateManager.shouldWindowBeOpen(cfg, 3 * 60, deviceUnattended = true, impulseBlockActive = false))
        assertFalse(PlayStoreUpdateManager.shouldWindowBeOpen(cfg, 3 * 60, deviceUnattended = false, impulseBlockActive = false))
        assertFalse(PlayStoreUpdateManager.shouldWindowBeOpen(cfg, 12 * 60, deviceUnattended = true, impulseBlockActive = false))
        assertFalse(PlayStoreUpdateManager.shouldWindowBeOpen(cfg, 3 * 60, deviceUnattended = true, impulseBlockActive = true))
        assertTrue(
            PlayStoreUpdateManager.shouldWindowBeOpen(
                cfg.copy(screenOffOnly = false),
                3 * 60,
                deviceUnattended = false,
                impulseBlockActive = false
            )
        )
    }

    @Test
    fun `isUnauthorizedPackageInstall and findUnauthorizedPackages catch new apps and re-enabled dormant stubs`() {
        val preWindowEnabled = setOf("com.brave.browser", "com.whatsapp", "com.android.vending")
        val own = "io.github.warleysr.dechainer"

        // Updating an already-enabled app is allowed
        assertFalse(
            PlayStoreUpdateManager.isUnauthorizedPackageInstall(
                packageName = "com.brave.browser",
                preWindowEnabled = preWindowEnabled,
                isReplacing = true,
                ownPackageName = own
            )
        )

        // Brand-new third-party install is unauthorized
        assertTrue(
            PlayStoreUpdateManager.isUnauthorizedPackageInstall(
                packageName = "com.zhiliaoapp.musically",
                preWindowEnabled = preWindowEnabled,
                isReplacing = false,
                ownPackageName = own
            )
        )

        // Even with EXTRA_REPLACING=true, an app absent from enabled snapshot is unauthorized
        assertTrue(
            PlayStoreUpdateManager.isUnauthorizedPackageInstall(
                packageName = "com.oem.dormant.game",
                preWindowEnabled = preWindowEnabled,
                isReplacing = true,
                ownPackageName = own
            )
        )

        // Re-enabled dormant OEM bloatware stub shows up in currentEnabled - preWindowEnabled
        val currentEnabled = preWindowEnabled + setOf("com.oem.dormant.browser", "com.tiktok.app", own)
        val stray = PlayStoreUpdateManager.findUnauthorizedPackages(
            currentEnabled = currentEnabled,
            preWindowEnabled = preWindowEnabled,
            ownPackageName = own
        )
        assertEquals(setOf("com.oem.dormant.browser", "com.tiktok.app"), stray)
    }

    @Test
    fun `computeNextBoundaryInstant picks next start or end across normal and midnight-wrap schedules`() {
        val zone = ZoneId.of("Asia/Karachi")
        val beforeWindow = ZonedDateTime.of(2026, 5, 10, 1, 30, 0, 0, zone)
        val nextFromBefore = PlayStoreUpdateManager.computeNextBoundaryInstant(beforeWindow, 2 * 60, 5 * 60)
        assertNotNull(nextFromBefore)
        assertEquals(ZonedDateTime.of(2026, 5, 10, 2, 0, 0, 0, zone).toInstant(), nextFromBefore)

        val insideWindow = ZonedDateTime.of(2026, 5, 10, 3, 15, 0, 0, zone)
        val nextFromInside = PlayStoreUpdateManager.computeNextBoundaryInstant(insideWindow, 2 * 60, 5 * 60)
        assertEquals(ZonedDateTime.of(2026, 5, 10, 5, 0, 0, 0, zone).toInstant(), nextFromInside)

        val afterWindow = ZonedDateTime.of(2026, 5, 10, 20, 0, 0, 0, zone)
        val nextFromAfter = PlayStoreUpdateManager.computeNextBoundaryInstant(afterWindow, 2 * 60, 5 * 60)
        assertEquals(ZonedDateTime.of(2026, 5, 11, 2, 0, 0, 0, zone).toInstant(), nextFromAfter)

        val insideWrap = ZonedDateTime.of(2026, 5, 10, 23, 45, 0, 0, zone)
        val nextWrapEnd = PlayStoreUpdateManager.computeNextBoundaryInstant(insideWrap, 23 * 60, 2 * 60)
        assertEquals(ZonedDateTime.of(2026, 5, 11, 2, 0, 0, 0, zone).toInstant(), nextWrapEnd)

        assertNull(PlayStoreUpdateManager.computeNextBoundaryInstant(beforeWindow, 2 * 60, 2 * 60))
    }

    @Test
    fun `shouldBlockUiForPackage blocks only Play Store when enabled`() {
        assertFalse(PlayStoreUpdateManager.shouldBlockUiForPackage(context, PlayStoreUpdateManager.PLAY_STORE_PACKAGE))
        PlayStoreUpdateManager.applyConfig(context, PlayStoreUpdateConfig(blockPlayStoreUi = true))
        assertTrue(PlayStoreUpdateManager.shouldBlockUiForPackage(context, PlayStoreUpdateManager.PLAY_STORE_PACKAGE))
        assertFalse(PlayStoreUpdateManager.shouldBlockUiForPackage(context, "com.brave.browser"))
    }

    @Test
    fun `closeSilentWindowNow works safely even during impulse lock`() {
        SecurityManager.startImpulseBlock(context)
        PlayStoreUpdateManager.sync(context, screenOffOverride = true)
        assertFalse(PlayStoreUpdateManager.isSilentWindowOpen(context))
        PlayStoreUpdateManager.closeSilentWindowNow(context)
        assertFalse(PlayStoreUpdateManager.isSilentWindowOpen(context))
    }
}
