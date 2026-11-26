package io.github.warleysr.dechainer.viewmodels

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.DechainerApplication
import androidx.core.content.edit

data class GroupedActivityLog(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val activities: List<DechainerAccessibilityService.Companion.ActivityLog>
)

class ActivityBlockerViewModel : ViewModel() {
    private val context = DechainerApplication.getInstance()
    private val packageManager = context.packageManager
    private val prefs = context.getSharedPreferences("activity_blocker_prefs", Context.MODE_PRIVATE)
    private val appMetaCache = HashMap<String, Pair<String, Drawable>>()

    val blockedActivities = mutableStateListOf<String>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "blocked_activities") {
            loadBlockedActivities()
        }
    }

    init {
        loadBlockedActivities()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun loadBlockedActivities() {
        val blocked = prefs.getStringSet("blocked_activities", emptySet()) ?: emptySet()
        blockedActivities.clear()
        blockedActivities.addAll(blocked.sorted())
    }

    fun addBlockedActivity(rule: String) {
        val clean = rule.trim()
        if (clean.isNotEmpty() && !blockedActivities.contains(clean)) {
            val updated = (prefs.getStringSet("blocked_activities", emptySet()).orEmpty() + clean)
            prefs.edit(commit = true) { putStringSet("blocked_activities", updated) }
            loadBlockedActivities()
        }
    }

    fun removeBlockedActivity(rule: String) {
        val updated = prefs.getStringSet("blocked_activities", emptySet()).orEmpty() - rule
        prefs.edit(commit = true) { putStringSet("blocked_activities", updated) }
        loadBlockedActivities()
    }

    fun getGroupedAccessedActivities(): List<GroupedActivityLog> {
        return DechainerAccessibilityService.accessedActivities
            .toList()
            .groupBy { it.packageName }
            .map { (packageName, logs) ->
                val (appName, icon) = appMetaCache.getOrPut(packageName) {
                    val appInfo = runCatching {
                        packageManager.getApplicationInfo(packageName, 0)
                    }.getOrNull()
                    val name = appInfo?.loadLabel(packageManager)?.toString() ?: packageName
                    val drawable = appInfo?.loadIcon(packageManager) ?: packageManager.defaultActivityIcon
                    name to drawable
                }
                GroupedActivityLog(
                    packageName = packageName,
                    appName = appName,
                    icon = icon,
                    activities = logs
                )
            }
            .sortedBy { it.appName.lowercase() }
    }
}
