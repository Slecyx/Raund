package app.nightwatch.store

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal data class RiotStatusNotice(val title: String, val severity: String, val kind: String)
internal data class RiotRegionStatus(val region: String, val notices: List<RiotStatusNotice>)
internal data class PatchNoteLink(val title: String, val date: String, val url: String)

internal object RiotStatusParser {
    private val validRegions = setOf("eu", "na", "ap", "kr", "br", "latam")

    fun parse(region: String, json: JSONObject, turkish: Boolean): RiotRegionStatus {
        val targetLocale = if(turkish) "tr_TR" else "en_US"
        fun localized(array: JSONArray?): String {
            val entries = array.objects()
            return entries.firstOrNull { it.optString("locale").equals(targetLocale, true) }?.textOrNull("content")
                ?: entries.firstOrNull { it.optString("locale").startsWith("en", true) }?.textOrNull("content")
                ?: entries.firstOrNull()?.textOrNull("content").orEmpty()
        }
        val notices = mutableListOf<RiotStatusNotice>()
        json.optJSONArray("incidents").objects().forEach { row ->
            val title = localized(row.optJSONArray("titles")).ifBlank { "Service incident" }
            notices += RiotStatusNotice(title, row.optString("incident_severity", "info"), "incident")
        }
        json.optJSONArray("maintenances").objects().forEach { row ->
            val title = localized(row.optJSONArray("titles")).ifBlank { "Scheduled maintenance" }
            notices += RiotStatusNotice(title, row.optString("maintenance_status", "scheduled"), "maintenance")
        }
        return RiotRegionStatus(region.uppercase(Locale.ROOT), notices)
    }

    suspend fun fetchStatus(region: String, turkish: Boolean): RiotRegionStatus = withContext(Dispatchers.IO) {
        val safe = region.lowercase(Locale.ROOT).takeIf { it in validRegions } ?: "eu"
        val connection = URL("https://valorant.secure.dyn.riotcdn.net/channels/public/x/status/$safe.json").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        val raw = try {
            if(connection.responseCode !in 200..299) error("Status HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder(); val buf = CharArray(4096)
                while(true) {
                    val c = reader.read(buf); if(c < 0) break
                    if(out.length + c > 2_000_000) error("Status payload too large")
                    out.append(buf, 0, c)
                }
                out.toString()
            }
        } finally { connection.disconnect() }
        parse(safe, JSONObject(raw), turkish)
    }

    suspend fun fetchPatchNotes(turkish: Boolean): List<PatchNoteLink> = withContext(Dispatchers.IO) {
        val locale = if(turkish) "tr-tr" else "en-us"
        val doc = Jsoup.connect("https://playvalorant.com/$locale/news/game-updates/")
            .userAgent("Raund Android companion").timeout(12_000).maxBodySize(1_500_000).get()
        doc.select("a[href*=/news/game-updates/]").mapNotNull { link ->
            val href = link.absUrl("href")
            if(!href.startsWith("https://playvalorant.com/")) return@mapNotNull null
            val title = link.selectFirst("h2, h3, [data-testid*=title]")?.text()?.trim().orEmpty()
                .ifBlank { link.text().trim() }
            if(title.length < 6) return@mapNotNull null
            val date = link.selectFirst("time")?.text()?.trim().orEmpty()
            PatchNoteLink(title.take(140), date, href)
        }.distinctBy { it.url }.take(12)
    }
}

internal data class WeaponDamageRange(val startMeters: Int, val endMeters: Int, val head: Int, val body: Int, val leg: Int)
internal data class WeaponGuideEntry(
    val id: String,
    val name: String,
    val category: String,
    val cost: Int,
    val fireRate: Double,
    val magazine: Int,
    val wallPenetration: String,
    val imageUrl: String?,
    val ranges: List<WeaponDamageRange>
)

internal data class AgentAbilityEntry(val slot: String, val name: String, val description: String, val icon: String?)
internal data class AgentGuideEntry(
    val id: String,
    val name: String,
    val role: String,
    val description: String,
    val icon: String?,
    val abilities: List<AgentAbilityEntry>
)

internal object GameGuideParser {
    fun weapons(items: List<JSONObject>): List<WeaponGuideEntry> = items.mapNotNull { row ->
        val id = row.textOrNull("uuid") ?: return@mapNotNull null
        val name = row.textOrNull("displayName") ?: return@mapNotNull null
        val stats = row.optJSONObject("weaponStats") ?: return@mapNotNull null
        val shop = row.optJSONObject("shopData")
        val ranges = stats.optJSONArray("damageRanges").objects().map { range ->
            WeaponDamageRange(
                range.optInt("rangeStartMeters"), range.optInt("rangeEndMeters"),
                range.optDouble("headDamage").toInt(), range.optDouble("bodyDamage").toInt(), range.optDouble("legDamage").toInt()
            )
        }
        val category = shop?.textOrNull("categoryText") ?: row.optString("category").substringAfterLast("::")
        val wall = stats.optString("wallPenetration").substringAfterLast("::")
        WeaponGuideEntry(id, name, category, shop?.optInt("cost") ?: 0, stats.optDouble("fireRate", 0.0),
            stats.optInt("magazineSize", 0), wall, row.textOrNull("displayIcon"), ranges)
    }.sortedWith(compareBy<WeaponGuideEntry> { it.category }.thenBy { it.cost }.thenBy { it.name })

    fun agents(items: List<JSONObject>): List<AgentGuideEntry> = items.mapNotNull { row ->
        if(!row.optBoolean("isPlayableCharacter", false)) return@mapNotNull null
        val id = row.textOrNull("uuid") ?: return@mapNotNull null
        val name = row.textOrNull("displayName") ?: return@mapNotNull null
        val role = row.optJSONObject("role")?.textOrNull("displayName").orEmpty()
        val abilities = row.optJSONArray("abilities").objects().mapNotNull { ab ->
            val abName = ab.textOrNull("displayName") ?: return@mapNotNull null
            AgentAbilityEntry(ab.optString("slot"), abName, ab.optString("description"), ab.textOrNull("displayIcon"))
        }
        AgentGuideEntry(id, name, role, row.optString("description"), row.textOrNull("displayIcon"), abilities)
    }.sortedBy { it.name }
}

internal object SavedFriendsStore {
    private const val KEY = "saved_friends_v1"
    fun list(context: Context): List<Pair<String, String>> {
        val raw = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            JSONArray(raw).objects().mapNotNull { row ->
                val puuid = row.textOrNull("puuid")?.takeIf(RiotLiveGateway::isRiotId) ?: return@mapNotNull null
                val name = row.textOrNull("name") ?: return@mapNotNull null
                puuid to name
            }
        }.getOrDefault(emptyList())
    }
    fun toggle(context: Context, puuid: String, name: String): List<Pair<String, String>> {
        if(!RiotLiveGateway.isRiotId(puuid)) return list(context)
        val current = list(context)
        val next = if(current.any { it.first == puuid }) current.filterNot { it.first == puuid }
        else (listOf(puuid to name.take(64)) + current).distinctBy { it.first }.take(30)
        val array = JSONArray()
        next.forEach { (id, label) -> array.put(JSONObject().put("puuid", id).put("name", label)) }
        context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
        return next
    }
}

@Composable internal fun GameHubScreen(account: LiveStoreAccount?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val tr = LocalTurkish.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Status & Patches
    var status by remember { mutableStateOf<RiotRegionStatus?>(null) }
    var statusRegion by rememberSaveable { mutableStateOf(account?.region?.lowercase(Locale.ROOT) ?: "eu") }
    var patches by remember { mutableStateOf<List<PatchNoteLink>>(emptyList()) }
    var statusLoading by remember { mutableStateOf(false) }

    // Weapons & Agents
    var weapons by remember { mutableStateOf<List<WeaponGuideEntry>>(emptyList()) }
    var agents by remember { mutableStateOf<List<AgentGuideEntry>>(emptyList()) }
    var weaponFilter by rememberSaveable { mutableStateOf("All") }
    var agentRole by rememberSaveable { mutableStateOf("All") }
    var expandedAgent by remember { mutableStateOf<String?>(null) }

    // Player lookup
    var lookupQuery by rememberSaveable { mutableStateOf("") }
    var lookedUp by remember { mutableStateOf<LookedUpPlayer?>(null) }
    var lookupLoading by remember { mutableStateOf(false) }
    var lookupError by remember { mutableStateOf<String?>(null) }
    var friends by remember { mutableStateOf(SavedFriendsStore.list(context)) }
    val knownPlayers = remember(account?.career?.matches) {
        CareerPeopleAnalysis.calculate(account?.career?.matches.orEmpty()).players
    }

    LaunchedEffect(tab, statusRegion, tr) {
        if(tab == 0) {
            statusLoading = true
            status = runCatching { RiotStatusParser.fetchStatus(statusRegion, tr) }.getOrNull()
            if(patches.isEmpty()) patches = runCatching { RiotStatusParser.fetchPatchNotes(tr) }.getOrDefault(emptyList())
            statusLoading = false
        } else if(tab == 1 && weapons.isEmpty()) {
            weapons = withContext(Dispatchers.IO) {
                runCatching { GameGuideParser.weapons(ContentCatalog(context).data("weapons", if(tr) "tr-TR" else "en-US").objects()) }.getOrDefault(emptyList())
            }
        } else if(tab == 2 && agents.isEmpty()) {
            agents = withContext(Dispatchers.IO) {
                runCatching { GameGuideParser.agents(ContentCatalog(context).data("agents", if(tr) "tr-TR" else "en-US").objects()) }.getOrDefault(emptyList())
            }
        }
    }

    fun inspectPlayer(puuid: String) {
        scope.launch {
            lookupLoading = true; lookupError = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val session = SessionStore(context).validSession()
                    RiotLiveGateway(context).lookupPlayerProfile(session, puuid)
                }
            }.onSuccess { lookedUp = it }
                .onFailure { lookupError = if(tr) "Oyuncu verisi alınamadı. Riot oturumunun açık olduğundan emin ol." else "Could not load player profile. Make sure your Riot session is active." }
            lookupLoading = false
        }
    }

    DetailPage(txt("Game Hub & Lookup", "Oyun Merkezi ve Arama"), onBack) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(txt("Status & Patches", "Durum ve Yamalar"), tab == 0) { tab = 0 }
            Chip(txt("Lineups", "Lineuplar"), tab == 4) { tab = 4 }
            Chip(txt("Weapons", "Silahlar"), tab == 1) { tab = 1 }
            Chip(txt("Agents", "Ajanlar"), tab == 2) { tab = 2 }
            Chip(txt("Player Lookup", "Oyuncu Ara"), tab == 3) { tab = 3 }
        }
        when(tab) {
            0 -> {
                SectionTitle(txt("Riot server status", "Riot sunucu durumu"), statusRegion.uppercase(Locale.ROOT))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("eu", "na", "ap", "kr", "br", "latam").forEach { r ->
                        Chip(r.uppercase(Locale.ROOT), statusRegion == r) { statusRegion = r }
                    }
                }
                if(statusLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                status?.let { s ->
                    if(s.notices.isEmpty()) {
                        Text(txt("✓ All systems operational (${s.region})", "✓ Tüm sistemler normal çalışıyor (${s.region})"),
                            color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
                    } else s.notices.forEach { n ->
                        Text("• [${n.severity.uppercase(Locale.ROOT)}] ${n.title}", color = Accent, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Raised)
                SectionTitle(txt("Official patch notes", "Resmî yama notları"))
                if(patches.isEmpty() && !statusLoading) {
                    Note(txt("Could not load patch notes list. Open PlayValorant directly.", "Yama notları listesi alınamadı. PlayValorant üzerinden açabilirsin."))
                    TextButton(onClick = { openHttpsUrl(context, if(tr) "https://playvalorant.com/tr-tr/news/game-updates/" else "https://playvalorant.com/en-us/news/game-updates/") }) {
                        Text(txt("Open patch notes ↗", "Yama notlarını aç ↗"))
                    }
                }
                patches.forEach { note ->
                    Row(Modifier.fillMaxWidth().clickable { openHttpsUrl(context, note.url) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(note.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if(note.date.isNotBlank()) Text(note.date, color = Muted, fontSize = 10.sp)
                        }
                        Text("↗", color = Accent, fontSize = 16.sp)
                    }
                    HorizontalDivider(color = Raised.copy(alpha = .5f))
                }
            }
            1 -> {
                val categories = remember(weapons) { listOf("All") + weapons.map { it.category }.distinct().sorted() }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    categories.forEach { cat -> Chip(if(cat == "All") txt("All", "Tümü") else cat, weaponFilter == cat) { weaponFilter = cat } }
                }
                weapons.filter { weaponFilter == "All" || it.category == weaponFilter }.forEach { w ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(w.imageUrl, w.name, Modifier.width(96.dp).height(42.dp), contentScale = ContentScale.Fit)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(w.name, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("${w.cost} Creds · ${String.format(Locale.ROOT, "%.1f", w.fireRate)}/s · ${w.magazine} · ${w.wallPenetration}",
                                    color = Muted, fontSize = 11.sp)
                            }
                        }
                        w.ranges.forEach { r ->
                            Text("${r.startMeters}–${r.endMeters}m   ${txt("Head", "Kafa")} ${r.head} · ${txt("Body", "Gövde")} ${r.body} · ${txt("Leg", "Bacak")} ${r.leg}",
                                color = White.copy(alpha = .85f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    HorizontalDivider(color = Raised.copy(alpha = .5f))
                }
            }
            2 -> {
                val roles = remember(agents) { listOf("All") + agents.map { it.role }.filter(String::isNotBlank).distinct().sorted() }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    roles.forEach { r -> Chip(if(r == "All") txt("All", "Tümü") else r, agentRole == r) { agentRole = r } }
                }
                agents.filter { agentRole == "All" || it.role == agentRole }.forEach { ag ->
                    val open = expandedAgent == ag.id
                    Column(Modifier.fillMaxWidth().clickable { expandedAgent = if(open) null else ag.id }.padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(ag.icon, ag.name, Modifier.size(44.dp).clip(CircleShape).background(Raised), contentScale = ContentScale.Crop)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(ag.name, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(ag.role, color = Accent, fontSize = 11.sp)
                            }
                            Text(if(open) "−" else "+", color = Muted, fontSize = 20.sp)
                        }
                        if(open) {
                            Text(ag.description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                            ag.abilities.forEach { ab ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                                    ab.icon?.let { AsyncImage(it, ab.name, Modifier.size(28.dp).padding(top = 2.dp), contentScale = ContentScale.Fit) }
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text("${ab.name} · ${ab.slot}", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text(ab.description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Raised.copy(alpha = .5f))
                }
            }
            3 -> {
                SectionTitle(txt("Player & friend lookup", "Oyuncu ve arkadaş arama"))
                Note(txt("Search players from your recent matches by Riot ID#Tag, or paste a Riot PUUID to check live rank and recent games.",
                    "Son maçlarındaki oyuncuları Riot ID#Tag ile ara veya canlı rank ve son maçları görmek için bir Riot PUUID yapıştır."))
                OutlinedTextField(lookupQuery, { lookupQuery = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true,
                    shape = RoundedCornerShape(14.dp), placeholder = { Text(txt("Name#Tag or PUUID", "İsim#Etiket veya PUUID"), fontSize = 13.sp) })
                if(RiotLiveGateway.isRiotId(lookupQuery.trim())) {
                    Button(onClick = { inspectPlayer(lookupQuery.trim()) }, enabled = !lookupLoading, modifier = Modifier.fillMaxWidth()) {
                        Text(txt("Load player by PUUID", "PUUID ile oyuncuyu yükle"))
                    }
                }
                if(lookupLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Accent)
                lookupError?.let { Note(it) }
                lookedUp?.let { found ->
                    val isFriend = friends.any { it.first == found.puuid }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(found.riotId, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("${found.career.rank} · ${if(found.career.rankedRating >= 0) "${found.career.rankedRating} RR" else "— RR"} · ${found.region}",
                                    color = Green, fontSize = 12.sp)
                            }
                            TextButton(onClick = { friends = SavedFriendsStore.toggle(context, found.puuid, found.riotId) }) {
                                Text(if(isFriend) txt("♥ Saved", "♥ Kayıtlı") else txt("♡ Save", "♡ Kaydet"))
                            }
                        }
                        found.career.matches.take(5).forEach { m ->
                            Text("• ${m.map} ${m.score} (${m.mode}) · ${m.kills}/${m.deaths}/${m.assists}",
                                color = White, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if(friends.isNotEmpty()) {
                    SectionTitle(txt("Saved friends", "Kayıtlı arkadaşlar"), friends.size.toString())
                    friends.forEach { (id, name) ->
                        Row(Modifier.fillMaxWidth().clickable { inspectPlayer(id) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(txt("Inspect ›", "İncele ›"), color = Accent, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Raised.copy(alpha = .5f))
                    }
                }
                val matchedKnown = remember(knownPlayers, lookupQuery) {
                    val q = normalized(lookupQuery.trim())
                    knownPlayers.filter { q.isBlank() || normalized(it.riotId).contains(q) }.take(20)
                }
                if(matchedKnown.isNotEmpty()) {
                    SectionTitle(txt("Players from your matches", "Maçlarındaki oyuncular"), matchedKnown.size.toString())
                    matchedKnown.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { inspectPlayer(p.puuid) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.riotId, color = White, fontSize = 14.sp)
                                Text("${p.games} " + txt("games", "maç") + " · ${p.winRate}% · ${String.format(Locale.ROOT, "%.2f", p.kd)} K/D", color = Muted, fontSize = 11.sp)
                            }
                            Text(txt("Rank ›", "Rank ›"), color = Accent, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Raised.copy(alpha = .5f))
                    }
                }
            }
            4 -> LineupGuideTab()
        }
    }
}
