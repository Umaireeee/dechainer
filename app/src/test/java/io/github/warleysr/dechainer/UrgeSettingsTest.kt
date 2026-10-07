package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.urge.UrgeSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The blackout settings: how long the lock lasts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UrgeSettingsTest {
    private lateinit var ctx: Context
    private val prefs = listOf("ai", "urge_settings", "lock_settings")

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Store.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        prefs.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
    }

    @After
    fun tearDown() {
        Store.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        prefs.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
    }

    @Test
    fun aDefaultLengthByDefault() {
        val s = UrgeSettings(ctx)
        assertEquals(Rules.URGE_LOCK_DEFAULT_MINUTES, s.durationMinutes)
        assertFalse(s.durationMs <= 0)
    }

    @Test
    fun theLengthIsClampedToTheBlueprintBounds() {
        val s = UrgeSettings(ctx)
        s.setDurationMinutes(0)
        assertEquals(Rules.URGE_LOCK_MIN_MINUTES, s.durationMinutes)
        s.setDurationMinutes(1_000)
        assertEquals(Rules.URGE_LOCK_MAX_MINUTES, s.durationMinutes)
        s.setDurationMinutes(25)
        assertEquals(25, s.durationMinutes)
        assertEquals(25 * 60_000L, s.durationMs)
    }

    @Test
    fun theLengthSurvivesANewInstance() {
        UrgeSettings(ctx).apply { setDurationMinutes(20) }
        assertEquals(20, UrgeSettings(ctx).durationMinutes)
    }
}
