package com.neurorift.android.profile

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neurorift.android.R
import java.util.concurrent.TimeUnit

/**
 * Subscription auto-update scheduling (issue #18) — the WorkManager owner.
 *
 * The periodic `subscription-sync` work is enqueued once per process (KEEP
 * policy: existing schedule wins, so re-enqueueing on every app start never
 * resets the cadence) with a connected-network constraint; [SyncWorker] then
 * scans the registry for due profiles each run. A user-facing "check now"
 * path (or a profile change that should be picked up immediately) can kick
 * [triggerOneShot] — an expedited one-shot appended to any running sync.
 *
 * Wired from [com.neurorift.android.NeuroRiftApplication.onCreate]; the
 * worker shares the process-wide [ProfileManager] singleton.
 */
class SubscriptionScheduler(private val context: Context) {

    /** 30-minute periodic scan of due subscriptions (unique, KEEP). */
    fun ensurePeriodicWork() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    /** Expedited one-shot run (APPEND_OR_REPLACE — chains behind a running sync). */
    fun triggerOneShot() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    companion object {
        const val PERIODIC_WORK_NAME = "subscription-sync"
        const val ONE_SHOT_WORK_NAME = "subscription-sync-now"
    }
}

/**
 * Scans the profile registry for profiles with an auto-update interval whose
 * next update time ([ScheduleMath.isDue]) has passed and refreshes each one
 * through [ProfileManager.update] — the same pipeline as a manual refresh
 * (fetch → decode → materialize → sub:progress events → profiles:changed).
 *
 * Local imports (no source URL) are skipped — they have nothing to fetch and
 * a forced update would only set a spurious error badge.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = ProfileViewModel.manager(applicationContext)
        return try {
            val now = System.currentTimeMillis()
            val due = manager.list().filter {
                it.intervalH > 0 && !it.sourceUrl.isNullOrEmpty() &&
                    ScheduleMath.isDue(it.lastSyncAt, it.intervalH, now)
            }
            for (record in due) {
                runCatching { manager.update(record.id) }
            }
            Result.success()
        } catch (e: Exception) {
            // Never silent: surface the scheduler failure on the same channel
            // the Profiles screen listens to.
            com.neurorift.android.engine.EventBus.emit(
                com.neurorift.android.engine.Events.SUB_PROGRESS,
                ProfileManager.SubProgress(
                    null,
                    com.neurorift.android.engine.SubPhase.ERROR,
                    applicationContext.getString(R.string.profiles_scheduler_error, e.message ?: "unknown"),
                ),
            )
            Result.retry()
        }
    }
}
