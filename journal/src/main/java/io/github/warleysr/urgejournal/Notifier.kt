package io.github.warleysr.urgejournal

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

/** The two reminders, both off the moment you turn them off. Kept here so a receiver can read them. */
class ReminderSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** A quiet "how did it go?" about 20 minutes after an urge you rode out. On by default. */
    var checkIn: Boolean
        get() = prefs.getBoolean("checkin", true)
        set(v) = prefs.edit { putBoolean("checkin", v) }

    /** Minutes from midnight of the daily heads-up before your tough time of day, or -1 for off. */
    var nudgeMinute: Int
        get() = prefs.getInt("nudge", -1)
        set(v) = prefs.edit { putInt("nudge", v) }

}

/**
 * Notifications, deliberately plain: nothing on the lock screen says what the app is about. Alarms
 * are the inexact kind, so no special permission is needed and a few minutes' drift doesn't matter.
 */
object Notifier {
    const val ACTION_CHECKIN = "io.github.warleysr.urgejournal.CHECKIN"
    const val ACTION_NUDGE = "io.github.warleysr.urgejournal.NUDGE"
    const val ACTION_BLOCK = "io.github.warleysr.urgejournal.BLOCK_NOW"

    /** How long after a ride starts the check-in comes. */
    const val CHECKIN_DELAY_MS = 20L * 60 * 1000

    private const val CHANNEL = "reminders"
    private const val ID_CHECKIN = 11
    private const val ID_NUDGE = 12
    private const val REQ_CHECKIN_ALARM = 21
    private const val REQ_NUDGE_ALARM = 22
    private const val REQ_BLOCK = 23
    private const val REQ_OPEN_CHECKIN = 24
    private const val REQ_OPEN_NUDGE = 25

    /** True when Android would actually show a notification from this app right now. */
    fun canPost(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** True when the person still has to be asked for the notification permission (Android 13 and up). */
    fun needsPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun alarmManager(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun alarmIntent(ctx: Context, action: String, request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, request,
            Intent(ctx, ReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun scheduleCheckIn(ctx: Context, at: Long) {
        if (!ReminderSettings(ctx).checkIn) return
        runCatching {
            alarmManager(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx, ACTION_CHECKIN, REQ_CHECKIN_ALARM))
        }
    }

    fun cancelCheckIn(ctx: Context) {
        runCatching { alarmManager(ctx).cancel(alarmIntent(ctx, ACTION_CHECKIN, REQ_CHECKIN_ALARM)) }
        NotificationManagerCompat.from(ctx).cancel(ID_CHECKIN)
    }

    /** Arms (or clears) the next daily heads-up from the saved time. */
    fun rearmNudge(ctx: Context) {
        val minute = ReminderSettings(ctx).nudgeMinute
        val pi = alarmIntent(ctx, ACTION_NUDGE, REQ_NUDGE_ALARM)
        runCatching {
            if (minute < 0) alarmManager(ctx).cancel(pi)
            else alarmManager(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Times.nextDaily(minute, System.currentTimeMillis()), pi)
        }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun openApp(ctx: Context, action: String, request: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, request,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun post(ctx: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        try {
            NotificationManagerCompat.from(ctx).notify(id, builder.build())
        } catch (_: SecurityException) {
            // Permission taken away between the check and the call; nothing to do.
        }
    }

    private fun base(ctx: Context, title: Int, text: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(ctx.getString(title))
            .setContentText(ctx.getString(text))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    fun postCheckIn(ctx: Context) {
        post(
            ctx, ID_CHECKIN,
            base(ctx, R.string.notif_checkin_title, R.string.notif_checkin_text)
                .setContentIntent(openApp(ctx, MainActivity.ACTION_CHECKIN, REQ_OPEN_CHECKIN))
        )
    }

    fun postNudge(ctx: Context) {
        post(
            ctx, ID_NUDGE,
            base(ctx, R.string.notif_nudge_title, R.string.notif_nudge_text)
                .setContentIntent(openApp(ctx, MainActivity.ACTION_NONE, REQ_OPEN_NUDGE))
                .addAction(
                    0, ctx.getString(R.string.notif_nudge_action),
                    alarmIntent(ctx, ACTION_BLOCK, REQ_BLOCK)
                )
        )
    }

    fun cancelNudge(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_NUDGE)
}

/** Fires the reminders, and puts the daily one back after a reboot or an update. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Notifier.ACTION_CHECKIN -> {
                // Only if a ride is still waiting for its check-in.
                if (JournalStore(ctx).pendingRide() != 0L && ReminderSettings(ctx).checkIn) Notifier.postCheckIn(ctx)
            }
            Notifier.ACTION_NUDGE -> {
                if (ReminderSettings(ctx).nudgeMinute >= 0) Notifier.postNudge(ctx)
                Notifier.rearmNudge(ctx)
            }
            Notifier.ACTION_BLOCK -> {
                Door.send(ctx, DoorAction(DoorAction.IMPULSE_BLOCK, NUDGE_BLOCK_MINUTES))
                Notifier.cancelNudge(ctx)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Notifier.rearmNudge(ctx)
        }
    }

    private companion object {
        const val NUDGE_BLOCK_MINUTES = 60
    }
}
