package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.clock.TrustedClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.data.UrgeActions
import io.github.warleysr.dechainer.focus.FocusLogMath
import io.github.warleysr.dechainer.focus.Pomodoro
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
        val intention = FocusLogMath.cleanIntention(intent.getStringExtra(UrgeActions.EXTRA_INTENTION))
        val ctx = context.applicationContext
        // Blocking needs Device Owner; without it there is nothing this could honestly do.
        if (!DeviceOwnerRepository.isDeviceOwner()) return

        val pending = goAsync()
        Thread {
            try {
                when (kind) {
                    // Both ask for the urge lock; the rule decides (never extended, not inside a focus
                    // block or a punishment day, not without Device Owner).
                    UrgeActions.Kind.IMPULSE_BLOCK, UrgeActions.Kind.RIDE_LOCK -> LockEngine.startUrgeLock(ctx)
                    UrgeActions.Kind.FOCUS_BLOCK -> {
                        Pomodoro.ensureLoaded(ctx)
                        val started = Pomodoro.startBlock(ctx, TrustedClock.now(ctx) + minutes * 60_000L)
                        // Only a block this request started: the plan made the evening before is what the
                        // first session is for, and the question at its end asks about exactly that.
                        if (started && intention != null) Pomodoro.setIntention(ctx, intention)
                    }
                }
                LockEngine.sync(ctx)
                Timber.d("Urge action %s for %d min", kind, minutes)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
