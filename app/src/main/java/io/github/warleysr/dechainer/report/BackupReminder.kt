package io.github.warleysr.dechainer.report

import android.content.Context
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.store.AppStateKeys
import io.github.warleysr.dechainer.store.Store
import timber.log.Timber

/**
 * The data lives only on this phone, so a lost or wiped phone loses every report and every day of
 * history. Reports shows a quiet reminder when there is something worth keeping and the last backup
 * is older than [EVERY_DAYS] days (or there has never been one).
 */
object BackupReminder {
    const val EVERY_DAYS = 30L
    private const val DAY_MS = 86_400_000L

    /** Pure: whether to remind, from the last backup time (null when never), the first data point and now. */
    fun due(lastBackupAt: Long?, firstDataAt: Long?, now: Long): Boolean {
        if (firstDataAt == null) return false
        // Give a new owner a week of data before the first nudge.
        if (lastBackupAt == null) return now - firstDataAt >= 7 * DAY_MS
        return now - lastBackupAt >= EVERY_DAYS * DAY_MS
    }

    fun isDue(ctx: Context): Boolean = try {
        val state = Store.appState(ctx)
        val first = listOfNotNull(
            Store.urgeEntries(ctx).firstCreatedAt(),
            Store.days(ctx).firstPlanAt(),
            Store.focus(ctx).sessions().minOfOrNull { it.startedAt }
        ).minOrNull()
        due(state.getLong(AppStateKeys.LAST_BACKUP_AT), first, TrustedClock.now(ctx))
    } catch (e: Exception) {
        Timber.w(e, "Backup reminder not read")
        false
    }

    fun markDone(ctx: Context) {
        try {
            Store.appState(ctx).set(AppStateKeys.LAST_BACKUP_AT, TrustedClock.now(ctx).toString())
        } catch (e: Exception) {
            Timber.w(e, "Backup time not stored")
        }
    }
}
