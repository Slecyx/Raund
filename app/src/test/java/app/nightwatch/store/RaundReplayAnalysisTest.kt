package app.nightwatch.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaundReplayAnalysisTest {
    @Test fun classifiesBuyClutchFirstDuelAndMapPointFromRecordedData() {
        val json = JSONObject("""
          {"players":[
            {"subject":"p","teamId":"Red"},{"subject":"a","teamId":"Red"},{"subject":"b","teamId":"Red"},
            {"subject":"c","teamId":"Red"},{"subject":"d","teamId":"Red"},{"subject":"e1","teamId":"Blue"}
          ],"roundResults":[{"roundNum":0,"winningTeam":"Red","playerStats":[
            {"subject":"p","economy":{"loadoutValue":5000},"damage":[{"damage":150}],"kills":[
              {"killer":"p","victim":"e1","timeSinceRoundStartMillis":1000,"victimLocation":{"x":200,"y":100}}]},
            {"subject":"a","economy":{"loadoutValue":5000},"kills":[]},
            {"subject":"b","economy":{"loadoutValue":5000},"kills":[]},
            {"subject":"c","economy":{"loadoutValue":5000},"kills":[]},
            {"subject":"d","economy":{"loadoutValue":5000},"kills":[]},
            {"subject":"e1","economy":{"loadoutValue":5000},"kills":[
              {"killer":"e1","victim":"a","timeSinceRoundStartMillis":2000},
              {"killer":"e1","victim":"b","timeSinceRoundStartMillis":3000},
              {"killer":"e1","victim":"c","timeSinceRoundStartMillis":4000},
              {"killer":"e1","victim":"d","timeSinceRoundStartMillis":5000}]}
          ]}]}
        """)
        val replay = RaundReplayAnalysis.calculate(json, "p", .001, .001)
        val round = replay.rounds.single()
        assertEquals("full", round.buy)
        assertTrue(round.firstKill)
        assertTrue(round.clutch)
        assertEquals(ReplayPointType.KILL, replay.points.single().type)
        assertEquals(.1f, replay.points.single().x, .001f)
        assertEquals(.2f, replay.points.single().y, .001f)
    }
}
