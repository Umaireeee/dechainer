package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.models.BlockSchedule
import org.json.JSONArray
import java.time.LocalDateTime

/**
 * Stores the user's [BlockSchedule]s in `schedule_prefs`. Only persistence lives here — applying
 * a schedule to the device is [ScheduleEnforcer]'s job, which must be re-run after every change.
 */
object ScheduleRepository {
    const val PREFS_NAME = "schedule_prefs"
    private const val KEY_SCHEDULES = "schedules_json"
    private const val KEY_ANTI_TAMPER = "anti_tamper_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSchedules(context: Context): List<BlockSchedule> {
        val json = prefs(context).getString(KEY_SCHEDULES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { BlockSchedule.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getSchedule(context: Context, id: String): BlockSchedule? =
        getSchedules(context).firstOrNull { it.id == id }

    private fun saveAll(context: Context, schedules: List<BlockSchedule>) {
        val array = JSONArray()
        schedules.forEach { array.put(it.toJson()) }
        // commit() rather than apply(): the enforcer reads this right after, possibly from another thread.
        prefs(context).edit(commit = true) { putString(KEY_SCHEDULES, array.toString()) }
    }

    fun upsert(context: Context, schedule: BlockSchedule) {
        val list = getSchedules(context).toMutableList()
        val index = list.indexOfFirst { it.id == schedule.id }
        if (index >= 0) list[index] = schedule else list.add(schedule)
        saveAll(context, list)
    }

    fun delete(context: Context, id: String) {
        saveAll(context, getSchedules(context).filterNot { it.id == id })
    }

    /** True while [schedule]'s window is open and it was set to lock itself during that time. */
    fun isLockedNow(schedule: BlockSchedule, now: LocalDateTime = LocalDateTime.now()): Boolean =
        schedule.lockWhileActive && schedule.isActiveAt(now)

    /**
     * Anti-tamper forces automatic date/time and blocks changing it while any schedule is enabled,
     * so a window can't be skipped by moving the clock. On by default.
     */
    fun isAntiTamperEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANTI_TAMPER, true)

    fun setAntiTamperEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit(commit = true) { putBoolean(KEY_ANTI_TAMPER, enabled) }
    }
}
