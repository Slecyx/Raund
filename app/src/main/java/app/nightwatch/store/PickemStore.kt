package app.nightwatch.store

import android.content.Context

internal data class PickemSummary(val totalPicks: Int, val decided: Int, val correct: Int) {
    val accuracyPercent: Int? get() = if(decided == 0) null else correct * 100 / decided
}

internal object PickemStore {
    private const val PREFS = "raund_pickem"
    private fun key(id: String) = "match:$id"

    fun get(context: Context, match: EsportsMatch): String? = context.getSharedPreferences(PREFS, 0)
        .getString(key(match.id), null)?.takeIf { it == match.team1 || it == match.team2 }

    fun choose(context: Context, match: EsportsMatch, team: String): String? {
        if(match.status != "upcoming" || team != match.team1 && team != match.team2) return get(context, match)
        context.getSharedPreferences(PREFS, 0).edit().putString(key(match.id), team).commit()
        return team
    }

    fun correct(match: EsportsMatch, choice: String?): Boolean? {
        if(choice == null || match.status != "result" || match.score1 == null || match.score2 == null || match.score1 == match.score2) return null
        val winner = if(match.score1 > match.score2) match.team1 else match.team2
        return choice == winner
    }

    fun summarize(picks: Map<String, String>, matches: List<EsportsMatch>): PickemSummary {
        val byId = matches.associateBy { it.id }
        var decided = 0
        var correctCount = 0
        picks.forEach { (id, team) ->
            val result = byId[id]?.let { correct(it, team) }
            if(result != null) {
                decided++
                if(result) correctCount++
            }
        }
        return PickemSummary(picks.size, decided, correctCount)
    }

    fun summary(context: Context, matches: List<EsportsMatch>): PickemSummary {
        val raw = context.getSharedPreferences(PREFS, 0).all
            .mapNotNull { (k, v) -> if(k.startsWith("match:") && v is String) k.removePrefix("match:") to v else null }
            .toMap()
        return summarize(raw, matches)
    }
}
