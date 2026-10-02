package io.github.warleysr.dechainer.lock

import android.content.Context
import androidx.core.content.edit
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.Store
import timber.log.Timber

/**
 * Where the urge lock is kept (blueprint section 7: `app_state`). A read that fails or does not
 * parse never writes anything back, and falls back to the last value read or written in this
 * process, so a store that cannot be opened for a moment neither ends a lock nor starts one.
 */
object LockStateStore {
    private const val PREFS = "lock_settings"
    /** The study-app list of the removed punishment day; only ever removed now. */
    private const val KEY_PUNISHMENT_APPS = "punishment_allowed_apps"

    @Volatile private var lastUrge = UrgeInput()

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
            Store.appState(context).setAll(
                mapOf(AppStateKeys.URGE_LOCK_STARTED to startedAt.toString(), AppStateKeys.URGE_LOCK_ENDS to endsAt.toString())
            )
        } catch (e: Exception) {
            Timber.e(e, "Urge lock not written; it runs from memory only")
        }
    }

    fun clearUrge(context: Context) {
        val state = Store.appState(context)
        state.remove(AppStateKeys.URGE_LOCK_STARTED)
        state.remove(AppStateKeys.URGE_LOCK_ENDS)
        lastUrge = UrgeInput()
    }

    /**
     * Removes what an older build may have stored for the punishment day, which no longer exists.
     * Safe to run on every start: it only removes keys.
     */
    fun clearLegacyPunishment(context: Context) {
        val state = Store.appState(context)
        AppStateKeys.LEGACY_PUNISHMENT_KEYS.forEach { state.remove(it) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { remove(KEY_PUNISHMENT_APPS) }
    }

    /** For tests: forget what was last seen in this process. */
    internal fun resetForTests() {
        lastUrge = UrgeInput()
    }
}
