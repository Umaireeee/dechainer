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
 * `elapsedRealtime()`, making it immune to wall-clock manipulation. Across reboots, forward RTC
 * jumps are capped both per-reboot ([maxRebootAdvanceMs]) AND across the entire lifetime of the
 * countdown ([MAX_TOTAL_REBOOT_CREDIT_MS]), so repeated clock-forward + reboot cycles cannot
 * fast-forward a 7-day timelock.
 */
data class CountdownState(
    val durationMs: Long,
    val claimWindowMs: Long,
    val accumulatedMs: Long = 0L,
    val readyAccumulatedMs: Long = 0L,
    val lastSyncRtcMs: Long,
    val lastSyncElapsedMs: Long,
    val lastBootCount: Int = -1,
    val isReady: Boolean = false,
    val rebootRtcCreditedMs: Long = 0L
) {
    fun advance(
        nowRtc: Long,
        nowElapsed: Long,
        currentBootCount: Int,
        maxRebootAdvanceMs: Long = MAX_REBOOT_ADVANCE_MS
    ): CountdownState {
        val rebooted = (lastBootCount != -1 && currentBootCount != -1 && currentBootCount != lastBootCount) ||
            nowElapsed < lastSyncElapsedMs

        var nextRebootCredit = rebootRtcCreditedMs
        val delta = if (!rebooted) {
            (nowElapsed - lastSyncElapsedMs).coerceAtLeast(0L)
        } else {
            val safeElapsed = nowElapsed.coerceAtLeast(0L)
            val remainingBudget = (MAX_TOTAL_REBOOT_CREDIT_MS - rebootRtcCreditedMs).coerceAtLeast(0L)
            val effectiveCap = minOf(maxRebootAdvanceMs, remainingBudget)
            val rtcDelta = (nowRtc - lastSyncRtcMs).coerceIn(0L, effectiveCap)
            nextRebootCredit = (rebootRtcCreditedMs + rtcDelta).coerceAtMost(MAX_TOTAL_REBOOT_CREDIT_MS)
            maxOf(safeElapsed, rtcDelta)
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
            isReady = nextReady,
            rebootRtcCreditedMs = nextRebootCredit
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
        put("rebootRtcCreditedMs", rebootRtcCreditedMs)
    }

    companion object {
        const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L
        const val TWENTY_FOUR_HOURS_MS = 24L * 60L * 60L * 1000L
        const val MAX_REBOOT_ADVANCE_MS = 24L * 60L * 60L * 1000L
        const val MAX_TOTAL_REBOOT_CREDIT_MS = 24L * 60L * 60L * 1000L

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
            isReady = false,
            rebootRtcCreditedMs = 0L
        )

        fun fromJson(obj: JSONObject): CountdownState = CountdownState(
            durationMs = obj.optLong("durationMs", SEVEN_DAYS_MS),
            claimWindowMs = obj.optLong("claimWindowMs", TWENTY_FOUR_HOURS_MS),
            accumulatedMs = obj.optLong("accumulatedMs", 0L).coerceAtLeast(0L),
            readyAccumulatedMs = obj.optLong("readyAccumulatedMs", 0L).coerceAtLeast(0L),
            lastSyncRtcMs = obj.optLong("lastSyncRtcMs", 0L),
            lastSyncElapsedMs = obj.optLong("lastSyncElapsedMs", 0L),
            lastBootCount = obj.optInt("lastBootCount", -1),
            isReady = obj.optBoolean("isReady", false),
            rebootRtcCreditedMs = obj.optLong("rebootRtcCreditedMs", 0L).coerceAtLeast(0L)
        )
    }
}
