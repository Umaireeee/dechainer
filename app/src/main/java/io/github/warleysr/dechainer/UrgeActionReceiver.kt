package io.github.warleysr.dechainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.data.UrgeActions
import io.github.warleysr.dechainer.focus.Pomodoro
import io.github.warleysr.dechainer.security.SecurityManager
import timber.log.Timber

/**
 * The door the urge journal knocks on. It is exported but guarded by a signature permission (see
 * the manifest), so only an app signed with the same key can reach it. Every command only adds
 * blocking; see [UrgeActions].
 */
class UrgeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UrgeActions.ACTION) return
        val kind = UrgeActions.parseKind(intent.getStringExtra(UrgeActions.EXTRA_KIND)) ?: return
        val minutes = UrgeActions.clampMinutes(kind, intent.getIntExtra(UrgeActions.EXTRA_MINUTES, 0))
        val ctx = context.applicationContext
        // Blocking needs Device Owner; without it there is nothing this could honestly do.
        if (!DeviceOwnerRepository.isDeviceOwner()) return

        val pending = goAsync()
        Thread {
            try {
                when (kind) {
                    UrgeActions.Kind.IMPULSE_BLOCK -> {
                        val remaining = SecurityManager.getImpulseBlockRemainingTime(ctx).coerceAtLeast(0L)
                        if (UrgeActions.shouldStartImpulse(remaining, minutes)) {
                            SecurityManager.startImpulseBlock(ctx, minutes)
                        }
                    }
                    UrgeActions.Kind.FOCUS_BLOCK -> {
                        Pomodoro.ensureLoaded(ctx)
                        Pomodoro.startBlock(ctx, System.currentTimeMillis() + minutes * 60_000L)
                    }
                }
                ScheduleEnforcer.sync(ctx)
                Timber.d("Urge action %s for %d min", kind, minutes)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
