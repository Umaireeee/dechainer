package io.github.warleysr.dechainer.lock

import java.time.Instant
import java.time.ZoneId

/**
 * The pure rules of study mode and lockdown: no Android in here, so plain unit tests cover them.
 *
 * Study mode is started by hand with an end time. Until then the phone is locked the way the old
 * dumb-phone lock was (every app with an icon paused except the essentials the allow-only window
 * always leaves open, plus [ALLOWED_APPS]), the Google app is hidden instead of paused ([HIDDEN_APPS]),
 * and the ways around the lock are closed ([LockRestrictions.STUDY]). When the end time passes the
 * phone is normal again.
 *
 * Lockdown is smaller: it pauses only the apps the owner picked, until its end time.
 *
 * Both only ever get longer. A running session is never shortened and never ended early; the only
 * things a second start can do are extend the end time and, for a lockdown, add apps.
 */
object StudyRules {
    /** The shortest a session can be started for. */
    const val MIN_MS = 10L * 60_000L

    /** The longest a session can run from now, including after an extension. */
    const val MAX_MS = 12L * 3_600_000L

    /** The Gemini app: open during study mode. */
    const val GEMINI = "com.google.android.apps.bard"

    /** The Google app (search, assistant). It is hidden during study mode, not paused. */
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /** Apps that stay open during study mode, beyond the essentials. */
    val ALLOWED_APPS: Set<String> = setOf(GEMINI)

    /** Apps taken away by hiding them rather than pausing them, for as long as study mode runs. */
    val HIDDEN_APPS: Set<String> = setOf(GOOGLE_APP)

    /** The system file pickers: closed while studying, so Files cannot be reached through them. */
    val FILE_PICKERS: Set<String> = setOf("com.android.documentsui", "com.google.android.documentsui")

    /** What a start (or a second start) of a study session or a lockdown turned into. */
    sealed interface Start {
        /** Nothing was running: it runs until [endsAt]. */
        data class Started(val endsAt: Long) : Start

        /** Something was running: it now runs until [endsAt], which is later than before. */
        data class Extended(val endsAt: Long) : Start

        /** An end time that is not later than the running one. A running session is never shortened. */
        data object NotLonger : Start

        /** A running lockdown kept its end time and gained apps. */
        data class AppsAdded(val endsAt: Long) : Start

        /** A lockdown with no apps to pause. */
        data object NothingPicked : Start

        data object TooShort : Start
        data object TooLong : Start

        /** Nothing can be applied without Device Owner. */
        data object NoDeviceOwner : Start
    }

    /**
     * What a request for a session ending at [requestedEndsAt] does, given the end of the one that is
     * already running ([runningEndsAt], at or before [now] when none is).
     */
    fun decide(now: Long, runningEndsAt: Long, requestedEndsAt: Long, deviceOwner: Boolean): Start {
        if (!deviceOwner) return Start.NoDeviceOwner
        val length = requestedEndsAt - now
        if (runningEndsAt > now) {
            return when {
                requestedEndsAt <= runningEndsAt -> Start.NotLonger
                length > MAX_MS -> Start.TooLong
                else -> Start.Extended(requestedEndsAt)
            }
        }
        return when {
            length < MIN_MS -> Start.TooShort
            length > MAX_MS -> Start.TooLong
            else -> Start.Started(requestedEndsAt)
        }
    }

    /**
     * What a request for a lockdown ending at [requestedEndsAt] and pausing [picked] does, given the
     * running one ([runningEndsAt] and [runningApps]). Same length rules as [decide]. On top: with no apps
     * at all there is nothing to hold, and a running lockdown can gain apps without being lengthened, but
     * never lose one and never end sooner.
     */
    fun decideLockdown(
        now: Long,
        runningEndsAt: Long,
        runningApps: Set<String>,
        requestedEndsAt: Long,
        picked: Set<String>,
        deviceOwner: Boolean
    ): Start {
        if (!deviceOwner) return Start.NoDeviceOwner
        val running = runningEndsAt > now
        val apps = lockdownApps(running, runningApps, picked)
        if (apps.isEmpty()) return Start.NothingPicked
        if (running && requestedEndsAt <= runningEndsAt) {
            return if (apps != runningApps) Start.AppsAdded(runningEndsAt) else Start.NotLonger
        }
        return decide(now, runningEndsAt, requestedEndsAt, deviceOwner)
    }

    /**
     * The next moment after [now] when the local clock reads [minuteOfDay] (minutes after midnight):
     * today if that is still ahead, otherwise tomorrow. This is how "until 13:00" becomes an end time.
     */
    fun nextOccurrence(now: Long, zone: ZoneId, minuteOfDay: Int): Long {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val hour = (minuteOfDay / 60).coerceIn(0, 23)
        val minute = (minuteOfDay % 60).coerceIn(0, 59)
        val today = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        if (today > now) return today
        return day.plusDays(1).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * The apps a lockdown holds once [picked] is asked for: while one runs the list only grows, and
     * when none runs the new list is the picked one.
     */
    fun lockdownApps(running: Boolean, current: Set<String>, picked: Set<String>): Set<String> =
        if (running) current + picked else picked

    /**
     * What a lockdown actually pauses: the picked apps, never the protected ones and never the
     * essentials (calls, SMS, camera, Settings, the alarm clock, emergency apps).
     */
    fun lockdownBlocked(phone: PhoneFacts, apps: Set<String>): Set<String> =
        apps - phone.protectedApps - phone.alwaysAllowed - phone.emergencyApps - phone.alarmApps

    /** Whole minutes left, rounded up, so the screen never shows 0 while time remains. */
    fun minutesLeft(now: Long, endsAt: Long): Long =
        if (endsAt <= now) 0L else (endsAt - now + 59_999L) / 60_000L
}

/** The one way out of Device Owner: a long, cancellable wait. Only its screen arithmetic is pure. */
object ExitWait {
    /** The days and hours of an exit wait that has [remainingMs] left, for the wait screen. */
    fun splitWait(remainingMs: Long): Pair<Long, Long> {
        val hours = (remainingMs.coerceAtLeast(0L) + 3_599_999L) / 3_600_000L
        return (hours / 24) to (hours % 24)
    }
}
