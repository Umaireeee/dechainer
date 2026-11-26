package io.github.warleysr.dechainer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.data.ApkUpdateInstaller
import io.github.warleysr.dechainer.data.AppRepository
import timber.log.Timber

class ApkUpdateResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INSTALL_RESULT = "io.github.warleysr.dechainer.action.APK_UPDATE_INSTALL_RESULT"
        const val ACTION_UNINSTALL_RESULT = "io.github.warleysr.dechainer.action.APK_UPDATE_UNINSTALL_RESULT"
        const val ACTION_DOWNGRADE_UNINSTALL_RESULT =
            "io.github.warleysr.dechainer.action.APK_DOWNGRADE_UNINSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DOWNGRADE_UNINSTALL_RESULT -> onDowngradeUninstallResult(context, intent)
            ACTION_INSTALL_RESULT -> onInstallResult(context, intent)
            ACTION_UNINSTALL_RESULT -> {
                val pkg = intent.getStringExtra(ApkUpdateInstaller.EXTRA_PACKAGE)
                ApkUpdateInstaller.onRollbackUninstallFinished(context, pkg)
                AppRepository.invalidateCache()
            }
        }
    }

    private fun onDowngradeUninstallResult(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(ApkUpdateInstaller.EXTRA_DOWNGRADE_SESSION_ID, -1)
        val packageName = intent.getStringExtra(ApkUpdateInstaller.EXTRA_PACKAGE)
        if (packageName == null) {
            if (sessionId != -1) runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            ApkUpdateInstaller.restoreUnknownSourcesRestrictionsIfIdle(context, if (sessionId != -1) sessionId else null)
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        if (status == PackageInstaller.STATUS_SUCCESS && sessionId != -1) {
            if (ApkUpdateInstaller.commitDowngradeSession(context, sessionId, packageName)) {
                return
            }
        } else {
            ApkUpdateInstaller.abortDowngradeSession(context, sessionId, packageName)
        }
        Toast.makeText(context, R.string.apk_update_failed, Toast.LENGTH_LONG).show()
    }

    private fun onInstallResult(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(ApkUpdateInstaller.EXTRA_PACKAGE)
        val isDowngrade = intent.getBooleanExtra(ApkUpdateInstaller.EXTRA_IS_DOWNGRADE, false)
        if (status != PackageInstaller.STATUS_SUCCESS) {
            Timber.w("APK update result status=$status pkg=$packageName msg=$statusMessage")
        }

        // Nobody is going to confirm it, so the session would otherwise stay open forever.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && sessionId != -1) {
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        }
        if (status == PackageInstaller.STATUS_SUCCESS &&
            !isDowngrade &&
            packageName != null &&
            ApkUpdateInstaller.isFreshInstall(context, packageName)
        ) {
            // Keep DISALLOW_UNINSTALL_APPS lifted until the rollback uninstall finishes.
            ApkUpdateInstaller.uninstallForFreshRollback(context, packageName)
            AppRepository.invalidateCache()
            Toast.makeText(context, R.string.apk_update_new_app, Toast.LENGTH_LONG).show()
            return
        }

        ApkUpdateInstaller.onInstallFinished(context, sessionId, packageName)
        if (packageName == null) return

        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> R.string.apk_update_success
            PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.apk_update_signature_mismatch
            else -> R.string.apk_update_failed
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
