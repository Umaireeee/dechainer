package io.github.warleysr.dechainer.data

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.utils.ShizukuRunner
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Keeps advanced blocking (the accessibility service) switched on.
 *
 * The phone can switch the service off without being asked: its process is killed by a battery
 * manager, it crashes, or an update replaces the app. Nothing used to bring it back until the app
 * was opened and the toggle flipped. While the user *wants* it on, this checks now and then, and
 * after a reboot, an update or an unexpected stop, and switches it back on.
 *
 * "Wants it on" is remembered: it becomes true when the service connects or is turned on in the app,
 * and false when it is turned off through the app (which is behind the recovery gate), so a
 * deliberate shutdown is never undone. Needs the WRITE_SECURE_SETTINGS permission, which the app
 * is granted when advanced blocking is first turned on.
 */
object AccessibilityWatchdog {
    private const val PREFS = "accessibility_watchdog"
    private const val KEY_WANTED = "wanted"
    const val ACTION_CHECK = "io.github.warleysr.dechainer.ACCESSIBILITY_WATCHDOG"
    private const val PERIODIC_REQUEST = 7301
    private const val SOON_REQUEST = 7302
    private const val SOON_DELAY_MS = 1_500L
    private const val GUARD_SETTLE_DELAY_MS = 400L
    private const val REBIND_GAP_MS = 1_000L

    /** Pure helpers on the colon separated `enabled_accessibility_services` value. */
    object ServiceList {
        fun entries(value: String?): List<String> =
            value.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }

        /** Both spellings the system and this app use: `pkg/.Service` and `pkg/pkg.Service`. */
        fun isOurs(entry: String, packageName: String, serviceClass: String): Boolean {
            val short = serviceClass.removePrefix(packageName)
            return entry == "$packageName/$serviceClass" || entry == "$packageName/$short"
        }

        fun isListed(value: String?, packageName: String, serviceClass: String) =
            entries(value).any { isOurs(it, packageName, serviceClass) }

        fun without(value: String?, packageName: String, serviceClass: String) =
            entries(value).filterNot { isOurs(it, packageName, serviceClass) }.joinToString(":")

        fun with(value: String?, packageName: String, serviceClass: String): String =
            (entries(value).filterNot { isOurs(it, packageName, serviceClass) } +
                "$packageName/$serviceClass").joinToString(":")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isWanted(context: Context) = prefs(context).getBoolean(KEY_WANTED, false)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var rebinding = false
    private var rebindRunnable: Runnable? = null

    fun setWanted(context: Context, wanted: Boolean) {
        prefs(context).edit { putBoolean(KEY_WANTED, wanted) }
        if (!wanted) {
            rebinding = false
            rebindRunnable?.let { mainHandler.removeCallbacks(it) }
            rebindRunnable = null
            cancelAll(context)
        }
    }

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun grantWriteSecureSettingsViaShizuku(context: Context): Boolean {
        if (hasPermission(context)) return true
        return try {
            if (!Shizuku.pingBinder() || !DeviceOwnerRepository.checkShizukuPermission()) return false
            ShizukuRunner.exec("pm", "grant", context.packageName, Manifest.permission.WRITE_SECURE_SETTINGS)
            hasPermission(context)
        } catch (e: Exception) {
            Timber.w(e, "Could not grant WRITE_SECURE_SETTINGS via Shizuku")
            false
        }
    }

    /**
     * Switches the service back on if it should be on but isn't running.
     * Returns `true` when an async off/on rebind gap was scheduled so receivers stay alive for it.
     */
    fun ensureEnabled(context: Context): Boolean {
        val app = context.applicationContext
        if (!isWanted(app) || !hasPermission(app)) return false

        val resolver = app.contentResolver
        val serviceClass = DechainerAccessibilityService::class.java.name
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val listed = ServiceList.isListed(current, app.packageName, serviceClass)
        val globallyEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (listed && globallyEnabled && DechainerAccessibilityService.isRunning) return false

        return try {
            if (listed && !DechainerAccessibilityService.isRunning) {
                // Listed but not running (killed or crashed): take it out of the list and put it back,
                // which makes the system start it afresh.
                rebindRunnable?.let { mainHandler.removeCallbacks(it) }
                rebinding = true
                Settings.Secure.putString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ServiceList.without(current, app.packageName, serviceClass)
                )
                val runnable = Runnable {
                    rebinding = false
                    rebindRunnable = null
                    addToList(app)
                }
                rebindRunnable = runnable
                mainHandler.postDelayed(runnable, REBIND_GAP_MS)
                true
            } else {
                addToList(app)
                false
            }
        } catch (e: SecurityException) {
            rebinding = false
            Timber.e(e, "Could not switch advanced blocking back on")
            false
        }
    }

    private var guardObserver: android.database.ContentObserver? = null

    /**
     * Puts the service back the moment it is removed from the enabled list or global accessibility
     * is turned off, as long as the app wants it on.
     */
    fun startGuard(context: Context) {
        val app = context.applicationContext
        if (guardObserver != null) return
        val observer = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                if (rebinding || !isWanted(app) || !hasPermission(app)) return
                val resolver = app.contentResolver
                val listed = ServiceList.isListed(
                    Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    app.packageName,
                    DechainerAccessibilityService::class.java.name
                )
                val globallyEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                if (!listed || !globallyEnabled || !DechainerAccessibilityService.isRunning) {
                    ensureEnabled(app)
                    // Re-check once after Android Settings finishes its toggle sequence.
                    mainHandler.postDelayed({
                        if (!rebinding && isWanted(app) && hasPermission(app)) ensureEnabled(app)
                    }, GUARD_SETTLE_DELAY_MS)
                }
            }
        }
        app.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer
        )
        app.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer
        )
        guardObserver = observer
    }

    private fun addToList(context: Context) {
        if (!isWanted(context) || !hasPermission(context)) return
        try {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Settings.Secure.putString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ServiceList.with(current, context.packageName, DechainerAccessibilityService::class.java.name)
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: SecurityException) {
            Timber.e(e, "Could not switch advanced blocking back on")
        }
    }

    /** Called after a reboot or an app update. Zero periodic polling: only re-enables if needed. */
    fun onSystemEvent(context: Context) {
        cancelLegacyPeriodic(context)
        if (!isWanted(context)) return
        ensureEnabled(context)
        scheduleSoon(context)
    }

    private fun pendingIntent(context: Context, requestCode: Int) = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, AccessibilityWatchdogReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * Legacy entrypoint kept for binary callers: cancels any old periodic repeating alarm so
     * watchdog recovery remains strictly event-driven with zero background polling.
     */
    fun schedulePeriodic(context: Context) {
        cancelLegacyPeriodic(context)
    }

    fun cancelLegacyPeriodic(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(pendingIntent(context, PERIODIC_REQUEST))
    }

    /** One check shortly from now, for when the service has just stopped unexpectedly. */
    fun scheduleSoon(context: Context) {
        if (!isWanted(context)) return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + SOON_DELAY_MS,
            pendingIntent(context, SOON_REQUEST)
        )
    }

    private fun cancelAll(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(pendingIntent(context, PERIODIC_REQUEST))
        alarms.cancel(pendingIntent(context, SOON_REQUEST))
    }
}

class AccessibilityWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AccessibilityWatchdog.ACTION_CHECK) return
        val scheduledRebind = try {
            AccessibilityWatchdog.ensureEnabled(context)
        } catch (e: Exception) {
            Timber.e(e, "Accessibility watchdog check failed")
            false
        }
        if (scheduledRebind) {
            // Stay alive only for the short gap of the off/on rebind.
            val pending = goAsync()
            Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 2_500L)
        }
    }
}
