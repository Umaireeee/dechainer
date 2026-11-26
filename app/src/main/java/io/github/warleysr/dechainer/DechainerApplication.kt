package io.github.warleysr.dechainer

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.BrowserHardeningManager
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.DowntimeManager
import io.github.warleysr.dechainer.data.NotificationGuard
import io.github.warleysr.dechainer.data.PlayStoreUpdateManager
import io.github.warleysr.dechainer.data.SettingsLock
import io.github.warleysr.dechainer.security.TimelockManager
import io.github.warleysr.dechainer.vpn.LocalDnsShieldManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber

class DechainerApplication : Application() {

    companion object {
        private lateinit var instance: DechainerApplication

        fun getInstance() : DechainerApplication {
            return instance
        }
    }

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Uncaught coroutine failure in DechainerApplication")
        }
    )

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                return
            }
            AppRepository.invalidateCache()
            if (intent?.action == Intent.ACTION_PACKAGE_ADDED) {
                val pkg = intent.data?.encodedSchemeSpecificPart
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (pkg != null) {
                    PlayStoreUpdateManager.onPackageAdded(this@DechainerApplication, pkg, replacing)
                }
            } else if (intent?.action == Intent.ACTION_PACKAGE_CHANGED) {
                val pkg = intent.data?.encodedSchemeSpecificPart
                if (pkg != null) {
                    PlayStoreUpdateManager.onPackageChanged(this@DechainerApplication, pkg)
                }
            }
            if (intent?.action == Intent.ACTION_PACKAGE_ADDED ||
                intent?.action == Intent.ACTION_PACKAGE_REPLACED
            ) {
                applicationScope.launch {
                    BrowserHardeningManager.reapplyIfEnabled(this@DechainerApplication)
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    PlayStoreUpdateManager.onScreenStateChanged(this@DechainerApplication, screenOff = true)
                }
                Intent.ACTION_USER_PRESENT -> {
                    PlayStoreUpdateManager.onScreenStateChanged(this@DechainerApplication, screenOff = false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(KeyguardManager::class.java)
                    if (km == null || !km.isKeyguardLocked) {
                        PlayStoreUpdateManager.onScreenStateChanged(this@DechainerApplication, screenOff = false)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        instance = this

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (e: Throwable) {
                Timber.e(e, "Failed to add hidden api exemptions")
            }
        }

        registerReceiver(packageChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })

        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        applicationScope.launch {
            DeviceOwnerRepository.restoreStuckUserSetupComplete()
        }

        // Catches up after a force stop, which also cancels alarms.
        applicationScope.launch {
            if (SettingsLock.isLocked()) SettingsLock.setLocked(false)
            TimelockManager.syncAll(this@DechainerApplication)
            DowntimeManager.sync(this@DechainerApplication)
            AccessibilityWatchdog.cancelLegacyPeriodic(this@DechainerApplication)
            if (!AccessibilityWatchdog.hasPermission(this@DechainerApplication)) {
                AccessibilityWatchdog.grantWriteSecureSettingsViaShizuku(this@DechainerApplication)
            }
            if (AccessibilityWatchdog.isWanted(this@DechainerApplication)) {
                AccessibilityWatchdog.ensureEnabled(this@DechainerApplication)
            }
            NotificationGuard.enforceAll(this@DechainerApplication)
            DnsGuard.enforce(this@DechainerApplication)
            LocalDnsShieldManager.enforce(this@DechainerApplication)
            BrowserHardeningManager.reapplyIfEnabled(this@DechainerApplication)
            PlayStoreUpdateManager.sync(this@DechainerApplication)
        }

        // Zero-polling instant observers for accessibility, notifications, and Clean DNS failover.
        AccessibilityWatchdog.startGuard(this)
        NotificationGuard.observe(this)
        DnsGuard.observe(this)
    }
}
