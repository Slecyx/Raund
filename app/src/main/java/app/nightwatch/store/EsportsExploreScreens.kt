package app.nightwatch.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun darkSilhouette(drawable: Drawable): Boolean = runCatching {
    val source = (drawable as? BitmapDrawable)?.bitmap ?: return false
    val bitmap = if(source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
    var opaque = 0
    var dark = 0
    var bright = 0
    val stepX = (bitmap.width / 24).coerceAtLeast(1)
    val stepY = (bitmap.height / 24).coerceAtLeast(1)
    for(y in 0 until bitmap.height step stepY) for(x in 0 until bitmap.width step stepX) {
        val pixel = bitmap.getPixel(x, y)
        if(android.graphics.Color.alpha(pixel) < 80) continue
        opaque++
        val light = (android.graphics.Color.red(pixel) * 299 + android.graphics.Color.green(pixel) * 587 +
            android.graphics.Color.blue(pixel) * 114) / 1000
        if(light < 36) dark++
        if(light > 150) bright++
    }
    if(bitmap !== source) bitmap.recycle()
    opaque > 0 && dark * 5 >= opaque * 4 && bright * 20 <= opaque
}.getOrDefault(false)

@Composable internal fun TeamLogo(name: String, url: String?, size: Dp = 48.dp) {
    val context = LocalContext.current
    val request = remember(url) { coil.request.ImageRequest.Builder(context).data(url).size(200).allowHardware(false).crossfade(false).build() }
    var loaded by remember(url) { mutableStateOf(false) }
    var lighten by remember(url) { mutableStateOf(false) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if(!loaded) Text(name.trim().take(2).uppercase(), color = White, fontSize = (size.value * .28f).sp, fontWeight = FontWeight.Bold)
        if(!url.isNullOrBlank()) {
            AsyncImage(request, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                colorFilter = if(lighten) ColorFilter.tint(White) else null,
                onSuccess = { loaded = true; lighten = darkSilhouette(it.result.drawable) }, onError = { loaded = false })
        }
    }
}

@Composable internal fun EsportsEventRow(event: EsportsEvent, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(event.title, event.image, 60.dp)
        Column(Modifier.weight(1f).padding(start = 15.dp)) {
            Text(event.title, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(event.status, event.dates).filter { it.isNotBlank() }.joinToString(" · "), color = Muted,
                fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Text("›", color = Muted, fontSize = 23.sp)
    }
    HorizontalDivider(color = Raised.copy(alpha = .7f))
}

@Composable internal fun EsportsFeaturedEvent(event: EsportsEvent, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(top = 18.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(event.title, event.image, 82.dp)
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(txt("FEATURED TOURNAMENT", "ÖNE ÇIKAN TURNUVA"), color = Accent, fontSize = 9.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(event.title, color = White, fontSize = 21.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
            Text(listOf(event.status, event.dates).filter(String::isNotBlank).joinToString(" · "), color = Muted,
                fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        }
        Text("›", color = Muted, fontSize = 26.sp)
    }
    HorizontalDivider(color = Raised.copy(alpha = .65f))
}

@Composable internal fun EsportsNewsRow(news: EsportsNews, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 18.dp)) {
        Text(news.date, color = Accent, fontSize = 10.sp, maxLines = 1)
        Text(news.title, color = White, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 7.dp))
        if(news.summary.isNotBlank()) Text(news.summary, color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
    HorizontalDivider(color = Raised.copy(alpha = .7f))
}

@Composable internal fun EsportsFeaturedNews(news: EsportsNews, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(top = 20.dp, bottom = 17.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("LATEST", "MANŞET"), color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
            Text(news.date, color = Muted, fontSize = 10.sp)
        }
        Text(news.title, color = White, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 10.dp))
        if(news.summary.isNotBlank()) Text(news.summary, color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(txt("Read in Raund  →", "Raund'da oku  →"), color = White, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 13.dp))
    }
    HorizontalDivider(color = Raised.copy(alpha = .7f))
}

@Composable private fun ExploreBack(label: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("← $label", color = Accent) }
        Spacer(Modifier.weight(1f))
        Text("VLR.gg", color = Muted, fontSize = 10.sp)
    }
}

@Composable internal fun TournamentScreen(event: EsportsEvent, followed: Boolean, spoilerMode: Boolean, onFollow: () -> Unit, onBack: () -> Unit,
                                         onMatch: (EsportsMatch) -> Unit, onTeam: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repo = remember { EsportsExploreRepository(context) }
    var detail by remember(event.id) { mutableStateOf(repo.cachedTournament(event)) }
    var failed by remember(event.id) { mutableStateOf(false) }
    var phase by remember(event.id) { mutableStateOf("") }
    var tab by remember(event.id) { mutableStateOf("overview") }
    LaunchedEffect(event.id) { runCatching { repo.tournament(event) }.onSuccess { detail = it }.onFailure { failed = true } }
    val value = detail
    LazyColumn(contentPadding = PaddingValues(horizontal = 21.dp, vertical = 12.dp)) {
        item { ExploreBack(txt("Events","Turnuvalar"), onBack) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(value?.event?.image ?: event.image, event.title,
                    Modifier.size(78.dp).padding(end = 14.dp), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f)) {
                    Text(value?.event?.title ?: event.title, color = White, fontSize = 23.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold)
                    Text(listOf(event.status, event.dates).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
            Button(onClick = onFollow, modifier = Modifier.fillMaxWidth(), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = if(followed) Raised else White)) {
                Text(if(followed) txt("✓ Following tournament · alerts on", "✓ Turnuva takipte · uyarılar açık")
                    else txt("＋ Follow every match", "＋ Tüm maçları takip et"), color = if(followed) White else Ink)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TournamentTab(txt("Overview", "Özet"), tab == "overview", Modifier.weight(1f)) { tab = "overview" }
                TournamentTab(txt("Matches", "Maçlar"), tab == "matches", Modifier.weight(1f)) { tab = "matches" }
                TournamentTab(txt("Bracket", "Tablo"), tab == "bracket", Modifier.weight(1f)) { tab = "bracket" }
                TournamentTab(txt("Teams", "Takımlar"), tab == "teams", Modifier.weight(1f)) { tab = "teams" }
            }
            if(detail == null) Note(if(failed) txt("Tournament details are unavailable.","Turnuva detayları alınamadı.")
                else txt("Loading bracket and teams…","Eşleşmeler ve takımlar yükleniyor…"))
            val checked = repo.tournamentCheckedAt(event)
            Text(txt("Source: VLR.gg · Checked ", "Kaynak: VLR.gg · Kontrol ") + if(checked > 0) timeLabel(checked) else "—",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            if(failed && detail != null) Note(txt("Could not refresh; showing cached tournament details.", "Yenilenemedi; kaydedilmiş turnuva bilgileri gösteriliyor."))
        }
        if(value != null) {
            if(tab == "overview" && value.description.isNotBlank()) item { Text(value.description, color = Muted, fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 12.dp)) }
            if(tab == "overview") item {
                Row(Modifier.fillMaxWidth().padding(vertical = 15.dp)) {
                    TournamentMetric(value.teams.size.toString(), txt("Teams","Takım"), Modifier.weight(.75f))
                    TournamentMetric(value.matches.size.toString(), txt("Matches","Maç"), Modifier.weight(.75f))
                    TournamentMetric(value.prize.ifBlank { "—" }, txt("Prize","Ödül"), Modifier.weight(1.5f))
                }
                HorizontalDivider(color = Raised)
            }
            if(tab == "overview") item {
                val teams = context.getSharedPreferences("nightwatch", 0).getStringSet("esports_teams", emptySet()).orEmpty()
                val relevant = value.matches.filter { EsportsReminders.follows(it, emptySet(), teams, emptyList()) }
                val next = relevant.filter { it.status == "live" || it.status == "upcoming" }.sortedWith(compareBy<EsportsMatch> {
                    if(it.status == "live") 0 else 1
                }.thenBy { it.startAtMillis ?: Long.MAX_VALUE }).take(2)
                SectionTitle(txt("Your tournament route", "Turnuvadaki rotan"))
                if(teams.isEmpty()) TextButton(onClick = { tab = "teams" }) { Text(txt("Choose a team to follow", "Takip edeceğin takımı seç")) }
                else if(next.isEmpty()) Text(txt("No next opponent has been published for your followed teams. Check the bracket for announced pairings.",
                    "Takip ettiğin takımların sıradaki rakibi yayımlanmamış. Açıklanan eşleşmeleri tabloda görebilirsin."), color = Muted, fontSize = 12.sp)
                next.forEach { match -> TeamMatchRow(match, spoilerMode) { onMatch(match) } }
                TextButton(onClick = { tab = "bracket" }) { Text(txt("View their bracket path →", "Tablodaki yollarını gör →")) }
            }
            if(tab == "overview" && value.stages.isNotEmpty()) {
                item { SectionTitle(txt("Stages","Aşamalar"), value.stages.size.toString()) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(value.stages) { stage -> Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                        Text(stage, color = White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp))
                    } }
                } }
            }
            if((tab == "overview" || tab == "matches") && value.matches.isNotEmpty()) {
                item { SectionTitle(txt("Schedule & results","Program ve sonuçlar"), value.matches.size.toString()) }
                items(if(tab == "overview") value.matches.take(4) else value.matches, key = { "event-match-${it.id}" }) { match -> TeamMatchRow(match, spoilerMode) { onMatch(match) } }
            }
            if(tab == "matches" && value.matches.isEmpty()) item { Note(txt("No matches have been published yet.", "Henüz yayımlanmış maç yok.")) }
            val standings = tournamentStandings(value.matches).let { if(spoilerMode) it.sortedBy { row -> row.team } else it }
            if(tab == "overview" && standings.isNotEmpty()) {
                item { SectionTitle(txt("Recorded match form","Yayımlanan maçlarda form"), standings.size.toString())
                    Text(txt("Computed from available results; not official tournament standings.", "Mevcut sonuçlardan hesaplanır; resmî turnuva sıralaması değildir."), color = Muted, fontSize = 12.sp) }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text("#", color = Muted, fontSize = 10.sp, modifier = Modifier.width(25.dp))
                        Text(txt("TEAM","TAKIM"), color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text("O", color = Muted, fontSize = 10.sp, modifier = Modifier.width(35.dp))
                        Text("G", color = Muted, fontSize = 10.sp, modifier = Modifier.width(35.dp))
                        Text("M", color = Muted, fontSize = 10.sp, modifier = Modifier.width(35.dp))
                        Text("±", color = Muted, fontSize = 10.sp, modifier = Modifier.width(35.dp))
                    }
                }
                items(standings, key = { "standing-${it.team}" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if(spoilerMode) "—" else (standings.indexOf(row) + 1).toString(), color = Muted, fontSize = 11.sp, modifier = Modifier.width(25.dp))
                        TeamLogo(row.team, row.logo, 26.dp)
                        Text(row.team, color = White, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if(spoilerMode) Text("•••", color = Muted, fontSize = 11.sp, modifier = Modifier.width(140.dp))
                        else {
                            Text(row.played.toString(), color = White, fontSize = 11.sp, modifier = Modifier.width(35.dp))
                            Text(row.wins.toString(), color = Accent, fontSize = 11.sp, modifier = Modifier.width(35.dp))
                            Text(row.losses.toString(), color = White, fontSize = 11.sp, modifier = Modifier.width(35.dp))
                            Text(if(row.mapDifference > 0) "+${row.mapDifference}" else row.mapDifference.toString(), color = White, fontSize = 11.sp, modifier = Modifier.width(35.dp))
                        }
                    }
                    HorizontalDivider(color = Raised.copy(alpha = .55f))
                }
            }
            if(tab == "bracket") item { SectionTitle(txt("Bracket","Eşleşme ağacı"), value.bracket.size.toString()) }
            if(tab == "bracket" && value.bracket.isEmpty()) item { Note(txt("No bracket has been published for this stage.","Bu aşama için eşleşme ağacı henüz yayımlanmadı.")) }
            else if(tab == "bracket") item {
                val phases = value.bracket.map { it.phase }.distinct()
                val active = phase.takeIf { it in phases } ?: phases.first()
                if(phases.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    phases.forEach { item ->
                        val label = when(item) { "upper" -> txt("Upper bracket","Üst tablo"); "lower" -> txt("Lower bracket","Alt tablo"); else -> txt("Finals","Finaller") }
                        Column(Modifier.clickable { phase = item }.padding(vertical = 7.dp)) {
                            Text(label, color = if(active == item) White else Muted, fontSize = 13.sp,
                                fontWeight = if(active == item) FontWeight.Bold else FontWeight.Normal)
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.width(25.dp).height(2.dp).background(if(active == item) Accent else androidx.compose.ui.graphics.Color.Transparent))
                        }
                    }
                }
                BracketTree(value.bracket.filter { it.phase == active }, event, spoilerMode, onMatch)
            }
            if(tab == "teams" && value.teams.isNotEmpty()) {
                item { SectionTitle(txt("Teams","Takımlar"), value.teams.size.toString()) }
                items(value.teams, key = { it.second }) { (name, url) ->
                    Row(Modifier.fillMaxWidth().clickable { onTeam(url) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val logo = value.bracket.firstNotNullOfOrNull { entry -> when(name) {
                            entry.team1 -> entry.logo1
                            entry.team2 -> entry.logo2
                            else -> null
                        } }
                        TeamLogo(name, logo, 42.dp)
                        Text(name, color = White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 13.dp))
                        Text("›", color = Muted, fontSize = 20.sp)
                    }
                    HorizontalDivider(color = Raised.copy(alpha = .65f))
                }
            }
            if(tab == "teams" && value.teams.isEmpty()) item { Note(txt("Team list is not available yet.", "Takım listesi henüz yayımlanmadı.")) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun TournamentMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = White, fontSize = when { value.length > 16 -> 12.sp; value.length > 10 -> 15.sp; else -> 21.sp },
            lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable private fun TournamentTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if(selected) White else Muted, fontSize = 11.sp,
            fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(if(selected) 24.dp else 1.dp).height(2.dp)
            .background(if(selected) Accent else androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable private fun BracketTree(entries: List<BracketEntry>, event: EsportsEvent, spoilerMode: Boolean, onMatch: (EsportsMatch) -> Unit) {
    val rounds = entries.groupBy { it.round }.values.toList()
    if(rounds.isEmpty()) return
    val columnWidth = 248
    val cardWidth = 218
    val cardHeight = 100
    val slotHeight = 122
    val headerHeight = 42
    val requiredSlots = rounds.indices.maxOf { round -> (1 shl round.coerceAtMost(5)) * rounds[round].size.coerceAtLeast(1) }
    val totalWidth = columnWidth * rounds.size
    val totalHeight = headerHeight + requiredSlots * slotHeight + 18
    fun center(round: Int, index: Int): Float = headerHeight + ((index + .5f) * (1 shl round.coerceAtMost(5)) * slotHeight)
    var zoom by remember(entries) { mutableFloatStateOf(.9f) }
    var pan by remember(entries) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val fit = if(viewport.width > 0) minOf(viewport.width / (totalWidth * density), viewport.height / (totalHeight * density)).coerceIn(.1f, 1.65f) else .9f
    val transform = rememberTransformableState { change, panChange, _ ->
        val nextZoom = (zoom * change).coerceIn(.1f, 1.65f)
        val zoomRatio = nextZoom / zoom
        pan = Offset((pan.x + panChange.x) * zoomRatio, (pan.y + panChange.y) * zoomRatio)
        zoom = nextZoom
    }
    val connectorColor = Muted.copy(alpha = .48f)
    val viewportHeight = minOf(520f, totalHeight * .9f).coerceAtLeast(330f)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(txt("Tournament tree", "Turnuva ağacı"), color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(txt("One finger: move · two fingers: zoom", "Tek parmak: taşı · iki parmak: yakınlaştır"), color = Muted, fontSize = 9.sp)
            }
            TextButton(onClick = { zoom = (zoom - .12f).coerceAtLeast(.1f) }) { Text("−", color = White, fontSize = 20.sp) }
            Text("${(zoom * 100).toInt()}%", color = Muted, fontSize = 10.sp, modifier = Modifier.width(43.dp))
            TextButton(onClick = { zoom = (zoom + .12f).coerceAtMost(1.65f) }) { Text("+", color = White, fontSize = 18.sp) }
            TextButton(onClick = { zoom = fit; pan = Offset.Zero }, contentPadding = PaddingValues(horizontal = 5.dp)) {
                Text(txt("Fit", "Sığdır"), color = Muted, fontSize = 9.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(viewportHeight.dp).clip(RoundedCornerShape(14.dp))
            .onSizeChanged { viewport = it }.background(Ink).transformable(transform, canPan = { true }, lockRotationOnZoomPan = true)) {
                Box(Modifier.width(totalWidth.dp).height(totalHeight.dp).graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x; translationY = pan.y
                    transformOrigin = TransformOrigin(0f, 0f)
                }) {
                    Canvas(Modifier.fillMaxSize()) {
                        rounds.dropLast(1).forEachIndexed { roundIndex, matches ->
                            val next = rounds[roundIndex + 1]
                            matches.forEachIndexed { index, _ ->
                                val target = (index / 2).coerceAtMost(next.lastIndex)
                                val x1 = (roundIndex * columnWidth + cardWidth).dp.toPx()
                                val x2 = ((roundIndex + 1) * columnWidth).dp.toPx()
                                val mid = (x1 + x2) / 2f
                                val y1 = center(roundIndex, index).dp.toPx()
                                val y2 = center(roundIndex + 1, target).dp.toPx()
                                val stroke = 1.dp.toPx()
                                drawLine(connectorColor, Offset(x1, y1), Offset(mid, y1), stroke)
                                drawLine(connectorColor, Offset(mid, y1), Offset(mid, y2), stroke)
                                drawLine(connectorColor, Offset(mid, y2), Offset(x2, y2), stroke)
                            }
                        }
                    }
                    rounds.forEachIndexed { roundIndex, matches ->
                        Text(matches.first().round.uppercase(Locale.ROOT), color = Muted, fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.offset(x = (roundIndex * columnWidth).dp).width(cardWidth.dp).padding(vertical = 7.dp))
                        matches.forEachIndexed { index, entry ->
                            BracketMatchRow(entry, spoilerMode, Modifier.offset(x = (roundIndex * columnWidth).dp,
                                y = (center(roundIndex, index) - cardHeight / 2f).dp)
                                .width(cardWidth.dp).height(cardHeight.dp)) {
                                val id = Regex("/(\\d+)/").find(entry.matchUrl)?.groupValues?.get(1) ?: return@BracketMatchRow
                                onMatch(EsportsMatch(id, entry.team1, entry.team2, entry.score1, entry.score2,
                                    event.title, entry.round, if(entry.score1 != null && entry.score2 != null) "result" else "upcoming", "",
                                    entry.matchUrl, entry.startAtMillis, entry.logo1, entry.logo2, event.image))
                            }
                        }
                    }
                }
        }
    }
}

@Composable private fun BracketMatchRow(entry: BracketEntry, spoilerMode: Boolean, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Panel).border(1.dp,
        Raised, RoundedCornerShape(10.dp)).clickable(onClick = onOpen)) {
        val date = entry.startAtMillis?.let { DateTimeFormatter.ofPattern("dd MMM · HH:mm", if(LocalTurkish.current) Locale.forLanguageTag("tr") else Locale.ENGLISH)
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }.orEmpty()
        Text(date.ifBlank { entry.round }, color = Muted, fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 6.dp), maxLines = 1)
        listOf(Triple(entry.team1, entry.logo1, entry.score1), Triple(entry.team2, entry.logo2, entry.score2)).forEach { (name, logo, score) ->
            val opponent = if(name == entry.team1) entry.score2 else entry.score1
            val winner = !spoilerMode && score != null && opponent != null && score > opponent
            Row(Modifier.fillMaxWidth().height(34.dp).background(if(winner) White.copy(alpha = .045f) else androidx.compose.ui.graphics.Color.Transparent),
                verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(name, logo, 27.dp)
                Text(name, color = if(!spoilerMode && entry.eliminated == name) Muted else White, fontSize = 11.sp,
                    fontWeight = if(winner) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 7.dp))
                Text(if(spoilerMode && score != null) "•" else score?.toString() ?: "–", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(34.dp).padding(start = 5.dp))
            }
        }
    }
}

@Composable internal fun TeamScreen(url: String, followedTeams: Set<String>, schedule: List<EsportsMatch>, onBack: () -> Unit,
                                    onPlayer: (String) -> Unit, onMatch: (EsportsMatch) -> Unit,
                                    onFollow: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repo = remember { EsportsExploreRepository(context) }
    var team by remember(url) { mutableStateOf(repo.cachedTeam(url)) }
    var failed by remember(url) { mutableStateOf(false) }
    var showAllUpcoming by rememberSaveable(url) { mutableStateOf(false) }
    var showAllHistory by rememberSaveable(url) { mutableStateOf(false) }
    LaunchedEffect(url) { runCatching { repo.team(url) }.onSuccess { team = it }.onFailure { failed = true } }
    LazyColumn(contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
        item { ExploreBack(txt("Match","Maç"), onBack) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(team?.name.orEmpty(), team?.image, 74.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(team?.name ?: txt("Team","Takım"), color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    team?.let { value ->
                        Text(if(value.name in followedTeams) txt("♥ Following · alerts on","♥ Takipte · uyarılar açık")
                            else txt("♡ Follow team","♡ Takımı takip et"), color = if(value.name in followedTeams) White else Muted, fontSize = 12.sp,
                            modifier = Modifier.clickable { onFollow(value.name) }.padding(top = 7.dp, bottom = 7.dp))
                    }
                }
            }
            if(team == null) Note(if(failed) txt("Team details could not be loaded.","Takım bilgileri yüklenemedi.")
                else txt("Loading roster…","Kadro yükleniyor…"))
        }
        team?.let { value ->
            val allMatches = (value.matches + schedule.filter { it.team1.equals(value.name, true) || it.team2.equals(value.name, true) })
                .distinctBy { it.id }
            val results = allMatches.filter { it.status == "result" && it.score1 != null && it.score2 != null }
            val nextMatch = allMatches.filter { it.status == "upcoming" }
                .minByOrNull { it.startAtMillis ?: Long.MAX_VALUE }
            val wins = results.count { match ->
                if(value.name.equals(match.team1, true)) (match.score1 ?: 0) > (match.score2 ?: 0)
                else (match.score2 ?: 0) > (match.score1 ?: 0)
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
                    Stat(allMatches.count { it.status == "upcoming" }.toString(), txt("Upcoming","Yaklaşan"), Modifier.weight(1f))
                    Stat(results.size.toString(), txt("Recent","Geçmiş"), Modifier.weight(1f))
                    Stat(if(SpoilerPolicy.enabled(context)) "•••" else if(results.isEmpty()) "—" else "${wins * 100 / results.size}%", txt("Win rate","Kazanma"), Modifier.weight(1f))
                }
            }
            if(nextMatch != null) {
                item {
                    Text(txt("NEXT MATCH","SONRAKİ MAÇ"), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, modifier = Modifier.padding(top = 9.dp, bottom = 3.dp))
                    Surface(onClick = { onMatch(nextMatch) }, color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(listOf(nextMatch.event, nextMatch.series).filter(String::isNotBlank).joinToString(" · "),
                                color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                TeamLogo(nextMatch.team1, nextMatch.team1Logo, 34.dp)
                                Text("${nextMatch.team1}  ×  ${nextMatch.team2}", color = White, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                TeamLogo(nextMatch.team2, nextMatch.team2Logo, 34.dp)
                            }
                            Text(nextMatch.startAtMillis?.let { localMatchTimeForTeam(it) }
                                ?: esportsDisplayWhen(nextMatch.whenText, LocalTurkish.current), color = Accent, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 9.dp))
                        }
                    }
                }
            }
            val upcoming = allMatches.filter { it.status == "upcoming" }.sortedBy { it.startAtMillis ?: Long.MAX_VALUE }
            val past = allMatches.filter { it.status == "result" }
            if(past.isNotEmpty() && !SpoilerPolicy.enabled(context)) item {
                val form = past.take(5).map { match ->
                    val won = if(value.name.equals(match.team1, true)) (match.score1 ?: 0) > (match.score2 ?: 0)
                        else (match.score2 ?: 0) > (match.score1 ?: 0)
                    if(won) "G" else "M"
                }
                Row(Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(txt("RECENT FORM", "SON FORM"), color = Muted, fontSize = 9.sp, letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f))
                    form.forEach { result ->
                        Text(result, color = if(result == "G") White else Muted, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
            if(upcoming.isNotEmpty()) {
                item { SectionTitle(txt("Upcoming matches","Yaklaşan maçlar"), upcoming.size.toString()) }
                items(upcoming.take(if(showAllUpcoming) 14 else 5), key = { "upcoming-${it.id}" }) { match -> TeamMatchRow(match) { onMatch(match) } }
                if(upcoming.size > 5) item {
                    TextButton(onClick = { showAllUpcoming = !showAllUpcoming }, modifier = Modifier.fillMaxWidth()) {
                        Text(if(showAllUpcoming) txt("Show less", "Daha az göster") else txt("Show full fixture", "Tüm fikstürü göster"))
                    }
                }
            }
            item { SectionTitle(txt("Players","Oyuncular"), value.players.size.toString()) }
            items(value.players, key = { it.url }) { player ->
                Row(Modifier.fillMaxWidth().clickable { onPlayer(player.url) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(Raised), contentAlignment = Alignment.Center) {
                        Text(player.name.take(1).uppercase(), color = Muted)
                        if(player.image != null) AsyncImage(player.image, player.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(player.name, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if(player.realName.isNotBlank()) Text(player.realName, color = Muted, fontSize = 11.sp)
                    }
                    Text("›", color = Muted, fontSize = 20.sp)
                }
                HorizontalDivider(color = Raised.copy(alpha = .65f))
            }
            if(past.isNotEmpty()) {
                item { SectionTitle(txt("Match archive","Maç arşivi"), past.size.toString()) }
                items(past.take(if(showAllHistory) 15 else 6), key = { "past-${it.id}" }) { match -> TeamMatchRow(match) { onMatch(match) } }
                if(past.size > 6) item {
                    TextButton(onClick = { showAllHistory = !showAllHistory }, modifier = Modifier.fillMaxWidth()) {
                        Text(if(showAllHistory) txt("Show less", "Daha az göster") else txt("Open full recent history", "Tüm yakın geçmişi aç"))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun localMatchTimeForTeam(startAtMillis: Long): String = DateTimeFormatter
    .ofPattern("dd MMM · HH:mm", if(LocalTurkish.current) Locale.forLanguageTag("tr") else Locale.ENGLISH)
    .format(Instant.ofEpochMilli(startAtMillis).atZone(ZoneId.systemDefault()))

@Composable private fun TeamMatchRow(match: EsportsMatch, spoilerMode: Boolean = false, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp)) {
        Text(listOf(match.event, esportsDisplayWhen(match.whenText, LocalTurkish.current)).filter { it.isNotBlank() }.joinToString(" · "),
            color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        listOf(Triple(match.team1, match.team1Logo, match.score1), Triple(match.team2, match.team2Logo, match.score2))
            .forEach { (name, logo, score) ->
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(name, logo, 26.dp)
                    Text(name, color = White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 10.dp))
                    Text(if(spoilerMode) "•" else score?.toString() ?: "—", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
    }
    HorizontalDivider(color = Raised.copy(alpha = .65f))
}

@Composable private fun LegacyPlayerScreen(url: String, news: List<EsportsNews>, followed: Boolean,
                                      onFollow: () -> Unit, onBack: () -> Unit) {
    var openedNews by remember { mutableStateOf<EsportsNews?>(null) }
    openedNews?.let { story -> NewsReader(story) { openedNews = null }; return }
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repo = remember { EsportsExploreRepository(context) }
    var profile by remember(url) { mutableStateOf(repo.cachedPlayer(url)) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) { runCatching { repo.player(url) }.onSuccess { profile = it }.onFailure { failed = true } }
    val related = profile?.player?.name?.takeIf { it.length >= 3 }?.let { alias ->
        news.filter { item -> Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(alias)}(?![\\p{L}\\p{N}])").containsMatchIn(item.title + " " + item.summary) }
    }.orEmpty()
    LazyColumn(contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
        item { ExploreBack(txt("Team","Takım"), onBack) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(Raised), contentAlignment = Alignment.Center) {
                    Text(profile?.player?.name?.take(1).orEmpty(), color = Muted, fontSize = 24.sp)
                    profile?.player?.image?.let { AsyncImage(it, profile?.player?.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.padding(start = 17.dp)) {
                    Text(profile?.player?.name ?: txt("Player","Oyuncu"), color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    profile?.player?.realName?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 13.sp) }
                    profile?.country?.takeIf { it.isNotBlank() }?.let { Text(it, color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
                    Text(if(followed) txt("♥ Favorite player","♥ Favori oyuncu") else txt("♡ Add favorite","♡ Favori oyuncu ekle"),
                        color = if(followed) White else Muted, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onFollow).padding(top = 9.dp, bottom = 6.dp))
                }
            }
            if(profile == null) Note(if(failed) txt("Player profile is unavailable.","Oyuncu profili yüklenemedi.")
                else txt("Loading player profile…","Oyuncu profili yükleniyor…"))
        }
        profile?.let { value ->
            if(value.agents.isNotEmpty()) {
                val kills = value.agents.sumOf { it.kills.toIntOrNull() ?: 0 }
                val deaths = value.agents.sumOf { it.deaths.toIntOrNull() ?: 0 }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 17.dp, bottom = 7.dp)) {
                        Stat(kills.toString(), txt("Kills","Öldürme"), Modifier.weight(1f))
                        Stat(deaths.toString(), txt("Deaths","Ölüm"), Modifier.weight(1f))
                        Stat(if(deaths > 0) String.format(Locale.ROOT, "%.2f", kills.toFloat()/deaths) else "—", "K/D", Modifier.weight(1f))
                    }
                }
                item { SectionTitle(txt("Recent agent stats","Son ajan istatistikleri")) }
                items(value.agents) { agent ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(agent.image, agent.name, Modifier.size(40.dp), contentScale = ContentScale.Fit)
                        Text(agent.name, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 9.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${agent.acs} ACS", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("${agent.rating} R · ${agent.kd} K/D · ${agent.adr} ADR", color = Muted, fontSize = 10.sp)
                            Text(agent.usage, color = Muted, fontSize = 10.sp)
                        }
                    }
                    HorizontalDivider(color = Raised.copy(alpha = .65f))
                }
            }
            item { SectionTitle(txt("In the news","Haberlerde"), related.size.toString()) }
            if(related.isEmpty()) item { Note(txt("No recent headline mentions this player.","Son haber başlıklarında bu oyuncudan söz edilmiyor.")) }
            items(related, key = { it.url }) { story ->
                EsportsNewsRow(story) { openedNews = story }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
