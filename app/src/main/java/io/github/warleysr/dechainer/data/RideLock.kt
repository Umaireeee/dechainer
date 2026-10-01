package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.core.content.edit

/**
 * The short, total lock the urge journal asks for at the start of a ride. While it runs,
 * [ScheduleEnforcer] suspends every app with an icon except the essentials and the journal.
 *
 * It only ever runs out on its own: there is no way to end it early, and a new request can only
 * make it longer. The remaining time is capped at the longest a ride lock may be, so a clock moved
 * backwards cannot stretch it (the clock is also locked while it runs).
 */
object RideLock {
    /** The companion journal, which must stay usable: the ride happens in it. */
    const val JOURNAL_PACKAGE = "io.github.warleysr.urgejournal"

    /** The journal starts a ride (after its own five-second countdown) when launched with this extra. */
    const val JOURNAL_RIDE_EXTRA = "action"
    const val JOURNAL_RIDE_VALUE = "ride"

    /** Never locked, so a real emergency can always be handled. Calls are protected elsewhere. */
    val ALWAYS_OPEN = setOf(JOURNAL_PACKAGE, "com.android.emergency", "com.google.android.apps.safetyhub")

    private const val PREFS = "ride_lock"
    private const val KEY_ENDS_AT = "ends_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts (or lengthens, never shortens) the lock so it lasts [minutes] from now. */
    fun start(context: Context, minutes: Int) {
        val now = System.currentTimeMillis()
        val wanted = now + minutes * 60_000L
        val current = prefs(context).getLong(KEY_ENDS_AT, 0L)
        if (wanted > current) prefs(context).edit(commit = true) { putLong(KEY_ENDS_AT, wanted) }
    }

    /** Milliseconds left, or 0 when there is no lock. */
    fun remainingMillis(context: Context, now: Long = System.currentTimeMillis()): Long =
        remaining(prefs(context).getLong(KEY_ENDS_AT, 0L), now)

    /** Pure so it can be tested: what is left of a lock that ends at [endsAt], never above the cap. */
    internal fun remaining(endsAt: Long, now: Long): Long {
        if (endsAt <= 0L) return 0L
        return (endsAt - now).coerceIn(0L, UrgeActions.RIDE_MAX_MINUTES * 60_000L)
    }
}
