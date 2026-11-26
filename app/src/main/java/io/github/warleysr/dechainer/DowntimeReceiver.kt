package io.github.warleysr.dechainer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import io.github.warleysr.dechainer.data.ApkUpdateInstaller
import io.github.warleysr.dechainer.data.BlockSuspensionManager
import io.github.warleysr.dechainer.data.BrowserHardeningManager
import io.github.warleysr.dechainer.data.CleanResetMigration
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.data.DowntimeManager
import io.github.warleysr.dechainer.data.MusicLimitTracker
import io.github.warleysr.dechainer.data.NotificationGuard
import io.github.warleysr.dechainer.data.PlayStoreUpdateManager
import io.github.warleysr.dechainer.security.TimelockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** Handles system boot, update, notification block, and clock-change broadcasts. */
class DowntimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        if (CleanResetMigration.isUpgradeInstall(app)) {
                            CleanResetMigration.runIfNeeded(app)
                        } else {
                            CleanResetMigration.markCompleted(app)
                        }
                        TimelockManager.syncAll(app)
                        DowntimeManager.sync(app)
                        DechainerAccessibilityService.releaseImpulseSuspensionIfExpired(app)
                        val rebinding = AccessibilityWatchdog.onSystemEvent(app)
                        NotificationGuard.enforceAll(app)
                        DnsGuard.enforce(app)
                        BrowserHardeningManager.reapplyIfEnabled(app)
                        PlayStoreUpdateManager.sync(app)
                        ApkUpdateInstaller.restoreUnknownSourcesRestrictionsIfIdle(app, abandonStale = true)
                        if (rebinding) {
                            // Keep receiver wake lock until AccessibilityWatchdog finishes its rebind pass.
                            delay(2_000L)
                        }
                    }

                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        TimelockManager.syncAll(app)
                        DowntimeManager.sync(app)
                        BlockSuspensionManager.sync(app)
                        MusicLimitTracker.onLimitsChanged(app)
                        DechainerAccessibilityService.releaseImpulseSuspensionIfExpired(app)
                        PlayStoreUpdateManager.sync(app)
                    }

                    NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED -> {
                        if (NotificationGuard.shouldHandleBlockBroadcast(intent)) {
                            NotificationGuard.enforceAll(app)
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "DowntimeReceiver failed for $action")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
