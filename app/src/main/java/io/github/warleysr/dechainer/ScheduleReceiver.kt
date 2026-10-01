package io.github.warleysr.dechainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.lock.LockEngine
import timber.log.Timber

/**
 * Wakes the engine at every boundary and block end (alarms armed by the lock engine) and whenever
 * something could have invalidated the current state: a reboot, an app update, or the clock / time
 * zone being changed. The alarm is only a wake-up; [LockEngine.sync] recomputes everything from stored data.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("ScheduleReceiver: ${intent.action}")
        // Off the main thread (boot can be slow), with goAsync keeping the process alive until
        // the work is done even when nothing else of Déchaîner is running.
        val pending = goAsync()
        val ctx = context.applicationContext
        Thread {
            try {
                // A reboot or an app update clears alarms; put the Pomodoro one back if a phase
                // is running (or catch up if it passed). Idempotent, so running it on every wake-up
                // is harmless. First, so the sync below plans from a settled state.
                val fresh = intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                // Each step on its own: one that throws must never stop the sync, which is what re-applies the lock.
                try { Pomodoro.rearm(ctx) } catch (e: Throwable) { Timber.e(e, "Pomodoro not re-armed") }
                LockEngine.sync(ctx)
                // The pin does not survive a reboot or an update: if any brick runs, open Déchaîner so
                // it pins again. Only then; on any other wake-up it would drag you out of an allowed app.
                if (fresh) try { LockEngine.reopenIfBrick(ctx) } catch (e: Throwable) { Timber.e(e, "Brick screen not reopened") }
                // A weekly report that came due while the phone was off is queued again (blueprint 6.5).
                if (fresh) try { io.github.warleysr.dechainer.report.ReportScheduler.ensureQueued(ctx) } catch (e: Throwable) { Timber.e(e, "Report not queued") }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
