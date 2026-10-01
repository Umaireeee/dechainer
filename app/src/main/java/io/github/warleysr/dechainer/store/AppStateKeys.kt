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
     * The punishment day: the window it holds in (trusted-clock epoch millis, start inclusive, end
     * exclusive) and the local date it belongs to. Not deletable through data-delete: deleting data
     * never cancels or shortens a punishment (blueprint 6.4).
     */
    const val PUNISHMENT_FROM = "punishmentFrom"
    const val PUNISHMENT_UNTIL = "punishmentUntil"
    const val PUNISHMENT_DATE = "punishmentDate"

    /** Set when the crash-loop breaker cut a punishment day short, so the day is on record as ended by the system. */
    const val PUNISHMENT_ABORTED_AT = "punishmentAbortedAt"

    /** Prefix of the flags that mark a one-off migration as done: `migrated:<name>`. */
    const val MIGRATED_PREFIX = "migrated:"
}
