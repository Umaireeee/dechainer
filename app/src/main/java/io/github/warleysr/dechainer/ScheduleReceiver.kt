package io.github.warleysr.dechainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.focus.Pomodoro
import timber.log.Timber

/**
 * Wakes the engine at every window boundary and impulse-lock end (alarms armed by
 * [ScheduleEnforcer]) and whenever something could have invalidated the current state: a reboot,
 * an app update, or the clock / time zone being changed.
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
                ScheduleEnforcer.sync(ctx)
                DnsGuard.enforce(ctx)
                // A reboot or an app update clears alarms; put the Pomodoro one back if a phase
                // is running. Idempotent, so running it on every wake-up is harmless.
                val fresh = intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                Pomodoro.rearm(ctx, relaunchBrick = fresh)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
