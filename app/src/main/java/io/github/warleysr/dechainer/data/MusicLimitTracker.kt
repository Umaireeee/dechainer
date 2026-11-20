package io.github.warleysr.dechainer.data

import android.content.Context
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.models.SuspensionReason
import timber.log.Timber
import java.time.LocalDate

/**
 * Counts time an app spends playing music in the background, so its daily limit also covers
 * listening while the app isn't on screen.
 *
 * It is driven entirely by events: playback starting or stopping (from [MediaPlaybackListener]) and
 * the foreground app changing (from the accessibility service). An app is counted while it is
 * playing and not in front, because foreground time is already counted by the accessibility
 * service. A single timer is set for the moment the limit would run out, so nothing polls.
 */
object MusicLimitTracker {
    private val handler = Handler(Looper.getMainLooper())

    private val playing = mutableSetOf<String>()
    private val controllers = HashMap<String, MediaController>()
    // Package -> elapsedRealtime when it started being counted.
    private val counting = HashMap<String, Long>()
    // Packages blocked today; they stay out until the day changes.
    private val blockedOn = HashMap<String, LocalDate>()

    private var appContext: Context? = null

    private val limitTimer = Runnable { appContext?.let { reconcile(it) } }

    /** Called when an app starts or stops playing. [controller] is kept so the block can pause it. */
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
    fun onForegroundChanged(context: Context) {
        appContext = context.applicationContext
        reconcile(context.applicationContext)
    }

    /** Called when the media listener disconnects: nothing can be playing that we know about. */
    fun onListenerLost(context: Context) {
        appContext = context.applicationContext
        playing.clear()
        controllers.clear()
        reconcile(context.applicationContext)
    }

    private fun reconcile(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val today = LocalDate.now()
        val foreground = DechainerAccessibilityService.foregroundPackage

        val shouldCount = playing.filterTo(mutableSetOf()) { pkg ->
            pkg != foreground && blockedOn[pkg] != today && limitMinutes(context, pkg) > 0
        }

        for (pkg in counting.keys.toList()) {
            if (pkg !in shouldCount) stop(context, pkg, now)
        }
        for (pkg in shouldCount) {
            if (pkg !in counting) counting[pkg] = now
        }

        handler.removeCallbacks(limitTimer)
        var nextMillis: Long? = null
        for (pkg in counting.keys.toList()) {
            val remaining = remainingMillis(context, pkg, now)
            if (remaining <= 0) {
                block(context, pkg, now)
            } else {
                nextMillis = minOf(nextMillis ?: remaining, remaining)
            }
        }
        nextMillis?.let { handler.postDelayed(limitTimer, it) }
    }

    private fun limitMinutes(context: Context, pkg: String): Int {
        val daily = context.getSharedPreferences(AppTimeLimits.PREFS_NAME, Context.MODE_PRIVATE)
        val weekly = context.getSharedPreferences(AppTimeLimits.WEEKLY_PREFS_NAME, Context.MODE_PRIVATE)
        return AppTimeLimits.read(daily, weekly, pkg).todayMinutes()
    }

    private fun usagePrefs(context: Context) =
        context.getSharedPreferences("internal_usage_stats", Context.MODE_PRIVATE)

    /** Time left in today's limit, counting the current stretch of playback as already used. */
    private fun remainingMillis(context: Context, pkg: String, now: Long): Long {
        val started = counting[pkg] ?: return Long.MAX_VALUE
        val limitMillis = limitMinutes(context, pkg).toLong() * 60_000L
        val used = usagePrefs(context).getLong(pkg, 0L) + (now - started)
        return limitMillis - used
    }

    private fun stop(context: Context, pkg: String, now: Long) {
        val started = counting.remove(pkg) ?: return
        val elapsed = now - started
        val prefs = usagePrefs(context)
        prefs.edit { putLong(pkg, prefs.getLong(pkg, 0L) + elapsed) }
    }

    private fun block(context: Context, pkg: String, now: Long) {
        stop(context, pkg, now)
        blockedOn[pkg] = LocalDate.now()
        try {
            controllers[pkg]?.transportControls?.pause()
        } catch (e: Exception) {
            Timber.e(e, "Could not pause $pkg")
        }
        BlockSuspensionManager.suspend(context, pkg, outsideWindow = false)
        Timber.d("Music limit reached for $pkg")
    }
}
