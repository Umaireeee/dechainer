package io.github.warleysr.dechainer.urge

import android.content.Context
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockStateStore
import io.github.warleysr.dechainer.store.Store
import io.github.warleysr.dechainer.store.UrgeEntryRepository
import timber.log.Timber

/**
 * The blackout flow's actions: the lock starts the moment the owner asks for it, and the blackout
 * screen runs for the chosen length. The lock never waits on anything; a store
 * that cannot be written is a log line, not a reason to hold back. Blocking: call it off the main
 * thread.
 */
class UrgeFlow(
    context: Context,
    private val repository: () -> UrgeEntryRepository = { Store.urgeEntries(context) }
) {
    private val ctx = context.applicationContext

    private val repo get() = repository()

    /**
     * Starts (or adopts) the blackout. A running one is left exactly as it is: a second tap never
     * extends it and never makes a second entry. Returns the entry the blackout screen runs from.
     */
    fun startUrge(source: UrgeSource): UrgeEntry {
        val first = TrustedClock.now(ctx)
        runCatching { UrgeFlowRules.resumable(repo.all(), first) }.getOrNull()?.let { return it }

        val lockMs = UrgeSettings(ctx).durationMs
        val id = createEntry(source, first)
        val decision = LockEngine.startUrgeLock(ctx, lockMs)
        val now = TrustedClock.now(ctx)
        val window = UrgeFlowRules.windowFor(decision, now, lockMs, LockStateStore.startedAt(ctx).takeIf { it > 0L })
        if (id >= 0L) runCatching { repo.setLockWindow(id, window.startsAt, window.endsAt) }
            .onFailure { Timber.w(it, "Urge lock window not stored") }
        return UrgeEntry(id, first, source, window.startsAt, window.endsAt)
    }

    /** The entry the app opens back into after being closed or killed, if a lock is still running. */
    fun resumable(): UrgeEntry? =
        runCatching { UrgeFlowRules.resumable(repo.all(), TrustedClock.now(ctx)) }
            .onFailure { Timber.w(it, "Running urge entries not readable") }
            .getOrNull()

    private fun createEntry(source: UrgeSource, at: Long): Long =
        try {
            repo.insert(source, at)
        } catch (e: Exception) {
            // The lock must start anyway: an entry that cannot be stored is a log line, not a reason to wait.
            Timber.e(e, "Urge entry not stored; carrying on in memory")
            -1L
        }
}
