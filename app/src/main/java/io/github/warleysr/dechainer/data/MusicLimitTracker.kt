package io.github.warleysr.dechainer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.warleysr.dechainer.AlarmTickReceiver
import io.github.warleysr.dechainer.DechainerAccessibilityService
import timber.log.Timber
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Counts time an app spends playing media in the background, so both per-app/group daily limits
 * and allowed time windows cover listening while the app isn't on screen.
 *
 * Driven by playback events ([MediaPlaybackListener]), foreground transitions
 * ([DechainerAccessibilityService]), preference edits, and a backup AlarmManager wakeup so
 * deep sleep / DSP audio offload never stalls enforcement.
 */
object MusicLimitTracker {
    const val ACTION_TICK = "io.github.warleysr.dechainer.MUSIC_LIMIT_TICK"

    private const val ALARM_REQUEST_CODE = 7305
    private val CHECKPOINT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    private val handler = Handler(Looper.getMainLooper())

    private val playing = mutableSetOf<String>()
    private val controllers = HashMap<String, MediaController>()
    // Package -> elapsedRealtime when the current un-flushed background stretch started.
    private val counting = HashMap<String, Long>()
    private var activeDate: LocalDate = LocalDate.now()

    private var appContext: Context? = null

    private val limitTimer = Runnable { appContext?.let { onAlarmTick(it) } }

    /** Called when an app starts or stops playing. [controller] is kept so the block can pause it. */
    @Synchronized
    fun onPlaybackChanged(context: Context, pkg: String, controller: MediaController?, isPlaying: Boolean) {
        appContext = context.applicationContext
        if (isPlaying && controller != null) {
            playing += pkg
            controllers[pkg] = controller
        } else {
            playing -= pkg
            controllers.remove(pkg)
        }
        reconcile(context.applicationContext)
    }

    /** Called when the foreground app changes, including to the launcher or when the screen turns off. */
    @Synchronized
    fun onForegroundChanged(context: Context) {
        appContext = context.applicationContext
        reconcile(context.applicationContext)
    }

    /** Called when limits, groups, or time windows change while audio may be playing. */
    @Synchronized
    fun onLimitsChanged(context: Context) {
        appContext = context.applicationContext
        reconcile(context.applicationContext)
    }

    /** Called when the scheduled timer or backup exact alarm fires. */
    @Synchronized
    fun onAlarmTick(context: Context) {
        appContext = context.applicationContext
        reconcile(context.applicationContext)
    }

    /** Called when the media listener disconnects: nothing can be playing that we know about. */
    @Synchronized
    fun onListenerLost(context: Context) {
        appContext = context.applicationContext
        playing.clear()
        controllers.clear()
        reconcile(context.applicationContext)
    }

    private fun reconcile(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val nowZoned = ZonedDateTime.now()
        val today = nowZoned.toLocalDate()

        if (today.isAfter(activeDate)) {
            // Reset yesterday's counters BEFORE recording any post-midnight delta so yesterday's
            // usage never leaks into today's daily allowance.
            val millisSinceMidnight = nowZoned.toLocalTime().toSecondOfDay() * 1000L
            val midnightElapsed = (now - millisSinceMidnight).coerceAtLeast(0L)
            DailyUsageStore.ensureCurrentDate(context, today)
            for (pkg in counting.keys.toList()) {
                val started = counting[pkg] ?: continue
                counting[pkg] = midnightElapsed.coerceIn(started, now)
            }
            activeDate = today
        } else {
            if (DailyUsageStore.ensureCurrentDate(context, today)) {
                for (pkg in counting.keys.toList()) counting[pkg] = now
            }
        }

        // Flush accumulated elapsed time so far for active stretches before evaluating remaining caps.
        for (pkg in counting.keys.toList()) {
            flushStretch(context, pkg, now, today)
        }

        val foreground = DechainerAccessibilityService.foregroundPackage
        val dailyPrefs = context.getSharedPreferences(AppTimeLimits.PREFS_NAME, Context.MODE_PRIVATE)
        val weeklyPrefs = context.getSharedPreferences(AppTimeLimits.WEEKLY_PREFS_NAME, Context.MODE_PRIVATE)
        val windowsPrefs = context.getSharedPreferences(AppTimeWindows.PREFS_NAME, Context.MODE_PRIVATE)
        val dayValue = today.dayOfWeek.value
        val nowSecondOfDay = nowZoned.toLocalTime().toSecondOfDay()

        val shouldCount = mutableSetOf<String>()
        var nextDelayMillis: Long? = null

        for (pkg in playing.toList()) {
            if (pkg == foreground) continue

            val group = AppGroupRepository.getGroupForPackage(pkg)
            val appWindows = AppTimeWindows.decode(windowsPrefs.getString(pkg, null))
            val allowedSpans = io.github.warleysr.dechainer.models.TimeWindow.allowedSpans(
                listOf(group?.timeWindows.orEmpty(), appWindows), dayValue
            )

            if (allowedSpans != null) {
                val activeSpan = allowedSpans.firstOrNull { nowSecondOfDay in (it.start * 60) until (it.end * 60) }
                if (activeSpan == null) {
                    block(context, pkg, outsideWindow = true)
                    continue
                }
                val endMoment = today.atStartOfDay().plusMinutes(activeSpan.end.toLong())
                val endInstant = SuspensionCoordinator.resolvedInstants(
                    endMoment,
                    nowZoned.zone,
                    nowZoned.zone.rules
                ).firstOrNull { it.isAfter(nowZoned.toInstant()) }
                val untilWindowEnd = endInstant?.let {
                    java.time.Duration.between(nowZoned.toInstant(), it).toMillis().coerceAtLeast(1000L)
                } ?: ((activeSpan.end * 60 - nowSecondOfDay) * 1000L).coerceAtLeast(1000L)
                nextDelayMillis = minOf(nextDelayMillis ?: untilWindowEnd, untilWindowEnd)
            }

            val appLimit = AppTimeLimits.read(dailyPrefs, weeklyPrefs, pkg).minutesFor(today.dayOfWeek)
            val groupLimit = group?.timeLimit?.minutesFor(today.dayOfWeek) ?: 0

            if (appLimit > 0 || (group != null && groupLimit > 0)) {
                val appRem = if (appLimit > 0) {
                    TimeUnit.MINUTES.toMillis(appLimit.toLong()) - DailyUsageStore.getAppUsageMillis(context, pkg)
                } else Long.MAX_VALUE
                val groupRem = if (group != null && groupLimit > 0) {
                    TimeUnit.MINUTES.toMillis(groupLimit.toLong()) - DailyUsageStore.getGroupUsageMillis(context, group.id)
                } else Long.MAX_VALUE
                val remaining = minOf(appRem, groupRem)
                if (remaining <= 0L) {
                    block(context, pkg, outsideWindow = false)
                    continue
                }
                shouldCount += pkg
                nextDelayMillis = minOf(nextDelayMillis ?: remaining, remaining)
            }
        }

        for (pkg in counting.keys.toList()) {
            if (pkg !in shouldCount) counting.remove(pkg)
        }
        for (pkg in shouldCount) {
            if (pkg !in counting) counting[pkg] = now
        }

        handler.removeCallbacks(limitTimer)
        cancelAlarm(context)

        val anyActiveLimit = counting.isNotEmpty()
        val anyTrackedWindowOrVarying = nextDelayMillis != null || playing.any { pkg ->
            pkg != foreground && (
                AppTimeLimits.read(dailyPrefs, weeklyPrefs, pkg).isSet ||
                    AppGroupRepository.getGroupForPackage(pkg)?.timeLimit?.isSet == true
                )
        }
        if (anyActiveLimit) {
            nextDelayMillis = minOf(nextDelayMillis ?: CHECKPOINT_INTERVAL_MS, CHECKPOINT_INTERVAL_MS)
        }
        if (anyTrackedWindowOrVarying) {
            val nextMidnight = today.plusDays(1).atStartOfDay(nowZoned.zone)
            val untilMidnight = Duration.between(nowZoned, nextMidnight).toMillis().coerceAtLeast(1000L)
            nextDelayMillis = minOf(nextDelayMillis ?: untilMidnight, untilMidnight)
        }

        nextDelayMillis?.let { delay ->
            handler.postDelayed(limitTimer, delay)
            scheduleAlarm(context, now + delay)
        }
    }

    private fun flushStretch(context: Context, pkg: String, now: Long, date: LocalDate) {
        val started = counting[pkg] ?: return
        val delta = (now - started).coerceAtLeast(0L)
        if (delta > 0L) {
            addUsageFor(context, pkg, delta, date)
        }
        counting[pkg] = now
    }

    private fun addUsageFor(context: Context, pkg: String, deltaMillis: Long, date: LocalDate) {
        val daily = context.getSharedPreferences(AppTimeLimits.PREFS_NAME, Context.MODE_PRIVATE)
        val weekly = context.getSharedPreferences(AppTimeLimits.WEEKLY_PREFS_NAME, Context.MODE_PRIVATE)
        if (AppTimeLimits.read(daily, weekly, pkg).minutesFor(date.dayOfWeek) > 0) {
            DailyUsageStore.addAppUsageMillis(context, pkg, deltaMillis)
        }
        val group = AppGroupRepository.getGroupForPackage(pkg)
        if (group != null && group.timeLimit.minutesFor(date.dayOfWeek) > 0) {
            DailyUsageStore.addGroupUsageMillis(context, group.id, deltaMillis)
        }
    }

    private fun block(context: Context, pkg: String, outsideWindow: Boolean) {
        counting.remove(pkg)
        playing.remove(pkg)
        val controller = controllers.remove(pkg)
        try {
            controller?.transportControls?.pause()
        } catch (e: Exception) {
            Timber.e(e, "Could not pause $pkg")
        }
        BlockSuspensionManager.suspend(context, pkg, outsideWindow = outsideWindow)
        Timber.d("Music limit/window enforced for $pkg (outsideWindow=$outsideWindow)")
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, AlarmTickReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleAlarm(context: Context, triggerElapsed: Long) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pending)
        }
    }

    private fun cancelAlarm(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarms.cancel(alarmIntent(context))
    }
}
