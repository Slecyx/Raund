package app.nightwatch.store

import org.json.JSONObject

internal data class KnownPlayerStat(
    val puuid: String, val riotId: String, val games: Int, val wins: Int,
    val kills: Int, val deaths: Int, val assists: Int, val together: Boolean
) {
    val winRate: Int get() = if(games == 0) 0 else wins * 100 / games
    val kd: Double get() = kills.toDouble() / deaths.coerceAtLeast(1)
}

internal data class CareerPeople(val players: List<KnownPlayerStat>, val duos: List<KnownPlayerStat>, val ownWinRate: Int)

internal object CareerPeopleAnalysis {
    private data class MutableRow(var name: String, var games: Int = 0, var wins: Int = 0,
                                  var kills: Int = 0, var deaths: Int = 0, var assists: Int = 0)

    fun calculate(matches: List<MatchSummary>): CareerPeople {
        val competitive = matches.filter { it.mode.equals("Competitive", true) && it.detailsJson != null }
        val all = linkedMapOf<String, MutableRow>()
        val duos = linkedMapOf<String, MutableRow>()
        competitive.forEach { match ->
            val detail = runCatching { JSONObject(match.detailsJson!!) }.getOrNull() ?: return@forEach
            val players = detail.optJSONArray("players").objects()
            val own = players.firstOrNull { it.optString("subject") == match.playerId } ?: return@forEach
            val ownTeam = own.optString("teamId")
            val teamWins = detail.optJSONArray("teams").objects().associate { it.optString("teamId") to it.optBoolean("won") }
            players.filter { it.optString("subject").isNotBlank() && it.optString("subject") != match.playerId }.forEach { player ->
                val id = player.optString("subject")
                val gameName = player.optString("gameName").ifBlank { player.optString("name") }
                val tag = player.optString("tagLine").ifBlank { player.optString("tag") }
                val label = if(gameName.isNotBlank()) gameName + tag.takeIf(String::isNotBlank)?.let { "#$it" }.orEmpty()
                    else "Player ${id.take(6)}"
                val stats = player.optJSONObject("stats")
                fun add(target: MutableMap<String, MutableRow>, together: Boolean) {
                    val row = target.getOrPut(id) { MutableRow(label) }
                    if(row.name.startsWith("Player ") && !label.startsWith("Player ")) row.name = label
                    row.games++; if(teamWins[player.optString("teamId")] == true) row.wins++
                    row.kills += stats?.optInt("kills") ?: 0
                    row.deaths += stats?.optInt("deaths") ?: 0
                    row.assists += stats?.optInt("assists") ?: 0
                }
                add(all, false)
                if(player.optString("teamId") == ownTeam) add(duos, true)
            }
        }
        fun rows(source: Map<String, MutableRow>, together: Boolean) = source.map { (id, row) ->
            KnownPlayerStat(id, row.name, row.games, row.wins, row.kills, row.deaths, row.assists, together)
        }.sortedWith(compareByDescending<KnownPlayerStat> { it.games }.thenByDescending { it.winRate }.thenBy { it.riotId })
        val decided = competitive.count { it.won != null }
        return CareerPeople(rows(all, false), rows(duos, true),
            if(decided == 0) 0 else competitive.count { it.won == true } * 100 / decided)
    }
}

internal data class CoachGoal(val key: String, val titleTr: String, val titleEn: String,
                              val detailTr: String, val detailEn: String, val progress: Int)

internal object CompetitiveCoachAnalysis {
    fun goals(matches: List<MatchSummary>): List<CoachGoal> {
        val competitive = matches.filter { it.mode.equals("Competitive", true) && it.detailsJson != null }
        if(competitive.isEmpty()) return emptyList()
        val advanced = competitive.mapNotNull { match -> runCatching {
            AdvancedMatchAnalysis.calculate(JSONObject(match.detailsJson!!), match.playerId)
        }.getOrNull() }
        val replayRounds = competitive.flatMap { match -> runCatching {
            RaundReplayAnalysis.calculate(JSONObject(match.detailsJson!!), match.playerId).rounds
        }.getOrDefault(emptyList()) }
        val result = mutableListOf<CoachGoal>()
        val firstKills = advanced.sumOf { it.firstKills }; val firstDeaths = advanced.sumOf { it.firstDeaths }
        if(firstKills + firstDeaths > 0 && firstDeaths >= firstKills) result += CoachGoal("opening",
            "İlk ölümü azalt", "Reduce first deaths",
            "$firstKills ilk skor / $firstDeaths ilk ölüm. Hedef: açılış düellolarında pozitife geç.",
            "$firstKills first kills / $firstDeaths first deaths. Goal: turn opening duels positive.",
            firstKills * 100 / (firstKills + firstDeaths))
        val kast = advanced.mapNotNull { it.kastPercent }.average().takeUnless { it.isNaN() }?.toInt()
        if(kast != null && kast < 72) result += CoachGoal("kast", "Raunt etkisini yükselt", "Raise round impact",
            "Ortalama KAST %$kast. Hedef: takas mesafesi ve hayatta kalmayla %72 üzerine çık.",
            "Average KAST is $kast%. Goal: pass 72% through trade spacing and survival.", (kast * 100 / 72).coerceAtMost(100))
        val full = replayRounds.filter { it.buy == "full" }
        if(full.size >= 5) {
            val rate = full.count { it.won } * 100 / full.size
            if(rate < 55) result += CoachGoal("fullbuy", "Full-buy rauntlarını dönüştür", "Convert full-buy rounds",
                "${full.size} full-buy rauntta kazanma %$rate. Hedef: %55.",
                "$rate% wins across ${full.size} full-buy rounds. Goal: 55%.", (rate * 100 / 55).coerceAtMost(100))
        }
        val weakMap = competitive.filter { it.won != null }.groupBy { it.map }.mapValues { (_, games) ->
            games.count { it.won == true } * 100 / games.size
        }.filter { competitive.count { match -> match.map == it.key } >= 8 }.minByOrNull { it.value }
        if(weakMap != null && weakMap.value < 50) result += CoachGoal("map", "${weakMap.key} planı", "${weakMap.key} plan",
            "Bu haritada kazanma %${weakMap.value}. Sonraki maç için tek ajan ve iki site planı belirle.",
            "${weakMap.value}% win rate here. Pick one agent and prepare two site plans for the next match.", weakMap.value * 2)
        return result.distinctBy { it.key }.take(3).ifEmpty {
            listOf(CoachGoal("maintain", "KAST örneklemini izle", "Track your KAST sample",
                "Mevcut veriyle kesin gelişim sonucu çıkarılamaz. Sonraki 5 rekabetçi maçın raunt etkisini karşılaştır.",
                "This sample cannot establish improvement. Compare round impact in the next five competitive matches.", 0))
        }
    }
}
