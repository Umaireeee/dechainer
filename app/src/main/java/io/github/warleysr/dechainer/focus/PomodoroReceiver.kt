package io.github.warleysr.dechainer.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.lock.LockEngine

/** The Pomodoro's alarm, and the Yes / No / Start buttons on its notifications. */
class PomodoroReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Pomodoro.ACTION_PHASE_END -> Pomodoro.onPhaseAlarm(ctx)
            Pomodoro.ACTION_ANSWER -> {
                val id = intent.getLongExtra(Pomodoro.EXTRA_SESSION_ID, 0L)
                if (id > 0L && intent.hasExtra(Pomodoro.EXTRA_DONE)) {
                    Pomodoro.answer(ctx, id, intent.getBooleanExtra(Pomodoro.EXTRA_DONE, false))
                }
            }
            // Yes or No on a focus-flow notification (a check-in, or the last question).
            FocusRunner.ACTION_ANSWER -> {
                if (intent.hasExtra(Pomodoro.EXTRA_DONE)) {
                    FocusRunner.answer(ctx, intent.getBooleanExtra(Pomodoro.EXTRA_DONE, false))
                }
            }
            Pomodoro.ACTION_START_NEXT -> {
                Pomodoro.ensureLoaded(ctx)
                if (Pomodoro.state.value.isIdle) Pomodoro.start(ctx) else Pomodoro.dismissAlarm(ctx)
            }
            Pomodoro.ACTION_STOP_ALARM -> Pomodoro.dismissAlarm(ctx)
            Pomodoro.ACTION_LECTURE -> {
                val id = intent.getLongExtra(Pomodoro.EXTRA_SESSION_ID, 0L)
                val a = runCatching { LectureAnswer.valueOf(intent.getStringExtra(Pomodoro.EXTRA_LECTURE) ?: "") }.getOrNull()
                if (id > 0L && a != null) Pomodoro.answerLecture(ctx, id, a)
            }
        }
        // Every receiver is a wake-up (blueprint 5.4): the lock is recomputed from stored data, so
        // an alarm that arrives late or twice can never leave a stale brick behind.
        val pending = goAsync()
        Thread {
            try {
                LockEngine.sync(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
