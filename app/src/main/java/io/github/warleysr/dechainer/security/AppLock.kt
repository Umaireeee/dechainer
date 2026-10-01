package io.github.warleysr.dechainer.security

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.SettingsFreeze
import timber.log.Timber

/** What happened to one try at the lock. */
sealed interface UnlockResult {
    data object Unlocked : UnlockResult

    /** Wrong. [freeTriesLeft] more wrong tries cost nothing; 0 means the next wrong one makes the app wait. */
    data class Wrong(val freeTriesLeft: Int, val waitMs: Long) : UnlockResult

    /** Too many wrong tries: the app refuses for [waitMs] more. */
    data class WaitFirst(val waitMs: Long) : UnlockResult
}

/**
 * The app lock (owner request, after Phase 7): a PIN or pattern of its own, different from the
 * phone's screen lock, asked for when the app opens and after it has been out of sight for
 * [AppLockRules.RELOCK_AFTER_MS]. Home (the clock and the Urge button), the urge flow and a running
 * brick never wait behind it: an urge must always be reachable at once. Everything else does.
 *
 * Only a salted hash is stored. Wrong tries are counted in the preferences with a synchronous write,
 * so killing the app does not reset them, and they are slowed down by [AppLockRules.delayAfter] on
 * the trusted clock. Removing the lock loosens it, so it goes through the recovery code (and its
 * unlock delay): that is also the way back in when the secret is forgotten. Setting or changing it is
 * a settings write and is refused on a punishment day like every other.
 */
object AppLock {
    private const val PREFS = "app_lock_prefs"
    private const val KEY_KIND = "kind"
    private const val KEY_HASH = "hash"
    private const val KEY_FAILURES = "failures"
    private const val KEY_LAST_FAILURE = "last_failure_at"

    private val lockObject = Any()
    private var loaded = false
    private var enabledState by mutableStateOf(false)
    private var kindState by mutableStateOf<AppLockKind?>(null)

    /** True while the lock has been opened in this process. A new process always starts locked. */
    var unlocked by mutableStateOf(false)
        private set

    private var leftAt = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(lockObject) {
            if (loaded) return
            val p = prefs(ctx)
            val kind = p.getString(KEY_KIND, null)?.let { k -> AppLockKind.entries.firstOrNull { it.name == k } }
            val hasHash = !p.getString(KEY_HASH, null).isNullOrEmpty()
            kindState = kind.takeIf { hasHash }
            enabledState = kindState != null
            loaded = true
        }
    }

    fun isEnabled(ctx: Context): Boolean { ensureLoaded(ctx); return enabledState }

    fun kind(ctx: Context): AppLockKind? { ensureLoaded(ctx); return kindState }

    /** Whether the lock is on and has not been opened yet. Reads Compose state, so a screen that calls it updates. */
    fun isLocked(ctx: Context): Boolean { ensureLoaded(ctx); return enabledState && !unlocked }

    /** Locks now ("Lock the app now"). */
    fun lock() { unlocked = false }

    /** The activity left the screen. Remembered, so a short trip (a call, a permission dialog) does not lock. */
    fun onBackground() { leftAt = SystemClock.elapsedRealtime() }

    /** The activity is back. After [AppLockRules.RELOCK_AFTER_MS] away it is locked again. Elapsed time is the phone's own uptime, so the wall clock cannot stretch it. */
    fun onForeground() {
        if (leftAt != 0L && AppLockRules.shouldRelock(SystemClock.elapsedRealtime() - leftAt)) unlocked = false
        leftAt = 0L
    }

    // ---- trying the lock ----

    fun failures(ctx: Context): Int = prefs(ctx).getInt(KEY_FAILURES, 0)

    /** How long the app still refuses to try, in milliseconds (0 when it will take a try). */
    fun waitRemainingMs(ctx: Context): Long {
        val p = prefs(ctx)
        return AppLockRules.waitRemaining(p.getInt(KEY_FAILURES, 0), p.getLong(KEY_LAST_FAILURE, 0L), TrustedClock.now(ctx))
    }

    /** Tries [secret] (the PIN, or the pattern's dots as text). A right one opens the lock and clears the count. */
    fun tryUnlock(ctx: Context, secret: String): UnlockResult {
        synchronized(lockObject) {
            val p = prefs(ctx)
            val hash = p.getString(KEY_HASH, null)
            if (hash.isNullOrEmpty()) { unlocked = true; return UnlockResult.Unlocked }
            val wait = waitRemainingMs(ctx)
            if (wait > 0L) return UnlockResult.WaitFirst(wait)
            if (RecoveryCodeHash.verify(secret, hash)) {
                p.edit(commit = true) { putInt(KEY_FAILURES, 0); remove(KEY_LAST_FAILURE) }
                unlocked = true
                return UnlockResult.Unlocked
            }
            val n = p.getInt(KEY_FAILURES, 0) + 1
            // Synchronous: a kill right after a wrong try must not give the try back.
            p.edit(commit = true) { putInt(KEY_FAILURES, n); putLong(KEY_LAST_FAILURE, TrustedClock.now(ctx)) }
            return UnlockResult.Wrong(AppLockRules.freeTriesLeft(n), AppLockRules.delayAfter(n))
        }
    }

    // ---- changing it ----

    /** Sets (or replaces) the lock. False when a punishment day refuses the write or the secret is not valid for [kind]. */
    fun set(ctx: Context, kind: AppLockKind, secret: String): Boolean {
        if (!SettingsFreeze.allowWrite(ctx, "app lock")) return false
        val valid = when (kind) {
            AppLockKind.PIN -> AppLockRules.validPin(secret) && !AppLockRules.weakPin(secret)
            AppLockKind.PATTERN -> secret.length >= AppLockRules.PATTERN_MIN && secret.all { it in '0'..'8' } && secret.toSet().size == secret.length
        }
        if (!valid) return false
        val hash = try { RecoveryCodeHash.create(secret) } catch (e: Exception) { Timber.e(e, "App lock not hashed"); return false }
        prefs(ctx).edit(commit = true) {
            putString(KEY_KIND, kind.name); putString(KEY_HASH, hash)
            putInt(KEY_FAILURES, 0); remove(KEY_LAST_FAILURE)
        }
        synchronized(lockObject) { kindState = kind; enabledState = true; loaded = true }
        // Whoever just set it is in: do not lock them out of the screen they are on.
        unlocked = true
        return true
    }

    /**
     * Removes the lock. This loosens, so callers run it only inside the recovery-code gate; it is
     * also what "I forgot it" does. False when a punishment day refuses it.
     */
    fun remove(ctx: Context): Boolean {
        if (!SettingsFreeze.allowWrite(ctx, "app lock")) return false
        prefs(ctx).edit(commit = true) { clear() }
        synchronized(lockObject) { kindState = null; enabledState = false; loaded = true }
        unlocked = true
        return true
    }

    internal fun resetForTests() {
        synchronized(lockObject) { loaded = false; enabledState = false; kindState = null }
        unlocked = false
        leftAt = 0L
    }
}
