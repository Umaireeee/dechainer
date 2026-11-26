package io.github.warleysr.dechainer.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Quickstep launchers (Pixel and other Launcher3 forks) show recents inside the home activity, so each
 * check below catches recents on some launchers; a launcher event none of them flags counts as home.
 */
class HomeScreenDetector(private val context: Context) {
    private var recentsLabelCache: Pair<String, String?>? = null

    @Volatile
    private var cachedHomePackages: Set<String>? = null

    fun invalidateCache() {
        cachedHomePackages = null
    }

    fun isHomeScreen(event: AccessibilityEvent, root: AccessibilityNodeInfo?): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        if (pkg !in homePackages()) return false

        val cls = event.className?.toString().orEmpty()
        if (RECENTS_CLASS_TOKENS.any { cls.contains(it, ignoreCase = true) }) return false

        val recentsLabel = recentsLabel(pkg)
        if (recentsLabel != null && event.text.any { it?.toString() == recentsLabel }) return false

        if (root != null && root.packageName == pkg) {
            for (suffix in RECENTS_VIEW_IDS) {
                val nodes = runCatching {
                    root.findAccessibilityNodeInfosByViewId("$pkg:id/$suffix")
                }.getOrNull().orEmpty()
                var anyVisible = false
                for (node in nodes) {
                    if (!anyVisible && node.isVisibleToUser) {
                        anyVisible = true
                    }
                    @Suppress("DEPRECATION")
                    runCatching { node.recycle() }
                }
                if (anyVisible) return false
            }
        }
        return true
    }

    private fun homePackages(): Set<String> {
        cachedHomePackages?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = context.packageManager
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
            ?.let { setOf(it) }
            ?: pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet() - SETTINGS_PACKAGE
        cachedHomePackages = resolved
        return resolved
    }

    @SuppressLint("DiscouragedApi")
    private fun recentsLabel(pkg: String): String? {
        val key = "$pkg|${Locale.getDefault()}"
        recentsLabelCache?.let { (cachedKey, label) -> if (cachedKey == key) return label }
        val label = runCatching {
            val resources = context.packageManager.getResourcesForApplication(pkg)
            val id = resources.getIdentifier("accessibility_recent_apps", "string", pkg)
            if (id != 0) resources.getString(id) else null
        }.getOrNull()
        recentsLabelCache = key to label
        return label
    }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        val RECENTS_CLASS_TOKENS = listOf("Recents", "Overview", "Quickstep")
        val RECENTS_VIEW_IDS = listOf("overview_panel", "recents_view", "task_view", "snapshot")
    }
}
