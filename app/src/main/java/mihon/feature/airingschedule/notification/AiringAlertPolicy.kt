package mihon.feature.airingschedule.notification

/**
 * Pure decision logic for the Schedule tab notification bell. Kept free of Android/Injekt
 * dependencies so it can be unit tested.
 */
object AiringAlertPolicy {

    /** What the background sync should do with a "notify every episode" subscription. */
    enum class SeriesDecision {
        /** Arm an alarm for the next known episode. */
        SCHEDULE,

        /** Series is still running but no next airing date is known yet (hiatus, TBA). Keep it. */
        WAIT,

        /** Series has finished or was cancelled: drop the subscription. */
        END,
    }

    private val ENDED_STATUSES = setOf("FINISHED", "CANCELLED")

    fun seriesDecision(status: String?, nextEpisode: Int?): SeriesDecision = when {
        status?.uppercase() in ENDED_STATUSES -> SeriesDecision.END
        nextEpisode != null -> SeriesDecision.SCHEDULE
        else -> SeriesDecision.WAIT
    }

    /** True when [episode] is the last episode of a show with a known episode count. */
    fun isFinalEpisode(episode: Int, totalEpisodes: Int?): Boolean =
        totalEpisodes != null && totalEpisodes > 0 && episode >= totalEpisodes

    /**
     * Mirrors the delay the Schedule card applies to its countdown: a user-entered custom delay
     * wins, otherwise the largest learned delay among the favourite sources. Returns null when
     * no delay applies (countdown == official air time).
     */
    fun effectiveDelayMinutes(
        uploadDelayEnabled: Boolean,
        customDelayMinutes: Long?,
        favoriteSourceIds: Set<String>,
        learnedDelays: Map<String, Long>,
    ): Long? {
        if (!uploadDelayEnabled) return null
        return customDelayMinutes ?: favoriteSourceIds.mapNotNull { learnedDelays[it] }.maxOrNull()
    }

    /** Epoch second at which the alert should fire: the same moment the card countdown hits zero. */
    fun notifyAtSeconds(officialAiringAt: Long, delayMinutes: Long?): Long =
        if (delayMinutes == null) officialAiringAt else officialAiringAt + delayMinutes * 60
}
