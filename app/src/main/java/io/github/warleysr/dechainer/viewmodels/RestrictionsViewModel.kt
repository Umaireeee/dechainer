package io.github.warleysr.dechainer.viewmodels

import android.os.UserManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.data.DeviceAdmin
import io.github.warleysr.dechainer.data.ScheduleEnforcer
import timber.log.Timber

class RestrictionsViewModel : ViewModel() {
    private val dpm = DeviceAdmin.policyManager
    private val adminName = DeviceAdmin.component

    // Current state in the UI (draft)
    val draftRestrictions = mutableStateMapOf<String, Boolean>()
    
    // Actual state applied in the system
    val appliedRestrictions = mutableStateMapOf<String, Boolean>()

    // The routes out of a blocker, closed. Safe mode starts the phone without Déchaîner, so
    // nothing stays suspended; debugging (ADB) can remove device owner outright. Debugging comes
    // last: with it blocked, forced removal is the only way out, so switch it on once everything
    // else is set up and working. OEM unlock isn't listed: its restriction isn't open to apps, and
    // blocking debugging already locks Developer options, where OEM unlock lives.
    val recommendedKeys = listOf(
        UserManager.DISALLOW_CONFIG_VPN,
        UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_DEBUGGING_FEATURES
    )

    private val restrictionFieldNames: Map<String, String> = UserManager::class.java.fields
        .filter { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    java.lang.reflect.Modifier.isFinal(field.modifiers) &&
                    (field.name.startsWith("DISALLOW_") || field.name.startsWith("ALLOW_"))
        }
        .associate { (it.get(null) as String) to it.name.lowercase() }

    val otherKeys = restrictionFieldNames.keys
        .filter { it !in recommendedKeys }
        .sorted()

    private val allKeys = (recommendedKeys + otherKeys).distinct()

    fun resourceNameFor(key: String): String? = restrictionFieldNames[key]

    init {
        loadRestrictions()
    }

    fun loadRestrictions() {
        if (!dpm.isDeviceOwnerApp(adminName.packageName)) return
        val currentRestrictions = dpm.getUserRestrictions(adminName)
        allKeys.forEach { key ->
            val isEnabled = currentRestrictions.getBoolean(key as String?)
            draftRestrictions[key] = isEnabled
            appliedRestrictions[key] = isEnabled
        }
    }

    fun toggleDraft(key: String, enabled: Boolean) {
        draftRestrictions[key] = enabled
    }

    fun toggleAllDrafts(keys: List<String>, enabled: Boolean) {
        keys.forEach { key ->
            draftRestrictions[key] = enabled
        }
    }

    fun applyChanges() {
        val current = dpm.getUserRestrictions(adminName)
        val switchedOnByHand = mutableSetOf<String>()
        // Only the restrictions you actually changed on this screen. Applying the whole list would
        // also clear one a schedule switched on while the screen was open.
        allKeys.filter { (draftRestrictions[it] ?: false) != (appliedRestrictions[it] ?: false) }.forEach { key ->
            val shouldEnable = draftRestrictions[key] ?: false
            // Per restriction: one this phone refuses — unsupported on its Android version, or
            // reserved for another kind of admin — used to throw and abandon every one after it.
            try {
                if (shouldEnable) {
                    dpm.addUserRestriction(adminName, key)
                    if (appliedRestrictions[key] != true) switchedOnByHand += key
                } else if (current.containsKey(key)) {
                    dpm.clearUserRestriction(adminName, key)
                }
            } catch (e: Exception) {
                Timber.w(e, "Restriction %s was refused", key)
            }
        }
        // Only the ones actually switched on in this apply, so a restriction a schedule is
        // currently holding isn't made permanent just because it showed as on in the list.
        ScheduleEnforcer.disownRestrictions(DechainerApplication.getInstance(), switchedOnByHand)
        // Anything a schedule, focus block or lock holds right now (the clock lock, say) is put
        // straight back: this screen can only change what is yours, never open a gap in one of those.
        ScheduleEnforcer.requestSyncAll(DechainerApplication.getInstance())
        loadRestrictions()
    }

    fun isAllDraftsEnabled(keys: List<String>): Boolean = keys.all { draftRestrictions[it] == true }

    fun hasPendingChanges(): Boolean = allKeys.any { draftRestrictions[it] != appliedRestrictions[it] }
}
