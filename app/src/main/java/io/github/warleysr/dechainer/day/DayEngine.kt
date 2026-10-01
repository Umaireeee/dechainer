package io.github.warleysr.dechainer.day

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.activities.MainActivity
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.lock.PunishmentInput
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.Store
import timber.log.Timber
import java.time.LocalDate

/** The Android side of the daily checklist: activation, the evaluation inside every sync, the evening alarms and notifications. */
object DayEngine {
    const val EXTRA_OPEN_TODAY = "open_today"
    private const val CHANNEL = "evening_checklist"
    private const val NOTIF_ID = 5201
    private const val REQUEST = 5202
    private const val ACTION = "io.github.warleysr.dechainer.DAY_WAKE"

    /** Whether the owner has confirmed the checklist rules, which is what starts enforcement (blueprint 6.4, D19). */
    fun rulesConfirmed(ctx: Context): Boolean = try {
        Store.appState(ctx).get(AppStateKeys.ACTIVATED_ON) != null
    } catch (e: Exception) { false }

    /** The one explicit confirmation at the end of setup. Enforcement starts today; it never moves once set. */
    fun confirmRules(ctx: Context) {
        try {
            val state = Store.appState(ctx)
            if (state.get(AppStateKeys.ACTIVATED_ON) == null) {
                val now = TrustedClock.now(ctx)
                val zone = TrustedClock.zone()
                val today = DayWindow.dateOf(now, zone)
                // Confirming inside tonight's evening window would leave seconds or minutes to write the first plan
                // and punish tomorrow for it. From the evening window on, the first day that counts is tomorrow: the
                // first plan is then due tomorrow evening, with a whole day to prepare.
                val start = if (DayWindow.inEvening(now, today, zone)) today.plusDays(1) else today
                state.set(AppStateKeys.ACTIVATED_ON, start.toString())
            }
            LockEngine.requestSync(ctx.applicationContext)
        } catch (e: Exception) {
            Timber.e(e, "Rules not confirmed")
        }
    }

    /** Called by every sync pass, before the lock is planned, so a new punishment day is in the plan. Never throws. */
    fun runPass(ctx: Context, now: Long) {
        try {
            val zone = TrustedClock.zone()
            val state = Store.appState(ctx)
            // Enforcement starts when setup finishes (D19): on the owner's explicit confirmation of the rules
            // ([confirmRules]), not at a first plan and not by itself.
            val activated = state.get(AppStateKeys.ACTIVATED_ON)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (activated != null) {
                val repo = Store.days(ctx)
                val today = DayWindow.dateOf(now, zone)
                // The first time this runs on a phone, today becomes the first day that counts: days before it
                // were never enforced (the pass was not wired in), so none of them can be punished now.
                val liveFrom = state.get(AppStateKeys.DAY_ENGINE_LIVE_FROM)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: today.also { state.set(AppStateKeys.DAY_ENGINE_LIVE_FROM, it.toString()) }
                val from = effectiveStart(activated, liveFrom)
                if (DayService.evaluate(repo, repo.stats(zone), from, now, zone) &&
                    state.get(AppStateKeys.PUNISHMENT_DATE) != today.toString()
                ) {
                    LockStateStore.setPunishment(ctx, PunishmentInput.wholeDay(today, zone), today.toString())
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Day evaluation failed; the lock pass goes on")
        }
        // The wake-ups are set even when the evaluation threw: losing the 20:00 and midnight alarms would be worse.
        try {
            arm(ctx, now, TrustedClock.zone())
        } catch (e: Exception) {
            Timber.e(e, "Day wake-up not armed")
        }
    }

    /** The day enforcement counts from: the later of the confirmation day and the day the evaluation went live. */
    internal fun effectiveStart(activated: LocalDate, liveFrom: LocalDate): LocalDate = if (liveFrom.isAfter(activated)) liveFrom else activated

    /** The next moment that needs a wake-up: 20:00, 23:00 or midnight. Only a wake-up; the truth is recomputed on arrival. */
    private fun arm(ctx: Context, now: Long, zone: java.time.ZoneId) {
        val today = DayWindow.dateOf(now, zone)
        val next = listOf(
            DayWindow.eveningStart(today, zone), DayWindow.eveningStart(today, zone, DayRules.REMINDER_LATE_HOUR),
            DayWindow.endOf(today, zone) + 1000
        ).first { it > now }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = TrustedClock.toWall(next, ctx)
        // Exact when the phone allows it, so a punishment day and the evening window start on the minute and not minutes late in Doze.
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, REQUEST, Intent(ctx, DayReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun checklistOpen(ctx: Context): Boolean = try {
        val now = TrustedClock.now(ctx)
        Store.appState(ctx).get(AppStateKeys.ACTIVATED_ON) != null &&
            DayService.checklistOpen(Store.days(ctx), now, TrustedClock.zone())
    } catch (e: Exception) { false }

    /** On unlock, in the evening window, an unfinished checklist is brought to the front (blueprint 10). */
    fun showOnUnlock(ctx: Context) {
        if (!checklistOpen(ctx)) return
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TODAY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { Timber.w(e, "Checklist not shown on unlock") }
    }

    internal fun notifyIfOpen(ctx: Context) {
        if (!checklistOpen(ctx)) return
        val zone = TrustedClock.zone()
        val late = java.time.Instant.ofEpochMilli(TrustedClock.now(ctx)).atZone(zone).hour >= DayRules.REMINDER_LATE_HOUR
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.evening_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, REQUEST, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TODAY, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentIntent(open).setAutoCancel(true)
            .setContentText(ctx.getString(if (late) R.string.evening_late else R.string.evening_first))
            .setContentTitle(ctx.getString(R.string.today_title)).build())
    }

    class DayReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val pending = goAsync()
            Thread {
                try {
                    LockEngine.sync(context)
                    notifyIfOpen(context)
                } finally { pending.finish() }
            }.start()
        }
    }
}
