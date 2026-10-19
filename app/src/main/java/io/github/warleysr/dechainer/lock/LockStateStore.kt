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

    /** True once this process has read or written the urge lock, so [lastUrge] is a real value and not the default. */
    @Volatile private var knownThisProcess = false

    /**
     * The urge lock as stored. A value that cannot be read or parsed is never "no lock": the last value known
     * in this process is used instead. Null only when nothing is known yet and the store cannot be read.
     */
    fun urgeOrNull(context: Context): UrgeInput? {
        val input = try {
            when (val raw = Store.appState(context).get(AppStateKeys.URGE_LOCK_ENDS)) {
                null -> UrgeInput()
                else -> raw.toLongOrNull()?.let { UrgeInput(it) }
            }
        } catch (e: Exception) {
            Timber.w(e, "Urge lock not readable; using the last known")
            null
        }
        if (input == null) {
            Timber.w("Urge lock not readable; using the last known")
            return if (knownThisProcess) lastUrge else null
        }
        lastUrge = input
        knownThisProcess = true
        return input
    }

    /** The urge lock as stored; a read that fails with nothing known yet reads as no lock. */
    fun urge(context: Context): UrgeInput = urgeOrNull(context) ?: UrgeInput()

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
        knownThisProcess = true
        try {
            Store.appState(context).setAll(
                mapOf(AppStateKeys.URGE_LOCK_STARTED to startedAt.toString(), AppStateKeys.URGE_LOCK_ENDS to endsAt.toString())
            )
        } catch (e: Exception) {
            Timber.e(e, "Urge lock not written; it runs from memory only")
        }
    }

    fun clearUrge(context: Context) {
        // Forget it in this process first: the escape (the crash-loop breaker) must not fail closed
        // if the store cannot be written. Both keys go in one transaction, as setUrge writes them.
        lastUrge = UrgeInput()
        knownThisProcess = true
        try {
            Store.appState(context).removeAll(listOf(AppStateKeys.URGE_LOCK_STARTED, AppStateKeys.URGE_LOCK_ENDS))
        } catch (e: Exception) {
            Timber.w(e, "Urge lock not cleared on disk; it is cleared in memory")
        }
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
        knownThisProcess = false
    }
}
