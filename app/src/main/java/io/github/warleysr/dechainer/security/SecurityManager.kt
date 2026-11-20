package io.github.warleysr.dechainer.security

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.warleysr.dechainer.BuildConfig
import io.github.warleysr.dechainer.data.DeviceAdmin
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SecurityManager {

    /** What the "I'm having impulses" panic button does on top of locking Dechainer itself. */
    enum class ImpulseAction {
        /** Only the timer that already blocks access to Dechainer. */
        TIMER_ONLY,

        /** The timer, plus suspending a user-picked list of apps until it runs out. */
        TIMER_AND_SUSPEND
    }

    companion object {
        private const val CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        const val TETRIS_MIN_MINUTES = 1
        const val TETRIS_MAX_MINUTES = 15
        const val TETRIS_DEFAULT_MINUTES = 3

        const val IMPULSE_MIN_DURATION_MINUTES = 15
        const val IMPULSE_MAX_DURATION_MINUTES = 360
        const val IMPULSE_DEFAULT_DURATION_MINUTES = 60

        const val DEBUG_AUTO_START_SESSION_KEY = "debug_auto_start_session"

        private const val DEBUG_RESTORE_UNKNOWN_SOURCES_KEY = "debug_restore_unknown_sources_restriction"
        private const val RECOVERY_PREFS = "recovery_prefs"
        private const val KEY_LEGACY_RECOVERY_CODE = "recovery_code"
        private const val KEY_RECOVERY_HASH = "recovery_code_hash"
        private const val KEY_RECOVERY_SALT = "recovery_code_salt"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_BITS = 256
        private const val SALT_BYTES = 16
        private const val MAX_FREE_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MS = 30_000L

        var isRecoveryKeySet by mutableStateOf(false)
            private set

        var sessionEndTime by mutableLongStateOf(0L)
            private set

        private var failedAttempts = 0
        private var lockoutUntilElapsed = 0L

        fun getSessionRemainingMillis(): Long =
            (sessionEndTime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

        fun isSessionActive(): Boolean = getSessionRemainingMillis() > 0L

        fun startSession() {
            sessionEndTime = SystemClock.elapsedRealtime() + (10 * 60 * 1000L) // 10 minutes
        }

        /** Restarts the 10-minute countdown of a session that is still active; an expired one needs the code again. */
        fun renewSession() {
            if (isSessionActive()) startSession()
        }

        fun endSession() {
            sessionEndTime = 0L
        }

        fun consumeDebugAutoStartSession(context: Context): Boolean {
            if (!BuildConfig.DEBUG) return false

            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean(DEBUG_AUTO_START_SESSION_KEY, false)) return false

            prefs.edit { remove(DEBUG_AUTO_START_SESSION_KEY) }
            startSession()
            return true
        }

        fun suspendUnknownSourcesRestrictionForDebugInstall(context: Context) {
            if (!BuildConfig.DEBUG) return

            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            if (!dpm.isDeviceOwnerApp(context.packageName)) return

            val wasActive = dpm.getUserRestrictions(admin)
                .getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
            if (wasActive) {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
            }

            context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).edit {
                putBoolean(DEBUG_RESTORE_UNKNOWN_SOURCES_KEY, wasActive)
            }
        }

        fun consumeDebugRestoreUnknownSourcesRestriction(context: Context) {
            if (!BuildConfig.DEBUG) return

            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean(DEBUG_RESTORE_UNKNOWN_SOURCES_KEY, false)) return
            prefs.edit { remove(DEBUG_RESTORE_UNKNOWN_SOURCES_KEY) }

            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
            }
        }

        fun isShuffleKeyboardEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("shuffle_keyboard", false)
        }

        fun setShuffleKeyboardEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            prefs.edit { putBoolean("shuffle_keyboard", enabled) }
        }

        /** Whether starting a recovery session first requires playing Tetris (the unlock delay). */
        fun isTetrisUnlockEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            if (prefs.contains("tetris_unlock")) return prefs.getBoolean("tetris_unlock", false)
            // Older versions stored the chosen challenges as a set; keep honouring a Tetris choice.
            return prefs.getStringSet("access_challenges", null)?.contains("TETRIS") == true
        }

        fun setTetrisUnlockEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            prefs.edit { putBoolean("tetris_unlock", enabled) }
        }

        fun getTetrisMinutes(context: Context): Int {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            return prefs.getInt("tetris_minutes", TETRIS_DEFAULT_MINUTES)
                .coerceIn(TETRIS_MIN_MINUTES, TETRIS_MAX_MINUTES)
        }

        fun setTetrisMinutes(context: Context, minutes: Int) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            prefs.edit { putInt("tetris_minutes", minutes.coerceIn(TETRIS_MIN_MINUTES, TETRIS_MAX_MINUTES)) }
        }

        fun isTetrisSoundEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("tetris_sound", true)
        }

        fun setTetrisSoundEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            prefs.edit { putBoolean("tetris_sound", enabled) }
        }

        fun getImpulseAction(context: Context): ImpulseAction {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            val stored = prefs.getString("impulse_action", ImpulseAction.TIMER_ONLY.name)!!
            return runCatching { ImpulseAction.valueOf(stored) }.getOrDefault(ImpulseAction.TIMER_ONLY)
        }

        fun setImpulseAction(context: Context, action: ImpulseAction) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            prefs.edit { putString("impulse_action", action.name) }
        }

        /** Always inside [IMPULSE_MIN_DURATION_MINUTES]..[IMPULSE_MAX_DURATION_MINUTES]. */
        fun getImpulseDurationMinutes(context: Context): Int {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            return prefs.getInt("impulse_duration_minutes", IMPULSE_DEFAULT_DURATION_MINUTES)
                .coerceIn(IMPULSE_MIN_DURATION_MINUTES, IMPULSE_MAX_DURATION_MINUTES)
        }

        fun setImpulseDurationMinutes(context: Context, minutes: Int) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            prefs.edit {
                putInt(
                    "impulse_duration_minutes",
                    minutes.coerceIn(IMPULSE_MIN_DURATION_MINUTES, IMPULSE_MAX_DURATION_MINUTES)
                )
            }
        }

        /** Apps the user picked to be suspended while an impulse block is running. */
        fun getImpulseSuspendedApps(context: Context): Set<String> {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            return prefs.getStringSet("impulse_suspended_apps", emptySet()) ?: emptySet()
        }

        fun setImpulseSuspendedApps(context: Context, packages: Set<String>) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            prefs.edit { putStringSet("impulse_suspended_apps", packages) }
        }

        /**
         * Apps that are suspended *right now* because of an impulse block, as opposed to the list
         * the user configured — the two can differ if the configuration changes mid-block, and
         * releasing has to act on what was actually suspended.
         */
        fun getActiveImpulseSuspension(context: Context): Set<String> {
            if (getImpulseBlockRemainingTime(context) <= 0L) return emptySet()
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            return prefs.getStringSet("impulse_active_suspension", emptySet()) ?: emptySet()
        }

        fun setActiveImpulseSuspension(context: Context, packages: Set<String>) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            prefs.edit { putStringSet("impulse_active_suspension", packages) }
        }

        fun clearActiveImpulseSuspension(context: Context) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            prefs.edit { remove("impulse_active_suspension") }
        }

        private fun readBootCount(context: Context): Int =
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
                .getOrDefault(-1)

        fun startImpulseBlock(context: Context) {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            val duration = getImpulseDurationMinutes(context) * 60 * 1000L
            prefs.edit(commit = true) {
                putLong("impulse_block_start_rtc", System.currentTimeMillis())
                putLong("impulse_block_start_elapsed", SystemClock.elapsedRealtime())
                putLong("impulse_block_duration", duration)
                putInt("impulse_block_boot_count", readBootCount(context))
                putBoolean("impulse_block_active", true)
            }
        }

        fun getImpulseBlockRemainingTime(context: Context): Long {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("impulse_block_active", false)) return -1L

            val startRtc = prefs.getLong("impulse_block_start_rtc", 0L)
            val startElapsed = prefs.getLong("impulse_block_start_elapsed", 0L)
            val duration = prefs.getLong("impulse_block_duration", 0L)
            val storedBootCount = prefs.getInt("impulse_block_boot_count", -1)
            val currentBootCount = readBootCount(context)

            val nowRtc = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()

            val remainingElapsed = (startElapsed + duration) - nowElapsed
            val remainingRtc = (startRtc + duration) - nowRtc
            val rebooted = (storedBootCount != -1 && currentBootCount != -1 && currentBootCount != storedBootCount) ||
                nowElapsed < startElapsed

            val remaining = if (rebooted) {
                // Clamp RTC fallback to [.., duration] so rolling the clock back cannot cause multi-year lockouts.
                remainingRtc.coerceAtMost(duration)
            } else {
                // No reboot: trust monotonic elapsed time as it is resistant to wall-clock changes.
                remainingElapsed
            }

            if (remaining <= 0) {
                prefs.edit { putBoolean("impulse_block_active", false) }
                return -1L
            }
            return remaining
        }

        fun generateRecoveryCode(length: Int = 16): String {
            val random = SecureRandom()
            return (1..length)
                .map { CHAR_POOL[random.nextInt(CHAR_POOL.length)] }
                .joinToString("")
        }

        private fun hashRecoveryCode(code: String, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(code.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        @Synchronized
        private fun migrateLegacyRecoveryCodeIfNeeded(context: Context) {
            val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            val legacy = prefs.getString(KEY_LEGACY_RECOVERY_CODE, null) ?: return
            if (prefs.getString(KEY_RECOVERY_HASH, null) != null) {
                prefs.edit(commit = true) { remove(KEY_LEGACY_RECOVERY_CODE) }
                return
            }
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val hash = hashRecoveryCode(legacy, salt)
            prefs.edit(commit = true) {
                putString(KEY_RECOVERY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                putString(KEY_RECOVERY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                remove(KEY_LEGACY_RECOVERY_CODE)
            }
        }

        fun syncRecoveryCodeState(context: Context) {
            isRecoveryKeySet = isRecoveryCodeSet(context)
        }

        fun isRecoveryCodeSet(context: Context): Boolean {
            migrateLegacyRecoveryCodeIfNeeded(context)
            val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            return prefs.getString(KEY_RECOVERY_HASH, null) != null
        }

        fun saveRecoveryCode(context: Context, code: String) {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val hash = hashRecoveryCode(code, salt)
            val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            prefs.edit(commit = true) {
                putString(KEY_RECOVERY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                putString(KEY_RECOVERY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                remove(KEY_LEGACY_RECOVERY_CODE)
            }
            synchronized(this) {
                failedAttempts = 0
                lockoutUntilElapsed = 0L
            }
            isRecoveryKeySet = true
        }

        @Synchronized
        fun getRecoveryLockoutRemainingMillis(): Long =
            (lockoutUntilElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

        @Synchronized
        internal fun resetRateLimitForTests() {
            failedAttempts = 0
            lockoutUntilElapsed = 0L
        }

        @Synchronized
        fun verifyRecoveryCode(context: Context, userInput: String): Boolean {
            if (getRecoveryLockoutRemainingMillis() > 0L) return false
            migrateLegacyRecoveryCodeIfNeeded(context)
            val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            val hashB64 = prefs.getString(KEY_RECOVERY_HASH, null) ?: return false
            val saltB64 = prefs.getString(KEY_RECOVERY_SALT, null) ?: return false

            val expected = runCatching { Base64.decode(hashB64, Base64.NO_WRAP) }.getOrNull() ?: return false
            val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
            val actual = hashRecoveryCode(userInput, salt)

            return if (MessageDigest.isEqual(expected, actual)) {
                failedAttempts = 0
                lockoutUntilElapsed = 0L
                true
            } else {
                failedAttempts++
                if (failedAttempts >= MAX_FREE_ATTEMPTS) {
                    val shift = (failedAttempts - MAX_FREE_ATTEMPTS).coerceAtMost(4)
                    lockoutUntilElapsed = SystemClock.elapsedRealtime() + (BASE_LOCKOUT_MS * (1L shl shift))
                }
                false
            }
        }

        fun startForcedRemoval(context: Context) {
            val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            prefs.edit {
                putBoolean("forced_removal_active", true)
                putLong("forced_removal_accumulated", 0L)
                putLong("forced_removal_last_elapsed", SystemClock.elapsedRealtime())
            }
        }

        fun cancelForcedRemoval(context: Context) {
            val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            prefs.edit(commit = true) {
                putBoolean("forced_removal_active", false)
                remove("forced_removal_accumulated")
                remove("forced_removal_last_elapsed")
            }
        }

        fun getForcedRemovalRemainingTime(context: Context): Long {
            val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("forced_removal_active", false)) return -1L

            var accumulated = prefs.getLong("forced_removal_accumulated", 0L)
            val lastElapsed = prefs.getLong("forced_removal_last_elapsed", 0L)
            val now = SystemClock.elapsedRealtime()

            val diff = if (now >= lastElapsed) now - lastElapsed else now
            accumulated += diff

            prefs.edit {
                putLong("forced_removal_accumulated", accumulated)
                putLong("forced_removal_last_elapsed", now)
            }

            val target = 48L * 60 * 60 * 1000
            return (target - accumulated).coerceAtLeast(0L)
        }

    }
}
