package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.ai.AiSettings
import io.github.warleysr.dechainer.ai.Provider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.PunishmentInput
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

/**
 * The new settings of Phase 3 (the AI choices, the personal reason, the crisis contact) obey the
 * settings freeze at the layer that writes them, and reading stays open on a punishment day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UrgeSettingsFreezeTest {
    private lateinit var ctx: Context
    private val hour = 3_600_000L
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

    private fun startDay() {
        val now = TrustedClock.now(ctx)
        LockStateStore.setPunishment(ctx, PunishmentInput(now - hour, now + hour), "today")
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
    fun theReasonAndTheContactCannotBeChangedOnAPunishmentDayButCanBeRead() {
        val s = UrgeSettings(ctx)
        s.setReason("before")
        s.setContact("Sam", "123")
        startDay()
        assertTrue(s.frozen)
        assertFalse(s.setReason("after"))
        assertFalse(s.setContact("Else", "999"))
        assertEquals("before", s.reason)
        assertEquals("123", s.contact.number)
    }

    @Test
    fun everyAiSettingIsRefusedOnAPunishmentDay() {
        val ai = AiSettings(ctx)
        assertTrue(ai.setProvider(Provider.DEEPSEEK))
        assertTrue(ai.setModel("m1"))
        assertTrue(ai.setConsent(true))
        assertEquals(Provider.DEEPSEEK, ai.provider)
        assertTrue(ai.consent)

        startDay()
        assertTrue(ai.frozen)
        assertFalse(ai.setProvider(Provider.OPENAI))
        assertFalse(ai.setModel("m2"))
        assertFalse(ai.setCustomBase("https://example.com"))
        assertFalse(ai.setConsent(false))
        assertEquals(AiSettings.KeySave.REFUSED_FROZEN, ai.saveKey("sk-abc"))
        assertEquals(Provider.DEEPSEEK, ai.provider)
        assertEquals("m1", ai.model)
        assertTrue("consent stays as it was", ai.consent)
        assertEquals("", ai.key)
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
    fun clearingTheKeyIsSavedOnAnyDayThatIsNotFrozen() {
        val ai = AiSettings(ctx)
        assertEquals(AiSettings.KeySave.SAVED, ai.saveKey("   "))
        assertEquals("", ai.key)
    }
}
