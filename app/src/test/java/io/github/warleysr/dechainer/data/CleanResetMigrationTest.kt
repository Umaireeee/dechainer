package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.security.TimelockManager
import io.github.warleysr.dechainer.vpn.LocalDnsConfig
import io.github.warleysr.dechainer.vpn.LocalDnsShieldManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CleanResetMigrationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        CleanResetMigration.resetForTest(context)
    }

    @After
    fun tearDown() {
        CleanResetMigration.resetForTest(context)
    }

    @Test
    fun `runIfNeeded wipes old policies once and never resets user choices afterwards`() {
        LocalDnsShieldManager.applyConfig(
            context,
            LocalDnsConfig(enabled = true, blockAdultDomains = true)
        )
        BrowserHardeningManager.applyConfig(
            context,
            BrowserHardeningConfig(enabled = true, blockYouTubeShorts = true)
        )
        PlayStoreUpdateManager.applyConfig(
            context,
            PlayStoreUpdateConfig(blockPlayStoreUi = true, silentAutoUpdateEnabled = true)
        )
        context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).edit()
            .putStringSet("apk_update_restore_restrictions", setOf("no_install_apps"))
            .commit()
        context.getSharedPreferences("timelock_prefs", Context.MODE_PRIVATE).edit()
            .putString("pending_requests", "[{\"id\":\"1\"}]")
            .commit()

        assertTrue(CleanResetMigration.runIfNeeded(context))

        assertFalse(LocalDnsShieldManager.getConfig(context).enabled)
        assertFalse(BrowserHardeningManager.getConfig(context).enabled)
        assertFalse(PlayStoreUpdateManager.getConfig(context).blockPlayStoreUi)
        assertTrue(
            context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
                .getStringSet("apk_update_restore_restrictions", emptySet())!!.isEmpty()
        )
        assertTrue(TimelockManager.requests.value.isEmpty())

        // Subsequent user configuration must NOT be wiped by later runs.
        val userChoice = BrowserHardeningConfig(enabled = true, disableIncognito = true)
        BrowserHardeningManager.applyConfig(context, userChoice)
        assertFalse(CleanResetMigration.runIfNeeded(context))
        assertEquals(userChoice, BrowserHardeningManager.getConfig(context))
    }
}
