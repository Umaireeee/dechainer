package io.github.warleysr.dechainer

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.security.SecurityManager
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

/** The recovery code can be guessed only so fast: free tries, then waiting that a right code does not skip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryAttemptsTest {
    private lateinit var ctx: Context
    private val code = "ABCDEFGHIJKLMNOP"
    private val wrong = "PONMLKJIHGFEDCBA"

    private fun clean() {
        SecurityManager.endSession()
        Store.resetForTests()
        TrustedClock.resetForTests()
        ctx.deleteDatabase(DechainerDatabase.FILE_NAME)
        listOf("recovery_prefs", "security_prefs", "lock_settings").forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        LockStateStore.resetForTests()
    }

    @Before fun setUp() { ctx = ApplicationProvider.getApplicationContext(); clean(); SecurityManager.saveRecoveryCode(ctx, code) }

    @After fun tearDown() = clean()

    @Test
    fun rightCodeWorksAndWrongOnesAreFreeAtFirst() {
        repeat(4) { assertFalse(SecurityManager.validateRecoveryCode(ctx, wrong)) }
        assertEquals(0L, SecurityManager.recoveryWaitMs(ctx))
        assertTrue(SecurityManager.validateRecoveryCode(ctx, code))
    }

    @Test
    fun theFifthWrongTryMakesEveryTryWaitEvenTheRightCode() {
        repeat(5) { assertFalse(SecurityManager.validateRecoveryCode(ctx, wrong)) }
        assertTrue(SecurityManager.recoveryWaitMs(ctx) in 1L..30_000L)
        assertFalse("the right code is refused during the wait", SecurityManager.validateRecoveryCode(ctx, code))
    }

    @Test
    fun aRightCodeBeforeTheLimitClearsTheCount() {
        repeat(4) { SecurityManager.validateRecoveryCode(ctx, wrong) }
        assertTrue(SecurityManager.validateRecoveryCode(ctx, code))
        repeat(4) { assertFalse(SecurityManager.validateRecoveryCode(ctx, wrong)) }
        assertEquals("the four before the right code no longer count", 0L, SecurityManager.recoveryWaitMs(ctx))
    }
}
