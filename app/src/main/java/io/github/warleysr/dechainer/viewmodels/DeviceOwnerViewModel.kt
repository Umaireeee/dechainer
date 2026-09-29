package io.github.warleysr.dechainer.viewmodels

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.RestrictionEntry
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.BrowserRestrictionsManager
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.data.DnsGuard
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener

class DeviceOwnerViewModel : ViewModel() {

    private var shizukuPermission = mutableStateOf(
        Shizuku.pingBinder() && DeviceOwnerRepository.checkShizukuPermission()
    )
    private var isDeviceOwner = mutableStateOf(DeviceOwnerRepository.isDeviceOwner())

    private val requestResultPermissionListener =
        OnRequestPermissionResultListener { requestCode: Int, grantResult: Int ->
            shizukuPermission.value = grantResult == PackageManager.PERMISSION_GRANTED
        }

    fun addShizukuListener() {
        Shizuku.addRequestPermissionResultListener(requestResultPermissionListener)
    }

    fun removeShizukuListener() {
        Shizuku.removeRequestPermissionResultListener(requestResultPermissionListener)
    }

    fun isShizukuPermissionGranted() = shizukuPermission.value

    fun isDeviceOwner() = isDeviceOwner.value

    fun isShizukuInstalled() = DeviceOwnerRepository.isShizukuInstalled()

    fun installShizuku() = DeviceOwnerRepository.installShizuku()

    fun openShizukuSetupGuide() = DeviceOwnerRepository.openShizukuSetupGuide()

    /** Forced removal, after its 48h wait. Deliberately ignores "lock while active": it is the last-resort escape. */
    fun removeDeviceOwner() {
        processDeviceOwnerPrivileges(remove = true, ignoreScheduleLock = true)
    }

    /** Returns false if removal was refused because a schedule is locked right now. */
    fun processDeviceOwnerPrivileges(remove: Boolean = false, ignoreScheduleLock: Boolean = false): Boolean {
        if (remove) {
            val context = DechainerApplication.getInstance()
            if (!ignoreScheduleLock && ScheduleEnforcer.isAnyScheduleLocked(context)) return false
            // Lift schedule blocks while we still hold the privileges needed to do so.
            ScheduleEnforcer.releaseAll(context)
        }
        isDeviceOwner.value = DeviceOwnerRepository.processDeviceOwnerPrivileges(remove)
        return true
    }

    fun setPrivateDNS(host: String): Int {
        val result = DeviceOwnerRepository.setPrivateDNS(host)
        // Lock the browsers' own secure-DNS straight away, or a browser could keep resolving
        // around the new filter until something else happened to refresh its policies.
        if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
            val ctx = DechainerApplication.getInstance()
            // Remembered so DnsGuard can put it back if it's ever switched off in Settings.
            DnsGuard.remember(ctx, host)
            BrowserRestrictionsManager(ctx).applyRestrictions()
        }
        return result
    }

    fun getPrivateDNS(): String? = DeviceOwnerRepository.getPrivateDNS()

    /**
     * Turns DNS filtering off: the pin is dropped first (or the guard would put the filter straight
     * back), then Android goes to automatic DNS. The way out when a network blocks Private DNS.
     */
    fun turnOffDnsFilter(): Int {
        val ctx = DechainerApplication.getInstance()
        DnsGuard.forget(ctx)
        val result = DeviceOwnerRepository.setPrivateDnsAutomatic()
        BrowserRestrictionsManager(ctx).applyRestrictions()
        return result
    }

    fun getAllAccountsViaShizuku(): List<Pair<String, String>> = DeviceOwnerRepository.getAllAccountsViaShizuku()

    fun getAppNameFromAccountType(context: Context, accountType: String): String =
        DeviceOwnerRepository.getAppNameFromAccountType(context, accountType)

    fun getCurrentDeviceOwner(): Pair<String, String>? = DeviceOwnerRepository.getCurrentDeviceOwner()

    fun getExtraUsersInfo(): List<String> = DeviceOwnerRepository.getExtraUsersInfo()


    fun getApplicationRestrictions(packageName: String): Bundle =
        AppRepository.getApplicationRestrictions(packageName)

    fun setApplicationRestrictions(packageName: String, restrictions: Bundle) =
        AppRepository.setApplicationRestrictions(packageName, restrictions)

    fun getAvailableRestrictions(packageName: String): List<RestrictionEntry> =
        AppRepository.getAvailableRestrictions(packageName)
}
