package io.github.warleysr.dechainer.clock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.Rules
import io.github.warleysr.dechainer.security.ForcedRemovalClock
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.Store
import timber.log.Timber
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * The time every decision is made on (blueprint 9.2): the wall time, but never going backwards.
 * The rule is in [TrustedClockMath]; this object keeps the last reading in memory, stores it in
 * `app_state` so a reboot or a process restart can build on it, and reads the boot count.
 *
 * A failing store never stops a lock: the clock carries on from memory, and from the wall time if
 * nothing was ever stored.
 *
 * Day boundaries use [zone]: the time zone of the phone, which the Device Owner keeps on automatic.
 */
object TrustedClock {
    private val lock = Any()
    private var checkpoint: ClockCheckpoint? = null
    private var loaded = false
    private var lastWriteElapsed = Long.MIN_VALUE
    private var lastWrittenBoot = ForcedRemovalClock.UNKNOWN_BOOT
    private val writer by lazy { Executors.newSingleThreadExecutor { Thread(it, "trusted-clock").apply { isDaemon = true } } }

    /** The trusted time now. From the UI and from code without a context; writes its checkpoint off-thread, now and then. */
    fun now(): Long = read(DechainerApplication.getInstance(), writeNow = false)

    fun now(context: Context): Long = read(context.applicationContext, writeNow = false)

    /** The trusted time now, with the checkpoint written before returning. What every wake-up uses. */
    fun checkpoint(context: Context): Long = read(context.applicationContext, writeNow = true)

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** A trusted-clock time as the wall time to give an alarm; the two differ only while the wall clock is behind. */
    fun toWall(trustedMs: Long, context: Context = DechainerApplication.getInstance()): Long =
        TrustedClockMath.toWall(trustedMs, trustedNow = now(context), wallNow = System.currentTimeMillis())

    private fun read(context: Context, writeNow: Boolean): Long {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = bootCount(context)
        val reading: ClockReading
        var toWrite: ClockCheckpoint? = null
        synchronized(lock) {
            if (!loaded) {
                checkpoint = load(context)
                loaded = true
            }
            reading = TrustedClockMath.read(wall, elapsed, boot, checkpoint)
            checkpoint = reading.checkpoint
            val due = writeNow || reading.wallDistrusted || boot != lastWrittenBoot ||
                elapsed - lastWriteElapsed >= Rules.CLOCK_CHECKPOINT_INTERVAL_MS
            if (due) {
                toWrite = reading.checkpoint
                lastWriteElapsed = elapsed
                lastWrittenBoot = boot
            }
        }
        if (reading.wallDistrusted) Timber.w("Wall clock is behind the trusted clock; using the running time")
        toWrite?.let { cp -> if (writeNow) save(context, cp) else writer.execute { save(context, cp) } }
        return reading.trustedMs
    }

    private fun load(context: Context): ClockCheckpoint? = try {
        val state = Store.appState(context)
        val trusted = state.getLong(AppStateKeys.LAST_SEEN_WALL)
        val elapsed = state.getLong(AppStateKeys.LAST_SEEN_ELAPSED)
        val boot = state.getInt(AppStateKeys.LAST_SEEN_BOOT)
        // All three or nothing: a partial record cannot be built on.
        if (trusted != null && elapsed != null && boot != null) ClockCheckpoint(trusted, elapsed, boot) else null
    } catch (e: Exception) {
        Timber.w(e, "Clock checkpoint not readable; starting from the wall time")
        null
    }

    private fun save(context: Context, cp: ClockCheckpoint) {
        try {
            Store.appState(context).setAll(
                mapOf(
                    AppStateKeys.LAST_SEEN_WALL to cp.trustedMs.toString(),
                    AppStateKeys.LAST_SEEN_ELAPSED to cp.elapsedMs.toString(),
                    AppStateKeys.LAST_SEEN_BOOT to cp.bootCount.toString()
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Clock checkpoint not saved")
        }
    }

    /** Android's count of boots, so a reboot is noticed for certain; unknown if it can't be read. */
    internal fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, ForcedRemovalClock.UNKNOWN_BOOT)
    } catch (_: Exception) {
        ForcedRemovalClock.UNKNOWN_BOOT
    }

    /** For tests: forget everything held in memory. */
    internal fun resetForTests() = synchronized(lock) {
        checkpoint = null
        loaded = false
        lastWriteElapsed = Long.MIN_VALUE
        lastWrittenBoot = ForcedRemovalClock.UNKNOWN_BOOT
    }
}
