package mihon.feature.airingschedule

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleManualPicksTest {

    private val goblin = AiringScheduleEntry(
        scheduleId = 1,
        airingAt = 0L,
        episode = 4,
        mediaId = 1001,
        titleUserPreferred = "So What's Wrong with Getting Reborn as a Goblin?",
        titleRomaji = "Goblin Tensei",
    )

    private fun pickFor(entry: AiringScheduleEntry, sourceId: Long, url: String, pickedAt: Long = 1L) =
        ScheduleManualPicks.withPick(
            emptyMap(),
            SchedulePickRequest.from(entry),
            ScheduleManualPick(sourceId = sourceId, url = url, title = "Picked", pickedAt = pickedAt),
        )

    /** Library where the automatic title match points at a different (wrong) anime, id 10. */
    private fun indexWithWrongAutoMatch(
        picks: Map<String, ScheduleManualPick>,
        pickRows: Map<String, ScheduleManualPickRow>,
    ): ScheduleLibraryIndex {
        val keys = mihon.feature.airingschedule.util.ScheduleTitleMatcher
            .normalizedKeys(goblin.titleUserPreferred)
        return ScheduleLibraryIndex(
            titles = keys,
            sourcesByTitle = keys.associateWith { setOf("111") },
            idByTitle = keys.associateWith { 10L },
            manualPicks = picks,
            pickRows = pickRows,
        )
    }

    @Test
    fun `automatic title match is used when there is no manual pick`() {
        val match = indexWithWrongAutoMatch(emptyMap(), emptyMap()).resolve(goblin)
        assertEquals(10L, match.animeId)
        assertTrue(match.inLibrary)
        assertNull(match.manualPick)
    }

    @Test
    fun `manual pick in library overrides the automatic match`() {
        val picks = pickFor(goblin, sourceId = 222L, url = "/anime/goblin")
        val rows = mapOf(ScheduleManualPicks.identityOf(222L, "/anime/goblin") to ScheduleManualPickRow(42L, true))
        val match = indexWithWrongAutoMatch(picks, rows).resolve(goblin)
        assertEquals(42L, match.animeId)
        assertTrue(match.inLibrary)
        assertEquals(setOf("222"), match.sources)
    }

    @Test
    fun `manual pick not in library still wins and is opened`() {
        val picks = pickFor(goblin, sourceId = 222L, url = "/anime/goblin")
        val rows = mapOf(ScheduleManualPicks.identityOf(222L, "/anime/goblin") to ScheduleManualPickRow(42L, false))
        val match = indexWithWrongAutoMatch(picks, rows).resolve(goblin)
        assertEquals(42L, match.animeId)
        assertFalse(match.inLibrary)
        assertEquals("/anime/goblin", match.manualPick?.url)
    }

    @Test
    fun `manual pick follows the anime to a new id after re-adding`() {
        val picks = pickFor(goblin, sourceId = 222L, url = "/anime/goblin")
        // Row recreated (e.g. after removal + database cleanup + re-add) with a new id.
        val rows = mapOf(ScheduleManualPicks.identityOf(222L, "/anime/goblin") to ScheduleManualPickRow(99L, true))
        assertEquals(99L, indexWithWrongAutoMatch(picks, rows).resolve(goblin).animeId)
    }

    @Test
    fun `missing pick row never falls back to the automatic match`() {
        val picks = pickFor(goblin, sourceId = 222L, url = "/anime/goblin")
        val match = indexWithWrongAutoMatch(picks, emptyMap()).resolve(goblin)
        assertNull(match.animeId)
        assertFalse(match.inLibrary)
        assertEquals(222L, match.manualPick?.sourceId)
    }

    @Test
    fun `pick is found by title when the schedule source changes the media id`() {
        val picks = pickFor(goblin, sourceId = 222L, url = "/anime/goblin")
        val liveChartEntry = goblin.copy(mediaId = -555, titleRomaji = null)
        assertEquals("/anime/goblin", ScheduleManualPicks.find(picks, liveChartEntry)?.url)
    }

    @Test
    fun `pick for one entry does not leak to an unrelated entry`() {
        val picks = pickFor(goblin, sourceId = 222L, url = "/anime/goblin")
        val other = AiringScheduleEntry(
            scheduleId = 2,
            airingAt = 0L,
            episode = 3,
            mediaId = 2002,
            titleUserPreferred = "PSYREN",
        )
        assertNull(ScheduleManualPicks.find(picks, other))
    }

    @Test
    fun `newer pick replaces the older one and survives encoding`() {
        val first = pickFor(goblin, sourceId = 222L, url = "/anime/old")
        val second = ScheduleManualPicks.withPick(
            first,
            SchedulePickRequest.from(goblin),
            ScheduleManualPick(sourceId = 333L, url = "/anime/new", pickedAt = 2L),
        )
        val decoded = ScheduleManualPicks.decode(ScheduleManualPicks.encode(second))
        assertEquals(1, decoded.size)
        assertEquals("/anime/new", ScheduleManualPicks.find(decoded, goblin)?.url)
        assertEquals(333L, ScheduleManualPicks.find(decoded, goblin)?.sourceId)
    }

    @Test
    fun `corrupt stored value decodes to no picks`() {
        assertTrue(ScheduleManualPicks.decode("not json").isEmpty())
        assertTrue(ScheduleManualPicks.decode("").isEmpty())
    }
}
