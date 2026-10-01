package io.github.warleysr.dechainer.lock

import java.time.Instant
import java.time.ZoneId

/**
 * `LockEngine.plan` (blueprint 5.1): a pure function from the time and the stored state to what
 * the phone should look like. It reads no clock and asks Android nothing, so every rule here is
 * covered by plain unit tests.
 *
 * Precedence (5.3), as built:
 * - Every running hold applies and only ever adds: an app is blocked if any hold wants it blocked.
 *   For the brick modes that is exactly the intersection of their allow sets ([LockAllow]).
 * - The bricks win in what is shown and when the phone unlocks: it is pinned, the safety
 *   restrictions are on, the lock screen names a brick (not a schedule), and nothing ends until
 *   every brick has ended. A schedule or a daily limit ending changes none of that.
 * - Reading "win over" as "a focus-allowed app is let through a limit or schedule that blocks it"
 *   would loosen a lock, so this does not: such an app stays blocked.
 */
object LockPlanner {

    fun plan(now: Long, zone: ZoneId, state: LockState): LockPlan {
        val zoned = Instant.ofEpochMilli(now).atZone(zone)
        val local = zoned.toLocalDateTime()
        val schedulesNext = state.schedules.flatMap { it.boundariesAfter(zoned) }.map { it.toInstant().toEpochMilli() }
        val punishmentActive = state.punishment.activeAt(now)
        // A day that has not started yet is a wake-up: the brick must be on at 00:00, not at the next unrelated sync.
        val punishmentStart = state.punishment.startsAt.takeIf { state.punishment.endsAt > now && it > now }

        if (!state.deviceOwner) {
            // Nothing can be applied without Device Owner; the alarm still wakes the engine at each
            // boundary. A stored block that has ended is still flagged: the record is closed from the
            // time on any phone, so the screen never keeps showing a block that is over.
            return LockPlan(
                deviceOwner = false, holds = emptyList(), desiredApps = emptySet(),
                desiredRestrictions = emptySet(), desiredSites = emptySet(), brick = false, brickStatus = null,
                punishmentActive = punishmentActive, holdClock = false,
                expiredFocusBlock = state.focus.brickEndsAt in 1..now,
                nextWakeAt = (schedulesNext + listOfNotNull(punishmentStart, state.focus.wakeAt.takeIf { it > now }))
                    .filter { it > now }.minOrNull()
            )
        }

        val holds = mutableListOf<Hold>()
        // The bricks that run, with the owner's apps each lets through (for the pin).
        val running = mutableListOf<RunningBrick>()
        // A FOCUS entry holds nothing by itself: when its window opens it starts a focus block, which
        // is the hold (see FocusTimetable). Its boundaries are still wake-ups, in schedulesNext above.
        val activeSchedules = state.schedules.filter { !it.isFocus && it.isActiveAt(local) }

        // What each open window takes away: its own list, or with "allow only" on, every app with an
        // icon that isn't on its list.
        activeSchedules.forEach { schedule ->
            val end = schedule.currentWindowEnd(local)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
            val apps = if (schedule.allowOnly) allowOnlyBlocked(state.phone, schedule.packages) else schedule.packages
            holds += Hold(LockMode.SCHEDULE, schedule.name, end, apps)
        }

        // The urge lock: ten minutes, ended only by the time.
        if (state.urge.endsAt > now) {
            holds += Hold(LockMode.URGE_LOCK, null, state.urge.endsAt, LockAllow.blocked(state.phone, LockAllow.URGE, emptySet()))
            running += RunningBrick(LockMode.URGE_LOCK, state.urge.endsAt)
        }

        // The focus block. Its end is read against `now`: a block whose end has passed is over
        // whether or not any alarm ever said so (R1).
        val focus = state.focus
        var expiredFocusBlock = false
        if (focus.brickEndsAt > 0L) {
            if (focus.brickEndsAt > now) {
                holds += Hold(
                    LockMode.FOCUS_BLOCK, null, focus.brickEndsAt,
                    LockAllow.blocked(state.phone, LockAllow.FOCUS, focus.allowedApps)
                )
                running += RunningBrick(LockMode.FOCUS_BLOCK, focus.brickEndsAt, focus.allowedApps)
            } else {
                expiredFocusBlock = true
            }
        } else if (focus.sessionLockEndsAt > now) {
            // The old locked session outside a block: only the allowed apps and the essentials work.
            holds += Hold(LockMode.FOCUS_SESSION, null, focus.sessionLockEndsAt, allowOnlyBlocked(state.phone, focus.allowedApps))
        }

        // A punishment day: the whole window, calls and the alarm only (plus the study-app list, empty by default).
        if (punishmentActive) {
            holds += Hold(
                LockMode.PUNISHMENT_DAY, null, state.punishment.endsAt,
                LockAllow.blocked(state.phone, LockAllow.PUNISHMENT, state.punishment.ownerApps)
            )
            running += RunningBrick(LockMode.PUNISHMENT_DAY, state.punishment.endsAt, state.punishment.ownerApps)
        }

        // Daily limits: an app that has used its time stays paused until the day resets.
        if (state.limits.reachedApps.isNotEmpty() && state.limits.resetsAt > now) {
            holds += Hold(LockMode.DAILY_LIMIT, null, state.limits.resetsAt, state.limits.reachedApps)
        }

        val brickStatus = statusOf(running)
        val brick = brickStatus != null
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
            brickStatus = brickStatus,
            punishmentActive = punishmentActive,
            holdClock = true,
            expiredFocusBlock = expiredFocusBlock,
            nextWakeAt = (schedulesNext + holdEnds + listOfNotNull(punishmentStart, state.focus.wakeAt))
                .filter { it > now }.minOrNull()
        )
    }

    /**
     * Which brick the lock screen names, when the phone unlocks, and which owner apps may open pinned,
     * from the running bricks. The phone unlocks when the last one ends; the one that ends last is
     * named, and when two end together the day wins over the urge and the urge over a focus block.
     */
    fun statusOf(bricks: List<RunningBrick>): BrickStatus? {
        if (bricks.isEmpty()) return null
        val primary = bricks.maxWith(compareBy({ it.endsAt }, { it.mode.labelRank }))
        return BrickStatus(
            primary = primary.mode,
            endsAt = primary.endsAt,
            modes = bricks.mapTo(mutableSetOf()) { it.mode },
            // Every running brick must allow an app for it to open: the intersection of their lists.
            ownerApps = bricks.map { it.ownerApps }.reduce { a, b -> a intersect b }
        )
    }

    /**
     * The same status from the raw stored inputs, without planning: the lock screen needs it on the
     * first frame, before any pass has run. Agrees with [plan] by construction (same rule, same inputs).
     */
    fun quickStatus(now: Long, focus: FocusInput, urge: UrgeInput, punishment: PunishmentInput): BrickStatus? =
        statusOf(
            listOfNotNull(
                RunningBrick(LockMode.URGE_LOCK, urge.endsAt).takeIf { urge.endsAt > now },
                RunningBrick(LockMode.FOCUS_BLOCK, focus.brickEndsAt, focus.allowedApps).takeIf { focus.brickEndsAt > now },
                RunningBrick(LockMode.PUNISHMENT_DAY, punishment.endsAt, punishment.ownerApps).takeIf { punishment.activeAt(now) }
            )
        )

    /**
     * For each blocked app, the hold that keeps it blocked: a brick before anything else, and among
     * equals the one that lasts longest. Protected apps are never listed.
     */
    fun blockedBy(holds: List<Hold>, protectedApps: Set<String>): Map<String, Hold> {
        val out = HashMap<String, Hold>()
        holds.forEach { hold ->
            hold.apps.forEach { pkg ->
                val existing = out[pkg]
                if (pkg !in protectedApps && (existing == null || outranks(hold, existing))) out[pkg] = hold
            }
        }
        return out
    }

    private fun outranks(a: Hold, b: Hold): Boolean =
        if (a.mode.isBrick != b.mode.isBrick) a.mode.isBrick else a.endsAt > b.endsAt

    /** What an "allow only" window suspends: every app with an icon except [allowed] and the essentials. */
    private fun allowOnlyBlocked(phone: PhoneFacts, allowed: Set<String>): Set<String> =
        phone.launcherApps - allowed - phone.protectedApps - phone.alwaysAllowed
}
