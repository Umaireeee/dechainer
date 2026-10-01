package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.day.DayEngine
import io.github.warleysr.dechainer.day.DayWindow
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.DechainerDatabase
import io.github.warleysr.dechainer.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The daily evaluation inside the lock pass. It was written but never called, so no punishment day could
 * ever start; these tests call it the way the pass does and check the grace for days that were never enforced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DayEnginePassTest {
    private lateinit var ctx: Context
    private val zone get() = TrustedClock.zone()

    private fun clean() {
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        ctx.getSharedPreferences("lock_settings", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        LockStateStore.resetForTests()
    }

    @Before fun setUp() { ctx = ApplicationProvider.getApplicationContext(); clean() }
    @After fun tearDown() = clean()

    private fun today(): LocalDate = DayWindow.dateOf(TrustedClock.now(ctx), zone)

    @Test fun theFirstPassOnAPhoneThatWasAlreadyActivatedJudgesNothingBeforeToday() {
        Store.appState(ctx).set(AppStateKeys.ACTIVATED_ON, today().minusDays(5).toString())
        DayEngine.runPass(ctx, TrustedClock.now(ctx))
        assertEquals(today().toString(), Store.appState(ctx).get(AppStateKeys.DAY_ENGINE_LIVE_FROM))
        assertFalse("no punishment for days that were never enforced", LockStateStore.punishment(ctx).activeAt(TrustedClock.now(ctx)))
    }

    @Test fun onceLiveAMissingPlanBecomesAPunishmentDayThatTheLockPlanSees() {
        Store.appState(ctx).set(AppStateKeys.ACTIVATED_ON, today().minusDays(5).toString())
        Store.appState(ctx).set(AppStateKeys.DAY_ENGINE_LIVE_FROM, today().minusDays(3).toString())
        val now = TrustedClock.now(ctx)
        DayEngine.runPass(ctx, now)
        assertTrue(LockStateStore.punishment(ctx).activeAt(now))
        // Running it again changes nothing.
        val first = LockStateStore.punishment(ctx)
        DayEngine.runPass(ctx, now)
        assertEquals(first, LockStateStore.punishment(ctx))
    }

    @Test fun withoutConfirmingTheRulesNothingIsEvaluatedOrPunished() {
        DayEngine.runPass(ctx, TrustedClock.now(ctx))
        assertEquals(null, Store.appState(ctx).get(AppStateKeys.DAY_ENGINE_LIVE_FROM))
        assertFalse(LockStateStore.punishment(ctx).activeAt(TrustedClock.now(ctx)))
    }

    @Test fun enforcementStartsOnTheLaterOfActivationAndGoingLive() {
        val a = LocalDate.of(2026, 10, 1); val b = LocalDate.of(2026, 10, 9)
        assertEquals(b, DayEngine.effectiveStart(a, b))
        assertEquals(b, DayEngine.effectiveStart(b, a))
        assertEquals(a, DayEngine.effectiveStart(a, a))
    }
}
