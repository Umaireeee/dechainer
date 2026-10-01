package io.github.warleysr.dechainer.urge

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The retry for a deep dive that could not be made (blueprint 6.2): a unique WorkManager job that
 * needs a network and backs off exponentially, running until every waiting entry has its deep dive
 * or the owner deletes it. It survives reboots by itself.
 */
object DeepDiveScheduler {
    private const val UNIQUE = "deepdive-retry"

    /**
     * Queues the job. [replace] starts a fresh one (a new pending entry, a new key); without it a job
     * already queued, running or backing off is left alone, so opening the app does not reset its wait.
     * Never throws: failing to queue a retry must not break the flow that asked.
     */
    fun enqueue(context: Context, replace: Boolean) {
        try {
            val request = OneTimeWorkRequestBuilder<DeepDiveWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Timber.w(e, "Deep dive retry not queued")
        }
    }

    /** On app open and when settings change: queue the job if anything is waiting. */
    fun enqueueIfPending(context: Context, replace: Boolean = false) {
        if (UrgeFlow(context).hasPending()) enqueue(context, replace)
    }
}

private const val MAX_ATTEMPTS = 6

class DeepDiveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = when (UrgeFlow(applicationContext).retryPending()) {
        // After a few tries the job rests until the app is opened again: a reply that fails every time must not
        // be sent (and paid for) for ever in the background.
        DeepDiveRetry.Outcome.RETRY -> if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        // Done, nothing waiting, or waiting on the owner (key, consent, a refused key): the job ends and is queued again when that changes.
        else -> Result.success()
    }
}
