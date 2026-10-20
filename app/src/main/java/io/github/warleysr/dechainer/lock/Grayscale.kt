package io.github.warleysr.dechainer.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver
import timber.log.Timber

/**
 * Black-and-white screen while the dumb-phone lock is on, colour back in the open window.
 *
 * Uses Android's own colour-correction setting (monochromacy). A Device Owner may grant itself
 * WRITE_SECURE_SETTINGS, which is what lets the app switch it. If the phone refuses any step, nothing
 * else changes: the lock works the same, only in colour. Turned back off only when this app turned it
 * on, so a setting the owner chose before is never touched.
 */
object Grayscale {
    private const val PREFS = "grayscale"
    private const val KEY_WANTED = "wanted"
    private const val KEY_BY_US = "by_us"
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"
    private const val MONOCHROMACY = 0
    private const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    @Volatile private var watching = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Makes the screen match [wanted]: black and white while locked, as it was otherwise. */
    fun set(context: Context, wanted: Boolean) {
        val ctx = context.applicationContext
        prefs(ctx).edit(commit = true) { putBoolean(KEY_WANTED, wanted) }
        apply(ctx)
    }

    private fun apply(ctx: Context) {
        try {
            if (!grantPermission(ctx)) return
            val wanted = prefs(ctx).getBoolean(KEY_WANTED, false)
            val resolver = ctx.contentResolver
            val on = Settings.Secure.getInt(resolver, ENABLED, 0) == 1 &&
                Settings.Secure.getInt(resolver, MODE, -1) == MONOCHROMACY
            if (wanted && !on) {
                if (Settings.Secure.getInt(resolver, ENABLED, 0) == 0) {
                    prefs(ctx).edit(commit = true) { putBoolean(KEY_BY_US, true) }
                }
                Settings.Secure.putInt(resolver, MODE, MONOCHROMACY)
                Settings.Secure.putInt(resolver, ENABLED, 1)
            } else if (!wanted && prefs(ctx).getBoolean(KEY_BY_US, false)) {
                Settings.Secure.putInt(resolver, ENABLED, 0)
                prefs(ctx).edit(commit = true) { putBoolean(KEY_BY_US, false) }
            }
        } catch (e: Throwable) {
            Timber.w(e, "Grayscale not applied")
        }
    }

    private fun grantPermission(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return false
        val admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
        return try {
            dpm.setPermissionGrantState(admin, ctx.packageName, PERMISSION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        } catch (e: Throwable) {
            Timber.w(e, "WRITE_SECURE_SETTINGS not granted")
            false
        }
    }

    /** Puts the colour filter back at once if someone switches it off in Settings while the lock wants it. Call once at startup. */
    fun watch(context: Context) {
        if (watching) return
        val ctx = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (prefs(ctx).getBoolean(KEY_WANTED, false)) apply(ctx)
            }
        }
        try {
            for (key in listOf(ENABLED, MODE)) {
                ctx.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
            }
            watching = true
        } catch (e: Throwable) {
            Timber.w(e, "Could not watch the colour setting")
        }
    }
}
