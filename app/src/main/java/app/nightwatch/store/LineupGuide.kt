package app.nightwatch.store

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class LineupVideo(
    val videoId: String,
    val title: String,
    val channel: String,
    val duration: String,
    val map: String,
    val agent: String
) {
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    val embedUrl: String get() = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&rel=0&modestbranding=1&playsinline=1"
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
}

internal object LineupCatalog {
    val maps = listOf("Ascent", "Bind", "Haven", "Split", "Icebox", "Lotus", "Sunset", "Abyss", "Pearl", "Breeze", "Fracture", "Corrode")
    val agents = listOf("Sova", "Viper", "Brimstone", "Killjoy", "KAY/O", "Fade", "Gekko", "Cypher", "Tejo", "Deadlock", "Vyse")
    val sites = listOf("All", "A Site", "B Site", "C Site", "Mid")
    private val videoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

    fun isValidVideoId(id: String?): Boolean = id != null && videoIdRegex.matches(id)

    fun buildQuery(map: String, agent: String, side: String, site: String): String = listOfNotNull(
        "valorant",
        map,
        agent,
        site.takeIf { it != "All" },
        when(side) { "attack" -> "attack post plant"; "defense" -> "defense setup"; else -> null },
        "lineup"
    ).joinToString(" ")

    fun youtubeSearchUrl(map: String, agent: String, side: String = "all", site: String = "All"): String {
        val encoded = URLEncoder.encode(buildQuery(map, agent, side, site), StandardCharsets.UTF_8.toString())
        return "https://www.youtube.com/results?search_query=$encoded"
    }

    /** Extracts real YouTube video entries from ytInitialData in the public search HTML. */
    fun parseVideos(html: String, map: String, agent: String): List<LineupVideo> {
        val marker = "var ytInitialData = "
        val start = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length)
            ?: html.indexOf("ytInitialData\"]:").takeIf { it >= 0 }
            ?: return emptyList()
        val end = html.indexOf(";</script>", start).takeIf { it > start } ?: return emptyList()
        val jsonText = html.substring(start, end).trim()
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<LineupVideo>()
        val seen = hashSetOf<String>()

        fun walk(node: Any?) {
            if(results.size >= 16) return
            when(node) {
                is JSONObject -> {
                    val renderer = node.optJSONObject("videoRenderer")
                    if(renderer != null) {
                        val id = renderer.optString("videoId")
                        if(isValidVideoId(id) && seen.add(id)) {
                            val title = renderer.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                                .ifBlank { renderer.optJSONObject("title")?.optString("simpleText").orEmpty() }
                            val channel = renderer.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                            val duration = renderer.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                            if(title.isNotBlank()) {
                                results += LineupVideo(id, title.take(120), channel.take(60), duration.take(16), map, agent)
                            }
                        }
                    } else {
                        val keys = node.keys()
                        while(keys.hasNext() && results.size < 16) walk(node.opt(keys.next()))
                    }
                }
                is org.json.JSONArray -> {
                    for(i in 0 until node.length()) {
                        if(results.size >= 16) break
                        walk(node.opt(i))
                    }
                }
            }
        }
        walk(root)
        return results
    }

    suspend fun fetchVideos(context: Context, map: String, agent: String, side: String, site: String, force: Boolean = false): List<LineupVideo> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("esports_explore_cache", Context.MODE_PRIVATE)
        val query = buildQuery(map, agent, side, site)
        val cacheKey = "lineup_v1_${query.hashCode()}"
        val cachedAt = prefs.getLong("${cacheKey}_at", 0L)
        if(!force && System.currentTimeMillis() - cachedAt < 6 * 60 * 60_000L) {
            val cached = prefs.getString(cacheKey, null)?.let { raw ->
                runCatching { Gson().fromJson(raw, Array<LineupVideo>::class.java).toList() }.getOrNull()
            }
            if(!cached.isNullOrEmpty()) return@withContext cached
        }
        val url = youtubeSearchUrl(map, agent, side, site)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 12_000
        // Desktop UA ensures standard unescaped ytInitialData JSON; CONSENT cookie avoids EU/TR interstitial pages.
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
        val html = try {
            if(connection.responseCode !in 200..299) error("Video search HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder(); val buf = CharArray(8_192)
                while(true) {
                    val count = reader.read(buf); if(count < 0) break
                    if(out.length + count > 2_500_000) break
                    out.append(buf, 0, count)
                }
                out.toString()
            }
        } finally { connection.disconnect() }
        val parsed = parseVideos(html, map, agent)
        if(parsed.isNotEmpty()) {
            prefs.edit().apply {
                putString(cacheKey, Gson().toJson(parsed))
                putLong("${cacheKey}_at", System.currentTimeMillis())
                prefs.all.entries
                    .filter { it.key.startsWith("lineup_v1_") && it.key.endsWith("_at") && it.value is Long && it.key != "${cacheKey}_at" }
                    .sortedByDescending { it.value as Long }
                    .drop(30)
                    .forEach { (atKey, _) -> remove(atKey); remove(atKey.removeSuffix("_at")) }
            }.apply()
        }
        parsed
    }
}

internal object SavedLineupsStore {
    private const val KEY = "saved_lineup_videos_v2"
    fun list(context: Context): List<LineupVideo> {
        val raw = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            Gson().fromJson(raw, Array<LineupVideo>::class.java).toList().filter { LineupCatalog.isValidVideoId(it.videoId) }
        }.getOrDefault(emptyList())
    }
    fun toggle(context: Context, video: LineupVideo): List<LineupVideo> {
        if(!LineupCatalog.isValidVideoId(video.videoId)) return list(context)
        val current = list(context)
        val next = if(current.any { it.videoId == video.videoId }) current.filterNot { it.videoId == video.videoId }
        else (listOf(video) + current).distinctBy { it.videoId }.take(40)
        context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE).edit().putString(KEY, Gson().toJson(next)).apply()
        return next
    }
}

@Composable internal fun LineupGuideTab() {
    val context = LocalContext.current
    var map by rememberSaveable { mutableStateOf("Ascent") }
    var agent by rememberSaveable { mutableStateOf("Sova") }
    var side by rememberSaveable { mutableStateOf("all") }
    var site by rememberSaveable { mutableStateOf("All") }
    var savedOnly by rememberSaveable { mutableStateOf(false) }
    var savedVideos by remember { mutableStateOf(SavedLineupsStore.list(context)) }
    var videos by remember { mutableStateOf<List<LineupVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<LineupVideo?>(null) }

    LaunchedEffect(map, agent, side, site, savedOnly, retry) {
        if(savedOnly) return@LaunchedEffect
        loading = true; failed = false
        runCatching { LineupCatalog.fetchVideos(context, map, agent, side, site, force = retry > 0) }
            .onSuccess { videos = it; failed = it.isEmpty() }
            .onFailure { videos = emptyList(); failed = true }
        loading = false
    }

    val shown = if(savedOnly) savedVideos else videos

    SectionTitle(txt("Video lineups", "Videolu lineuplar"), "${shown.size}")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        LineupCatalog.maps.forEach { m -> Chip(m, !savedOnly && map == m) { savedOnly = false; map = m } }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        LineupCatalog.agents.forEach { a -> Chip(a, !savedOnly && agent == a) { savedOnly = false; agent = a } }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Chip(txt("All", "Tümü"), !savedOnly && side == "all") { savedOnly = false; side = "all" }
        Chip(txt("Attack", "Saldırı"), !savedOnly && side == "attack") { savedOnly = false; side = "attack" }
        Chip(txt("Defense", "Savunma"), !savedOnly && side == "defense") { savedOnly = false; side = "defense" }
        LineupCatalog.sites.drop(1).forEach { s -> Chip(s, !savedOnly && site == s) { savedOnly = false; site = if(site == s) "All" else s } }
        Chip(txt("Saved", "Kayıtlı") + " (${savedVideos.size})", savedOnly) { savedOnly = !savedOnly }
    }

    playing?.let { active ->
        InAppYouTubePlayer(
            video = active,
            onClose = { playing = null },
            onOpenExternal = { openHttpsUrl(context, active.watchUrl) }
        )
        Spacer(Modifier.height(12.dp))
    }

    if(loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Accent)
    if(!savedOnly && failed && !loading) {
        Note(txt("Could not load lineup videos right now.", "Lineup videoları şu an yüklenemedi."))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { retry++ }) { Text(txt("Retry", "Tekrar dene")) }
            TextButton(onClick = { openHttpsUrl(context, LineupCatalog.youtubeSearchUrl(map, agent, side, site)) }) {
                Text(txt("Open on YouTube ↗", "YouTube'da aç ↗"))
            }
        }
    }
    if(savedOnly && shown.isEmpty()) {
        Note(txt("Tap the heart on any lineup video to save it here for quick match access.", "Maç öncesi hızlı erişim için beğendiğin lineup videolarını kalp ile buraya kaydet."))
    }

    shown.forEach { video ->
        val isSaved = savedVideos.any { it.videoId == video.videoId }
        val isPlaying = playing?.videoId == video.videoId
        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .clickable { playing = video }) {
            Box(Modifier.fillMaxWidth().height(185.dp).background(Color.Black)) {
                AsyncImage(video.thumbnailUrl, video.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(if(isPlaying) txt("Playing", "Oynuyor") else "▶  " + txt("Play", "Oynat"),
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                if(video.duration.isNotBlank()) {
                    Text(video.duration, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.8f))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(video.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(video.channel, "${video.map} · ${video.agent}").filter(String::isNotBlank).joinToString(" · "),
                        color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
                TextButton(onClick = { savedVideos = SavedLineupsStore.toggle(context, video) }) {
                    Text(if(isSaved) "♥" else "♡", color = if(isSaved) Accent else Muted, fontSize = 20.sp)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable private fun InAppYouTubePlayer(video: LineupVideo, onClose: () -> Unit, onOpenExternal: () -> Unit) {
    val context = LocalContext.current
    val webView = remember(video.videoId) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(AndroidColor.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val host = uri.host.orEmpty()
                    val inEmbed = uri.scheme == "https" && (host == "www.youtube-nocookie.com" || host == "youtube-nocookie.com" || host == "www.youtube.com" || host.endsWith(".googlevideo.com"))
                    if(!inEmbed) {
                        openHttpsUrl(context, uri.toString())
                        return true
                    }
                    return false
                }
            }
            // Loading via an HTTPS base URL supplies the Referer required by YouTube embeds (prevents Error 153).
            val safeId = video.videoId.takeIf(LineupCatalog::isValidVideoId).orEmpty()
            val html = """<!doctype html><html><body style="margin:0;background:#000;overflow:hidden;">
                <iframe width="100%" height="100%" style="position:fixed;inset:0;border:0;"
                src="https://www.youtube-nocookie.com/embed/$safeId?autoplay=1&rel=0&modestbranding=1&playsinline=1"
                referrerpolicy="strict-origin-when-cross-origin"
                allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe>
                </body></html>""".trimIndent()
            loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "UTF-8", null)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.onPause()
            webView.destroy()
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(video.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenExternal) { Text("YouTube ↗", fontSize = 11.sp) }
            TextButton(onClick = onClose) { Text("×", fontSize = 18.sp) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
