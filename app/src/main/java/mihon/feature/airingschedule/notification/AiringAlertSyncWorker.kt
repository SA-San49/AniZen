package mihon.feature.airingschedule.notification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import mihon.feature.airingschedule.ScheduleDataRefreshWorker
import java.util.concurrent.TimeUnit

/**
 * Keeps "notify every episode" (long-press) subscriptions alive independently of the weekly
 * schedule. For every followed series it asks AniList for the *next* airing episode and arms an
 * alarm for it, and ends the subscription once the show is finished or cancelled.
 *
 * Runs periodically (every [PERIODIC_HOURS] hours while any series is followed), right after an
 * alert fires, on app start and after reboot.
 */
class AiringAlertSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        // Re-register anything AlarmManager may have lost.
        runCatching { ScheduleNotifications.rearmAll(context) }

        val seriesIds = ScheduleNotifications.seriesMediaIds()
        if (seriesIds.isEmpty()) {
            cancelPeriodic(context)
            return Result.success()
        }

        var failed = false

        val aniListIds = seriesIds.filter { it > 0 }
        if (aniListIds.isNotEmpty()) {
            try {
                val infoById = AniListNextEpisodeLookup().fetch(aniListIds)
                for (mediaId in aniListIds) {
                    // Missing from the response (e.g. temporarily unavailable): keep it, retry later.
                    val info = infoById[mediaId] ?: continue
                    when (AiringAlertPolicy.seriesDecision(info.status, info.nextEpisode?.episode)) {
                        AiringAlertPolicy.SeriesDecision.SCHEDULE -> {
                            ScheduleNotifications.ensureScheduled(context, info.nextEpisode!!)
                        }
                        AiringAlertPolicy.SeriesDecision.END -> {
                            // Let an already-armed final-episode alert fire first; the receiver
                            // (or the next sync) ends the subscription afterwards.
                            if (!ScheduleNotifications.hasPendingAlarm(mediaId)) {
                                ScheduleNotifications.endSeries(context, mediaId)
                            }
                        }
                        AiringAlertPolicy.SeriesDecision.WAIT -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }

        // Entries that only came from the LiveChart fallback have no AniList id; the best we can
        // do for those is arm whatever the latest cached weekly schedule contains.
        val localIds = seriesIds.filter { it < 0 }.toSet()
        if (localIds.isNotEmpty()) {
            runCatching {
                ScheduleDataRefreshWorker.readCache(context)?.entries
                    ?.filter { it.mediaId in localIds }
                    ?.forEach { ScheduleNotifications.ensureScheduled(context, it) }
            }
        }

        return if (failed && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "AiringAlertSyncPeriodic"
        private const val ONE_TIME_WORK_NAME = "AiringAlertSyncNow"
        private const val PERIODIC_HOURS = 12L
        private const val MAX_ATTEMPTS = 5

        private val networkConstraints
            get() = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        /** Starts the periodic sync if it isn't already scheduled. */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<AiringAlertSyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        /** Runs a sync as soon as the network allows, optionally after [initialDelayMinutes]. */
        fun enqueueNow(context: Context, initialDelayMinutes: Long = 0L) {
            val request = OneTimeWorkRequestBuilder<AiringAlertSyncWorker>()
                .setConstraints(networkConstraints)
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }
        }

        private fun cancelPeriodic(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME) }
        }

        /** Stops all syncing (no followed series left). */
        fun cancelAll(context: Context) {
            runCatching {
                val wm = WorkManager.getInstance(context)
                wm.cancelUniqueWork(PERIODIC_WORK_NAME)
                wm.cancelUniqueWork(ONE_TIME_WORK_NAME)
            }
        }
    }
}
