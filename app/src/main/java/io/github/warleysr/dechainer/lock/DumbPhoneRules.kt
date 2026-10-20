package io.github.warleysr.dechainer.lock

import io.github.warleysr.dechainer.models.BlockSchedule
import java.time.DayOfWeek

/**
 * The pure rules of the dumb-phone lock: no Android in here, so plain unit tests cover them.
 *
 * Once started, the phone is locked every day except one open window ([OPEN_START_MINUTE] to
 * [OPEN_END_MINUTE]). Locked: every app with an icon is suspended except the essentials the allow-only
 * window always leaves open (calls, SMS, camera, Settings, the alarm clock). Open: nothing is suspended.
 * In both, the permanent rules ([LockRestrictions.DUMB]) and the adult-site filter stay on. Nothing in
 * the app edits any of it; the only way out is the exit wait.
 */
object DumbPhoneRules {
    /** After starting, the lock can be undone at once for this long. A safety net for the first build. */
    const val UNDO_MS = 10L * 60 * 1000

    /** The daily open window, in minutes after midnight: 21:00 to 22:30. */
    const val OPEN_START_MINUTE = 21 * 60
    const val OPEN_END_MINUTE = 22 * 60 + 30

    const val SCHEDULE_ID = "dumb-phone"

    /** Apps allowed while locked, beyond the essentials. None: a dumb phone is a phone. */
    val ALLOWED_APPS: Set<String> = emptySet()

    /** The system file pickers: closed while locked, so Files cannot be reached through them. */
    val FILE_PICKERS: Set<String> = setOf("com.android.documentsui", "com.google.android.documentsui")

    /**
     * The locked part of the day, every day: from the end of the open window round to its start the
     * next day. That window crosses midnight, which the schedule reads as belonging to the day it starts.
     */
    fun schedule(): BlockSchedule = BlockSchedule(
        id = SCHEDULE_ID,
        name = "Dumb phone",
        enabled = true,
        days = DayOfWeek.entries.toSet(),
        startMinute = OPEN_END_MINUTE,
        endMinute = OPEN_START_MINUTE,
        packages = ALLOWED_APPS,
        allowOnly = true
    )

    /** True while the phone is in its open window; [minuteOfDay] is minutes after local midnight. */
    fun isOpenAt(minuteOfDay: Int): Boolean = minuteOfDay in OPEN_START_MINUTE until OPEN_END_MINUTE

    /** The open window as shown on screen, for example "21:00 to 22:30". */
    fun openHoursLabel(): String =
        "${BlockSchedule.formatMinute(OPEN_START_MINUTE)} to ${BlockSchedule.formatMinute(OPEN_END_MINUTE)}"

    /** True while the lock was started less than [UNDO_MS] ago. [startedAt] is 0 when it never started. */
    fun undoAvailable(now: Long, startedAt: Long): Boolean =
        startedAt > 0L && now >= startedAt && now < startedAt + UNDO_MS

    /** How much of the undo window is left, 0 when it is over or the lock never started. */
    fun undoRemainingMs(now: Long, startedAt: Long): Long =
        if (undoAvailable(now, startedAt)) startedAt + UNDO_MS - now else 0L

    /** The days and hours of an exit wait that has [remainingMs] left, for the wait screen. */
    fun splitWait(remainingMs: Long): Pair<Long, Long> {
        val hours = (remainingMs.coerceAtLeast(0L) + 3_599_999L) / 3_600_000L
        return (hours / 24) to (hours % 24)
    }
}
