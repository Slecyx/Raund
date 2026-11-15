package app.nightwatch.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerDashboardTest {
    private fun match(id: String, map: String, won: Boolean, rr: Int?, startedAt: Long, agent: String = "agent") =
        MatchSummary(id, map, "Competitive", won, "13-8", if(won) 20 else 8, 10, 4, rr, startedAt,
            detailsJson = "{}", agentId = agent)

    @Test fun calculatesStreakRrCurveAndStrongMapsWithoutInventingMatches() {
        val day = 24 * 60 * 60 * 1000L
        val now = 20L * day
        val result = CareerDashboardAnalysis.calculate(listOf(
            match("3", "Ascent", true, 20, now - day),
            match("2", "Ascent", true, 18, now - 2 * day),
            match("1", "Bind", false, -12, now - 9 * day)
        ), now)
        assertEquals(2, result.streak)
        assertEquals(26, result.rrNet)
        assertEquals(listOf(0, -12, 6, 26), result.rrCurve)
        assertEquals("Ascent", result.maps.single().key)
        assertEquals(100, result.maps.single().winRate)
        assertEquals(2, result.lastSeven.matches)
        assertTrue(result.dayParts.isNotEmpty())
    }
}
