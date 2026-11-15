package app.nightwatch.store

import android.content.Context

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
}
