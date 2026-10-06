package eu.kanade.util

import tachiyomi.domain.anime.model.Anime
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Centralized duplicate-prevention system for AniZen.
 *
 * Based on prompt-file requirements (step 3, 4, 6, 14):
 * - Canonical identity includes provider (source) + entity ID.
 * - Deduplication uses LinkedHashMap for deterministic first-wins ordering.
 * - Defensive assertions only in development; production uses safe dedup.
 */

object DuplicatePrevention {

    /**
     * Canonical identity for an [Anime] based on existing architecture.
     * Reuses `Anime.source` (provider ID) and `Anime.id` (entity ID).
     */
    fun canonicalAnimeIdentity(anime: Anime): String {
        val sourceId = when (anime.source) {
            -1L -> "unknown"
            else -> anime.source.toString()
        }
        val entityId = when (anime.id) {
            -1L -> {
                // Fallback for uninitialized/default anime: derive from URL if available
                if (anime.url.isNotBlank()) {
                    anime.url.hashCode().toString()
                } else {
                    "null"
                }
            }
            else -> anime.id.toString()
        }
        return "$sourceId:${entityId}"
    }

    /**
     * Deduplicate a list of [Anime] objects.
     * Uses [LinkedHashMap] to preserve first-seen stable ordering (step 4).
     * First record wins; duplicates are ignored but logged in debug mode.
     */
    fun deduplicateAnimeList(animes: List<Anime>): List<Anime> {
        val unique = LinkedHashMap<String, Anime>(animes.size)
        for (anime in animes) {
            val id = canonicalAnimeIdentity(anime)
            if (id.isNotBlank() && !id.endsWith(":null")) {
                if (!unique.containsKey(id)) {
                    unique[id] = anime
                } else {
                    // Debug-only logging: detect duplicate without crashing production
                    logcat(LogPriority.DEBUG, tag = "DuplicatePrevention") {
                        "Duplicate detected: entityType=anime " +
                        "canonicalId=$id source=${anime.source} id=${anime.id} " +
                        "url=${anime.url} action=ignored"
                    }
                }
            } else {
                // For items with invalid identity, keep them but log
                unique["fallback-${anime.hashCode()}-${anime.url.hashCode()}"] = anime
            }
        }
        return unique.values.toList()
    }

    /**
     * Defensive assertion concept (step 14): verify no duplicate keys exist
     * in the given list. Returns `true` if all keys are unique.
     * This is lightweight enough to call before Compose rendering in debug builds.
     */
    fun hasNoDuplicateKeys(animes: List<Anime>): Boolean {
        val keys = animes.map { canonicalAnimeIdentity(it) }
        return keys.distinct().size == keys.size
    }

    /**
     * Generate a stable Compose key for an [Anime] that includes source + id.
     * This fixes the `gs-999` crash by ensuring identity is globally unique.
     */
    fun composeKeyForAnime(anime: Anime): String {
        return "gs-${anime.source}-${anime.id}"
    }

    /**
     * Generate a stable Compose key for search/card row items.
     * Uses the same canonical identity pattern.
     */
    fun composeKeyForSearchItem(anime: Anime): String {
        return "search-${anime.source}-${anime.id}"
    }
}
