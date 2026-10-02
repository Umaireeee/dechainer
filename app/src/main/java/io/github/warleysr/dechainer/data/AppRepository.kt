package io.github.warleysr.dechainer.data

import android.content.Context
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.models.AppItem

/** The installed apps and their two states Déchaîner controls: suspended, and protected from uninstall. */
object AppRepository {
    private val context = DechainerApplication.getInstance()
    private val packageManager = context.packageManager
    private val dpm get() = DeviceAdmin.policyManager
    private val adminName get() = DeviceAdmin.component

    @Volatile
    private var cachedApps: List<AppItem>? = null
    private val cacheLock = Any()

    fun getApps(forceRefresh: Boolean = false): List<AppItem> {
        if (!forceRefresh) cachedApps?.let { return it }
        synchronized(cacheLock) {
            if (!forceRefresh) cachedApps?.let { return it }
            val fresh = loadAppsFromSystem()
            cachedApps = fresh
            return fresh
        }
    }

    private fun loadAppsFromSystem(): List<AppItem> =
        packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES).asSequence()
            .filter { it.packageName != context.packageName }
            .map { appInfo ->
                val pkg = appInfo.packageName
                AppItem(
                    name = appInfo.loadLabel(packageManager).toString(),
                    packageName = pkg,
                    iconLoader = { appInfo.loadIcon(packageManager) },
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isUninstallBlocked = try { dpm.isUninstallBlocked(adminName, pkg) } catch (_: Exception) { false },
                    isSuspended = try { dpm.isPackageSuspended(adminName, pkg) } catch (_: Exception) { false }
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()

    fun invalidateCache() {
        synchronized(cacheLock) { cachedApps = null }
    }

    private fun updateCachedApp(packageName: String, transform: (AppItem) -> AppItem) {
        synchronized(cacheLock) {
            cachedApps = cachedApps?.map { if (it.packageName == packageName) transform(it) else it }
        }
    }

    /** Manual suspension. The schedule engine never releases an app it didn't suspend itself. */
    fun setAppSuspended(packageName: String, suspended: Boolean) {
        dpm.setPackagesSuspended(adminName, arrayOf(packageName), suspended)
        updateCachedApp(packageName) { it.copy(isSuspended = suspended) }
    }

    fun setUninstallBlocked(packageName: String, block: Boolean) {
        dpm.setUninstallBlocked(adminName, packageName, block)
        updateCachedApp(packageName) { it.copy(isUninstallBlocked = block) }
    }

    fun getApplicationRestrictions(packageName: String): Bundle =
        dpm.getApplicationRestrictions(adminName, packageName)

    fun setApplicationRestrictions(packageName: String, restrictions: Bundle) {
        val current = dpm.getApplicationRestrictions(adminName, packageName)
        current.putAll(restrictions)
        dpm.setApplicationRestrictions(adminName, packageName, current)
    }

    fun getAvailableRestrictions(packageName: String): List<RestrictionEntry> {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            if (appInfo.metaData == null) return emptyList()
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        return rm.getManifestRestrictions(packageName)?.toList() ?: emptyList()
    }
}
