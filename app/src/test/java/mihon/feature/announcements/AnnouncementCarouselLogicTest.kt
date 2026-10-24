package mihon.feature.announcements

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnnouncementCarouselLogicTest {

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    private fun entry(id: Int, release: Long?, title: String = "Anime $id") = AnnouncementEntry(
        mediaId = id,
        title = title,
        category = AnnouncementCategory.NEW_SEASON,
        description = "",
        expectedYear = null,
        relatedAniListMediaId = null,
        relatedTitle = null,
        relatedYear = null,
        coverImageUrl = null,
        bannerImageUrl = null,
        exactReleaseDate = release,
        fetchedFrom = "AniList",
    )

    private fun ids(list: List<AnnouncementEntry>) = list.map { it.mediaId }

    @Test
    fun `seven pages when more than seven are eligible`() {
        val entries = (1..10).map { entry(it, now + it * hour) }
        ids(selectCarouselEntries(entries, now)) shouldBe (1..7).toList()
    }

    @Test
    fun `only available entries are shown when fewer than seven`() {
        val entries = (1..3).map { entry(it, now + it * hour) }
        ids(selectCarouselEntries(entries, now)) shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `no eligible entries gives an empty carousel`() {
        selectCarouselEntries(emptyList(), now) shouldBe emptyList()
        selectCarouselEntries(listOf(entry(1, null)), now) shouldBe emptyList()
    }

    @Test
    fun `earliest release comes first regardless of input order`() {
        val entries = listOf(entry(3, now + 3 * hour), entry(1, now + hour), entry(2, now + 2 * hour))
        ids(selectCarouselEntries(entries, now)) shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `entries without a release date are excluded`() {
        val entries = listOf(entry(1, null), entry(2, now + hour))
        ids(selectCarouselEntries(entries, now)) shouldBe listOf(2)
    }

    @Test
    fun `duplicates are removed`() {
        val entries = listOf(entry(1, now + hour), entry(1, now + hour), entry(2, now + 2 * hour))
        ids(selectCarouselEntries(entries, now)) shouldBe listOf(1, 2)
    }

    @Test
    fun `released entry stays for exactly 24 hours`() {
        val released = entry(1, now - day + 1)
        val expired = entry(2, now - day)
        ids(selectCarouselEntries(listOf(released, expired), now)) shouldBe listOf(1)
    }

    @Test
    fun `expired entry is removed and the queue is refilled`() {
        val entries = (1..8).map { entry(it, now + it * day) }
        ids(selectCarouselEntries(entries, now)) shouldBe (1..7).toList()
        // 1 day after anime 1 released + its 24h grace period.
        val later = now + day + CAROUSEL_GRACE_MS + 1
        ids(selectCarouselEntries(entries, later)) shouldBe (2..8).toList()
    }

    @Test
    fun `grace period is measured from the release timestamp not from first display`() {
        val entries = listOf(entry(1, now - 2 * day), entry(2, now + hour))
        ids(selectCarouselEntries(entries, now)) shouldBe listOf(2)
    }

    @Test
    fun `next update is the earliest release or grace-period boundary`() {
        val carousel = listOf(entry(1, now + 5 * hour), entry(2, now + hour))
        nextCarouselUpdate(carousel, now) shouldBe now + hour
        nextCarouselUpdate(carousel, now + hour + 1) shouldBe now + 2 * hour
        nextCarouselUpdate(emptyList(), now) shouldBe null
    }

    @Test
    fun `next update skips boundaries that have already passed`() {
        val carousel = listOf(entry(1, now - hour))
        nextCarouselUpdate(carousel, now) shouldBe now - hour + CAROUSEL_GRACE_MS
    }

    @Test
    fun `selection is kept when the anime is still present`() {
        val entries = listOf(entry(2, now + hour), entry(3, now + 2 * hour))
        reconcileCarouselIndex(selectedId = 3, selectedIndex = 2, entries = entries) shouldBe 1
    }

    @Test
    fun `selection falls back to a valid index when the anime was removed`() {
        val entries = listOf(entry(2, now + hour), entry(3, now + 2 * hour))
        reconcileCarouselIndex(selectedId = 1, selectedIndex = 0, entries = entries) shouldBe 0
        reconcileCarouselIndex(selectedId = 9, selectedIndex = 6, entries = entries) shouldBe 1
        reconcileCarouselIndex(selectedId = 9, selectedIndex = 0, entries = emptyList()) shouldBe 0
    }

    @Test
    fun `pages wrap in both directions`() {
        val start = carouselStartPage(7)
        carouselIndexForPage(start, 7) shouldBe 0
        carouselIndexForPage(start + 7, 7) shouldBe 0
        carouselIndexForPage(start - 1, 7) shouldBe 6
        carouselIndexForPage(carouselStartPage(7, 3) + 4, 7) shouldBe 0
    }

    @Test
    fun `target page keeps the same index near the middle and recenters near the edges`() {
        carouselTargetPage(carouselStartPage(5) + 2, 5, 4) shouldBe carouselStartPage(5, 4)
        carouselTargetPage(0, 4, 1) shouldBe carouselStartPage(4, 1)
        carouselTargetPage(10, 1, 0) shouldBe 0
        carouselPageCount(1) shouldBe 1
        carouselPageCount(0) shouldBe 0
        carouselPageCount(3) shouldBe CAROUSEL_VIRTUAL_PAGES
    }
}
