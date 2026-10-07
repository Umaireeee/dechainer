package io.github.warleysr.dechainer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import io.github.warleysr.dechainer.ScheduleReceiver
import timber.log.Timber

/** Key both engines store their owned packages under, each in its own preferences file. */
private const val KEY_OWNED_APPS = "owned_apps"

/** Re-check at least this often even when no boundary is near, to self-heal after clock jumps. */
internal const val ENGINE_MAX_CACHE_MS = 5 * 60 * 1000L

/**
 * The blocking core: owns the apps it suspended, releases only those, and wakes at the next
 * boundary with an exact alarm — nothing polls.
 *
 * Ownership is the rule everything rests on: the engine releases an app only if it is the one
 * that suspended it. Anything suspended before a window opened — by hand, for example — is left
 * exactly as it was.
 */
abstract class AppBlockEngine {

    /** SharedPreferences file holding this engine's state. Must differ per engine. */
    protected abstract val statePrefsName: String

    /** PendingIntent request code for this engine's boundary alarm. Must differ per engine. */
    protected abstract val alarmRequestCode: Int

    /** Action on the boundary alarm, for logging and for keeping the two alarms distinct. */
    protected abstract val alarmAction: String

    /** Prefix for this engine's log lines. */
    protected abstract val logName: String

    protected val lock = Any()

    protected fun state(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(statePrefsName, Context.MODE_PRIVATE)

    /** Packages this engine currently has blocked, so other features don't release them. */
    fun ownedApps(context: Context): Set<String> =
        state(context).getStringSet(KEY_OWNED_APPS, emptySet())?.toSet() ?: emptySet()

    /** Gives up ownership without releasing — used when the person blocks an app by hand. */
    fun disown(context: Context, pkg: String) = synchronized(lock) {
        val owned = ownedApps(context)
        if (pkg in owned) state(context).edit(commit = true) { putStringSet(KEY_OWNED_APPS, owned - pkg) }
    }

    /**
     * Brings the blocked set in line with [desired].
     *
     * @param takeOver given the packages this engine wants to release, returns the ones the other
     * engine is taking over — already adopted by it, so they stay blocked and get released later.
     */
    protected fun applyApps(
        ctx: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        desired: Set<String>,
        takeOver: (Set<String>) -> Set<String>
    ) {
        val prefs = state(ctx)
        val initiallyOwned = ownedApps(ctx)
        val owned = initiallyOwned.toMutableSet()

        // Block what should be blocked and isn't yet. Uninstalled packages are skipped here and
        // caught on a later sync if they get (re)installed while the window is still open.
        val toBlock = desired.filter { pkg ->
            try {
                // Blocked check first: for an app already blocked that's one call, not three.
                !Blocker.isBlocked(dpm, admin, pkg) && Blocker.isInstalled(ctx, pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        if (toBlock.isNotEmpty()) {
            // Ownership is written BEFORE the suspension: if the process dies in between, the apps
            // are still on record as ours to release, instead of orphaned and suspended for good.
            owned += toBlock
            prefs.edit(commit = true) { putStringSet(KEY_OWNED_APPS, owned.toSet()) }
            val failed = Blocker.block(ctx, dpm, admin, toBlock)
            if (failed.isNotEmpty()) Timber.w("$logName: could not block $failed")
            owned.clear()
            owned += ownedAfterBlock(initiallyOwned, toBlock.toSet(), failed)
        }

        // Release what this engine applied and no longer wants — unless something else still needs
        // it blocked, in which case that feature owns it from here.
        val toRelease = owned - desired
        if (toRelease.isNotEmpty()) {
            val heldElsewhere = takeOver(toRelease)
            val releasable = (toRelease - heldElsewhere).filter { Blocker.isInstalled(ctx, it) }
            // An app Android refused to release stays on our list, so the next sync tries again.
            val notReleased = if (releasable.isNotEmpty()) Blocker.release(ctx, dpm, admin, releasable) else emptySet()
            if (notReleased.isNotEmpty()) Timber.w("$logName: could not release $notReleased")
            val remaining = ownedAfterRelease(owned, toRelease, notReleased)
            owned.clear()
            owned += remaining
        }

        // Only touch disk when something changed — this runs on every tick, app switch and alarm.
        if (owned != initiallyOwned) prefs.edit(commit = true) { putStringSet(KEY_OWNED_APPS, owned) }
        if (toBlock.isNotEmpty() || toRelease.isNotEmpty()) AppRepository.invalidateCache()
    }

    /**
     * Wakes this engine one second after [boundaryMillis]. Without it a block would only be applied
     * and lifted when something else happened to call sync, which is exactly the dependence on the
     * accessibility service these engines exist to remove. Returns the alarm time, or null if there
     * is no next boundary (the alarm is then cancelled).
     */
    protected fun armAlarmFor(ctx: Context, boundaryMillis: Long?): Long? {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx)
        if (boundaryMillis == null) {
            am.cancel(pi)
            return null
        }
        val triggerAt = boundaryMillis + 1000L
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        return triggerAt
    }

    protected fun cancelAlarm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx))
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        alarmRequestCode,
        Intent(ctx, ScheduleReceiver::class.java).setAction(alarmAction),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

}

/** Packages owned once [toBlock] has been attempted: the ones Android refused ([failed]) were never suspended, so they are not ours. */
internal fun ownedAfterBlock(ownedBefore: Set<String>, toBlock: Set<String>, failed: Set<String>): Set<String> =
    ownedBefore + (toBlock - failed)

/** Packages still owned after [toRelease] was attempted: only the ones actually released are given up. */
internal fun ownedAfterRelease(owned: Set<String>, toRelease: Set<String>, notReleased: Set<String>): Set<String> =
    owned - (toRelease - notReleased)
