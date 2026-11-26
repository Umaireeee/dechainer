package io.github.warleysr.dechainer.data

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.security.SecurityManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayStoreUpdateManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("play_store_update_pass", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("play_store_update_pass", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `isUnauthorizedPackageInstall allows updates to pre-existing packages only`() {
        val prePass = setOf("com.brave.browser", "com.whatsapp")
        val own = "io.github.warleysr.dechainer"

        // Updating an already-installed app is allowed
        assertFalse(
            PlayStoreUpdateManager.isUnauthorizedPackageInstall(
                packageName = "com.brave.browser",
                prePassPackages = prePass,
                isReplacing = true,
                ownPackageName = own
            )
        )

        // Installing a brand-new app is unauthorized
        assertTrue(
            PlayStoreUpdateManager.isUnauthorizedPackageInstall(
                packageName = "com.zhiliaoapp.musically",
                prePassPackages = prePass,
                isReplacing = false,
                ownPackageName = own
            )
        )

        // Even if EXTRA_REPLACING=true, an app absent from pre-pass snapshot is unauthorized
        assertTrue(
            PlayStoreUpdateManager.isUnauthorizedPackageInstall(
                packageName = "com.instagram.android",
                prePassPackages = prePass,
                isReplacing = true,
                ownPackageName = own
            )
        )

        // Own package is never treated as unauthorized
        assertFalse(
            PlayStoreUpdateManager.isUnauthorizedPackageInstall(
                packageName = own,
                prePassPackages = prePass,
                isReplacing = false,
                ownPackageName = own
            )
        )
    }

    @Test
    fun `findUnauthorizedNewPackages detects all apps added since snapshot`() {
        val prePass = setOf("com.brave.browser", "com.whatsapp")
        val current = setOf("com.brave.browser", "com.whatsapp", "com.tiktok.app", "io.github.warleysr.dechainer")
        val stray = PlayStoreUpdateManager.findUnauthorizedNewPackages(
            currentPackages = current,
            prePassPackages = prePass,
            ownPackageName = "io.github.warleysr.dechainer"
        )
        assertEquals(setOf("com.tiktok.app"), stray)
    }

    @Test
    fun `getRemainingMillis tracks pass timer and ends pass on expiration`() {
        val t0Rtc = System.currentTimeMillis()
        val t0Elapsed = SystemClock.elapsedRealtime()
        context.getSharedPreferences("play_store_update_pass", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("active", true)
            .putLong("start_rtc", t0Rtc)
            .putLong("start_elapsed", t0Elapsed)
            .putInt("boot_count", -1)
            .putStringSet("pre_pass_packages", setOf("com.brave.browser"))
            .commit()

        val remMid = PlayStoreUpdateManager.getRemainingMillis(
            context = context,
            nowRtc = t0Rtc + 120_000L,
            nowElapsed = t0Elapsed + 120_000L,
            bootCount = -1
        )
        assertEquals(PlayStoreUpdateManager.PASS_DURATION_MS - 120_000L, remMid)
        assertTrue(PlayStoreUpdateManager.state.value.active)

        val remExpired = PlayStoreUpdateManager.getRemainingMillis(
            context = context,
            nowRtc = t0Rtc + PlayStoreUpdateManager.PASS_DURATION_MS + 1_000L,
            nowElapsed = t0Elapsed + PlayStoreUpdateManager.PASS_DURATION_MS + 1_000L,
            bootCount = -1
        )
        assertEquals(0L, remExpired)
        assertFalse(PlayStoreUpdateManager.state.value.active)
    }

    @Test
    fun `startPass refuses when impulse block is active`() {
        SecurityManager.startImpulseBlock(context)
        assertFalse(PlayStoreUpdateManager.startPass(context))
    }
}
