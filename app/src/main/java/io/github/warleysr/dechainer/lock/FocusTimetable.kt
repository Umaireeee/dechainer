package io.github.warleysr.dechainer.lock

import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.models.BlockSchedule
import java.time.Instant
import java.time.ZoneId

/**
 * One open window of a FOCUS timetable entry (blueprint 6.3). [key] names this window of this entry,
 * so a window is started or skipped once, however many wake-ups see it.
 */
data class FocusWindow(val scheduleId: String, val startsAt: Long, val endsAt: Long) {
    val key: String get() = "$scheduleId@$startsAt"
}

/** A window with what to do about it. */
data class FocusDue(val window: FocusWindow, val decision: FocusStart) {
    /** Done with this window: started, or skipped for good. A deferred start (`At`) is not. */
    val settled: Boolean get() = decision !is FocusStart.At
}

/**
 * Which FOCUS entry should be starting a block now. Pure: it reads no clock, and never starts
 * anything itself; the lock engine asks it on every wake-up and acts on the answer.
 */
object FocusTimetable {

    /** Every enabled FOCUS entry whose window is open at [now], its end capped at the longest block. */
    fun openWindows(now: Long, zone: ZoneId, schedules: List<BlockSchedule>): List<FocusWindow> {
        val local = Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
        return schedules.filter { it.isFocus }.mapNotNull { entry ->
            val start = entry.currentWindowStart(local)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: return@mapNotNull null
            val end = entry.currentWindowEnd(local)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: return@mapNotNull null
            FocusWindow(entry.id, start, minOf(end, start + Rules.FOCUS_BLOCK_MAX_MS))
        }
    }

    /**
     * The window to act on, or null. A window already settled ([settledKeys]) is left alone. When
     * several are open the one that ends last is taken, and the others are settled with it: one
     * block covers them.
     *
     * [urgeEndsAt] and the two flags come from the other locks (5.3); the rest day is Phase 5's.
     */
    fun due(
        now: Long, zone: ZoneId, schedules: List<BlockSchedule>, settledKeys: Set<String>,
        punishmentActive: Boolean, restDay: Boolean, urgeEndsAt: Long
    ): FocusDue? {
        val window = openWindows(now, zone, schedules)
            .filter { it.key !in settledKeys }
            .maxByOrNull { it.endsAt } ?: return null
        val decision = FocusStartRule.decide(
            now, window.endsAt, punishmentActive, restDay, urgeEndsAt, minLeftMs = Rules.FOCUS_BLOCK_MIN_MS
        )
        return FocusDue(window, decision)
    }

    /**
     * Whether a window of [minutes] can be a FOCUS entry: null if so, a negative number if it is shorter
     * than a block may be, a positive one if it is longer.
     */
    fun checkWindow(minutes: Int): Int? = when {
        minutes < Rules.FOCUS_BLOCK_MIN_MS / 60_000L -> -1
        minutes > Rules.FOCUS_BLOCK_MAX_MS / 60_000L -> 1
        else -> null
    }

    /** The end a deferred start ([FocusStart.At]) or an immediate one runs to. */
    fun blockEnd(window: FocusWindow): Long = window.endsAt
}
