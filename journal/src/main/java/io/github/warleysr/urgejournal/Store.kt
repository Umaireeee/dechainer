package io.github.warleysr.urgejournal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit

/** The journal, kept on this phone only. Only the optional AI deep dive ever sends anything out. */
class JournalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("journal", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Entry> = Entry.parseList(prefs.getString(KEY, null)).items.sortedBy { it.time }

    /**
     * Changes the stored entries. If the stored text cannot be read as a list at all, nothing is
     * written, so a read that failed can never be saved over the journal. Rows that could not be
     * parsed individually are kept as they were. Returns false when nothing was written.
     */
    @Synchronized
    private fun mutate(change: (List<Entry>) -> List<Entry>): Boolean {
        val read = Entry.parseList(prefs.getString(KEY, null))
        if (!read.rootOk) return false
        val next = change(read.items.sortedBy { it.time }).takeLast(LIMIT)
        prefs.edit(commit = true) { putString(KEY, Entry.composeList(next, read.unreadable)) }
        return true
    }

    @Synchronized
    fun add(entry: Entry) {
        mutate { it + entry }
    }

    /** Adds the entry, or replaces the one made at the same moment (a ride's first note becomes its full entry). */
    @Synchronized
    fun put(entry: Entry) {
        mutate { list ->
            if (list.any { it.time == entry.time }) list.map { if (it.time == entry.time) entry else it } else list + entry
        }
    }

    /** Removes the entry made at [time], but only if it is still an empty ride note; anything answered stays. */
    @Synchronized
    fun deleteStub(time: Long) {
        mutate { list -> list.filterNot { it.time == time && it.isStub } }
    }

    /** A ride nobody ever checked in on, older than [maxAgeMs]: its empty note and its reminder are dropped. */
    @Synchronized
    fun dropStaleRide(now: Long, maxAgeMs: Long) {
        val started = pendingRide()
        if (started != 0L && now - started > maxAgeMs) {
            deleteStub(started)
            clearPendingRide()
        }
    }

    @Synchronized
    fun setOutcome(time: Long, outcome: Outcome) = update(time) { it.copy(outcome = outcome) }

    @Synchronized
    fun setReport(time: Long, report: String) = update(time) { it.copy(report = report) }

    /** Deletes every entry, plan and the saved weekly review. Used by "delete all my data". */
    @Synchronized
    fun clear() {
        prefs.edit(commit = true) {
            remove(KEY); remove(REVIEW_TEXT); remove(REVIEW_TIME); remove(PLANS); remove(PENDING_RIDE); remove(DAYS)
            remove("dismissed_hot"); remove("dismissed_heavier")
        }
    }

    // ---- The if-then plans the person saved ----

    @Synchronized
    fun plans(): List<MyPlan> = MyPlan.parseList(prefs.getString(PLANS, null)).items

    /** Same rule as [mutate]: a failed read is never saved over the plans. */
    @Synchronized
    private fun mutatePlans(change: (List<MyPlan>) -> List<MyPlan>) {
        val read = MyPlan.parseList(prefs.getString(PLANS, null))
        if (!read.rootOk) return
        prefs.edit(commit = true) { putString(PLANS, MyPlan.composeList(change(read.items), read.unreadable)) }
    }

    @Synchronized
    fun addPlan(plan: MyPlan) = mutatePlans { (it + plan).takeLast(PLAN_LIMIT) }

    @Synchronized
    fun editPlan(id: Long, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        mutatePlans { list -> list.map { if (it.id == id) it.copy(text = clean.take(300)) else it } }
    }

    @Synchronized
    fun removePlan(id: Long) = mutatePlans { list -> list.filter { it.id != id } }

    // ---- The evening question: did the day go the way you planned? ----

    @Synchronized
    fun days(): Map<java.time.LocalDate, DayResult> = runCatching {
        val o = org.json.JSONObject(prefs.getString(DAYS, "{}") ?: "{}")
        buildMap {
            o.keys().forEach { key ->
                val date = runCatching { java.time.LocalDate.parse(key) }.getOrNull()
                val result = runCatching { DayResult.valueOf(o.getString(key)) }.getOrNull()
                if (date != null && result != null) put(date, result)
            }
        }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun setDay(date: java.time.LocalDate, result: DayResult) {
        // A stored value that cannot be read is left alone rather than replaced by one day.
        if (runCatching { org.json.JSONObject(prefs.getString(DAYS, "{}") ?: "{}") }.isFailure) return
        val kept = (days() + (date to result)).toSortedMap().entries.toList().takeLast(120)
        val o = org.json.JSONObject()
        kept.forEach { o.put(it.key.toString(), it.value.name) }
        prefs.edit(commit = true) { putString(DAYS, o.toString()) }
    }

    // ---- A ride that has started and not yet been checked in on ----

    /** When the last ride started, or 0 if none is waiting for a check-in. */
    fun pendingRide(): Long = prefs.getLong(PENDING_RIDE, 0L)

    fun setPendingRide(time: Long) = prefs.edit(commit = true) { putLong(PENDING_RIDE, time) }

    fun clearPendingRide() = prefs.edit(commit = true) { remove(PENDING_RIDE) }

    // ---- Cards the person waved away, so they stay away for a while ----

    fun dismissedAt(card: String): Long = prefs.getLong("dismissed_$card", 0L)

    fun dismiss(card: String, time: Long = System.currentTimeMillis()) =
        prefs.edit(commit = true) { putLong("dismissed_$card", time) }

    /** The whole journal as text, for a backup the person keeps. */
    fun exportJson(): String = Entry.listToJson(all())

    /** Adds entries from a backup; returns how many were new. Nothing existing is changed. */
    @Synchronized
    fun importJson(text: String): Int {
        val incoming = Entry.listFromJson(text)
        if (incoming.isEmpty()) return 0
        var added = 0
        mutate { before ->
            val merged = Entry.merge(before, incoming).takeLast(LIMIT)
            added = (merged.size - before.size).coerceAtLeast(0)
            merged
        }
        return added
    }

    /** The last weekly review and when it was written, if any. */
    fun lastReview(): Pair<Long, String>? {
        val text = prefs.getString(REVIEW_TEXT, null) ?: return null
        return prefs.getLong(REVIEW_TIME, 0L) to text
    }

    fun saveReview(text: String, time: Long = System.currentTimeMillis()) {
        prefs.edit(commit = true) { putString(REVIEW_TEXT, text); putLong(REVIEW_TIME, time) }
    }

    private fun update(time: Long, change: (Entry) -> Entry) {
        mutate { list -> list.map { if (it.time == time) change(it) else it } }
    }

    private companion object {
        const val KEY = "entries"
        const val REVIEW_TEXT = "review_text"
        const val REVIEW_TIME = "review_time"
        const val PLANS = "plans"
        const val DAYS = "days"
        const val PENDING_RIDE = "pending_ride"
        const val LIMIT = 2000
        const val PLAN_LIMIT = 30
    }
}

/** The connection to Déchaîner: one explicit broadcast, allowed only because both apps share a signing key. */
object Door {
    const val PKG = "io.github.warleysr.dechainer"
    private const val RECEIVER = "io.github.warleysr.dechainer.UrgeActionReceiver"
    private const val ACTION = "io.github.warleysr.dechainer.URGE_ACTION"
    private const val PERMISSION = "io.github.warleysr.dechainer.permission.URGE_ACTION"

    enum class Result { SENT, NOT_INSTALLED, NO_PERMISSION }

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PKG, 0) }.isSuccess

    /** Whether Android has granted this app the right to talk to Déchaîner (same signing key). */
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private const val STATUS_URI = "content://io.github.warleysr.dechainer.status/status"

    /**
     * Asks Déchaîner what is really going on: is it Device Owner, and until when is the ride lock
     * running. Null if it can't be reached. Blocking, so call it off the main thread.
     */
    fun status(context: Context): DoorStatus? = runCatching {
        context.contentResolver.query(android.net.Uri.parse(STATUS_URI), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            DoorStatus(
                deviceOwner = c.getInt(c.getColumnIndexOrThrow("device_owner")) == 1,
                rideLockUntil = c.getLong(c.getColumnIndexOrThrow("ride_lock_until")),
                impulseUntil = c.getLong(c.getColumnIndexOrThrow("impulse_until"))
            )
        }
    }.getOrNull()

    /**
     * Asks Déchaîner to block. SENT means the request left this app; Déchaîner still has to be
     * Device Owner for it to take effect.
     */
    fun send(context: Context, action: DoorAction): Result {
        if (!isInstalled(context)) return Result.NOT_INSTALLED
        if (!hasPermission(context)) return Result.NO_PERMISSION
        context.sendBroadcast(
            Intent(ACTION).apply {
                component = ComponentName(PKG, RECEIVER)
                putExtra("kind", action.kind)
                putExtra("minutes", action.minutes)
            }
        )
        return Result.SENT
    }
}

/** Whether the app hides itself in the recent-apps list and blocks screenshots. On by default: entries are private. */
class PrivacySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("privacy", Context.MODE_PRIVATE)

    var hideInRecents: Boolean
        get() = prefs.getBoolean("hide", true)
        set(v) = prefs.edit { putBoolean("hide", v) }
}
