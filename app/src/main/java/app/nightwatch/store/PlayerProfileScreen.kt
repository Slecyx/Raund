package app.nightwatch.store

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class PlayerSummary(val kills: Int, val deaths: Int, val assists: Int, val maps: Int,
                                  val rating: Double?, val acs: Double?, val recentRating: Double?,
                                   val wins: Int, val decidedMatches: Int, val bestAgent: String?)
internal data class PlayerMapIntel(val name: String, val played: Int, val wins: Int, val rating: Double?, val kills: Int, val deaths: Int)
internal data class PlayerOpponentIntel(val name: String, val matches: Int, val wins: Int, val losses: Int)
internal data class PlayerYearIntel(val year: String, val matches: Int, val wins: Int, val maps: Int)

internal object PlayerAnalytics {
    private fun count(usage: String): Int = Regex("\\d+").findAll(usage).lastOrNull()?.value?.toIntOrNull() ?: 0
    fun summary(profile: PlayerProfile): PlayerSummary {
        val agents = profile.agents
        val kills = agents.sumOf { it.kills.toIntOrNull() ?: 0 }
        val deaths = agents.sumOf { it.deaths.toIntOrNull() ?: 0 }
        val assists = agents.sumOf { it.assists.toIntOrNull() ?: 0 }
        val weights = agents.map { (it.rounds.toIntOrNull() ?: count(it.usage)).coerceAtLeast(0) }
        fun weighted(value: (PlayerAgent) -> Double?): Double? {
            val rows = agents.mapIndexedNotNull { index, agent -> value(agent)?.let { Triple(it, weights[index], agent) } }
            if(rows.isEmpty()) return null
            val weight = rows.sumOf { it.second }
            return if(weight > 0) rows.sumOf { it.first * it.second } / weight else rows.map { it.first }.average()
        }
        val maps = agents.sumOf(::agentMaps)
        val recentRatings = profile.matches.flatMap { it.maps }.take(10).mapNotNull { it.rating.toDoubleOrNull() }
        val decided = profile.matches.filter { it.won != null }
        return PlayerSummary(kills, deaths, assists, maps, weighted { it.rating.toDoubleOrNull() }, weighted { it.acs.toDoubleOrNull() },
            recentRatings.takeIf(List<Double>::isNotEmpty)?.average(), decided.count { it.won == true }, decided.size,
            agents.maxByOrNull { it.rounds.toIntOrNull() ?: count(it.usage) }?.name)
    }
    fun agentMaps(agent: PlayerAgent): Int = Regex("\\d+").findAll(agent.usage).lastOrNull()?.value?.toIntOrNull() ?: 0
    fun ratingTrend(profile: PlayerProfile): List<Float> = profile.matches.flatMap { it.maps }.take(12).mapNotNull { it.rating.toFloatOrNull() }.reversed()
    fun mapPool(profile: PlayerProfile): List<PlayerMapIntel> = profile.matches.flatMap { it.maps }.groupBy { it.map.ifBlank { "Unknown" } }
        .map { (name, rows) -> PlayerMapIntel(name, rows.size, rows.count { it.won == true },
            rows.mapNotNull { it.rating.toDoubleOrNull() }.takeIf(List<Double>::isNotEmpty)?.average(),
            rows.sumOf { it.kills ?: 0 }, rows.sumOf { it.deaths ?: 0 }) }
        .sortedWith(compareByDescending<PlayerMapIntel> { it.played }.thenByDescending { it.rating ?: 0.0 })
    fun opponents(profile: PlayerProfile): List<PlayerOpponentIntel> = profile.matches.groupBy { it.opponent }
        .map { (name, rows) -> PlayerOpponentIntel(name, rows.size, rows.count { it.won == true }, rows.count { it.won == false }) }
        .sortedWith(compareByDescending<PlayerOpponentIntel> { it.matches }.thenByDescending { it.wins })
    fun years(profile: PlayerProfile): List<PlayerYearIntel> = profile.matches.groupBy { match ->
        Regex("20\\d{2}").find(match.date)?.value ?: "—"
    }.map { (year, rows) -> PlayerYearIntel(year, rows.size, rows.count { it.won == true }, rows.sumOf { it.maps.size }) }
        .sortedByDescending { it.year }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun PlayerScreen(url: String, news: List<EsportsNews>, followed: Boolean,
                                      onFollow: () -> Unit, onBack: () -> Unit, onMatch: (EsportsMatch) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repo = remember { EsportsExploreRepository(context) }
    val scope = rememberCoroutineScope()
    var period by remember(url) { mutableStateOf("60d") }
    var tab by remember(url) { mutableStateOf("overview") }
    var profile by remember(url, period) { mutableStateOf<PlayerProfile?>(null) }
    var history by remember(url) { mutableStateOf<List<PlayerMatchPerformance>>(emptyList()) }
    var loading by remember(url, period) { mutableStateOf(true) }
    var historyLoading by remember(url) { mutableStateOf(false) }
    var failed by remember(url, period) { mutableStateOf(false) }
    var openedNews by remember { mutableStateOf<EsportsNews?>(null) }
    var matchQuery by rememberSaveable(url) { mutableStateOf("") }
    var compareQuery by rememberSaveable(url) { mutableStateOf("") }
    var compareResults by remember(url) { mutableStateOf<List<EsportsPlayerSearchResult>>(emptyList()) }
    var compareProfile by remember(url) { mutableStateOf<PlayerProfile?>(null) }
    var compareLoading by remember(url) { mutableStateOf(false) }
    fun load(force: Boolean) {
        if(loading && force) return
        loading = true
        scope.launch {
            runCatching { repo.player(url, period, force, includeMatches = false) }
                .onSuccess { quick -> profile = quick.copy(matches = history.ifEmpty { quick.matches }); failed = false }
                .onFailure { failed = true }
            loading = false
            if(history.isEmpty() || force) {
                historyLoading = true
                runCatching { repo.player(url, period, force, includeMatches = true) }
                    .onSuccess { full -> history = full.matches; profile = full }
                    .onFailure { if(profile == null) failed = true }
                historyLoading = false
            }
        }
    }
    LaunchedEffect(url, period) {
        val cached = withContext(Dispatchers.IO) { repo.cachedPlayer(url, period) }
        if(cached != null) {
            history = history.ifEmpty { cached.matches }
            profile = cached.copy(matches = history.ifEmpty { cached.matches })
            loading = false
        }
        load(false)
    }
    LaunchedEffect(compareQuery) {
        if(compareQuery.trim().length < 2) { compareResults = emptyList(); return@LaunchedEffect }
        delay(350)
        compareResults = runCatching { repo.searchPlayers(compareQuery) }.getOrDefault(emptyList()).filterNot { it.url == url }.take(8)
    }
    openedNews?.let { story -> NewsReader(story) { openedNews = null }; return }
    val value = profile?.copy(matches = history.ifEmpty { profile?.matches.orEmpty() })
    val summary = value?.let(PlayerAnalytics::summary)
    val related = value?.player?.name?.takeIf { it.length >= 3 }?.let { alias ->
        (value.news + news.filter { item -> Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(alias)}(?![\\p{L}\\p{N}])")
            .containsMatchIn(item.title + " " + item.summary) }
            ).distinctBy { it.url }
    }.orEmpty()
    val filteredMatches = remember(value?.matches, matchQuery) {
        val query = matchQuery.trim()
        if(query.isBlank()) value?.matches.orEmpty() else value?.matches.orEmpty().filter { match ->
            listOf(match.opponent, match.team, match.event, match.stage, match.date) .any { it.contains(query, true) } ||
                match.maps.any { it.map.contains(query, true) || it.agent.contains(query, true) }
        }
    }
    val spoiler = SpoilerPolicy.enabled(context)
    LazyColumn(contentPadding = PaddingValues(horizontal = 21.dp, vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(txt("← Players", "← Oyuncular")) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onFollow) {
                    Icon(painterResource(if(followed) R.drawable.ic_favorite_filled else R.drawable.ic_tab_favorites),
                        if(followed) txt("Unfollow player", "Oyuncuyu takipten çıkar") else txt("Follow player", "Oyuncuyu takip et"),
                        Modifier.size(22.dp), tint = if(followed) Accent else White)
                }
                TextButton(onClick = { load(true) }, enabled = !loading) { Text(txt("Refresh", "Yenile")) }
            }
        }
        item {
            PlayerHero(value, followed)
            if(loading || historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = Accent)
            if(value == null) Note(if(failed) txt("Player profile could not be loaded.", "Oyuncu profili yüklenemedi.")
                else txt("Building the player dossier…", "Oyuncu dosyası hazırlanıyor…"))
            if(failed && value != null) Note(txt("Refresh failed · showing the saved dossier.", "Yenileme başarısız · kayıtlı dosya gösteriliyor."))
            if(value != null) {
                val checked = repo.playerCheckedAt(url, period)
                Text(txt("Source: VLR.gg · Checked ", "Kaynak: VLR.gg · Kontrol ") + if(checked > 0) timeLabel(checked) else "—",
                    color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 9.dp))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("30d" to "30", "60d" to "60", "90d" to "90", "all" to txt("All", "Tümü")).forEach { (key, label) ->
                        FilterChip(selected = period == key, onClick = { period = key }, label = { Text(label) }, modifier = Modifier.weight(1f))
                    }
                }
                Text(txt("Performance window · match history and career stay complete", "Performans aralığı · maç ve kariyer geçmişi tam kalır"),
                    color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
                Text(txt("${value.matches.size} matches · ${value.matches.sumOf { it.maps.size }} maps · ${value.achievements.size} events · ${value.teams.size} teams recorded",
                    "${value.matches.size} maç · ${value.matches.sumOf { it.maps.size }} harita · ${value.achievements.size} turnuva · ${value.teams.size} takım kaydı"),
                    color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        if(value != null) stickyHeader {
                Row(Modifier.fillMaxWidth().background(Ink)) {
                    PlayerTab(txt("Overview", "Özet"), tab == "overview", Modifier.weight(1f)) { tab = "overview" }
                    PlayerTab(txt("Matches", "Maçlar"), tab == "matches", Modifier.weight(1f)) { tab = "matches" }
                    PlayerTab(txt("Agents", "Ajanlar"), tab == "agents", Modifier.weight(1f)) { tab = "agents" }
                    PlayerTab(txt("Career", "Kariyer"), tab == "career", Modifier.weight(1f)) { tab = "career" }
                    PlayerTab(txt("Compare", "Kıyas"), tab == "compare", Modifier.weight(1f)) { tab = "compare" }
                }
        }
        if(value != null && summary != null) when(tab) {
            "matches" -> {
                item {
                    PlayerSectionTitle(txt("Match archive", "Maç arşivi"), filteredMatches.size.toString())
                    OutlinedTextField(matchQuery, { matchQuery = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(txt("Search opponent, event, map or agent", "Rakip, turnuva, harita veya ajan ara")) })
                    if(historyLoading) Text(txt("Loading the complete archive…", "Tam arşiv yükleniyor…"), color = Muted, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
                if(value.matches.isEmpty()) item { Note(txt("The source has not returned match history.", "Kaynak maç geçmişi döndürmedi.")) }
                items(filteredMatches, key = { "player-match-${it.id}" }) { match ->
                    PlayerMatchRow(match, spoiler) { onMatch(match.asMatch()) }
                }
            }
            "agents" -> {
                item {
                    PlayerSectionTitle(txt("Agent pool", "Ajan havuzu"), value.agents.size.toString())
                    Text(txt("Aggregated for the selected period. R = source rating.", "Seçilen dönem için toplam. R = kaynak rating değeri."), color = Muted, fontSize = 11.sp)
                }
                items(value.agents, key = { "agent-${it.name}" }) { agent -> PlayerAgentRow(agent, summary.maps) }
            }
            "career" -> {
                item {
                    val firsts = value.achievements.sumOf { achievement -> achievement.results.count { result -> Regex("(^|–\\s*)1st(\\D|$)", RegexOption.IGNORE_CASE).containsMatchIn(result) } }
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                        PlayerMetric(value.achievements.size.toString(), txt("Events", "Turnuva"), Modifier.weight(1f))
                        PlayerMetric(firsts.toString(), txt("Recorded 1st", "Kayıtlı 1."), Modifier.weight(1f))
                        PlayerMetric(value.totalWinnings.ifBlank { "—" }, txt("Total winnings", "Toplam kazanç"), Modifier.weight(1f))
                    }
                    PlayerSectionTitle(txt("Team timeline", "Takım zaman çizelgesi"), value.teams.size.toString())
                }
                items(value.teams, key = { "tenure-${it.url}" }) { tenure -> PlayerTeamTenureRow(tenure) }
                item {
                    PlayerSectionTitle(txt("Tournament record", "Turnuva geçmişi"), value.achievements.size.toString())
                    Text(txt("Placements and prize text are shown exactly as supplied by the source.",
                        "Dereceler ve ödül metinleri kaynağın sağladığı biçimde gösterilir."), color = Muted, fontSize = 11.sp)
                }
                if(value.achievements.isEmpty()) item { Note(txt("No tournament record was supplied.", "Turnuva geçmişi sağlanmadı.")) }
                items(value.achievements, key = { it.eventUrl }) { achievement -> PlayerAchievementRow(achievement) }
            }
            "compare" -> {
                item {
                    PlayerSectionTitle(txt("Player comparison", "Oyuncu karşılaştırma"))
                    OutlinedTextField(compareQuery, { compareQuery = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(txt("Search another player", "Başka oyuncu ara")) })
                }
                if(compareProfile == null) items(compareResults, key = { "compare-${it.url}" }) { candidate ->
                    Row(Modifier.fillMaxWidth().clickable {
                        compareLoading = true
                        scope.launch {
                            compareProfile = runCatching { repo.player(candidate.url, "60d", includeMatches = true) }.getOrNull()
                            compareLoading = false
                        }
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(candidate.image, candidate.name, Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(candidate.name, color = White, fontWeight = FontWeight.SemiBold)
                            Text(candidate.description, color = Muted, fontSize = 10.sp)
                        }
                    }
                    HorizontalDivider(color = Raised.copy(alpha = .55f))
                }
                if(compareLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 18.dp), color = Accent) }
                compareProfile?.let { rival -> item {
                    PlayerComparison(value, rival) { compareProfile = null; compareQuery = "" }
                } }
            }
            else -> {
                item {
                    PlayerOverview(summary, value, spoiler)
                    if(value.matches.isNotEmpty()) PlayerSectionTitle(txt("Recent form", "Son form"), minOf(5, value.matches.size).toString())
                }
                items(value.matches.take(5), key = { "overview-match-${it.id}" }) { match ->
                    PlayerMatchCompact(match, spoiler) { onMatch(match.asMatch()) }
                }
                if(value.agents.isNotEmpty()) {
                    item { PlayerSectionTitle(txt("Signature agents", "Öne çıkan ajanlar"), minOf(3, value.agents.size).toString()) }
                    items(value.agents.sortedByDescending(PlayerAnalytics::agentMaps).take(3), key = { "signature-${it.name}" }) {
                        PlayerAgentRow(it, summary.maps)
                    }
                }
                val mapPool = PlayerAnalytics.mapPool(value)
                if(mapPool.isNotEmpty()) {
                    item { PlayerSectionTitle(txt("Map dossier", "Harita dosyası"), mapPool.size.toString()) }
                    items(mapPool.take(8), key = { "map-intel-${it.name}" }) { PlayerMapIntelRow(it) }
                }
                val opponents = PlayerAnalytics.opponents(value)
                if(opponents.isNotEmpty()) {
                    item { PlayerSectionTitle(txt("Most faced opponents", "En sık rakipler"), minOf(8, opponents.size).toString()) }
                    items(opponents.take(8), key = { "opponent-${it.name}" }) { PlayerOpponentRow(it, spoiler) }
                }
                val years = PlayerAnalytics.years(value)
                if(years.isNotEmpty()) {
                    item { PlayerSectionTitle(txt("Season archive", "Sezon arşivi"), years.size.toString()) }
                    items(years, key = { "year-${it.year}" }) { PlayerYearRow(it, spoiler) }
                }
                item { PlayerSectionTitle(txt("Player news", "Oyuncu haberleri"), related.size.toString()) }
                if(related.isEmpty()) item { Note(txt("No recent headline mentions this player.", "Son haberlerde bu oyuncudan söz edilmiyor.")) }
                items(related.take(5), key = { it.url }) { story -> EsportsNewsRow(story) { openedNews = story } }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable private fun PlayerHero(profile: PlayerProfile?, followed: Boolean) {
    val currentTeam = profile?.teams?.firstOrNull { it.current }?.name ?: profile?.matches?.firstOrNull()?.team
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(112.dp).clip(RoundedCornerShape(28.dp)).background(Raised), contentAlignment = Alignment.Center) {
            Text(profile?.player?.name?.take(1).orEmpty(), color = Muted, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            profile?.player?.image?.let { AsyncImage(it, profile.player.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(profile?.player?.name ?: txt("Player", "Oyuncu"), color = White, fontSize = 31.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            profile?.player?.realName?.takeIf(String::isNotBlank)?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
            val identity = listOfNotNull(currentTeam?.takeIf(String::isNotBlank), profile?.country?.takeIf(String::isNotBlank)).joinToString(" · ")
            if(identity.isNotBlank()) Text(identity, color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Text(if(followed) txt("Following player", "Oyuncu takipte") else txt("Not followed", "Takip edilmiyor"), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
            profile?.socials?.take(3)?.forEach { social ->
                val context = LocalContext.current
                Text(social.label, color = White, fontSize = 11.sp, modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(social.url))) }
                }.padding(top = 5.dp))
            }
        }
    }
}

@Composable private fun PlayerTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if(selected) White else Muted, fontSize = 12.sp, fontWeight = if(selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = 7.dp).width(if(selected) 28.dp else 0.dp).height(2.dp).background(if(selected) White else Color.Transparent))
    }
}

@Composable private fun PlayerOverview(summary: PlayerSummary, profile: PlayerProfile, spoiler: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth()) {
            PlayerMetric(summary.rating?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—", "R", Modifier.weight(1f))
            PlayerMetric(summary.acs?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—", "ACS", Modifier.weight(1f))
            PlayerMetric(if(summary.deaths > 0) String.format(Locale.ROOT, "%.2f", summary.kills.toDouble() / summary.deaths) else "—", "K/D", Modifier.weight(1f))
            PlayerMetric(summary.maps.toString(), txt("Maps", "Harita"), Modifier.weight(1f))
        }
        val trend = PlayerAnalytics.ratingTrend(profile)
        if(trend.size >= 2) {
            PlayerSectionTitle(txt("Map rating pulse", "Harita rating akışı"), txt("oldest → newest", "eski → yeni"))
            PlayerRatingTrend(trend)
            Text(txt("Last ${trend.size} published maps · no proprietary score", "Yayımlanan son ${trend.size} harita · özel puan üretilmez"), color = Muted, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(txt("RECENT MATCH FORM", "SON MAÇ FORMU"), color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                Text(if(spoiler) "•••" else if(summary.decidedMatches == 0) "—" else "${summary.wins}/${summary.decidedMatches} " + txt("wins", "galibiyet"),
                    color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            Text(summary.bestAgent ?: "—", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun PlayerRatingTrend(values: List<Float>) {
    val line = Accent
    val grid = Raised
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(vertical = 14.dp)) {
        drawLine(grid, Offset(0f, size.height * .5f), Offset(size.width, size.height * .5f), 1.dp.toPx())
        val min = (values.minOrNull() ?: 0f).coerceAtMost(.8f)
        val max = (values.maxOrNull() ?: 2f).coerceAtLeast(1.2f)
        val points = values.mapIndexed { index, value ->
            Offset(if(values.size == 1) 0f else size.width * index / (values.size - 1), size.height * (1f - (value - min) / (max - min)))
        }
        points.zipWithNext().forEach { (a, b) -> drawLine(line, a, b, 2.dp.toPx()) }
        points.forEach { drawCircle(line, 3.dp.toPx(), it) }
    }
}

@Composable private fun PlayerMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Muted, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable private fun PlayerSectionTitle(title: String, count: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if(count.isNotBlank()) Text(count, color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun PlayerAgentRow(agent: PlayerAgent, totalMaps: Int) {
    val maps = PlayerAnalytics.agentMaps(agent)
    val usage = agent.usage.replace(Regex("\\s+$maps$"), "").trim()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(agent.image, agent.name, Modifier.size(48.dp), contentScale = ContentScale.Fit)
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(agent.name.ifBlank { txt("Unknown agent", "Bilinmeyen ajan") }, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(listOf("$maps " + txt("maps", "harita"), usage).filter(String::isNotBlank).distinct().joinToString(" · "), color = Muted, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(agent.rating.ifBlank { "—" } + " R", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(agent.acs.ifBlank { "—" } + " ACS", color = Muted, fontSize = 10.sp)
            }
        }
        if(totalMaps > 0 && maps > 0) LinearProgressIndicator(progress = { (maps.toFloat() / totalMaps).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp), color = Accent, trackColor = Raised)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            PlayerTinyStat(agent.kd, "K/D", Modifier.weight(1f)); PlayerTinyStat(agent.kast, "KAST", Modifier.weight(1f))
            PlayerTinyStat(agent.adr, "ADR", Modifier.weight(1f)); PlayerTinyStat(agent.kills, "K", Modifier.weight(1f))
            PlayerTinyStat(agent.assists, "A", Modifier.weight(1f))
        }
    }
    HorizontalDivider(color = Raised.copy(alpha = .58f))
}

@Composable private fun PlayerTinyStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.ifBlank { "—" }, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Muted, fontSize = 8.sp)
    }
}

@Composable private fun PlayerMatchCompact(match: PlayerMatchPerformance, spoiler: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(match.opponent, match.opponentLogo, 38.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(match.opponent, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(listOf(match.event, match.stage, match.date).filter(String::isNotBlank).joinToString(" · "), color = Muted, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if(spoiler) "•••" else listOfNotNull(match.score1, match.score2).joinToString(" – ").ifBlank { "—" },
            color = if(!spoiler && match.won == true) Accent else White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = Raised.copy(alpha = .55f))
}

@Composable private fun PlayerMatchRow(match: PlayerMatchPerformance, spoiler: Boolean, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(match.event, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(match.stage, color = Muted, fontSize = 10.sp, maxLines = 1)
            }
            Text(match.date, color = Muted, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(match.team, match.teamLogo, 34.dp)
            Text(match.team, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
            Text(if(spoiler) "•••" else "${match.score1 ?: "—"} : ${match.score2 ?: "—"}", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp))
            Text(match.opponent, color = White, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 1)
            TeamLogo(match.opponent, match.opponentLogo, 34.dp)
        }
        if(!spoiler && match.maps.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                match.maps.take(4).forEach { map ->
                    Column(Modifier.weight(1f)) {
                        Text(map.map, color = White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${map.rating.ifBlank { "—" }} R", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(map.kills, map.deaths, map.assists).joinToString("/"), color = Muted, fontSize = 9.sp)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Raised.copy(alpha = .65f))
}

@Composable private fun PlayerAchievementRow(item: PlayerAchievement) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(item.event, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
            Text(item.year, color = Accent, fontSize = 11.sp)
        }
        item.results.forEach { result -> Text(result, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
    }
    HorizontalDivider(color = Raised.copy(alpha = .58f))
}

@Composable private fun PlayerTeamTenureRow(item: PlayerTeamTenure) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(item.name, item.logo, 44.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(item.period.ifBlank { txt("Date not supplied", "Tarih sağlanmadı") }, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            if(item.note.isNotBlank()) Text(item.note, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text(if(item.current) txt("CURRENT", "GÜNCEL") else txt("PAST", "ESKİ"), color = if(item.current) Accent else Muted,
            fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = Raised.copy(alpha = .58f))
}

@Composable private fun PlayerMapIntelRow(item: PlayerMapIntel) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.name, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("${item.played} " + txt("maps", "harita") + " · ${item.wins} " + txt("wins", "galibiyet"), color = Muted, fontSize = 10.sp)
        }
        PlayerTinyStat(item.rating?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—", "R", Modifier.width(54.dp))
        PlayerTinyStat(if(item.deaths > 0) String.format(Locale.ROOT, "%.2f", item.kills.toDouble()/item.deaths) else "—", "K/D", Modifier.width(54.dp))
        PlayerTinyStat(if(item.played > 0) "${item.wins * 100 / item.played}%" else "—", "WR", Modifier.width(54.dp))
    }
    HorizontalDivider(color = Raised.copy(alpha = .5f))
}

@Composable private fun PlayerOpponentRow(item: PlayerOpponentIntel, spoiler: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(if(spoiler) "•••" else "${item.wins}W · ${item.losses}L", color = Muted, fontSize = 11.sp)
        Text("${item.matches} " + txt("matches", "maç"), color = Accent, fontSize = 10.sp, modifier = Modifier.padding(start = 14.dp))
    }
    HorizontalDivider(color = Raised.copy(alpha = .5f))
}

@Composable private fun PlayerYearRow(item: PlayerYearIntel, spoiler: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.year, color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("${item.matches} " + txt("matches", "maç") + " · ${item.maps} " + txt("maps", "harita"), color = Muted, fontSize = 10.sp)
        Text(if(spoiler) "•••" else "${item.wins}W", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
    }
    HorizontalDivider(color = Raised.copy(alpha = .5f))
}

@Composable private fun PlayerComparison(base: PlayerProfile, rival: PlayerProfile, reset: () -> Unit) {
    val left = PlayerAnalytics.summary(base)
    val right = PlayerAnalytics.summary(rival)
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ComparePlayerIdentity(base, Modifier.weight(1f))
            Text("VS", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
            ComparePlayerIdentity(rival, Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        PlayerCompareLine("R", left.rating?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—", right.rating?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—")
        PlayerCompareLine("ACS", left.acs?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—", right.acs?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—")
        PlayerCompareLine("K/D", if(left.deaths > 0) String.format(Locale.ROOT, "%.2f", left.kills.toDouble()/left.deaths) else "—",
            if(right.deaths > 0) String.format(Locale.ROOT, "%.2f", right.kills.toDouble()/right.deaths) else "—")
        PlayerCompareLine(txt("Maps", "Harita"), left.maps.toString(), right.maps.toString())
        PlayerCompareLine(txt("Recent wins", "Son galibiyet"), "${left.wins}/${left.decidedMatches}", "${right.wins}/${right.decidedMatches}")
        PlayerCompareLine(txt("Best agent", "En iyi ajan"), left.bestAgent ?: "—", right.bestAgent ?: "—")
        TextButton(onClick = reset, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(txt("Choose another player", "Başka oyuncu seç")) }
    }
}

@Composable private fun ComparePlayerIdentity(profile: PlayerProfile, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(profile.player.image, profile.player.name, Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)), contentScale = ContentScale.Crop)
        Text(profile.player.name, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        Text(profile.teams.firstOrNull { it.current }?.name ?: profile.country, color = Muted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable private fun PlayerCompareLine(label: String, left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
        Text(label, color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1.2f))
        Text(right, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = Raised.copy(alpha = .5f))
}
