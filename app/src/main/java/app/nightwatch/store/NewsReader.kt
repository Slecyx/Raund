package app.nightwatch.store

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONArray
import java.net.URI

internal object NewsArticleParser {
    fun path(url: String): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "www.vlr.gg" && uri.userInfo == null && uri.port == -1)
        require(Regex("^/\\d+/[^/]+$").matches(uri.path))
        return uri.path
    }
    fun paragraphs(html: String): List<String> {
        val page = Jsoup.parse(html)
        val body = page.selectFirst(".article-body, article, .wf-card.mod-article") ?: error("Article body unavailable")
        body.select("script, style, iframe, .article-comments, .wf-hover-card, .article-ref-card").remove()
        val paragraphs = body.select("p, h2, h3, li, blockquote, .article-body > div").filter { element ->
            element.parents().none { it != body && it.tagName() in listOf("p", "li", "blockquote") }
        }.map { it.text().replace(Regex("\\s+"), " ").trim() }.filter { it.length > 1 }.distinct()
        return paragraphs.ifEmpty { listOf(body.text().trim()).filter { it.isNotBlank() } }.also { require(it.isNotEmpty()) }
    }
}

@Composable internal fun NewsReader(story: EsportsNews, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("news_articles", Context.MODE_PRIVATE) }
    var paragraphs by remember(story.url) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(story.url, retry) {
        loading = true; failed = false
        runCatching { withContext(Dispatchers.IO) {
            val path = NewsArticleParser.path(story.url)
            val key = "article-v3-$path"
            val cached = prefs.getString(key, null)?.let { raw -> runCatching { val array = JSONArray(raw); (0 until array.length()).map { array.getString(it) } }.getOrNull() }
            if(retry == 0 && !cached.isNullOrEmpty()) cached else {
                val html = Jsoup.connect("https://www.vlr.gg$path").userAgent("Raund Android companion").timeout(15000).maxBodySize(1_500_000).get().outerHtml()
                NewsArticleParser.paragraphs(html).also { prefs.edit().putString(key, JSONArray(it).toString()).apply() }
            }
        } }.onSuccess { paragraphs = it }.onFailure { failed = true }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
        item { TextButton(onClick = onBack) { Text(txt("← News", "← Haberler")) } }
        item {
            Text(story.title, color = White, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Text("VLR.gg · ${story.date}", color = Muted, fontSize = 11.sp)
            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Raised)
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(failed) {
                Note(txt("The article could not be loaded.", "Haber metni yüklenemedi."))
                TextButton(onClick = { retry++ }) { Text(txt("Retry", "Tekrar dene")) }
            }
        }
        items(paragraphs) { paragraph -> SelectionContainer {
            Text(paragraph, color = White, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.padding(bottom = 18.dp))
        } }
        item { Spacer(Modifier.navigationBarsPadding().height(18.dp)) }
    }
}
