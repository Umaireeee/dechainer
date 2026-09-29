package io.github.warleysr.dechainer.data

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.core.content.edit
import io.github.warleysr.dechainer.ScheduleReceiver
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily time limits per app, at no battery cost.
 *
 * Nothing watches the screen and nothing polls. Android already logs every app's foreground time;
 * this reads that log only when the phone is awake anyway. The look-again alarm is a *non-wakeup*
 * alarm set for the earliest moment any limit could run out: it never wakes the phone from sleep
 * (an app can't be used while the phone sleeps), and the first time the phone wakes it fires and
 * checks. An app that has used its time is paused until midnight by the same engine that runs
 * schedules.
 */
object TimeLimits {
    const val ACTION_CHECK = "io.github.warleysr.dechainer.LIMIT_CHECK"
    private const val PREFS = "app_time_limits"
    private const val RC_CHECK = 31
    private const val MAX_MINUTES = 720

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** package -> daily limit in minutes, for the apps that have one. */
    fun all(ctx: Context): Map<String, Int> =
        prefs(ctx).all.mapNotNull { (pkg, v) -> (v as? Int)?.takeIf { it > 0 }?.let { pkg to it } }.toMap()

    /** Sets (or, with 0, removes) [pkg]'s limit, and has the engine look at once. */
    fun set(ctx: Context, pkg: String, minutes: Int) {
        prefs(ctx).edit(commit = true) {
            if (minutes <= 0) remove(pkg) else putInt(pkg, minutes.coerceAtMost(MAX_MINUTES))
        }
        ScheduleEnforcer.requestSyncAll(ctx.applicationContext)
    }

    /** Usage access, granted once in Settings: what lets this app read Android's usage log. */
    fun hasUsageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    /** Milliseconds of foreground time today per package, from one read of the usage log. */
    fun usedToday(ctx: Context, pkgs: Set<String>, now: Long = System.currentTimeMillis()): Map<String, Long> {
        if (pkgs.isEmpty() || !hasUsageAccess(ctx)) return emptyMap()
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = ArrayList<UsageEvt>()
        try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val it = usm.queryEvents(startOfDay, now)
            val e = UsageEvents.Event()
            while (it.hasNextEvent()) {
                it.getNextEvent(e)
                val t = e.eventType
                val global = t == UsageMath.SCREEN_OFF || t == UsageMath.SHUTDOWN || t == UsageMath.STARTUP
                val name = e.packageName ?: ""
                if (global || name in pkgs) events += UsageEvt(t, name, e.className ?: "", e.timeStamp)
            }
        } catch (ex: Exception) {
            Timber.w(ex, "Usage log not readable")
            return emptyMap()
        }
        return UsageMath.foregroundMillisAll(events, pkgs, now)
    }

    fun midnightMillis(now: Long): Long {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** [reached]: apps out of time today. [nextCheckDelayMs]: when to look again, null if there's nothing to watch. */
    class Status(val reached: Set<String>, val nextCheckDelayMs: Long?)

    fun evaluate(ctx: Context, now: Long): Status {
        val limits = all(ctx)
        // Without Usage access nothing can be measured, so nothing is held and nothing is scheduled.
        if (limits.isEmpty() || !hasUsageAccess(ctx)) return Status(emptySet(), null)
        val used = usedToday(ctx, limits.keys, now)
        return Status(
            LimitMath.reached(limits, used),
            LimitMath.nextCheckDelay(limits, used, midnightMillis(now) - now)
        )
    }

    /** The look-again alarm. Non-wakeup: it fires the next time the phone is awake, never wakes it. */
    fun armCheck(ctx: Context, delayMs: Long?) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, RC_CHECK,
            Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (delayMs == null) {
            am.cancel(pi)
            return
        }
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pi)
        }
    }
}
