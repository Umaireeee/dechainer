package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.models.AppGroup
import io.github.warleysr.dechainer.models.TimeWindow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object AppGroupRepository {
    private val context = DechainerApplication.getInstance()

    const val PREFS_NAME = "app_groups"
    private const val KEY_GROUPS = "groups"
    private const val USAGE_PREFS_NAME = "group_usage_stats"

    @Volatile
    private var cachedGroups: List<AppGroup>? = null
    private val lock = Any()

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getGroups(forceRefresh: Boolean = false): List<AppGroup> {
        if (!forceRefresh) cachedGroups?.let { return it }
        synchronized(lock) {
            if (!forceRefresh) cachedGroups?.let { return it }
            val fresh = decode(prefs().getString(KEY_GROUPS, null))
            cachedGroups = fresh
            return fresh
        }
    }

    fun getGroupForPackage(packageName: String): AppGroup? =
        getGroups().firstOrNull { packageName in it.packageNames }

    fun createGroup(name: String): AppGroup {
        val group = AppGroup(id = UUID.randomUUID().toString(), name = name)
        synchronized(lock) { persist(getGroups() + group) }
        return group
    }

    fun deleteGroup(groupId: String) {
        synchronized(lock) { persist(getGroups().filterNot { it.id == groupId }) }
        context.getSharedPreferences(USAGE_PREFS_NAME, Context.MODE_PRIVATE).edit { remove(groupId) }
    }

    fun renameGroup(groupId: String, name: String) = updateGroup(groupId) { it.copy(name = name) }

    fun setGroupTimeLimit(groupId: String, minutes: Int) =
        updateGroup(groupId) { it.copy(timeLimitMinutes = minutes) }

    fun setGroupTimeWindows(groupId: String, windows: List<TimeWindow>) =
        updateGroup(groupId) { it.copy(timeWindows = windows) }

    /** Replaces the group's members. Any package added here is removed from whichever other group held it, keeping each app in at most one group. */
    fun setGroupPackages(groupId: String, packageNames: Set<String>) {
        synchronized(lock) {
            val updated = getGroups().map { group ->
                if (group.id == groupId) group.copy(packageNames = packageNames)
                else group.copy(packageNames = group.packageNames - packageNames)
            }
            persist(updated)
        }
    }

    /** Assigns [packageName] to [groupId] (or removes it from any group when null), keeping the one-group-per-app invariant. */
    fun setPackageGroup(packageName: String, groupId: String?) {
        synchronized(lock) {
            val updated = getGroups().map { group ->
                when {
                    group.id == groupId -> group.copy(packageNames = group.packageNames + packageName)
                    packageName in group.packageNames -> group.copy(packageNames = group.packageNames - packageName)
                    else -> group
                }
            }
            persist(updated)
        }
    }

    fun getGroupUsage(groupId: String, inMinutes: Boolean = false): Long {
        val used = context.getSharedPreferences(USAGE_PREFS_NAME, Context.MODE_PRIVATE).getLong(groupId, 0L)
        return if (inMinutes) TimeUnit.MILLISECONDS.toMinutes(used) else used
    }

    private fun updateGroup(groupId: String, transform: (AppGroup) -> AppGroup) {
        synchronized(lock) {
            persist(getGroups().map { if (it.id == groupId) transform(it) else it })
        }
    }

    private fun persist(groups: List<AppGroup>) {
        prefs().edit { putString(KEY_GROUPS, encode(groups)) }
        cachedGroups = groups
    }

    private fun encode(groups: List<AppGroup>): String {
        val array = JSONArray()
        groups.forEach { group ->
            array.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("packages", JSONArray(group.packageNames.toList()))
                put("limit", group.timeLimitMinutes)
                put("windows", JSONArray(group.timeWindows.map { w ->
                    JSONObject().apply { put("s", w.startMinute); put("e", w.endMinute) }
                }))
            })
        }
        return array.toString()
    }

    private fun decode(json: String?): List<AppGroup> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val packagesArray = obj.getJSONArray("packages")
                val packages = (0 until packagesArray.length()).map { packagesArray.getString(it) }.toSet()
                val windowsArray = obj.getJSONArray("windows")
                val windows = (0 until windowsArray.length()).map { j ->
                    val w = windowsArray.getJSONObject(j)
                    TimeWindow(w.getInt("s"), w.getInt("e"))
                }
                AppGroup(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    packageNames = packages,
                    timeLimitMinutes = obj.getInt("limit"),
                    timeWindows = windows
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
