package io.github.warleysr.dechainer.viewmodels

import android.content.Context
import android.content.RestrictionEntry
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.warleysr.dechainer.data.AppRepository
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import timber.log.Timber

class DeviceOwnerViewModel : ViewModel() {

    private var shizukuPermission = mutableStateOf(
        Shizuku.pingBinder() && DeviceOwnerRepository.checkShizukuPermission()
    )
    private var isDeviceOwner = mutableStateOf(DeviceOwnerRepository.isDeviceOwner())

    private val requestResultPermissionListener =
        OnRequestPermissionResultListener { requestCode: Int, grantResult: Int ->
            shizukuPermission.value = grantResult == PackageManager.PERMISSION_GRANTED
        }

    init {
        runCatching { Shizuku.addRequestPermissionResultListener(requestResultPermissionListener) }
    }

    override fun onCleared() {
        runCatching { Shizuku.removeRequestPermissionResultListener(requestResultPermissionListener) }
        super.onCleared()
    }

    fun isShizukuPermissionGranted() = shizukuPermission.value

    fun isDeviceOwner() = isDeviceOwner.value

    fun refreshStatus() {
        isDeviceOwner.value = DeviceOwnerRepository.isDeviceOwner()
    }

    fun isShizukuInstalled() = DeviceOwnerRepository.isShizukuInstalled()

    fun installShizuku() = DeviceOwnerRepository.installShizuku()

    fun openShizukuSetupGuide() = DeviceOwnerRepository.openShizukuSetupGuide()

    fun removeDeviceOwner(onComplete: ((String) -> Unit)? = null) =
        processDeviceOwnerPrivileges(remove = true, onComplete = onComplete)

    fun processDeviceOwnerPrivileges(
        remove: Boolean = false,
        onComplete: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val output = try {
                DeviceOwnerRepository.processDeviceOwnerPrivileges(remove)
            } catch (e: Exception) {
                Timber.e(e, "processDeviceOwnerPrivileges failed")
                e.message.orEmpty()
            }
            val granted = DeviceOwnerRepository.isDeviceOwner()
            withContext(Dispatchers.Main) {
                isDeviceOwner.value = granted
                onComplete?.invoke(output)
            }
        }
    }

    fun setPrivateDNS(host: String, autoFailover: Boolean = false): Int =
        DeviceOwnerRepository.setPrivateDNS(host, autoFailover)

    fun setAutoPrivateDNS(): Int = DeviceOwnerRepository.setAutoPrivateDNS()

    fun getPrivateDNS(): String? = DeviceOwnerRepository.getPrivateDNS()

    fun getAllAccountsViaShizuku(): List<Pair<String, String>> = DeviceOwnerRepository.getAllAccountsViaShizuku()

    fun getAppNameFromAccountType(context: Context, accountType: String): String =
        DeviceOwnerRepository.getAppNameFromAccountType(context, accountType)

    fun getCurrentDeviceOwner(): Pair<String, String>? = DeviceOwnerRepository.getCurrentDeviceOwner()

    fun getExtraUsersInfo(): List<String> = DeviceOwnerRepository.getExtraUsersInfo()

    fun changeAccessibilityPermission(grant: Boolean) = DeviceOwnerRepository.changeAccessibilityPermission(grant)

    fun getApplicationRestrictions(packageName: String): Bundle =
        AppRepository.getApplicationRestrictions(packageName)

    fun setApplicationRestrictions(packageName: String, restrictions: Bundle) =
        AppRepository.setApplicationRestrictions(packageName, restrictions)

    fun getAvailableRestrictions(packageName: String): List<RestrictionEntry> =
        AppRepository.getAvailableRestrictions(packageName)
}
