package io.github.warleysr.dechainer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import io.github.warleysr.dechainer.AlarmTickReceiver
import io.github.warleysr.dechainer.models.Span
import io.github.warleysr.dechainer.models.SuspensionReason
import io.github.warleysr.dechainer.models.TimeWindow
import io.github.warleysr.dechainer.security.SecurityManager
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Suspends an app when a block stops it: opening it outside its allowed time window, or running
 * past its daily time limit. Unlike the blocking screen alone, the suspension keeps the app shut
 * after the screen is dismissed.
 *
 * Holds are released by the rule that caused them:
 * - a schedule hold is released when an allowed window covering the app starts again;
 * - a time-limit hold is released when the day advances or when limits are raised above today's usage.
 *
 * [sync] is idempotent. It runs at boot, when the accessibility service connects, when the
 * limit, window or group settings change, and from an exact alarm set for the next boundary.
 */
object BlockSuspensionManager {
    const val ACTION_TICK = "io.github.warleysr.dechainer.BLOCK_SUSPENSION_TICK"

    private const val PREFS_NAME = "block_suspensions"
    private const val KIND_SCHEDULE = "schedule"
    private const val KIND_LIMIT = "limit"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Packages currently held by a block. */
    fun heldPackages(context: Context): Set<String> = prefs(context).all.keys

    /** Suspends [pkg] because a block stopped it. Returns false when the OS refused or lacked Device Owner. */
    fun suspend(context: Context, pkg: String, outsideWindow: Boolean): Boolean {
        synchronized(SuspensionCoordinator.lock) {
            return try {
                val dpm = DeviceAdmin.policyManager
                val admin = DeviceAdmin.component
                if (!dpm.isDeviceOwnerApp(context.packageName) || pkg == context.packageName) return false

                val failed = dpm.setPackagesSuspended(admin, arrayOf(pkg), true).toSet()
                if (pkg in failed) {
                    Timber.w("Block suspension refused by DPM for $pkg")
                    return false
                }
                val today = LocalDate.now()
                val prev = prefs(context).getString(pkg, null)
                val marker = when {
                    outsideWindow && prev?.startsWith("$KIND_LIMIT:") == true ->
                        "${prev.substringBefore('|')}|$KIND_SCHEDULE"
                    outsideWindow && prev?.contains("|$KIND_SCHEDULE") == true -> prev
                    outsideWindow -> KIND_SCHEDULE
                    prev == KIND_SCHEDULE || prev?.endsWith("|$KIND_SCHEDULE") == true ->
                        "$KIND_LIMIT:$today|$KIND_SCHEDULE"
                    else -> "$KIND_LIMIT:$today"
                }
                prefs(context).edit { putString(pkg, marker) }
                SuspensionLogRepository.log(
                    context, pkg,
                    if (outsideWindow) SuspensionReason.SCHEDULE_BLOCK else SuspensionReason.TIME_LIMIT_BLOCK
                )
                AppRepository.invalidateCache()
                scheduleNext(context)
                true
            } catch (e: Exception) {
                Timber.e(e, "Block suspension failed for $pkg")
                false
            }
        }
    }

    /**
     * Drops a block hold without releasing the app. Used when the user suspends or unsuspends
     * an app by hand so the block doesn't later lift or re-trap that suspension.
     */
    fun forget(context: Context, pkg: String) {
        synchronized(SuspensionCoordinator.lock) {
            prefs(context).edit { remove(pkg) }
        }
    }

    fun sync(context: Context) {
        synchronized(SuspensionCoordinator.lock) {
            try {
                val dpm = DeviceAdmin.policyManager
                val admin = DeviceAdmin.component
                if (!dpm.isDeviceOwnerApp(context.packageName)) return

                val p = prefs(context)
                val now = ZonedDateTime.now()
                val today = now.toLocalDate()
                val nowMinute = now.hour * 60 + now.minute

                val decodedWindows = HashMap<String, List<TimeWindow>>()
                val due = p.all.mapNotNull { (pkg, value) ->
                    val marker = value as? String ?: return@mapNotNull null
                    val parts = marker.split('|')
                    val scheduleBlocked = KIND_SCHEDULE in parts
                    val limitPart = parts.firstOrNull { it.startsWith("$KIND_LIMIT:") }
                    val scheduleClear = !scheduleBlocked ||
                        inAllowedSpan(context, pkg, today.dayOfWeek.value, nowMinute, decodedWindows)
                    val limitClear = limitPart == null || run {
                        val storedDate = runCatching { LocalDate.parse(limitPart.substringAfter(':')) }.getOrNull()
                        (storedDate != null && today.isAfter(storedDate)) || isWithinCurrentLimits(context, pkg)
                    }
                    if (scheduleClear && limitClear) pkg else null
                }.toSet()

                // Leave apps held by downtime, impulse lock, or manual suspension untouched.
                val releasable = due -
                    DowntimeManager.heldPackages(context) -
                    DowntimeManager.activePackages(context) -
                    SecurityManager.getActiveImpulseSuspension(context) -
                    SuspensionCoordinator.manualSuspensions(context)

                if (releasable.isNotEmpty()) {
                    val failed = dpm.setPackagesSuspended(admin, releasable.toTypedArray(), false).toSet()
                    val released = releasable - failed
                    if (released.isNotEmpty()) {
                        p.edit { released.forEach { remove(it) } }
                        AppRepository.invalidateCache()
                    }
                }
                scheduleNext(context, decodedWindows, now)
            } catch (e: Exception) {
                Timber.e(e, "Block suspension sync failed")
            }
        }
    }

    private fun isWithinCurrentLimits(context: Context, pkg: String): Boolean {
        val daily = context.getSharedPreferences(AppTimeLimits.PREFS_NAME, Context.MODE_PRIVATE)
        val weekly = context.getSharedPreferences(AppTimeLimits.WEEKLY_PREFS_NAME, Context.MODE_PRIVATE)
        val appLimit = AppTimeLimits.read(daily, weekly, pkg).todayMinutes()
        if (appLimit > 0 && DailyUsageStore.getAppUsageMillis(context, pkg) >= TimeUnit.MINUTES.toMillis(appLimit.toLong())) {
            return false
        }
        val group = AppGroupRepository.getGroupForPackage(pkg)
        val groupLimit = group?.timeLimit?.todayMinutes() ?: 0
        if (group != null && groupLimit > 0 &&
            DailyUsageStore.getGroupUsageMillis(context, group.id) >= TimeUnit.MINUTES.toMillis(groupLimit.toLong())
        ) {
            return false
        }
        return true
    }

    /** Whether today's allowed spans for [pkg] (its own and its group's windows) cover [nowMinute]. */
    private fun inAllowedSpan(
        context: Context,
        pkg: String,
        day: Int,
        nowMinute: Int,
        cache: MutableMap<String, List<TimeWindow>>
    ): Boolean {
        val spans = allowedOnDay(context, pkg, day, cache) ?: return true
        return spans.any { it.contains(nowMinute) }
    }

    private fun allowedOnDay(
        context: Context,
        pkg: String,
        day: Int,
        cache: MutableMap<String, List<TimeWindow>>
    ): List<Span>? {
        val group = AppGroupRepository.getGroupForPackage(pkg)
        val appWindows = cache.getOrPut(pkg) {
            AppTimeWindows.decode(
                context.getSharedPreferences(AppTimeWindows.PREFS_NAME, Context.MODE_PRIVATE).getString(pkg, null)
            )
        }
        return TimeWindow.allowedSpans(listOf(group?.timeWindows.orEmpty(), appWindows), day)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, AlarmTickReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * Sets one exact alarm for the next moment a hold can end: scanning up to 7 days ahead for
     * schedule holds and adding next midnight only when a daily time-limit hold exists.
     */
    private fun scheduleNext(
        context: Context,
        cache: MutableMap<String, List<TimeWindow>> = HashMap(),
        now: ZonedDateTime = ZonedDateTime.now()
    ) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val all = prefs(context).all
        if (all.isEmpty()) {
            alarms.cancel(alarmIntent(context))
            return
        }

        val today = now.toLocalDate()
        val candidates = mutableListOf<LocalDateTime>()
        var hasLimitHold = false

        for ((pkg, value) in all) {
            val marker = value as? String ?: continue
            val parts = marker.split('|')
            if (parts.any { it.startsWith("$KIND_LIMIT:") }) {
                hasLimitHold = true
            }
            if (KIND_SCHEDULE in parts) {
                for (offset in 0..7) {
                    val date = today.plusDays(offset.toLong())
                    val spans = allowedOnDay(context, pkg, date.dayOfWeek.value, cache)
                    if (spans == null) {
                        candidates += date.atStartOfDay()
                        break
                    }
                    for (span in spans) {
                        candidates += date.atStartOfDay().plusMinutes(span.start.toLong())
                    }
                }
            }
        }
        if (hasLimitHold) {
            candidates += today.plusDays(1).atStartOfDay()
        }

        val instant = SuspensionCoordinator.nextZonedInstantAfter(candidates, now)
        if (instant == null) {
            alarms.cancel(alarmIntent(context))
            return
        }
        val millis = instant.toEpochMilli()
        val pending = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    }
}
