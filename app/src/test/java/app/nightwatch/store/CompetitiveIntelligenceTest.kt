package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test

class CompetitiveIntelligenceTest {
    private val detail = """{"players":[
      {"subject":"me","gameName":"Me","tagLine":"TR1","teamId":"Blue","stats":{"kills":10,"deaths":8,"assists":3}},
      {"subject":"duo","gameName":"Duo","tagLine":"123","teamId":"Blue","stats":{"kills":14,"deaths":7,"assists":5}},
      {"subject":"enemy","gameName":"Enemy","tagLine":"EUW","teamId":"Red","stats":{"kills":8,"deaths":12,"assists":2}}],
      "teams":[{"teamId":"Blue","won":true},{"teamId":"Red","won":false}],"roundResults":[]}"""
    private fun match() = MatchSummary("1", "Ascent", "Competitive", true, "13-8", 10, 8, 3, 20, 1,
        detail, "me", "agent")

    @Test fun findsKnownPlayersAndDuosFromCompetitiveMatches() {
        val result = CareerPeopleAnalysis.calculate(listOf(match()))
        assertEquals(2, result.players.size)
        assertEquals("Duo#123", result.duos.single().riotId)
        assertEquals(100, result.duos.single().winRate)
        assertEquals(100, result.ownWinRate)
    }

    @Test fun coachReturnsReliableFallbackWhenRoundsAreMissing() {
        val goals = CompetitiveCoachAnalysis.goals(listOf(match()))
        assertTrue(goals.isNotEmpty())
        assertEquals("maintain", goals.single().key)
    }
}
