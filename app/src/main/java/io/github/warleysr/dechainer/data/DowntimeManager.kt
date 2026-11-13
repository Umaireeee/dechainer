package io.github.warleysr.dechainer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.warleysr.dechainer.DowntimeReceiver
import io.github.warleysr.dechainer.models.Downtime
import io.github.warleysr.dechainer.models.SuspensionReason
import io.github.warleysr.dechainer.security.SecurityManager
import timber.log.Timber
import java.time.LocalDateTime

/**
 * Makes the device match the downtime rules: suspends the apps of every running window, gives
 * them back when it ends, and sets one exact alarm for the next change. Nothing runs in between,
 * so downtime costs no battery while idle.
 *
 * [sync] is idempotent, so it is called from everywhere the picture can change: the alarm, boot,
 * clock changes, rule or group edits, the accessibility service connecting and app start.
 */
object DowntimeManager {
    const val ACTION_TICK = "io.github.warleysr.dechainer.DOWNTIME_TICK"

    fun heldPackages(context: Context): Set<String> = DowntimeRepository.getHeld(context)

    fun sync(context: Context) {
        try {
            val dpm = DeviceAdmin.policyManager
            val admin = DeviceAdmin.component
            val rules = DowntimeRepository.getRules(context)
            val held = DowntimeRepository.getHeld(context)

            if (!dpm.isAdminActive(admin) || !dpm.isDeviceOwnerApp(context.packageName)) {
                cancelAlarm(context)
                return
            }

            val now = LocalDateTime.now()
            val should = Downtime.suspendedAt(
                rules, AppGroupRepository.getGroups(), now.dayOfWeek.value, now.hour * 60 + now.minute
            ).filterTo(mutableSetOf()) { it != context.packageName }

            val suspendedNow = (should + held).filterTo(mutableSetOf()) { isSuspended(dpm, admin, it) }
            val plan = Downtime.plan(should, held, suspendedNow)

            // The impulse lock may hold the same apps; leave those to it.
            val impulse = SecurityManager.getActiveImpulseSuspension(context)
            val release = plan.release.filterNot { it in impulse }
            if (release.isNotEmpty()) dpm.setPackagesSuspended(admin, release.toTypedArray(), false)
            if (plan.suspend.isNotEmpty()) {
                dpm.setPackagesSuspended(admin, plan.suspend.toTypedArray(), true)
                val names = rules.associate { it.id to it.name }
                plan.suspend.forEach { pkg ->
                    val rule = rules.firstOrNull { pkg in Downtime.packagesOf(it, AppGroupRepository.getGroups()) }
                    SuspensionLogRepository.log(context, pkg, SuspensionReason.DOWNTIME, rule?.let { names[it.id] })
                }
            }

            DowntimeRepository.setHeld(context, plan.held)
            AppRepository.invalidateCache()
            scheduleNext(context, rules, now)
        } catch (e: Exception) {
            Timber.e(e, "Downtime sync failed")
        }
    }

    private fun isSuspended(dpm: android.app.admin.DevicePolicyManager, admin: android.content.ComponentName, pkg: String) =
        try { dpm.isPackageSuspended(admin, pkg) } catch (_: Exception) { false }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, DowntimeReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cancelAlarm(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(context))
    }

    private fun scheduleNext(context: Context, rules: List<io.github.warleysr.dechainer.models.DowntimeRule>, now: LocalDateTime) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = Downtime.nextBoundaryMillis(rules, now)
        if (at == null) {
            cancelAlarm(context)
            return
        }
        val pending = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        Timber.d("Downtime: next change at $at (exact=$exact)")
    }
}
