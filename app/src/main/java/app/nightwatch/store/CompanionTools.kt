package app.nightwatch.store

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal data class RadianitePlan(val levels: Int, val chromas: Int, val requiredRp: Int, val walletRp: Int?, val deficitRp: Int) {
    companion object {
        const val RP_PER_LEVEL = 10
        const val RP_PER_CHROMA = 15
        fun calculate(levels: Int, chromas: Int, walletRp: Int?): RadianitePlan {
            val safeLevels = levels.coerceIn(0, 50)
            val safeChromas = chromas.coerceIn(0, 50)
            val needed = safeLevels * RP_PER_LEVEL + safeChromas * RP_PER_CHROMA
            val deficit = walletRp?.let { (needed - it).coerceAtLeast(0) } ?: needed
            return RadianitePlan(safeLevels, safeChromas, needed, walletRp, deficit)
        }
    }
}

internal object ShareSummaries {
    fun shareText(context: Context, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, subject)) }
    }

    fun addMatchToCalendar(context: Context, match: EsportsMatch): Boolean {
        val start = match.startAtMillis ?: return false
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "${match.team1} vs ${match.team2}")
            putExtra(CalendarContract.Events.DESCRIPTION, listOf(match.event, match.series, match.url).filter(String::isNotBlank).joinToString("\n"))
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 2 * 60 * 60_000L)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun storeSummary(account: LiveStoreAccount, turkish: Boolean): String {
        val lines = mutableListOf<String>()
        lines += if(turkish) "Raund · Günlük Mağaza (${account.region})" else "Raund · Daily Store (${account.region})"
        account.snapshot.offers.forEach { offer ->
            val price = if(offer.priceVp >= 0) "${offer.priceVp} ${offer.currency}" else "—"
            lines += "• ${offer.name} ($price)"
        }
        if(account.snapshot.nightMarketAvailable && account.snapshot.nightOffers.isNotEmpty()) {
            lines += ""
            lines += if(turkish) "Gece Pazarı:" else "Night Market:"
            account.snapshot.nightOffers.forEach { offer ->
                val price = if(offer.priceVp >= 0) "${offer.priceVp} ${offer.currency}" else "—"
                lines += "• ${offer.name} ($price)"
            }
        }
        return lines.joinToString("\n")
    }

    fun matchSummary(match: MatchSummary, metrics: MatchMetrics, turkish: Boolean): String {
        val outcome = when(match.won) {
            true -> if(turkish) "Galibiyet" else "Win"
            false -> if(turkish) "Mağlubiyet" else "Loss"
            null -> match.mode
        }
        val hs = metrics.headshotPercent?.let { "$it%" } ?: "—"
        return buildString {
            appendLine("${match.map} ${match.score} · $outcome (${match.mode})")
            appendLine("K/D/A: ${match.kills}/${match.deaths}/${match.assists} · ACS: ${metrics.acs} · ADR: ${metrics.adr} · HS: $hs")
        }.trim()
    }

    fun esportsMatchSummary(match: EsportsMatch, hideScores: Boolean, turkish: Boolean): String {
        val headline = if(match.status == "upcoming") "${match.team1} vs ${match.team2}"
        else SpoilerPolicy.title(match, hideScores)
        val status = when(match.status) {
            "live" -> if(turkish) "Canlı" else "Live"
            "result" -> if(turkish) "Sonuç" else "Final"
            else -> if(turkish) "Yaklaşan" else "Upcoming"
        }
        return listOf(headline, listOf(status, match.event, match.series).filter(String::isNotBlank).joinToString(" · "), match.url)
            .filter(String::isNotBlank).joinToString("\n")
    }
}

internal data class MapCalloutGroup(val superRegion: String, val regions: List<String>)
internal data class MapGuideData(val name: String, val minimapUrl: String?, val groups: List<MapCalloutGroup>)

internal object MapCalloutParser {
    fun parse(mapName: String, meta: JSONObject?): MapGuideData {
        val minimap = meta?.textOrNull("displayIcon")
        val callouts = meta?.optJSONArray("callouts").objects()
        val grouped = callouts.mapNotNull { row ->
            val region = row.textOrNull("regionName") ?: return@mapNotNull null
            val superRegion = row.textOrNull("superRegionName") ?: "Other"
            superRegion to region
        }.groupBy({ it.first }, { it.second })
            .map { (superRegion, regions) -> MapCalloutGroup(superRegion, regions.distinct().sorted()) }
            .sortedBy { it.superRegion }
        return MapGuideData(mapName, minimap, grouped)
    }
}

@Composable internal fun MapCalloutDialog(mapName: String, meta: JSONObject?, onDismiss: () -> Unit) {
    val guide = remember(mapName, meta) { MapCalloutParser.parse(mapName, meta) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(guide, query) {
        val q = normalized(query)
        if(q.isBlank()) guide.groups else guide.groups.mapNotNull { group ->
            val matched = group.regions.filter { normalized(it).contains(q) || normalized(group.superRegion).contains(q) }
            if(matched.isEmpty()) null else group.copy(regions = matched)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${guide.name} · " + txt("Map callouts", "Harita bölgeleri")) },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                guide.minimapUrl?.let { url ->
                    AsyncImage(url, guide.name, Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.25f)), contentScale = ContentScale.Fit)
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(12.dp), placeholder = { Text(txt("Search callouts", "Bölge ara"), fontSize = 13.sp) })
                Spacer(Modifier.height(10.dp))
                if(filtered.isEmpty()) {
                    Text(txt("No callout data available for this map.", "Bu harita için bölge verisi bulunamadı."), color = Muted, fontSize = 12.sp)
                } else LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(filtered, key = { it.superRegion }) { group ->
                        Text(group.superRegion, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
                        Text(group.regions.joinToString(" · "), color = White, fontSize = 12.sp, lineHeight = 18.sp)
                        HorizontalDivider(Modifier.padding(top = 8.dp), color = Raised)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(txt("Close", "Kapat")) } }
    )
}

internal data class CrosshairProfile(
    val name: String,
    val tag: String,
    val code: String,
    val color: Color,
    val length: Float,
    val thickness: Float,
    val gap: Float,
    val dot: Boolean = false,
    val outline: Boolean = true
)

internal object CrosshairLibrary {
    val profiles = listOf(
        CrosshairProfile("TenZ", "Cyan Classic", "0;s;1;P;c;5;h;0;m;1;0l;4;0o;2;0a;1;0f;0;1b;0;S;c;4;o;1", Color(0xFF00FFFF), 4f, 2f, 2f, dot = false, outline = false),
        CrosshairProfile("Demon1", "White Dot", "0;s;1;P;o;1;d;1;m;1;0b;0;1b;0", Color.White, 0f, 2f, 0f, dot = true, outline = true),
        CrosshairProfile("Aspas", "Clean Cyan", "0;P;c;5;o;1;d;1;z;3;f;0;0b;0;1b;0", Color(0xFF00FFFF), 0f, 2.5f, 0f, dot = true, outline = true),
        CrosshairProfile("Alfajer", "Compact White", "0;s;1;P;c;5;o;1;d;1;z;3;0b;0;1b;0", Color.White, 3f, 2f, 1.5f, dot = false, outline = true),
        CrosshairProfile("Derke", "Tight Cross", "0;s;1;P;u;000000FF;o;1;d;1;f;0;0b;0;1b;0", Color(0xFF00FF88), 3.5f, 2f, 2f, dot = false, outline = true),
        CrosshairProfile("Yay", "Classic White", "0;P;h;0;f;0;0l;4;0o;0;0a;1;0f;0;1b;0", Color.White, 4.5f, 2f, 0.5f, dot = false, outline = false),
        CrosshairProfile("Boaster", "Green Precision", "0;s;1;P;c;1;o;1;d;1;0l;0;0o;2;0a;1;0f;0;1t;0;1l;0;1o;0;1a;0;S;c;1;o;1", Color(0xFF22EE44), 3f, 2f, 2.5f, dot = true, outline = true),
        CrosshairProfile("Small Cross", "All-round", "0;P;c;1;h;0;0l;3;0o;2;0a;1;0f;0;1b;0", Color(0xFF00FF66), 3f, 1.8f, 2f, dot = false, outline = false)
    )
}

@Composable internal fun CrosshairLibraryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var lightBackdrop by remember { mutableStateOf(false) }
    var copiedName by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(txt("Crosshair library", "Nişangâh kütüphanesi"), Modifier.weight(1f), fontSize = 18.sp)
                TextButton(onClick = { lightBackdrop = !lightBackdrop }) {
                    Text(if(lightBackdrop) txt("Dark bg", "Koyu") else txt("Light bg", "Açık"), fontSize = 11.sp)
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Text(txt("Import in VALORANT → Settings → Crosshair → Import Profile Code.",
                    "VALORANT → Ayarlar → Nişangâh → Profil Kodunu İçe Aktar bölümüne yapıştır."),
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
                copiedName?.let { name ->
                    Text(txt("Copied $name code.", "$name kodu kopyalandı."), color = Accent, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
                }
                LazyColumn {
                    items(CrosshairLibrary.profiles, key = { it.name }) { profile ->
                        Row(Modifier.fillMaxWidth().clickable {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("VALORANT Crosshair", profile.code))
                            copiedName = profile.name
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CrosshairPreview(profile, lightBackdrop)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(profile.name, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(profile.tag, color = Muted, fontSize = 11.sp)
                                Text(profile.code, color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                            }
                            Text(txt("Copy", "Kopyala"), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        HorizontalDivider(color = Raised)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(txt("Close", "Kapat")) } }
    )
}

@Composable private fun CrosshairPreview(profile: CrosshairProfile, lightBackdrop: Boolean) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(if(lightBackdrop) Color(0xFFD8DCE2) else Color(0xFF151922)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val scale = 2.6f
            val len = profile.length * scale
            val gap = profile.gap * scale
            val stroke = profile.thickness * 1.4f
            fun drawArms(color: Color, extra: Float) {
                if(len > 0f) {
                    val w = stroke + extra
                    drawLine(color, Offset(cx - gap - len, cy), Offset(cx - gap, cy), strokeWidth = w)
                    drawLine(color, Offset(cx + gap, cy), Offset(cx + gap + len, cy), strokeWidth = w)
                    drawLine(color, Offset(cx, cy - gap - len), Offset(cx, cy - gap), strokeWidth = w)
                    drawLine(color, Offset(cx, cy + gap), Offset(cx, cy + gap + len), strokeWidth = w)
                }
                if(profile.dot) drawCircle(color, radius = (stroke + extra) / 1.8f, center = Offset(cx, cy))
            }
            if(profile.outline) drawArms(Color.Black.copy(alpha = 0.85f), 2.2f)
            drawArms(profile.color, 0f)
        }
    }
}

@Composable internal fun RadianiteCalculatorCard(walletRp: Int?) {
    var levels by remember { mutableIntStateOf(3) }
    var chromas by remember { mutableIntStateOf(1) }
    val plan = remember(levels, chromas, walletRp) { RadianitePlan.calculate(levels, chromas, walletRp) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(txt("Radianite (RP) upgrade calculator", "Radianite (RP) yükseltme hesaplayıcı"),
            color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(txt("Estimate how much RP you need for animation levels (10 RP) and variants (15 RP).",
            "Animasyon seviyeleri (10 RP) ve renk varyantları (15 RP) için gereken RP miktarını hesapla."),
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("Upgrade levels (10 RP)", "Yükseltme seviyesi (10 RP)"), color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { if(levels > 0) levels-- }) { Text("−") }
            Text("$levels", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { if(levels < 30) levels++ }) { Text("+") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("Color variants (15 RP)", "Renk varyantı (15 RP)"), color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { if(chromas > 0) chromas-- }) { Text("−") }
            Text("$chromas", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { if(chromas < 30) chromas++ }) { Text("+") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Stat("${plan.requiredRp} RP", txt("Needed", "Gereken"), Modifier.weight(1f))
            Stat(plan.walletRp?.let { "$it RP" } ?: "—", txt("Wallet RP", "Cüzdan RP"), Modifier.weight(1f))
            Stat(if(plan.walletRp == null) "—" else if(plan.deficitRp == 0) txt("Ready", "Yeterli") else "-${plan.deficitRp} RP",
                txt("Status", "Durum"), Modifier.weight(1f))
        }
    }
}

internal object CacheMaintenance {
    private val publicPrefs = listOf("esports_public_cache", "esports_detail_cache", "esports_explore_cache", "news_articles")

    private fun dirBytes(file: File?): Long {
        if(file == null || !file.exists()) return 0L
        if(file.isFile) return file.length()
        return file.listFiles()?.sumOf(::dirBytes) ?: 0L
    }

    fun bytes(context: Context): Long {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val prefBytes = publicPrefs.sumOf { File(prefsDir, "$it.xml").takeIf(File::exists)?.length() ?: 0L }
        return dirBytes(context.cacheDir) + prefBytes
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024L -> "${bytes / 1_024L} KB"
        else -> "$bytes B"
    }

    /** Clears only public catalog/esports/news/image caches. Session, favorites and history remain intact. */
    fun clearPublicCaches(context: Context) {
        publicPrefs.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        context.cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }
}

/** Filters VLR feeds to Tier-1 VCT events, regions, or search queries without extra network calls. */
internal object EsportsTierFilter {
    private val tierOneTokens = listOf("vct", "masters", "champions", "kickoff", "lock//in", "game changers championship")
    private val lowerTierExclusions = listOf("challengers", "ascension", "premier", "collegiate", "national", "academy")

    fun isTierOne(match: EsportsMatch): Boolean {
        val text = "${match.event} ${match.series}".lowercase(Locale.ROOT)
        if(lowerTierExclusions.any(text::contains)) return false
        return tierOneTokens.any(text::contains)
    }

    fun matchesRegion(match: EsportsMatch, region: String): Boolean {
        if(region == "all") return true
        val text = "${match.event} ${match.series}".lowercase(Locale.ROOT)
        return when(region) {
            "emea" -> "emea" in text || "europe" in text || "turkey" in text || "türkiye" in text
            "americas" -> "americas" in text || "north america" in text || "brazil" in text || "latam" in text
            "pacific" -> "pacific" in text || "japan" in text || "korea" in text || "oceania" in text || "sea" in text
            "cn" -> "china" in text || " cn " in " $text " || text.endsWith(" cn")
            "intl" -> "masters" in text || "champions" in text || "lock//in" in text
            else -> true
        }
    }

    fun filter(matches: List<EsportsMatch>, tierOneOnly: Boolean, region: String, query: String): List<EsportsMatch> {
        val q = normalized(query.trim())
        return matches.filter { match ->
            (!tierOneOnly || isTierOne(match)) &&
                matchesRegion(match, region) &&
                (q.isBlank() ||
                    normalized(match.team1).contains(q) ||
                    normalized(match.team2).contains(q) ||
                    normalized(match.event).contains(q))
        }
    }
}
