package eu.kanade.util

import tachiyomi.domain.anime.model.Anime
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression and duplicate-prevention tests based on prompt-file requirements
 * (step 15: basic duplicate, pagination overlap, different providers, missing IDs, stable keys).
 */
class DuplicatePreventionTest {

    private fun makeAnime(id: Long, source: Long, url: String = "http://test/$id/$source") = Anime(
        id = id,
        source = source,
        url = url,
        favorite = false,
        lastUpdate = 0L,
        nextUpdate = 0L,
        fetchInterval = 0,
        dateAdded = 0L,
        viewerFlags = 0L,
        episodeFlags = 0L,
        coverLastModified = 0L,
        ogTitle = "Anime $id from $source",
        ogArtist = null,
        ogAuthor = null,
        ogThumbnailUrl = null,
        ogDescription = null,
        ogGenre = null,
        ogStatus = 0L,
        updateStrategy = tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
        initialized = false,
        lastModifiedAt = 0L,
        favoriteModifiedAt = null,
        version = 0L,
        parentId = null,
        seasonNumber = null,
        seasonOrder = null,
    )

    @Test
    fun basicDuplicate_isRemoved() {
        val a = makeAnime(999, 1)
        val list = listOf(a, a)
        val unique = DuplicatePrevention.deduplicateAnimeList(list)
        assertEquals(1, unique.size)
        assertEquals("1:999", DuplicatePrevention.canonicalAnimeIdentity(unique[0]))
    }

    @Test
    fun manyDuplicates_collapsed() {
        val a = makeAnime(100, 1)
        val b = makeAnime(200, 2)
        val c = makeAnime(300, 3)
        val list = listOf(a, a, a, b, b, c)
        val unique = DuplicatePrevention.deduplicateAnimeList(list)
        assertEquals(3, unique.size)
    }

    @Test
    fun sameIdDifferentSource_remainsSeparate() {
        val a = makeAnime(999, 1)
        val b = makeAnime(999, 2)
        val list = listOf(a, b)
        val unique = DuplicatePrevention.deduplicateAnimeList(list)
        assertEquals(2, unique.size)
    }

    @Test
    fun paginationOverlap_noDuplication() {
        val a = makeAnime(1, 1)
        val b = makeAnime(2, 1)
        val c = makeAnime(3, 1)
        val d = makeAnime(2, 1) // overlap with page 2
        val page1 = listOf(a, b, c)
        val page2 = listOf(c, d)
        val combined = page1 + page2
        val unique = DuplicatePrevention.deduplicateAnimeList(combined)
        assertEquals(4, unique.size) // A, B, C, D
        assertEquals(listOf(1L, 2L, 3L, 4L).map { it }.toString(), unique.map { it.id }.joinToString())
    }

    @Test
    fun regression_gs999_duplicateNotCrash() {
        // Reproduce the crash scenario: same numeric ID from different providers
        val anidb = makeAnime(999, 1, "https://anidb/anime/999")
        val anizone = makeAnime(999, 2, "https://anizone/anime/999")
        val list = listOf(anidb, anizone)
        // Before fix: both would have key "gs-999" causing crash
        // After fix: keys are "gs-1-999" and "gs-2-999"
        val keys = list.map { DuplicatePrevention.composeKeyForAnime(it) }
        assertTrue("Keys must be unique for different sources", keys.distinct().size == keys.size)
        assertEquals("gs-1-999", keys[0])
        assertEquals("gs-2-999", keys[1])
    }

    @Test
    fun missingId_fallbackNotEmptyKey() {
        val anime = Anime.create()
        val identity = DuplicatePrevention.canonicalAnimeIdentity(anime)
        assertFalse("Identity should not be blank for default anime", identity.isBlank())
        assertTrue("Identity should contain source info", identity.contains("unknown"))
    }

    @Test
    fun stableKeys_acrossRecomposition() {
        val a = makeAnime(42, 5)
        val key1 = DuplicatePrevention.composeKeyForAnime(a)
        val key2 = DuplicatePrevention.composeKeyForAnime(a)
        assertEquals("Keys must remain stable", key1, key2)
        assertEquals("gs-5-42", key1)
    }
}
