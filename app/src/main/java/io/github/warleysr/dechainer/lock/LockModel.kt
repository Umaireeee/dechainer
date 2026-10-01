package io.github.warleysr.dechainer.lock

import io.github.warleysr.dechainer.models.BlockSchedule

/**
 * The modes the engine can be holding the phone in. The first three are the blueprint's (5.2). The
 * rest exist in the app today and are carried unchanged until they are removed: [IMPULSE_LOCK] and
 * [RIDE_LOCK] go in Phase 2 (merged into the urge lock), and [FOCUS_SESSION], the old "lock apps
 * during a session" option, is not in the blueprint at all.
 */
enum class LockMode { SCHEDULE, DAILY_LIMIT, FOCUS_BLOCK, FOCUS_SESSION, IMPULSE_LOCK, RIDE_LOCK }

/**
 * One reason some apps are taken away until [endsAt] (epoch millis on the trusted clock;
 * [Long.MAX_VALUE] means "until something else ends it"). [name] is the schedule's own name, or null
 * when the mode has a fixed label that the Android side supplies.
 */
data class Hold(val mode: LockMode, val name: String?, val endsAt: Long, val apps: Set<String>)

/** What is on the phone, as plain sets, so [LockPlanner] never asks the system anything. */
data class PhoneFacts(
    /** Every app with a launcher icon. */
    val launcherApps: Set<String>,
    /** Never suspended by anything: this app, the launcher, the dialer, keyboards, system UI. */
    val protectedApps: Set<String>,
    /** Alarm-clock apps, left alone by a brick so a morning alarm still rings. */
    val alarmApps: Set<String>,
    /** Never taken away by an allow-only window: Settings, file picker, SMS, camera and the like. */
    val alwaysAllowed: Set<String>
)

/** The Pomodoro, reduced to what the lock needs. 0 means "none". */
data class FocusInput(
    /** End of a running focus block, or 0. */
    val brickEndsAt: Long = 0L,
    /**
     * End of a locked session outside a block (the old "lock apps" option), 0 when there is none,
     * [Long.MAX_VALUE] while such a session is paused.
     */
    val sessionLockEndsAt: Long = 0L,
    /** Apps the owner allowed during a focus block or a locked session. */
    val allowedApps: Set<String> = emptySet()
)

/** Daily time limits: the apps already out of time today, and when the day resets. */
data class LimitInput(val reachedApps: Set<String> = emptySet(), val resetsAt: Long = Long.MAX_VALUE)

/** Everything [LockPlanner.plan] decides from. */
data class LockState(
    val deviceOwner: Boolean,
    val schedules: List<BlockSchedule>,
    val phone: PhoneFacts,
    val focus: FocusInput = FocusInput(),
    val limits: LimitInput = LimitInput(),
    /** Impulse and ride locks, already resolved to apps by the caller: removed in Phase 2. */
    val extraHolds: List<Hold> = emptyList()
)

/** What the engine should make true right now. */
data class LockPlan(
    val deviceOwner: Boolean,
    /** The holds still running at the planning time, expired ones already dropped. */
    val holds: List<Hold>,
    /** Every app to be suspended: the union of the holds, minus the protected ones. */
    val desiredApps: Set<String>,
    val desiredRestrictions: Set<String>,
    val desiredSites: Set<String>,
    /** The phone is pinned to this app: a focus block is running. */
    val brick: Boolean,
    /** Automatic date, time and time zone must be forced on, and the settings locked. */
    val holdClock: Boolean,
    /**
     * The stored focus block ended at or before the planning time but is still recorded. The caller
     * closes it and plans again (blueprint 5.4: expiry comes from the time, not from an alarm).
     */
    val expiredFocusBlock: Boolean,
    /** The next moment anything could change on its own, or null when nothing will. */
    val nextWakeAt: Long?
)

/** For the Apps screen: which hold keeps an app blocked right now (the longest one, if several do). */
data class ActiveBlock(val scheduleName: String, val endsAtMillis: Long)

/** Restriction keys as `UserManager` spells them, kept here so the planner needs no Android. */
object LockRestrictions {
    const val DATE_TIME = "no_config_date_time"
    const val SAFE_BOOT = "no_safe_boot"
    const val DEBUGGING = "no_debugging_features"
    const val FACTORY_RESET = "no_factory_reset"
    const val ADD_USER = "no_add_user"
    const val USER_SWITCH = "no_user_switch"

    /**
     * A brick closes every way around it for exactly as long as the block runs: safe mode (boots
     * without this app), USB debugging (ADB can lift the pin), a factory reset, and other users (a
     * guest user has no brick).
     */
    val BRICK = setOf(SAFE_BOOT, DEBUGGING, FACTORY_RESET, ADD_USER, USER_SWITCH)
}
