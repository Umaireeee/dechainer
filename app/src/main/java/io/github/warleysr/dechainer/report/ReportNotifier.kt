package io.github.warleysr.dechainer.report

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.activities.MainActivity
import io.github.warleysr.dechainer.store.StoredReport

/** The "Weekly report" channel (blueprint 10): one notification when a report is saved, opening it in the app. */
object ReportNotifier {
    const val EXTRA_OPEN_REPORT = "open_report"
    private const val CHANNEL = "weekly_report"
    private const val NOTIF_ID = 6101

    fun post(ctx: Context, report: StoredReport) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.report_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, NOTIF_ID,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_REPORT, report.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentIntent(open).setAutoCancel(true)
            .setContentTitle(ctx.getString(R.string.report_ready)).build())
    }
}
