package io.github.warleysr.dechainer.security

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.edit
import io.github.warleysr.dechainer.BuildConfig
import io.github.warleysr.dechainer.data.DeviceAdmin
import org.json.JSONObject

class SecurityManager {

    /** What the "I'm having impulses" panic button does on top of locking Dechainer itself. */
    enum class ImpulseAction {
        /** Only the timer that already blocks access to Dechainer. */
        TIMER_ONLY,

        /** The timer, plus suspending a user-picked list of apps until it runs out. */
        TIMER_AND_SUSPEND
    }

    companion object {
        const val TETRIS_MIN_MINUTES = 1
        const val TETRIS_MAX_MINUTES = 15
        const val TETRIS_DEFAULT_MINUTES = 3

        const val IMPULSE_MIN_DURATION_MINUTES = 15
        const val IMPULSE_MAX_DURATION_MINUTES = 360
        const val IMPULSE_DEFAULT_DURATION_MINUTES = 60

        const val FORCED_REMOVAL_TARGET_MS = CountdownState.SEVEN_DAYS_MS
        const val FORCED_REMOVAL_CLAIM_WINDOW_MS = CountdownState.TWENTY_FOUR_HOURS_MS

        private const val DEBUG_RESTORE_UNKNOWN_SOURCES_KEY = "debug_restore_unknown_sources_restriction"
        private const val RECOVERY_PREFS = "recovery_prefs"
        private const val KEY_FORCED_REMOVAL_COUNTDOWN = "forced_removal_countdown"
        private const val KEY_FORCED_REMOVAL_ACTIVE = "forced_removal_active"

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

        /** Whether applying a ready 7-day unlock first requires playing Tetris. */
        fun isTetrisUnlockEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            if (prefs.contains("tetris_unlock")) return prefs.getBoolean("tetris_unlock", false)
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
            val nowElapsed = SystemClock.elapsedRealtime()
            prefs.edit(commit = true) {
                putLong("impulse_block_start_rtc", System.currentTimeMillis())
                putLong("impulse_block_start_elapsed", nowElapsed)
                putLong("impulse_block_duration", duration)
                putLong("impulse_block_accumulated_ms", 0L)
                putLong("impulse_block_last_elapsed", nowElapsed)
                putInt("impulse_block_boot_count", readBootCount(context))
                putBoolean("impulse_block_active", true)
            }
        }

        fun getImpulseBlockRemainingTime(context: Context): Long {
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("impulse_block_active", false)) return -1L

            val duration = prefs.getLong("impulse_block_duration", 0L)
            val startElapsed = prefs.getLong("impulse_block_start_elapsed", 0L)
            val lastElapsed = prefs.getLong("impulse_block_last_elapsed", startElapsed)
            val accumulated = prefs.getLong("impulse_block_accumulated_ms", 0L).coerceAtLeast(0L)
            val storedBootCount = prefs.getInt("impulse_block_boot_count", -1)
            val currentBootCount = readBootCount(context)

            val nowElapsed = SystemClock.elapsedRealtime()
            val rebooted = (storedBootCount != -1 && currentBootCount != -1 && currentBootCount != storedBootCount) ||
                nowElapsed < lastElapsed

            // On same boot, advance by monotonic elapsed delta; across reboot, only credit monotonic
            // uptime since boot (never forward wall-clock RTC, which is trivial to fast-forward).
            val delta = if (rebooted) nowElapsed.coerceAtLeast(0L) else (nowElapsed - lastElapsed).coerceAtLeast(0L)
            val nextAccumulated = (accumulated + delta).coerceAtMost(duration)
            val remaining = duration - nextAccumulated

            if (remaining <= 0L) {
                prefs.edit(commit = true) {
                    putBoolean("impulse_block_active", false)
                    putLong("impulse_block_accumulated_ms", duration)
                    putLong("impulse_block_last_elapsed", nowElapsed)
                    putInt("impulse_block_boot_count", currentBootCount)
                }
                return -1L
            }

            if (rebooted || delta >= 1_000L) {
                prefs.edit {
                    putLong("impulse_block_accumulated_ms", nextAccumulated)
                    putLong("impulse_block_last_elapsed", nowElapsed)
                    putInt("impulse_block_boot_count", currentBootCount)
                }
            }
            return remaining
        }

        fun startForcedRemoval(
            context: Context,
            nowRtc: Long = System.currentTimeMillis(),
            nowElapsed: Long = SystemClock.elapsedRealtime(),
            bootCount: Int = readBootCount(context)
        ) {
            val state = CountdownState.start(
                durationMs = FORCED_REMOVAL_TARGET_MS,
                claimWindowMs = FORCED_REMOVAL_CLAIM_WINDOW_MS,
                nowRtc = nowRtc,
                nowElapsed = nowElapsed,
                bootCount = bootCount
            )
            context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                putBoolean(KEY_FORCED_REMOVAL_ACTIVE, true)
                putString(KEY_FORCED_REMOVAL_COUNTDOWN, state.toJson().toString())
                remove("forced_removal_accumulated")
                remove("forced_removal_last_elapsed")
            }
        }

        fun cancelForcedRemoval(context: Context) {
            context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                putBoolean(KEY_FORCED_REMOVAL_ACTIVE, false)
                remove(KEY_FORCED_REMOVAL_COUNTDOWN)
                remove("forced_removal_accumulated")
                remove("forced_removal_last_elapsed")
            }
        }

        fun getForcedRemovalStatus(
            context: Context,
            nowRtc: Long = System.currentTimeMillis(),
            nowElapsed: Long = SystemClock.elapsedRealtime(),
            bootCount: Int = readBootCount(context)
        ): CountdownStatus? {
            val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_FORCED_REMOVAL_ACTIVE, false)) return null

            val rawJson = prefs.getString(KEY_FORCED_REMOVAL_COUNTDOWN, null)
            val currentState = if (rawJson != null) {
                runCatching { CountdownState.fromJson(JSONObject(rawJson)) }.getOrNull()
            } else {
                // Migrate legacy 48h accumulated timer to the 7-day dual-clock countdown preserving elapsed progress.
                val legacyAcc = prefs.getLong("forced_removal_accumulated", 0L).coerceAtLeast(0L)
                CountdownState(
                    durationMs = FORCED_REMOVAL_TARGET_MS,
                    claimWindowMs = FORCED_REMOVAL_CLAIM_WINDOW_MS,
                    accumulatedMs = legacyAcc.coerceAtMost(FORCED_REMOVAL_TARGET_MS),
                    lastSyncRtcMs = nowRtc,
                    lastSyncElapsedMs = nowElapsed,
                    lastBootCount = bootCount
                )
            } ?: return null

            val advanced = currentState.advance(nowRtc, nowElapsed, bootCount)
            val status = advanced.status()
            if (status == CountdownStatus.Expired) {
                cancelForcedRemoval(context)
                return null
            }

            prefs.edit(commit = true) {
                putString(KEY_FORCED_REMOVAL_COUNTDOWN, advanced.toJson().toString())
            }
            return status
        }

        fun getForcedRemovalRemainingTime(
            context: Context,
            nowRtc: Long = System.currentTimeMillis(),
            nowElapsed: Long = SystemClock.elapsedRealtime(),
            bootCount: Int = readBootCount(context)
        ): Long = when (val status = getForcedRemovalStatus(context, nowRtc, nowElapsed, bootCount)) {
            is CountdownStatus.Waiting -> status.remainingMs
            is CountdownStatus.Ready -> 0L
            CountdownStatus.Expired, null -> -1L
        }
    }
}
