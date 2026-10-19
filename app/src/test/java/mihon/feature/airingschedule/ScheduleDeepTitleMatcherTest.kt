package mihon.feature.airingschedule

import mihon.feature.airingschedule.util.ScheduleDeepTitleMatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleDeepTitleMatcherTest {

    private fun match(a: String, b: String) = ScheduleDeepTitleMatcher.matches(a, b)

    @Test
    fun `deep cleaning matches like migration deep search`() {
        assertTrue(match("Tsuyokute New Saga (2024)", "Tsuyokute New Saga"))
        assertTrue(match("[Dub] Frieren: Beyond Journey's End", "Frieren - Beyond Journeys End"))
        assertTrue(
            match(
                "The Too-Perfect Saint: Tossed Aside by My Fiancé and Sold to Another Kingdom",
                "Too Perfect Saint Tossed Aside by My Fiance and Sold to Another Kingdom",
            ),
        )
        assertTrue(match("Shoujo Kageki Revue Starlight", "Shojo Kageki Revue Starlight"))
        assertTrue(match("Kyoukai no Kanata", "Kyōkai no Kanata"))
        assertTrue(match("So What's Wrong with Getting Reborn as a Goblin?", "So Whats Wrong With Getting Reborn As Goblin"))
    }

    @Test
    fun `small typos in long titles still match`() {
        assertTrue(
            match(
                "I Was Reincarnated as the 7th Prince so I Can Take My Time Perfecting My Magical Ability",
                "I Was Reincarnated as the 7th Prince so I Can Take My Time Perfecting My Magic Ability",
            ),
        )
        assertTrue(match("Mushoku Tensei: Jobless Reincarnation", "Mushoku Tensei Jobless Reincarnaton"))
    }

    @Test
    fun `season part and cour notations are normalized`() {
        assertTrue(match("Jujutsu Kaisen", "Jujutsu Kaisen Season 1"))
        assertTrue(match("Re:Zero kara Hajimeru Isekai Seikatsu 3rd Season", "Re:Zero kara Hajimeru Isekai Seikatsu Season 3"))
        assertTrue(match("Dr. Stone: Science Future Cour 2", "Dr. Stone: Science Future Part 2"))
        assertTrue(match("Overlord IV", "Overlord Season 4"))
        assertTrue(match("Mushoku Tensei Ⅲ: Isekai Ittara Honki Dasu", "Mushoku Tensei III: Isekai Ittara Honki Dasu"))
        assertTrue(match("Kingdom (Season 6)", "Kingdom 6th Season"))
    }

    @Test
    fun `different seasons parts and sequels never collide`() {
        assertFalse(match("Jujutsu Kaisen Season 1", "Jujutsu Kaisen Season 2"))
        assertFalse(match("Mob Psycho 100", "Mob Psycho 100 II"))
        assertFalse(match("Mob Psycho 100 II", "Mob Psycho 100 III"))
        assertFalse(match("Attack on Titan Final Season Part 1", "Attack on Titan Final Season Part 2"))
        assertFalse(match("Kaiju No. 8", "Kaiju No. 8 Season 2"))
        assertFalse(match("Kaiju No. 8", "Kaiju No. 9"))
        assertFalse(match("One Piece", "One Piece Film: Red"))
        assertFalse(match("Naruto", "Boruto"))
        assertFalse(match("Bleach", "Bleach: Thousand-Year Blood War"))
        assertFalse(
            match(
                "BLEACH: Thousand-Year Blood War - The Calamity -",
                "BLEACH: Thousand-Year Blood War - The Conflict -",
            ),
        )
        assertFalse(match("The Shy Snow Woman and the Cursed Ring", "The Lonely Snow Widow and the Cursed Ring"))
    }

    @Test
    fun `index resolves exact then deep then fuzzy`() {
        val index = ScheduleDeepTitleMatcher.Index(
            listOf(
                1L to listOf("Sousou no Frieren"),
                2L to listOf("Tsuyokute New Saga"),
                3L to listOf("Mushoku Tensei Jobless Reincarnaton"),
                4L to listOf("Mob Psycho 100"),
            ),
        )
        val exact = index.find(listOf("Sousou no Frieren (TV)"))
        assertEquals(listOf(1L), exact?.items)
        assertEquals(ScheduleDeepTitleMatcher.Tier.EXACT, exact?.tier)

        val deep = index.find(listOf("Tsuyokute New Saga (2024)"))
        assertEquals(listOf(2L), deep?.items)

        val fuzzy = index.find(listOf("Mushoku Tensei: Jobless Reincarnation"))
        assertEquals(listOf(3L), fuzzy?.items)
        assertEquals(ScheduleDeepTitleMatcher.Tier.FUZZY, fuzzy?.tier)

        assertNull(index.find(listOf("Mob Psycho 100 III")))
        assertNotNull(index.find(listOf("Mob Psycho 100")))
    }
}
