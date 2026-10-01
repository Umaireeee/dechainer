package io.github.warleysr.dechainer.lock

import io.github.warleysr.dechainer.data.LockSafety
import java.time.Instant
import java.time.ZoneId

/**
 * `LockEngine.plan` (blueprint 5.1): a pure function from the time and the stored state to what
 * the phone should look like. It reads no clock and asks Android nothing, so every rule here is
 * covered by plain unit tests.
 *
 * Precedence for this phase: every running hold applies, and an app stays blocked until the last
 * hold that wants it ends. The brick modes winning over schedules and limits is Phase 2's, when the
 * urge lock and punishment day arrive.
 */
object LockPlanner {

    fun plan(now: Long, zone: ZoneId, state: LockState): LockPlan {
        val zoned = Instant.ofEpochMilli(now).atZone(zone)
        val local = zoned.toLocalDateTime()
        val schedulesNext = state.schedules.flatMap { it.boundariesAfter(zoned) }.map { it.toInstant().toEpochMilli() }

        if (!state.deviceOwner) {
            // Nothing can be applied without Device Owner; the alarm still wakes the engine at each boundary.
            return LockPlan(
                deviceOwner = false, holds = emptyList(), desiredApps = emptySet(),
                desiredRestrictions = emptySet(), desiredSites = emptySet(), brick = false, holdClock = false,
                expiredFocusBlock = false, nextWakeAt = schedulesNext.minOrNull()
            )
        }

        val holds = mutableListOf<Hold>()
        val activeSchedules = state.schedules.filter { it.isActiveAt(local) }

        // What each open window takes away: its own list, or with "allow only" on, every app with an
        // icon that isn't on its list.
        activeSchedules.forEach { schedule ->
            val end = schedule.currentWindowEnd(local)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
            val apps = if (schedule.allowOnly) allowOnlyBlocked(state.phone, schedule.packages) else schedule.packages
            holds += Hold(LockMode.SCHEDULE, schedule.name, end, apps)
        }

        // The focus block. Its end is read against `now`: a block whose end has passed is over
        // whether or not any alarm ever said so (R1).
        val focus = state.focus
        var expiredFocusBlock = false
        if (focus.brickEndsAt > 0L) {
            if (focus.brickEndsAt > now) {
                holds += Hold(
                    LockMode.FOCUS_BLOCK, null, focus.brickEndsAt,
                    LockSafety.brickTargets(state.phone.launcherApps, state.phone.protectedApps, state.phone.alarmApps, focus.allowedApps)
                )
            } else {
                expiredFocusBlock = true
            }
        } else if (focus.sessionLockEndsAt > now) {
            // The old locked session outside a block: only the allowed apps and the essentials work.
            holds += Hold(LockMode.FOCUS_SESSION, null, focus.sessionLockEndsAt, allowOnlyBlocked(state.phone, focus.allowedApps))
        }

        // Daily limits: an app that has used its time stays paused until the day resets.
        if (state.limits.reachedApps.isNotEmpty() && state.limits.resetsAt > now) {
            holds += Hold(LockMode.DAILY_LIMIT, null, state.limits.resetsAt, state.limits.reachedApps)
        }

        holds += state.extraHolds.filter { it.endsAt > now }

        val brick = holds.any { it.mode == LockMode.FOCUS_BLOCK }
        val restrictions = activeSchedules.flatMap { it.restrictions }.toMutableSet()
        // Date, time and time zone are locked all the time, not only during blocks (9.2).
        restrictions += LockRestrictions.DATE_TIME
        if (brick) restrictions += LockRestrictions.BRICK

        val holdEnds = holds.map { it.endsAt }.filter { it != Long.MAX_VALUE }
        return LockPlan(
            deviceOwner = true,
            holds = holds,
            desiredApps = holds.flatMapTo(mutableSetOf()) { it.apps } - state.phone.protectedApps,
            desiredRestrictions = restrictions,
            desiredSites = activeSchedules.flatMapTo(mutableSetOf()) { it.websites },
            brick = brick,
            holdClock = true,
            expiredFocusBlock = expiredFocusBlock,
            nextWakeAt = (schedulesNext + holdEnds).filter { it > now }.minOrNull()
        )
    }

    /** For each blocked app, the hold that keeps it blocked longest. Protected apps are never listed. */
    fun blockedBy(holds: List<Hold>, protectedApps: Set<String>): Map<String, Hold> {
        val out = HashMap<String, Hold>()
        holds.forEach { hold ->
            hold.apps.forEach { pkg ->
                val existing = out[pkg]
                // With overlapping holds the app stays blocked until the last one ends.
                if (pkg !in protectedApps && (existing == null || hold.endsAt > existing.endsAt)) out[pkg] = hold
            }
        }
        return out
    }

    /** What an "allow only" window suspends: every app with an icon except [allowed] and the essentials. */
    private fun allowOnlyBlocked(phone: PhoneFacts, allowed: Set<String>): Set<String> =
        phone.launcherApps - allowed - phone.protectedApps - phone.alwaysAllowed
}
