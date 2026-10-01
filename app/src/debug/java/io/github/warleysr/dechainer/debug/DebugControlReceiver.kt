package io.github.warleysr.dechainer.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.lock.LockEngine
import timber.log.Timber

/**
 * Controls for testing a brick on an emulator or a spare phone (blueprint 14A). Debug builds only:
 * this class and its manifest entry live in the debug source set and are not in a release build.
 *
 *     adb shell am broadcast -n io.github.warleysr.dechainer/.debug.DebugControlReceiver \
 *         -a io.github.warleysr.dechainer.DEBUG_FOCUS_BLOCK --ei minutes 1
 *
 * - DEBUG_FOCUS_BLOCK: a focus block of `minutes` (default 1), with no 10 minute minimum.
 * - DEBUG_URGE_LOCK: an urge lock of `minutes` (default 1 instead of the real ten), through the real rule:
 *   a second one while it runs changes nothing, and it is refused inside a focus block or a punishment day.
 * - DEBUG_PUNISHMENT_DAY: a punishment day of `minutes` (default 5) starting now. Settings are frozen
 *   for it and every phone-side effect is the real one; it ends by the time like the real day.
 * - DEBUG_DROP_ALARMS: forgets the alarms that would end the block, so the next wake-up (unlock,
 *   opening the app) has to end it from the time alone.
 * - DEBUG_ABORT_BRICK: runs the crash-loop breaker's abort at once.
 * - DEBUG_CRASH: crashes the app on purpose, through the real uncaught-exception handler. Send it
 *   twice within 5 minutes while a block runs and the second crash must abort the block.
 */
class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    ACTION_FOCUS_BLOCK -> {
                        val minutes = intent.getIntExtra(EXTRA_MINUTES, 1).coerceIn(1, 60)
                        Timber.i("Debug: focus block of %d min, started=%s", minutes, Pomodoro.debugStartBlock(ctx, minutes))
                    }
                    ACTION_URGE_LOCK -> {
                        val minutes = intent.getIntExtra(EXTRA_MINUTES, 1).coerceIn(1, 30)
                        Timber.i("Debug: urge lock of %d min, result=%s", minutes, LockEngine.debugStartUrgeLock(ctx, minutes))
                    }
                    ACTION_PUNISHMENT_DAY -> {
                        val minutes = intent.getIntExtra(EXTRA_MINUTES, 5).coerceIn(1, 120)
                        LockEngine.debugStartPunishment(ctx, minutes)
                        Timber.i("Debug: punishment day of %d min", minutes)
                    }
                    ACTION_DROP_ALARMS -> LockEngine.debugDropAlarms(ctx)
                    ACTION_CRASH -> {
                        // On a thread of its own, so it is an uncaught exception like any real crash.
                        Thread { throw IllegalStateException("Debug crash on purpose (DEBUG_CRASH)") }.start()
                    }
                    ACTION_ABORT_BRICK -> {
                        LockEngine.abortBrick(ctx)
                        // Not a crash: the process lives on, so re-apply anything that should still hold.
                        LockEngine.sync(ctx)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val ACTION_FOCUS_BLOCK = "io.github.warleysr.dechainer.DEBUG_FOCUS_BLOCK"
        const val ACTION_URGE_LOCK = "io.github.warleysr.dechainer.DEBUG_URGE_LOCK"
        const val ACTION_PUNISHMENT_DAY = "io.github.warleysr.dechainer.DEBUG_PUNISHMENT_DAY"
        const val ACTION_DROP_ALARMS = "io.github.warleysr.dechainer.DEBUG_DROP_ALARMS"
        const val ACTION_ABORT_BRICK = "io.github.warleysr.dechainer.DEBUG_ABORT_BRICK"
        const val ACTION_CRASH = "io.github.warleysr.dechainer.DEBUG_CRASH"
        const val EXTRA_MINUTES = "minutes"
    }
}
