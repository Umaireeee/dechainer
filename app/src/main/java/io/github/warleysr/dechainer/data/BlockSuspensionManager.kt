package io.github.warleysr.dechainer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import io.github.warleysr.dechainer.DowntimeReceiver
import io.github.warleysr.dechainer.models.Span
import io.github.warleysr.dechainer.models.SuspensionReason
import io.github.warleysr.dechainer.models.TimeWindow
import io.github.warleysr.dechainer.security.SecurityManager
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Suspends an app when a block stops it: opening it outside its allowed time window, or running
 * past its daily time limit. Unlike the blocking screen alone, the suspension keeps the app shut
 * after the screen is dismissed.
 *
 * Holds are released by the rule that caused them:
 * - a schedule hold is released when an allowed window covering the app starts again;
 * - a time-limit hold is released when the day changes, which is when usage resets.
 *
 * [sync] is idempotent. It runs at boot, when the accessibility service connects, when the
 * window or group settings change, and from an exact alarm set for the next boundary.
 */
object BlockSuspensionManager {
    const val ACTION_TICK = "io.github.warleysr.dechainer.BLOCK_SUSPENSION_TICK"

    private const val PREFS_NAME = "block_suspensions"
    private const val KIND_SCHEDULE = "schedule"
    private const val KIND_LIMIT = "limit"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Packages currently held by a block. */
    fun heldPackages(context: Context): Set<String> = prefs(context).all.keys

    /** Suspends [pkg] because a block stopped it. [outsideWindow] picks the schedule or the limit rule. */
    fun suspend(context: Context, pkg: String, outsideWindow: Boolean) {
        try {
            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            if (!dpm.isAdminActive(admin) || pkg == context.packageName) return

            dpm.setPackagesSuspended(admin, arrayOf(pkg), true)
            val marker = if (outsideWindow) KIND_SCHEDULE else "$KIND_LIMIT:${LocalDate.now()}"
            prefs(context).edit { putString(pkg, marker) }
            SuspensionLogRepository.log(
                context, pkg,
                if (outsideWindow) SuspensionReason.SCHEDULE_BLOCK else SuspensionReason.TIME_LIMIT_BLOCK
            )
            AppRepository.invalidateCache()
            scheduleNext(context)
        } catch (e: Exception) {
            Timber.e(e, "Block suspension failed for $pkg")
        }
    }

    /**
     * Drops a block hold without releasing the app. Used when the user suspends an app by hand,
     * so the block doesn't later lift that suspension behind their back.
     */
    fun forget(context: Context, pkg: String) {
        prefs(context).edit { remove(pkg) }
    }

    fun sync(context: Context) {
        try {
            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            if (!dpm.isAdminActive(admin)) return

            val p = prefs(context)
            val today = LocalDate.now()
            val nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }

            val due = p.all.mapNotNull { (pkg, value) ->
                val marker = value as? String ?: return@mapNotNull null
                val release = when {
                    marker == KIND_SCHEDULE -> inAllowedSpan(context, pkg, today.dayOfWeek.value, nowMinute)
                    marker.startsWith("$KIND_LIMIT:") -> marker.substringAfter(':') != today.toString()
                    else -> true
                }
                if (release) pkg else null
            }.toSet()

            // Downtime and the impulse lock may be holding the same apps. Leave those to them, and
            // keep the block's record so the next sync tries again.
            val releasable = due - DowntimeManager.heldPackages(context) -
                SecurityManager.getActiveImpulseSuspension(context)

            if (releasable.isNotEmpty()) {
                dpm.setPackagesSuspended(admin, releasable.toTypedArray(), false)
                p.edit { releasable.forEach { remove(it) } }
                AppRepository.invalidateCache()
            }
            scheduleNext(context)
        } catch (e: Exception) {
            Timber.e(e, "Block suspension sync failed")
        }
    }

    /** Whether today's allowed spans for [pkg] (its own and its group's windows) cover [nowMinute]. */
    private fun inAllowedSpan(context: Context, pkg: String, day: Int, nowMinute: Int): Boolean {
        val spans = allowedToday(context, pkg, day) ?: return true
        return spans.any { it.contains(nowMinute) }
    }

    private fun allowedToday(context: Context, pkg: String, day: Int): List<Span>? {
        val group = AppGroupRepository.getGroupForPackage(pkg)
        val appWindows = AppTimeWindows.decode(
            context.getSharedPreferences(AppTimeWindows.PREFS_NAME, Context.MODE_PRIVATE).getString(pkg, null)
        )
        return TimeWindow.allowedSpans(listOf(group?.timeWindows.orEmpty(), appWindows), day)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, DowntimeReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * Sets one alarm for the next moment a hold can end: the next allowed window start for a
     * schedule hold, or midnight, whichever is first. Midnight covers time-limit holds.
     */
    private fun scheduleNext(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = prefs(context)
        if (p.all.isEmpty()) {
            alarms.cancel(alarmIntent(context))
            return
        }

        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        var at = today.plusDays(1).atStartOfDay()

        for ((pkg, value) in p.all) {
            if (value != KIND_SCHEDULE) continue
            val spans = allowedToday(context, pkg, today.dayOfWeek.value) ?: continue
            for (span in spans) {
                val moment = today.atStartOfDay().plusMinutes(span.start.toLong())
                if (moment.isAfter(now) && moment.isBefore(at)) at = moment
            }
        }

        val millis = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    }
}
