package io.github.warleysr.dechainer.data

/**
 * The companion Urge Journal, kept out of every lock: it is the safety net for the worst moments, so
 * no hold of Déchaîner suspends it ([LockSafety.neverBlocked]), and the Focus page can open it with
 * its ride extra. Only names; the lock engine owns every lock.
 */
object JournalLink {
    const val PACKAGE = "io.github.warleysr.urgejournal"

    /** The journal starts a ride (after its own five-second countdown) when launched with this extra. */
    const val RIDE_EXTRA = "action"
    const val RIDE_VALUE = "ride"
}
