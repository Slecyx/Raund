package app.nightwatch.store

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdvancedMatchAnalysisTest {
    private fun match(tradeTime: Long) = JSONObject("""{
      "players":[{"subject":"me","teamId":"Blue"},{"subject":"friend","teamId":"Blue"},{"subject":"enemy","teamId":"Red"}],
      "roundResults":[{"bombPlanter":"me","playerStats":[
        {"subject":"me","kills":[],"damage":[{"receiver":"enemy","damage":100,"headshots":1,"bodyshots":2,"legshots":1}],"economy":{"spent":3000}},
        {"subject":"enemy","kills":[{"killer":"enemy","victim":"me","timeSinceRoundStartMillis":1000}],"damage":[{"receiver":"me","damage":150}]},
        {"subject":"friend","kills":[{"killer":"friend","victim":"enemy","timeSinceRoundStartMillis":$tradeTime}]}
      ]}]}""")
    @Test fun tradeBoundaryAndDamage() {
        val m = AdvancedMatchAnalysis.calculate(match(6000), "me")
        assertEquals(1, m.firstDeaths)
        assertEquals(0, m.firstKills)
        assertEquals(1, m.tradedDeaths)
        assertEquals(100, m.kastPercent)
        assertEquals(150, m.damageReceived)
        assertEquals(1, m.headHits)
        assertEquals(2, m.bodyHits)
        assertEquals(1, m.legHits)
        assertEquals(3000, m.creditsSpent)
        assertEquals(1, m.plants)
    }
    @Test fun laterKillIsNotATrade() {
        val m = AdvancedMatchAnalysis.calculate(match(6001), "me")
        assertEquals(0, m.tradedDeaths)
        assertEquals(0, m.kastPercent)
    }
    @Test fun missingSubjectDoesNotInventStats() {
        val m = AdvancedMatchAnalysis.calculate(match(2000), "absent")
        assertEquals(0, m.rounds)
        assertNull(m.kastPercent)
    }
    @Test fun survivorCountsTowardKast() {
        val m = AdvancedMatchAnalysis.calculate(JSONObject("""{"roundResults":[{"playerStats":[{"subject":"me","kills":[]}]}]}"""), "me")
        assertEquals(1, m.survived)
        assertEquals(100, m.kastPercent)
        assertEquals(0, m.economyRounds)
    }
    @Test fun friendlyDamageIsExcludedFromDealtAndReceived() {
        val raw = JSONObject("""{"players":[{"subject":"me","teamId":"Blue"},{"subject":"friend","teamId":"Blue"},{"subject":"enemy","teamId":"Red"}],
          "roundResults":[{"playerStats":[{"subject":"me","damage":[{"receiver":"friend","damage":20},{"receiver":"enemy","damage":100}]},
          {"subject":"friend","damage":[{"receiver":"me","damage":40}]},{"subject":"enemy","damage":[{"receiver":"me","damage":120}]}]}]}""")
        assertEquals(120, AdvancedMatchAnalysis.calculate(raw, "me").damageReceived)
        assertEquals(100, MatchAnalysis.metrics(raw, "me").damage)
    }
}
