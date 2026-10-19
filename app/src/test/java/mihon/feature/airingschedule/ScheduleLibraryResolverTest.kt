package mihon.feature.airingschedule

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.airingschedule.match.ScheduleLibraryRef
import mihon.feature.airingschedule.match.ScheduleLibraryResolver
import mihon.feature.airingschedule.match.ScheduleManualMatch
import mihon.feature.airingschedule.match.ScheduleManualMatchIndex
import mihon.feature.airingschedule.match.ScheduleMatchKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleLibraryResolverTest {

    private val aniListEntry = AiringScheduleEntry(
        scheduleId = 10,
        airingAt = 1000L,
        episode = 3,
        mediaId = 171000,
        titleUserPreferred = "Mibōjin Yukionna to Noroi no Yubiwa",
        titleEnglish = "The Shy Snow Woman and the Cursed Ring",
        titleRomaji = "Mibōjin Yukionna to Noroi no Yubiwa",
    )

    // The same show as LiveChart reports it (single English title, negative id).
    private val liveChartEntry = AiringScheduleEntry(
        scheduleId = 99,
        airingAt = 1000L,
        episode = 3,
        mediaId = -12999,
        titleUserPreferred = "The Shy Snow Woman and the Cursed Ring",
        titleEnglish = "The Shy Snow Woman and the Cursed Ring",
        titleRomaji = "The Shy Snow Woman and the Cursed Ring",
    )

    private val wrongTitleMatch = ScheduleLibraryRef(1, sourceId = 100, url = "/a/shy-snow", titles = listOf("The Shy Snow Woman and the Cursed Ring"))
    private val userPick = ScheduleLibraryRef(2, sourceId = 200, url = "/anime/lonely-snow-widow", titles = listOf("The Lonely Snow Widow and the Cursed Ring"))

    private fun pick(entry: AiringScheduleEntry, ref: ScheduleLibraryRef, existing: List<ScheduleManualMatch> = emptyList()): List<ScheduleManualMatch> =
        ScheduleManualMatchIndex.upsert(
            existing,
            ScheduleMatchKeys.bindingRequest(entry, entry.titleUserPreferred),
            ScheduleManualMatch(ref.sourceId, ref.url, ref.animeId, ref.titles.first(), updatedAt = 1L),
        )

    @Test
    fun `manual pick overrides automatic title match`() {
        val auto = ScheduleLibraryResolver(listOf(wrongTitleMatch, userPick)).resolve(aniListEntry)
        assertEquals(1L, auto?.animeId)
        assertFalse(auto!!.isManual)

        val manual = ScheduleLibraryResolver(listOf(wrongTitleMatch, userPick), ScheduleManualMatchIndex(pick(aniListEntry, userPick)))
            .resolve(aniListEntry)!!
        assertEquals(2L, manual.animeId)
        assertTrue(manual.isManual)
        assertTrue(manual.inLibrary)
        assertEquals(setOf(200L), manual.sourceIds)
    }

    @Test
    fun `manual pick survives removing and re-adding to library`() {
        val matches = ScheduleManualMatchIndex(pick(aniListEntry, userPick))

        // Removed from library: still opens the picked anime, never falls back to the title match.
        val removed = ScheduleLibraryResolver(listOf(wrongTitleMatch), matches).resolve(aniListEntry)!!
        assertEquals(2L, removed.animeId)
        assertTrue(removed.isManual)
        assertFalse(removed.inLibrary)

        // Re-added (even with a new database id): found again by source + url.
        val readded = userPick.copy(animeId = 777)
        val back = ScheduleLibraryResolver(listOf(wrongTitleMatch, readded), matches).resolve(aniListEntry)!!
        assertEquals(777L, back.animeId)
        assertTrue(back.inLibrary)
        assertTrue(back.isManual)
    }

    @Test
    fun `manual pick works for both AniList and LiveChart`() {
        val pickedOnAniList = ScheduleManualMatchIndex(pick(aniListEntry, userPick))
        val onLiveChart = ScheduleLibraryResolver(listOf(wrongTitleMatch, userPick), pickedOnAniList).resolve(liveChartEntry)!!
        assertEquals(2L, onLiveChart.animeId)
        assertTrue(onLiveChart.isManual)

        val pickedOnLiveChart = ScheduleManualMatchIndex(pick(liveChartEntry, userPick))
        val onAniList = ScheduleLibraryResolver(listOf(wrongTitleMatch, userPick), pickedOnLiveChart).resolve(aniListEntry)!!
        assertEquals(2L, onAniList.animeId)
        assertTrue(onAniList.isManual)
    }

    @Test
    fun `new pick replaces the old one and keeps provider ids`() {
        val other = ScheduleLibraryRef(3, sourceId = 300, url = "/x", titles = listOf("Something Else"))
        val first = pick(liveChartEntry, other)
        val second = pick(aniListEntry, userPick, first)
        assertEquals(1, second.size)
        assertEquals("/anime/lonely-snow-widow", second.single().url)
        assertTrue("anilist:171000" in second.single().mediaKeys)
        assertTrue("livechart:12999" in second.single().mediaKeys)
    }

    @Test
    fun `unrelated entries are unaffected and unmatched entries resolve to null`() {
        val other = AiringScheduleEntry(
            scheduleId = 11,
            airingAt = 1000L,
            episode = 4,
            mediaId = 5,
            titleUserPreferred = "PSYREN",
        )
        val resolver = ScheduleLibraryResolver(listOf(wrongTitleMatch, userPick), ScheduleManualMatchIndex(pick(aniListEntry, userPick)))
        assertNull(resolver.resolve(other))
    }

    @Test
    fun `pinned source decides which library copy opens`() {
        val a = ScheduleLibraryRef(10, sourceId = 1, url = "/1", titles = listOf("PSYREN"))
        val b = ScheduleLibraryRef(20, sourceId = 2, url = "/2", titles = listOf("Psyren"))
        val entry = AiringScheduleEntry(1, 0L, 1, 7, "PSYREN")
        assertEquals(20L, ScheduleLibraryResolver(listOf(a, b), pinnedSourceIds = listOf(2L, 1L)).resolve(entry)?.animeId)
        assertEquals(setOf(1L, 2L), ScheduleLibraryResolver(listOf(a, b)).resolve(entry)?.sourceIds)
    }

    @Test
    fun `manual matches round-trip through json`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val list = pick(aniListEntry, userPick)
        val decoded = json.decodeFromString<List<ScheduleManualMatch>>(json.encodeToString(list))
        assertEquals(list, decoded)
    }
}
