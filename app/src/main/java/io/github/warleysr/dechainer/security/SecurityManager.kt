package io.github.warleysr.dechainer.security

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.warleysr.dechainer.BuildConfig
import io.github.warleysr.dechainer.data.DeviceAdmin
import java.security.SecureRandom
import androidx.core.content.edit

class SecurityManager {

    companion object {
        private const val CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /** How long forced removal makes you wait, without the recovery code: four days. */
        const val FORCED_REMOVAL_WAIT_MS = 4L * 24 * 60 * 60 * 1000

        const val DEBUG_AUTO_START_SESSION_KEY = "debug_auto_start_session"

        private const val DEBUG_RESTORE_UNKNOWN_SOURCES_KEY = "debug_restore_unknown_sources_restriction"

        private val isRecoveryKeySet = mutableStateOf(false)
        
        /**
         * When the open recovery session ends, on the running-time clock: moving the wall clock
         * cannot extend or shorten the window in which settings stay unlocked (blueprint 9.1/9.2).
         */
        var sessionEndElapsed by mutableLongStateOf(0L)
            private set

        fun isSessionActive(): Boolean = SystemClock.elapsedRealtime() < sessionEndElapsed

        private fun startSession() {
            sessionEndElapsed = SystemClock.elapsedRealtime() + (10 * 60 * 1000) // 10 minutes
        }

        fun endSession() {
            sessionEndElapsed = 0L
        }

        fun consumeDebugAutoStartSession(context: Context) {
            if (!BuildConfig.DEBUG) return

            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean(DEBUG_AUTO_START_SESSION_KEY, false)) {
                prefs.edit { remove(DEBUG_AUTO_START_SESSION_KEY) }
                startSession()
            }
        }

        fun suspendUnknownSourcesRestrictionForDebugInstall(context: Context) {
            if (!BuildConfig.DEBUG) return

            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            if (!dpm.isAdminActive(admin)) return

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
            if (dpm.isAdminActive(admin)) {
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

        private const val KEY_ENTRY_LOCK = "entry_lock"

        /** Whether opening the app asks for the opening pattern first. On unless switched off. */
        fun isEntryLockEnabled(context: Context): Boolean =
            context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).getBoolean(KEY_ENTRY_LOCK, true)

        /** Switching it off loosens the lock, so callers ask for the recovery code first. */
        fun setEntryLockEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE).edit { putBoolean(KEY_ENTRY_LOCK, enabled) }
        }

        private const val KEY_ENTRY_HASH = "entry_pattern_hash"
        private const val KEY_ENTRY_FAILS = "entry_failures"
        private const val KEY_ENTRY_FAIL_AT = "entry_last_fail_elapsed"

        private const val PRIVATE_OPEN_MS = 5 * 60_000L
        private var privateOpenUntil = 0L

        /** Your blackouts and focus sessions are private: a pattern drawn in the last few minutes keeps them open. */
        @Synchronized fun isPrivateOpen(): Boolean = SystemClock.elapsedRealtime() < privateOpenUntil
        @Synchronized fun openPrivate() { privateOpenUntil = SystemClock.elapsedRealtime() + PRIVATE_OPEN_MS }
        @Synchronized fun closePrivate() { privateOpenUntil = 0L }

        /** Whether the opening pattern has been chosen. The pattern itself is only stored as a salted hash. */
        fun hasEntryPattern(context: Context): Boolean = recoveryPrefs(context).getString(KEY_ENTRY_HASH, null) != null

        /** Sets (or replaces) the opening pattern. False if it is not valid. */
        fun setEntryPattern(context: Context, dots: List<Int>): Boolean {
            if (!Pattern.isValid(dots)) return false
            recoveryPrefs(context).edit(commit = true) {
                putString(KEY_ENTRY_HASH, RecoveryCodeHash.create(Pattern.encode(dots)))
                putInt(KEY_ENTRY_FAILS, 0)
            }
            openPrivate()
            return true
        }

        /** Forgotten pattern: the recovery code (checked by the caller) clears it, and the next opening asks for a new one. */
        fun clearEntryPattern(context: Context) {
            recoveryPrefs(context).edit(commit = true) {
                remove(KEY_ENTRY_HASH)
                putInt(KEY_ENTRY_FAILS, 0)
            }
        }

        fun entryWaitMs(context: Context): Long {
            val p = recoveryPrefs(context)
            return EntryLock.remainingWaitMs(p.getInt(KEY_ENTRY_FAILS, 0), p.getLong(KEY_ENTRY_FAIL_AT, 0L), SystemClock.elapsedRealtime())
        }

        /** One try at the opening pattern. Too many wrong ones in a row make the next try wait, longer each time. */
        fun tryEntryPattern(context: Context, dots: List<Int>): EntryAttempt = synchronized(RECOVERY_LOCK) {
            if (entryWaitMs(context) > 0L) return@synchronized EntryAttempt.WAIT
            val p = recoveryPrefs(context)
            val stored = p.getString(KEY_ENTRY_HASH, null) ?: return@synchronized EntryAttempt.OK
            if (RecoveryCodeHash.verify(Pattern.encode(dots), stored)) {
                p.edit(commit = true) { putInt(KEY_ENTRY_FAILS, 0) }
                openPrivate()
                EntryAttempt.OK
            } else {
                p.edit(commit = true) {
                    putInt(KEY_ENTRY_FAILS, p.getInt(KEY_ENTRY_FAILS, 0) + 1)
                    putLong(KEY_ENTRY_FAIL_AT, SystemClock.elapsedRealtime())
                }
                EntryAttempt.WRONG
            }
        }

        fun generateRecoveryCode(length: Int = 16): String {
            val random = SecureRandom()
            return (1..length)
                .map { CHAR_POOL[random.nextInt(CHAR_POOL.length)] }
                .joinToString("")
        }

        private const val KEY_RECOVERY_PLAIN = "recovery_code"
        private const val KEY_RECOVERY_HASH = "recovery_hash"

        private fun recoveryPrefs(context: Context) =
            context.getSharedPreferences("recovery_prefs", Context.MODE_PRIVATE)

        /**
         * The stored hash of the recovery code, or null when none is set. A code saved by an older
         * version as plain text is turned into a hash here, on first read, and the text is removed.
         */
        private fun recoveryHash(context: Context): String? = synchronized(RECOVERY_LOCK) {
            val prefs = recoveryPrefs(context)
            prefs.getString(KEY_RECOVERY_HASH, null)?.let { return@synchronized it }
            val plain = prefs.getString(KEY_RECOVERY_PLAIN, null) ?: return@synchronized null
            val hash = RecoveryCodeHash.create(plain)
            prefs.edit(commit = true) {
                putString(KEY_RECOVERY_HASH, hash)
                remove(KEY_RECOVERY_PLAIN)
            }
            hash
        }

        private val RECOVERY_LOCK = Any()

        /** Whether a recovery code has been set. The code itself can no longer be read back. */
        fun hasRecoveryCode(context: Context): Boolean = recoveryHash(context) != null

        fun isRecoveryCodeSet(context: Context) : Boolean  {
            isRecoveryKeySet.value = hasRecoveryCode(context)
            return isRecoveryKeySet.value
        }

        fun saveRecoveryCode(context: Context, code: String) {
            synchronized(RECOVERY_LOCK) {
                recoveryPrefs(context).edit(commit = true) {
                    putString(KEY_RECOVERY_HASH, RecoveryCodeHash.create(code))
                    remove(KEY_RECOVERY_PLAIN)
                }
            }
            isRecoveryKeySet.value = true
        }

        /** Checks [userInput] against the stored hash. An open recovery session counts as correct. */
        fun validateRecoveryCode(context: Context, userInput: String): Boolean {
            if (isSessionActive()) return true

            // Opening the session is now RecoveryGate's call, via beginUnlock, so the unlock
            // delay can sit between a correct code and changes unlocking.
            val stored = recoveryHash(context) ?: return false
            return RecoveryCodeHash.verify(userInput, stored)
        }

        private const val KEY_UNLOCK_DELAY_MIN = "unlock_delay_minutes"
        private const val KEY_UNLOCK_STARTED = "unlock_started_elapsed"
        private const val KEY_UNLOCK_AT = "unlock_at_elapsed"

        private fun securityPrefs(context: Context) =
            context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)

        fun getUnlockDelayMinutes(context: Context): Int =
            UnlockDelay.clampMinutes(securityPrefs(context).getInt(KEY_UNLOCK_DELAY_MIN, 0))

        fun setUnlockDelayMinutes(context: Context, minutes: Int) {
            securityPrefs(context).edit { putInt(KEY_UNLOCK_DELAY_MIN, UnlockDelay.clampMinutes(minutes)) }
        }

        private fun pendingUnlock(context: Context): UnlockDelay.Pending? {
            val p = securityPrefs(context)
            val at = p.getLong(KEY_UNLOCK_AT, 0L)
            if (at <= 0L) return null
            val stored = UnlockDelay.Pending(p.getLong(KEY_UNLOCK_STARTED, 0L), at)
            val fixed = UnlockDelay.afterReboot(stored, android.os.SystemClock.elapsedRealtime())
            if (fixed != stored) p.edit {
                putLong(KEY_UNLOCK_STARTED, fixed.startedAt)
                putLong(KEY_UNLOCK_AT, fixed.unlockAt)
            }
            return fixed
        }

        /** After a correct code: opens now with no delay, otherwise starts the countdown. True if open now. */
        fun beginUnlock(context: Context): Boolean {
            val delay = getUnlockDelayMinutes(context)
            if (delay <= 0) {
                startSession()
                return true
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val pending = pendingUnlock(context)
            // Re-entering the code while a countdown runs never restarts or shortens it.
            if (pending == null || UnlockDelay.isExpired(pending, now)) {
                securityPrefs(context).edit {
                    putLong(KEY_UNLOCK_STARTED, now)
                    putLong(KEY_UNLOCK_AT, now + delay * 60_000L)
                }
            }
            return syncDelayedSession(context)
        }

        /** Opens the session once a countdown has finished. True while a session is open. */
        fun syncDelayedSession(context: Context): Boolean {
            if (isSessionActive()) return true
            val pending = pendingUnlock(context) ?: return false
            val now = android.os.SystemClock.elapsedRealtime()
            return when {
                UnlockDelay.isOpen(pending, now) -> {
                    sessionEndElapsed = now + UnlockDelay.remainingOpenMs(pending, now)
                    // Handed to the in-memory session, so ending the session really ends it.
                    cancelUnlock(context)
                    true
                }
                UnlockDelay.isExpired(pending, now) -> {
                    cancelUnlock(context)
                    false
                }
                else -> false
            }
        }

        fun remainingUnlockWaitMs(context: Context): Long =
            pendingUnlock(context)?.let { UnlockDelay.remainingWaitMs(it, android.os.SystemClock.elapsedRealtime()) } ?: 0L

        fun isWaitingForUnlock(context: Context): Boolean =
            pendingUnlock(context)?.let { UnlockDelay.isWaiting(it, android.os.SystemClock.elapsedRealtime()) } ?: false

        fun cancelUnlock(context: Context) = securityPrefs(context).edit {
            remove(KEY_UNLOCK_STARTED)
            remove(KEY_UNLOCK_AT)
        }

        private val forcedRemovalLock = Any()

        /** Android's count of boots, so a reboot is noticed for certain; -1 if it can't be read. */
        private fun bootCount(context: Context): Int = try {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, ForcedRemovalClock.UNKNOWN_BOOT)
        } catch (_: Exception) {
            ForcedRemovalClock.UNKNOWN_BOOT
        }

        fun startForcedRemoval(context: Context) = synchronized(forcedRemovalLock) {
            val prefs = context.getSharedPreferences("recovery_prefs", Context.MODE_PRIVATE)
            prefs.edit(commit = true) {
                putBoolean("forced_removal_active", true)
                putLong("forced_removal_accumulated", 0L)
                putLong("forced_removal_last_elapsed", SystemClock.elapsedRealtime())
                putInt("forced_removal_last_boot", bootCount(context))
            }
        }
        fun cancelForcedRemoval(context: Context) {
            val prefs = context.getSharedPreferences("recovery_prefs", Context.MODE_PRIVATE)
            prefs.edit {
                putBoolean("forced_removal_active", false)
            }
        }

        /**
         * Milliseconds of the wait left, or -1 when no forced removal is running. Every call also
         * checkpoints the running time, which is why the enforcer calls it on every sync: time only
         * counts when it is written down, and a reboot would otherwise lose what came after the last write.
         */
        fun getForcedRemovalRemainingTime(context: Context): Long = synchronized(forcedRemovalLock) {
            val prefs = context.getSharedPreferences("recovery_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("forced_removal_active", false)) return@synchronized -1L

            val now = SystemClock.elapsedRealtime()
            val boot = bootCount(context)
            val accumulated = prefs.getLong("forced_removal_accumulated", 0L) + ForcedRemovalClock.elapsedSince(
                lastElapsed = prefs.getLong("forced_removal_last_elapsed", 0L),
                lastBootCount = prefs.getInt("forced_removal_last_boot", ForcedRemovalClock.UNKNOWN_BOOT),
                nowElapsed = now,
                nowBootCount = boot
            )

            prefs.edit(commit = true) {
                putLong("forced_removal_accumulated", accumulated)
                putLong("forced_removal_last_elapsed", now)
                putInt("forced_removal_last_boot", boot)
            }

            (FORCED_REMOVAL_WAIT_MS - accumulated).coerceAtLeast(0L)
        }

    }
}
