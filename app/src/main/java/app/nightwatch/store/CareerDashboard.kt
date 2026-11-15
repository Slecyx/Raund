package app.nightwatch.store

import java.time.Instant
import java.time.ZoneId

internal data class CareerSplit(val key: String, val matches: Int, val wins: Int, val kills: Int, val deaths: Int) {
    val winRate: Int get() = if(matches > 0) wins * 100 / matches else 0
    val kd: Double get() = kills.toDouble() / deaths.coerceAtLeast(1)
}

internal data class CareerDashboard(
    val rrNet: Int,
    val rrCurve: List<Int>,
    val streak: Int,
    val streakWon: Boolean?,
    val lastSeven: CareerSplit,
    val previousSeven: CareerSplit,
    val maps: List<CareerSplit>,
    val agents: List<CareerSplit>,
    val dayParts: List<CareerSplit>
)

internal object CareerDashboardAnalysis {
    private fun split(key: String, matches: List<MatchSummary>): CareerSplit = CareerSplit(
        key, matches.size, matches.count { it.won == true }, matches.sumOf { it.kills }, matches.sumOf { it.deaths }
    )

    fun calculate(matches: List<MatchSummary>, now: Long = System.currentTimeMillis()): CareerDashboard {
        val decided = matches.filter { it.won != null }
        val newest = decided.firstOrNull()?.won
        val streak = decided.takeWhile { it.won == newest }.size
        val competitive = matches.filter { it.mode.equals("Competitive", true) && it.rrChange != null }
        val chronological = competitive.sortedBy { it.startedAt }
        var cumulative = 0
        val curve = buildList {
            add(0)
            chronological.forEach { cumulative += it.rrChange ?: 0; add(cumulative) }
        }
        val week = 7 * 24 * 60 * 60 * 1000L
        val recent = decided.filter { it.startedAt in (now - week)..now }
        val previous = decided.filter { it.startedAt in (now - 2 * week) until (now - week) }
        fun grouped(selector: (MatchSummary) -> String, minimum: Int = 1) = decided.groupBy(selector)
            .map { split(it.key, it.value) }.filter { it.matches >= minimum }
            .sortedWith(compareByDescending<CareerSplit> { it.winRate }.thenByDescending { it.matches })
        val zone = ZoneId.systemDefault()
        val dayParts = grouped({ match ->
            when(Instant.ofEpochMilli(match.startedAt).atZone(zone).hour) {
                in 5..11 -> "morning"
                in 12..17 -> "afternoon"
                in 18..22 -> "evening"
                else -> "night"
            }
        })
        return CareerDashboard(cumulative, curve, streak, newest, split("last7", recent), split("previous7", previous),
            grouped({ it.map }, 2), grouped({ it.agentId }, 2), dayParts)
    }
}
