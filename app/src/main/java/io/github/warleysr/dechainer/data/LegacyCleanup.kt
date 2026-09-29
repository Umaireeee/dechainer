package io.github.warleysr.dechainer.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerDeviceAdminReceiver
import timber.log.Timber

/**
 * One-time cleanup after the simplification. Time windows, daily limits, reopening cooldowns and
 * visual blocking are gone — and with them the code that used to release the apps they had
 * suspended. Without this, an app caught by one of them at the moment of the update would stay
 * suspended forever. Anything a schedule still wants is suspended again by the sync that runs
 * straight after.
 */
object LegacyCleanup {
    private const val PREFS = "legacy_cleanup"
    private const val KEY_DONE = "done_v43"

    // Where the removed features kept the apps they held.
    private const val WINDOW_STATE = "window_state"          // key "owned_apps"
    private const val TIME_LIMITS = "time_limit_suspensions" // keys "daily_<pkg>", "reopen_<pkg>"
    private const val VISUAL = "visual_blocking_suspensions" // keys "until_<pkg>", "hits_<pkg>"

    fun runOnce(context: Context) {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        try {
            val held = mutableSetOf<String>()
            ctx.getSharedPreferences(WINDOW_STATE, Context.MODE_PRIVATE)
                .getStringSet("owned_apps", emptySet())?.let { held += it }
            ctx.getSharedPreferences(TIME_LIMITS, Context.MODE_PRIVATE).all.keys.forEach { key ->
                when {
                    key.startsWith("daily_") -> held += key.removePrefix("daily_")
                    key.startsWith("reopen_") -> held += key.removePrefix("reopen_")
                }
            }
            ctx.getSharedPreferences(VISUAL, Context.MODE_PRIVATE).all.keys.forEach { key ->
                if (key.startsWith("until_")) held += key.removePrefix("until_")
            }

            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                val admin = ComponentName(ctx, DechainerDeviceAdminReceiver::class.java)
                val installed = held.filter { Blocker.isInstalled(ctx, it) }
                if (installed.isNotEmpty()) Blocker.release(ctx, dpm, admin, installed)
                Timber.d("Legacy cleanup: released ${installed.size} app(s)")
            }
            for (name in listOf(WINDOW_STATE, TIME_LIMITS, VISUAL)) {
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
            }
            prefs.edit(commit = true) { putBoolean(KEY_DONE, true) }
        } catch (e: Exception) {
            // Not marked done: it tries again on the next start.
            Timber.w(e, "Legacy cleanup failed")
        }
    }
}
