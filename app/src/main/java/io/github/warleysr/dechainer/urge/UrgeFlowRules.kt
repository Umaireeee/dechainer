package io.github.warleysr.dechainer.urge

import io.github.warleysr.dechainer.lock.UrgeStart

/** The urge flow's screens after the lock starts. */
enum class UrgeScreen { BREATHING, FINISHED }

/** When the breathing runs: [startsAt] to [endsAt] (trusted-clock millis), and whether the urge lock itself was started for it. */
data class BreathingWindow(val startsAt: Long, val endsAt: Long, val lockStarted: Boolean) {
    val totalMs: Long get() = (endsAt - startsAt).coerceAtLeast(0L)
}

/**
 * What the urge flow decides, as pure functions over stored data and a `now` that is handed in
 * (blueprint 5.1 and 6.2). Nothing here reads a clock or touches Android.
 */
object UrgeFlowRules {

    /**
     * The breathing window for what [io.github.warleysr.dechainer.lock.UrgeLockRule] decided (5.3).
     * A fresh lock breathes for its full length, from the moment it was stored ([lockStartedAt]).
     * A lock already running (a second tap, or one the tile started) breathes until it ends, never
     * longer, and never extends it. Inside a focus block, or without Device Owner, no lock starts
     * and the breathing still runs for the full [lockMs] from [now].
     */
    fun breathingFor(start: UrgeStart, now: Long, lockMs: Long, lockStartedAt: Long? = null): BreathingWindow = when (start) {
        is UrgeStart.Started -> BreathingWindow((lockStartedAt ?: now).coerceAtMost(now), start.endsAt, lockStarted = true)
        is UrgeStart.AlreadyRunning ->
            BreathingWindow((lockStartedAt ?: now).coerceAtMost(now), start.endsAt, lockStarted = false)
        // The full length, ending where the rule said, whatever the clock read in between.
        is UrgeStart.Covered -> BreathingWindow(start.breathingUntil - lockMs, start.breathingUntil, lockStarted = false)
        is UrgeStart.Unavailable -> BreathingWindow(start.breathingUntil - lockMs, start.breathingUntil, lockStarted = false)
    }

    /**
     * Which screen an entry belongs on, from its stored state and the time. The breathing holds
     * until its window has run out, then the flow is finished. Derived from stored data, so a
     * killed app comes back to the same place.
     */
    fun screenFor(entry: UrgeEntry, now: Long): UrgeScreen =
        if (entry.lockEndedAt != null && now >= entry.lockEndedAt) UrgeScreen.FINISHED else UrgeScreen.BREATHING

    /**
     * The entry the app opens straight back into: the newest one whose lock is still running at
     * [now]. An entry whose lock has ended is done; it stays as a logged row but is not resumed.
     */
    fun resumable(entries: List<UrgeEntry>, now: Long): UrgeEntry? = entries
        .filter { it.lockEndedAt != null && it.lockEndedAt > now }
        .maxByOrNull { it.createdAt }
}
