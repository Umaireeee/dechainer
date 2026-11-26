package io.github.warleysr.dechainer.security

import org.json.JSONObject

/** Result of evaluating a dual-clock countdown at the current moment. */
sealed interface CountdownStatus {
    data class Waiting(val remainingMs: Long) : CountdownStatus
    data class Ready(val claimRemainingMs: Long) : CountdownStatus
    data object Expired : CountdownStatus
}

/**
 * Tamper-resistant dual-clock countdown that survives reboots.
 *
 * Within the same boot (`!rebooted`), progress is measured strictly via monotonic
 * `elapsedRealtime()`, making it immune to wall-clock manipulation. Across reboots, progress is
 * derived from `rtc` delta clamped to `[0, maxRebootAdvanceMs]` (or post-reboot `elapsed` if
 * larger, up to `maxOf(elapsed, maxRebootAdvanceMs)`), so a user cannot fast-forward a 7-day
 * countdown by rolling the system clock forward and rebooting.
 */
data class CountdownState(
    val durationMs: Long,
    val claimWindowMs: Long,
    val accumulatedMs: Long = 0L,
    val readyAccumulatedMs: Long = 0L,
    val lastSyncRtcMs: Long,
    val lastSyncElapsedMs: Long,
    val lastBootCount: Int = -1,
    val isReady: Boolean = false
) {
    fun advance(
        nowRtc: Long,
        nowElapsed: Long,
        currentBootCount: Int,
        maxRebootAdvanceMs: Long = MAX_REBOOT_ADVANCE_MS
    ): CountdownState {
        val rebooted = (lastBootCount != -1 && currentBootCount != -1 && currentBootCount != lastBootCount) ||
            nowElapsed < lastSyncElapsedMs

        val delta = if (!rebooted) {
            (nowElapsed - lastSyncElapsedMs).coerceAtLeast(0L)
        } else {
            val safeElapsed = nowElapsed.coerceAtLeast(0L)
            val rtcDelta = (nowRtc - lastSyncRtcMs).coerceIn(0L, maxRebootAdvanceMs)
            maxOf(safeElapsed, rtcDelta).coerceAtMost(maxOf(safeElapsed, maxRebootAdvanceMs))
        }

        var nextAcc = accumulatedMs
        var nextReadyAcc = readyAccumulatedMs
        var nextReady = isReady

        if (!nextReady) {
            nextAcc += delta
            if (nextAcc >= durationMs) {
                val overflow = nextAcc - durationMs
                nextAcc = durationMs
                nextReady = true
                nextReadyAcc += overflow
            }
        } else {
            nextReadyAcc += delta
        }

        return copy(
            accumulatedMs = nextAcc,
            readyAccumulatedMs = nextReadyAcc,
            lastSyncRtcMs = nowRtc,
            lastSyncElapsedMs = nowElapsed,
            lastBootCount = currentBootCount,
            isReady = nextReady
        )
    }

    fun status(): CountdownStatus = when {
        !isReady -> CountdownStatus.Waiting((durationMs - accumulatedMs).coerceAtLeast(0L))
        readyAccumulatedMs >= claimWindowMs -> CountdownStatus.Expired
        else -> CountdownStatus.Ready((claimWindowMs - readyAccumulatedMs).coerceAtLeast(0L))
    }

    fun progressFraction(): Float =
        if (durationMs <= 0L) 1f else (accumulatedMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("durationMs", durationMs)
        put("claimWindowMs", claimWindowMs)
        put("accumulatedMs", accumulatedMs)
        put("readyAccumulatedMs", readyAccumulatedMs)
        put("lastSyncRtcMs", lastSyncRtcMs)
        put("lastSyncElapsedMs", lastSyncElapsedMs)
        put("lastBootCount", lastBootCount)
        put("isReady", isReady)
    }

    companion object {
        const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L
        const val TWENTY_FOUR_HOURS_MS = 24L * 60L * 60L * 1000L
        const val MAX_REBOOT_ADVANCE_MS = 24L * 60L * 60L * 1000L

        fun start(
            durationMs: Long = SEVEN_DAYS_MS,
            claimWindowMs: Long = TWENTY_FOUR_HOURS_MS,
            nowRtc: Long,
            nowElapsed: Long,
            bootCount: Int
        ): CountdownState = CountdownState(
            durationMs = durationMs,
            claimWindowMs = claimWindowMs,
            accumulatedMs = 0L,
            readyAccumulatedMs = 0L,
            lastSyncRtcMs = nowRtc,
            lastSyncElapsedMs = nowElapsed,
            lastBootCount = bootCount,
            isReady = false
        )

        fun fromJson(obj: JSONObject): CountdownState = CountdownState(
            durationMs = obj.optLong("durationMs", SEVEN_DAYS_MS),
            claimWindowMs = obj.optLong("claimWindowMs", TWENTY_FOUR_HOURS_MS),
            accumulatedMs = obj.optLong("accumulatedMs", 0L).coerceAtLeast(0L),
            readyAccumulatedMs = obj.optLong("readyAccumulatedMs", 0L).coerceAtLeast(0L),
            lastSyncRtcMs = obj.optLong("lastSyncRtcMs", 0L),
            lastSyncElapsedMs = obj.optLong("lastSyncElapsedMs", 0L),
            lastBootCount = obj.optInt("lastBootCount", -1),
            isReady = obj.optBoolean("isReady", false)
        )
    }
}
