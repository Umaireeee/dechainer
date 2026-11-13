package io.github.warleysr.dechainer.models

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Apps that are suspended on a schedule. Unlike an allowed-hours schedule, which blocks an app
 * when it is opened outside its hours, downtime suspends the apps as a whole while a window is
 * running (so their notifications stop too) and gives them back when it ends.
 *
 * The [windows] are the times the apps are *suspended*. They reuse [TimeWindow], so they carry
 * days and an on/off switch, and a window that ends before it starts runs past midnight.
 */
data class DowntimeRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val packageNames: Set<String> = emptySet(),
    /** Every member of these groups is included, as the group is at the time of the check. */
    val groupIds: Set<String> = emptySet(),
    val windows: List<TimeWindow> = emptyList()
)

object Downtime {
    /** What a rule covers right now: its own apps plus the current members of its groups. */
    fun packagesOf(rule: DowntimeRule, groups: List<AppGroup>): Set<String> =
        rule.packageNames + groups.filter { it.id in rule.groupIds }.flatMap { it.packageNames }

    /** The apps that must be suspended on [day] (ISO 1..7) at [minuteOfDay]. */
    fun suspendedAt(rules: List<DowntimeRule>, groups: List<AppGroup>, day: Int, minuteOfDay: Int): Set<String> =
        rules.filter { rule -> rule.windows.any { w -> w.spansOn(day).any { it.contains(minuteOfDay) } } }
            .flatMapTo(mutableSetOf()) { packagesOf(it, groups) }

    /**
     * The next moment after [now] at which the set of suspended apps can change, or null when no
     * window is ever going to start or end. Looks a week ahead, which covers every weekly pattern.
     */
    fun nextBoundaryMillis(
        rules: List<DowntimeRule>,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        var best: LocalDateTime? = null
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val day = date.dayOfWeek.value
            for (rule in rules) for (window in rule.windows) for (span in window.spansOn(day)) {
                for (minute in intArrayOf(span.start, span.end)) {
                    val moment = date.atStartOfDay().plusMinutes(minute.toLong())
                    if (moment.isAfter(now) && (best == null || moment.isBefore(best))) best = moment
                }
            }
        }
        return best?.atZone(zone)?.toInstant()?.toEpochMilli()
    }

    /**
     * Loosening means some app is suspended for less time than before on some day: a window
     * shortened or turned off, a day dropped, an app or group removed.
     */
    fun isLooser(old: List<DowntimeRule>, new: List<DowntimeRule>, groups: List<AppGroup>): Boolean {
        val packages = old.flatMapTo(mutableSetOf()) { packagesOf(it, groups) }
        fun spans(rules: List<DowntimeRule>, pkg: String, day: Int) = TimeWindow.merge(
            rules.filter { pkg in packagesOf(it, groups) }.flatMap { r -> r.windows.flatMap { it.spansOn(day) } }
        )
        return (1..7).any { day ->
            packages.any { pkg ->
                val kept = spans(new, pkg, day)
                spans(old, pkg, day).any { o -> kept.none { it.start <= o.start && it.end >= o.end } }
            }
        }
    }

    /**
     * What to change on the device so it matches [should]. Downtime only gives back what it
     * suspended itself ([held]) and never touches an app that was already suspended for another
     * reason (the user, impulse lock, ...), so it can't end someone else's suspension.
     */
    data class Plan(val release: Set<String>, val suspend: Set<String>, val held: Set<String>)

    fun plan(should: Set<String>, held: Set<String>, suspendedNow: Set<String>): Plan {
        val release = held - should
        val suspend = should.filterTo(mutableSetOf()) { it !in suspendedNow }
        return Plan(release, suspend, (held intersect should) + suspend)
    }
}
