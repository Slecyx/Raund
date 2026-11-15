package app.nightwatch.store
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CurrentRankTest {
    @Test fun selectsCurrentActInsteadOfOldMatch() {
        val mmr = JSONObject("""{"QueueSkills":{"competitive":{"SeasonalInfoBySeasonID":{"old":{"CompetitiveTier":15,"RankedRating":90},"current":{"CompetitiveTier":12,"RankedRating":27}}}},"LatestCompetitiveUpdate":{"TierProgressAfterUpdate":90}}""")
        assertEquals(CurrentRank(12,27), CurrentRankParser.parse(mmr,"current"))
        assertEquals(CurrentRank(0,-1), CurrentRankParser.parse(mmr,"new-act"))
    }
    @Test fun unavailableIsNotZeroRR() {
        assertEquals(CurrentRank(-1,-1), CurrentRankParser.parse(JSONObject(),"current"))
        assertEquals(CurrentRank(-1,-1), CurrentRankParser.parse(JSONObject(),null))
    }
    @Test fun immortalRRIsNotClampedTo100() {
        val mmr = JSONObject("""{"QueueSkills":{"competitive":{"SeasonalInfoBySeasonID":{"current":{"CompetitiveTier":26,"RankedRating":450}}}}}""")
        assertEquals(450, CurrentRankParser.parse(mmr,"current").rr)
    }
}
