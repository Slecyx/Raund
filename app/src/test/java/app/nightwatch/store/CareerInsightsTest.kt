package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test

class CareerInsightsTest {
    private fun match(id: Int, won: Boolean, k: Int, d: Int) = MatchSummary(id.toString(), "Map", "Competitive", won, "13-9", k, d, 2, null, id.toLong(),
        """{"players":[{"subject":"me","stats":{"score":2600,"roundsPlayed":10}}],"roundResults":[{"playerStats":[{"subject":"me","kills":[],"damage":[{"damage":100}]}]}]}""", "me", "")
    @Test fun comparesTwoEqualWindows() {
        val trend = CareerInsights.trend((0 until 10).map { match(it, it < 4, if(it < 5) 15 else 8, 10) })!!
        assertEquals(5, trend.recent.matches); assertTrue(trend.recent.kd > trend.previous.kd)
    }
    @Test fun avoidsClaimsWithTooFewMatches() { assertNull(CareerInsights.trend((0 until 5).map { match(it,true,10,10) })) }
    @Test fun evaluationFlagsOpeningDeaths() {
        val lines = CareerInsights.evaluation(match(1,false,5,10), MatchMetrics(100,100,20,1000,10),
            AdvancedMatchMetrics(10,0,4,2,0,4,0,1,0,0,1200,1,3,1,0,0), true)
        assertTrue(lines.any { "ilk ölüm" in it })
    }
}
