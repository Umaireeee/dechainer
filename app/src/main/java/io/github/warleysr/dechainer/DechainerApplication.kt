package io.github.warleysr.dechainer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.BrowserRestrictionsManager
import io.github.warleysr.dechainer.data.Blocker
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import io.github.warleysr.dechainer.data.LegacyCleanup
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

    override fun onCreate() {
        super.onCreate()

        instance = this

        // The app is English-only now. Anyone who had picked Portuguese gets a clean reset, so
        // the stored choice can't linger.
        try {
            if (LocaleUtils.hasExplicitLocale(this)) LocaleUtils.clearLocale(this)
        } catch (e: Exception) {
            Timber.w(e, "Could not clear the old app language")
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        registerReceiver(packageChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })

        applicationScope.launch {
            AppRepository.getApps()
        }

        // Covers process restarts (app update, crash, being killed): re-applies any open schedule
        // window or impulse lock, and re-arms the boundary alarm.
        applicationScope.launch {
            // Older builds hid blocked apps; un-hide them first, and the sync below suspends
            // whatever should still be blocked. Then free anything held by removed features.
            Blocker.migrateFromHiding(this@DechainerApplication)
            LegacyCleanup.runOnce(this@DechainerApplication)
            ScheduleEnforcer.sync(this@DechainerApplication)
            DnsGuard.enforce(this@DechainerApplication)
            DnsGuard.observe(this@DechainerApplication)
            // Impulse lock starting or ending is acted on at once.
            ScheduleEnforcer.watchForChanges(this@DechainerApplication)
            // Repairs browser policies on phones that already had them: SafeSearch used to be
            // wiped by the first update after install, and the secure-DNS lock is new.
            BrowserRestrictionsManager(this@DechainerApplication).applyRestrictions()
        }
    }
}