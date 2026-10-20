package io.github.warleysr.dechainer.lock

import io.github.warleysr.dechainer.models.BlockSchedule
import java.time.DayOfWeek

/**
 * The pure rules of the dumb-phone lock: no Android in here, so plain unit tests cover them.
 *
 * One lock, always on once started: every app with an icon is suspended except the essentials the
 * allow-only window always leaves open (calls, SMS, camera, Settings, the alarm clock) and the apps
 * in [ALLOWED_APPS]. Nothing in the app edits any of it. The only way out is the exit wait.
 */
object DumbPhoneRules {
    /** After starting, the lock can be undone at once for this long. A safety net for the first build. */
    const val UNDO_MS = 10L * 60 * 1000

    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_LIMIT_MINUTES = 45

    const val SCHEDULE_ID = "dumb-phone"

    /** The only apps beyond the essentials. Fixed: the app has no screen to change it. */
    val ALLOWED_APPS: Set<String> = setOf(WHATSAPP)

    /** The system file pickers: closed, so Files cannot be reached through them. */
    val FILE_PICKERS: Set<String> = setOf("com.android.documentsui", "com.google.android.documentsui")

    /** A full-day window, every day: start equals end, which the schedule reads as 24 hours. */
    fun schedule(): BlockSchedule = BlockSchedule(
        id = SCHEDULE_ID,
        name = "Dumb phone",
        enabled = true,
        days = DayOfWeek.entries.toSet(),
        startMinute = 0,
        endMinute = 0,
        packages = ALLOWED_APPS,
        allowOnly = true
    )

    /** True while the lock was started less than [UNDO_MS] ago. [startedAt] is 0 when it never started. */
    fun undoAvailable(now: Long, startedAt: Long): Boolean =
        startedAt > 0L && now >= startedAt && now < startedAt + UNDO_MS

    /** How much of the undo window is left, 0 when it is over or the lock never started. */
    fun undoRemainingMs(now: Long, startedAt: Long): Long =
        if (undoAvailable(now, startedAt)) startedAt + UNDO_MS - now else 0L

    /**
     * Apps whose daily limit has run out. WhatsApp is also counted as out of time when Usage access
     * is off, because without it the time cannot be measured: switching it off must never lift the limit.
     */
    fun limitedApps(reached: Set<String>, usageAccess: Boolean): Set<String> =
        if (usageAccess) reached else reached + WHATSAPP

    /** The days and hours of an exit wait that has [remainingMs] left, for the wait screen. */
    fun splitWait(remainingMs: Long): Pair<Long, Long> {
        val hours = (remainingMs.coerceAtLeast(0L) + 3_599_999L) / 3_600_000L
        return (hours / 24) to (hours % 24)
    }
}
