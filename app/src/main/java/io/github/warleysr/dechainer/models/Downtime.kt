package io.github.warleysr.dechainer.models

import io.github.warleysr.dechainer.data.SuspensionCoordinator
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
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
    ): Long? = nextBoundaryMillis(rules, now.atZone(zone), groups = null)

    fun nextBoundaryMillis(
        rules: List<DowntimeRule>,
        now: ZonedDateTime,
        groups: List<AppGroup>? = null
    ): Long? {
        val zone = now.zone
        val zoneRules = zone.rules
        val nowInstant = now.toInstant()
        val scopedRules = if (groups == null) rules else rules.filter { packagesOf(it, groups).isNotEmpty() }
        if (scopedRules.isEmpty()) return null

        var best: Instant? = null
        val startDate = now.toLocalDate()
        for (offset in 0..7) {
            val date = startDate.plusDays(offset.toLong())
            val day = date.dayOfWeek.value
            val prevDay = TimeWindow.previousDay(day)
            val nextDay = if (day == 7) 1 else day + 1
            for (rule in scopedRules) {
                for (window in rule.windows) {
                    for (span in window.spansOn(day)) {
                        for (minute in intArrayOf(span.start, span.end)) {
                            // Skip redundant midnight boundaries when the same rule stays active across 23:59 -> 00:00.
                            if (minute == 0 && rule.windows.any { w -> w.spansOn(prevDay).any { it.contains(1439) } }) {
                                continue
                            }
                            if (minute == 1440 && rule.windows.any { w -> w.spansOn(nextDay).any { it.contains(0) } }) {
                                continue
                            }
                            val moment = date.atStartOfDay().plusMinutes(minute.toLong())
                            for (instant in SuspensionCoordinator.resolvedInstants(moment, zone, zoneRules)) {
                                if (instant.isAfter(nowInstant) && (best == null || instant.isBefore(best))) {
                                    best = instant
                                }
                            }
                        }
                    }
                }
            }
        }
        return best?.toEpochMilli()
    }

    /**
     * Loosening means some app is suspended for less time than before on some day: a window
     * shortened or turned off, a day dropped, an app or group removed.
     */
    fun isLooser(old: List<DowntimeRule>, new: List<DowntimeRule>, groups: List<AppGroup>): Boolean {
        val oldPackagesByRule = old.associateWith { packagesOf(it, groups) }
        val newPackagesByRule = new.associateWith { packagesOf(it, groups) }
        val packages = oldPackagesByRule.values.flatten().toSet()
        fun spans(byRule: Map<DowntimeRule, Set<String>>, pkg: String, day: Int) = TimeWindow.merge(
            byRule.entries.filter { pkg in it.value }.flatMap { (r, _) -> r.windows.flatMap { it.spansOn(day) } }
        )
        return (1..7).any { day ->
            packages.any { pkg ->
                val kept = spans(newPackagesByRule, pkg, day)
                spans(oldPackagesByRule, pkg, day).any { o -> kept.none { it.start <= o.start && it.end >= o.end } }
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
