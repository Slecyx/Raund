package app.nightwatch.store

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.json.JSONObject
import java.util.Locale

data class MatchMetrics(val acs: Int, val adr: Int, val headshotPercent: Int?, val damage: Int, val rounds: Int)

object MatchAnalysis {
    fun metrics(details: JSONObject, subject: String): MatchMetrics {
        val players = details.optJSONArray("players").objects()
        val player = players.find { it.optString("subject") == subject }
        val teams = players.associate { it.optString("subject") to it.optString("teamId") }
        val ownTeam = teams[subject].orEmpty()
        val stats = player?.optJSONObject("stats")
        val roundStats = details.optJSONArray("roundResults").objects().mapNotNull { round -> round.optJSONArray("playerStats").objects().find { it.optString("subject") == subject } }
        val damageRows = roundStats.flatMap { it.optJSONArray("damage").objects() }.filter { row ->
            val receiver = row.optString("receiver")
            receiver != subject && (ownTeam.isBlank() || teams[receiver].isNullOrBlank() || teams[receiver] != ownTeam)
        }
        val hits = damageRows.sumOf { it.optInt("headshots") + it.optInt("bodyshots") + it.optInt("legshots") }
        val damage = damageRows.sumOf { it.optInt("damage") }
        val rounds = (stats?.optInt("roundsPlayed") ?: 0).takeIf { it > 0 } ?: roundStats.size
        return MatchMetrics(if(rounds > 0) (stats?.optInt("score") ?: 0)/rounds else 0,
            if(rounds > 0) damage/rounds else 0,
            if(hits > 0) damageRows.sumOf { it.optInt("headshots") }*100/hits else null, damage, rounds)
    }
}

@Composable
internal fun MatchDetailPage(match: MatchSummary, agents: Map<String, JSONObject>, maps: Map<String, JSONObject>,
                             history: List<MatchSummary> = emptyList(), onClose: () -> Unit) {
    val raw = remember(match.id) { match.detailsJson?.let { runCatching { JSONObject(it) }.getOrNull() } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val tr = LocalTurkish.current
    var showCallouts by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableIntStateOf(-1) }
    val players = remember(raw) { raw?.optJSONArray("players").objects() }
    val own = players.find { it.optString("subject") == match.playerId }
    val ownTeam = own?.optString("teamId")
    val rounds = remember(raw) { raw?.optJSONArray("roundResults").objects() }
    val mapMeta = maps[match.map]
    val replay = remember(raw, match.playerId, mapMeta) { raw?.let {
        val xScalar = mapMeta?.optDouble("xScalarToAdd", Double.NaN)?.takeUnless(Double::isNaN)
            ?: mapMeta?.optDouble("xScalar", 0.0) ?: 0.0
        val yScalar = mapMeta?.optDouble("yScalarToAdd", Double.NaN)?.takeUnless(Double::isNaN)
            ?: mapMeta?.optDouble("yScalar", 0.0) ?: 0.0
        RaundReplayAnalysis.calculate(it, match.playerId, mapMeta?.optDouble("xMultiplier", 1.0) ?: 1.0,
            mapMeta?.optDouble("yMultiplier", 1.0) ?: 1.0, xScalar, yScalar)
    } }
    fun agentFor(subject: String): String {
        val player = players.find { it.optString("subject") == subject }
        return agents[player?.optString("characterId")]?.textOrNull("displayName") ?: "Player"
    }
    DetailPage(match.map, onClose) {
        Box(Modifier.fillMaxWidth().height(152.dp).clip(Shape).background(Panel)) {
            AsyncImage(match.mapImage ?: maps[match.map]?.textOrNull("splash"), match.map, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.15f), Color.Black.copy(.88f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text(match.mode, color = Color.White.copy(.8f), fontSize = 12.sp)
                Text(match.map + "  " + match.score, color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
                Text(timeLabel(match.startedAt), color = Color.White.copy(.8f), fontSize = 11.sp)
            }
        }
        if(showCallouts) MapCalloutDialog(match.map, mapMeta) { showCallouts = false }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if(mapMeta != null) TextButton(onClick = { showCallouts = true }) { Text(txt("Map callouts", "Harita bölgeleri")) }
            if(raw != null) TextButton(onClick = {
                val m = MatchAnalysis.metrics(raw, match.playerId)
                ShareSummaries.shareText(context, "${match.map} ${match.score}", ShareSummaries.matchSummary(match, m, tr))
            }) { Text(txt("Share ↗", "Paylaş ↗")) }
        }
        if(raw == null) {
            Note(txt("Riot did not return this match's details. Refresh your career and try again.", "Riot bu maçın ayrıntılarını döndürmedi. Kariyeri yenileyip tekrar dene."))
        } else {
            val metrics = remember(raw) { MatchAnalysis.metrics(raw, match.playerId) }
            val advanced = remember(raw, match.playerId) { AdvancedMatchAnalysis.calculate(raw, match.playerId) }
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                Stat("${match.kills}/${match.deaths}/${match.assists}", "K/D/A", Modifier.weight(1.3f))
                Stat(metrics.acs.toString(), "ACS", Modifier.weight(1f))
                Stat(metrics.headshotPercent?.let { "$it%" } ?: "—", txt("Headshots","Kafa vuruşu"), Modifier.weight(1f))
            }
            if(tab == 0) {
            SectionTitle(txt("Match takeaway", "Maç değerlendirmesi"))
            CareerInsights.evaluation(match, metrics, advanced, LocalTurkish.current).forEachIndexed { index, line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text("${index + 1}", color = Accent, fontSize = 11.sp, modifier = Modifier.width(25.dp))
                    Text(line, color = White, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            Text(txt("Generated only from recorded match data.", "Yalnızca kaydedilmiş maç verisinden oluşturuldu."),
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            }
            val comparisons = remember(match.id, history) {
                val previous = history.filter { it.id != match.id && it.mode == match.mode && it.detailsJson != null }.take(10)
                val samples = previous.mapNotNull { old -> old.detailsJson?.let { runCatching { MatchAnalysis.metrics(JSONObject(it), old.playerId) }.getOrNull() } }
                if(previous.isEmpty() || samples.isEmpty()) null else Triple(
                    previous.sumOf { it.kills }.toDouble() / previous.sumOf { it.deaths }.coerceAtLeast(1),
                    samples.sumOf { it.acs } / samples.size, samples.sumOf { it.adr } / samples.size)
            }
            comparisons?.let { (kd, acs, adr) ->
                SectionTitle(txt("Compared with previous matches", "Önceki maçlarınla karşılaştırma"), txt("Up to 10", "En fazla 10"))
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    CompareMetric("K/D", match.kills.toDouble()/match.deaths.coerceAtLeast(1), kd, Modifier.weight(1f))
                    CompareMetric("ACS", metrics.acs.toDouble(), acs.toDouble(), Modifier.weight(1f))
                    CompareMetric("ADR", metrics.adr.toDouble(), adr.toDouble(), Modifier.weight(1f))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Chip(txt("Overview","Özet"), tab == 0) { tab = 0 } }
                item { Chip(txt("Scoreboard","Skor tablosu"), tab == 1) { tab = 1 } }
                item { Chip("Raund Replay", tab == 2) { tab = 2 } }
            }
            when(tab) {
                0 -> {
                    SectionTitle(txt("Your performance","Performansın"))
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Stat(metrics.adr.toString(), txt("Damage / round","Hasar / raunt"), Modifier.weight(1f))
                        Stat(metrics.damage.toString(), txt("Total damage","Toplam hasar"), Modifier.weight(1f))
                    }
                    val length = raw.optJSONObject("matchInfo")?.optLong("gameLengthMillis") ?: 0
                    Note(txt("Duration","Süre") + " · ${length/60000}m ${length/1000%60}s")
                    if(advanced.rounds > 0) {
                        HorizontalDivider(color = Raised)
                        SectionTitle(txt("Round impact", "Raunt katkısı"))
                        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                            Stat(advanced.kastPercent?.let { "$it%" } ?: "—", "KAST · 5s", Modifier.weight(1f))
                            Stat("${advanced.firstKills} / ${advanced.firstDeaths}", txt("First kill / death", "İlk öldürme / ölüm"), Modifier.weight(1f))
                            Stat("${advanced.survived}/${advanced.rounds}", txt("Survived", "Hayatta kalma"), Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                            Stat(advanced.multiKillRounds.toString(), txt("Multi-kill rounds", "Çoklu öldürme rauntu"), Modifier.weight(1f))
                            Stat(advanced.bestRoundKills.toString(), txt("Best round kills", "Bir rauntta en çok"), Modifier.weight(1f))
                            Stat(advanced.tradedDeaths.toString(), txt("Traded rounds", "Takas edilen raunt"), Modifier.weight(1f))
                        }
                        SectionTitle(txt("Damage & accuracy", "Hasar ve isabet"))
                        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                            Stat(advanced.damageReceived.toString(), txt("Damage received", "Alınan hasar"), Modifier.weight(1f))
                            Stat((metrics.damage - advanced.damageReceived).let { if(it > 0) "+$it" else it.toString() }, txt("Damage delta", "Hasar farkı"), Modifier.weight(1f))
                        }
                        val totalHits = advanced.headHits + advanced.bodyHits + advanced.legHits
                        listOf(txt("Head", "Kafa") to advanced.headHits, txt("Body", "Gövde") to advanced.bodyHits, txt("Legs", "Bacak") to advanced.legHits).forEach { (label, hits) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(65.dp))
                                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) { ProgressLine(if(totalHits > 0) hits.toFloat()/totalHits else 0f, Accent) }
                                Text(if(totalHits > 0) "$hits · ${hits*100/totalHits}%" else "—", color = White, fontSize = 11.sp)
                            }
                        }
                        SectionTitle(txt("Objectives & economy", "Spike ve ekonomi"))
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Stat(advanced.plants.toString(), txt("Plants", "Spike kurma"), Modifier.weight(1f))
                            Stat(advanced.defuses.toString(), txt("Defuses", "Spike imha"), Modifier.weight(1f))
                            Stat(if(advanced.economyRounds > 0) (advanced.creditsSpent/advanced.economyRounds).toString() else "—", txt("Credits / round", "Harcama / raunt"), Modifier.weight(1f))
                        }
                        Note(txt("KAST: rounds with a kill, assist, survival or a teammate trading your death within 5 seconds. Calculated from recorded rounds; it may differ from other trackers.",
                            "KAST: öldürme, asist, hayatta kalma veya ölümünün 5 saniyede takım arkadaşınca takas edildiği raunt oranı. Kaydedilen rauntlardan hesaplanır; diğer takip uygulamalarıyla farklı olabilir."))
                    }
                    if(replay?.points.orEmpty().isNotEmpty()) {
                        SectionTitle(txt("Combat heatmap", "Çatışma haritası"))
                        val killsColor = Green; val deathsColor = Accent
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Panel)) {
                            AsyncImage(mapMeta?.textOrNull("displayIcon") ?: mapMeta?.textOrNull("listViewIcon"), match.map,
                                Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit, alpha = .48f)
                            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                                replay?.points.orEmpty().forEach { point ->
                                    val color = if(point.type == ReplayPointType.KILL) killsColor else deathsColor
                                    drawCircle(color.copy(alpha = .2f), 16.dp.toPx(), Offset(size.width * point.x, size.height * point.y))
                                    drawCircle(color, 5.dp.toPx(), Offset(size.width * point.x, size.height * point.y))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp)) {
                            Text("● " + txt("Kill", "Öldürme"), color = Green, fontSize = 10.sp, modifier = Modifier.weight(1f))
                            Text("● " + txt("Death", "Ölüm"), color = Accent, fontSize = 10.sp)
                        }
                    }
                    SectionTitle(txt("Round timeline","Raunt akışı"))
                    rounds.take(5).forEach { round ->
                        val index = round.optInt("roundNum")
                        val win = round.optString("winningTeam") == ownTeam
                        val mine = round.optJSONArray("playerStats").objects().find { it.optString("subject") == match.playerId }
                        val damage = mine?.optJSONArray("damage").objects().sumOf { it.optInt("damage") }
                        val deaths = round.optJSONArray("playerStats").objects().flatMap { it.optJSONArray("kills").objects() }
                            .count { it.optString("victim") == match.playerId }
                        Row(Modifier.fillMaxWidth().clickable { tab = 2; expanded = index }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((index+1).toString().padStart(2,'0'), color = Muted, fontSize = 12.sp, modifier = Modifier.width(34.dp))
                            Text(if(win) txt("Won","Kazandı") else txt("Lost","Kaybetti"), color = if(win) Green else Accent,
                                fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("${mine?.optJSONArray("kills")?.length() ?: 0}K · ${deaths}D · $damage DMG", color = White, fontSize = 11.sp)
                            Text("  ›", color = Muted, fontSize = 15.sp)
                        }
                        HorizontalDivider(color = Raised.copy(alpha = .65f))
                    }
                    if(rounds.size > 5) TextButton(onClick = { tab = 2 }) { Text(txt("View all ${rounds.size} rounds", "Tüm ${rounds.size} rauntu incele")) }
                    Note(txt("Tap a round to inspect kills, damage and economy.", "Öldürme, hasar ve ekonomiyi görmek için bir raunta dokun."))
                    val ability = own?.optJSONObject("stats")?.optJSONObject("abilityCasts")
                    if(ability != null) {
                        SectionTitle(txt("Ability use","Yetenek kullanımı"))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf("grenadeCasts" to "C", "ability1Casts" to "Q", "ability2Casts" to "E", "ultimateCasts" to "X").forEach { (key, label) -> Stat(ability.optInt(key).toString(), label) }
                        }
                    }
                    Note(txt("ACS = average combat score. ADR = damage per round. Headshot rate uses recorded hit counts.", "ACS = ortalama çatışma skoru. ADR = raunt başına hasar. Kafa vuruş oranı, kaydedilen isabetlerden hesaplanır."))
                }
                1 -> {
                    players.groupBy { it.optString("teamId") }.entries.sortedBy { if(it.key == ownTeam) 0 else 1 }.forEach { (team, members) ->
                        SectionTitle(if(team == ownTeam) txt("Your team","Takımın") else txt("Opponent team","Rakip takım"), "ACS · K / D / A")
                        members.sortedByDescending { it.optJSONObject("stats")?.optInt("score") ?: 0 }.forEach { player ->
                            val subject = player.optString("subject")
                            val stats = player.optJSONObject("stats")
                            val self = subject == match.playerId
                            val agent = agents[player.optString("characterId")]
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(self) Raised else Color.Transparent).padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(agent?.textOrNull("displayIcon"), agent?.textOrNull("displayName"), Modifier.size(37.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(agent?.textOrNull("displayName") ?: txt("Player","Oyuncu"), color = if(self) Green else White, fontSize = 13.sp)
                                    val playerMetrics = remember(raw, subject) { MatchAnalysis.metrics(raw, subject) }
                                    Text("${playerMetrics.adr} ADR · ${playerMetrics.headshotPercent?.let { "$it%" } ?: "—"} HS", color = Muted, fontSize = 10.sp)
                                    if(self) Text(txt("You","Sen"), color = Green, fontSize = 9.sp)
                                }
                                Text(MatchAnalysis.metrics(raw, subject).acs.toString(), color = Muted, fontSize = 12.sp, modifier = Modifier.width(35.dp))
                                Text("${stats?.optInt("kills") ?: 0} / ${stats?.optInt("deaths") ?: 0} / ${stats?.optInt("assists") ?: 0}", color = White, fontSize = 11.sp)
                            }
                        }
                    }
                }
                2 -> {
                    if(rounds.isEmpty()) Note(txt("No round data was returned.","Raunt verisi gelmedi."))
                    else {
                        SectionTitle(txt("Choose a round","Raunt seç"), rounds.size.toString())
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(rounds) { round ->
                                val index = round.optInt("roundNum")
                                val win = round.optString("winningTeam") == ownTeam
                                val selected = (if(expanded < 0) 0 else expanded) == index
                                Text("${index+1}", color = if(selected) Ink else if(win) Green else Accent,
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                        .background(if(selected) White else (if(win) Green else Accent).copy(alpha = .13f))
                                        .clickable { expanded = index }.padding(horizontal = 13.dp, vertical = 10.dp))
                            }
                        }
                        val index = (if(expanded < 0) 0 else expanded).coerceIn(0,rounds.lastIndex)
                        replay?.rounds?.getOrNull(index)?.let { item ->
                            Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 4.dp)) {
                                Stat(when(item.buy) { "eco" -> "ECO"; "force" -> "FORCE"; else -> "FULL" }, txt("Team buy", "Takım alımı"), Modifier.weight(1f))
                                Stat(item.teamLoadout.toString(), txt("Loadout", "Ekipman"), Modifier.weight(1f))
                                Stat(when { item.clutch -> "CLUTCH"; item.traded -> txt("TRADED", "TAKAS"); item.firstKill -> "FIRST +"; item.firstDeath -> "FIRST −"; else -> "—" },
                                    txt("Round context", "Raunt bağlamı"), Modifier.weight(1f))
                            }
                        }
                        RoundFocused(rounds[index], rounds.take(index+1), ownTeam, match.playerId, ::agentFor) { subject ->
                            players.find { it.optString("subject") == subject }?.optString("teamId").orEmpty()
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CompareMetric(label: String, current: Double, baseline: Double, modifier: Modifier = Modifier) {
    val delta = current - baseline
    Column(modifier) {
        Text(if(label == "K/D") String.format(Locale.ROOT, "%.2f", current) else current.toInt().toString(), color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text((if(delta >= 0) "+" else "") + if(label == "K/D") String.format(Locale.ROOT, "%.2f", delta) else delta.toInt().toString(),
            color = if(delta >= 0) Green else Accent, fontSize = 10.sp)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable private fun RoundFocused(round: JSONObject, played: List<JSONObject>, ownTeam: String?, playerId: String,
                                     agentFor: (String) -> String, teamFor: (String) -> String) {
    val win = round.optString("winningTeam") == ownTeam
    val won = played.count { it.optString("winningTeam") == ownTeam }
    val lost = played.size - won
    val mine = round.optJSONArray("playerStats").objects().find { it.optString("subject") == playerId }
    val roundStats = round.optJSONArray("playerStats").objects()
    val damage = mine?.optJSONArray("damage").objects().filter { hit ->
        val target = hit.optString("receiver")
        target != playerId && (ownTeam.isNullOrBlank() || teamFor(target).isBlank() || teamFor(target) != ownTeam)
    }.sumOf { it.optInt("damage") }
    val damageReceived = roundStats.filter { attacker ->
        val attackerId = attacker.optString("subject")
        attackerId != playerId && (ownTeam.isNullOrBlank() || teamFor(attackerId).isBlank() || teamFor(attackerId) != ownTeam)
    }.flatMap { it.optJSONArray("damage").objects() }.filter { it.optString("receiver") == playerId }.sumOf { it.optInt("damage") }
    val kills = roundStats.flatMap { it.optJSONArray("kills").objects() }
        .sortedBy { it.optLong("roundTime", it.optLong("timeSinceRoundStartMillis", Long.MAX_VALUE)) }
    val ownKills = mine?.optJSONArray("kills")?.length() ?: 0
    val died = kills.any { it.optString("victim") == playerId }
    val assists = kills.count { kill ->
        kill.optJSONArray("assistants")?.let { list -> (0 until list.length()).any { list.optString(it) == playerId } } == true
    }
    val economy = mine?.optJSONObject("economy")
    data class FlowEvent(val time: Long, val type: String, val actor: String, val target: String = "", val personal: Boolean = false)
    val events = (kills.map { kill ->
        val actor = kill.optString("killer")
        val target = kill.optString("victim")
        val assisted = kill.optJSONArray("assistants")?.let { list ->
            (0 until list.length()).any { list.optString(it) == playerId }
        } == true
        FlowEvent(kill.optLong("roundTime", kill.optLong("timeSinceRoundStartMillis", -1L)), "kill", actor, target,
            actor == playerId || target == playerId || assisted)
    } + listOfNotNull(
        round.textOrNull("bombPlanter")?.let { FlowEvent(round.optLong("plantRoundTime", -1L), "plant", it, personal = it == playerId) },
        round.textOrNull("bombDefuser")?.let { FlowEvent(round.optLong("defuseRoundTime", -1L), "defuse", it, personal = it == playerId) }
    )).sortedBy { if(it.time >= 0) it.time else Long.MAX_VALUE }
    val reason = when(round.optString("roundResult").lowercase(Locale.ROOT).replace(" ","")) {
        "eliminated" -> txt("Elimination","Eliminasyon")
        "bombdetonated" -> txt("Spike detonation","Spike patladı")
        "bombdefused" -> txt("Spike defused","Spike imha edildi")
        "timerexpired" -> txt("Time expired","Süre doldu")
        else -> round.optString("roundResult").replace(Regex("([a-z])([A-Z])"), "$1 $2")
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(txt("ROUND ${round.optInt("roundNum")+1}","RAUNT ${round.optInt("roundNum")+1}"), color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(if(win) txt("Victory","Galibiyet") else txt("Defeat","Mağlubiyet"), color = if(win) Green else Accent,
                    fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
            }
            Text("$won - $lost", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        if(reason.isNotBlank()) Text(reason, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Raised)
        Row(Modifier.fillMaxWidth()) {
            Stat("$ownKills/${if(died) 1 else 0}/$assists", "K/D/A", Modifier.weight(1.1f))
            Stat(if(mine != null) "$damage / $damageReceived" else "—", txt("Dealt / received","Verilen / alınan"), Modifier.weight(1f))
            Stat(if(died) txt("Eliminated","Elendi") else txt("Survived","Yaşadı"), txt("Your finish","Durumun"), Modifier.weight(1f))
        }
        economy?.let {
            Text(txt("Loadout","Ekipman") + " ${it.optInt("loadoutValue")}  ·  " +
                    txt("Spent","Harcadı") + " ${it.optInt("spent")}  ·  " +
                    txt("Left","Kalan") + " ${it.optInt("remaining")}",
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
        }
        SectionTitle(txt("Round flow","Rauntta ne oldu"), events.size.toString())
        if(events.isEmpty()) Note(txt("No event timeline was returned for this round.","Bu raunt için olay akışı gelmedi."))
        events.forEach { event ->
            val seconds = event.time / 1000
            val actor = if(event.actor == playerId) txt("You","Sen") else agentFor(event.actor)
            val target = if(event.target == playerId) txt("You","Sen") else agentFor(event.target)
            val line = when(event.type) {
                "plant" -> actor + " · " + txt("Spike planted","Spike kuruldu") +
                    round.optString("plantSite").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                "defuse" -> actor + " · " + txt("Spike defused","Spike imha edildi")
                else -> "$actor  →  $target"
            }
            val label = when {
                event.type != "kill" -> txt("Objective","Hedef")
                event.actor == playerId -> txt("Your kill","Öldürmen")
                event.target == playerId -> txt("Your death","Ölümün")
                event.personal -> txt("Your assist","Asistin")
                else -> txt("Round event","Raunt olayı")
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if(event.time >= 0) String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60) else "—",
                    color = Muted, fontSize = 10.sp, modifier = Modifier.width(48.dp))
                Box(Modifier.width(2.dp).height(36.dp).background(if(event.personal) Accent else Raised))
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(line, color = if(event.personal) White else Muted, fontSize = 12.sp,
                        fontWeight = if(event.personal) FontWeight.SemiBold else FontWeight.Normal)
                    Text(label, color = if(event.personal) Accent else Muted, fontSize = 10.sp)
                }
            }
        }
    }
}
