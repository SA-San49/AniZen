package mihon.feature.airingschedule

import mihon.feature.airingschedule.notification.AiringAlertPolicy
import mihon.feature.airingschedule.notification.AiringAlertPolicy.SeriesDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiringAlertPolicyTest {

    @Test
    fun `series keeps scheduling while a next episode is known`() {
        assertEquals(SeriesDecision.SCHEDULE, AiringAlertPolicy.seriesDecision("RELEASING", 7))
        assertEquals(SeriesDecision.SCHEDULE, AiringAlertPolicy.seriesDecision("NOT_YET_RELEASED", 1))
        assertEquals(SeriesDecision.SCHEDULE, AiringAlertPolicy.seriesDecision(null, 3))
    }

    @Test
    fun `series waits on hiatus or unknown next airing instead of ending`() {
        assertEquals(SeriesDecision.WAIT, AiringAlertPolicy.seriesDecision("RELEASING", null))
        assertEquals(SeriesDecision.WAIT, AiringAlertPolicy.seriesDecision("HIATUS", null))
        assertEquals(SeriesDecision.WAIT, AiringAlertPolicy.seriesDecision(null, null))
    }

    @Test
    fun `series ends once finished or cancelled`() {
        assertEquals(SeriesDecision.END, AiringAlertPolicy.seriesDecision("FINISHED", null))
        assertEquals(SeriesDecision.END, AiringAlertPolicy.seriesDecision("CANCELLED", null))
        assertEquals(SeriesDecision.END, AiringAlertPolicy.seriesDecision("finished", 12))
    }

    @Test
    fun `final episode detection`() {
        assertTrue(AiringAlertPolicy.isFinalEpisode(12, 12))
        assertTrue(AiringAlertPolicy.isFinalEpisode(13, 12))
        assertFalse(AiringAlertPolicy.isFinalEpisode(11, 12))
        assertFalse(AiringAlertPolicy.isFinalEpisode(12, null))
        assertFalse(AiringAlertPolicy.isFinalEpisode(1, 0))
    }

    @Test
    fun `delay matches the schedule card countdown`() {
        val learned = mapOf("1" to 30L, "2" to 90L)
        assertNull(AiringAlertPolicy.effectiveDelayMinutes(false, 45L, setOf("1"), learned))
        assertEquals(45L, AiringAlertPolicy.effectiveDelayMinutes(true, 45L, setOf("1", "2"), learned))
        assertEquals(90L, AiringAlertPolicy.effectiveDelayMinutes(true, null, setOf("1", "2"), learned))
        assertNull(AiringAlertPolicy.effectiveDelayMinutes(true, null, setOf("3"), learned))
    }

    @Test
    fun `notify time is official time plus delay`() {
        assertEquals(1_000L, AiringAlertPolicy.notifyAtSeconds(1_000L, null))
        assertEquals(1_000L + 90 * 60, AiringAlertPolicy.notifyAtSeconds(1_000L, 90L))
        assertEquals(1_000L - 10 * 60, AiringAlertPolicy.notifyAtSeconds(1_000L, -10L))
    }
}
