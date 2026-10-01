package io.github.warleysr.dechainer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.BrowserRestrictionsManager
import io.github.warleysr.dechainer.data.Blocker
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.data.LegacyCleanup
import io.github.warleysr.dechainer.guard.CrashHandler
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.store.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.warleysr.dechainer.utils.LocaleUtils
import timber.log.Timber

class DechainerApplication : Application() {

    companion object {
        private lateinit var instance: DechainerApplication

        fun getInstance() : DechainerApplication {
            return instance
        }

        /**
         * How long a process start waits before asking for its own sync. A receiver that started the
         * process runs a pass of its own within this time, and the request then drops out (see
         * [LockEngine.requestSync]): one sync per wake-up, not two.
         */
        private const val STARTUP_SYNC_DELAY_MS = 1_500L
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var reloadJob: Job? = null

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppRepository.invalidateCache()
            // A new launcher or keyboard changes which apps must never be blocked.
            ScheduleEnforcer.invalidateProtectedPackages()
            // A batch of installs or updates fires one broadcast per app. The app list itself is
            // rebuilt the next time the Apps tab asks for it (the cache was just dropped), not here:
            // updates arrive all day and nothing is looking at the list. Refresh the browser
            // policies once for the whole batch.
            reloadJob?.cancel()
            reloadJob = applicationScope.launch {
                delay(1000)
                // A newly installed browser gets the blocklist, SafeSearch and the secure-DNS lock
                // at once, instead of browsing around the DNS filter until something else re-applies them.
                try {
                    BrowserRestrictionsManager(this@DechainerApplication).applyRestrictions()
                } catch (e: Exception) {
                    Timber.w(e, "Browser policies not refreshed after an install")
                }
            }
        }
    }

    /** Unlocking the phone is a wake-up like any other: the lock is recomputed from stored data. */
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LockEngine.requestSync(this@DechainerApplication)
            io.github.warleysr.dechainer.day.DayEngine.showOnUnlock(this@DechainerApplication)
        }
    }

    override fun onCreate() {
        super.onCreate()

        instance = this

        // First of all: nothing below may crash without the crash-loop breaker watching (5.4, R2).
        CrashHandler.install(this)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        registerReceiver(packageChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        // Android only delivers this to a receiver registered while the process is alive.
        ContextCompat.registerReceiver(
            this, userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Cold start diet (5.4): the app list is no longer loaded here (the Apps tab loads it when
        // asked), and each one-off step runs once, behind a stored flag.
        val startedAt = SystemClock.elapsedRealtime()
        applicationScope.launch {
            runOneOffMigrations()
            // Instant reactions to the Private DNS setting, to security settings and to suspensions.
            DnsGuard.observe(this@DechainerApplication)
            ScheduleEnforcer.watchForChanges(this@DechainerApplication)
            // Covers process restarts (app update, crash, being killed): the engine recomputes the
            // whole lock from stored data and re-arms its wake-up. Skipped when a receiver already
            // ran a pass since this process started.
            delay(STARTUP_SYNC_DELAY_MS)
            LockEngine.requestSync(this@DechainerApplication, unlessStartedSince = startedAt)
        }
    }

    /**
     * Steps that only ever needed to run once on a phone updated from an older build. Each is
     * remembered in `app_state` only after it succeeds, so one that could not run (no Device Owner
     * yet, say) is tried again on a later start. If the store cannot be opened none of them is
     * skipped for good: they simply run again.
     */
    private fun runOneOffMigrations() {
        val state = try {
            Store.appState(this)
        } catch (e: Exception) {
            Timber.w(e, "Store unavailable; one-off steps will be tried again next start")
            return
        }
        // The app is English-only now. Anyone who had picked Portuguese gets a clean reset, so the
        // stored choice can't linger. (Only "en" is offered now, so nobody can pick one again.)
        state.runOnce("locale_reset_v1") {
            try {
                if (LocaleUtils.hasExplicitLocale(this)) LocaleUtils.clearLocale(this)
                true
            } catch (e: Exception) {
                Timber.w(e, "Could not clear the old app language")
                false
            }
        }
        // Older builds hid blocked apps; un-hide them. The engine's sync suspends whatever should
        // still be blocked.
        state.runOnce("release_hidden_apps_v1") { Blocker.migrateFromHiding(this) }
        // Frees anything held by removed features (it keeps its own flag).
        LegacyCleanup.runOnce(this)
        // Repairs browser policies on phones that already had them: SafeSearch used to be wiped by
        // the first update after install, and the secure-DNS lock is new. Every later change
        // (an install, a schedule, the DNS filter) refreshes them itself.
        state.runOnce("browser_policy_repair_v1") {
            try {
                BrowserRestrictionsManager(this).applyRestrictions()
                true
            } catch (e: Exception) {
                Timber.w(e, "Browser policies not repaired")
                false
            }
        }
    }
}
