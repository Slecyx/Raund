package app.nightwatch.store

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun EsportsPage(favorites: Set<String>, onTeam: (String) -> Unit,
                                    favoritePlayers: Set<String>, onPlayerFavorite: (String) -> Unit, trackedId: String?,
                                    onTracked: (String?) -> Unit, matchAlerts: Boolean, onSettings: () -> Unit,
                                    onSubpageChanged: (Boolean) -> Unit = {}, requestedMatch: Pair<Int, EsportsMatch?> = 0 to null,
                                    onRouteConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val tr = LocalTurkish.current
    val repo = remember { EsportsRepository(context) }
    val explore = remember { EsportsExploreRepository(context) }
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf(repo.cached()) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val routePrefs = remember { context.getSharedPreferences("nightwatch", 0) }
    var selected by rememberSaveable { mutableStateOf(routePrefs.getString("esports_last_filter", null)
        ?: if(favorites.isNotEmpty()) "followed" else "upcoming") }
    var matchFilterOpen by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(routePrefs.getString("esports_last_section", "matches") ?: "matches") }
    var eventQuery by rememberSaveable { mutableStateOf("") }
    var eventFilter by rememberSaveable { mutableStateOf("live") }
    var events by remember { mutableStateOf(explore.cachedEvents()) }
    var news by remember { mutableStateOf(explore.cachedNews()) }
    var exploreLoading by remember { mutableStateOf(false) }
    var exploreError by remember { mutableStateOf(false) }
    var teamQuery by rememberSaveable { mutableStateOf("") }
    var directoryKind by remember { mutableStateOf("teams") }
    var teamResults by remember { mutableStateOf<List<EsportsTeamSearchResult>>(emptyList()) }
    var playerResults by remember { mutableStateOf<List<EsportsPlayerSearchResult>>(emptyList()) }
    var teamSearchLoading by remember { mutableStateOf(false) }
    var teamSearchError by remember { mutableStateOf(false) }
    var openedEvent by remember { mutableStateOf<EsportsEvent?>(null) }
    var openedTeam by remember { mutableStateOf<String?>(null) }
    var returnTeam by remember { mutableStateOf<String?>(null) }
    var returnPlayer by remember { mutableStateOf<String?>(null) }
    var openedPlayer by remember { mutableStateOf<String?>(null) }
    var visibleMatchCount by remember(selected, section) { mutableIntStateOf(6) }
    var permissionMatch by remember { mutableStateOf<EsportsMatch?>(null) }
    var permissionFavorite by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(SavedEsportsMatches.get(context)) }
    var watchLater by remember { mutableStateOf(WatchLater.get(context)) }
    var watchOpenedId by remember { mutableStateOf<String?>(null) }
    var showWatched by remember { mutableStateOf(false) }
    var followedEvents by remember { mutableStateOf(SavedEsportsEvents.get(context)) }
    var opened by remember { mutableStateOf<EsportsMatch?>(null) }
    var openedNews by remember { mutableStateOf<EsportsNews?>(null) }
    var followedMatches by remember { mutableStateOf<List<EsportsMatch>>(emptyList()) }
    var followedProfiles by remember { mutableStateOf<List<EsportsTeam>>(emptyList()) }
    var followedPlayerProfiles by remember { mutableStateOf<List<PlayerProfile>>(emptyList()) }
    var teamLinksRevision by remember { mutableIntStateOf(0) }
    var followsLoading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<EsportsDetail?>(null) }
    var detailError by remember { mutableStateOf(false) }
    var detailRevision by remember { mutableIntStateOf(0) }
    var cacheRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val source = context.getSharedPreferences("esports_public_cache", 0)
        val details = context.getSharedPreferences("esports_detail_cache", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cacheRevision++ }
        source.registerOnSharedPreferenceChangeListener(listener); details.registerOnSharedPreferenceChangeListener(listener)
        onDispose { source.unregisterOnSharedPreferenceChangeListener(listener); details.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val esportsPrefs = remember { context.getSharedPreferences("nightwatch", android.content.Context.MODE_PRIVATE) }
    var spoilerMode by remember { mutableStateOf(esportsPrefs.getBoolean("esports_spoiler_mode", false)) }
    var showEsportsGuide by remember { mutableStateOf(false) }
    LaunchedEffect(section, selected) {
        routePrefs.edit().putString("esports_last_section", section).putString("esports_last_filter", selected).apply()
    }
    LaunchedEffect(requestedMatch) {
        requestedMatch.second?.let { incoming ->
            watchOpenedId = null
            section = "matches"
            openedNews = null; openedTeam = null; openedPlayer = null; openedEvent = null
            val cached = repo.cachedDetail(incoming.id)
            opened = cached?.match ?: incoming
            detail = cached
            onRouteConsumed()
        }
    }
    SideEffect { onSubpageChanged(opened != null || openedEvent != null || openedTeam != null || openedPlayer != null || openedNews != null) }
    openedNews?.let { story -> NewsReader(story) { openedNews = null }; return }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val match = permissionMatch
        if(granted && match != null) runCatching { EsportsScoreScheduler.follow(context, match); onTracked(match.id) }
        if(granted && permissionFavorite) EsportsScoreScheduler.syncSaved(context, saved, saved)
        permissionMatch = null
        permissionFavorite = false
    }
    fun updateSaved(next: List<EsportsMatch>) {
        EsportsScoreScheduler.syncSaved(context, saved, next)
        saved = next
    }
    fun toggleSaved(match: EsportsMatch) {
        if(saved.any { it.id == match.id }) updateSaved(saved.filterNot { it.id == match.id })
        else {
            updateSaved(saved + match)
            if(matchAlerts && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionFavorite = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if(match.startAtMillis == null && match.status == "upcoming") scope.launch {
                runCatching { repo.details(match).match }.onSuccess { detailed ->
                    if(saved.any { it.id == match.id }) updateSaved(saved.map { if(it.id == match.id) detailed else it })
                }
            }
        }
    }
    fun refresh(force: Boolean) {
        if(refreshing) return
        refreshing = true
        val showLoading = force || feed == null
        if(showLoading) loading = true
        scope.launch {
            try { runCatching { repo.load(force) }.onSuccess { feed = it; error = false; RaundWidgetProvider.updateAll(context) }.onFailure { error = true } }
            finally { refreshing = false; if(showLoading) loading = false }
        }
    }
    LaunchedEffect(Unit) {
        refresh(false)
        while(true) {
            delay(if(feed?.live?.isNotEmpty() == true) 60_000L else 5 * 60_000L)
            refresh(false)
        }
    }
    LaunchedEffect(feed?.checkedAt, selected, saved, followedMatches, favorites, watchLater) {
        val visible = when(selected) {
            "results" -> feed?.results.orEmpty()
            "upcoming" -> feed?.upcoming.orEmpty()
            "saved" -> saved
            "watch_later" -> watchLater.map { it.match }
            "followed" -> (feed?.live.orEmpty() + feed?.upcoming.orEmpty() + feed?.results.orEmpty() + followedMatches)
                .filter { match -> favorites.any { it.equals(match.team1, true) || it.equals(match.team2, true) } }.distinctBy { it.id }
            else -> feed?.live.orEmpty()
        }
        visible.take(10).flatMap { listOfNotNull(it.team1Logo, it.team2Logo) }.distinct().forEach { url ->
            context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).size(200).allowHardware(false).crossfade(false).build())
        }
    }
    LaunchedEffect(opened?.id, detailRevision) {
        var match = opened ?: return@LaunchedEffect
        detail = repo.cachedDetail(match.id)
        detailError = false
        do {
        runCatching { repo.details(match, force = detailRevision > 0) }.onSuccess { loaded ->
            detail = loaded
            detailError = false
            opened = loaded.match
            match = loaded.match
            if(saved.any { it.id == match.id }) updateSaved(saved.map { if(it.id == match.id) loaded.match else it })
        }.onFailure { detailError = true }
        if(match.status != "live") break
        delay(61_000)
        } while(true)
    }
    LaunchedEffect(cacheRevision) {
        opened?.id?.let { id -> repo.cachedDetail(id)?.let { cached -> detail = cached; opened = cached.match } }
    }
    LaunchedEffect(section) {
        if(section == "matches" || section == "teams") return@LaunchedEffect
        exploreLoading = true
        exploreError = false
        runCatching {
            if(section == "events") events = explore.events() else news = explore.news()
        }.onFailure { exploreError = true }
        exploreLoading = false
    }
    LaunchedEffect(teamQuery, directoryKind) {
        teamSearchError = false
        if(teamQuery.trim().length < 2) { teamResults = emptyList(); playerResults = emptyList(); teamSearchLoading = false; return@LaunchedEffect }
        delay(450)
        teamSearchLoading = true
        runCatching {
            if(directoryKind == "teams") teamResults = explore.searchTeams(teamQuery)
            else playerResults = explore.searchPlayers(teamQuery)
        }.onFailure { teamResults = emptyList(); playerResults = emptyList(); teamSearchError = true }
        teamSearchLoading = false
    }
    LaunchedEffect(selected) {
        if(selected == "followed" && news.isEmpty()) runCatching { explore.news() }.onSuccess { news = it }
    }
    LaunchedEffect(favorites, teamLinksRevision) {
        if(favorites.isEmpty()) { followedMatches = emptyList(); followedProfiles = emptyList(); return@LaunchedEffect }
        followsLoading = true
        followedProfiles = favorites.mapNotNull { name ->
            val url = EsportsTeamLinks.get(context, name) ?: return@mapNotNull null
            runCatching { explore.team(url) }.getOrNull()
        }
        followedMatches = followedProfiles.flatMap { it.matches }.distinctBy { it.id }
        followsLoading = false
    }
    LaunchedEffect(favoritePlayers) {
        followedPlayerProfiles = favoritePlayers.mapNotNull { url -> runCatching { explore.player(url, includeMatches = false) }.getOrNull() }
    }
    openedPlayer?.let { url ->
        PlayerScreen(url, news, url in favoritePlayers, onFollow = { onPlayerFavorite(url) }, onBack = { openedPlayer = null },
            onMatch = { match -> returnPlayer = url; openedPlayer = null; opened = match })
        return
    }
    // The team page's own source is historical and can omit matches scheduled for the
    // next few days.  Merge it with the live schedule so a followed team always gets
    // a proper fixture, even for regional leagues.
    val teamSchedule = remember(feed, cacheRevision) {
        val cached = repo.cached() ?: feed
        (cached?.live.orEmpty() + cached?.upcoming.orEmpty() + cached?.results.orEmpty()).distinctBy { it.id }
    }
    openedTeam?.let { url ->
        TeamScreen(url, favorites, teamSchedule, onBack = { openedTeam = null }, onPlayer = { openedPlayer = it },
            onMatch = { returnTeam = url; openedTeam = null; opened = it }, onFollow = { name ->
                EsportsTeamLinks.put(context, name, url); onTeam(name)
            })
        return
    }
    opened?.let { match ->
        EsportsMatchDetail(detail?.match ?: match, detail, detailError, match.id in saved.map { it.id }, spoilerMode || watchOpenedId == match.id,
            onBack = { opened = null; watchOpenedId = null; openedPlayer = returnPlayer; returnPlayer = null; openedTeam = returnTeam; returnTeam = null }, onSave = { toggleSaved(detail?.match ?: match) },
            onFollow = {
                if(trackedId == match.id) { EsportsScoreScheduler.unfollow(context); onTracked(null) }
                else if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionMatch = match; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else runCatching { EsportsScoreScheduler.follow(context, match); onTracked(match.id) }
            }, tracked = trackedId == match.id, onRefresh = { detailRevision++ },
            onTeamOpen = { openedTeam = it }, onPlayerOpen = { openedPlayer = it }, onEventOpen = { url ->
                val id = Regex("/event/(\\d+)").find(url)?.groupValues?.get(1)
                if(id != null) {
                    openedEvent = events.firstOrNull { it.id == id } ?: EsportsEvent(id, match.event, url, match.eventIcon, "", "")
                    opened = null
                }
            }, queued = watchLater.any { it.match.id == match.id }, onQueue = {
                watchLater = WatchLater.put(context, if(watchLater.any { it.match.id == match.id }) watchLater.filterNot { it.match.id == match.id }
                    else listOf(WatchLaterEntry(detail?.match ?: match, System.currentTimeMillis())) + watchLater)
            })
        return
    }
    openedEvent?.let { event ->
        TournamentScreen(event, followed = followedEvents.any { it.id == event.id }, spoilerMode = spoilerMode, onFollow = {
            followedEvents = if(followedEvents.any { it.id == event.id }) followedEvents.filterNot { it.id == event.id } else followedEvents + event
            SavedEsportsEvents.put(context, followedEvents)
            EsportsReminders.refreshSoon(context)
            if(matchAlerts && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionFavorite = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }, onBack = { openedEvent = null }, onMatch = { opened = it }, onTeam = { openedTeam = it })
        return
    }
    val freshFeed = remember(feed, cacheRevision) { repo.cached() ?: feed }
    val freshMatches = remember(freshFeed, cacheRevision) {
        (freshFeed?.live.orEmpty() + freshFeed?.upcoming.orEmpty() + freshFeed?.results.orEmpty()).distinctBy { it.id }.map { match ->
            val detail = repo.cachedDetail(match.id)?.match
            if(detail != null && (detail.status == "result" && match.status != "result" || repo.cachedDetailAt(match.id) > (freshFeed?.checkedAt ?: 0))) detail else match
        }
    }
    val latest = freshMatches.associateBy { it.id }
    val currentSaved = saved.map { old ->
        val cache = repo.cachedDetail(old.id)?.match
        val source = latest[old.id] ?: old
        if(cache != null && repo.cachedDetailAt(old.id) > (freshFeed?.checkedAt ?: 0)) cache
        else EsportsCachePolicy.merge(old, source)
    }
    val followed = (freshMatches + followedMatches)
        .filter { match -> favorites.any { it.equals(match.team1, true) || it.equals(match.team2, true) } }
        .distinctBy { it.id }.map { match -> repo.cachedDetail(match.id)?.match?.takeIf {
            repo.cachedDetailAt(match.id) > (freshFeed?.checkedAt ?: 0) || it.status == "result" && match.status != "result"
        } ?: match }
        .sortedWith(compareBy<EsportsMatch> { when(it.status) { "live" -> 0; "upcoming" -> 1; else -> 2 } }
            .thenBy { it.startAtMillis ?: Long.MAX_VALUE })
    val list = when(selected) { "watch_later" -> emptyList(); "upcoming" -> freshMatches.filter { it.status == "upcoming" }; "results" -> freshMatches.filter { it.status == "result" }; "saved" -> currentSaved; "followed" -> followed;
        else -> freshMatches.filter { it.status == "live" } }
    val nextSpotlight = if(selected == "live" && list.isEmpty())
        (followed.filter { it.status == "upcoming" } + freshMatches.filter { it.status == "upcoming" }).distinctBy { it.id }
            .minByOrNull { it.startAtMillis ?: Long.MAX_VALUE } else null
    val displayList = if(nextSpotlight != null) freshMatches.filter { it.status == "upcoming" && it.id != nextSpotlight.id }.take(4) else list
    val followedNews = remember(news, favorites, followedPlayerProfiles) {
        val terms = (favorites + followedPlayerProfiles.map { it.player.name }).filter { it.length >= 3 }
        news.filter { story -> terms.any { term -> story.title.contains(term, true) || story.summary.contains(term, true) } }.take(6)
    }
    if(showEsportsGuide) AlertDialog(onDismissRequest = { showEsportsGuide = false },
        title = { Text(txt("Your esports hub", "Espor merkezini hazırla")) },
        text = { Text(txt("Search and follow teams, save matches, then get one useful reminder before they begin.",
            "Takım ara ve takip et, maçları kaydet; başlamadan önce tek ve faydalı hatırlatma al.")) },
        confirmButton = { TextButton(onClick = {
            showEsportsGuide = false
            esportsPrefs.edit().putBoolean("esports_guide_seen_v2", true).apply()
        }) { Text(txt("Got it", "Anladım")) } })
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            EsportsTopHeader(spoilerMode, onGuide = { showEsportsGuide = !showEsportsGuide }, onSpoiler = {
                spoilerMode = !spoilerMode
                SpoilerPolicy.change(context, spoilerMode)
            }, onRefresh = {
                if(section == "matches") refresh(true) else scope.launch {
                    exploreLoading = true
                    runCatching {
                        when(section) {
                            "events" -> events = explore.events(true)
                            "news" -> news = explore.news(true)
                            "teams" -> if(teamQuery.trim().length >= 2) {
                                if(directoryKind == "teams") teamResults = explore.searchTeams(teamQuery)
                                else playerResults = explore.searchPlayers(teamQuery)
                            }
                            else -> Unit
                        }
                    }
                        .onSuccess { exploreError = false }.onFailure { exploreError = true }
                    exploreLoading = false
                }
            })
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            if(exploreLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            if(error && section == "matches" || exploreError && section != "matches") Note(txt("Could not update VLR.gg. Showing saved data if available.","VLR.gg güncellenemedi. Varsa kaydedilmiş veriler gösteriliyor."))
            Row(Modifier.fillMaxWidth()) {
                EsportsTab(txt("Matches","Maçlar"), section == "matches", Modifier.weight(1f)) { section = "matches" }
                EsportsTab(txt("Tournaments","Turnuvalar"), section == "events", Modifier.weight(1f)) { section = "events" }
                EsportsTab(txt("Teams","Takımlar"), section == "teams", Modifier.weight(1f)) { section = "teams" }
                EsportsTab(txt("News","Haberler"), section == "news", Modifier.weight(1f)) { section = "news" }
            }
            if(section == "matches") {
                val filters = listOf("live" to txt("Live", "Canlı"), "upcoming" to txt("Upcoming", "Yaklaşan"),
                    "results" to txt("Results", "Sonuçlar"), "followed" to txt("For you", "Sana özel"), "saved" to txt("Saved", "Kaydedilen"),
                    "watch_later" to txt("Watch later", "Sonra izle"))
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { matchFilterOpen = true }) {
                            Text((filters.firstOrNull { it.first == selected }?.second ?: txt("Matches", "Maçlar")) + "  ▾", color = Accent, fontSize = 15.sp)
                        }
                        DropdownMenu(matchFilterOpen, onDismissRequest = { matchFilterOpen = false }) {
                            filters.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) },
                                onClick = { selected = value; matchFilterOpen = false }) }
                        }
                    }
                    if(selected == "watch_later") TextButton(onClick = { showWatched = !showWatched }) {
                        Text(if(showWatched) txt("Watched", "İzlenenler") else txt("To watch", "İzlenecekler"))
                    } else Text("${list.size} " + txt(if(list.size == 1) "match" else "matches", "maç"), color = Muted, fontSize = 12.sp)
                }
                Text(if(selected == "watch_later") txt("Private replay list · No notifications", "Kişisel izleme listesi · Bildirim göndermez") else txt("Last checked ", "Son kontrol ") +
                    ((if(selected == "results") freshFeed?.resultsCheckedAt else freshFeed?.checkedAt)?.takeIf { it > 0 }?.let(::timeLabel) ?: "—"), color = Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
                if(freshFeed?.partialResults == true && selected == "results") Note(txt("Results could not be refreshed; showing the last saved results.",
                    "Sonuçlar güncellenemedi; son kaydedilen sonuçlar gösteriliyor."))
                if(nextSpotlight != null) EsportsSpotlight(nextSpotlight, favorites, spoilerMode,
                    saved = nextSpotlight.id in saved.map { it.id }, onSave = { toggleSaved(nextSpotlight) }, onOpen = { opened = nextSpotlight })
                if(nextSpotlight != null && displayList.isNotEmpty()) Text(txt("NEXT ON THE SCHEDULE", "PROGRAMDAKİ DİĞER MAÇLAR"),
                    color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
            }
            if(section == "matches" && selected == "followed" && favorites.isNotEmpty()) {
                Text(txt("YOUR TEAMS", "TAKIMLARIN"), color = Muted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                    favorites.sorted().forEach { name ->
                        val profile = followedProfiles.firstOrNull { it.name.equals(name, true) }
                        val url = profile?.url ?: EsportsTeamLinks.get(context, name)
                        Column(Modifier.width(88.dp).clickable(enabled = url != null) { if(url != null) openedTeam = url },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            TeamLogo(name, profile?.image, 44.dp)
                            Text(name, color = White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            if(section == "matches" && selected == "followed" && favoritePlayers.isNotEmpty()) {
                Text(txt("YOUR PLAYERS", "OYUNCULARIN"), color = Muted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                    followedPlayerProfiles.forEach { profile ->
                        Column(Modifier.width(88.dp).clickable { openedPlayer = profile.player.url }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Raised), contentAlignment = Alignment.Center) {
                                Text(profile.player.name.take(1), color = White)
                                profile.player.image?.let { coil.compose.AsyncImage(it, profile.player.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                            }
                            Text(profile.player.name, color = White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        if(section == "events") {
            item {
                EsportsTournamentSearch(eventQuery, eventFilter,
                    onQuery = { eventQuery = it }, onFilter = { eventFilter = it })
            }
            fun isActive(event: EsportsEvent): Boolean {
                val status = event.status.lowercase(Locale.ROOT)
                return status.contains("ongoing") || status.contains("live") || status.contains("current") ||
                    status.contains("in progress") || status.contains("active")
            }
            val visible = events.filter { event ->
                val status = event.status.lowercase(Locale.ROOT)
                val matchesQuery = eventQuery.isBlank() || event.title.contains(eventQuery.trim(), true)
                val matchesFilter = when(eventFilter) {
                    "live" -> isActive(event)
                    "upcoming" -> status.contains("upcoming")
                    "completed" -> status.contains("completed") || status.contains("finished")
                    else -> true
                }
                matchesQuery && matchesFilter
            }
            val activeFirst = visible.sortedWith(compareByDescending<EsportsEvent> { isActive(it) }.thenBy { it.title })
            if(visible.isEmpty() && !exploreLoading) item { Empty(
                if(eventFilter == "live") txt("No active tournaments found", "Aktif turnuva bulunamadı") else txt("No tournaments","Turnuva bulunamadı"),
                txt("Refresh to check the latest events.","Güncel turnuvalar için yenile.")) }
            activeFirst.firstOrNull()?.let { featured ->
                item { Text(if(isActive(featured)) txt("ACTIVE TOURNAMENT", "AKTİF TURNUVA") else txt("TOURNAMENT", "TURNUVA"), color = Accent, fontSize = 9.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
                item { EsportsFeaturedEvent(featured) { openedEvent = featured } }
            }
            if(activeFirst.size > 1) item { Text(txt("MORE TOURNAMENTS", "DİĞER TURNUVALAR"), color = Muted, fontSize = 9.sp,
                letterSpacing = 1.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)) }
            items(activeFirst.drop(1), key = { it.id }) { event -> EsportsEventRow(event) { openedEvent = event } }
        } else if(section == "teams") {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(txt("Teams", "Takımlar"), directoryKind == "teams") { directoryKind = "teams" }
                    Chip(txt("Players", "Oyuncular"), directoryKind == "players") { directoryKind = "players" }
                }
                OutlinedTextField(value = teamQuery, onValueChange = { teamQuery = it }, singleLine = true,
                    label = { Text(if(directoryKind == "teams") txt("Search all teams", "Tüm takımlarda ara") else txt("Search pro players", "Profesyonel oyuncu ara")) },
                    placeholder = { Text(if(directoryKind == "teams") txt("Try FNATIC, BBL, Sentinels…", "FNATIC, BBL, Sentinels…") else txt("Try alfajer, TenZ…", "alfajer, TenZ…")) },
                    leadingIcon = { Text("⌕", color = Muted, fontSize = 22.sp) },
                    trailingIcon = { if(teamQuery.isNotBlank()) TextButton(onClick = { teamQuery = "" }) { Text("×") } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(18.dp))
                if(teamSearchLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent)
                if(teamSearchError) Note(txt("Search could not be updated.", "Arama güncellenemedi."))
                if(teamQuery.length < 2) {
                    SectionTitle(if(directoryKind == "teams") txt("Your teams", "Takımların") else txt("Your players", "Oyuncuların"),
                        if(directoryKind == "teams") favorites.size.toString() else favoritePlayers.size.toString())
                    if(directoryKind == "teams" && favorites.isEmpty()) Note(txt("Search for any team and tap the heart to follow it.",
                        "Herhangi bir takımı ara ve takip etmek için kalbe dokun."))
                    if(directoryKind == "players" && favoritePlayers.isEmpty()) Note(txt("Search for a pro player and add them to your follows.",
                        "Profesyonel oyuncu ara ve takip listene ekle."))
                } else if((directoryKind == "teams" && teamResults.isEmpty() || directoryKind == "players" && playerResults.isEmpty()) && !teamSearchLoading && !teamSearchError) {
                    Note(txt("No result found for this search.", "Bu aramada sonuç bulunamadı."))
                }
            }
            if(directoryKind == "teams" && teamQuery.length < 2) items(favorites.sorted(), key = { "favorite-team-$it" }) { name ->
                val profile = followedProfiles.firstOrNull { it.name.equals(name, true) }
                val url = profile?.url ?: EsportsTeamLinks.get(context, name)
                EsportsTeamSearchRow(name, profile?.image, true, enabled = url != null,
                    onOpen = { if(url != null) openedTeam = url }, onFavorite = { onTeam(name) })
            } else if(directoryKind == "teams") items(teamResults, key = { it.url }) { team ->
                EsportsTeamSearchRow(team.name, team.image, favorites.any { it.equals(team.name, true) }, true,
                    onOpen = { openedTeam = team.url }, onFavorite = {
                        EsportsTeamLinks.put(context, team.name, team.url)
                        onTeam(team.name)
                    })
            } else if(teamQuery.length < 2) items(followedPlayerProfiles, key = { "favorite-player-${it.player.url}" }) { profile ->
                EsportsPlayerSearchRow(profile.player.name, profile.player.image, profile.player.realName,
                    true, { openedPlayer = profile.player.url }, { onPlayerFavorite(profile.player.url) })
            } else items(playerResults, key = { it.url }) { player ->
                EsportsPlayerSearchRow(player.name, player.image, player.description, player.url in favoritePlayers,
                    { openedPlayer = player.url }, { onPlayerFavorite(player.url) })
            }
        } else if(section == "news") {
            val visible = news
            if(visible.isEmpty() && !exploreLoading) item { Empty(txt("No news","Haber bulunamadı"), txt("Refresh to check new stories.","Yeni haberler için yenile.")) }
            visible.firstOrNull()?.let { lead -> item { EsportsFeaturedNews(lead) { openedNews = lead } } }
            if(visible.size > 1) item { Text(txt("MORE STORIES", "DİĞER HABERLER"), color = Muted, fontSize = 9.sp,
                letterSpacing = 1.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)) }
            items(visible.drop(1), key = { it.url }) { story -> EsportsNewsRow(story) {
                openedNews = story
            } }
        } else if(selected == "watch_later") {
            val queue = watchLater.filter { it.watched == showWatched }.sortedByDescending { it.savedAt }
            if(queue.isEmpty()) item { Note(if(showWatched) txt("No watched matches yet.", "Henüz izlenen maç yok.") else
                txt("Open a match → Streams & replays → Watch later. Scores stay hidden when you open it from here.",
                    "Maçı aç → Yayınlar ve tekrarlar → Sonra izle. Buradan açtığın maçta sonuçlar gizli kalır.")) }
            items(queue, key = { "watch-${it.match.id}" }) { entry ->
                WatchLaterRow(entry, onOpen = { watchOpenedId = entry.match.id; opened = entry.match }, onWatched = {
                    watchLater = WatchLater.put(context, watchLater.map { if(it.match.id == entry.match.id) it.copy(watched = !it.watched) else it })
                }, onRemove = { watchLater = WatchLater.put(context, watchLater.filterNot { it.match.id == entry.match.id }) })
            }
        } else {
        if(selected == "followed" && followsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent) }
        if(displayList.isEmpty() && nextSpotlight == null && followedNews.isEmpty() && !loading) item {
            EsportsEmptyState(
                if(error) txt("Scores are temporarily unavailable", "Skorlar geçici olarak alınamadı")
                else if(selected == "live") txt("The arena is quiet right now", "Şu an arena sessiz")
                else if(selected == "followed") txt("Build your esports feed", "Espor akışını oluştur")
                else txt("Nothing in this view yet", "Bu görünümde henüz maç yok"),
                onUpcoming = { selected = "upcoming" }, onTeams = { section = "teams" }, onEvents = { section = "events" })
        }
        displayList.take(visibleMatchCount).forEachIndexed { index, match ->
            val day = esportsDaySection(match.startAtMillis, match.whenText, tr)
            val previousDay = displayList.getOrNull(index - 1)?.let { esportsDaySection(it.startAtMillis, it.whenText, tr) }
            if(day.isNotBlank() && day != previousDay) item(key = "day-$index-$day") {
                Text(day.uppercase(Locale.getDefault()), color = Muted, fontSize = 9.sp, letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if(index == 0) 10.dp else 18.dp, bottom = 2.dp))
            }
            item(key = match.id) {
                EsportsMatchCard(match, favorites, { teamLinksRevision++ }, { openedTeam = it }, spoilerMode,
                    match.id in saved.map { it.id }, { toggleSaved(match) }, trackedId == match.id,
                    onFollow = {
                        if(trackedId == match.id) { EsportsScoreScheduler.unfollow(context); onTracked(null) }
                        else if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permissionMatch = match; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else runCatching { EsportsScoreScheduler.follow(context, match); onTracked(match.id) }
                    }, onOpen = { opened = match })
            }
        }
        if(displayList.size > visibleMatchCount) item {
            TextButton(onClick = { visibleMatchCount += 6 }, modifier = Modifier.fillMaxWidth()) {
                Text(txt("Show more matches", "Daha fazla maç göster"))
            }
        }
        if(selected == "followed" && followedNews.isNotEmpty()) {
            item { SectionTitle(txt("News from your follows", "Takip ettiklerinden haberler"), followedNews.size.toString()) }
            items(followedNews, key = { "follow-${it.url}" }) { story -> EsportsNewsRow(story) { openedNews = story } }
        }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable private fun EsportsEmptyState(title: String, onUpcoming: () -> Unit, onTeams: () -> Unit, onEvents: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 18.dp)) {
        Text(title, color = White, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        Text(txt("Choose where to continue.", "Devam etmek istediğin alanı seç."), color = Muted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 7.dp, bottom = 18.dp))
        listOf(
            Triple(txt("Upcoming matches", "Yaklaşan maçlar"), txt("See the next scheduled games", "Sıradaki programı gör"), onUpcoming),
            Triple(txt("Find teams & players", "Takım ve oyuncu bul"), txt("Create a personal follow feed", "Kişisel takip akışını oluştur"), onTeams),
            Triple(txt("Browse tournaments", "Turnuvalara göz at"), txt("Brackets, results and standings", "Eşleşmeler, sonuçlar ve puanlar"), onEvents)
        ).forEach { (label, body, action) ->
            Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(body, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Text("›", color = Muted, fontSize = 22.sp)
            }
            HorizontalDivider(color = Raised.copy(alpha = .5f))
        }
    }
}

@Composable private fun EsportsTopHeader(spoilerMode: Boolean, onGuide: () -> Unit, onSpoiler: () -> Unit, onRefresh: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(txt("Esports", "eSpor"), color = White, fontSize = 30.sp, lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                Text(txt("Matches, teams and tournaments", "Maçlar, takımlar ve turnuvalar"), color = Muted,
                    fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Box {
                TextButton(onClick = { menuOpen = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("•••", color = White, fontSize = 17.sp, letterSpacing = 2.sp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if(spoilerMode) txt("Show scores", "Skorları göster")
                        else txt("Hide scores", "Skorları gizle")) }, onClick = { menuOpen = false; onSpoiler() })
                    DropdownMenuItem(text = { Text(txt("Refresh data", "Verileri yenile")) },
                        onClick = { menuOpen = false; onRefresh() })
                    DropdownMenuItem(text = { Text(txt("Quick guide", "Kısa rehber")) },
                        onClick = { menuOpen = false; onGuide() })
                }
            }
        }
        Text(txt("Source: VLR.gg · unofficial", "Kaynak: VLR.gg · resmî değil"), color = Muted,
            fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable private fun EsportsTeamSearchRow(name: String, image: String?, followed: Boolean, enabled: Boolean,
                                              onOpen: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(name, image, 46.dp)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(name, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if(enabled) txt("Open team profile", "Takım profilini aç") else txt("Profile link unavailable", "Profil bağlantısı bulunamadı"),
                color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
        TextButton(onClick = onFavorite) { Text(if(followed) "♥" else "♡", color = if(followed) White else Muted, fontSize = 24.sp) }
    }
    HorizontalDivider(color = Raised.copy(alpha = .55f))
}

@Composable private fun EsportsPlayerSearchRow(name: String, image: String?, description: String, followed: Boolean,
                                                onOpen: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Raised), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Muted, fontWeight = FontWeight.Bold)
            if(image != null) coil.compose.AsyncImage(image, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(name, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(description.ifBlank { txt("Open player profile", "Oyuncu profilini aç") }, color = Muted, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        TextButton(onClick = onFavorite) { Text(if(followed) "♥" else "♡", color = if(followed) White else Muted, fontSize = 24.sp) }
    }
    HorizontalDivider(color = Raised.copy(alpha = .55f))
}

@Composable private fun EsportsTab(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if(active) White else Muted, fontSize = 13.sp, fontWeight = if(active) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.height(7.dp))
        Box(Modifier.width(if(active) 22.dp else 1.dp).height(2.dp).background(if(active) Accent else androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable private fun EsportsPulseRow(live: Int, upcoming: Int, followed: Int, selected: String,
                                        onLive: () -> Unit, onUpcoming: () -> Unit, onFollowed: () -> Unit,
                                        onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            listOf(if(live > 0) "● $live" else "—", txt("Live", "Canlı"), "live"),
            listOf(upcoming.toString(), txt("Upcoming", "Yaklaşan"), "upcoming"),
            listOf(followed.toString(), txt("For you", "Sana özel"), "followed")
        ).forEach { (value, label, target) ->
            val action = when(target) { "live" -> onLive; "upcoming" -> onUpcoming; else -> onFollowed }
            val active = selected == target
            Column(Modifier.weight(1f).clickable(onClick = action).padding(vertical = 7.dp)) {
                Text(value, color = if(active) Color(0xFFFF6B73) else White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(label, color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                Box(Modifier.padding(top = 5.dp).width(if(active) 18.dp else 1.dp).height(2.dp)
                    .background(if(active) Accent else Color.Transparent))
            }
        }
        TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 7.dp)) {
            Text(txt("Alerts", "Bildirimler"), fontSize = 10.sp)
        }
    }
}

@Composable private fun EsportsTournamentSearch(query: String, filter: String, onQuery: (String) -> Unit,
                                                 onFilter: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp)) {
        OutlinedTextField(query, onQuery, singleLine = true, leadingIcon = { Text("⌕", color = Muted, fontSize = 21.sp) },
            trailingIcon = { if(query.isNotBlank()) TextButton(onClick = { onQuery("") }) { Text("×") } },
            placeholder = { Text(txt("Search tournaments", "Turnuva ara")) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("all" to txt("All", "Tümü"), "live" to txt("Ongoing", "Devam eden"),
                "upcoming" to txt("Upcoming", "Yaklaşan"), "completed" to txt("Completed", "Tamamlanan"))
                .forEach { (value, label) -> Chip(label, filter == value) { onFilter(value) } }
        }
    }
}

@Composable private fun EsportsSpotlight(match: EsportsMatch, favorites: Set<String>, spoilerMode: Boolean,
                                         saved: Boolean, onSave: () -> Unit, onOpen: () -> Unit) {
    val followed = favorites.any { it.equals(match.team1, true) || it.equals(match.team2, true) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(top = 14.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if(followed) txt("FOR YOU · UP NEXT", "SANA ÖZEL · SIRADAKİ") else txt("UP NEXT", "SIRADAKİ"),
                color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(if(saved) "★" else "☆", color = if(saved) Accent else Muted, fontSize = 22.sp,
                modifier = Modifier.clickable(onClick = onSave).padding(start = 12.dp))
        }
        Text(match.event.ifBlank { "VALORANT" }, color = Muted, fontSize = 10.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(match.team1, match.team1Logo, 54.dp)
            Text(match.team1, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 11.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if(spoilerMode && match.status == "result") "•••" else "VS", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                match.startAtMillis?.let { Text(esportsCountdown(it, turkish = LocalTurkish.current), color = Accent, fontSize = 10.sp) }
            }
            Text(match.team2, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 11.dp))
            TeamLogo(match.team2, match.team2Logo, 54.dp)
        }
        Text(listOf(match.series, match.startAtMillis?.let { localMatchTime(it, LocalTurkish.current) }
            ?: esportsDisplayWhen(match.whenText, LocalTurkish.current)).filter(String::isNotBlank).joinToString(" · "),
            color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp))
        HorizontalDivider(color = Raised.copy(alpha = .7f), modifier = Modifier.padding(top = 14.dp))
    }
}

internal fun esportsCountdown(startAtMillis: Long?, now: Long = System.currentTimeMillis(), turkish: Boolean = true): String {
    if(startAtMillis == null) return "—"
    val remaining = ((startAtMillis - now).coerceAtLeast(0L) + 59_999) / 60_000
    if(remaining == 0L) return "00:00"
    val days = remaining / 1440
    val hours = (remaining % 1440) / 60
    val minutes = remaining % 60
    return if(days > 0) if(turkish) "${days}g ${hours}s ${minutes}dk" else "${days}d ${hours}h ${minutes}m"
        else "%02d:%02d".format(Locale.ROOT, hours, minutes)
}

internal fun esportsDaySection(startAtMillis: Long?, fallback: String = "", turkish: Boolean = true,
                               now: Long = System.currentTimeMillis()): String {
    if(startAtMillis == null) return fallback.substringBefore(" · ").trim()
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(startAtMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when(date) {
        today.minusDays(1) -> if(turkish) "Dün" else "Yesterday"
        today -> if(turkish) "Bugün" else "Today"
        today.plusDays(1) -> if(turkish) "Yarın" else "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("d MMMM EEEE",
            if(turkish) Locale.forLanguageTag("tr") else Locale.ENGLISH))
    }
}

private fun localMatchTime(startAtMillis: Long, turkish: Boolean): String = DateTimeFormatter
    .ofPattern("dd MMM yyyy · HH:mm", if(turkish) Locale.forLanguageTag("tr") else Locale.ENGLISH)
    .format(Instant.ofEpochMilli(startAtMillis).atZone(ZoneId.systemDefault()))

internal fun esportsDisplayWhen(source: String, turkish: Boolean): String {
    if(!turkish) return source
    val parsed = runCatching { LocalDateTime.parse(source,
        DateTimeFormatter.ofPattern("EEE, MMMM d, uuuu '·' h:mm a", Locale.ENGLISH)) }.getOrNull() ?: return source
    return parsed.format(DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.forLanguageTag("tr")))
}

@Composable private fun EsportsMatchDetail(match: EsportsMatch, detail: EsportsDetail?, detailError: Boolean, saved: Boolean,
                                          spoilerMode: Boolean,
                                          onBack: () -> Unit, onSave: () -> Unit, onFollow: () -> Unit, tracked: Boolean,
                                          onRefresh: () -> Unit,
                                          onTeamOpen: (String) -> Unit, onPlayerOpen: (String) -> Unit,
                                          onEventOpen: (String) -> Unit, queued: Boolean, onQueue: () -> Unit) {
    val context = LocalContext.current
    val tr = LocalTurkish.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tab by remember(match.id) { mutableStateOf("overview") }
    var spoilersRevealed by remember(match.id) { mutableStateOf(false) }
    var statsMap by remember(match.id) { mutableStateOf("all") }
    var openedMap by remember(match.id) { mutableStateOf<String?>(null) }
    var headToHead by remember(match.id) { mutableStateOf<List<EsportsMatch>>(emptyList()) }
    var mapArt by remember(match.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pickem by remember(match.id) { mutableStateOf(PickemStore.get(context, match)) }
    LaunchedEffect(match.id) { while(true) { delay(30_000); now = System.currentTimeMillis() } }
    LaunchedEffect(detail?.mapResults, detail?.veto) {
        mapArt = withContext(Dispatchers.IO) { runCatching { ContentCatalog(context).data("maps").objects().mapNotNull { row ->
            val name = row.textOrNull("displayName")?.lowercase(Locale.ROOT) ?: return@mapNotNull null
            val art = row.textOrNull("splash") ?: row.textOrNull("listViewIcon") ?: return@mapNotNull null
            name to art
        }.toMap() }.getOrDefault(emptyMap()) }
    }
    LaunchedEffect(detail?.team1Url, detail?.team2Url) {
        val url = detail?.team1Url ?: return@LaunchedEffect
        headToHead = runCatching { EsportsExploreRepository(context).team(url).matches.filter {
            setOf(it.team1.lowercase(), it.team2.lowercase()) == setOf(match.team1.lowercase(), match.team2.lowercase())
        }.distinctBy { it.id }.filterNot { it.id == match.id }.take(5) }.getOrDefault(emptyList())
    }
    BackHandler { if(openedMap != null) openedMap = null else onBack() }
    val hideScores = spoilerMode && !spoilersRevealed
    val selectedMap = detail?.mapResults.orEmpty().firstOrNull { it.name == openedMap }
    if(selectedMap != null) {
        EsportsMapDetail(selectedMap, mapArt[selectedMap.name.lowercase(Locale.ROOT)], match, detail?.veto,
            detail?.mapPlayers?.get(selectedMap.name).orEmpty(), hideScores,
            onBack = { openedMap = null }, onPlayerOpen = onPlayerOpen)
        return
    }
    val date = match.startAtMillis?.let { localMatchTime(it, LocalTurkish.current) }
        ?: esportsDisplayWhen(match.whenText, LocalTurkish.current)
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(txt("← Matches","← Maçlar")) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 9.dp)) {
                    Text(if(saved) "★" else "☆", color = if(saved) Accent else White, fontSize = 23.sp)
                }
                TextButton(onClick = onRefresh) { Text(txt("Refresh","Yenile")) }
            }
        }
        item {
            Text(match.event.ifBlank { "VALORANT" }, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = detail?.eventUrl != null) { detail?.eventUrl?.let(onEventOpen) })
            if(match.series.isNotBlank()) Text(match.series, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(when(match.status) { "live" -> txt("● LIVE NOW","● ŞİMDİ CANLI"); "result" -> txt("FINAL","BİTTİ");
                        "cancelled" -> txt("CANCELLED", "İPTAL EDİLDİ"); "postponed" -> txt("POSTPONED", "ERTELENDİ"); else -> txt("UPCOMING MATCH","YAKLAŞAN MAÇ") },
                        color = if(match.status == "live") Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    if(match.status == "live") TextButton(onClick = onFollow, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                        Text(if(tracked) txt("● Following score", "● Skor takipte") else txt("Follow score", "Skoru takip et"),
                            color = if(tracked) Accent else White, fontSize = 11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(enabled = detail?.team1Url != null) { detail?.team1Url?.let(onTeamOpen) },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        TeamLogo(match.team1, match.team1Logo, 48.dp)
                        Text(match.team1, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, modifier = Modifier.padding(top = 10.dp))
                    }
                    Text(if(match.status == "upcoming") "VS" else if(hideScores) "•••" else match.score, color = White,
                        fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
                    Column(Modifier.weight(1f).clickable(enabled = detail?.team2Url != null) { detail?.team2Url?.let(onTeamOpen) },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        TeamLogo(match.team2, match.team2Logo, 48.dp)
                        Text(match.team2, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                Text(date, color = Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                if(match.status == "upcoming" && match.startAtMillis != null)
                    Text(txt("Starts in ","Başlamasına ") + esportsCountdown(match.startAtMillis, now, LocalTurkish.current), color = Accent,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                Text(listOfNotNull(detail?.format?.takeIf { it.isNotBlank() }, detail?.patch?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
                if(hideScores) TextButton(onClick = { spoilersRevealed = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(txt("Reveal result", "Sonucu göster"))
                }
            }
        }
        if(detailError && detail != null) item { Note(txt("Update failed · showing saved match details.", "Yenilenemedi · kaydedilen maç bilgileri gösteriliyor.")) }
        item { val checked = EsportsRepository(context).cachedDetailAt(match.id)
            Text(txt("Source: VLR.gg · Checked ", "Kaynak: VLR.gg · Kontrol ") + if(checked > 0) timeLabel(checked) else "—", color = Muted, fontSize = 12.sp) }
        if(match.status == "upcoming" || pickem != null) item {
            SectionTitle(txt("Private Pick'em", "Kişisel Pick'em"))
            Text(txt("Your local prediction · no money, odds or public leaderboard", "Cihazındaki tahminin · para, oran veya genel sıralama yok"),
                color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf(match.team1, match.team2).forEach { team ->
                    FilterChip(selected = pickem == team, onClick = { pickem = PickemStore.choose(context, match, team) },
                        enabled = match.status == "upcoming", label = { Text(team, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f))
                }
            }
            PickemStore.correct(match, pickem)?.let { correct ->
                Text(if(correct) txt("✓ Prediction correct", "✓ Tahmin doğru") else txt("Prediction missed", "Tahmin yanlış"),
                    color = if(correct) Accent else Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if(detail == null) item { Note(if(detailError) txt("Match details could not be updated.","Maç detayları güncellenemedi.")
            else txt("Loading match details…","Maç detayları yükleniyor…")) }
        item {
            HorizontalDivider(color = Raised.copy(alpha = .7f))
            Row(Modifier.fillMaxWidth()) {
                EsportsTab(txt("Overview","Özet"), tab == "overview", Modifier.weight(1f)) { tab = "overview" }
                EsportsTab(txt("Players","Oyuncular"), tab == "players", Modifier.weight(1f)) { tab = "players" }
                EsportsTab(txt("Watch","İzle"), tab == "watch", Modifier.weight(1f)) { tab = "watch" }
            }
        }
        when(tab) {
            "overview" -> {
                item {
                    val mapsPlayed = detail?.mapResults.orEmpty().filter { it.score1 != null && it.score2 != null }
                    val winner = when {
                        hideScores || match.status != "result" -> null
                        match.score1 != null && match.score2 != null && match.score1 > match.score2 -> match.team1
                        match.score1 != null && match.score2 != null && match.score2 > match.score1 -> match.team2
                        else -> null
                    }
                    SectionTitle(if(winner != null) txt("Series complete","Seri tamamlandı") else txt("Series at a glance","Seriye bakış"))
                    val finalScore = if(match.score1 != null && match.score2 != null)
                        "${maxOf(match.score1, match.score2)}-${minOf(match.score1, match.score2)}" else match.score
                    if(winner != null) Text(txt("$winner won the series $finalScore.", "$winner seriyi $finalScore kazandı."),
                        color = White, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 15.dp))
                    Row(Modifier.fillMaxWidth()) {
                        val total1 = mapsPlayed.sumOf { it.score1 ?: 0 }
                        val total2 = mapsPlayed.sumOf { it.score2 ?: 0 }
                        Stat(if(hideScores) "•••" else mapsPlayed.size.toString(),
                            txt("Maps played","Oynanan harita"), Modifier.weight(1f))
                        Stat(if(hideScores) "•••" else if(mapsPlayed.isEmpty()) "—" else "$total1 : $total2",
                            txt("Total rounds","Toplam raunt"), Modifier.weight(1f))
                        Stat(detail?.format?.ifBlank { "—" } ?: "—", txt("Format","Format"), Modifier.weight(.7f))
                    }
                    HorizontalDivider(color = Raised, modifier = Modifier.padding(top = 17.dp))
                }
                val comparisonMaps = detail?.mapResults.orEmpty().filter { it.score1 != null && it.score2 != null }
                if(comparisonMaps.isNotEmpty() && !hideScores) item {
                    SeriesComparison(match, comparisonMaps)
                }
                if(hideScores) item { Note(txt("Map outcomes and match length stay hidden until you reveal the result.",
                    "Sonucu açana kadar harita sonuçları ve maç uzunluğu gizli kalır.")) }
                if(!hideScores && detail?.mapResults.orEmpty().isNotEmpty()) {
                    item { SectionTitle(txt("Map results","Harita sonuçları")) }
                    items(detail?.mapResults.orEmpty()) { map ->
                        EsportsMapCard(map, mapArt[map.name.lowercase(Locale.ROOT)], match, detail?.veto,
                            detail?.mapPlayers?.get(map.name).orEmpty(), hideScores) { openedMap = map.name }
                    }
                }
                detail?.veto?.takeIf { !hideScores }?.let { veto ->
                    item {
                        SectionTitle(txt("Map selection","Harita seçimleri"))
                        veto.split(';').map { it.trim() }.filter { it.isNotBlank() }.forEachIndexed { index, action ->
                            EsportsVetoCard(index + 1, action, mapArt, match)
                        }
                    }
                }
                if(!hideScores && detail != null && detail.mapResults.orEmpty().isEmpty() && detail.veto.isNullOrBlank())
                    item { Note(txt("The source has not published map details for this match yet.",
                        "Kaynak bu maç için harita ayrıntılarını henüz yayımlamadı.")) }
                if(headToHead.isNotEmpty()) {
                    item { SectionTitle(txt("Previous meetings","Önceki karşılaşmalar"), headToHead.size.toString()) }
                    items(headToHead, key = { "h2h-${it.id}" }) { previous ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(previous.whenText, color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                            Text(if(hideScores) "•••" else "${previous.team1}  ${previous.score}  ${previous.team2}", color = White, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Raised.copy(alpha = .55f))
                    }
                }
            }
            "players" -> {
                item { SectionTitle(txt("Player scoreboard","Oyuncu skor tablosu")) }
                if(hideScores) item { Note(txt("Player metrics are hidden until you reveal this match.", "Bu maçın sonucunu açana kadar oyuncu verileri gizli.")) }
                val availableMaps = if(hideScores) emptyList() else detail?.mapPlayers.orEmpty().keys.toList()
                if(availableMaps.isNotEmpty()) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf("all") + availableMaps).forEach { name -> FilterChip(selected = statsMap == name,
                            onClick = { statsMap = name }, label = { Text(if(name == "all") txt("All maps","Tüm haritalar") else name) }) }
                    }
                }
                if(detail?.players.orEmpty().isEmpty()) item { Note(txt("Player stats are not available yet.",
                    "Oyuncu istatistikleri henüz yayımlanmadı.")) }
                listOf(match.team1, match.team2).forEach { team ->
                    val sourcePlayers = if(hideScores) emptyList() else if(statsMap == "all") detail?.players.orEmpty() else detail?.mapPlayers?.get(statsMap).orEmpty()
                    val players = sourcePlayers.filter { it.team == team }
                    if(players.isNotEmpty()) {
                        item { Text(team, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 13.dp, bottom = 4.dp)) }
                        items(players) { player ->
                            Row(Modifier.fillMaxWidth()
                                .clickable(enabled = player.playerUrl != null) {
                                player.playerUrl?.let(onPlayerOpen)
                            }.padding(vertical = 13.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                EsportsPlayerPortrait(player)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(player.name, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    val performance = listOf(player.rating.takeIf(String::isNotBlank)?.let { "$it R" },
                                        player.acs.takeIf(String::isNotBlank)?.let { "$it ACS" },
                                        player.adr.takeIf(String::isNotBlank)?.let { "$it ADR" }).filterNotNull().joinToString("  ·  ")
                                    Text(performance.ifBlank { txt("Live metrics pending", "Canlı veriler bekleniyor") }, color = Muted, fontSize = 11.sp)
                                    val impact = listOf(player.hs.takeIf(String::isNotBlank)?.let { "$it HS" },
                                        player.kast?.takeIf(String::isNotBlank)?.let { "$it KAST" },
                                        player.firstKills?.takeIf(String::isNotBlank)?.let { "$it FK" },
                                        player.firstDeaths?.takeIf(String::isNotBlank)?.let { "$it FD" }).filterNotNull().joinToString("  ·  ")
                                    if(impact.isNotBlank()) Text(impact, color = Muted, fontSize = 11.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    fun shown(value: String) = value.ifBlank { "—" }
                                    Text("${shown(player.kills)} / ${shown(player.deaths)} / ${shown(player.assists)}", color = White, fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold)
                                    Text("K / D / A", color = Muted, fontSize = 10.sp)
                                }
                            }
                            HorizontalDivider(color = Raised.copy(alpha = .55f))
                        }
                    }
                }
            }
            "watch" -> {
                item {
                    TextButton(onClick = onQueue) { Text(if(queued) txt("Remove from Watch later", "Sonra izle'den kaldır") else txt("Watch later", "Sonra izle")) }
                    Text(txt("A private queue; it does not subscribe you to alerts. Replay links appear only when supplied by the source.",
                        "Kişisel liste; bildirim aboneliği açmaz. Tekrar bağlantıları yalnızca kaynak sağladığında görünür."), color = Muted, fontSize = 12.sp)
                }
                item { SectionTitle(txt("Streams & replays","Yayınlar ve tekrarlar")) }
                val links = detail?.streams.orEmpty().map { Triple(it.first, it.second, if(tr) "Canlı yayın" else "Live stream") } +
                    detail?.vods.orEmpty().map { Triple(it.first, it.second, "VOD") }
                if(links.isEmpty()) item { Note(txt("No stream or replay link was supplied.","Yayın veya tekrar bağlantısı verilmedi.")) }
                items(links) { (label, url, kind) ->
                    Row(Modifier.fillMaxWidth().clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = White, fontSize = 14.sp)
                            Text(kind, color = Muted, fontSize = 10.sp)
                        }
                        Text("↗", color = Accent, fontSize = 17.sp)
                    }
                    HorizontalDivider(color = Raised.copy(alpha = .55f))
                }
            }
        }
        item {
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(match.url))) } }) {
                Text(txt("Source · VLR.gg ↗","Kaynak · VLR.gg ↗"))
            }
        }
    }
}

@Composable private fun SeriesComparison(match: EsportsMatch, maps: List<EsportsMapResult>) {
    val mapWins1 = maps.count { (it.score1 ?: 0) > (it.score2 ?: 0) }
    val mapWins2 = maps.count { (it.score2 ?: 0) > (it.score1 ?: 0) }
    val rounds1 = maps.sumOf { it.score1 ?: 0 }
    val rounds2 = maps.sumOf { it.score2 ?: 0 }
    val attack1 = maps.mapNotNull { it.attack1 }.sum().takeIf { maps.any { map -> map.attack1 != null } }
    val attack2 = maps.mapNotNull { it.attack2 }.sum().takeIf { maps.any { map -> map.attack2 != null } }
    val defense1 = maps.mapNotNull { it.defense1 }.sum().takeIf { maps.any { map -> map.defense1 != null } }
    val defense2 = maps.mapNotNull { it.defense2 }.sum().takeIf { maps.any { map -> map.defense2 != null } }
    Column(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 4.dp)) {
        SectionTitle(txt("Series comparison", "Seri karşılaştırması"))
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                TeamLogo(match.team1, match.team1Logo, 34.dp)
                Text(match.team1, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
            Text("VS", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                TeamLogo(match.team2, match.team2Logo, 34.dp)
                Text(match.team2, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
        }
        listOfNotNull(
            Triple(mapWins1.toString(), txt("Map wins", "Harita galibiyeti"), mapWins2.toString()),
            Triple(rounds1.toString(), txt("Rounds", "Raunt"), rounds2.toString()),
            if(attack1 != null && attack2 != null) Triple(attack1.toString(), txt("Attack rounds", "Hücum rauntu"), attack2.toString()) else null,
            if(defense1 != null && defense2 != null) Triple(defense1.toString(), txt("Defense rounds", "Savunma rauntu"), defense2.toString()) else null
        ).forEach { (left, label, right) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(left, color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
                Text(label, color = Muted, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(136.dp))
                Text(right, color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = Raised.copy(alpha = .48f))
        }
    }
}

@Composable private fun EsportsMapCard(map: EsportsMapResult, art: String?, match: EsportsMatch, veto: String?,
                                      players: List<EsportsPlayerLine>, hideScores: Boolean, onOpen: () -> Unit) {
    val selection = esportsMapSelection(map.name, veto, match.team1, match.team2)
    Box(Modifier.fillMaxWidth().height(174.dp).padding(vertical = 5.dp).clip(RoundedCornerShape(20.dp))
        .background(Panel).clickable(onClick = onOpen)) {
        if(art != null) coil.compose.AsyncImage(art, map.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink.copy(alpha = .95f), Ink.copy(alpha = .48f), Ink.copy(alpha = .82f)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(map.name, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if(hideScores) "•••" else if(map.score1 != null && map.score2 != null) "${map.score1} : ${map.score2}" else "—",
                    color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            if(players.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                players.filter { it.team == match.team1 }.take(5).forEach { player ->
                    coil.compose.AsyncImage(player.agentIcon, player.name, Modifier.size(25.dp), contentScale = ContentScale.Fit)
                }
                Spacer(Modifier.weight(1f))
                val halves = listOfNotNull(map.attack1?.let { "ATK $it" }, map.defense1?.let { "DEF $it" }).joinToString(" · ")
                Text(if(hideScores) "" else halves, color = White.copy(alpha = .68f), fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                players.filter { it.team == match.team2 }.take(5).forEach { player ->
                    coil.compose.AsyncImage(player.agentIcon, player.name, Modifier.size(25.dp), contentScale = ContentScale.Fit)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(match.team1, match.team1Logo, 30.dp)
                Column(Modifier.padding(start = 7.dp).weight(1f)) {
                    Text(match.team1, color = White, fontSize = 11.sp, maxLines = 1)
                    if(selection.first == 1) Text(txt("PICKED","SEÇTİ"), color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(map.duration.ifBlank { if(map.score1 != null) txt("FINAL","BİTTİ") else "—" }, color = White.copy(alpha = .78f), fontSize = 10.sp)
                Column(Modifier.padding(start = 10.dp, end = 7.dp).weight(1f), horizontalAlignment = Alignment.End) {
                    Text(match.team2, color = White, fontSize = 11.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    if(selection.first == 2) Text(txt("PICKED","SEÇTİ"), color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                TeamLogo(match.team2, match.team2Logo, 30.dp)
            }
            Text(txt("Tap for map details", "Harita detayları için dokun"), color = White.copy(alpha = .62f),
                fontSize = 9.sp, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
        }
    }
}

@Composable private fun EsportsMapDetail(map: EsportsMapResult, art: String?, match: EsportsMatch, veto: String?,
                                         players: List<EsportsPlayerLine>, hideScores: Boolean,
                                         onBack: () -> Unit, onPlayerOpen: (String) -> Unit) {
    val selection = esportsMapSelection(map.name, veto, match.team1, match.team2)
    val teamOne = players.filter { it.team == match.team1 }.sortedByDescending { it.acs.toIntOrNull() ?: -1 }
    val teamTwo = players.filter { it.team == match.team2 }.sortedByDescending { it.acs.toIntOrNull() ?: -1 }
    val tableScroll = rememberScrollState()
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(txt("← Match", "← Maç")) }
                Spacer(Modifier.weight(1f))
                Text(txt("Source: VLR.gg", "Kaynak: VLR.gg"), color = Muted, fontSize = 10.sp)
            }
            Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(24.dp)).background(Panel)) {
                if(art != null) coil.compose.AsyncImage(art, map.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.copy(alpha = .22f), Ink.copy(alpha = .96f)))))
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                    Text(map.name, color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(selection.second ?: map.pick.ifBlank { txt("Series map", "Seri haritası") }, color = White.copy(alpha = .72f),
                        fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(if(hideScores) "•••" else if(map.score1 != null && map.score2 != null) "${map.score1} : ${map.score2}" else "—",
                    color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(match.team1, match.team1Logo, 40.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(match.team1, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if(hideScores) "ATK — · DEF —" else "ATK ${map.attack1 ?: "—"} · DEF ${map.defense1 ?: "—"}", color = Muted, fontSize = 10.sp)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(match.team2, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if(hideScores) "ATK — · DEF —" else "ATK ${map.attack2 ?: "—"} · DEF ${map.defense2 ?: "—"}", color = Muted, fontSize = 10.sp)
                }
                TeamLogo(match.team2, match.team2Logo, 40.dp)
            }
            if(map.duration.isNotBlank()) Text(txt("Duration: ${map.duration}", "Süre: ${map.duration}"), color = Muted, fontSize = 10.sp)
            HorizontalDivider(color = Raised, modifier = Modifier.padding(top = 15.dp))
        }
        if(players.isEmpty()) item { Note(txt("Detailed player data has not been published for this map yet.",
            "Bu haritanın ayrıntılı oyuncu verileri henüz yayımlanmadı.")) }
        if(hideScores) item { Note(txt("Player metrics are hidden by your spoiler preference.", "Oyuncu verileri spoiler tercihin nedeniyle gizli.")) }
        (if(hideScores) emptyList() else listOf(match.team1 to teamOne, match.team2 to teamTwo)).forEach { (team, rows) ->
            if(rows.isNotEmpty()) {
                item {
                    Text(team, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 20.dp, bottom = 7.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(tableScroll).padding(bottom = 4.dp)) {
                        Text(txt("PLAYER", "OYUNCU"), color = Muted, fontSize = 8.sp, modifier = Modifier.width(167.dp))
                        listOf("ACS", "K/D/A", "ADR", "HS%", "KAST", "FK/FD").forEach { label ->
                            Text(label, color = Muted, fontSize = 8.sp, modifier = Modifier.width(if(label == "K/D/A") 62.dp else 43.dp), maxLines = 1)
                        }
                    }
                }
                items(rows, key = { "${map.name}-${team}-${it.name}" }) { player ->
                    EsportsMapPlayerRow(player, tableScroll, onPlayerOpen)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable private fun EsportsMapPlayerRow(player: EsportsPlayerLine, tableScroll: ScrollState, onPlayerOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(tableScroll).clickable(enabled = player.playerUrl != null) { player.playerUrl?.let(onPlayerOpen) }
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        coil.compose.AsyncImage(player.agentIcon, player.name, Modifier.size(30.dp), contentScale = ContentScale.Fit)
        Text(player.name, color = White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(137.dp).padding(start = 7.dp))
        Text(player.acs.ifBlank { "—" }, color = White, fontSize = 10.sp, modifier = Modifier.width(43.dp))
        Text("${player.kills}/${player.deaths}/${player.assists}", color = White, fontSize = 10.sp, modifier = Modifier.width(62.dp))
        Text(player.adr.ifBlank { "—" }, color = White, fontSize = 10.sp, modifier = Modifier.width(43.dp))
        Text(player.hs.ifBlank { "—" }, color = White, fontSize = 10.sp, modifier = Modifier.width(43.dp))
        Text(player.kast?.ifBlank { "—" } ?: "—", color = White, fontSize = 10.sp, modifier = Modifier.width(43.dp))
        Text("${player.firstKills?.ifBlank { "—" } ?: "—"}/${player.firstDeaths?.ifBlank { "—" } ?: "—"}",
            color = White, fontSize = 10.sp, modifier = Modifier.width(43.dp))
    }
    HorizontalDivider(color = Raised.copy(alpha = .5f))
}

internal fun esportsMapSelection(mapName: String, veto: String?, team1: String, team2: String): Pair<Int?, String?> {
    val action = veto?.split(';')?.map { it.trim() }?.firstOrNull {
        it.contains(mapName, true) && (it.contains("pick", true) || it.contains("select", true))
    }
    return (action?.let { esportsVetoTeam(it, team1, team2) }) to action
}

@Composable private fun EsportsVetoCard(index: Int, action: String, mapArt: Map<String, String>, match: EsportsMatch) {
    val key = mapArt.keys.sortedByDescending { it.length }.firstOrNull { action.contains(it, ignoreCase = true) }
    val ban = action.contains("ban", true)
    val pick = action.contains("pick", true)
    val matchedTeam = esportsVetoTeam(action, match.team1, match.team2)
    val teamOne = matchedTeam == 1
    val teamTwo = matchedTeam == 2
    val logo = when { teamOne -> match.team1Logo; teamTwo -> match.team2Logo; else -> null }
    val team = when { teamOne -> match.team1; teamTwo -> match.team2; else -> "" }
    Box(Modifier.fillMaxWidth().height(92.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(17.dp)).background(Panel)) {
        key?.let { coil.compose.AsyncImage(mapArt[it], it, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink.copy(alpha = .96f), Ink.copy(alpha = .58f), Ink.copy(alpha = .86f)))))
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(index.toString().padStart(2, '0'), color = Muted, fontSize = 11.sp, modifier = Modifier.width(28.dp))
            if(logo != null) TeamLogo(team, logo, 34.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(key?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: action.substringAfterLast(' '), color = White,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(action, color = White.copy(alpha = .7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(when { ban -> txt("BANNED", "BAN"); pick -> txt("PICKED", "SEÇİM"); else -> txt("DECIDER", "KALAN") },
                color = if(ban) Color(0xFFFF6B73) else Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

internal fun esportsVetoTeam(action: String, team1: String, team2: String): Int? {
    val actor = action.split(Regex("(?i)\\s+(ban|pick|remove|select)s?\\s+"), limit = 2).first()
        .trim().replace(Regex("[^A-Za-z0-9]"), "")
    if(actor.length < 2) return null
    fun matches(team: String): Boolean {
        val compact = team.replace(Regex("[^A-Za-z0-9]"), "")
        val first = team.substringBefore(' ').replace(Regex("[^A-Za-z0-9]"), "")
        return actor.equals(compact, true) || actor.equals(first, true) ||
            (actor.length >= 3 && compact.startsWith(actor, true))
    }
    return when {
        matches(team1) -> 1
        matches(team2) -> 2
        else -> null
    }
}

@Composable private fun EsportsPlayerPortrait(player: EsportsPlayerLine) {
    val context = LocalContext.current
    val repository = remember { EsportsExploreRepository(context) }
    var portrait by remember(player.playerUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(player.playerUrl) {
        val url = player.playerUrl ?: return@LaunchedEffect
        portrait = EsportsPortraits[url]
        if(portrait == null) runCatching { repository.player(url, includeMatches = false).player.image }.onSuccess { portrait = it }
    }
    Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(15.dp)).background(Raised).padding(3.dp), contentAlignment = Alignment.Center) {
            Text(player.name.take(1).uppercase(), color = White, fontWeight = FontWeight.Bold)
            player.agentIcon?.let { agent ->
                coil.compose.AsyncImage(agent, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        portrait?.let {
            Box(Modifier.align(Alignment.BottomEnd).size(23.dp).clip(CircleShape).background(Ink).padding(2.dp)) {
                coil.compose.AsyncImage(it, player.name, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable private fun EsportsMatchCard(match: EsportsMatch, favorites: Set<String>, onTeamLink: () -> Unit,
                                        onTeamOpen: (String) -> Unit, spoilerMode: Boolean, saved: Boolean, onSave: () -> Unit,
                                        tracked: Boolean, onFollow: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { EsportsRepository(context) }
    var rich by remember(match.id) { mutableStateOf(repo.cachedDetail(match.id)?.match ?: match) }
    var links by remember(match.id) { mutableStateOf(repo.cachedDetail(match.id)) }
    LaunchedEffect(match.id) {
        runCatching { repo.details(match) }.onSuccess { detail ->
            rich = detail.match
            links = detail
            listOf(match.team1 to detail.team1Url, match.team2 to detail.team2Url).forEach { (name, url) ->
                if(favorites.any { it.equals(name, true) } && url != null && EsportsTeamLinks.get(context, name) != url) {
                    EsportsTeamLinks.put(context, name, url)
                    onTeamLink()
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(top = 13.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if(rich.eventIcon != null) coil.compose.AsyncImage(rich.eventIcon, null, Modifier.size(18.dp).padding(end = 5.dp), contentScale = ContentScale.Fit)
            Text(match.event.ifBlank { "VALORANT" }, color = Muted, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(when(match.status) { "live" -> txt("● LIVE","● CANLI"); "result" -> txt("FINAL","BİTTİ"); "cancelled" -> txt("CANCELLED", "İPTAL"); else -> txt("UPCOMING","YAKLAŞAN") },
                color = if(match.status == "live") Color(0xFFFF6B73) else Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onSave, modifier = Modifier.size(44.dp)) {
                Text(if(saved) "★" else "☆", color = if(saved) Accent else Muted, fontSize = 22.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { links?.team1Url?.let(onTeamOpen) ?: onOpen() }
                .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TeamLogo(match.team1, rich.team1Logo ?: match.team1Logo, 44.dp)
                Text(match.team1, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp))
            }
            Column(Modifier.width(88.dp).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if(match.status == "upcoming") "VS" else SpoilerPolicy.score(match, spoilerMode),
                    color = White, fontSize = if(spoilerMode) 14.sp else 24.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if(match.status == "upcoming" && rich.startAtMillis != null)
                    Text(esportsCountdown(rich.startAtMillis, turkish = LocalTurkish.current), color = Accent, fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 5.dp))
            }
            Column(Modifier.weight(1f).clickable { links?.team2Url?.let(onTeamOpen) ?: onOpen() }
                .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TeamLogo(match.team2, rich.team2Logo ?: match.team2Logo, 44.dp)
                Text(match.team2, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(match.series, rich.startAtMillis?.let { localMatchTime(it, LocalTurkish.current) }
                ?: esportsDisplayWhen(match.whenText, LocalTurkish.current))
                .filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if(match.status == "live") Text(if(tracked) txt("Unpin","Bırak") else txt("Pin score","Skoru izle"),
                color = Accent, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onFollow).padding(start = 6.dp, top = 7.dp, bottom = 7.dp))
        }
        HorizontalDivider(color = Raised.copy(alpha = .58f), modifier = Modifier.padding(top = 10.dp))
    }
}
