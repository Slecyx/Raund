package app.nightwatch.store

import org.json.JSONObject

internal data class CurrentRank(val tier: Int, val rr: Int)
internal object CurrentRankParser {
    fun parse(mmr: JSONObject, seasonId: String?): CurrentRank {
        if(seasonId.isNullOrBlank()) return CurrentRank(-1, -1)
        val competitive = mmr.optJSONObject("QueueSkills")?.optJSONObject("competitive") ?: return CurrentRank(-1, -1)
        val seasons = competitive.optJSONObject("SeasonalInfoBySeasonID") ?: return CurrentRank(-1, -1)
        // Never use a previous act's last match as the current rank.
        val season = seasons.optJSONObject(seasonId) ?: return CurrentRank(0, -1)
        if(!season.has("CompetitiveTier")) return CurrentRank(-1, -1)
        val tier = season.optInt("CompetitiveTier", -1)
        return CurrentRank(tier, if(tier >= 3) season.optInt("RankedRating", -1) else -1)
    }
}
