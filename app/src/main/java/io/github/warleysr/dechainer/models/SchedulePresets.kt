package io.github.warleysr.dechainer.models

import android.os.UserManager
import java.time.DayOfWeek

/**
 * Ready-made starting points for a schedule. Each one only fills in the editor: nothing is saved,
 * locked or applied until the person reviews it and taps save.
 *
 * All of them are "allow only" windows with an empty allow-list, i.e. the phone and essentials
 * only; the person adds the few apps they actually need (lecture app, notes, PDF reader).
 */
enum class SchedulePreset(
    val days: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
    val restrictions: Set<String>
) {
    /** Everything but the essentials from late evening until morning. */
    BEDTIME(
        days = DayOfWeek.entries.toSet(),
        startMinute = 22 * 60 + 30,
        endMinute = 6 * 60 + 30,
        restrictions = setOf(UserManager.DISALLOW_INSTALL_APPS)
    ),

    /** Weekday study hours. */
    STUDY_HOURS(
        days = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        ),
        startMinute = 9 * 60,
        endMinute = 17 * 60,
        restrictions = emptySet()
    ),

    /** Every day, long hours, and no installing a distraction or adding a VPN to get around it. */
    EXAM_WEEK(
        days = DayOfWeek.entries.toSet(),
        startMinute = 8 * 60,
        endMinute = 22 * 60,
        restrictions = setOf(UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_CONFIG_VPN)
    ),

    /** The small hours, when most bad scrolling and worse browsing happens. */
    NIGHT_DETOX(
        days = DayOfWeek.entries.toSet(),
        startMinute = 23 * 60,
        endMinute = 5 * 60,
        restrictions = setOf(UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_CONFIG_VPN)
    );

    /** A fresh, unlocked draft for this preset. */
    fun toDraft(id: String, name: String): BlockSchedule = BlockSchedule(
        id = id,
        name = name,
        enabled = true,
        days = days,
        startMinute = startMinute,
        endMinute = endMinute,
        restrictions = restrictions,
        lockWhileActive = false,
        allowOnly = true
    )
}
