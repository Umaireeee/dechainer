package io.github.warleysr.dechainer.lock

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.Store
import timber.log.Timber

/**
 * Where the urge lock and the punishment day are kept (blueprint section 7: `app_state`), and the
 * owner's study-app list for a punishment day. A read that fails or does not parse never writes
 * anything back, and falls back to the last value read or written in this process, so a store
 * that cannot be opened for a moment neither ends a lock nor starts one.
 */
object LockStateStore {
    private const val PREFS = "lock_settings"
    private const val KEY_PUNISHMENT_APPS = "punishment_allowed_apps"

    @Volatile private var lastUrge = UrgeInput()
    @Volatile private var lastWindow = PunishmentInput()

    fun urge(context: Context): UrgeInput = try {
        val ends = Store.appState(context).getLong(AppStateKeys.URGE_LOCK_ENDS) ?: 0L
        UrgeInput(ends).also { lastUrge = it }
    } catch (e: Exception) {
        Timber.w(e, "Urge lock not readable; using the last known")
        lastUrge
    }

    fun startedAt(context: Context): Long = try {
        Store.appState(context).getLong(AppStateKeys.URGE_LOCK_STARTED) ?: 0L
    } catch (_: Exception) {
        0L
    }

    /**
     * Stores a running urge lock. Both values in one transaction. The lock is remembered in this
     * process first: if the store cannot be written the lock still runs until the process ends, which
     * is the safe side of a failure (a lock never fails to start because of a disk error).
     */
    fun setUrge(context: Context, startedAt: Long, endsAt: Long) {
        lastUrge = UrgeInput(endsAt)
        try {
            Store.appState(context).removeAll(listOf(AppStateKeys.URGE_LOCK_STARTED, AppStateKeys.URGE_LOCK_ENDS))
        } catch (e: Exception) {
            // Fall back to individual removes; at worst a reboot re-plans and drops the expired lock.
            Timber.w(e, "Urge lock clear not atomic; falling back to individual removes")
            try { Store.appState(context).remove(AppStateKeys.URGE_LOCK_STARTED) } catch (_: Exception) {}
            try { Store.appState(context).remove(AppStateKeys.URGE_LOCK_ENDS) } catch (_: Exception) {}
        }
        lastUrge = UrgeInput()
    }

    /** The punishment day on record, with the owner's study-app list. An empty window when there is none. */
    fun punishment(context: Context): PunishmentInput = try {
        val state = Store.appState(context)
        PunishmentInput(
            startsAt = state.getLong(AppStateKeys.PUNISHMENT_FROM) ?: 0L,
            endsAt = state.getLong(AppStateKeys.PUNISHMENT_UNTIL) ?: 0L,
            ownerApps = punishmentOwnerApps(context)
        ).also { lastWindow = it }
    } catch (e: Exception) {
        Timber.w(e, "Punishment day not readable; using the last known")
        lastWindow
    }

    /**
     * Records a punishment day. Written by the day evaluation (Phase 5) and by the debug build. Not a
     * settings write: the freeze must never be able to stop a punishment from being recorded.
     */
    fun setPunishment(context: Context, window: PunishmentInput, date: String) {
        Store.appState(context).setAll(
            mapOf(
                AppStateKeys.PUNISHMENT_FROM to window.startsAt.toString(),
                AppStateKeys.PUNISHMENT_UNTIL to window.endsAt.toString(),
                AppStateKeys.PUNISHMENT_DATE to date
            )
        )
        lastWindow = window.copy(ownerApps = punishmentOwnerApps(context))
    }

    /**
     * Ends a punishment day at [at] and notes that the system did it. Only the crash-loop breaker
     * calls this: it exists so a bug cannot trap the phone, and is not reachable from the UI.
     */
    fun endPunishmentBySystem(context: Context, at: Long) {
        val state = Store.appState(context)
        val until = state.getLong(AppStateKeys.PUNISHMENT_UNTIL) ?: 0L
        if (until <= at) return
        state.setAll(mapOf(AppStateKeys.PUNISHMENT_UNTIL to at.toString(), AppStateKeys.PUNISHMENT_ABORTED_AT to at.toString()))
        lastWindow = lastWindow.copy(endsAt = at)
    }

    // ---- The study-app list (D7): empty by default, changed only with the recovery code ----

    fun punishmentOwnerApps(context: Context): Set<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PUNISHMENT_APPS, emptySet())?.toSet() ?: emptySet()

    /** Replaces the list. Refused while the settings are frozen. False if it was refused. */
    fun setPunishmentOwnerApps(context: Context, apps: Set<String>): Boolean {
        if (!SettingsFreeze.allowWrite(context, "punishment study-app list")) return false
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit(commit = true) { putStringSet(KEY_PUNISHMENT_APPS, apps) }
        return true
    }

    /** For tests: forget what was last seen in this process. */
    internal fun resetForTests() {
        lastUrge = UrgeInput()
        lastWindow = PunishmentInput()
    }
}
