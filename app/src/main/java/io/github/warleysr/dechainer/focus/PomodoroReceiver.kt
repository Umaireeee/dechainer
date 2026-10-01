package io.github.warleysr.dechainer.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.lock.LockEngine

/** The Pomodoro's alarm, and the Yes / No / Start buttons on its notifications. */
class PomodoroReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val action = intent.action
        val sessionId = intent.getLongExtra(Pomodoro.EXTRA_SESSION_ID, 0L)
        val hasDone = intent.hasExtra(Pomodoro.EXTRA_DONE)
        val done = intent.getBooleanExtra(Pomodoro.EXTRA_DONE, false)
        // All of it off the main thread (it reads and writes the database, posts notifications and starts
        // screens), with goAsync keeping the process alive until it is done.
        val pending = goAsync()
        Thread {
            try {
                try {
                    when (action) {
                        Pomodoro.ACTION_PHASE_END -> Pomodoro.onPhaseAlarm(ctx)
                        Pomodoro.ACTION_ANSWER -> if (sessionId > 0L && hasDone) Pomodoro.answer(ctx, sessionId, done)
                        // Yes or No on a focus-flow notification (a check-in, or the last question).
                        FocusRunner.ACTION_ANSWER -> if (hasDone) FocusRunner.answer(ctx, done)
                        Pomodoro.ACTION_START_NEXT -> {
                            Pomodoro.ensureLoaded(ctx)
                            if (Pomodoro.state.value.isIdle) Pomodoro.start(ctx) else Pomodoro.dismissAlarm(ctx)
                        }
                        Pomodoro.ACTION_STOP_ALARM -> Pomodoro.dismissAlarm(ctx)
                    }
                } catch (e: Throwable) {
                    // A failure here must not skip the sync below, nor take the process (and the crash-loop breaker's count) with it.
                    timber.log.Timber.e(e, "Pomodoro receiver step failed: %s", action)
                }
                // Every receiver is a wake-up (blueprint 5.4): the lock is recomputed from stored data, so
                // an alarm that arrives late or twice can never leave a stale brick behind.
                LockEngine.sync(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
