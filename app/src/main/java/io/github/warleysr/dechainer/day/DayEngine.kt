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
import io.github.warleysr.dechainer.security.SecurityManager
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

    /** Called by every sync pass, before the lock is planned, so a new punishment day is in the plan. Never throws. */
    fun runPass(ctx: Context, now: Long) {
        try {
            val zone = TrustedClock.zone()
            val state = Store.appState(ctx)
            // Enforcement starts when setup finishes (D19); the recovery code is the last step of today's setup.
            var activated = state.get(AppStateKeys.ACTIVATED_ON)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (activated == null && SecurityManager.hasRecoveryCode(ctx)) {
                activated = DayWindow.dateOf(now, zone)
                state.set(AppStateKeys.ACTIVATED_ON, activated.toString())
            }
            if (activated != null) {
                val repo = Store.days(ctx)
                val today = DayWindow.dateOf(now, zone)
                if (DayService.evaluate(repo, repo.stats(zone), activated, now, zone) &&
                    state.get(AppStateKeys.PUNISHMENT_DATE) != today.toString()
                ) {
                    LockStateStore.setPunishment(ctx, PunishmentInput.wholeDay(today, zone), today.toString())
                }
            }
            arm(ctx, now, zone)
        } catch (e: Exception) {
            Timber.e(e, "Day evaluation failed; the lock pass goes on")
        }
    }

    /** The next moment that needs a wake-up: 20:00, 23:00 or midnight. Only a wake-up; the truth is recomputed on arrival. */
    private fun arm(ctx: Context, now: Long, zone: java.time.ZoneId) {
        val today = DayWindow.dateOf(now, zone)
        val next = listOf(
            DayWindow.eveningStart(today, zone), DayWindow.eveningStart(today, zone, DayRules.REMINDER_LATE_HOUR),
            DayWindow.endOf(today, zone) + 1000
        ).first { it > now }
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, TrustedClock.toWall(next, ctx), pending(ctx))
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
