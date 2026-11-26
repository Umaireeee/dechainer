package io.github.warleysr.dechainer.viewmodels

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import io.github.warleysr.dechainer.data.DeviceAdmin
import io.github.warleysr.dechainer.data.SystemRestrictions
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

    fun addedKeys(snapshot: Map<String, Boolean> = draftRestrictions): List<String> {
        val live = if (dpm.isDeviceOwnerApp(adminName.packageName)) dpm.getUserRestrictions(adminName) else null
        return (allKeys + snapshot.keys).distinct().filter { key ->
            val wasEnabled = appliedRestrictions[key] == true || (live?.getBoolean(key) == true)
            val shouldEnable = snapshot[key] == true
            !wasEnabled && shouldEnable
        }
    }

    fun removedKeys(snapshot: Map<String, Boolean> = draftRestrictions): List<String> {
        val live = if (dpm.isDeviceOwnerApp(adminName.packageName)) dpm.getUserRestrictions(adminName) else null
        return (allKeys + appliedRestrictions.keys + snapshot.keys + live?.keySet().orEmpty())
            .distinct()
            .filter { key ->
                val wasEnabled = appliedRestrictions[key] == true || (live?.getBoolean(key) == true)
                val shouldEnable = snapshot[key] == true
                wasEnabled && !shouldEnable
            }
    }

    fun addRestrictions(keys: List<String>) {
        if (!dpm.isDeviceOwnerApp(adminName.packageName)) return
        keys.forEach { key ->
            runCatching { dpm.addUserRestriction(adminName, key) }
                .onFailure { Timber.w(it, "Failed to add restriction $key") }
        }
        loadRestrictions()
    }

    fun revertKeysToApplied(keys: List<String>) {
        keys.forEach { key ->
            draftRestrictions[key] = appliedRestrictions[key] == true
        }
    }

    fun isAllDraftsEnabled(keys: List<String>): Boolean =
        keys.isNotEmpty() && keys.all { draftRestrictions[it] == true }

    fun hasPendingChanges(): Boolean {
        val allTrackedKeys = (allKeys + draftRestrictions.keys).distinct()
        return allTrackedKeys.any { (draftRestrictions[it] == true) != (appliedRestrictions[it] == true) }
    }
}
