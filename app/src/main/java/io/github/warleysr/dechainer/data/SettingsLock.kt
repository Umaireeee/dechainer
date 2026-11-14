package io.github.warleysr.dechainer.data

import timber.log.Timber

/**
 * Hides the phone's Settings app while it is on, so the toggles for notification access,
 * accessibility and the rest can't be reached from its screens. Uses device owner, so it does
 * nothing unless Dechainer is the device owner. Turning it off goes through the recovery gate.
 */
object SettingsLock {
    const val SETTINGS_PACKAGE = "com.android.settings"

    fun isLocked(): Boolean = try {
        DeviceAdmin.policyManager.isApplicationHidden(DeviceAdmin.component, SETTINGS_PACKAGE)
    } catch (e: Exception) {
        Timber.e(e, "Could not read Settings lock")
        false
    }

    /** Returns true when the change was applied. */
    fun setLocked(locked: Boolean): Boolean {
        if (!DeviceOwnerRepository.isDeviceOwner()) return false
        return try {
            DeviceAdmin.policyManager.setApplicationHidden(DeviceAdmin.component, SETTINGS_PACKAGE, locked)
        } catch (e: Exception) {
            Timber.e(e, "Could not change Settings lock")
            false
        }
    }
}
