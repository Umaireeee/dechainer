package io.github.warleysr.dechainer.data

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.models.BlockSchedule
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDateTime

/**
 * Stores the user's [BlockSchedule]s in `schedule_prefs`. Only persistence lives here — applying
 * a schedule to the device is [ScheduleEnforcer]'s job, which must be re-run after every change.
 */
object ScheduleRepository {
    const val PREFS_NAME = "schedule_prefs"
    private const val KEY_SCHEDULES = "schedules_json"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    fun getSchedules(context: Context): List<BlockSchedule> =
        parseSchedules(prefs(context).getString(KEY_SCHEDULES, null)).schedules

    fun getSchedule(context: Context, id: String): BlockSchedule? =
        getSchedules(context).firstOrNull { it.id == id }

    /**
     * Read, change, write, all under one lock. If the stored text cannot be read as a list at all,
     * nothing is written: a failed read must never be saved over the schedules. Entries that cannot
     * be parsed one by one are kept exactly as they were. Returns false when nothing was written.
     */
    private fun mutate(context: Context, change: (MutableList<BlockSchedule>) -> Unit): Boolean =
        synchronized(lock) {
            val read = parseSchedules(prefs(context).getString(KEY_SCHEDULES, null))
            if (!read.rootOk) {
                Timber.e("Stored schedules could not be read; leaving them untouched")
                return@synchronized false
            }
            val list = read.schedules.toMutableList()
            change(list)
            // commit() rather than apply(): the enforcer reads this right after, possibly from another thread.
            prefs(context).edit(commit = true) { putString(KEY_SCHEDULES, composeSchedules(list, read.unreadable)) }
            true
        }

    fun upsert(context: Context, schedule: BlockSchedule): Boolean = mutate(context) { list ->
        val index = list.indexOfFirst { it.id == schedule.id }
        if (index >= 0) list[index] = schedule else list.add(schedule)
    }

    fun delete(context: Context, id: String): Boolean = mutate(context) { list ->
        list.removeAll { it.id == id }
    }

    /** True while [schedule]'s window is open and it was set to lock itself during that time. */
    fun isLockedNow(schedule: BlockSchedule, now: LocalDateTime = LocalDateTime.now()): Boolean =
        schedule.lockWhileActive && schedule.isActiveAt(now)
}

/** What was read from storage: the schedules that parsed, and the rows that did not, kept as they were. */
internal class ScheduleRead(val schedules: List<BlockSchedule>, val unreadable: List<Any>, val rootOk: Boolean)

/** Reads the stored list entry by entry, so one bad entry never costs the others. */
internal fun parseSchedules(json: String?): ScheduleRead {
    if (json == null) return ScheduleRead(emptyList(), emptyList(), true)
    val array = try {
        JSONArray(json)
    } catch (_: Exception) {
        return ScheduleRead(emptyList(), emptyList(), false)
    }
    val good = mutableListOf<BlockSchedule>()
    val bad = mutableListOf<Any>()
    for (i in 0 until array.length()) {
        val raw = array.opt(i)
        val parsed = (raw as? JSONObject)?.let { runCatching { BlockSchedule.fromJson(it) }.getOrNull() }
        if (parsed != null) good += parsed else if (raw != null) bad += raw
    }
    return ScheduleRead(good, bad, true)
}

/** The text to store: the schedules, then the unreadable entries put back untouched. */
internal fun composeSchedules(schedules: List<BlockSchedule>, unreadable: List<Any>): String {
    val array = JSONArray()
    schedules.forEach { array.put(it.toJson()) }
    unreadable.forEach { array.put(it) }
    return array.toString()
}
