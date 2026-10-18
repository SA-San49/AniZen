package mihon.feature.airingschedule.notification

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mihon.feature.airingschedule.AiringScheduleEntry
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.io.IOException

/**
 * Looks up the airing status and *next* airing episode of specific AniList media, independent of
 * the weekly schedule window. This is what lets a "notify every episode" subscription keep
 * working after the weekly schedule rolls over.
 */
class AniListNextEpisodeLookup {

    private val networkHelper: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    data class MediaAiringInfo(
        val mediaId: Int,
        val status: String?,
        val totalEpisodes: Int?,
        val nextEpisode: AiringScheduleEntry?,
    )

    /** Fetches info for every id (AniList ids only, i.e. > 0), batched 50 per request. */
    suspend fun fetch(mediaIds: Collection<Int>): Map<Int, MediaAiringInfo> = withIOContext {
        mediaIds.filter { it > 0 }.distinct().chunked(PAGE_SIZE)
            .flatMap { fetchChunk(it) }
            .associateBy { it.mediaId }
    }

    private suspend fun fetchChunk(ids: List<Int>): List<MediaAiringInfo> {
        val payload = buildJsonObject {
            put("query", QUERY)
            putJsonObject("variables") {
                putJsonArray("ids") { ids.forEach { add(it) } }
                put("perPage", PAGE_SIZE)
            }
        }
        with(json) {
            val response = networkHelper.client.newCall(
                POST(API_URL, body = payload.toString().toRequestBody(jsonMime)),
            ).await()
            response.use {
                val result = try {
                    it.parseAs<LookupResponse>()
                } catch (e: Exception) {
                    if (!it.isSuccessful) throw HttpException(it.code)
                    throw e
                }
                if (!it.isSuccessful) throw HttpException(it.code)
                if (!result.errors.isNullOrEmpty()) {
                    throw IOException("AniList GraphQL error: " + result.errors.mapNotNull { e -> e.message }.joinToString("; "))
                }
                val media = result.data?.page?.media ?: throw IOException("AniList response missing Page data")
                return media.map { it.toInfo() }
            }
        }
    }

    private fun LookupMedia.toInfo(): MediaAiringInfo {
        val next = nextAiringEpisode?.let { n ->
            AiringScheduleEntry(
                scheduleId = n.id,
                airingAt = n.airingAt.toLong(),
                episode = n.episode,
                mediaId = id,
                titleUserPreferred = title?.userPreferred.orEmpty(),
                titleEnglish = title?.english,
                titleRomaji = title?.romaji,
                titleNative = title?.native,
                coverImageUrl = coverImage?.large.orEmpty(),
                totalEpisodes = episodes,
                averageScore = averageScore,
                format = format,
                status = status,
                isAdult = isAdult ?: false,
                genres = genres.orEmpty(),
            )
        }
        return MediaAiringInfo(mediaId = id, status = status, totalEpisodes = episodes, nextEpisode = next)
    }

    companion object {
        private const val API_URL = "https://graphql.anilist.co/"
        private const val PAGE_SIZE = 50
        private const val QUERY =
            "query NextAiring(\$ids:[Int],\$perPage:Int){Page(page:1,perPage:\$perPage){media(id_in:\$ids,type:ANIME){id status episodes averageScore format isAdult genres title{userPreferred english romaji native}coverImage{large}nextAiringEpisode{id airingAt episode}}}}"
    }
}

@Serializable
private data class LookupResponse(
    val data: LookupData? = null,
    val errors: List<LookupError>? = null,
)

@Serializable
private data class LookupError(val message: String? = null)

@Serializable
private data class LookupData(@SerialName("Page") val page: LookupPage? = null)

@Serializable
private data class LookupPage(val media: List<LookupMedia> = emptyList())

@Serializable
private data class LookupMedia(
    val id: Int,
    val status: String? = null,
    val episodes: Int? = null,
    val averageScore: Int? = null,
    val format: String? = null,
    val isAdult: Boolean? = null,
    val genres: List<String>? = null,
    val title: LookupTitle? = null,
    val coverImage: LookupCover? = null,
    val nextAiringEpisode: LookupNextAiring? = null,
)

@Serializable
private data class LookupTitle(
    val userPreferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
private data class LookupCover(val large: String? = null)

@Serializable
private data class LookupNextAiring(
    val id: Int = 0,
    val airingAt: Int = 0,
    val episode: Int = 0,
)
