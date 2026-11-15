package app.nightwatch.store

import org.json.JSONObject
import com.google.gson.Gson

internal data class EvidenceSample(val value: Int?, val matches: Int, val observations: Int)
internal data class DevelopmentCheckpoint(val key: String, val titleTr: String, val titleEn: String,
    val map: String?, val baseline: EvidenceSample, val matchIds: Set<String>, val at: Long)

internal object DevelopmentEvidence {
    fun measure(key: String, matches: List<MatchSummary>, map: String? = null): EvidenceSample {
        val eligible = matches.filter { it.mode.equals("Competitive", true) && it.detailsJson != null && (map == null || it.map == map) }.distinctBy { it.id }
        if(key == "map") {
            val decided = eligible.filter { it.won != null }
            return EvidenceSample(if(decided.isEmpty()) null else decided.count { it.won == true } * 100 / decided.size, decided.size, decided.size)
        }
        if(key == "fullbuy") {
            val rounds = eligible.flatMap { match -> runCatching {
                RaundReplayAnalysis.calculate(JSONObject(match.detailsJson!!), match.playerId).rounds.filter { it.buy == "full" }
            }.getOrDefault(emptyList()) }
            return EvidenceSample(if(rounds.isEmpty()) null else rounds.count { it.won } * 100 / rounds.size, eligible.size, rounds.size)
        }
        val samples = eligible.mapNotNull { match -> runCatching {
            AdvancedMatchAnalysis.calculate(JSONObject(match.detailsJson!!), match.playerId).takeIf { it.rounds > 0 }
        }.getOrNull() }
        val denominator = if(key == "opening") samples.sumOf { it.firstKills + it.firstDeaths } else samples.sumOf { it.rounds }
        val numerator = if(key == "opening") samples.sumOf { it.firstDeaths } else samples.sumOf { it.kastRounds }
        return EvidenceSample(if(denominator == 0) null else numerator * 100 / denominator, samples.size, denominator)
    }
    fun start(goal: CoachGoal, matches: List<MatchSummary>, now: Long): DevelopmentCheckpoint {
        val ranked = matches.filter { it.mode.equals("Competitive", true) }
        val map = if(goal.key == "map") ranked.filter { it.won != null }.groupBy { it.map }.filterValues { it.size >= 8 }
            .minByOrNull { (_, games) -> games.count { it.won == true }.toDouble() / games.size }?.key else null
        return DevelopmentCheckpoint(goal.key, goal.titleTr, goal.titleEn, map, measure(goal.key, ranked.take(20), map), ranked.map { it.id }.toSet(), now)
    }
    fun after(checkpoint: DevelopmentCheckpoint, matches: List<MatchSummary>) = matches.filter {
        it.mode.equals("Competitive", true) && it.detailsJson != null && it.id !in checkpoint.matchIds && it.startedAt > checkpoint.at &&
            (checkpoint.map == null || it.map == checkpoint.map)
    }.distinctBy { it.id }.sortedBy { it.startedAt }.take(5)
    fun decode(raw: String?): DevelopmentCheckpoint? = runCatching {
        Gson().fromJson(raw, DevelopmentCheckpoint::class.java)?.takeIf { it.key in setOf("opening", "kast", "fullbuy", "map", "maintain") && it.at > 0 }
    }.getOrNull()
}
