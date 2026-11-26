package io.github.warleysr.dechainer.security

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.data.AccessibilityWatchdog
import io.github.warleysr.dechainer.data.AppGroupRepository
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.AppTimeWindows
import io.github.warleysr.dechainer.data.BrowserHardeningManager
import io.github.warleysr.dechainer.data.DeviceAdmin
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.data.DowntimeRepository
import io.github.warleysr.dechainer.data.InstallRestrictionCoordinator
import io.github.warleysr.dechainer.data.NotificationGuard
import io.github.warleysr.dechainer.data.PlayStoreUpdateConfig
import io.github.warleysr.dechainer.data.PlayStoreUpdateManager
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import timber.log.Timber

/** Executes a [PendingAction] once its 7-day timelock countdown has completed. */
object PendingActionExecutor {
    fun execute(context: Context, action: PendingAction): Boolean {
        val ctx = context.applicationContext
        return when (action) {
            is PendingAction.UnhideApp -> {
                AppRepository.setAppHidden(action.packageName, false)
                true
            }
            is PendingAction.AllowUninstallApp -> {
                AppRepository.setUninstallBlocked(action.packageName, false)
                true
            }
            is PendingAction.UnsuspendApp -> {
                AppRepository.setAppSuspended(action.packageName, false)
                true
            }
            is PendingAction.SetAppTimeLimit -> {
                AppRepository.setAppReopenTime(action.packageName, action.reopenSeconds)
                AppRepository.setAppTimeLimit(action.packageName, TimelockManager.decodeTimeLimit(action.limitEncoded))
                true
            }
            is PendingAction.SetAppTimeWindows -> {
                AppRepository.setAppTimeWindows(
                    action.packageName,
                    AppTimeWindows.decode(action.windowsEncoded)
                )
                true
            }
            is PendingAction.SetAppGroup -> {
                AppGroupRepository.setPackageGroup(action.packageName, action.groupId)
                true
            }
            is PendingAction.BulkSetAppGroup -> {
                AppGroupRepository.setPackagesGroup(action.packageNames.toSet(), action.groupId)
                true
            }
            is PendingAction.SetAppRestrictions -> {
                AppRepository.setApplicationRestrictions(
                    action.packageName,
                    TimelockManager.decodeBundle(action.bundleJson)
                )
                true
            }
            is PendingAction.UpdateGroup -> {
                val existing = AppGroupRepository.getGroups().firstOrNull { it.id == action.groupId }
                    ?: return true
                if (action.name != existing.name) AppGroupRepository.renameGroup(action.groupId, action.name)
                AppGroupRepository.setGroupTimeLimit(action.groupId, TimelockManager.decodeTimeLimit(action.limitEncoded))
                AppGroupRepository.setGroupTimeWindows(action.groupId, AppTimeWindows.decode(action.windowsEncoded))
                AppGroupRepository.setGroupPackages(action.groupId, action.packageNames.toSet())
                true
            }
            is PendingAction.SetGroupTimeLimit -> {
                AppGroupRepository.setGroupTimeLimit(action.groupId, TimelockManager.decodeTimeLimit(action.limitEncoded))
                true
            }
            is PendingAction.SetGroupTimeWindows -> {
                AppGroupRepository.setGroupTimeWindows(action.groupId, AppTimeWindows.decode(action.windowsEncoded))
                true
            }
            is PendingAction.SetGroupMembers -> {
                AppGroupRepository.updateGroupDetails(action.groupId, action.name, action.packageNames.toSet())
                true
            }
            is PendingAction.DeleteGroup -> {
                AppGroupRepository.deleteGroup(action.groupId)
                true
            }
            is PendingAction.SaveDowntimeRules -> {
                DowntimeRepository.save(DowntimeRepository.decode(action.rulesJson), ctx)
                true
            }
            is PendingAction.UpdateDowntimeRule -> {
                val updatedRule = DowntimeRepository.decode("[${action.ruleJson}]").firstOrNull() ?: return false
                val current = DowntimeRepository.getRules(ctx)
                val next = if (current.any { it.id == updatedRule.id }) {
                    current.map { if (it.id == updatedRule.id) updatedRule else it }
                } else {
                    current + updatedRule
                }
                DowntimeRepository.save(next, ctx)
                true
            }
            is PendingAction.DeleteDowntimeRule -> {
                val updated = DowntimeRepository.getRules(ctx).filterNot { it.id == action.ruleId }
                DowntimeRepository.save(updated, ctx)
                true
            }
            is PendingAction.ClearSystemRestrictions -> {
                val dpm = DeviceAdmin.policyManager
                val admin = DeviceAdmin.component
                if (!dpm.isDeviceOwnerApp(ctx.packageName)) return false
                var allSucceeded = true
                action.keys.forEach { key ->
                    if (key == UserManager.DISALLOW_INSTALL_APPS) {
                        InstallRestrictionCoordinator.setUserBaseline(ctx, false)
                    } else {
                        val ok = runCatching { dpm.clearUserRestriction(admin, key) }
                            .onFailure { Timber.w(it, "Failed to clear restriction $key") }
                            .isSuccess
                        if (!ok) allSucceeded = false
                    }
                }
                allSucceeded
            }
            is PendingAction.DisableAccessibility -> {
                DechainerAccessibilityService.prepareServiceDisable()
                AccessibilityWatchdog.markIntentionalDisable(ctx)
                val disabledViaShizuku = Shizuku.pingBinder() &&
                    DeviceOwnerRepository.checkShizukuPermission() &&
                    runCatching { DeviceOwnerRepository.changeAccessibilityPermission(false) }.getOrDefault(false)
                if (!disabledViaShizuku) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { if (it !is ActivityNotFoundException) Timber.w(it) }
                }
                true
            }
            is PendingAction.DisableNotificationAccess -> {
                NotificationGuard.setListenerWanted(ctx, false)
                val disabledViaShizuku = NotificationGuard.disableListenerViaShizuku(ctx)
                if (!disabledViaShizuku) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { if (it !is ActivityNotFoundException) Timber.w(it) }
                }
                true
            }
            is PendingAction.ChangePrivateDns -> {
                val result = if (action.host == "auto") {
                    DeviceOwnerRepository.setAutoPrivateDNS()
                } else {
                    DeviceOwnerRepository.setPrivateDNS(action.host, action.autoFailover)
                }
                result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            }
            is PendingAction.UpdateBrowserHardening -> {
                BrowserHardeningManager.applyPatch(ctx, action.configJson)
            }
            is PendingAction.UpdatePlayStorePolicy -> {
                val config = PlayStoreUpdateConfig.fromJsonString(action.configJson) ?: return false
                PlayStoreUpdateManager.applyConfig(ctx, config)
                true
            }
            is PendingAction.RemoveBlockedActivity -> {
                val prefs = ctx.getSharedPreferences("activity_blocker_prefs", Context.MODE_PRIVATE)
                val current = prefs.getStringSet("blocked_activities", emptySet()).orEmpty()
                prefs.edit(commit = true) {
                    putStringSet("blocked_activities", current - action.className)
                }
                true
            }
            is PendingAction.UpdateImpulseLock -> {
                val parsedAction = runCatching { SecurityManager.ImpulseAction.valueOf(action.actionName) }
                    .getOrDefault(SecurityManager.ImpulseAction.TIMER_ONLY)
                SecurityManager.setImpulseAction(ctx, parsedAction)
                SecurityManager.setImpulseDurationMinutes(ctx, action.durationMinutes)
                SecurityManager.setImpulseSuspendedApps(ctx, action.suspendedApps.toSet())
                SecurityManager.setTetrisUnlockEnabled(ctx, action.tetrisUnlock)
                SecurityManager.setTetrisMinutes(ctx, action.tetrisMinutes)
                true
            }
            is PendingAction.UpdateImpulsePatch -> {
                val obj = runCatching { JSONObject(action.patchJson) }.getOrNull() ?: return false
                if (obj.has("actionName")) {
                    val parsed = runCatching {
                        SecurityManager.ImpulseAction.valueOf(obj.getString("actionName"))
                    }.getOrDefault(SecurityManager.ImpulseAction.TIMER_ONLY)
                    SecurityManager.setImpulseAction(ctx, parsed)
                }
                if (obj.has("durationMinutes")) {
                    SecurityManager.setImpulseDurationMinutes(ctx, obj.getInt("durationMinutes"))
                }
                if (obj.has("removeSuspendedApp")) {
                    val pkg = obj.getString("removeSuspendedApp")
                    SecurityManager.setImpulseSuspendedApps(ctx, SecurityManager.getImpulseSuspendedApps(ctx) - pkg)
                }
                if (obj.has("suspendedApps")) {
                    val arr = obj.optJSONArray("suspendedApps") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    SecurityManager.setImpulseSuspendedApps(ctx, list)
                }
                if (obj.has("tetrisUnlock")) {
                    SecurityManager.setTetrisUnlockEnabled(ctx, obj.getBoolean("tetrisUnlock"))
                }
                if (obj.has("tetrisMinutes")) {
                    SecurityManager.setTetrisMinutes(ctx, obj.getInt("tetrisMinutes"))
                }
                true
            }
            is PendingAction.RemoveDeviceOwner -> {
                DeviceOwnerRepository.processDeviceOwnerPrivileges(remove = true)
                true
            }
            is PendingAction.AllowApkDowngrade -> {
                TimelockManager.grantApkDowngradePass(ctx, action.packageName)
                true
            }
        }
    }
}
