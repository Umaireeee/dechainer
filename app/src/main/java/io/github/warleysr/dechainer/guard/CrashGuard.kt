package io.github.warleysr.dechainer.guard

import io.github.warleysr.dechainer.Rules

/**
 * One crash, remembered by monotonic time and boot count rather than wall time, so moving the clock
 * cannot hide a crash loop, and a crash from before a reboot is not counted with one after it.
 */
data class CrashRecord(val elapsedMs: Long, val bootCount: Int) {
    companion object {
        /** `elapsed:boot` pairs joined by `;`. Anything that does not parse is dropped, never fatal. */
        fun decode(text: String?): List<CrashRecord> =
            (text ?: "").split(';').mapNotNull { part ->
                val f = part.split(':')
                val elapsed = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val boot = f.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                if (f.size == 2) CrashRecord(elapsed, boot) else null
            }

        fun encode(records: List<CrashRecord>): String = records.joinToString(";") { "${it.elapsedMs}:${it.bootCount}" }
    }
}

/** Where crashes are remembered. Implemented over SharedPreferences with a synchronous `commit()`. */
interface CrashLog {
    fun load(): List<CrashRecord>
    fun save(records: List<CrashRecord>)
}

/**
 * The crash-loop breaker (blueprint 5.4, R2). A bug can crash the app again and again while it holds
 * the phone; with the phone pinned to a crashing app the owner could be locked out for hours. Two
 * crashes inside five minutes while a brick is running abort the brick. It guards against bugs
 * only and is never reachable from the UI.
 *
 * Everything here is written to run inside an uncaught-exception handler: it never throws.
 */
class CrashGuard(
    private val log: CrashLog,
    private val brickActive: () -> Boolean,
    private val abort: () -> Unit,
    private val windowMs: Long = Rules.CRASH_WINDOW_MS,
    private val limit: Int = Rules.CRASH_LIMIT
) {
    /** Records this crash and aborts the brick if it is the second in the window. True if it aborted. */
    fun onCrash(nowElapsedMs: Long, bootCount: Int): Boolean = try {
        val recent = recentCrashes(log.load(), nowElapsedMs, bootCount) + CrashRecord(nowElapsedMs, bootCount)
        if (recent.size >= limit && brickActive()) {
            abort()
            log.save(emptyList())
            true
        } else {
            // Kept (and pruned to the window) so the next crash can still count them.
            log.save(recent)
            false
        }
    } catch (_: Throwable) {
        false
    }

    /** The crashes of this boot that fall inside the window ending now. Inclusive at exactly [windowMs]. */
    fun recentCrashes(records: List<CrashRecord>, nowElapsedMs: Long, bootCount: Int): List<CrashRecord> =
        records.filter { it.bootCount == bootCount && nowElapsedMs - it.elapsedMs in 0..windowMs }
}
