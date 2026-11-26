package io.github.warleysr.dechainer.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.UserManager
import androidx.core.content.edit
import io.github.warleysr.dechainer.notifications.ApkUpdateResultReceiver
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Lets a `.apk` or split-APK bundle (`.apks`, `.xapk`, `.apkm`) update an app that is already
 * installed, while new apps stay blocked. Install restrictions (`DISALLOW_INSTALL_APPS`,
 * `DISALLOW_INSTALL_UNKNOWN_SOURCES*`) are lifted only for the duration of the install and put
 * back once its result arrives.
 */
object ApkUpdateInstaller {
    const val EXTRA_PACKAGE = "io.github.warleysr.dechainer.extra.APK_UPDATE_PACKAGE"
    const val EXTRA_IS_DOWNGRADE = "io.github.warleysr.dechainer.extra.APK_UPDATE_IS_DOWNGRADE"
    const val EXTRA_DOWNGRADE_SESSION_ID = "io.github.warleysr.dechainer.extra.APK_DOWNGRADE_SESSION_ID"

    private const val PREFS_NAME = "security_prefs"
    private const val KEY_RESTORE_RESTRICTIONS = "apk_update_restore_restrictions"
    private const val KEY_RESTORE_UNINSTALL_BLOCKED = "apk_update_restore_uninstall_blocked"
    private const val KEY_RESTORE_SUSPENDED = "apk_update_restore_suspended"
    private const val KEY_RESTORE_HIDDEN = "apk_update_restore_hidden"
    private const val KEY_ACTIVE_SESSIONS = "apk_update_active_sessions"
    private const val SESSION_STALE_MS = 10 * 60 * 1000L

    private const val INSTALL_REQUEST_DOWNGRADE = 0x00000080
    private const val INSTALL_ALLOW_DOWNGRADE = 0x00100000
    // @SystemApi PackageManager.MATCH_FACTORY_ONLY (not exposed in the public SDK stubs).
    private const val MATCH_FACTORY_ONLY = 0x00800000

    internal val installRestrictions = listOf(
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_INSTALL_APPS
    )

    private val downgradeRestrictions = installRestrictions + UserManager.DISALLOW_UNINSTALL_APPS

    sealed interface Inspection {
        data class Update(
            val file: File,
            val stagingDir: File,
            val apkFiles: List<File>,
            val isSplitBundle: Boolean,
            val packageName: String,
            val label: String,
            val icon: Drawable?,
            val installedVersion: String,
            val newVersion: String,
            val isDowngrade: Boolean = false
        ) : Inspection {
            fun cleanup() {
                runCatching { stagingDir.deleteRecursively() }
            }
        }

        data object NewApp : Inspection
        data object SignatureMismatch : Inspection
        data object Invalid : Inspection
    }

    /**
     * Whether installing apps/APKs is restricted or Play Store is suspended, which is when the
     * lock-screen Update hub is needed.
     */
    fun isInstallRestricted(context: Context): Boolean {
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (!runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)) return false

        if (PlayStoreUpdateManager.isSilentWindowOpen(context)) return true

        val pending = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_RESTORE_RESTRICTIONS, null)
        if (!pending.isNullOrEmpty()) return true

        val active = runCatching { dpm.getUserRestrictions(admin) }.getOrNull() ?: return false
        if (installRestrictions.any { active.getBoolean(it) }) return true

        return runCatching {
            dpm.isPackageSuspended(admin, PlayStoreUpdateManager.PLAY_STORE_PACKAGE)
        }.getOrDefault(false)
    }

    fun isUnknownSourcesRestricted(context: Context): Boolean = isInstallRestricted(context)

    fun hasActiveSessions(context: Context): Boolean =
        runCatching { context.packageManager.packageInstaller.mySessions.isNotEmpty() }.getOrDefault(false)

    fun enqueueRestoreRestriction(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(KEY_RESTORE_RESTRICTIONS, null).orEmpty()
        prefs.edit(commit = true) { putStringSet(KEY_RESTORE_RESTRICTIONS, pending + key) }
    }

    internal fun hasMatchingSignatures(installed: PackageInfo, archive: PackageInfo): Boolean {
        val installedInfo = installed.signingInfo ?: return false
        val archiveInfo = archive.signingInfo ?: return false
        if (installedInfo.hasMultipleSigners() != archiveInfo.hasMultipleSigners()) return false

        val installedSigners = installedInfo.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        val archiveSigners = archiveInfo.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        if (installedSigners.isEmpty() || archiveSigners.isEmpty()) return false

        if (installedInfo.hasMultipleSigners()) {
            return installedSigners.size == archiveSigners.size &&
                archiveSigners.all { a -> installedSigners.any { i -> i.contentEquals(a) } }
        }

        val installedLeaf = installedSigners.first()
        val archiveLeaf = archiveSigners.first()
        if (installedLeaf.contentEquals(archiveLeaf)) return true

        val isUpgrade = archive.longVersionCode >= installed.longVersionCode
        return if (isUpgrade) {
            val archiveHistory = archiveInfo.signingCertificateHistory?.map { it.toByteArray() }.orEmpty()
            archiveHistory.any { it.contentEquals(installedLeaf) }
        } else {
            val installedHistory = installedInfo.signingCertificateHistory?.map { it.toByteArray() }.orEmpty()
            installedHistory.any { it.contentEquals(archiveLeaf) }
        }
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(pm: PackageManager, path: String): PackageInfo? =
        pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)

    fun inspect(context: Context, uri: Uri): Inspection {
        val rootDir = File(context.cacheDir, "apk_update").apply { mkdirs() }
        val staleBefore = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        rootDir.listFiles()?.forEach { existing ->
            if (existing.lastModified() < staleBefore) existing.deleteRecursively()
        }

        val stagingDir = File(rootDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val pickedFile = File(stagingDir, "picked.bin")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                pickedFile.outputStream().use { input.copyTo(it) }
            }
        }.isSuccess
        if (!copied) return Inspection.Invalid.also { stagingDir.deleteRecursively() }

        val extracted = ApkArchiveExtractor.extract(pickedFile, stagingDir)
            ?: return Inspection.Invalid.also { stagingDir.deleteRecursively() }
        if (extracted.isSplitBundle) {
            pickedFile.delete()
        }

        val pm = context.packageManager
        val parsedPairs = extracted.apkFiles.map { apkFile ->
            val info = archivePackageInfo(pm, apkFile.path)
                ?: return Inspection.Invalid.also { stagingDir.deleteRecursively() }
            apkFile to info
        }
        val baseIndex = parsedPairs.indexOfFirst { (file, info) ->
            info.splitNames.isNullOrEmpty() || file.name.equals("base.apk", ignoreCase = true)
        }.let { if (it >= 0) it else 0 }

        val orderedPairs = buildList {
            add(parsedPairs[baseIndex])
            parsedPairs.forEachIndexed { idx, pair -> if (idx != baseIndex) add(pair) }
        }
        val (baseFile, baseArchive) = orderedPairs.first()
        val pkgName = baseArchive.packageName
        if (orderedPairs.any { it.second.packageName != pkgName }) {
            return Inspection.Invalid.also { stagingDir.deleteRecursively() }
        }

        val installed = installedInfo(context, pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return Inspection.NewApp.also { stagingDir.deleteRecursively() }

        if (orderedPairs.any { !hasMatchingSignatures(installed, it.second) }) {
            return Inspection.SignatureMismatch.also { stagingDir.deleteRecursively() }
        }

        val isDowngrade = baseArchive.longVersionCode < installed.longVersionCode ||
            (baseArchive.longVersionCode == installed.longVersionCode &&
                baseArchive.baseRevisionCode < installed.baseRevisionCode)

        val appInfo = installed.applicationInfo
        return Inspection.Update(
            file = baseFile,
            stagingDir = stagingDir,
            apkFiles = orderedPairs.map { it.first },
            isSplitBundle = extracted.isSplitBundle,
            packageName = pkgName,
            label = appInfo?.loadLabel(pm)?.toString() ?: pkgName,
            icon = appInfo?.loadIcon(pm),
            installedVersion = installed.versionName ?: installed.longVersionCode.toString(),
            newVersion = baseArchive.versionName ?: baseArchive.longVersionCode.toString(),
            isDowngrade = isDowngrade
        )
    }

    fun install(context: Context, update: Inspection.Update): Boolean {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        var committed = false
        return try {
            val installed = installedInfo(context, update.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return false
            for (apkFile in update.apkFiles) {
                val archive = archivePackageInfo(context.packageManager, apkFile.path) ?: return false
                if (archive.packageName != update.packageName || !hasMatchingSignatures(installed, archive)) {
                    return false
                }
            }

            val uninstallFirst = update.isDowngrade &&
                needsUninstallForDowngrade(context, update.file, update.packageName)

            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply {
                    setAppPackageName(update.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                    if (update.isDowngrade) requestDowngrade(this)
                }

            // Lift DISALLOW_INSTALL_APPS & unknown sources BEFORE createSession(), since AOSP
            // PackageInstallerService checks DISALLOW_INSTALL_APPS inside createSession().
            liftInstallRestrictions(context, includeUninstall = uninstallFirst)

            sessionId = installer.createSession(params)
            recordActiveSession(context, sessionId)

            installer.openSession(sessionId).use { session ->
                update.apkFiles.forEachIndexed { index, apkFile ->
                    val splitName = if (index == 0) "base.apk" else "split_${index}.apk"
                    apkFile.inputStream().use { input ->
                        session.openWrite(splitName, 0, apkFile.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }
                if (installedInfo(context, update.packageName) == null) {
                    session.abandon()
                    return false
                }
                if (uninstallFirst) {
                    savePreDowngradeState(context, update.packageName)
                    installer.uninstall(
                        update.packageName,
                        downgradeUninstallSender(context, sessionId, update.packageName)
                    )
                    committed = true
                } else {
                    session.commit(
                        resultSender(
                            context,
                            sessionId,
                            update.packageName,
                            isDowngrade = update.isDowngrade
                        )
                    )
                    committed = true
                }
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "APK update install failed for ${update.packageName}")
            false
        } finally {
            if (!committed) {
                if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
                restorePreDowngradeState(context, update.packageName)
                restoreUnknownSourcesRestrictionsIfIdle(context, if (sessionId != -1) sessionId else null)
            }
            update.cleanup()
        }
    }

    /** Commits a staged downgrade session once the older version's predecessor has been uninstalled. */
    fun commitDowngradeSession(context: Context, sessionId: Int, packageName: String): Boolean {
        val installer = context.packageManager.packageInstaller
        return try {
            installer.openSession(sessionId).use { session ->
                session.commit(resultSender(context, sessionId, packageName, isDowngrade = true))
            }
            true
        } catch (_: Exception) {
            abortDowngradeSession(context, sessionId, packageName)
            false
        }
    }

    /** Cleans up a staged downgrade session when the preliminary uninstall fails. */
    fun abortDowngradeSession(context: Context, sessionId: Int, packageName: String) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        onInstallFinished(context, sessionId, packageName)
    }

    /** Restores per-package policies and user restrictions once an install session finishes. */
    fun onInstallFinished(context: Context, sessionId: Int, packageName: String?) {
        if (packageName != null) {
            restorePreDowngradeState(context, packageName)
        }
        AppRepository.invalidateCache()
        restoreUnknownSourcesRestrictionsIfIdle(context, sessionId)
    }

    internal fun requestDowngrade(params: PackageInstaller.SessionParams) {
        runCatching {
            val field = PackageInstaller.SessionParams::class.java.getField("installFlags")
            field.setInt(
                params,
                field.getInt(params) or INSTALL_REQUEST_DOWNGRADE or INSTALL_ALLOW_DOWNGRADE
            )
        }
        runCatching {
            PackageInstaller.SessionParams::class.java
                .getMethod("setRequestDowngrade", Boolean::class.javaPrimitiveType)
                .invoke(params, true)
        }
    }

    private val isBuildDebuggable: Boolean
        get() = runCatching {
            Build::class.java.getField("IS_DEBUGGABLE").getBoolean(null)
        }.getOrDefault(Build.TYPE == "userdebug" || Build.TYPE == "eng")

    private fun needsUninstallForDowngrade(
        context: Context,
        archiveFile: File,
        packageName: String
    ): Boolean {
        if (packageName == context.packageName) return false
        val installed = installedInfo(context, packageName) ?: return false
        val flags = installed.applicationInfo?.flags ?: 0
        val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem) {
            if (!isUpdatedSystem) return false
            val archiveVersion = context.packageManager
                .getPackageArchiveInfo(archiveFile.path, 0)?.longVersionCode ?: return false
            val systemVersion = runCatching {
                context.packageManager
                    .getPackageInfo(packageName, MATCH_FACTORY_ONLY)
                    .longVersionCode
            }.getOrNull()
            if (systemVersion != null && archiveVersion < systemVersion) return false
        }
        val isAppDebuggable = (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return !isBuildDebuggable && !isAppDebuggable
    }

    private fun savePreDowngradeState(context: Context, packageName: String) {
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val wasUninstallBlocked = runCatching { dpm.isUninstallBlocked(admin, packageName) }.getOrDefault(false)
        val wasSuspended = runCatching { dpm.isPackageSuspended(admin, packageName) }.getOrDefault(false)
        val wasHidden = runCatching { dpm.isApplicationHidden(admin, packageName) }.getOrDefault(false)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            if (wasUninstallBlocked) {
                val set = prefs.getStringSet(KEY_RESTORE_UNINSTALL_BLOCKED, null).orEmpty()
                putStringSet(KEY_RESTORE_UNINSTALL_BLOCKED, set + packageName)
            }
            if (wasSuspended) {
                val set = prefs.getStringSet(KEY_RESTORE_SUSPENDED, null).orEmpty()
                putStringSet(KEY_RESTORE_SUSPENDED, set + packageName)
            }
            if (wasHidden) {
                val set = prefs.getStringSet(KEY_RESTORE_HIDDEN, null).orEmpty()
                putStringSet(KEY_RESTORE_HIDDEN, set + packageName)
            }
        }

        if (wasUninstallBlocked) {
            runCatching { dpm.setUninstallBlocked(admin, packageName, false) }
        }
    }

    private fun restorePreDowngradeState(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val unblockSet = prefs.getStringSet(KEY_RESTORE_UNINSTALL_BLOCKED, null).orEmpty()
        val suspendedSet = prefs.getStringSet(KEY_RESTORE_SUSPENDED, null).orEmpty()
        val hiddenSet = prefs.getStringSet(KEY_RESTORE_HIDDEN, null).orEmpty()

        val restoreUninstallBlocked = packageName in unblockSet
        val restoreSuspended = packageName in suspendedSet
        val restoreHidden = packageName in hiddenSet
        if (!restoreUninstallBlocked && !restoreSuspended && !restoreHidden) return

        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (!dpm.isDeviceOwnerApp(context.packageName) || installedInfo(context, packageName) == null) return

        if (restoreUninstallBlocked) {
            runCatching { dpm.setUninstallBlocked(admin, packageName, true) }
        }
        if (restoreSuspended) {
            runCatching { dpm.setPackagesSuspended(admin, arrayOf(packageName), true) }
        }
        if (restoreHidden) {
            runCatching { dpm.setApplicationHidden(admin, packageName, true) }
        }

        prefs.edit {
            val nextUnblock = unblockSet - packageName
            if (nextUnblock.isEmpty()) remove(KEY_RESTORE_UNINSTALL_BLOCKED)
            else putStringSet(KEY_RESTORE_UNINSTALL_BLOCKED, nextUnblock)

            val nextSuspended = suspendedSet - packageName
            if (nextSuspended.isEmpty()) remove(KEY_RESTORE_SUSPENDED)
            else putStringSet(KEY_RESTORE_SUSPENDED, nextSuspended)

            val nextHidden = hiddenSet - packageName
            if (nextHidden.isEmpty()) remove(KEY_RESTORE_HIDDEN)
            else putStringSet(KEY_RESTORE_HIDDEN, nextHidden)
        }
    }

    private fun restoreUnknownSourcesRestrictions(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keys = prefs.getStringSet(KEY_RESTORE_RESTRICTIONS, null) ?: return

        val passActive = PlayStoreUpdateManager.isSilentWindowOpen(context)
        val toRestore = if (passActive) keys - UserManager.DISALLOW_INSTALL_APPS else keys
        val keepDeferred = if (passActive && UserManager.DISALLOW_INSTALL_APPS in keys) {
            setOf(UserManager.DISALLOW_INSTALL_APPS)
        } else {
            emptySet()
        }

        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            toRestore.forEach { runCatching { dpm.addUserRestriction(admin, it) } }
        }
        prefs.edit {
            if (keepDeferred.isEmpty()) remove(KEY_RESTORE_RESTRICTIONS)
            else putStringSet(KEY_RESTORE_RESTRICTIONS, keepDeferred)
        }
    }

    private fun recordActiveSession(context: Context, sessionId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val existing = prefs.getStringSet(KEY_ACTIVE_SESSIONS, null).orEmpty()
            .filterNot { it.substringBefore(':').toIntOrNull() == sessionId }
        prefs.edit(commit = true) {
            putStringSet(KEY_ACTIVE_SESSIONS, existing.toSet() + "$sessionId:$now")
        }
    }

    fun restoreUnknownSourcesRestrictionsIfIdle(
        context: Context,
        finishedSessionId: Int? = null,
        abandonStale: Boolean = false
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val tracked = prefs.getStringSet(KEY_ACTIVE_SESSIONS, null).orEmpty()
            .mapNotNull { entry ->
                val id = entry.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                val ts = entry.substringAfter(':', "").toLongOrNull() ?: 0L
                id to ts
            }
            .toMap()

        val installer = context.packageManager.packageInstaller
        val sessions = installer.mySessions
        val abandonedIds = mutableSetOf<Int>()
        for (info in sessions) {
            val sid = info.sessionId
            if (sid == finishedSessionId) {
                abandonedIds += sid
                continue
            }
            val startedAt = tracked[sid]
            val isStale = (startedAt != null && now - startedAt > SESSION_STALE_MS) ||
                (abandonStale && startedAt == null)
            if (isStale) {
                runCatching { installer.abandonSession(sid) }
                abandonedIds += sid
            }
        }

        val liveRemaining = sessions.map { it.sessionId }.toSet() - abandonedIds
        val updatedTracked = tracked.filterKeys { it in liveRemaining }
            .map { (id, ts) -> "$id:$ts" }
            .toSet()
        prefs.edit {
            if (updatedTracked.isEmpty()) remove(KEY_ACTIVE_SESSIONS)
            else putStringSet(KEY_ACTIVE_SESSIONS, updatedTracked)
        }

        if (liveRemaining.isEmpty()) {
            restoreUnknownSourcesRestrictions(context)
        }
    }

    fun isFreshInstall(context: Context, packageName: String): Boolean {
        val info = installedInfo(context, packageName) ?: return false
        return info.firstInstallTime == info.lastUpdateTime
    }

    fun uninstallForFreshRollback(context: Context, packageName: String) {
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            val active = runCatching { dpm.getUserRestrictions(admin) }.getOrNull()
            if (active?.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS) == true) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val pending = prefs.getStringSet(KEY_RESTORE_RESTRICTIONS, null).orEmpty()
                prefs.edit(commit = true) {
                    putStringSet(KEY_RESTORE_RESTRICTIONS, pending + UserManager.DISALLOW_UNINSTALL_APPS)
                }
                runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS) }
            }
        }
        uninstall(context, packageName)
    }

    fun uninstall(context: Context, packageName: String) {
        val intent = Intent(context, ApkUpdateResultReceiver::class.java)
            .setAction(ApkUpdateResultReceiver.ACTION_UNINSTALL_RESULT)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        context.packageManager.packageInstaller.uninstall(packageName, pendingIntent.intentSender)
    }

    private fun liftInstallRestrictions(context: Context, includeUninstall: Boolean) {
        val dpm = DeviceAdmin.policyManager
        val admin = DeviceAdmin.component
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val active = runCatching { dpm.getUserRestrictions(admin) }.getOrNull() ?: return
        val candidates = if (includeUninstall) downgradeRestrictions else installRestrictions
        val lifted = candidates.filter { active.getBoolean(it) }
        if (lifted.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(KEY_RESTORE_RESTRICTIONS, null).orEmpty()
        prefs.edit(commit = true) { putStringSet(KEY_RESTORE_RESTRICTIONS, pending + lifted) }
        lifted.forEach { runCatching { dpm.clearUserRestriction(admin, it) } }
    }

    private fun installedInfo(context: Context, packageName: String, flags: Int = 0) = try {
        context.packageManager.getPackageInfo(packageName, flags)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun downgradeUninstallSender(context: Context, sessionId: Int, packageName: String) =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, ApkUpdateResultReceiver::class.java)
                .setAction(ApkUpdateResultReceiver.ACTION_DOWNGRADE_UNINSTALL_RESULT)
                .putExtra(EXTRA_DOWNGRADE_SESSION_ID, sessionId)
                .putExtra(EXTRA_PACKAGE, packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ).intentSender

    private fun resultSender(
        context: Context,
        sessionId: Int,
        packageName: String,
        isDowngrade: Boolean = false
    ) =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, ApkUpdateResultReceiver::class.java)
                .setAction(ApkUpdateResultReceiver.ACTION_INSTALL_RESULT)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_IS_DOWNGRADE, isDowngrade),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ).intentSender
}
