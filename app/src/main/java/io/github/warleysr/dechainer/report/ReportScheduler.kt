package io.github.warleysr.dechainer.report

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.warleysr.dechainer.clock.TrustedClock
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The weekly report job (blueprint 6.5): a unique WorkManager job `weekly-report-k` per period, which
 * needs a network and backs off exponentially. WorkManager keeps it across reboots, and [ensureQueued]
 * puts it back on boot and on app open, so a report due while the phone was off is built the next time
 * the phone is on and online. The job is only a wake-up: the worker checks the trusted clock itself.
 */
object ReportScheduler {
    private const val KEY_PERIOD = "period"

    fun uniqueName(k: Int) = "weekly-report-$k"

    /** Queues the job for every due period and for the running one (to start when it ends). Never throws. */
    fun ensureQueued(ctx: Context) {
        try {
            val svc = ReportService(ctx)
            val now = TrustedClock.now(ctx)
            val zone = TrustedClock.zone()
            val anchor = svc.anchor(zone) ?: return
            svc.dueWithData(now, zone).forEach { enqueue(ctx, it, 0) }
            val running = WeekMath.lastEnded(anchor, now, zone) + 1
            enqueue(ctx, running, (WeekMath.periodEnd(anchor, running, zone) - now).coerceAtLeast(0))
        } catch (e: Exception) {
            Timber.w(e, "Weekly report not queued")
        }
        // The monthly and yearly reports ride on the same wake-ups.
        for (kind in PeriodKind.entries) {
            try {
                val svc = PeriodReportService(ctx)
                val now = TrustedClock.now(ctx)
                val zone = TrustedClock.zone()
                svc.dueWithData(kind, now, zone).forEach { enqueuePeriod(ctx, kind, it, 0) }
                val current = PeriodMath.current(kind, now, zone)
                enqueuePeriod(ctx, kind, current, (PeriodMath.end(kind, current, zone) - now).coerceAtLeast(0))
            } catch (e: Exception) {
                Timber.w(e, "%s report not queued", kind)
            }
        }
    }

    fun periodUniqueName(kind: PeriodKind, key: String) = (if (kind == PeriodKind.MONTH) "monthly-report-" else "yearly-report-") + key

    private fun enqueuePeriod(ctx: Context, kind: PeriodKind, key: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<PeriodReportWorker>()
            .setInputData(Data.Builder().putString(KEY_KIND, kind.name).putString(KEY_KEY, key).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(periodUniqueName(kind, key), ExistingWorkPolicy.KEEP, request)
    }

    private const val KEY_KIND = "kind"
    private const val KEY_KEY = "key"

    internal fun periodJobOf(params: WorkerParameters): Pair<PeriodKind, String>? {
        val kind = PeriodKind.entries.firstOrNull { it.name == params.inputData.getString(KEY_KIND) } ?: return null
        val key = params.inputData.getString(KEY_KEY) ?: return null
        return kind to key
    }

    private fun enqueue(ctx: Context, k: Int, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<ReportWorker>()
            .setInputData(Data.Builder().putInt(KEY_PERIOD, k).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: a job already waiting, running or backing off keeps its place.
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(uniqueName(k), ExistingWorkPolicy.KEEP, request)
    }

    internal fun periodOf(params: WorkerParameters): Int = params.inputData.getInt(KEY_PERIOD, -1)
}

class ReportWorker(context: Context, private val params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val k = ReportScheduler.periodOf(params)
        if (k < 0) return Result.success()
        return when (ReportService(applicationContext).generate(k, TrustedClock.now(applicationContext), TrustedClock.zone())) {
            ReportOutcome.RETRY, ReportOutcome.NOT_DUE -> Result.retry()
            // Done, nothing to report, or waiting on the owner (key, consent): the job ends and is queued again when the app opens.
            else -> Result.success()
        }
    }
}

class PeriodReportWorker(context: Context, private val params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val (kind, key) = ReportScheduler.periodJobOf(params) ?: return Result.success()
        return when (PeriodReportService(applicationContext).generate(kind, key, TrustedClock.now(applicationContext), TrustedClock.zone())) {
            ReportOutcome.RETRY, ReportOutcome.NOT_DUE -> Result.retry()
            else -> Result.success()
        }
    }
}
