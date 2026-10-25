package io.github.warleysr.dechainer.lock

import android.content.Context
import io.github.warleysr.dechainer.clock.TrustedClock

/**
 * Starting and extending study mode and lockdown. The decisions are [StudyRules]; this stores the
 * result and asks the engine to apply it. A session is stored before anything else happens, so it
 * never waits on a screen or the network, and it only ever gets longer (there is no way to end one early).
 */
object StudyMode {
    private val startLock = Any()

    /** When the running study session ends, or 0 (or a time already passed) when none runs. */
    fun studyEndsAt(context: Context): Long = LockStateStore.study(context.applicationContext).endsAt

    fun lockdown(context: Context): LockdownInput = LockStateStore.lockdown(context.applicationContext)

    /**
     * Starts study mode ending at [requestedEndsAt], or extends the one that runs. The first start also
     * switches the DNS filter on. Returns what happened; only [StudyRules.Start.Started] and
     * [StudyRules.Start.Extended] changed anything.
     */
    fun startStudy(context: Context, requestedEndsAt: Long): StudyRules.Start {
        val ctx = context.applicationContext
        val result = synchronized(startLock) {
            val now = TrustedClock.checkpoint(ctx)
            val decision = StudyRules.decide(
                now, LockStateStore.study(ctx).endsAt, requestedEndsAt, DumbPhone.isDeviceOwner(ctx)
            )
            when (decision) {
                is StudyRules.Start.Started -> {
                    DumbPhone.arm(ctx)
                    LockStateStore.setStudy(ctx, decision.endsAt)
                }
                is StudyRules.Start.Extended -> LockStateStore.setStudy(ctx, decision.endsAt)
                else -> Unit
            }
            decision
        }
        if (result is StudyRules.Start.Started || result is StudyRules.Start.Extended) LockEngine.requestSync(ctx)
        return result
    }

    /**
     * Starts a lockdown on [picked] ending at [requestedEndsAt], or extends the one that runs and adds
     * the picked apps to it. While one runs the list only grows.
     */
    fun startLockdown(context: Context, requestedEndsAt: Long, picked: Set<String>): StudyRules.Start {
        val ctx = context.applicationContext
        val result = synchronized(startLock) {
            val now = TrustedClock.checkpoint(ctx)
            val current = LockStateStore.lockdown(ctx)
            val decision = StudyRules.decideLockdown(
                now, current.endsAt, current.apps, requestedEndsAt, picked, DumbPhone.isDeviceOwner(ctx)
            )
            val running = current.endsAt > now
            val apps = StudyRules.lockdownApps(running, current.apps, picked)
            when (decision) {
                is StudyRules.Start.Started -> {
                    DumbPhone.arm(ctx)
                    LockStateStore.setLockdown(ctx, decision.endsAt, apps)
                }
                is StudyRules.Start.Extended -> LockStateStore.setLockdown(ctx, decision.endsAt, apps)
                is StudyRules.Start.AppsAdded -> LockStateStore.setLockdown(ctx, decision.endsAt, apps)
                else -> Unit
            }
            decision
        }
        if (result is StudyRules.Start.Started || result is StudyRules.Start.Extended || result is StudyRules.Start.AppsAdded) {
            LockEngine.requestSync(ctx)
        }
        return result
    }
}
