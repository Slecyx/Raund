package app.nightwatch.store

import org.json.JSONObject
import java.util.Locale

internal data class FormSnapshot(val matches: Int, val winRate: Int, val kd: Double, val adr: Int, val firstDeaths: Double)
internal data class CareerTrend(val recent: FormSnapshot, val previous: FormSnapshot, val mode: String)

internal object CareerInsights {
    fun trend(matches: List<MatchSummary>): CareerTrend? {
        val detailed = matches.filter { it.detailsJson != null }
        val competitive = detailed.filter { it.mode.equals("Competitive", true) }
        val source = if(competitive.size >= 6) competitive else detailed
        if(source.size < 6) return null
        val groupSize = minOf(5, source.size / 2)
        fun snapshot(group: List<MatchSummary>): FormSnapshot {
            val wins = group.count { it.won == true }
            val decided = group.count { it.won != null }.coerceAtLeast(1)
            val metrics = group.mapNotNull { match -> match.detailsJson?.let { raw ->
                runCatching { MatchAnalysis.metrics(JSONObject(raw), match.playerId) }.getOrNull()
            } }
            val firstDeaths = group.mapNotNull { match -> match.detailsJson?.let { raw ->
                runCatching { AdvancedMatchAnalysis.calculate(JSONObject(raw), match.playerId).firstDeaths }.getOrNull()
            } }.average().takeUnless { it.isNaN() } ?: 0.0
            return FormSnapshot(group.size, wins * 100 / decided,
                group.sumOf { it.kills }.toDouble() / group.sumOf { it.deaths }.coerceAtLeast(1),
                if(metrics.isEmpty()) 0 else metrics.sumOf { it.adr } / metrics.size, firstDeaths)
        }
        return CareerTrend(snapshot(source.take(groupSize)), snapshot(source.drop(groupSize).take(groupSize)),
            if(source === competitive) "Competitive" else "All")
    }

    fun evaluation(match: MatchSummary, metrics: MatchMetrics, advanced: AdvancedMatchMetrics, turkish: Boolean): List<String> {
        val kd = match.kills.toDouble() / match.deaths.coerceAtLeast(1)
        val lines = mutableListOf<String>()
        val kast = advanced.kastPercent
        if(kast != null && kast >= 75)
            lines += if(turkish) "Raunt etkisi güçlü: KAST %$kast." else "Strong round impact: $kast% KAST."
        else if(kast != null && kast < 60)
            lines += if(turkish) "Raunt etkisi düşük kaldı: KAST %$kast." else "Round impact was low at $kast% KAST."
        if(advanced.firstDeaths >= 3)
            lines += if(turkish) "${advanced.firstDeaths} ilk ölüm rakibe erken avantaj verdi." else "${advanced.firstDeaths} first deaths gave away early advantages."
        else if(advanced.firstKills > advanced.firstDeaths)
            lines += if(turkish) "Açılış düellolarında artıdasın: ${advanced.firstKills}/${advanced.firstDeaths}." else "You won the opening-duel battle: ${advanced.firstKills}/${advanced.firstDeaths}."
        if(kd >= 1.25) lines += if(turkish) "${String.format(Locale.getDefault(), "%.2f", kd)} K/D ile bireysel skor katkın yüksek." else "High individual fragging impact with a ${String.format(Locale.US, "%.2f", kd)} K/D."
        else if(metrics.damage > 0 && advanced.damageReceived > metrics.damage)
            lines += if(turkish) "Verdiğinden ${advanced.damageReceived - metrics.damage} daha fazla hasar aldın; düello seçimi geliştirilebilir." else "You took ${advanced.damageReceived - metrics.damage} more damage than you dealt; duel selection is the clearest improvement area."
        return lines.distinct().take(3).ifEmpty {
            listOf(if(turkish) "Bu maç için güvenilir bir çıkarım üretecek kadar ayrıntı yok." else "There is not enough reliable detail for a useful match takeaway.")
        }
    }
}
