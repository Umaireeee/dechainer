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
        // Forget it in this process first: the escape (the crash-loop breaker) must not fail closed
        // if the store cannot be written. The two removes mirror setUrge's one transaction.
        lastUrge = UrgeInput()
        try {
            val state = Store.appState(context)
            state.remove(AppStateKeys.URGE_LOCK_STARTED)
            state.remove(AppStateKeys.URGE_LOCK_ENDS)
        } catch (e: Exception) {
            Timber.w(e, "Urge lock not cleared on disk; it is cleared in memory")
        }
    }

    // ---- Study mode and lockdown ----
    // Same rule as the urge lock: a read that fails never writes anything back and falls back to the
    // last value seen in this process, and a start is remembered in the process before the disk.

    @Volatile private var lastStudy = StudyInput()
    @Volatile private var lastLockdown = LockdownInput()

    fun study(context: Context): StudyInput = try {
        StudyInput(Store.appState(context).getLong(AppStateKeys.STUDY_ENDS) ?: 0L).also { lastStudy = it }
    } catch (e: Exception) {
        Timber.w(e, "Study session not readable; using the last known")
        lastStudy
    }

    fun setStudy(context: Context, endsAt: Long) {
        lastStudy = StudyInput(endsAt)
        try {
            Store.appState(context).set(AppStateKeys.STUDY_ENDS, endsAt.toString())
        } catch (e: Exception) {
            Timber.e(e, "Study session not written; it runs from memory only")
        }
    }

    fun clearStudy(context: Context) {
        lastStudy = StudyInput()
        try {
            Store.appState(context).remove(AppStateKeys.STUDY_ENDS)
        } catch (e: Exception) {
            Timber.w(e, "Study session not cleared on disk; it is cleared in memory")
        }
    }

    fun lockdown(context: Context): LockdownInput = try {
        val state = Store.appState(context)
        val apps = state.get(AppStateKeys.LOCKDOWN_APPS).orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
        LockdownInput(state.getLong(AppStateKeys.LOCKDOWN_ENDS) ?: 0L, apps).also { lastLockdown = it }
    } catch (e: Exception) {
        Timber.w(e, "Lockdown not readable; using the last known")
        lastLockdown
    }

    /** Stores a lockdown: its end and its apps, in one transaction. */
    fun setLockdown(context: Context, endsAt: Long, apps: Set<String>) {
        lastLockdown = LockdownInput(endsAt, apps)
        try {
            Store.appState(context).setAll(
                mapOf(AppStateKeys.LOCKDOWN_ENDS to endsAt.toString(), AppStateKeys.LOCKDOWN_APPS to apps.sorted().joinToString("\n"))
            )
        } catch (e: Exception) {
            Timber.e(e, "Lockdown not written; it runs from memory only")
        }
    }

    /** Ends the lockdown and forgets its apps. Only the exit from Device Owner uses this. */
    fun clearLockdown(context: Context) {
        lastLockdown = LockdownInput()
        try {
            val state = Store.appState(context)
            state.remove(AppStateKeys.LOCKDOWN_ENDS)
            state.remove(AppStateKeys.LOCKDOWN_APPS)
        } catch (e: Exception) {
            Timber.w(e, "Lockdown not cleared on disk; it is cleared in memory")
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
        lastStudy = StudyInput()
        lastLockdown = LockdownInput()
    }
}
