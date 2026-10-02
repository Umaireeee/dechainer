package io.github.warleysr.dechainer.guard

import android.content.Context
import android.os.SystemClock
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockStateStore

/**
 * Wires [CrashGuard] into the process (blueprint 5.4, R2): an uncaught-exception handler that counts
 * crashes with a synchronous `commit()` and, on the second one inside five minutes while a brick is
 * running, runs [LockEngine.abortBrick]. It always hands the crash on to the handler it replaced, so
 * Android's own behaviour (the crash dialog, killing the process) is unchanged.
 */
object CrashHandler {
    private const val PREFS = "crash_guard"
    private const val KEY_CRASHES = "crashes"

    fun install(context: Context) {
        val ctx = context.applicationContext
        val guard = CrashGuard(
            log = PrefsCrashLog(ctx),
            brickActive = { brickIsRunning(ctx) },
            abort = { LockEngine.abortBrick(ctx) }
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                guard.onCrash(SystemClock.elapsedRealtime(), TrustedClock.bootCount(ctx))
            } catch (_: Throwable) {
                // Nothing may get in the way of the crash being passed on.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Whether a brick is running, read straight from the stored end times rather than from any
     * in-memory state, which may be what just crashed. Over-estimating is harmless: an abort on a
     * brick that had really ended only releases what it was about to release anyway.
     */
    private fun brickIsRunning(ctx: Context): Boolean {
        val now = try { TrustedClock.now(ctx) } catch (_: Throwable) { System.currentTimeMillis() }
        return Pomodoro.storedBlockEndsAt(ctx) > now ||
            LockStateStore.urge(ctx).endsAt > now
    }

    /** Crashes in SharedPreferences, written with `commit()` so they are on disk before the process dies. */
    private class PrefsCrashLog(ctx: Context) : CrashLog {
        private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun load(): List<CrashRecord> = CrashRecord.decode(prefs.getString(KEY_CRASHES, null))

        override fun save(records: List<CrashRecord>) {
            prefs.edit().putString(KEY_CRASHES, CrashRecord.encode(records)).commit()
        }
    }
}
