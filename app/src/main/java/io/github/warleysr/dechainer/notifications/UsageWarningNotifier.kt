package io.github.warleysr.dechainer.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.activities.MainActivity
import io.github.warleysr.dechainer.models.UsageAlertStage
import io.github.warleysr.dechainer.models.UsageAlertType
import io.github.warleysr.dechainer.utils.LocaleUtils
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt

object UsageWarningNotifier {
    private const val CHANNEL_SILENT = "usage_warning_silent"
    private const val CHANNEL_SOUND = "usage_warning_sound"
    private const val STATE_PREFS = "usage_warning_state"

    private const val BUCKET_OK_MIN_FRACTION = 0.5f
    private const val BUCKET_WARNING_MIN_FRACTION = 0.2f

    private const val VIBRATE_PULSE_MS = 250L
    private const val VIBRATE_PAUSE_MS = 150L

    private fun channelIdFor(stage: UsageAlertStage): String = when (stage.type) {
        UsageAlertType.SILENT -> CHANNEL_SILENT
        UsageAlertType.SOUND -> CHANNEL_SOUND
        UsageAlertType.VIBRATE -> "usage_warning_vibrate_${stage.vibrationCount.coerceAtLeast(1)}"
    }

    private fun localized(context: Context): Context {
        val locale = Locale.forLanguageTag(LocaleUtils.getLocale(context))
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }

    private fun ensureChannel(context: Context, stage: UsageAlertStage) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = channelIdFor(stage)
        if (manager.getNotificationChannel(channelId) != null) return

        val localizedContext = localized(context)
        val channel = when (stage.type) {
            UsageAlertType.SILENT -> NotificationChannel(
                channelId, localizedContext.getString(R.string.usage_warning_channel_silent), NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }

            UsageAlertType.SOUND -> NotificationChannel(
                channelId, localizedContext.getString(R.string.usage_warning_channel_sound), NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableVibration(false)
            }

            UsageAlertType.VIBRATE -> NotificationChannel(
                channelId,
                localizedContext.getString(R.string.usage_warning_channel_vibrate, stage.vibrationCount.coerceAtLeast(1)),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = buildVibrationPattern(stage.vibrationCount)
            }
        }
        channel.description = localizedContext.getString(R.string.usage_warning_channel_desc)
        manager.createNotificationChannel(channel)
    }

    private fun buildVibrationPattern(count: Int): LongArray {
        val n = count.coerceAtLeast(1)
        val pattern = LongArray(n * 2)
        for (i in 0 until n) {
            pattern[i * 2] = if (i == 0) 0L else VIBRATE_PAUSE_MS
            pattern[i * 2 + 1] = VIBRATE_PULSE_MS
        }
        return pattern
    }

    fun notificationIdFor(key: String, stageId: String) = "$key:$stageId".hashCode()

    fun hasBeenShown(context: Context, key: String, stageId: String) =
        statePrefs(context).getBoolean(shownKey(key, stageId), false)

    fun markShown(context: Context, key: String, stageId: String) {
        statePrefs(context).edit { putBoolean(shownKey(key, stageId), true) }
    }

    fun isDismissed(context: Context, key: String, stageId: String) =
        statePrefs(context).getBoolean(dismissedKey(key, stageId), false)

    fun markDismissed(context: Context, key: String, stageId: String) {
        statePrefs(context).edit { putBoolean(dismissedKey(key, stageId), true) }
    }

    fun reset(context: Context, key: String, stageId: String) {
        statePrefs(context).edit {
            remove(shownKey(key, stageId))
            remove(dismissedKey(key, stageId))
        }
    }

    fun resetAll(context: Context) {
        statePrefs(context).edit { clear() }
    }

    fun cancel(context: Context, key: String, stageId: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(key, stageId))
    }

    fun showOrUpdate(
        context: Context,
        key: String,
        stage: UsageAlertStage,
        displayName: String,
        remainingMillis: Long,
        thresholdMillis: Long
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Timber.d("UsageWarning: showOrUpdate($key,${stage.id}) - notifications not enabled, skipping notify()")
            return
        }

        ensureChannel(context, stage)
        val localizedContext = localized(context)

        val clampedRemaining = remainingMillis.coerceIn(0L, thresholdMillis)
        val fraction = if (thresholdMillis > 0) clampedRemaining.toFloat() / thresholdMillis else 0f

        val totalSeconds = clampedRemaining / 1000
        val timeText = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)

        val views = RemoteViews(context.packageName, R.layout.notification_usage_warning).apply {
            setTextViewText(R.id.usage_warning_title, localizedContext.getString(R.string.usage_warning_title))
            setTextViewText(
                R.id.usage_warning_message,
                localizedContext.getString(R.string.usage_warning_message, timeText, displayName)
            )

            val progress = (fraction * 100).roundToInt().coerceIn(0, 100)
            setProgressBar(R.id.usage_warning_progress_ok, 100, progress, false)
            setProgressBar(R.id.usage_warning_progress_warning, 100, progress, false)
            setProgressBar(R.id.usage_warning_progress_critical, 100, progress, false)

            val (visibleId, hiddenIds) = when {
                fraction >= BUCKET_OK_MIN_FRACTION -> R.id.usage_warning_progress_ok to
                    listOf(R.id.usage_warning_progress_warning, R.id.usage_warning_progress_critical)
                fraction >= BUCKET_WARNING_MIN_FRACTION -> R.id.usage_warning_progress_warning to
                    listOf(R.id.usage_warning_progress_ok, R.id.usage_warning_progress_critical)
                else -> R.id.usage_warning_progress_critical to
                    listOf(R.id.usage_warning_progress_ok, R.id.usage_warning_progress_warning)
            }
            setViewVisibility(visibleId, View.VISIBLE)
            hiddenIds.forEach { setViewVisibility(it, View.GONE) }
        }

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deleteIntent = PendingIntent.getBroadcast(
            context, notificationIdFor(key, stage.id),
            Intent(context, UsageWarningDismissReceiver::class.java).apply {
                putExtra(UsageWarningDismissReceiver.EXTRA_KEY, key)
                putExtra(UsageWarningDismissReceiver.EXTRA_STAGE_ID, stage.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelIdFor(stage))
            .setSmallIcon(R.drawable.ic_notification_time_warning)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        Timber.d(
            "UsageWarning: showOrUpdate($key,${stage.id}) notify() id=${notificationIdFor(key, stage.id)} " +
                "remaining=${remainingMillis}ms fraction=$fraction timeText=$timeText"
        )
        NotificationManagerCompat.from(context).notify(notificationIdFor(key, stage.id), notification)
    }

    private fun statePrefs(context: Context) = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
    private fun shownKey(key: String, stageId: String) = "shown_$key:$stageId"
    private fun dismissedKey(key: String, stageId: String) = "dismissed_$key:$stageId"
}
