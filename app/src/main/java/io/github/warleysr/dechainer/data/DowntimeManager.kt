package io.github.warleysr.dechainer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.warleysr.dechainer.AlarmTickReceiver
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.Downtime
import io.github.warleysr.dechainer.models.SuspensionReason
import io.github.warleysr.dechainer.security.SecurityManager
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZonedDateTime

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

    /** Packages currently required to be suspended by an active downtime rule right now. */
    fun activePackages(context: Context): Set<String> {
        val now = LocalDateTime.now()
        val rules = DowntimeRepository.getRules(context)
        return Downtime.suspendedAt(
            rules, AppGroupRepository.getGroups(), now.dayOfWeek.value, now.hour * 60 + now.minute
        ).filterTo(mutableSetOf()) { it != context.packageName }
    }

    fun sync(context: Context, isRetry: Boolean = false) {
        synchronized(SuspensionCoordinator.lock) {
            try {
                val dpm = DeviceAdmin.policyManager
                val admin = DeviceAdmin.component
                val rules = DowntimeRepository.getRules(context)
                val held = DowntimeRepository.getHeld(context)

                if (!dpm.isAdminActive(admin) || !dpm.isDeviceOwnerApp(context.packageName)) {
                    cancelAlarm(context)
                    return
                }

                val groups = AppGroupRepository.getGroups()
                val now = ZonedDateTime.now()
                val activeRules = activeRulesAt(rules, now)
                val rulePackages = activeRules.associateWith { Downtime.packagesOf(it, groups) }
                val should = rulePackages.values.flatten().filterTo(mutableSetOf()) { it != context.packageName }

                // Leave apps held by impulse lock, active blocks, or manual suspension untouched.
                val impulse = SecurityManager.getActiveImpulseSuspension(context)
                val blocked = BlockSuspensionManager.heldPackages(context)
                val manual = SuspensionCoordinator.manualSuspensions(context)
                val otherOwners = impulse + blocked + manual

                val suspendedByOther = should.filterTo(mutableSetOf()) { it in otherOwners && isSuspended(dpm, admin, it) }
                val plan = Downtime.plan(should, held, suspendedByOther)

                val release = plan.release.filterNot { it in otherOwners }

                val failedRelease = if (release.isNotEmpty()) {
                    dpm.setPackagesSuspended(admin, release.toTypedArray(), false).toSet()
                } else {
                    emptySet()
                }
                val actualRelease = release.toSet() - failedRelease

                val failedSuspend = if (plan.suspend.isNotEmpty()) {
                    dpm.setPackagesSuspended(admin, plan.suspend.toTypedArray(), true).toSet()
                } else {
                    emptySet()
                }
                val actualSuspend = plan.suspend - failedSuspend
                actualSuspend.forEach { pkg ->
                    val rule = activeRules.firstOrNull { pkg in rulePackages[it].orEmpty() }
                    SuspensionLogRepository.log(context, pkg, SuspensionReason.DOWNTIME, rule?.name)
                }

                val finalHeld = (plan.held - failedSuspend) + failedRelease
                DowntimeRepository.setHeld(context, finalHeld)
                if (actualRelease.isNotEmpty() || actualSuspend.isNotEmpty()) {
                    AppRepository.invalidateCache()
                }
                val scheduled = scheduleNext(context, rules, groups, ZonedDateTime.now())
                if (!scheduled && !isRetry && rules.isNotEmpty()) {
                    sync(context, isRetry = true)
                    return
                }
                BlockSuspensionManager.sync(context)
            } catch (e: Exception) {
                Timber.e(e, "Downtime sync failed")
            }
        }
    }

    private fun activeRulesAt(
        rules: List<io.github.warleysr.dechainer.models.DowntimeRule>,
        now: ZonedDateTime
    ): List<io.github.warleysr.dechainer.models.DowntimeRule> {
        val day = now.dayOfWeek.value
        val minute = now.hour * 60 + now.minute
        return rules.filter { rule ->
            rule.windows.any { w -> w.spansOn(day).any { it.contains(minute) } }
        }
    }

    private fun isSuspended(dpm: android.app.admin.DevicePolicyManager, admin: android.content.ComponentName, pkg: String) =
        try { dpm.isPackageSuspended(admin, pkg) } catch (_: Exception) { false }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, AlarmTickReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cancelAlarm(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(context))
    }

    private fun scheduleNext(
        context: Context,
        rules: List<io.github.warleysr.dechainer.models.DowntimeRule>,
        groups: List<AppGroup>,
        now: ZonedDateTime
    ): Boolean {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = Downtime.nextBoundaryMillis(rules, now, groups)
        if (at == null || at <= System.currentTimeMillis()) {
            cancelAlarm(context)
            return at == null
        }
        val pending = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        Timber.d("Downtime: next change at $at (exact=$exact)")
        return true
    }
}
