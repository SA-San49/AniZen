package mihon.feature.airingschedule.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.airingschedule.AiringScheduleEntry
import mihon.feature.airingschedule.SchedulePreferences
import mihon.feature.airingschedule.UploadDelayTracker
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Schedules/cancels the local alarms that back the per-anime "notify me" bell on the
 * Schedule tab. Notifications are delivered by [ScheduleAlarmReceiver].
 *
 * Two kinds of subscription exist (both keyed by media id):
 * - **Once** (tap): one alarm for one episode. Consumed when it fires.
 * - **Series** (long press): an alarm for the next episode, re-armed after every firing and by
 *   [AiringAlertSyncWorker], which looks the next episode up on AniList directly. This is what
 *   keeps a series subscription alive across weekly schedule refreshes, until the show finishes
 *   or the user turns it off.
 *
 * Alarms fire when the Schedule card's local countdown reaches zero (official air time plus the
 * same upload delay the card shows), not at the raw official time.
 */
object ScheduleNotifications {

    private val schedulePreferences: SchedulePreferences by lazy { Injekt.get() }
    private val uploadDelayTracker: UploadDelayTracker by lazy { Injekt.get() }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Pending-alarm and subscription preferences are read-modified-written from several
    // independent callers (UI taps, background workers, the alarm/boot receivers). All
    // mutating access is serialized on this lock.
    private val lock = Any()

    private inline fun <T> locked(crossinline block: () -> T): T = synchronized(lock) { block() }

    @Serializable
    data class PendingAlarm(
        val entry: AiringScheduleEntry,
        val triggerAtSeconds: Long,
    )

    fun alarmKey(mediaId: Int, episode: Int): String = "$mediaId:$episode"

    fun requestCode(mediaId: Int, episode: Int): Int = alarmKey(mediaId, episode).hashCode()

    /**
     * Whether the app is currently allowed to schedule *exact* alarms. On Android 12
     * (API 31) this permission is auto-granted; on Android 13+ it can be revoked by the
     * user or the system, so callers should check this and, if false, prompt the user via
     * [requestExactAlarmPermission] so the schedule bell fires at the precise air time
     * instead of being deferred by battery-optimization/Doze batching windows.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    /** Opens the system settings screen where the user can grant the exact-alarm permission. */
    fun requestExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    // region Timing

    /** Upload delay (minutes) currently applied to the Schedule card countdown, or null for none. */
    fun currentDelayMinutes(): Long? {
        val enabled = schedulePreferences.uploadDelayEnabled().get()
        if (!enabled) return null
        val custom = if (schedulePreferences.uploadDelayRefreshInterval().get() ==
            SchedulePreferences.UploadDelayInterval.CUSTOM
        ) {
            SchedulePreferences.parseCustomDelayMinutes(schedulePreferences.customUploadDelayMinutes().get())
        } else {
            null
        }
        return AiringAlertPolicy.effectiveDelayMinutes(
            uploadDelayEnabled = true,
            customDelayMinutes = custom,
            favoriteSourceIds = schedulePreferences.favoriteSourceIds().get(),
            learnedDelays = uploadDelayTracker.getDelays(),
        )
    }

    /** Epoch second at which the alert for [entry] fires (when its local countdown hits zero). */
    fun notifyAtSeconds(entry: AiringScheduleEntry, delayMinutes: Long? = currentDelayMinutes()): Long =
        AiringAlertPolicy.notifyAtSeconds(entry.airingAt, delayMinutes)

    /** True if [entry]'s countdown has not reached zero yet, so an alert can still be armed. */
    fun isUpcoming(entry: AiringScheduleEntry, delayMinutes: Long? = currentDelayMinutes()): Boolean =
        notifyAtSeconds(entry, delayMinutes) > nowSeconds()

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    // endregion

    // region Subscription state (drives the bell icon)

    fun isOnce(mediaId: Int): Boolean = mediaId.toString() in schedulePreferences.notifyOnceMediaIds().get()

    fun isSeries(mediaId: Int): Boolean = mediaId.toString() in schedulePreferences.notifySeriesMediaIds().get()

    fun setOnce(mediaId: Int, enabled: Boolean) = locked {
        val pref = schedulePreferences.notifyOnceMediaIds()
        val key = mediaId.toString()
        pref.set(if (enabled) pref.get() + key else pref.get() - key)
    }

    fun setSeries(mediaId: Int, enabled: Boolean) = locked {
        val pref = schedulePreferences.notifySeriesMediaIds()
        val key = mediaId.toString()
        pref.set(if (enabled) pref.get() + key else pref.get() - key)
    }

    fun seriesMediaIds(): Set<Int> =
        schedulePreferences.notifySeriesMediaIds().get().mapNotNull { it.toIntOrNull() }.toSet()

    // endregion

    // region Pending alarm store

    private fun readPending(): MutableMap<String, PendingAlarm> = runCatching {
        json.decodeFromString<Map<String, PendingAlarm>>(schedulePreferences.pendingScheduleAlarms().get())
    }.getOrDefault(emptyMap()).toMutableMap()

    private fun writePending(map: Map<String, PendingAlarm>) {
        schedulePreferences.pendingScheduleAlarms().set(json.encodeToString(map))
    }

    fun hasPendingAlarm(mediaId: Int): Boolean = locked {
        readPending().values.any { it.entry.mediaId == mediaId } ||
            schedulePreferences.scheduledAlarmKeys().get().any { it.startsWith("$mediaId:") }
    }

    /** The episode a pending alert is armed for, or null if none. */
    fun pendingEpisodeFor(mediaId: Int): Int? = locked {
        readPending().values.filter { it.entry.mediaId == mediaId }.minByOrNull { it.triggerAtSeconds }?.entry?.episode
    }

    // endregion

    // region Arming / cancelling

    /**
     * Ensures an alarm is armed for [entry] at its countdown time. Returns true if an alarm is now
     * armed (or already was), false if the countdown has already reached zero or the platform
     * rejected the alarm — callers must not light the bell in that case since nothing backs it.
     * Re-arms automatically if the trigger time changed (e.g. the upload delay was updated).
     */
    fun ensureScheduled(context: Context, entry: AiringScheduleEntry): Boolean = locked {
        val triggerAt = notifyAtSeconds(entry)
        if (triggerAt <= nowSeconds()) return@locked false
        val key = alarmKey(entry.mediaId, entry.episode)
        val pending = readPending()
        if (pending[key]?.triggerAtSeconds == triggerAt) return@locked true
        val armed = setAlarm(context, entry, triggerAt)
        if (armed) {
            pending[key] = PendingAlarm(entry, triggerAt)
            writePending(pending)
        }
        armed
    }

    private fun setAlarm(context: Context, entry: AiringScheduleEntry, triggerAtSeconds: Long): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val titleLanguage = runCatching { schedulePreferences.titleLanguage().get() }
            .getOrDefault(SchedulePreferences.TitleLanguage.USER_PREFERRED)
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            putExtra(ScheduleAlarmReceiver.EXTRA_MEDIA_ID, entry.mediaId)
            putExtra(ScheduleAlarmReceiver.EXTRA_EPISODE, entry.episode)
            putExtra(
                ScheduleAlarmReceiver.EXTRA_TITLE,
                entry.displayTitle(titleLanguage).ifBlank { entry.titleUserPreferred },
            )
            putExtra(ScheduleAlarmReceiver.EXTRA_TITLE_ENGLISH, entry.titleEnglish)
            putExtra(ScheduleAlarmReceiver.EXTRA_TITLE_ROMAJI, entry.titleRomaji)
            putExtra(ScheduleAlarmReceiver.EXTRA_TITLE_NATIVE, entry.titleNative)
            putExtra(ScheduleAlarmReceiver.EXTRA_COVER_URL, entry.coverImageUrl)
            putExtra(ScheduleAlarmReceiver.EXTRA_TOTAL_EPISODES, entry.totalEpisodes ?: -1)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(entry.mediaId, entry.episode),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAtMillis = triggerAtSeconds * 1000L
        return runCatching {
            if (canScheduleExactAlarms(context)) {
                // Fires at the precise moment even in Doze/App Standby.
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                // No exact-alarm permission (Android 13+ user/system revoked it) — fall back to an
                // inexact alarm; timing may drift under Doze.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }.isSuccess
    }

    private fun cancelAlarm(context: Context, mediaId: Int, episode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(mediaId, episode),
            Intent(context, ScheduleAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    /** Cancels a single previously-scheduled alarm for [entry], if any. */
    fun cancel(context: Context, entry: AiringScheduleEntry) = locked {
        cancelAlarm(context, entry.mediaId, entry.episode)
        removeKeysLocked(entry.mediaId, entry.episode)
    }

    /** Cancels every pending alarm belonging to [mediaId] (new and legacy). */
    fun cancelAllForMedia(context: Context, mediaId: Int) = locked {
        val pending = readPending()
        val episodes = pending.values.filter { it.entry.mediaId == mediaId }.map { it.entry.episode } +
            schedulePreferences.scheduledAlarmKeys().get()
                .filter { it.startsWith("$mediaId:") }
                .mapNotNull { it.substringAfter(':').toIntOrNull() }
        episodes.distinct().forEach { episode ->
            cancelAlarm(context, mediaId, episode)
            removeKeysLocked(mediaId, episode)
        }
    }

    /** Kept for source compatibility with older callers. */
    fun cancelAllForMedia(context: Context, mediaId: Int, entries: List<AiringScheduleEntry>) {
        entries.filter { it.mediaId == mediaId }.forEach { cancel(context, it) }
        cancelAllForMedia(context, mediaId)
    }

    /** Removes the persisted key for an already-fired alarm. */
    fun removeAlarmKey(mediaId: Int, episode: Int) = locked { removeKeysLocked(mediaId, episode) }

    private fun removeKeysLocked(mediaId: Int, episode: Int) {
        val key = alarmKey(mediaId, episode)
        val pending = readPending()
        if (pending.remove(key) != null) writePending(pending)
        val legacy = schedulePreferences.scheduledAlarmKeys().get()
        if (key in legacy) schedulePreferences.scheduledAlarmKeys().set(legacy - key)
    }

    /**
     * Re-registers every persisted pending alarm. Needed after a reboot, force-stop, app update or
     * exact-alarm permission change (all of which wipe AlarmManager), and after the upload delay
     * changes (which moves the countdown). Alarms whose countdown passed while they couldn't fire
     * are dropped. Single-episode bells left with no alarm behind them are switched off.
     */
    fun rearmAll(context: Context) = locked {
        val pending = readPending()
        val delay = currentDelayMinutes()
        val now = nowSeconds()
        val updated = mutableMapOf<String, PendingAlarm>()
        pending.forEach { (key, alarm) ->
            val triggerAt = AiringAlertPolicy.notifyAtSeconds(alarm.entry.airingAt, delay)
            if (triggerAt > now && setAlarm(context, alarm.entry, triggerAt)) {
                updated[key] = PendingAlarm(alarm.entry, triggerAt)
            }
        }
        writePending(updated)

        // Drop stale single-episode bells (nothing left to fire for them).
        val legacy = schedulePreferences.scheduledAlarmKeys().get()
        val armedMedia = updated.values.map { it.entry.mediaId.toString() }.toSet() +
            legacy.map { it.substringBefore(':') }.toSet()
        val oncePref = schedulePreferences.notifyOnceMediaIds()
        val once = oncePref.get()
        val stillArmed = once.filter { it in armedMedia }.toSet()
        if (stillArmed.size != once.size) oncePref.set(stillArmed)
    }

    /**
     * Restores everything after the system wiped alarms (boot, update, force-stop, permission
     * change) or on app start: re-arms pending alarms and kicks the series sync so the next
     * episode of every followed series is armed even if the Schedule tab is never opened.
     */
    fun restore(context: Context) {
        rearmAll(context)
        if (seriesMediaIds().isNotEmpty()) {
            AiringAlertSyncWorker.ensurePeriodic(context)
            AiringAlertSyncWorker.enqueueNow(context)
        } else {
            AiringAlertSyncWorker.cancelAll(context)
        }
    }

    // endregion

    // region Lifecycle hooks

    /**
     * Called by [ScheduleAlarmReceiver] when an alert fires. Consumes a single-episode alert and,
     * for a followed series, either ends the subscription (final episode) or schedules a sync to
     * arm the next episode.
     */
    fun onAlarmFired(context: Context, mediaId: Int, episode: Int, totalEpisodes: Int?) {
        removeAlarmKey(mediaId, episode)
        if (isOnce(mediaId) && !hasPendingAlarm(mediaId)) {
            setOnce(mediaId, false)
        }
        if (isSeries(mediaId)) {
            if (AiringAlertPolicy.isFinalEpisode(episode, totalEpisodes)) {
                endSeries(context, mediaId)
            } else {
                // Give AniList a few minutes to advance nextAiringEpisode before looking it up;
                // the periodic sync is the safety net if this run is early.
                AiringAlertSyncWorker.enqueueNow(context, initialDelayMinutes = NEXT_EPISODE_LOOKUP_DELAY_MIN)
            }
        }
    }

    /**
     * Called when the episode for [entry] reached the library before its alert fired (library
     * update). Cancels that alert, consumes a single-episode bell and keeps a series going.
     */
    fun markHandledEarly(context: Context, entry: AiringScheduleEntry) {
        cancel(context, entry)
        if (isOnce(entry.mediaId) && !hasPendingAlarm(entry.mediaId)) {
            setOnce(entry.mediaId, false)
        }
        if (isSeries(entry.mediaId)) {
            AiringAlertSyncWorker.enqueueNow(context, initialDelayMinutes = NEXT_EPISODE_LOOKUP_DELAY_MIN)
        }
    }

    /** Ends a "notify every episode" subscription and cancels its alarms. */
    fun endSeries(context: Context, mediaId: Int) {
        setSeries(mediaId, false)
        cancelAllForMedia(context, mediaId)
        if (seriesMediaIds().isEmpty()) AiringAlertSyncWorker.cancelAll(context)
    }

    // endregion

    /** Returns the unique Android notification ID for a schedule alert. */
    fun notificationId(mediaId: Int, episode: Int): Int {
        return eu.kanade.tachiyomi.data.notification.Notifications.ID_AIRING_SCHEDULE_BASE -
            ((requestCode(mediaId, episode) and Int.MAX_VALUE) % 10000)
    }

    /** Dismisses a posted airing schedule notification if active. */
    fun dismissNotification(context: Context, mediaId: Int, episode: Int) {
        val notifId = notificationId(mediaId, episode)
        val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)
        notificationManager.cancel(notifId)
    }

    private const val NEXT_EPISODE_LOOKUP_DELAY_MIN = 3L
}
