package app.nightwatch.store

import org.json.JSONObject

data class AdvancedMatchMetrics(
    val rounds: Int, val firstKills: Int, val firstDeaths: Int, val survived: Int,
    val tradedDeaths: Int, val kastRounds: Int, val multiKillRounds: Int, val bestRoundKills: Int,
    val plants: Int, val defuses: Int, val damageReceived: Int,
    val headHits: Int, val bodyHits: Int, val legHits: Int, val economyRounds: Int, val creditsSpent: Int
) {
    val kastPercent: Int? get() = if(rounds > 0) kastRounds * 100 / rounds else null
}

object AdvancedMatchAnalysis {
    /** Trade = a teammate eliminates your killer within five seconds of your death. */
    fun calculate(details: JSONObject, subject: String): AdvancedMatchMetrics {
        val teams = details.optJSONArray("players").objects().associate { it.optString("subject") to it.optString("teamId") }
        var count = 0; var fk = 0; var fd = 0; var survived = 0; var trades = 0; var kast = 0
        var multi = 0; var best = 0; var plants = 0; var defuses = 0; var received = 0
        var heads = 0; var bodies = 0; var legs = 0; var economyRounds = 0; var spent = 0
        fun time(kill: JSONObject): Long = kill.optLong("timeSinceRoundStartMillis", kill.optLong("roundTime", -1))
        for(round in details.optJSONArray("roundResults").objects()) {
            val stats = round.optJSONArray("playerStats").objects()
            val mine = stats.firstOrNull { it.optString("subject") == subject } ?: continue
            count++
            val kills = stats.flatMap { it.optJSONArray("kills").objects() }.distinctBy {
                listOf(it.optString("killer"), it.optString("victim"), time(it).toString())
            }.sortedBy { time(it).takeIf { t -> t >= 0 } ?: Long.MAX_VALUE }
            val ownKills = kills.count { it.optString("killer") == subject }
            val deaths = kills.filter { it.optString("victim") == subject }
            val assisted = kills.any { k -> k.optJSONArray("assistants")?.let { a -> (0 until a.length()).any { a.optString(it) == subject } } == true }
            val first = kills.firstOrNull { time(it) >= 0 }
            if(first?.optString("killer") == subject) fk++
            if(first?.optString("victim") == subject) fd++
            if(deaths.isEmpty()) survived++
            val traded = deaths.any { death ->
                val diedAt = time(death)
                diedAt >= 0 && kills.any { k ->
                    val killer = k.optString("killer")
                    k.optString("victim") == death.optString("killer") && killer != subject &&
                        teams[subject]?.isNotBlank() == true && teams[killer] == teams[subject] &&
                        time(k) >= diedAt && time(k) - diedAt <= 5000
                }
            }
            if(traded) trades++
            if(ownKills > 0 || assisted || deaths.isEmpty() || traded) kast++
            if(ownKills >= 2) multi++
            best = maxOf(best, ownKills)
            if(round.optString("bombPlanter") == subject) plants++
            if(round.optString("bombDefuser") == subject) defuses++
            mine.optJSONArray("damage").objects().forEach { d -> heads += d.optInt("headshots"); bodies += d.optInt("bodyshots"); legs += d.optInt("legshots") }
            received += stats.filter { attacker ->
                val attackerId = attacker.optString("subject")
                attackerId != subject && (teams[subject].isNullOrBlank() || teams[attackerId].isNullOrBlank() || teams[attackerId] != teams[subject])
            }.flatMap { it.optJSONArray("damage").objects() }
                .filter { it.optString("receiver") == subject }.sumOf { it.optInt("damage") }
            mine.optJSONObject("economy")?.takeIf { it.has("spent") }?.let { economyRounds++; spent += it.optInt("spent") }
        }
        return AdvancedMatchMetrics(count, fk, fd, survived, trades, kast, multi, best, plants, defuses, received, heads, bodies, legs, economyRounds, spent)
    }
}
