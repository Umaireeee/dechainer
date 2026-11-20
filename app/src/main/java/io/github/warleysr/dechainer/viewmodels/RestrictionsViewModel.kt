package io.github.warleysr.dechainer.viewmodels

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import io.github.warleysr.dechainer.data.DeviceAdmin
import io.github.warleysr.dechainer.data.SystemRestrictions
import io.github.warleysr.dechainer.security.SecurityManager
import timber.log.Timber

class RestrictionsViewModel : ViewModel() {
    private val dpm = DeviceAdmin.policyManager
    private val adminName = DeviceAdmin.component

    // Current state in the UI (draft)
    val draftRestrictions = mutableStateMapOf<String, Boolean>()
    
    // Actual state applied in the system
    val appliedRestrictions = mutableStateMapOf<String, Boolean>()

    val recommendedKeys get() = SystemRestrictions.recommendedKeys
    val otherKeys get() = SystemRestrictions.otherKeys
    private val allKeys get() = SystemRestrictions.allKeys

    fun resourceNameFor(key: String): String? = SystemRestrictions.resourceNameFor(key)

    init {
        loadRestrictions()
    }

    fun loadRestrictions() {
        if (!dpm.isDeviceOwnerApp(adminName.packageName)) return
        val currentRestrictions = dpm.getUserRestrictions(adminName)
        allKeys.forEach { key ->
            val isEnabled = currentRestrictions.getBoolean(key)
            draftRestrictions[key] = isEnabled
            appliedRestrictions[key] = isEnabled
        }
        currentRestrictions.keySet().forEach { key ->
            val isEnabled = currentRestrictions.getBoolean(key)
            if (!draftRestrictions.containsKey(key)) {
                draftRestrictions[key] = isEnabled
                appliedRestrictions[key] = isEnabled
            }
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

    fun snapshotDrafts(): Map<String, Boolean> = draftRestrictions.toMap()

    fun applyChanges(
        snapshot: Map<String, Boolean> = draftRestrictions,
        allowLoosening: Boolean = true
    ) {
        if (!dpm.isDeviceOwnerApp(adminName.packageName)) return
        val currentlyAppliedRestrictions = dpm.getUserRestrictions(adminName)
        val allTrackedKeys = (allKeys + snapshot.keys + currentlyAppliedRestrictions.keySet()).distinct()
        allTrackedKeys.forEach { key ->
            val wasEnabled = currentlyAppliedRestrictions.getBoolean(key)
            val shouldEnable = snapshot[key] == true
            if (shouldEnable == wasEnabled) return@forEach

            if (shouldEnable) {
                runCatching { dpm.addUserRestriction(adminName, key) }
                    .onFailure { Timber.w(it, "Failed to add restriction $key") }
            } else if (allowLoosening || SecurityManager.isSessionActive()) {
                runCatching { dpm.clearUserRestriction(adminName, key) }
                    .onFailure { Timber.w(it, "Failed to clear restriction $key") }
            }
        }
        loadRestrictions()
    }

    fun isAllDraftsEnabled(keys: List<String>): Boolean =
        keys.isNotEmpty() && keys.all { draftRestrictions[it] == true }

    fun hasPendingChanges(): Boolean {
        val allTrackedKeys = (allKeys + draftRestrictions.keys).distinct()
        return allTrackedKeys.any { (draftRestrictions[it] == true) != (appliedRestrictions[it] == true) }
    }

    fun isLoosening(snapshot: Map<String, Boolean> = draftRestrictions): Boolean {
        val liveRestrictions = if (dpm.isDeviceOwnerApp(adminName.packageName)) {
            dpm.getUserRestrictions(adminName)
        } else {
            null
        }
        val allTrackedKeys = (
            allKeys + appliedRestrictions.keys + snapshot.keys + liveRestrictions?.keySet().orEmpty()
        ).distinct()
        return allTrackedKeys.any { key ->
            val wasEnabled = appliedRestrictions[key] == true || (liveRestrictions?.getBoolean(key) == true)
            val shouldEnable = snapshot[key] == true
            wasEnabled && !shouldEnable
        }
    }
}
