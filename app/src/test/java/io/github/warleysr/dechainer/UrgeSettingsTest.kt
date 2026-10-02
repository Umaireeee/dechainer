package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.ai.AiSettings
import io.github.warleysr.dechainer.ai.Provider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.urge.UrgeSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settings of the urge flow and the AI: the personal reason, the crisis contact, provider, model and consent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UrgeSettingsTest {
    private lateinit var ctx: Context
    private val prefs = listOf("ai", "urge_settings", "lock_settings")

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        prefs.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        LockStateStore.resetForTests()
    }

    @After
    fun tearDown() {
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        prefs.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        LockStateStore.resetForTests()
    }

    @Test
    fun theReasonAndTheContactAreSavedTrimmedAndCapped() {
        val s = UrgeSettings(ctx)
        assertTrue(s.setReason("  For my daughter\nand me  "))
        assertEquals("For my daughter and me", s.reason)
        assertTrue(s.setReason("x".repeat(1_000)))
        assertEquals(Rules.MAX_REASON_CHARS, s.reason.length)
        assertTrue(s.setContact("  Sam ", "+1 (555) 010-2030"))
        assertEquals("Sam", s.contact.name)
        assertEquals("+15550102030", s.contact.number)
        assertTrue(s.contact.usable)
    }

    @Test
    fun noReasonAndNoContactByDefault() {
        val s = UrgeSettings(ctx)
        assertEquals("", s.reason)
        assertFalse(s.contact.usable)
    }

    @Test
    fun consentIsForOneProviderAndAskedAgainWhenItChanges() {
        val ai = AiSettings(ctx)
        ai.setProvider(Provider.OPENAI)
        ai.setConsent(true)
        assertTrue(ai.consent)
        ai.setProvider(Provider.GOOGLE)
        assertFalse(ai.consent)
        ai.setProvider(Provider.OPENAI)
        assertTrue(ai.consent)
    }

    @Test
    fun consentForACustomAddressIsForThatAddressOnly() {
        val ai = AiSettings(ctx)
        ai.setProvider(Provider.CUSTOM)
        ai.setCustomBase("https://a.example/v1")
        ai.setConsent(true)
        assertTrue(ai.consent)
        ai.setCustomBase("https://b.example/v1")
        assertFalse(ai.consent)
    }

    @Test
    fun theModelDefaultsToTheProvidersAndIsNotConfiguredWithoutAKey() {
        val ai = AiSettings(ctx)
        ai.setProvider(Provider.DEEPSEEK)
        assertEquals(Provider.DEEPSEEK.defaultModel, ai.model)
        assertFalse(ai.configured)
    }

    @Test
    fun clearingTheKeyIsSaved() {
        val ai = AiSettings(ctx)
        assertEquals(AiSettings.KeySave.SAVED, ai.saveKey("   "))
        assertEquals("", ai.key)
    }
}
