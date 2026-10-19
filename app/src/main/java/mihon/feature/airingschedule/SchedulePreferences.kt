package mihon.feature.airingschedule

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class SchedulePreferences(
    private val preferenceStore: PreferenceStore,
) {
    enum class TitleLanguage { USER_PREFERRED, ENGLISH, ROMAJI, NATIVE }
    enum class ScheduleSource { ANILIST, LIVECHART }
    enum class UploadDelayInterval(val minutes: Long) {
        THIRTY_MIN(30),
        ONE_HOUR(60),
        TWO_HOURS(120),
        SIX_HOURS(360),
        TWELVE_HOURS(720),
        CUSTOM(0),
        NEVER(0),
    }
    enum class AutoRefreshFrequency { EVERY_1_DAY, EVERY_2_DAYS, EVERY_3_DAYS, EVERY_4_DAYS, EVERY_5_DAYS, EVERY_6_DAYS, EVERY_7_DAYS }
    enum class ViewMode { WEEKLY, MONTHLY }

    fun viewMode() = preferenceStore.getEnum(
        "schedule_view_mode",
        ViewMode.WEEKLY,
    )

    fun favoriteSourceIds() = preferenceStore.getStringSet(
        "schedule_favorite_source_ids",
        emptySet(),
    )

    fun showOnlyFavoriteSources() = preferenceStore.getBoolean(
        "schedule_show_only_favorite_sources",
        false,
    )

    fun titleLanguage() = preferenceStore.getEnum(
        "schedule_title_language",
        TitleLanguage.USER_PREFERRED,
    )

    fun primarySource() = preferenceStore.getEnum(
        "schedule_primary_source",
        ScheduleSource.ANILIST,
    )

    fun showAdultContent() = preferenceStore.getBoolean(
        "schedule_show_adult_content",
        false,
    )

    fun uploadDelayEnabled() = preferenceStore.getBoolean(
        "schedule_upload_delay_enabled",
        false,
    )

    fun uploadDelayRefreshInterval() = preferenceStore.getEnum(
        "schedule_upload_delay_interval",
        UploadDelayInterval.ONE_HOUR,
    )

    /**
     * User-entered manual upload delay, in minutes (stored as a string so it can back an
     * [eu.kanade.presentation.more.settings.Preference.PreferenceItem.EditTextPreference]),
     * used when [uploadDelayRefreshInterval] is set to [UploadDelayInterval.CUSTOM]. Added to
     * the official AniList air time to shift the displayed expected upload time and episode
     * countdown, in place of the auto-learned per-source delay.
     */
    fun customUploadDelayMinutes() = preferenceStore.getString(
        "schedule_custom_upload_delay_minutes",
        "60",
    )

    fun sourceUploadDelays() = preferenceStore.getString(
        "schedule_source_upload_delays",
        "{}",
    )

    fun lastDelayCheckTime() = preferenceStore.getLong(
        "schedule_last_delay_check_time",
        0L,
    )

    fun lastSourceFeedSyncTime() = preferenceStore.getLong(
        "schedule_last_source_feed_sync_time",
        0L,
    )

    fun sourceFeedSyncStatus() = preferenceStore.getString(
        "schedule_source_feed_sync_status",
        "",
    )

    fun scheduleAutoRefreshEnabled() = preferenceStore.getBoolean(
        "schedule_auto_refresh_enabled",
        false,
    )

    fun scheduleAutoRefreshFrequency() = preferenceStore.getEnum(
        "schedule_auto_refresh_frequency",
        AutoRefreshFrequency.EVERY_7_DAYS,
    )

    fun scheduleLastAutoRefresh() = preferenceStore.getLong(
        "schedule_last_auto_refresh",
        0L,
    )

    /** Media ids for which the user wants a single alert for the next upcoming episode. */
    fun notifyOnceMediaIds() = preferenceStore.getStringSet(
        "schedule_notify_once_media_ids",
        emptySet(),
    )

    /** Media ids for which the user wants alerts for every episode until the series finishes. */
    fun notifySeriesMediaIds() = preferenceStore.getStringSet(
        "schedule_notify_series_media_ids",
        emptySet(),
    )

    /**
     * Legacy composite "mediaId:episode" keys for alarms scheduled by older builds. New alarms
     * are tracked in [pendingScheduleAlarms]; this set is only read so old alarms can still be
     * cancelled after an update.
     */
    fun scheduledAlarmKeys() = preferenceStore.getStringSet(
        "schedule_scheduled_alarm_keys",
        emptySet(),
    )

    /**
     * JSON map of "mediaId:episode" -> pending alarm (full schedule entry + trigger time).
     * Persisting the whole entry lets alarms be re-registered after a reboot, force-stop,
     * app update or upload-delay change, without needing the weekly schedule to be loaded.
     */
    fun pendingScheduleAlarms() = preferenceStore.getString(
        "schedule_pending_alarms_v2",
        "{}",
    )

    /**
     * JSON list of the user's manual schedule picks
     * ([mihon.feature.airingschedule.match.ScheduleManualMatch]). A pick made from a
     * schedule-launched Global Search overrides every automatic library title match.
     */
    fun manualMatches() = preferenceStore.getString(
        "schedule_manual_matches_v1",
        "[]",
    )

    companion object {
        /** Parses [customUploadDelayMinutes]'s raw string, clamped to a sane range. Falls back to 0 (no shift) if invalid. */
        fun parseCustomDelayMinutes(raw: String): Long =
            raw.trim().toLongOrNull()?.coerceIn(-24 * 60L, 24 * 60L) ?: 0L
    }
}
