package io.github.warleysr.dechainer.data

import android.Manifest
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver
import io.github.warleysr.dechainer.MediaPlaybackListener
import io.github.warleysr.dechainer.utils.ShizukuRunner
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Protects both Notification layers with zero background polling and zero battery cost:
 * 1. `POST_NOTIFICATIONS` runtime permission (API 33+): restored via Device Owner
 *    `setPermissionGrantState` whenever `ACTION_APP_BLOCK_STATE_CHANGED` reports a block or on sync.
 * 2. Notification Access (`MediaPlaybackListener` in `enabled_notification_listeners`): once the
 *    user enables notification access for music limits, a passive [ContentObserver] restores it if
 *    removed outside the app (requires `WRITE_SECURE_SETTINGS`, which Shizuku grants alongside
 *    advanced blocking).
 */
object NotificationGuard {
    private const val PREFS = "notification_guard"
    private const val KEY_LISTENER_WANTED = "listener_wanted"
    private const val SETTING_ENABLED_LISTENERS = "enabled_notification_listeners"

    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "notification-guard").apply { isDaemon = true }
    }
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var observing = false

    @Volatile
    private var lastAttemptElapsedMs: Long? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isListenerWanted(context: Context): Boolean = prefs(context).getBoolean(KEY_LISTENER_WANTED, false)

    fun setListenerWanted(context: Context, wanted: Boolean) {
        prefs(context).edit(commit = true) { putBoolean(KEY_LISTENER_WANTED, wanted) }
    }

    fun isListenerEnabled(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context.applicationContext)
            .contains(context.packageName)
    } catch (_: Exception) {
        false
    }

    fun areNotificationsEnabled(context: Context): Boolean = try {
        NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()
    } catch (_: Exception) {
        true
    }

    /**
     * Grants Notification Access (`MediaPlaybackListener`) via Shizuku + `WRITE_SECURE_SETTINGS`
     * when Shizuku is running, and marks it protected by the passive observer. Returns true if enabled.
     */
    fun enableListenerViaShizuku(context: Context): Boolean {
        val ctx = context.applicationContext
        setListenerWanted(ctx, true)
        enforcePostNotifications(ctx)
        if (!Shizuku.pingBinder() || !DeviceOwnerRepository.checkShizukuPermission()) return false
        AccessibilityWatchdog.grantWriteSecureSettingsViaShizuku(ctx)
        val component = "${ctx.packageName}/.${MediaPlaybackListener::class.java.simpleName}"
        ShizukuRunner.exec("cmd", "notification", "allow_listener", component)
        ensureNotificationListenerEnabled(ctx)
        return isListenerEnabled(ctx)
    }

    fun disableListenerViaShizuku(context: Context): Boolean {
        val ctx = context.applicationContext
        setListenerWanted(ctx, false)
        if (!Shizuku.pingBinder() || !DeviceOwnerRepository.checkShizukuPermission()) return false
        val component = "${ctx.packageName}/.${MediaPlaybackListener::class.java.simpleName}"
        ShizukuRunner.exec("cmd", "notification", "disallow_listener", component)
        val resolver = ctx.contentResolver
        val serviceClass = MediaPlaybackListener::class.java.name
        val current = Settings.Secure.getString(resolver, SETTING_ENABLED_LISTENERS)
        if (AccessibilityWatchdog.ServiceList.isListed(current, ctx.packageName, serviceClass)) {
            val updated = AccessibilityWatchdog.ServiceList.without(current, ctx.packageName, serviceClass)
            runCatching {
                Settings.Secure.putString(resolver, SETTING_ENABLED_LISTENERS, updated.ifEmpty { "null" })
            }
        }
        return !isListenerEnabled(ctx)
    }

    /** Enforces both `POST_NOTIFICATIONS` (Device Owner) and Notification Access (if wanted). */
    fun enforceAll(context: Context) {
        enforcePostNotifications(context)
        ensureNotificationListenerEnabled(context)
    }

    fun enforcePostNotifications(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val ctx = context.applicationContext
        synchronized(lock) {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
            val owner = try {
                dpm.isDeviceOwnerApp(ctx.packageName)
            } catch (_: Exception) {
                false
            }
            if (!owner) return

            val grantState = try {
                dpm.getPermissionGrantState(admin, ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            }
            val enabled = try {
                NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            } catch (_: Exception) {
                true
            }
            val now = SystemClock.elapsedRealtime()
            if (!NotificationGuardRule.shouldRestore(
                    sdk = Build.VERSION.SDK_INT,
                    deviceOwner = true,
                    grantState = grantState,
                    notificationsEnabled = enabled,
                    lastAttemptElapsedMs = lastAttemptElapsedMs,
                    nowElapsedMs = now
                )
            ) return

            lastAttemptElapsedMs = now
            val granted = try {
                dpm.setPermissionGrantState(
                    admin,
                    ctx.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            } catch (e: Exception) {
                Timber.w(e, "Notification guard: could not restore POST_NOTIFICATIONS")
                false
            }
            if (granted) {
                Timber.i("Notification guard: POST_NOTIFICATIONS restored")
            } else {
                Timber.w("Notification guard: POST_NOTIFICATIONS grant refused")
            }
        }
    }

    fun ensureNotificationListenerEnabled(context: Context) {
        val ctx = context.applicationContext
        if (!isListenerWanted(ctx) || isListenerEnabled(ctx)) return

        // Android 11+ stores notification listeners in NotificationManagerService rather than
        // Settings.Secure.enabled_notification_listeners, so use Shizuku `cmd notification` first.
        if (Shizuku.pingBinder() && DeviceOwnerRepository.checkShizukuPermission()) {
            val component = "${ctx.packageName}/.${MediaPlaybackListener::class.java.simpleName}"
            ShizukuRunner.exec("cmd", "notification", "allow_listener", component)
        }

        if (AccessibilityWatchdog.hasPermission(ctx)) {
            val serviceClass = MediaPlaybackListener::class.java.name
            try {
                val resolver = ctx.contentResolver
                val current = Settings.Secure.getString(resolver, SETTING_ENABLED_LISTENERS)
                if (!AccessibilityWatchdog.ServiceList.isListed(current, ctx.packageName, serviceClass)) {
                    Settings.Secure.putString(
                        resolver,
                        SETTING_ENABLED_LISTENERS,
                        AccessibilityWatchdog.ServiceList.with(current, ctx.packageName, serviceClass)
                    )
                }
                NotificationListenerService.requestRebind(ComponentName(ctx, MediaPlaybackListener::class.java))
            } catch (e: SecurityException) {
                Timber.w(e, "Notification guard: could not restore notification listener")
            } catch (e: Exception) {
                Timber.w(e, "Notification guard: notification listener check failed")
            }
        }
    }

    fun restoreListenerAsync(context: Context) {
        val ctx = context.applicationContext
        worker.execute { ensureNotificationListenerEnabled(ctx) }
    }

    fun enforceAsync(context: Context) {
        val ctx = context.applicationContext
        worker.execute { enforcePostNotifications(ctx) }
    }

    @Synchronized
    fun observe(context: Context) {
        if (observing) return
        val ctx = context.applicationContext

        val blockReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (shouldHandleBlockBroadcast(intent)) {
                    enforceAsync(ctx)
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                ctx,
                blockReceiver,
                IntentFilter(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Timber.w(e, "Could not observe notification block state")
        }

        try {
            val listenerObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    worker.execute { ensureNotificationListenerEnabled(ctx) }
                }
            }
            ctx.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_ENABLED_LISTENERS),
                false,
                listenerObserver
            )
        } catch (e: Exception) {
            Timber.w(e, "Could not observe notification listener setting")
        }

        observing = true
    }

    internal fun shouldHandleBlockBroadcast(intent: Intent): Boolean {
        val hasExtra = intent.hasExtra(NotificationManager.EXTRA_BLOCKED_STATE)
        val blocked = intent.getBooleanExtra(NotificationManager.EXTRA_BLOCKED_STATE, false)
        return NotificationGuardRule.shouldReactToBlockBroadcast(hasExtra, blocked)
    }

    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(lock) {
            lastAttemptElapsedMs = null
        }
    }
}
