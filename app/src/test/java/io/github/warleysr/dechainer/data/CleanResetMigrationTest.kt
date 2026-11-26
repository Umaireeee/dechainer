package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.security.TimelockManager
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
        BrowserHardeningManager.applyConfig(
            context,
            BrowserHardeningConfig(enabled = true, blockYouTubeShorts = true)
        )
        PlayStoreUpdateManager.applyConfig(
            context,
            PlayStoreUpdateConfig(blockPlayStoreUi = true, silentAutoUpdateEnabled = true)
        )
        context.getSharedPreferences("local_dns_shield_prefs", Context.MODE_PRIVATE).edit()
            .putString("config_json", "{\"enabled\":true}")
            .commit()
        context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).edit()
            .putStringSet("apk_update_restore_restrictions", setOf("no_install_apps"))
            .commit()
        context.getSharedPreferences("timelock_prefs", Context.MODE_PRIVATE).edit()
            .putString("pending_requests", "[{\"id\":\"1\"}]")
            .commit()

        assertTrue(CleanResetMigration.runIfNeeded(context))

        assertFalse(BrowserHardeningManager.getConfig(context).enabled)
        assertFalse(PlayStoreUpdateManager.getConfig(context).blockPlayStoreUi)
        assertTrue(
            context.getSharedPreferences("local_dns_shield_prefs", Context.MODE_PRIVATE)
                .all.isEmpty()
        )
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

    @Test
    fun `markCompleted preserves activity_blocker_prefs on fresh installs and skips runIfNeeded`() {
        val blockerPrefs = context.getSharedPreferences("activity_blocker_prefs", Context.MODE_PRIVATE)
        blockerPrefs.edit()
            .putStringSet("blocked_activities", setOf("com.android.settings.homepage.SettingsHomepageActivity"))
            .commit()

        CleanResetMigration.markCompleted(context)

        assertFalse(CleanResetMigration.runIfNeeded(context))
        assertEquals(
            setOf("com.android.settings.homepage.SettingsHomepageActivity"),
            blockerPrefs.getStringSet("blocked_activities", emptySet())
        )
    }
}
