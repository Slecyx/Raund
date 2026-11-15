package app.nightwatch.store

import org.json.JSONObject

internal enum class ReplayPointType { KILL, DEATH }
internal data class ReplayPoint(val x: Float, val y: Float, val type: ReplayPointType)
internal data class ReplayRound(
    val number: Int, val won: Boolean, val kills: Int, val deaths: Int, val assists: Int,
    val damage: Int, val teamLoadout: Int, val buy: String, val firstKill: Boolean,
    val firstDeath: Boolean, val traded: Boolean, val clutch: Boolean
)
internal data class RaundReplay(val rounds: List<ReplayRound>, val points: List<ReplayPoint>)

internal object RaundReplayAnalysis {
    fun calculate(details: JSONObject, subject: String, xMultiplier: Double = 1.0, yMultiplier: Double = 1.0,
                  xScalar: Double = 0.0, yScalar: Double = 0.0): RaundReplay {
        val players = details.optJSONArray("players").objects()
        val teams = players.associate { it.optString("subject") to it.optString("teamId") }
        val ownTeam = teams[subject].orEmpty()
        val points = mutableListOf<ReplayPoint>()
        fun time(kill: JSONObject) = kill.optLong("timeSinceRoundStartMillis", kill.optLong("roundTime", -1L))
        fun point(kill: JSONObject, type: ReplayPointType) {
            val raw = kill.optJSONObject("victimLocation") ?: return
            val px = (raw.optDouble("y") * xMultiplier + xScalar).toFloat().coerceIn(0f, 1f)
            val py = (raw.optDouble("x") * yMultiplier + yScalar).toFloat().coerceIn(0f, 1f)
            points += ReplayPoint(px, py, type)
        }
        val rounds = details.optJSONArray("roundResults").objects().map { round ->
            val stats = round.optJSONArray("playerStats").objects()
            val mine = stats.firstOrNull { it.optString("subject") == subject }
            val kills = stats.flatMap { it.optJSONArray("kills").objects() }.distinctBy {
                Triple(it.optString("killer"), it.optString("victim"), time(it))
            }.sortedBy { time(it).takeIf { value -> value >= 0 } ?: Long.MAX_VALUE }
            val ownKills = kills.filter { it.optString("killer") == subject }
            val deaths = kills.filter { it.optString("victim") == subject }
            ownKills.forEach { point(it, ReplayPointType.KILL) }; deaths.forEach { point(it, ReplayPointType.DEATH) }
            val assists = kills.count { kill -> kill.optJSONArray("assistants")?.let { list ->
                (0 until list.length()).any { list.optString(it) == subject }
            } == true }
            val first = kills.firstOrNull { time(it) >= 0 }
            val traded = deaths.any { death ->
                val diedAt = time(death)
                diedAt >= 0 && kills.any { kill ->
                    kill.optString("victim") == death.optString("killer") && kill.optString("killer") != subject &&
                        teams[kill.optString("killer")] == ownTeam && time(kill) in diedAt..(diedAt + 5000)
                }
            }
            val teamStats = stats.filter { teams[it.optString("subject")] == ownTeam }
            val loadout = teamStats.sumOf { it.optJSONObject("economy")?.optInt("loadoutValue") ?: 0 }
            val average = if(teamStats.isEmpty()) 0 else loadout / teamStats.size
            val buy = when { average <= 1500 -> "eco"; average <= 3500 -> "force"; else -> "full" }
            val teamDeaths = kills.count { teams[it.optString("victim")] == ownTeam && it.optString("victim") != subject }
            val won = round.optString("winningTeam") == ownTeam
            val survived = deaths.isEmpty()
            ReplayRound(round.optInt("roundNum") + 1, won, ownKills.size, deaths.size, assists,
                mine?.optJSONArray("damage").objects().sumOf { it.optInt("damage") }, loadout, buy,
                first?.optString("killer") == subject, first?.optString("victim") == subject,
                traded, won && survived && teamDeaths >= 4 && ownKills.isNotEmpty())
        }
        return RaundReplay(rounds, points)
    }
}
