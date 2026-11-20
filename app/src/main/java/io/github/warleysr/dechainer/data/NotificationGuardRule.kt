package io.github.warleysr.dechainer.data

/**
 * Pure decision logic for protecting notification permission (`POST_NOTIFICATIONS`) under Device Owner.
 */
object NotificationGuardRule {
    const val TIRAMISU_SDK = 33
    const val GRANT_STATE_GRANTED = 1
    const val DEFAULT_COOLDOWN_MS = 2_000L

    fun shouldRestore(
        sdk: Int,
        deviceOwner: Boolean,
        grantState: Int,
        notificationsEnabled: Boolean,
        lastAttemptElapsedMs: Long?,
        nowElapsedMs: Long,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): Boolean {
        if (!deviceOwner || sdk < TIRAMISU_SDK) return false
        if (grantState == GRANT_STATE_GRANTED && notificationsEnabled) return false
        if (lastAttemptElapsedMs != null && nowElapsedMs - lastAttemptElapsedMs in 0 until cooldownMs) {
            return false
        }
        return true
    }

    fun shouldReactToBlockBroadcast(hasBlockedExtra: Boolean, blocked: Boolean): Boolean =
        !hasBlockedExtra || blocked
}
