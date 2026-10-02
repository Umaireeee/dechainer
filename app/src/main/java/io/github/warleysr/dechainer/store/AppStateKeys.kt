package io.github.warleysr.dechainer.store

/**
 * Keys in the `app_state` table. State that must survive "delete my data" and reboots lives here
 * (blueprint section 7). Only the keys that are used so far are listed; later phases add theirs.
 */
object AppStateKeys {
    /**
     * The trusted clock's last reading, in epoch millis. It is the trusted time, not the raw wall
     * time, so a wall clock that was moved back cannot drag the checkpoint back with it.
     */
    const val LAST_SEEN_WALL = "lastSeenWall"

    /** Elapsed-realtime when [LAST_SEEN_WALL] was written. */
    const val LAST_SEEN_ELAPSED = "lastSeenElapsed"

    /** Android's boot count when [LAST_SEEN_WALL] was written, so a reboot is told from a long uptime. */
    const val LAST_SEEN_BOOT = "lastSeenBoot"

    /** When the running urge lock started and when it ends, in trusted-clock epoch millis. Absent when there is none. */
    const val URGE_LOCK_STARTED = "urgeLockStartedAt"
    const val URGE_LOCK_ENDS = "urgeLockEndsAt"

    /**
     * Keys an older build used for the punishment day, which was removed (blueprint 1.2). Kept only
     * so the update can remove them; nothing reads them.
     */
    val LEGACY_PUNISHMENT_KEYS = listOf("punishmentFrom", "punishmentUntil", "punishmentDate", "punishmentAbortedAt")

    /** The running focus block's story, as [io.github.warleysr.dechainer.focus.FlowState] JSON. Absent when none runs. */
    const val FOCUS_FLOW = "focusFlow"

    /**
     * The FOCUS timetable windows already started or skipped, as `scheduleId@windowStart` keys joined by
     * newlines, newest last, so a window is acted on once however many wake-ups see it.
     */
    const val FOCUS_SETTLED = "focusSettled"

    /** The local date (YYYY-MM-DD) enforcement of the daily checklist began. Not deletable (blueprint 7). */
    const val ACTIVATED_ON = "activatedOn"

    /**
     * The local date (YYYY-MM-DD) of the first data point: week 0 of the weekly reports starts that day.
     * Set once. Not deletable (blueprint 7), so wiping the data does not move the weeks.
     */
    const val WEEK_ANCHOR = "weekAnchor"

    /** Prefix of the flags that mark a one-off migration as done: `migrated:<name>`. */
    const val MIGRATED_PREFIX = "migrated:"
}
