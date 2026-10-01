package io.github.warleysr.dechainer.lock

/**
 * The bookkeeping that makes one wake-up cost one sync (blueprint 5.4, "one sync per wake-up").
 * Every request takes a ticket. A pass claims every ticket issued so far, so requests that arrive
 * while one is merely waiting to start are all served by that one pass; a request that arrives
 * while a pass is running gets exactly one more. Pure and single-purpose: the thread that runs the
 * passes and the waiting live in [LockEngine].
 */
class SyncCoalescer {
    private var issued = 0L
    private var claimed = 0L
    private var finished = 0L

    /** A caller wants a pass that starts from now on. Returns its ticket. */
    @Synchronized
    fun request(): Long = ++issued

    /** The worker: claims every ticket issued so far for one pass, or null if none is waiting. */
    @Synchronized
    fun beginPass(): Long? {
        if (claimed >= issued) return null
        claimed = issued
        return claimed
    }

    /** The pass that claimed [claim] is done. */
    @Synchronized
    fun endPass(claim: Long) {
        if (claim > finished) finished = claim
    }

    /** The pass serving [ticket] has finished. */
    @Synchronized
    fun isServed(ticket: Long): Boolean = finished >= ticket

    @Synchronized
    fun hasWaiting(): Boolean = claimed < issued
}
