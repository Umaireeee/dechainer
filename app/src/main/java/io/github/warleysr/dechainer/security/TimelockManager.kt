package io.github.warleysr.dechainer.security

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import io.github.warleysr.dechainer.data.AppTimeLimits
import io.github.warleysr.dechainer.models.TimeLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.DayOfWeek
import java.util.UUID

sealed interface PendingAction {
    val type: String

    fun toPayload(): JSONObject

    data class UnhideApp(val packageName: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("packageName", packageName)
        companion object { const val TYPE = "unhide_app" }
    }

    data class AllowUninstallApp(val packageName: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("packageName", packageName)
        companion object { const val TYPE = "allow_uninstall_app" }
    }

    data class UnsuspendApp(val packageName: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("packageName", packageName)
        companion object { const val TYPE = "unsuspend_app" }
    }

    data class SetAppTimeLimit(
        val packageName: String,
        val limitEncoded: String,
        val reopenSeconds: Int
    ) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject()
            .put("packageName", packageName)
            .put("limitEncoded", limitEncoded)
            .put("reopenSeconds", reopenSeconds)
        companion object { const val TYPE = "set_app_time_limit" }
    }

    data class SetAppTimeWindows(
        val packageName: String,
        val windowsEncoded: String
    ) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject()
            .put("packageName", packageName)
            .put("windowsEncoded", windowsEncoded)
        companion object { const val TYPE = "set_app_time_windows" }
    }

    data class SetAppGroup(val packageName: String, val groupId: String?) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject()
            .put("packageName", packageName)
            .apply { if (groupId != null) put("groupId", groupId) }
        companion object { const val TYPE = "set_app_group" }
    }

    data class BulkSetAppGroup(val packageNames: List<String>, val groupId: String?) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject()
            .put("packageNames", JSONArray(packageNames))
            .apply { if (groupId != null) put("groupId", groupId) }
        companion object { const val TYPE = "bulk_set_app_group" }
    }

    data class SetAppRestrictions(val packageName: String, val bundleJson: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject()
            .put("packageName", packageName)
            .put("bundleJson", bundleJson)
        companion object { const val TYPE = "set_app_restrictions" }
    }

    data class UpdateGroup(
        val groupId: String,
        val name: String,
        val limitEncoded: String,
        val windowsEncoded: String,
        val packageNames: List<String>
    ) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject()
            .put("groupId", groupId)
            .put("name", name)
            .put("limitEncoded", limitEncoded)
            .put("windowsEncoded", windowsEncoded)
            .put("packageNames", JSONArray(packageNames))
        companion object { const val TYPE = "update_group" }
    }

    data class DeleteGroup(val groupId: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("groupId", groupId)
        companion object { const val TYPE = "delete_group" }
    }

    data class SaveDowntimeRules(val rulesJson: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("rulesJson", rulesJson)
        companion object { const val TYPE = "save_downtime_rules" }
    }

    data class DeleteDowntimeRule(val ruleId: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("ruleId", ruleId)
        companion object { const val TYPE = "delete_downtime_rule" }
    }

    data class ClearSystemRestrictions(val keys: List<String>) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("keys", JSONArray(keys))
        companion object { const val TYPE = "clear_system_restrictions" }
    }

    data object DisableAccessibility : PendingAction {
        override val type = "disable_accessibility"
        override fun toPayload() = JSONObject()
    }

    data object DisableNotificationAccess : PendingAction {
        override val type = "disable_notification_access"
        override fun toPayload() = JSONObject()
    }

    data class ChangePrivateDns(val host: String, val autoFailover: Boolean) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject()
            .put("host", host)
            .put("autoFailover", autoFailover)
        companion object { const val TYPE = "change_private_dns" }
    }

    data class UpdateLocalDnsShield(val configJson: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("configJson", configJson)
        companion object { const val TYPE = "update_local_dns_shield" }
    }

    data class UpdateBrowserHardening(val configJson: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("configJson", configJson)
        companion object { const val TYPE = "update_browser_hardening" }
    }

    data class RemoveBlockedActivity(val className: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("className", className)
        companion object { const val TYPE = "remove_blocked_activity" }
    }

    data class UpdateImpulseLock(
        val actionName: String,
        val durationMinutes: Int,
        val suspendedApps: List<String>,
        val tetrisUnlock: Boolean,
        val tetrisMinutes: Int
    ) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject()
            .put("actionName", actionName)
            .put("durationMinutes", durationMinutes)
            .put("suspendedApps", JSONArray(suspendedApps))
            .put("tetrisUnlock", tetrisUnlock)
            .put("tetrisMinutes", tetrisMinutes)
        companion object { const val TYPE = "update_impulse_lock" }
    }

    data object RemoveDeviceOwner : PendingAction {
        override val type = "remove_device_owner"
        override fun toPayload() = JSONObject()
    }

    data class AllowApkDowngrade(val packageName: String) : PendingAction {
        override val type = TYPE
        override fun toPayload() = JSONObject().put("packageName", packageName)
        companion object { const val TYPE = "allow_apk_downgrade" }
    }

    companion object {
        fun fromJson(type: String, payload: JSONObject): PendingAction? = runCatching {
            when (type) {
                UnhideApp.TYPE -> UnhideApp(payload.getString("packageName"))
                AllowUninstallApp.TYPE -> AllowUninstallApp(payload.getString("packageName"))
                UnsuspendApp.TYPE -> UnsuspendApp(payload.getString("packageName"))
                SetAppTimeLimit.TYPE -> SetAppTimeLimit(
                    packageName = payload.getString("packageName"),
                    limitEncoded = payload.getString("limitEncoded"),
                    reopenSeconds = payload.getInt("reopenSeconds")
                )
                SetAppTimeWindows.TYPE -> SetAppTimeWindows(
                    packageName = payload.getString("packageName"),
                    windowsEncoded = payload.getString("windowsEncoded")
                )
                SetAppGroup.TYPE -> SetAppGroup(
                    packageName = payload.getString("packageName"),
                    groupId = payload.optString("groupId").takeIf { payload.has("groupId") && it.isNotEmpty() }
                )
                BulkSetAppGroup.TYPE -> BulkSetAppGroup(
                    packageNames = payload.getJSONArray("packageNames").toStringList(),
                    groupId = payload.optString("groupId").takeIf { payload.has("groupId") && it.isNotEmpty() }
                )
                SetAppRestrictions.TYPE -> SetAppRestrictions(
                    packageName = payload.getString("packageName"),
                    bundleJson = payload.getString("bundleJson")
                )
                UpdateGroup.TYPE -> UpdateGroup(
                    groupId = payload.getString("groupId"),
                    name = payload.getString("name"),
                    limitEncoded = payload.getString("limitEncoded"),
                    windowsEncoded = payload.getString("windowsEncoded"),
                    packageNames = payload.getJSONArray("packageNames").toStringList()
                )
                DeleteGroup.TYPE -> DeleteGroup(payload.getString("groupId"))
                SaveDowntimeRules.TYPE -> SaveDowntimeRules(payload.getString("rulesJson"))
                DeleteDowntimeRule.TYPE -> DeleteDowntimeRule(payload.getString("ruleId"))
                ClearSystemRestrictions.TYPE -> ClearSystemRestrictions(payload.getJSONArray("keys").toStringList())
                DisableAccessibility.type -> DisableAccessibility
                DisableNotificationAccess.type -> DisableNotificationAccess
                ChangePrivateDns.TYPE -> ChangePrivateDns(
                    host = payload.getString("host"),
                    autoFailover = payload.optBoolean("autoFailover", false)
                )
                UpdateLocalDnsShield.TYPE -> UpdateLocalDnsShield(payload.getString("configJson"))
                UpdateBrowserHardening.TYPE -> UpdateBrowserHardening(payload.getString("configJson"))
                RemoveBlockedActivity.TYPE -> RemoveBlockedActivity(payload.getString("className"))
                UpdateImpulseLock.TYPE -> UpdateImpulseLock(
                    actionName = payload.getString("actionName"),
                    durationMinutes = payload.getInt("durationMinutes"),
                    suspendedApps = payload.getJSONArray("suspendedApps").toStringList(),
                    tetrisUnlock = payload.getBoolean("tetrisUnlock"),
                    tetrisMinutes = payload.getInt("tetrisMinutes")
                )
                RemoveDeviceOwner.type -> RemoveDeviceOwner
                AllowApkDowngrade.TYPE -> AllowApkDowngrade(payload.getString("packageName"))
                else -> null
            }
        }.getOrNull()
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { getString(it) }

data class PendingUnlockRequest(
    val id: String,
    val dedupeKey: String,
    val title: String,
    val description: String,
    val createdRtcMs: Long,
    val countdown: CountdownState,
    val action: PendingAction
) {
    val status: CountdownStatus get() = countdown.status()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("dedupeKey", dedupeKey)
        put("title", title)
        put("description", description)
        put("createdRtcMs", createdRtcMs)
        put("countdown", countdown.toJson())
        put("actionType", action.type)
        put("actionPayload", action.toPayload())
    }

    companion object {
        fun fromJson(obj: JSONObject): PendingUnlockRequest? = runCatching {
            val type = obj.getString("actionType")
            val payload = obj.getJSONObject("actionPayload")
            val action = PendingAction.fromJson(type, payload) ?: return@runCatching null
            PendingUnlockRequest(
                id = obj.getString("id"),
                dedupeKey = obj.getString("dedupeKey"),
                title = obj.getString("title"),
                description = obj.getString("description"),
                createdRtcMs = obj.optLong("createdRtcMs", 0L),
                countdown = CountdownState.fromJson(obj.getJSONObject("countdown")),
                action = action
            )
        }.getOrNull()
    }
}

sealed interface EnqueueResult {
    data class Queued(val request: PendingUnlockRequest) : EnqueueResult
    data class AlreadyPending(val existing: PendingUnlockRequest) : EnqueueResult
}

object TimelockManager {
    private const val PREFS_NAME = "timelock_prefs"
    private const val KEY_REQUESTS = "pending_requests"
    private const val KEY_APK_DOWNGRADE_PASS_PREFIX = "apk_downgrade_pass_"
    private const val APK_DOWNGRADE_PASS_MS = 15L * 60L * 1000L

    const val WAIT_DURATION_MS = CountdownState.SEVEN_DAYS_MS
    const val CLAIM_WINDOW_MS = CountdownState.TWENTY_FOUR_HOURS_MS

    private val lock = Any()
    private val _requests = MutableStateFlow<List<PendingUnlockRequest>>(emptyList())
    val requests: StateFlow<List<PendingUnlockRequest>> = _requests.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal fun readBootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1)

    fun syncAll(
        context: Context,
        nowRtc: Long = System.currentTimeMillis(),
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = readBootCount(context)
    ): List<PendingUnlockRequest> {
        synchronized(lock) {
            val loaded = loadLocked(context)
            val updated = loaded.mapNotNull { req ->
                val advanced = req.countdown.advance(nowRtc, nowElapsed, bootCount)
                if (advanced.status() == CountdownStatus.Expired) null
                else req.copy(countdown = advanced)
            }
            if (updated != loaded) {
                saveLocked(context, updated)
            }
            _requests.value = updated
            return updated
        }
    }

    fun findByDedupeKey(
        context: Context,
        dedupeKey: String,
        nowRtc: Long = System.currentTimeMillis(),
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = readBootCount(context)
    ): PendingUnlockRequest? =
        syncAll(context, nowRtc, nowElapsed, bootCount).firstOrNull { it.dedupeKey == dedupeKey }

    fun enqueue(
        context: Context,
        dedupeKey: String,
        title: String,
        description: String,
        action: PendingAction,
        durationMs: Long = WAIT_DURATION_MS,
        claimWindowMs: Long = CLAIM_WINDOW_MS,
        nowRtc: Long = System.currentTimeMillis(),
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = readBootCount(context)
    ): EnqueueResult {
        synchronized(lock) {
            val current = syncAll(context, nowRtc, nowElapsed, bootCount)
            current.firstOrNull { it.dedupeKey == dedupeKey }?.let {
                return EnqueueResult.AlreadyPending(it)
            }
            val req = PendingUnlockRequest(
                id = UUID.randomUUID().toString(),
                dedupeKey = dedupeKey,
                title = title,
                description = description,
                createdRtcMs = nowRtc,
                countdown = CountdownState.start(
                    durationMs = durationMs,
                    claimWindowMs = claimWindowMs,
                    nowRtc = nowRtc,
                    nowElapsed = nowElapsed,
                    bootCount = bootCount
                ),
                action = action
            )
            val next = current + req
            saveLocked(context, next)
            _requests.value = next
            return EnqueueResult.Queued(req)
        }
    }

    fun cancel(context: Context, requestId: String) {
        synchronized(lock) {
            val next = loadLocked(context).filterNot { it.id == requestId }
            saveLocked(context, next)
            _requests.value = next
        }
    }

    fun cancelAll(context: Context) {
        synchronized(lock) {
            prefs(context).edit(commit = true) { clear() }
            _requests.value = emptyList()
        }
    }

    fun applyIfReady(
        context: Context,
        requestId: String,
        nowRtc: Long = System.currentTimeMillis(),
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = readBootCount(context)
    ): Boolean {
        val target = synchronized(lock) {
            val synced = syncAll(context, nowRtc, nowElapsed, bootCount)
            val found = synced.firstOrNull { it.id == requestId } ?: return false
            if (found.status !is CountdownStatus.Ready) return false
            found
        }
        val executed = runCatching { PendingActionExecutor.execute(context.applicationContext, target.action) }
            .onFailure { Timber.e(it, "Failed executing pending unlock ${target.id}") }
            .getOrDefault(false)
        if (!executed) return false

        synchronized(lock) {
            val next = loadLocked(context).filterNot { it.id == requestId }
            saveLocked(context, next)
            _requests.value = next
        }
        return true
    }

    fun grantApkDowngradePass(context: Context, packageName: String, nowElapsed: Long = SystemClock.elapsedRealtime()) {
        prefs(context).edit(commit = true) {
            putLong(KEY_APK_DOWNGRADE_PASS_PREFIX + packageName, nowElapsed + APK_DOWNGRADE_PASS_MS)
        }
    }

    fun hasActiveApkDowngradePass(context: Context, packageName: String, nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean {
        val key = KEY_APK_DOWNGRADE_PASS_PREFIX + packageName
        val p = prefs(context)
        val expiresAt = p.getLong(key, 0L)
        if (expiresAt <= 0L) return false
        if (nowElapsed > expiresAt) {
            p.edit(commit = true) { remove(key) }
            return false
        }
        return true
    }

    fun consumeApkDowngradePass(context: Context, packageName: String) {
        prefs(context).edit(commit = true) { remove(KEY_APK_DOWNGRADE_PASS_PREFIX + packageName) }
    }

    fun encodeTimeLimit(limit: TimeLimit): String {
        val days = DayOfWeek.entries.joinToString(",") { limit.minutesFor(it).toString() }
        return if (limit.isPerDay) "W:$days" else "D:${limit.dailyMinutes}"
    }

    fun decodeTimeLimit(encoded: String): TimeLimit {
        if (encoded.startsWith("D:")) {
            return TimeLimit.daily(encoded.removePrefix("D:").toIntOrNull()?.coerceAtLeast(0) ?: 0)
        }
        if (encoded.startsWith("W:")) {
            val list = AppTimeLimits.decodeWeekly(encoded.removePrefix("W:"))
            if (list != null) return TimeLimit.perDay(list)
        }
        return TimeLimit.NONE
    }

    fun encodeBundle(bundle: Bundle): String {
        val root = JSONObject()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            val value = bundle.get(key)
            val entry = JSONObject()
            when (value) {
                is Boolean -> {
                    entry.put("t", "b")
                    entry.put("v", value)
                }
                is Int -> {
                    entry.put("t", "i")
                    entry.put("v", value)
                }
                is Long -> {
                    entry.put("t", "l")
                    entry.put("v", value)
                }
                is String -> {
                    entry.put("t", "s")
                    entry.put("v", value)
                }
                is Array<*> -> {
                    entry.put("t", "sa")
                    entry.put("v", JSONArray(value.filterIsInstance<String>()))
                }
                is Bundle -> {
                    entry.put("t", "bu")
                    entry.put("v", JSONObject(encodeBundle(value)))
                }
                else -> continue
            }
            root.put(key, entry)
        }
        return root.toString()
    }

    fun decodeBundle(json: String): Bundle {
        val bundle = Bundle()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return bundle
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = root.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "b" -> bundle.putBoolean(key, entry.getBoolean("v"))
                "i" -> bundle.putInt(key, entry.getInt("v"))
                "l" -> bundle.putLong(key, entry.getLong("v"))
                "s" -> bundle.putString(key, entry.getString("v"))
                "sa" -> {
                    val arr = entry.optJSONArray("v") ?: JSONArray()
                    bundle.putStringArray(key, Array(arr.length()) { arr.getString(it) })
                }
                "bu" -> entry.optJSONObject("v")?.let {
                    bundle.putBundle(key, decodeBundle(it.toString()))
                }
            }
        }
        return bundle
    }

    fun formatRemaining(ms: Long): String {
        val totalMinutes = ((ms + 59_999L) / 60_000L).coerceAtLeast(0L)
        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes % (24L * 60L)) / 60L
        val minutes = totalMinutes % 60L
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    private fun loadLocked(context: Context): List<PendingUnlockRequest> {
        val raw = prefs(context).getString(KEY_REQUESTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { PendingUnlockRequest.fromJson(it) }
        }
    }

    private fun saveLocked(context: Context, list: List<PendingUnlockRequest>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs(context).edit(commit = true) {
            if (list.isEmpty()) remove(KEY_REQUESTS) else putString(KEY_REQUESTS, array.toString())
        }
    }
}
