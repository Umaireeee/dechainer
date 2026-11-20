package io.github.warleysr.dechainer.data

import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.zone.ZoneRules

/**
 * Serializes suspension reconciliation across Downtime, time-limit/schedule blocks, impulse lock,
 * and manual suspensions, and provides DST-safe boundary resolution for AlarmManager triggers.
 */
internal object SuspensionCoordinator {
    val lock = Any()

    fun manualSuspensions(context: Context): Set<String> =
        context.getSharedPreferences("manual_suspensions", Context.MODE_PRIVATE).all.keys.toSet()

    fun nextZonedInstantAfter(
        candidates: Iterable<LocalDateTime>,
        now: ZonedDateTime
    ): Instant? {
        val zone = now.zone
        val rules = zone.rules
        val nowInstant = now.toInstant()
        var best: Instant? = null
        for (dt in candidates) {
            for (instant in resolvedInstants(dt, zone, rules)) {
                if (instant.isAfter(nowInstant) && (best == null || instant.isBefore(best))) {
                    best = instant
                }
            }
        }
        return best
    }

    fun resolvedInstants(
        dt: LocalDateTime,
        zone: ZoneId,
        rules: ZoneRules = zone.rules
    ): List<Instant> {
        val offsets = rules.getValidOffsets(dt)
        if (offsets.isNotEmpty()) {
            return offsets.map { offset -> ZonedDateTime.ofStrict(dt, offset, zone).toInstant() }
        }
        // Spring-forward gap: trigger at the transition instant when wall time jumps past the gap.
        val transition = rules.getTransition(dt) ?: return listOf(dt.atZone(zone).toInstant())
        return listOf(transition.instant)
    }
}
