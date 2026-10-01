package io.github.warleysr.dechainer.lock

import android.content.Context
import io.github.warleysr.dechainer.clock.TrustedClock
import timber.log.Timber

/**
 * The settings freeze (blueprint 5.5). While a punishment day runs, every settings write is
 * refused here, in the repositories that do the writing, not only hidden in the UI. Reading is
 * always allowed. The urge flow, the plan and goals (Today) and Reports are not settings and are
 * never refused.
 *
 * The day is read against the trusted clock, so moving the date neither starts nor ends a freeze.
 * If the store cannot be read, the last day seen in this process decides ([LockStateStore]).
 */
object SettingsFreeze {
    fun isFrozen(context: Context): Boolean =
        LockStateStore.punishment(context).activeAt(TrustedClock.now(context))

    /**
     * Called first by every function that changes a setting. True if the write may go ahead; false
     * (and a log line) if it is refused, in which case the caller changes nothing and says so.
     */
    fun allowWrite(context: Context, what: String): Boolean {
        if (!isFrozen(context)) return true
        Timber.w("Settings are frozen on a punishment day: refused %s", what)
        return false
    }
}
