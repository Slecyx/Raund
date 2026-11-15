package app.nightwatch.store

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.google.gson.Gson
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    private var navigationRequest by mutableStateOf(0 to Page.STORE)
    private var requestedMatch by mutableStateOf<Pair<Int, EsportsMatch?>>(0 to null)
    private fun destination(intent: Intent): Page = when {
        intent.getBooleanExtra("open_esports", false) -> Page.ESPORTS
        intent.getBooleanExtra("open_favorites", false) -> Page.FAVORITES
        intent.getBooleanExtra("open_career", false) -> Page.CAREER
        else -> Page.STORE
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigationRequest = 0 to destination(intent)
        requestedMatch = 0 to MatchNavigation.read(intent)
        setContent { NightwatchApp(navigationRequest, requestedMatch) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigationRequest = navigationRequest.first + 1 to destination(intent)
        requestedMatch = navigationRequest.first to MatchNavigation.read(intent)
    }
}

internal val Shape = RoundedCornerShape(24.dp)
internal val LocalTurkish = staticCompositionLocalOf { false }
internal enum class RaundWidthClass { COMPACT, MEDIUM, EXPANDED }
internal val LocalRaundWidthClass = staticCompositionLocalOf { RaundWidthClass.COMPACT }
@Composable internal fun txt(en: String, tr: String) = if(LocalTurkish.current) tr else en
internal enum class Page { STORE, CAREER, ESPORTS, FAVORITES, SETTINGS }
internal data class SeasonWindow(val id: String, val label: String, val startAt: Long, val endAt: Long) {
    fun contains(time: Long) = time >= startAt && time < endAt
}

@Composable
private fun NightwatchApp(requestedPage: Pair<Int, Page>, requestedMatch: Pair<Int, EsportsMatch?>) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE) }
    val secure = remember { SessionStore(context) }
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<LiveStoreAccount?>(null) }
    var resolvedOwned by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(account?.accountId, account?.inventory?.checkedAt) {
        val levels = account?.inventory?.skinLevelIds.orEmpty()
        resolvedOwned = levels.toSet()
        if(levels.isNotEmpty()) resolvedOwned = withContext(Dispatchers.IO) {
            levels.toSet() + runCatching { ContentCatalog(context).ownedSkins(levels).map { it.id } }.getOrDefault(emptyList())
        }
    }
    var page by rememberSaveable { mutableStateOf(requestedPage.second) }
    var matchRoute by remember { mutableStateOf<Pair<Int, EsportsMatch?>>(0 to null) }
    LaunchedEffect(requestedMatch) { matchRoute = requestedMatch }
    LaunchedEffect(requestedPage) { if(requestedPage.first > 0 || requestedPage.second != Page.STORE) page = requestedPage.second }
    // The navigation is a primary control, especially on phones.  Hiding it after a
    // small scroll was easy to trigger accidentally and made the app feel broken.
    var navVisible by remember(page) { mutableStateOf(true) }
    var esportsSubpage by remember { mutableStateOf(false) }
    val navScroll = remember(page) { object : NestedScrollConnection {
        var travel = 0f
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if(source == NestedScrollSource.UserInput) {
                travel = (travel + consumed.y).coerceIn(-100f, 100f)
                navVisible = true
            }
            return Offset.Zero
        }
    } }
    var language by rememberSaveable { mutableStateOf(prefs.getString("language", null) ?: if(Locale.getDefault().language == "tr") "TR" else "EN") }
    var themeMode by rememberSaveable { mutableStateOf(prefs.getString("theme", "system").orEmpty()) }
    var onboarding by rememberSaveable { mutableStateOf(!prefs.getBoolean("onboarding_complete", false)) }
    var loading by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(true) }
    var login by rememberSaveable { mutableStateOf(false) }
    var needsLogin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var lastRefreshAttemptAt by remember { mutableLongStateOf(0L) }
    var selected by remember { mutableStateOf<StoreOffer?>(null) }
    var selectedOwned by remember { mutableStateOf(false) }
    var comparisonBase by remember { mutableStateOf<StoreOffer?>(null) }
    var bundle by remember { mutableStateOf<StoreBundle?>(null) }
    var radar by remember { mutableStateOf(false) }
    var inventoryOpen by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<MatchSummary?>(null) }
    var catalog by remember { mutableStateOf<List<StoreOffer>>(emptyList()) }
    var catalogError by remember { mutableStateOf(false) }
    var catalogLoading by remember { mutableStateOf(false) }
    var agents by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var maps by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var seasons by remember { mutableStateOf<List<SeasonWindow>>(emptyList()) }
    var historyLimit by remember { mutableIntStateOf(20) }
    val wished = remember { mutableStateListOf<String>().apply { addAll(prefs.getStringSet("wishlist", emptySet()).orEmpty().filter { it.length == 36 }) } }
    var savedItems by remember { mutableStateOf(runCatching { Gson().fromJson(prefs.getString("favorite_items", "[]"), Array<StoreOffer>::class.java).toList() }.getOrDefault(emptyList())) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("notifications", false)) }
    var esportsMode by remember { mutableStateOf(prefs.getString("esports_mode", "off") ?: "off") }
    var matchAlerts by remember { mutableStateOf(prefs.getBoolean("match_alerts", true)) }
    var pendingEsportsMode by remember { mutableStateOf("favorites") }
    var esportsTeams by remember { mutableStateOf(prefs.getStringSet("esports_teams", emptySet()).orEmpty().toSet()) }
    var esportsPlayers by remember { mutableStateOf(prefs.getStringSet("esports_players", emptySet()).orEmpty().toSet()) }
    var trackedMatch by remember { mutableStateOf(prefs.getString("tracked_match_id", null)) }
    fun toggleTeam(team: String) {
        esportsTeams = if(team in esportsTeams) esportsTeams - team else esportsTeams + team
        prefs.edit().putStringSet("esports_teams", esportsTeams).apply()
        EsportsScoreScheduler.schedule(context, esportsMode)
        EsportsReminders.sync(context, SavedEsportsMatches.get(context) + EsportsRepository(context).cached()?.upcoming.orEmpty())
        EsportsReminders.refreshSoon(context)
        RaundWidgetProvider.updateAll(context)
    }
    fun togglePlayer(url: String) {
        esportsPlayers = if(url in esportsPlayers) esportsPlayers - url else esportsPlayers + url
        prefs.edit().putStringSet("esports_players", esportsPlayers).apply()
    }
    fun setEsportsMode(mode: String) {
        esportsMode = mode
        prefs.edit().putString("esports_mode", mode).apply()
        EsportsScoreScheduler.schedule(context, mode)
    }
    fun setMatchAlerts(enabled: Boolean) {
        matchAlerts = enabled
        prefs.edit().putBoolean("match_alerts", enabled).apply()
        val saved = SavedEsportsMatches.get(context)
        if(!enabled) saved.forEach { context.getSystemService(android.app.NotificationManager::class.java).cancel(ScoreNotifications.favoriteId(it)) }
        else EsportsScoreScheduler.syncSaved(context, saved, saved)
        EsportsScoreScheduler.schedule(context, esportsMode)
        if(enabled) EsportsReminders.refreshSoon(context)
    }
    val esportsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) setEsportsMode(pendingEsportsMode)
    }
    fun requestEsportsMode(mode: String) {
        if(mode != "off" && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingEsportsMode = mode
            esportsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        else setEsportsMode(mode)
    }
    fun setAlerts(enabled: Boolean) {
        notifications = enabled
        prefs.edit().putBoolean("notifications", enabled).apply()
        FavoriteAlerts.schedule(context, enabled && prefs.getBoolean("notify_store", true))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setAlerts(it) }
    fun requestAlerts(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else setAlerts(enabled)
    }
    fun toggle(id: String) {
        val adding = id !in wished
        if(!adding) wished.remove(id) else wished.add(id)
        val sources = account?.snapshot?.let { it.offers + it.accessories + it.nightOffers + it.bundles.flatMap { bundle -> bundle.items } }.orEmpty() + catalog
        savedItems = (savedItems + sources.filter { it.id == id }).distinctBy { it.id }.filter { it.id in wished }
        prefs.edit().putStringSet("wishlist", wished.toSet()).putString("favorite_items", Gson().toJson(savedItems)).apply()
        prefs.edit().apply {
            if(adding) putLong("favorite_since-$id", System.currentTimeMillis()) else remove("favorite_since-$id")
        }.apply()
        if(adding && !notifications && !prefs.getBoolean("alerts_prompted", false)) {
            prefs.edit().putBoolean("alerts_prompted", true).apply()
            requestAlerts(true)
        }
        RaundWidgetProvider.updateAll(context)
    }
    fun loadCatalog() {
        if(catalogLoading) return
        catalogLoading = true; catalogError = false
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ContentCatalog(context).skins() } }.onSuccess { catalog = it }.onFailure { catalogError = true }
            catalogLoading = false
        }
    }
    fun refresh(session: RiotSession? = null, limit: Int = historyLimit) {
        if(job?.isActive == true) return
        lastRefreshAttemptAt = System.currentTimeMillis()
        job = scope.launch {
            loading = true; error = null
            try {
                val valid = session ?: secure.validSession()
                withContext(Dispatchers.IO) { secure.save(valid) }
                needsLogin = false
                val result = RiotLiveGateway(context).loadStore(valid, historyLimit = limit, onStoreReady = { fresh ->
                    RadarRepository(context).record(fresh)
                    withContext(Dispatchers.Main) {
                        val sameAccount = account?.accountId != null && account?.accountId == fresh.accountId && account?.region == fresh.region
                        val old = account
                        account = fresh.copy(career = if(sameAccount) old?.career else null, progress = if(sameAccount) old?.progress else null,
                            snapshot = fresh.snapshot)
                    }
                })
                withContext(Dispatchers.IO) { secure.cache(result); RadarRepository(context).record(result) }
                account = result
                prefs.edit().putBoolean("connected", true).putBoolean("background_login_required", false).apply()
                FavoriteAlerts.schedule(context, notifications && prefs.getBoolean("notify_store", true))
                WidgetRefreshScheduler.schedule(context)
                RaundWidgetProvider.updateAll(context)
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                needsLogin = e is LoginRequired || e.cause is LoginRequired
                error = if(needsLogin) (if(language == "TR") "Riot oturumunu yenilemen gerekiyor. Kayıtlı verilerin duruyor." else "Please renew your Riot session. Your saved data is still here.")
                    else if(language == "TR") "Bağlantı kurulamadı. İnternetini kontrol edip tekrar dene." else "Could not connect. Check your internet connection and retry."
            } finally { loading = false; restoring = false }
        }
    }
    LaunchedEffect(Unit) {
        EsportsScoreScheduler.schedule(context, esportsMode)
        FavoriteAlerts.schedule(context, notifications && prefs.getBoolean("notify_store", true))
        WidgetRefreshScheduler.schedule(context)
        withContext(Dispatchers.IO) {
            if(!prefs.getBoolean("reviews_removed_v9", false)) {
                check(context.getSharedPreferences("nightwatch_notes", Context.MODE_PRIVATE).edit().clear().commit())
                prefs.edit().putBoolean("reviews_removed_v9", true).apply()
            }
        }
        account = withContext(Dispatchers.IO) { secure.cached() }
        restoring = false
        val cachedAccount = account
        val now = System.currentTimeMillis()
        if(cachedAccount != null) {
            if(cachedAccount.snapshot.refreshEpochSeconds <= now / 1000 || now - cachedAccount.updatedAt >= 10 * 60_000L) refresh()
        } else if(prefs.getBoolean("connected", false) || !CookieManager.getInstance().getCookie("https://auth.riotgames.com").isNullOrBlank()) refresh()
        loadCatalog()
        withContext(Dispatchers.IO) {
            val public = ContentCatalog(context)
            val a = runCatching { public.data("agents").objects().associateBy { it.optString("uuid") } }.getOrDefault(emptyMap())
            val m = runCatching { public.data("maps").objects().associateBy { it.optString("displayName") } }.getOrDefault(emptyMap())
            val s = runCatching { public.data("seasons").objects().mapNotNull { row ->
                val start = runCatching { Instant.parse(row.optString("startTime")).toEpochMilli() }.getOrNull()
                val end = runCatching { Instant.parse(row.optString("endTime")).toEpochMilli() }.getOrNull()
                if(start == null || end == null || !row.optString("type").endsWith("::Act")) null
                else SeasonWindow(row.optString("uuid"), row.optString("displayName").ifBlank { "Act" }, start, end)
            }.sortedByDescending { it.startAt } }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { agents = a; maps = m; seasons = s }
        }
    }
    val resumeAction by rememberUpdatedState {
        val now = System.currentTimeMillis()
        val cachedAccount = account
        if(!login && !restoring && cachedAccount != null && !loading && now - lastRefreshAttemptAt >= 5 * 60_000L &&
            (cachedAccount.snapshot.refreshEpochSeconds <= now / 1000 || now - cachedAccount.updatedAt >= 10 * 60_000L)) refresh()
    }
    DisposableEffect(context) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) resumeAction() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    CompositionLocalProvider(LocalTurkish provides (language == "TR")) {
        NightwatchTheme(themeMode) {
            var tick by remember { mutableLongStateOf(System.currentTimeMillis()/1000) }
            val pageStateHolder = rememberSaveableStateHolder()
            LaunchedEffect(Unit) { while(true) { delay(30_000); tick = System.currentTimeMillis()/1000 } }
            LaunchedEffect(tick) {
                val expired = account?.snapshot?.refreshEpochSeconds?.let { it <= tick } == true
                val resumed = (context as ComponentActivity).lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if(expired && resumed && !login && !loading && !restoring &&
                    System.currentTimeMillis() - lastRefreshAttemptAt >= 5 * 60_000L) refresh()
            }
            val visibleAccount = account?.let { it.copy(snapshot = StoreFreshness.active(it.snapshot, tick)) }
            BoxWithConstraints(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
                val widthClass = when {
                    maxWidth >= 1000.dp -> RaundWidthClass.EXPANDED
                    maxWidth >= 600.dp -> RaundWidthClass.MEDIUM
                    else -> RaundWidthClass.COMPACT
                }
                CompositionLocalProvider(LocalRaundWidthClass provides widthClass) {
                val contentMaxWidth = when(page) {
                    Page.SETTINGS -> 720.dp
                    Page.CAREER, Page.ESPORTS -> 900.dp
                    else -> 1040.dp
                }
                Row(Modifier.fillMaxSize().then(if(widthClass == RaundWidthClass.COMPACT) Modifier else Modifier.navigationBarsPadding())) {
                    if(widthClass != RaundWidthClass.COMPACT) AdaptiveNavigationRail(page) { page = it }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.fillMaxHeight().widthIn(max = contentMaxWidth).fillMaxWidth().nestedScroll(navScroll)) {
                    Box(Modifier.weight(1f)) {
                    pageStateHolder.SaveableStateProvider(page.name) {
                        val destination = page
                        when(destination) {
                            Page.STORE -> StorePage(visibleAccount, loading || restoring, error, needsLogin, { if(needsLogin || account == null) login = true else refresh() }, { refresh() }, wished, ::toggle, { selected = it }, { bundle = it }, { inventoryOpen = true })
                            Page.CAREER -> CareerPage(account?.career, account?.progress, loading, agents, maps, seasons, { match = it }, { if(account == null) login = true else refresh() }, { historyLimit += 20; refresh(limit = historyLimit) })
                            Page.ESPORTS -> EsportsPage(esportsTeams, ::toggleTeam, esportsPlayers, ::togglePlayer, trackedMatch, { trackedMatch = it }, matchAlerts, { page = Page.SETTINGS }, { esportsSubpage = it }, matchRoute, { matchRoute = matchRoute.first to null })
                            Page.FAVORITES -> FavoritesPage(catalog + savedItems, wished, catalogLoading, catalogError, notifications, visibleAccount?.snapshot,
                                resolvedOwned, ::toggle, { selected = it; selectedOwned = it.id in resolvedOwned }, ::loadCatalog, ::requestAlerts, { radar = true })
                            Page.SETTINGS -> SettingsPage(account, themeMode, { themeMode = it; prefs.edit().putString("theme", it).apply(); RaundWidgetProvider.updateAll(context) }, language, notifications, { language = it; prefs.edit().putString("language", it).apply(); RaundWidgetProvider.updateAll(context) }, ::requestAlerts,
                                esportsMode, ::requestEsportsMode, esportsTeams, matchAlerts, ::setMatchAlerts,
                                { login = true }, { onboarding = true }, {
                                    scope.launch {
                                        job?.cancelAndJoin(); job = null
                                        withContext(Dispatchers.IO) { secure.clear() }
                                        account = null; needsLogin = false; error = null
                                        prefs.edit().putBoolean("connected", false).apply()
                                        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                                        WebStorage.getInstance().deleteAllData(); FavoriteAlerts.schedule(context, false)
                                    }
                                })
                        }
                    }
                    }
                    AnimatedVisibility(widthClass == RaundWidthClass.COMPACT && (page != Page.ESPORTS || !esportsSubpage)) { FloatingTabs(page) { page = it } }
                    }
                    }
                }
                bundle?.let { value -> DetailPage(value.name, { bundle = null }) {
                    AsyncImage(value.image, value.name, Modifier.fillMaxWidth().height(220.dp).clip(Shape), contentScale = ContentScale.Crop)
                    SectionTitle(txt("Bundle contents", "Paket içeriği"), value.price?.let { "$it VP" }.orEmpty())
                    Note(txt("Prices below apply within this bundle; they are not standalone purchase offers.", "Aşağıdaki fiyatlar paket içindir; ayrı satın alma teklifi değildir."))
                    value.items.forEach { SkinRow(it, it.id in wished, { toggle(it.id) }, { selected = it }) }
                    if(value.items.isEmpty()) Note(txt("Riot did not return the bundle contents.", "Riot paket içeriğini döndürmedi."))
                } }
                if(radar) RadarPage(visibleAccount, catalog + savedItems, wished, { selected = it }) { radar = false }
                if(inventoryOpen) InventoryPage(account, loading, { refresh() }, { offer, owned -> selected = offer; selectedOwned = owned }) { inventoryOpen = false }
                match?.let { MatchDetailPage(it, agents, maps, account?.career?.matches.orEmpty()) { match = null } }
                selected?.let { offer -> SkinDetailPage(offer, offer.id in wished, selectedOwned, { toggle(offer.id) }, {
                    comparisonBase = offer; selected = null; selectedOwned = false
                }) { selected = null; selectedOwned = false } }
                comparisonBase?.let { base ->
                    val allSkins = (catalog + savedItems + account?.snapshot?.offers.orEmpty() + account?.snapshot?.nightOffers.orEmpty() + account?.snapshot?.bundles.orEmpty().flatMap { it.items }).distinctBy { it.id }
                    SkinComparePage(base, allSkins, wished.toSet(), resolvedOwned) { comparisonBase = null }
                }
                if(login) VerifiedRiotLogin({ session -> login = false; refresh(session) }, { login = false })
                if(onboarding) RaundOnboarding(
                    onExplore = { prefs.edit().putBoolean("onboarding_complete", true).apply(); onboarding = false },
                    onConnect = { prefs.edit().putBoolean("onboarding_complete", true).apply(); onboarding = false; login = true }
                )
                }
            }
        }
    }
}

@Composable internal fun FloatingTabs(selected: Page, onSelect: (Page) -> Unit) {
    val clickSound = rememberInteractionSound()
    Column(Modifier.fillMaxWidth().background(Ink).navigationBarsPadding()) {
        HorizontalDivider(color = Raised.copy(alpha = .8f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Page.entries.forEach { item ->
                val active = selected == item
                val label = when(item) { Page.STORE -> txt("Store","Mağaza"); Page.CAREER -> txt("Career","Kariyer"); Page.ESPORTS -> txt("Esports","Espor"); Page.FAVORITES -> txt("Saved","Favori"); Page.SETTINGS -> txt("Settings","Ayarlar") }
                val icon = when(item) { Page.STORE -> R.drawable.ic_tab_store; Page.CAREER -> R.drawable.ic_tab_career; Page.ESPORTS -> R.drawable.ic_tab_esports; Page.FAVORITES -> R.drawable.ic_tab_favorites; Page.SETTINGS -> R.drawable.ic_tab_settings }
                Column(Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab, onClick = { if(!active) clickSound(); onSelect(item) })
                    .semantics { contentDescription = label }
                    .padding(top = 5.dp, bottom = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if(active) Accent else Muted)
                    Text(label, color = if(active) White else Muted, fontSize = 10.sp, fontWeight = if(active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 3.dp), maxLines = 1)
                }
            }
        }
    }
}

@Composable private fun AdaptiveNavigationRail(selected: Page, onSelect: (Page) -> Unit) {
    val clickSound = rememberInteractionSound()
    Column(Modifier.width(92.dp).fillMaxHeight().background(Ink).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Page.entries.forEach { item ->
            val active = selected == item
            val label = when(item) { Page.STORE -> txt("Store","Mağaza"); Page.CAREER -> txt("Career","Kariyer"); Page.ESPORTS -> txt("Esports","Espor"); Page.FAVORITES -> txt("Saved","Favori"); Page.SETTINGS -> txt("Settings","Ayarlar") }
            val icon = when(item) { Page.STORE -> R.drawable.ic_tab_store; Page.CAREER -> R.drawable.ic_tab_career; Page.ESPORTS -> R.drawable.ic_tab_esports; Page.FAVORITES -> R.drawable.ic_tab_favorites; Page.SETTINGS -> R.drawable.ic_tab_settings }
            Column(Modifier.fillMaxWidth().height(76.dp)
                .selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab, onClick = { if(!active) clickSound(); onSelect(item) })
                .semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(width = 50.dp, height = 32.dp).clip(CircleShape)
                    .background(if(active) Raised else Color.Transparent), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), null, Modifier.size(21.dp), tint = if(active) Accent else Muted)
                }
                Text(label, color = if(active) White else Muted, fontSize = 10.sp,
                    fontWeight = if(active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
    VerticalDivider(color = Raised.copy(alpha = .7f))
}

@Composable internal fun Header(title: String, subtitle: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            if(subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
        action?.let { TextButton(onClick = onAction) { Text(it, color = Accent, fontSize = 12.sp) } }
    }
}
@Composable internal fun SectionTitle(title: String, suffix: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(suffix, color = Muted, fontSize = 11.sp)
    }
}
@Composable internal fun Note(text: String) { Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 12.dp)) }
@Composable internal fun Empty(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Note(body)
        action?.let { Button(onClick = onAction, shape = CircleShape) { Text(it) } }
    }
}
@Composable internal fun ProgressLine(value: Float, color: Color = Green) {
    LinearProgressIndicator(progress = { value.coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = color, trackColor = Raised, drawStopIndicator = {})
}
@Composable internal fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) { Text(value, color = White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold); Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)) }
}
@Composable internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val clickSound = rememberInteractionSound()
    Column(Modifier.clickable { clickSound(); onClick() }.padding(horizontal = 10.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if(selected) White else Muted, fontSize = 12.sp,
            fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(if(selected) 21.dp else 1.dp).height(2.dp)
            .background(if(selected) Accent else Color.Transparent))
    }
}
@Composable internal fun Price(offer: StoreOffer) {
    if(offer.priceVp < 0) {
        Text(txt("Price unavailable","Fiyat bilgisi yok"), color = Muted, fontSize = 11.sp)
    } else Column {
        if(offer.priceSource == "bundle") Text(txt("Within this bundle", "Paket içinde"), color = Muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val original = offer.originalPrice?.takeIf { it > offer.priceVp && it > 0 }
        original?.let { Text("$it ${offer.currency}", color = Muted, fontSize = 11.sp, textDecoration = TextDecoration.LineThrough) }
        Text("${offer.priceVp} ${offer.currency}", color = if(original != null) Green else White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        original?.let { Text("−${(it-offer.priceVp)*100L/it}%", color = Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
@Composable internal fun offerDisplayName(offer: StoreOffer): String =
    if(offer.metadataPending || offer.name.startsWith("Item ·"))
        txt("Content pending · ${offer.id.take(8)}", "İçerik bekleniyor · ${offer.id.take(8)}") else offer.name
@Composable private fun FavoriteButton(saved: Boolean, onClick: () -> Unit) {
    val clickSound = rememberInteractionSound()
    val label = if(saved) txt("Remove favorite","Favoriden çıkar") else txt("Add favorite","Favoriye ekle")
    Box(Modifier.size(44.dp).clickable { clickSound(); onClick() }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(if(saved) R.drawable.ic_favorite_filled else R.drawable.ic_tab_favorites), null, Modifier.size(22.dp), tint = if(saved) Accent else White)
    }
}
@Composable private fun SkinCard(offer: StoreOffer, saved: Boolean, onWish: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier, available: Boolean = false, showPrice: Boolean = true, owned: Boolean = false) {
    Column(modifier.clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().height(if(offer.imageUrl.isNullOrBlank()) 72.dp else 130.dp)) {
            GameArtwork(offer.imageUrl, offer.name, Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 10.dp))
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { FavoriteButton(saved, onWish) }
            if(available) Text(txt("In store","Mağazada"), color = Green, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            if(owned) Text("✓ " + txt("Owned","Sende"), color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Column(Modifier.padding(horizontal = 2.dp).padding(top = 10.dp, bottom = 15.dp)) {
            Text(if(!offer.skinOffer()) txt("Accessory", "Aksesuar") else offer.weaponLabel(), color = Muted, fontSize = 10.sp)
            Text(offerDisplayName(offer), color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 7.dp))
            if(showPrice) Price(offer)
        }
    }
}
@Composable internal fun SkinRow(offer: StoreOffer, saved: Boolean, onWish: () -> Unit, onOpen: () -> Unit) {
    Column {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        GameArtwork(offer.imageUrl, offer.name, Modifier.width(110.dp).height(68.dp).padding(4.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(offerDisplayName(offer), color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            Price(offer)
        }
        FavoriteButton(saved, onWish)
    }
    HorizontalDivider(color = Raised.copy(alpha = .6f))
    }
}
@Composable private fun OfferGrid(offers: List<StoreOffer>, wished: List<String>, onWish: (String) -> Unit, onOpen: (StoreOffer) -> Unit) {
    val columns = when(LocalRaundWidthClass.current) {
        RaundWidthClass.COMPACT -> 2
        RaundWidthClass.MEDIUM -> 3
        RaundWidthClass.EXPANDED -> 4
    }
    offers.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { SkinCard(it, it.id in wished, { onWish(it.id) }, { onOpen(it) }, Modifier.weight(1f)) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
@Composable private fun StoreAccountHero(account: LiveStoreAccount) {
    val name = account.name.substringBefore('#')
    val tag = account.name.substringAfter('#', "")
    val art = account.playerCard
    Box(Modifier.fillMaxWidth().height(if(art?.wide != null) 116.dp else 86.dp).clip(RoundedCornerShape(18.dp))
        .background(Brush.horizontalGradient(listOf(Raised, Panel)))) {
        if(art?.wide != null) GameArtwork(art.wide, txt("Player card","Oyuncu kartı"), Modifier.fillMaxSize(), ContentScale.Crop)
        if(art?.wide != null) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            listOf(Color.Black.copy(alpha = .82f), Color.Black.copy(alpha = .23f)))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(55.dp),
                contentAlignment = Alignment.Center) {
                if(art?.small != null) GameArtwork(art.small, name, Modifier.fillMaxSize())
                else Text(name.take(2).uppercase(Locale.ROOT), color = White, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(name, color = if(art?.wide != null) Color.White else White, fontSize = 19.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(tag.takeIf { it.isNotBlank() }?.let { "#$it" }, account.region).joinToString(" · "),
                    color = if(art?.wide != null) Color.White.copy(alpha = .78f) else Muted, fontSize = 11.sp)
            }
        }
    }
    Text(when(art?.source) {
        "match" -> txt("Player card in your latest match", "Son maçındaki oyuncu kartı")
        "loadout" -> txt("Your current player card", "Güncel oyuncu kartın")
        else -> "Riot ID"
    },
        color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp, bottom = 5.dp))
}

@Composable private fun NightMarketEmpty(available: Boolean, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("☾", color = Accent, fontSize = 31.sp, modifier = Modifier.width(50.dp))
        Column(Modifier.weight(1f)) {
            Text(if(available) txt("Offers are loading", "Teklifler yükleniyor")
                else txt("No Night Market right now", "Şu anda Gece Pazarı yok"), color = White,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if(available) txt("Refresh to check again.", "Tekrar kontrol etmek için yenile.")
                else txt("When Riot opens one, your offers will appear here.", "Riot açtığında tekliflerin burada görünecek."),
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
        TextButton(onClick = onRefresh) { Text(txt("Check", "Kontrol"), fontSize = 11.sp) }
    }
    HorizontalDivider(color = Raised.copy(alpha = .6f))
}

@Composable private fun AccessoryRow(offer: StoreOffer, saved: Boolean, onWish: () -> Unit, onOpen: () -> Unit) {
    val kind = when(offer.collection) {
        "buddies" -> txt("Gun buddy", "Silah süsü")
        "playercards" -> txt("Player card", "Oyuncu kartı")
        "sprays" -> txt("Spray", "Sprey")
        "playertitles" -> txt("Title", "Unvan")
        else -> txt("Accessory", "Aksesuar")
    }
    var imageFailed by remember(offer.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
            if(offer.collection == "playertitles") {
                Text("❝", color = Muted, fontSize = 32.sp, fontWeight = FontWeight.Medium)
            } else if(offer.imageUrl.isNullOrBlank() || imageFailed) {
                Text(offer.name.take(1).uppercase(),
                    color = Accent, fontSize = 27.sp, fontWeight = FontWeight.Medium)
            } else {
                AsyncImage(offer.imageUrl, offer.name, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit,
                    onError = { imageFailed = true })
            }
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(kind, color = Muted, fontSize = 10.sp)
            Text(offer.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp))
            Price(offer)
        }
        FavoriteButton(saved, onWish)
    }
    HorizontalDivider(color = Raised.copy(alpha = .6f))
}
@Composable private fun StorePage(account: LiveStoreAccount?, loading: Boolean, error: String?, needsLogin: Boolean, onConnect: () -> Unit, onRefresh: () -> Unit, wished: List<String>, onWish: (String) -> Unit, onOpen: (StoreOffer) -> Unit, onBundle: (StoreBundle) -> Unit, onInventory: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item {
            Header(txt("Store","Mağaza"), if(account != null) txt("Your daily rotation","Sana özel günlük seçki") else "Raund", if(loading) null else txt("Refresh","Yenile"), onRefresh)
            if(loading) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Raised); Note(txt("Updating your account…", "Hesabın güncelleniyor…")) }
            error?.let { Note(it); TextButton(onClick = onConnect) { Text(if(needsLogin) txt("Renew session","Oturumu yenile") else txt("Retry","Tekrar dene")) } }
            if(account == null && !loading) Empty(txt("Your store, always close.","Mağazan her an yanında."), txt("Link your Riot account to see your offers.","Tekliflerini görmek için Riot hesabını bağla."), txt("Continue with Riot","Riot ile devam et"), onConnect)
            if(account != null) {
                StoreAccountHero(account)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    account.snapshot.balances.forEach { (currency, amount) -> Text("$amount $currency", color = White, fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 7.dp)) }
                }
                Row(Modifier.fillMaxWidth().clickable(onClick = onInventory).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(txt("My inventory","Envanterim"), color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(txt("Owned skins, organized by weapon","Sahip olduğun skinler, silahlara göre"), color = Muted, fontSize = 11.sp)
                    }
                    Text("→", color = Accent, fontSize = 19.sp)
                }
                HorizontalDivider(color = Raised.copy(alpha = .6f))
                if(account.snapshot.refreshEpochSeconds <= System.currentTimeMillis()/1000) {
                    Note(txt("This rotation expired. Refresh to see the current store.","Bu mağazanın süresi doldu. Güncel teklifler için yenile."))
                }
                SectionTitle(txt("Daily picks","Günün seçkisi"), countdown(account.snapshot.refreshEpochSeconds))
                OfferGrid(account.snapshot.offers, wished, onWish, onOpen)
                if(account.snapshot.bundles.isNotEmpty()) SectionTitle(txt("Featured bundles","Öne çıkan paketler"))
            }
        }
        items(account?.snapshot?.bundles.orEmpty(), key = { it.id }) { bundle ->
            Column(Modifier.padding(bottom = 17.dp).clickable { onBundle(bundle) }) {
                AsyncImage(bundle.image, bundle.name, Modifier.fillMaxWidth().height(178.dp).clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Crop)
                Row(Modifier.padding(horizontal = 2.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(bundle.name, color = White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold); Text(countdown(bundle.expiresAt), color = Muted, fontSize = 11.sp) }
                    Text(bundle.price?.let { "$it VP" } ?: "↗", color = White, fontSize = 13.sp)
                }
            }
        }
        item {
            if(account != null) {
                SectionTitle(txt("Night Market","Gece Pazarı"))
                if(account.snapshot.nightOffers.isEmpty()) NightMarketEmpty(account.snapshot.nightMarketAvailable || loading, onRefresh)
                else OfferGrid(account.snapshot.nightOffers, wished, onWish, onOpen)
                SectionTitle(txt("Accessories","Aksesuarlar"), txt("${account.snapshot.accessories.size} offers · Kingdom Credits", "${account.snapshot.accessories.size} teklif · Kingdom Kredisi"))
                account.snapshot.accessories.forEach { AccessoryRow(it, it.id in wished, { onWish(it.id) }, { onOpen(it) }) }
                if(account.snapshot.accessories.isEmpty()) Note(txt("No accessory offers were returned.", "Aksesuar teklifi dönmedi."))
                if(account.warnings.isNotEmpty()) Note(txt("Some sections could not be refreshed. Tap Refresh to retry.", "Bazı bölümler yenilenemedi. Yenile düğmesiyle tekrar deneyebilirsin."))
            }
        }
    }
}
@Composable private fun countdown(epoch: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()/1000) }
    LaunchedEffect(epoch) { while(true) { delay(1000); now = System.currentTimeMillis()/1000 } }
    val left = (epoch-now).coerceAtLeast(0)
    return if(left > 86400) "${left/86400}d ${left/3600%24}h" else String.format(Locale.ROOT, "%02d:%02d:%02d", left/3600, left/60%60, left%60)
}
internal fun timeLabel(epoch: Long) = runCatching { DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())) }.getOrDefault("")

internal fun normalized(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT).replace('ı','i'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
@Composable private fun FavoritesPage(catalog: List<StoreOffer>, wished: List<String>, loading: Boolean, failed: Boolean, notifications: Boolean, snapshot: StoreSnapshot?, ownedIds: Set<String>, onWish: (String) -> Unit, onOpen: (StoreOffer) -> Unit, onRetry: () -> Unit, onAlerts: (Boolean) -> Unit, onRadar: () -> Unit) {
    val context = LocalContext.current
    val turkish = LocalTurkish.current
    var localizedNames by remember(turkish) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(turkish) {
        if(turkish) localizedNames = withContext(Dispatchers.IO) { runCatching { ContentCatalog(context).localizedSkinNames("tr-TR") }.getOrDefault(emptyMap()) }
    }
    var search by rememberSaveable { mutableStateOf("") }
    var browse by rememberSaveable { mutableStateOf(false) }
    var weapon by rememberSaveable { mutableStateOf<String?>(null) }
    val all = remember(catalog, snapshot, wished.toList()) {
        val resolved = (snapshot?.offers.orEmpty() + snapshot?.nightOffers.orEmpty() + snapshot?.accessories.orEmpty() +
            snapshot?.bundles.orEmpty().flatMap { it.items } + catalog).filter { it.skinOffer() || it.id in wished }.distinctBy { it.id }
        resolved + wished.filter { id -> resolved.none { it.id == id } }.map(PendingCatalog::skin)
    }
    val available = snapshot?.let { FavoriteAlerts.matches(it, wished.toSet()).map { offer -> offer.id }.toSet() }.orEmpty()
    val rows = remember(all, wished.toList(), search, browse, weapon, localizedNames) {
        all.filter { (if(browse) it.skinOffer() else it.id in wished) && (weapon == null || it.weaponLabel() == weapon) &&
            (normalized(it.name).contains(normalized(search)) || normalized(localizedNames[it.id].orEmpty()).contains(normalized(search))) }
            .map { offer -> localizedNames[offer.id]?.let { offer.copy(name = it) } ?: offer }
    }
    val gridColumns = when(LocalRaundWidthClass.current) {
        RaundWidthClass.COMPACT -> 2
        RaundWidthClass.MEDIUM -> 3
        RaundWidthClass.EXPANDED -> 4
    }
    LazyVerticalGrid(columns = GridCells.Fixed(gridColumns), modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column {
                Header(txt("Favorites","Favoriler"), txt("The skins you want to see in your store.","Mağazanda görmek istediğin skinler."), txt("Radar","Radar"), onRadar)
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Chip(txt("My favorites","Favorilerim") + " · ${wished.size}", !browse) { browse = false; search = ""; weapon = null }
                    Chip(txt("Add skins","Skin ekle"), browse) { browse = true; search = ""; weapon = null }
                }
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text(if(browse) txt("Search all skins", "Tüm skinlerde ara") else txt("Search favorites", "Favorilerinde ara"), color = Muted, fontSize = 13.sp) },
                    trailingIcon = { if(search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("×") } })
                LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip(txt("All","Tümü"), weapon == null) { weapon = null } }
                    val types = all.filter { it.skinOffer() }.map { it.weaponLabel() }.distinct().sorted()
                    items(types, key = { it }) { type -> Chip(type, weapon == type) { weapon = type } }
                }
                Text("${rows.size} " + txt("skins", "skin") + " · " + txt("swipe for weapons","silahlar için sağa kaydır"), color = Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 7.dp))
                if(!browse) {
                    HorizontalDivider(color = Raised)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(txt("Notify when available", "Mağazaya gelince bildir"), color = White, fontSize = 13.sp)
                            Text(txt("Checked around 03:00 and 09:00", "Yaklaşık 03.00 ve 09.00'da kontrol edilir"), color = Muted, fontSize = 11.sp)
                        }
                        Switch(notifications, onCheckedChange = onAlerts)
                    }
                    HorizontalDivider(color = Raised)
                }
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                if(failed) TextButton(onClick = onRetry) { Text(txt("Could not load skins. Retry", "Skinler yüklenemedi. Tekrar dene")) }
                if(rows.isEmpty() && !loading) {
                    val noFavorites = !browse && wished.isEmpty()
                    Empty(if(noFavorites) txt("Make it yours", "İlk favorini seç") else txt("No results", "Sonuç bulunamadı"),
                        if(noFavorites) txt("Save skins with the heart button to follow them here.", "Beğendiğin skinin kalbine dokun; hepsini burada takip et.") else txt("Try another name or clear the weapon filter.", "Başka bir isim dene veya silah filtresini temizle."),
                        if(noFavorites) txt("Explore skins", "Skinleri keşfet") else txt("Clear filters", "Filtreleri temizle"),
                        { if(noFavorites) browse = true; search = ""; weapon = null })
                }
            }
        }
        items(rows.sortedByDescending { it.id in available }, key = { it.id }) { offer ->
            SkinCard(offer, offer.id in wished, { onWish(offer.id) }, { onOpen(offer) }, available = offer.id in available,
                showPrice = offer.priceVp >= 0, owned = offer.id in ownedIds)
        }
    }
}

@Composable internal fun CareerPage(career: CareerSummary?, progress: ProgressSummary?, loading: Boolean, agents: Map<String, JSONObject>, maps: Map<String, JSONObject>, seasons: List<SeasonWindow>, onMatch: (MatchSummary) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    var rankIcon by remember(career?.rankTier) { mutableStateOf<String?>(null) }
    var rankIconLoaded by remember(rankIcon) { mutableStateOf(false) }
    var seasonOpen by rememberSaveable { mutableStateOf(false) }
    var analysisOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(career?.rankTier) {
        if(career == null) return@LaunchedEffect
        rankIcon = withContext(Dispatchers.IO) {
            runCatching { ContentCatalog(context).data("competitivetiers").objects().lastOrNull()
                ?.optJSONArray("tiers").objects().firstOrNull { it.optInt("tier") == career.rankTier }?.textOrNull("largeIcon") }.getOrNull()
        }
    }
    var mode by rememberSaveable { mutableStateOf("Competitive") }
    var mapFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var resultFilter by rememberSaveable { mutableStateOf("all") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var selectedSeason by rememberSaveable { mutableStateOf("") }
    val availableSeasons = remember(seasons) { seasons.sortedByDescending { it.startAt }.take(10) }
    val currentSeason = availableSeasons.firstOrNull { it.contains(System.currentTimeMillis()) }
    val activeSeason = when(selectedSeason) {
        "all" -> null
        "" -> currentSeason
        else -> availableSeasons.firstOrNull { it.id == selectedSeason } ?: currentSeason
    }
    val seasonMatches = career?.matches.orEmpty().filter { activeSeason == null || activeSeason.contains(it.startedAt) }
    var visibleMatchCount by remember(mode, mapFilter, resultFilter, selectedSeason) { mutableIntStateOf(5) }
    val matches = seasonMatches.filter {
        (mode == "All" || it.mode == mode) && (mapFilter == null || it.map == mapFilter) &&
        (resultFilter == "all" || (resultFilter == "win" && it.won == true) || (resultFilter == "loss" && it.won == false))
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item {
            Header(txt("Career","Kariyer"), txt("Match history and season progress.","Maç geçmişin ve sezon ilerlemen."), txt("Refresh","Yenile"), onRefresh)
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            if(career == null && progress == null && !loading) Empty(txt("Career & season","Kariyer ve sezon"), txt("Connect your account to see matches, missions and progression.", "Maçlarını, görevlerini ve ilerlemeni görmek için hesabını bağla."), txt("Connect / refresh","Bağlan / yenile"), onRefresh)
            if(career == null && progress != null && !loading) Note(txt("Match history could not be loaded. Try refreshing.", "Maç geçmişi yüklenemedi. Yenilemeyi deneyebilirsin."))
            if(career != null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(70.dp).padding(end = 14.dp), contentAlignment = Alignment.Center) {
                        if(!rankIconLoaded) Icon(painterResource(R.drawable.ic_tab_career), null, Modifier.size(38.dp), tint = Muted)
                        if(rankIcon != null) AsyncImage(rankIcon, career.rank, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onSuccess = { rankIconLoaded = true }, onError = { rankIconLoaded = false })
                    }
                    Column(Modifier.weight(1f)) {
                        Text(txt("Competitive","Rekabetçi"), color = Muted, fontSize = 12.sp)
                        Text(when { career.rankTier < 0 -> txt("Unavailable", "Yüklenemedi"); career.rankTier < 3 -> txt("Unranked", "Derecesiz"); else -> career.rank }, color = White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                        if(career.rankTier >= 3) Text(if(career.rankedRating >= 0) "${career.rankedRating} RR" else "— RR", color = Green, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(progress?.accountLevel?.toString() ?: "—", color = White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                        Text(txt("Account level", "Hesap seviyesi"), color = Muted, fontSize = 10.sp)
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 5.dp, bottom = 9.dp)) {
                    item { Chip(txt("Current act", "Güncel act"), selectedSeason == "") { selectedSeason = "" } }
                    item { Chip(txt("All loaded", "Yüklü tümü"), selectedSeason == "all") { selectedSeason = "all" } }
                    items(availableSeasons, key = { it.id }) { season ->
                        Chip(season.label, selectedSeason == season.id) { selectedSeason = season.id }
                    }
                }
                if(activeSeason != null) Text(txt("Match history filtered to ${activeSeason.label}.", "Maç geçmişi ${activeSeason.label} ile filtrelendi."),
                    color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp))
                val competitiveMatches = seasonMatches.filter { it.mode.equals("Competitive", true) }
                val valid = competitiveMatches.filter { it.detailsJson != null }
                val wins = valid.count { it.won == true }; val decided = valid.count { it.won != null }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp)) {
                    Stat(if(decided > 0) "${wins*100/decided}%" else "—", txt("Win rate","Kazanma"), Modifier.weight(1f))
                    Stat(if(valid.isNotEmpty()) String.format(Locale.ROOT, "%.2f", valid.sumOf { it.kills }.toDouble()/valid.sumOf { it.deaths }.coerceAtLeast(1)) else "—", "K/D", Modifier.weight(1f))
                    Stat(valid.size.toString(), txt("Recent matches","Son maçlar"), Modifier.weight(1f))
                }
                CareerTrendSummary(competitiveMatches)
                Row(Modifier.fillMaxWidth().clickable { analysisOpen = !analysisOpen }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(txt("Performance & development", "Performans ve gelişim"), color = White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(if(analysisOpen) "−" else "+", color = Muted, fontSize = 20.sp)
                }
                if(analysisOpen) {
                    CareerPerformanceCenter(career.copy(matches = seasonMatches), agents, maps, onMatch)
                    CareerPeopleCenter(competitiveMatches)
                }
                HorizontalDivider(color = Raised)
                Row(Modifier.fillMaxWidth().clickable { seasonOpen = !seasonOpen }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(txt("Season & missions", "Sezon ve görevler"), color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if(seasonOpen) "−" else "+", color = Muted, fontSize = 20.sp)
                }
                if(seasonOpen) SeasonProgress(progress, loading, onRefresh)
                val modeOptions = listOf("Competitive", "Unrated", "Swiftplay", "Spike Rush", "Deathmatch", "Team Deathmatch", "Escalation", "Replication", "Snowball Fight", "Premier", "Custom")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item { Chip(txt("All","Tümü"), mode == "All") { mode = "All" } }
                    items((modeOptions + seasonMatches.map { it.mode }).distinct()) { value -> Chip(value, mode == value) { mode = value } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(txt("Match history","Maç geçmişi"), color = White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { filters = !filters }) { Text(txt("Filters","Filtreler") + if(mapFilter != null || resultFilter != "all") " •" else "") }
                }
                if(filters) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        item { Chip(txt("All maps","Tüm haritalar"), mapFilter == null) { mapFilter = null } }
                        items(seasonMatches.map { it.map }.distinct().sorted()) { map -> Chip(map, mapFilter == map) { mapFilter = map } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        Chip(txt("All results","Tüm sonuçlar"), resultFilter == "all") { resultFilter = "all" }
                        Chip(txt("Wins","Galibiyet"), resultFilter == "win") { resultFilter = "win" }
                        Chip(txt("Losses","Mağlubiyet"), resultFilter == "loss") { resultFilter = "loss" }
                    }
                }
                if(matches.isEmpty()) Note(txt("No matches in this filter.", "Bu filtrede maç yok."))
            }
            if(career == null && progress != null) SeasonProgress(progress, loading, onRefresh)
        }
        items(matches.take(visibleMatchCount), key = { it.id }) { value ->
            MatchHistoryCard(value, agents[value.agentId]?.textOrNull("displayIcon"),
                value.mapImage ?: maps[value.map]?.textOrNull("splash") ?: maps[value.map]?.textOrNull("listViewIcon")) { onMatch(value) }
        }
        item {
            if(matches.size > visibleMatchCount) TextButton(onClick = { visibleMatchCount += 5 }, modifier = Modifier.fillMaxWidth()) { Text(txt("Show 5 more matches", "5 maç daha göster")) }
            else if(career?.hasMore == true) OutlinedButton(onClick = onMore, enabled = !loading, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text(txt("Load older matches","Daha eski maçları yükle")) }
            if(career != null && seasonMatches.isNotEmpty()) {
                SectionTitle(txt("By agent","Ajanlara göre"))
                seasonMatches.filter { it.mode.equals("Competitive", true) && it.detailsJson != null && it.agentId.isNotBlank() }.groupBy { it.agentId }.entries.sortedByDescending { it.value.size }.take(5).forEach { (id, games) ->
                    PerformanceRow(agents[id]?.textOrNull("displayName") ?: txt("Agent","Ajan"), agents[id]?.textOrNull("displayIcon"), games)
                }
                SectionTitle(txt("By map","Haritalara göre"))
                seasonMatches.filter { it.mode.equals("Competitive", true) && it.detailsJson != null }.groupBy { it.map }.entries.sortedByDescending { it.value.size }.forEach { (name, games) ->
                    PerformanceRow(name, maps[name]?.textOrNull("listViewIcon"), games)
                }
                Note(txt("Competitive matches loaded on this device only.", "Yalnızca bu cihazda yüklü rekabetçi maçlara dayanır."))
            }
        }
    }
}

@Composable private fun CareerPerformanceCenter(career: CareerSummary, agents: Map<String, JSONObject>, maps: Map<String, JSONObject>, onMatch: (MatchSummary) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE) }
    val rankedMatches = remember(career.matches) { career.matches.filter { it.mode.equals("Competitive", true) } }
    val dashboard = remember(rankedMatches, career.rankedRating) { CareerDashboardAnalysis.calculate(rankedMatches) }
    var target by remember { mutableIntStateOf(prefs.getInt("career_rr_goal", 100).coerceIn(5, 100)) }
    val bestMap = dashboard.maps.firstOrNull { it.matches >= 8 }
    val weakMap = dashboard.maps.lastOrNull { it.matches >= 8 }?.takeIf { it.key != bestMap?.key }
    val bestAgent = dashboard.agents.firstOrNull { it.matches >= 8 }
    val bestTime = dashboard.dayParts.firstOrNull { it.matches >= 8 }
    SectionTitle(txt("Competitive performance", "Rekabetçi performans"), "${rankedMatches.size} ${txt("matches", "maç")}")
    if(rankedMatches.isEmpty()) Note(txt("Load competitive matches to build ranked insights.",
        "Dereceli analizini oluşturmak için rekabetçi maçlarını yükle."))
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Stat((if(dashboard.rrNet > 0) "+" else "") + dashboard.rrNet, txt("Loaded RR", "Yüklenen RR"), Modifier.weight(1f))
        Stat(if(dashboard.streak == 0) "—" else dashboard.streak.toString(),
            if(dashboard.streakWon == true) txt("Win streak", "Galibiyet serisi") else txt("Loss streak", "Mağlubiyet serisi"), Modifier.weight(1f))
        Stat(if(dashboard.lastSeven.matches > 0) "${dashboard.lastSeven.winRate}%" else "—", txt("Last 7 days", "Son 7 gün"), Modifier.weight(1f))
    }
    if(dashboard.rrCurve.size > 1) {
        val chartAccent = Accent
        val chartPoint = White
        Text(txt("RR movement across loaded competitive matches", "Yüklenen rekabetçi maçlarda RR hareketi"), color = Muted, fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().height(82.dp).padding(vertical = 10.dp)) {
            val values = dashboard.rrCurve
            val min = values.minOrNull() ?: 0; val max = values.maxOrNull() ?: 1
            val range = (max - min).coerceAtLeast(1)
            val points = values.mapIndexed { index, value ->
                Offset(size.width * index / (values.size - 1).coerceAtLeast(1), size.height - size.height * (value - min) / range)
            }
            points.zipWithNext().forEach { (a, b) -> drawLine(chartAccent, a, b, 3.dp.toPx()) }
            points.forEach { drawCircle(chartPoint, 3.dp.toPx(), it) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("Tier RR goal", "Kademe RR hedefi"), color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { target = (target - 5).coerceAtLeast(5); prefs.edit().putInt("career_rr_goal", target).apply() }) { Text("−") }
            Text("$target RR", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { target = (target + 5).coerceAtMost(100); prefs.edit().putInt("career_rr_goal", target).apply() }) { Text("+") }
        }
        ProgressLine(if(career.rankedRating >= 0) career.rankedRating.toFloat() / target else 0f, Accent)
        Text(if(career.rankedRating < 0) txt("Current RR is unavailable", "Mevcut RR yüklenemedi")
            else if(career.rankedRating >= target) txt("Goal reached", "Hedef tamamlandı") else
            txt("${(target-career.rankedRating).coerceAtLeast(0)} RR to goal", "Hedefe ${(target-career.rankedRating).coerceAtLeast(0)} RR kaldı"),
            color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
    listOfNotNull(
        bestMap?.let { txt("Strongest loaded map: ${it.key} · ${it.winRate}% over ${it.matches} matches.",
            "Yüklenen maçlarda öne çıkan harita: ${it.key} · ${it.matches} maçta %${it.winRate}.") },
        weakMap?.let { txt("Improvement map: ${it.key} · ${it.winRate}% win rate.",
            "Gelişim haritası: ${it.key} · %${it.winRate} kazanma.") },
        bestAgent?.let { row -> agents[row.key]?.textOrNull("displayName")?.let { name ->
            txt("Best agent sample: $name · ${row.winRate}% win rate.", "En iyi ajan örneklemi: $name · %${row.winRate} kazanma.")
        } },
        bestTime?.let { row ->
            val label = when(row.key) { "morning" -> txt("morning", "sabah"); "afternoon" -> txt("afternoon", "öğleden sonra"); "evening" -> txt("evening", "akşam"); else -> txt("late night", "gece") }
            txt("Observed time window: $label · ${row.matches} matches · ${row.winRate}% (not a prediction).",
                "Gözlenen zaman aralığı: $label · ${row.matches} maç · %${row.winRate} (tahmin değildir).")
        }
    ).forEach { insight -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text("•", color = Accent, modifier = Modifier.width(18.dp)); Text(insight, color = White, fontSize = 12.sp, lineHeight = 18.sp)
    } }
    Text(txt("Recommendations need at least 8 matches per group. Smaller samples are descriptive, not conclusions.",
        "Öneriler için grup başına en az 8 maç gerekir. Küçük örneklemler yalnızca gözlemdir, kesin sonuç değildir."),
        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
    val coachGoals = remember(rankedMatches) { CompetitiveCoachAnalysis.goals(rankedMatches) }
    if(coachGoals.isNotEmpty()) DevelopmentCard(rankedMatches, coachGoals, onMatch)
    if(coachGoals.isNotEmpty()) {
        Text(txt("NEXT 5 MATCHES", "SONRAKİ 5 MAÇ"), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp, modifier = Modifier.padding(top = 18.dp, bottom = 5.dp))
        coachGoals.forEachIndexed { index, goal ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("0${index + 1}", color = Muted, fontSize = 10.sp, modifier = Modifier.width(29.dp))
                    Text(if(LocalTurkish.current) goal.titleTr else goal.titleEn, color = White, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${goal.progress.coerceIn(0, 100)}%", color = Muted, fontSize = 10.sp)
                }
                ProgressLine(goal.progress / 100f, Accent)
                Text(if(LocalTurkish.current) goal.detailTr else goal.detailEn, color = Muted, fontSize = 10.sp,
                    lineHeight = 15.sp, modifier = Modifier.padding(start = 29.dp, top = 5.dp))
            }
        }
    }
    Text(txt("Insights only use matches currently loaded on this device.", "İçgörüler yalnızca bu cihazda yüklü maçlardan hesaplanır."),
        color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
}

@Composable private fun CareerPeopleCenter(matches: List<MatchSummary>) {
    val people = remember(matches) { CareerPeopleAnalysis.calculate(matches) }
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var compare by remember { mutableStateOf<List<KnownPlayerStat>>(emptyList()) }
    HorizontalDivider(color = Raised.copy(alpha = .65f))
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(txt("Players & duo analysis", "Oyuncu ve duo analizi"), color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(txt("Search ${people.players.size} players from loaded ranked matches", "Yüklü dereceli maçlardaki ${people.players.size} oyuncuda ara"),
                color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text(if(open) "−" else "+", color = Muted, fontSize = 20.sp)
    }
    if(!open) return
    OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(txt("Riot ID search", "Riot ID ara")) },
        placeholder = { Text(txt("Name or #tag from your matches", "Maçlarındaki isim veya #etiket")) },
        shape = RoundedCornerShape(17.dp))
    if(compare.isNotEmpty()) {
        Text(txt("COMPARE", "KARŞILAŞTIR"), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp, modifier = Modifier.padding(top = 18.dp, bottom = 7.dp))
        Row(Modifier.fillMaxWidth()) {
            compare.take(2).forEach { player ->
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(player.riotId, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${player.winRate}% · ${String.format(Locale.ROOT, "%.2f", player.kd)} K/D · ${player.games} ${txt("matches", "maç")}",
                        color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    val visible = if(query.isBlank()) people.duos else people.players.filter { it.riotId.contains(query.trim(), true) }
    SectionTitle(if(query.isBlank()) txt("Best duo samples", "Duo örnekleri") else txt("Player results", "Oyuncu sonuçları"), visible.size.toString())
    if(visible.isEmpty()) Note(if(matches.isEmpty()) txt("Load Competitive match details first.", "Önce Competitive maç ayrıntılarını yükle.")
        else txt("No player found in loaded matches.", "Yüklü maçlarda bu oyuncu bulunamadı."))
    visible.take(12).forEach { player ->
        val selected = compare.any { it.puuid == player.puuid }
        Row(Modifier.fillMaxWidth().clickable {
            compare = if(selected) compare.filterNot { it.puuid == player.puuid }
            else (compare + player).takeLast(2)
        }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(player.riotId, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("${player.games} ${txt("shared matches", "ortak maç")} · ${player.kills}/${player.deaths}/${player.assists}",
                    color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Text("${player.winRate}%", color = if(player.winRate >= people.ownWinRate) Green else White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
            Text(if(selected) "  ✓" else "  +", color = Accent, fontSize = 15.sp)
        }
        HorizontalDivider(color = Raised.copy(alpha = .45f))
    }
    Note(txt("Search and comparison use Competitive players already present in your loaded match history; no profile data is invented.",
        "Arama ve karşılaştırma yalnızca yüklü Competitive maç geçmişindeki gerçek oyuncuları kullanır; profil verisi uydurulmaz."))
}

@Composable private fun CareerTrendSummary(matches: List<MatchSummary>) {
    val trend = remember(matches) { CareerInsights.trend(matches) } ?: return
    SectionTitle(txt("Recent form", "Son form"), txt("Last ${trend.recent.matches} vs previous ${trend.previous.matches}", "Son ${trend.recent.matches} · önceki ${trend.previous.matches}"))
    fun signed(value: Int, suffix: String = "") = (if(value > 0) "+" else "") + value + suffix
    fun signed(value: Double) = (if(value > 0) "+" else "") + String.format(Locale.ROOT, "%.2f", value)
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        TrendMetric("${trend.recent.winRate}%", signed(trend.recent.winRate - trend.previous.winRate, " pt"), txt("Win rate", "Kazanma"), Modifier.weight(1f))
        TrendMetric(String.format(Locale.ROOT, "%.2f", trend.recent.kd), signed(trend.recent.kd - trend.previous.kd), "K/D", Modifier.weight(1f))
        TrendMetric(trend.recent.adr.toString(), signed(trend.recent.adr - trend.previous.adr), "ADR", Modifier.weight(1f))
    }
    Text(txt("Compared using ${if(trend.mode == "Competitive") "competitive" else "detailed"} matches with the same window size.",
        "Aynı büyüklükteki ${if(trend.mode == "Competitive") "rekabetçi" else "ayrıntılı"} maç grupları karşılaştırıldı."), color = Muted, fontSize = 10.sp)
}

@Composable private fun TrendMetric(value: String, delta: String, label: String, modifier: Modifier = Modifier) {
    val improving = !delta.startsWith("-")
    Column(modifier) {
        Text(value, color = White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(delta, color = if(improving) Green else MaterialTheme.colorScheme.error, fontSize = 10.sp)
        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable private fun PerformanceRow(name: String, image: String?, games: List<MatchSummary>) {
    val decided = games.count { it.won != null }
    val wins = games.count { it.won == true }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(image, name, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Panel), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, color = White, fontSize = 14.sp)
            Text("${games.size} " + txt("matches","maç"), color = Muted, fontSize = 10.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if(decided > 0) "${wins*100/decided}%" else "—", color = Green, fontSize = 15.sp)
            Text(String.format(Locale.ROOT,"%.2f K/D",games.sumOf { it.kills }.toDouble()/games.sumOf { it.deaths }.coerceAtLeast(1)), color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable private fun SeasonProgress(progress: ProgressSummary?, loading: Boolean, onRefresh: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if(progress == null) {
        if(!loading) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("Season progress is not loaded.", "Sezon ilerlemesi yüklenmedi."), color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text(txt("Load", "Yükle")) }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp, top = 7.dp).clip(RoundedCornerShape(18.dp)).background(Panel).padding(16.dp)) {
        Text(progress.battlePassName ?: txt("Battle Pass","Savaş Bileti"), color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(Raised), contentAlignment = Alignment.Center) {
                Text(progress.battlePassLevel?.toString() ?: "—", color = Accent, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(txt("Current tier","Mevcut seviye"), color = Muted, fontSize = 11.sp)
                val next = progress.rewards.firstOrNull { !it.unlocked }
                Text(next?.let { txt("Next: ","Sırada: ") + it.name } ?: txt("All listed rewards reached","Listelenen ödüllere ulaştın"),
                    color = White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(progress.accountLevel.toString(), color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(txt("Account","Hesap"), color = Muted, fontSize = 10.sp)
            }
        }
        if(progress.battlePassLevel != null && progress.battlePassXpRequired > 0) {
            Spacer(Modifier.height(16.dp))
            ProgressLine(progress.battlePassXp.toFloat()/progress.battlePassXpRequired, Accent)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("${progress.battlePassXp} XP", color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("${(progress.battlePassXpRequired-progress.battlePassXp).coerceAtLeast(0)} " + txt("XP left","XP kaldı"), color = Muted, fontSize = 11.sp)
            }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(if(expanded) txt("Hide season details", "Sezon detaylarını gizle") else txt("Missions & rewards", "Görevler ve ödüller"))
        }
    }
    if(expanded) {
        SectionTitle(progress.battlePassName ?: txt("Season progress","Sezon ilerlemesi"))
        Text("${progress.accountXp} / 5000 AP", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        ProgressLine(progress.accountXp/5000f)
        if(progress.missions.isNotEmpty()) SectionTitle(txt("Missions","Görevler"))
        progress.missions.forEach { mission ->
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 3.dp)) {
                Text(mission.name, color = White, fontSize = 14.sp)
                Note("${mission.progress} / ${mission.target} · ${mission.xp} XP")
                ProgressLine(if(mission.complete) 1f else mission.progress.toFloat()/mission.target.coerceAtLeast(1))
                HorizontalDivider(Modifier.padding(top = 12.dp), color = Raised.copy(alpha = .6f))
            }
        }
        if(progress.rewards.isNotEmpty()) {
            SectionTitle(txt("Battle Pass rewards","Savaş Bileti ödülleri"), "${progress.rewards.size}")
            Note(txt("Checks show levels reached, not premium reward ownership.","İşaretler ulaşılan seviyeyi gösterir; premium ödül sahipliğini değil."))
        }
        progress.rewards.forEach { reward ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(reward.level.toString().padStart(2,'0'), color = if(reward.unlocked) Green else Muted, fontSize = 14.sp, modifier = Modifier.width(35.dp))
                AsyncImage(reward.image, reward.name, Modifier.width(80.dp).height(48.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(6.dp), contentScale = ContentScale.Fit)
                Text(reward.name, color = White, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                if(reward.unlocked) Text("✓", color = Green)
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable private fun SettingsPage(account: LiveStoreAccount?, themeMode: String, onTheme: (String) -> Unit, language: String, alerts: Boolean, onLanguage: (String) -> Unit, onAlerts: (Boolean) -> Unit,
                                    esportsMode: String, onEsportsMode: (String) -> Unit, esportsTeams: Set<String>, matchAlerts: Boolean, onMatchAlerts: (Boolean) -> Unit,
                                    onLogin: () -> Unit, onShowOnboarding: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    // These disclosures intentionally reset when the Settings destination is left.  Keeping
    // them in the destination saveable state makes a return from another tab reopen an
    // arbitrary section, which is visually surprising and hides the rest of the settings.
    var deliveryOpen by remember { mutableStateOf(false) }
    var trustOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var alertsOpen by remember { mutableStateOf(false) }
    val soundPrefs = remember { context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE) }
    var sounds by remember { mutableStateOf(soundPrefs.getBoolean("interaction_sounds", true)) }
    var nowBar by remember { mutableStateOf(soundPrefs.getBoolean("now_bar", true)) }
    var storeNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_store", true)) }
    var reminderNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_esports_reminders", true)) }
    var resultNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_esports_results", true)) }
    var mapNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_esports_maps", true)) }
    var liveNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_esports_live", true)) }
    var batterySaver by remember { mutableStateOf(soundPrefs.getBoolean("battery_saver", true)) }
    var notificationProfile by remember { mutableStateOf(soundPrefs.getString("esports_notification_profile", "custom") ?: "custom") }
    var autoStore by remember { mutableStateOf(soundPrefs.getBoolean("auto_store_refresh", true)) }
    var spoiler by remember { mutableStateOf(SpoilerPolicy.enabled(context)) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) permissionRevision++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    val canPromote = remember(permissionRevision) { Build.VERSION.SDK_INT >= 36 && context.getSystemService(android.app.NotificationManager::class.java).canPostPromotedNotifications() }
    val liveCapability = LiveUpdateCapability.state(Build.VERSION.SDK_INT, nowBar, ScoreNotifications.permitted(context), canPromote)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Header(txt("Settings","Ayarlar"), "Raund · $appVersion")
        Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            account?.playerCard?.small?.let { art ->
                AsyncImage(art, txt("Player card", "Oyuncu kartı"), Modifier.padding(end = 14.dp).size(58.dp), contentScale = ContentScale.Fit)
            }
            Column(Modifier.weight(1f)) {
                Text(account?.name ?: txt("Riot account","Riot hesabı"), color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if(account != null) txt("Connected on this device", "Bu cihazda bağlı") else txt("Not connected", "Bağlı değil"),
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            TextButton(onClick = onLogin) { Text(if(account == null) txt("Connect","Bağlan") else txt("Renew","Yenile")) }
        }
        HorizontalDivider(color = Raised.copy(alpha = .7f))
        SettingsDisclosure(txt("Appearance & language", "Görünüm ve dil"),
            (if(themeMode == "light") txt("Light", "Açık") else if(themeMode == "dark") txt("Dark", "Koyu") else txt("System", "Sistem")) + " · " + if(language == "TR") "Türkçe" else "English", appearanceOpen) { appearanceOpen = !appearanceOpen }
        if(appearanceOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(txt("System","Sistem"), themeMode == "system") { onTheme("system") }
            Chip(txt("Light","Açık"), themeMode == "light") { onTheme("light") }
            Chip(txt("Dark","Koyu"), themeMode == "dark") { onTheme("dark") }
        }
        SectionTitle(txt("Language","Dil"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("Türkçe", language == "TR") { onLanguage("TR") }; Chip("English", language == "EN") { onLanguage("EN") } }
        TextButton(onClick = onShowOnboarding, modifier = Modifier.padding(top = 6.dp)) { Text(txt("View welcome guide", "Karşılama rehberini göster")) }
        }
        HorizontalDivider(color = Raised.copy(alpha = .55f))
        NotificationPreferenceRow(txt("Hide match scores", "Maç skorlarını gizle"),
            txt("Shared by Esports, widgets and notifications · reveal per match", "Espor, widget ve bildirimlerde ortak · maç içinde açabilirsin"), spoiler) {
            spoiler = it; SpoilerPolicy.change(context, it)
        }
        HorizontalDivider(color = Raised.copy(alpha = .55f))
        Row(Modifier.fillMaxWidth().heightIn(min = 70.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(txt("Interaction sounds", "İşlem sesleri"), color = White, fontSize = 14.sp)
                Text(txt("Tabs, filters and favorites · follows system sound", "Sekme, filtre ve favoriler · sistem sesine uyar"), color = Muted, fontSize = 11.sp)
            }
            Switch(sounds, onCheckedChange = { sounds = it; soundPrefs.edit().putBoolean("interaction_sounds", it).apply() })
        }
        HorizontalDivider(color = Raised.copy(alpha = .55f))
        NotificationPreferenceRow(txt("Automatic store check", "Otomatik mağaza kontrolü"),
            txt("Updates your store and widgets in the background · no extra alerts", "Mağaza ve widget’lar arka planda güncellenir · ek bildirim göndermez"), autoStore) {
            autoStore = it; soundPrefs.edit().putBoolean("auto_store_refresh", it).apply()
            WidgetRefreshScheduler.schedule(context)
            if(it) WidgetRefreshScheduler.refresh(context)
        }
        Text(txt("Home screen: long-press → Widgets → Raund. Each widget has its own settings and can be resized.",
            "Ana ekrana basılı tut → Widget’lar → Raund. Her widget ayrı ayarlanabilir ve boyutlandırılabilir."),
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp))
        HorizontalDivider(color = Raised.copy(alpha = .55f))
        SettingsDisclosure(txt("Notifications & live scores", "Bildirimler ve canlı skor"), txt("Skin alerts, matches and followed teams", "Skin uyarıları, maçlar ve takip edilen takımlar"), alertsOpen) { alertsOpen = !alertsOpen }
        if(alertsOpen) {
        Row(Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(txt("Live updates / Samsung Now Bar", "Canlı güncellemeler / Samsung Now Bar"), color = White, fontSize = 14.sp)
                Text(when(liveCapability) {
                    "off" -> txt("Disabled", "Kapalı")
                    "notifications" -> txt("Android notifications are disabled", "Android bildirimleri kapalı")
                    "standard" -> txt("Standard ongoing notification · Android 16 Live Updates unavailable", "Standart sürekli bildirim · Android 16 Live Updates kullanılamıyor")
                    "permission" -> txt("Live update permission required", "Canlı güncelleme izni gerekli")
                    else -> txt("System-eligible; lock-screen placement depends on your phone", "Sistem için uygun; kilit ekranındaki görünüm telefona bağlı")
                }, color = Muted, fontSize = 12.sp)
            }
            Switch(nowBar, onCheckedChange = { nowBar = it; soundPrefs.edit().putBoolean("now_bar", it).apply() })
        }
        if(Build.VERSION.SDK_INT >= 36 && nowBar && !canPromote) TextButton(onClick = {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        }) { Text(txt("Allow live updates", "Canlı güncelleme izni ver")) }
        Row(Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(txt("Store & favorite alerts", "Mağaza ve favori uyarıları"), color = White, fontSize = 14.sp)
                Text(txt("Twice daily · around 03:00 and 09:00", "Günde iki kez · yaklaşık 03.00 ve 09.00"), color = Muted, fontSize = 11.sp)
            }
            Switch(alerts, onCheckedChange = onAlerts)
        }
        if(alerts) NotificationPreferenceRow(txt("Daily store summary", "Günlük mağaza özeti"),
            txt("Favorite skins and Night Market · 03:00 / 09:00", "Favori skinler ve Gece Pazarı · 03.00 / 09.00"), storeNotice) {
            storeNotice = it; soundPrefs.edit().putBoolean("notify_store", it).apply(); FavoriteAlerts.schedule(context, it)
        }
        HorizontalDivider(color = Raised.copy(alpha = .55f))
        Row(Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(txt("Match reminders", "Maç hatırlatıcıları"), color = White, fontSize = 14.sp)
                Text(txt("Saved matches & followed teams", "Kayıtlı maçlar ve takip edilen takımlar"), color = Muted, fontSize = 11.sp)
            }
            Switch(matchAlerts, onCheckedChange = onMatchAlerts)
        }
        if(matchAlerts) {
            SectionTitle(txt("Alert intensity", "Bildirim yoğunluğu"))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    Triple("important", txt("Important only", "Sadece önemli"), listOf(true, true, false, false)),
                    Triple("detailed", txt("Every map", "Her harita"), listOf(true, true, true, true)),
                    Triple("silent", txt("Silent tracking", "Sessiz takip"), listOf(false, false, false, true))
                ).forEach { (key, label, values) ->
                    Chip(label, notificationProfile == key) {
                        notificationProfile = key
                        reminderNotice = values[0]; resultNotice = values[1]; mapNotice = values[2]; liveNotice = values[3]
                        soundPrefs.edit().putString("esports_notification_profile", key)
                            .putBoolean("notify_esports_reminders", reminderNotice)
                            .putBoolean("notify_esports_results", resultNotice)
                            .putBoolean("notify_esports_maps", mapNotice)
                            .putBoolean("notify_esports_live", liveNotice).apply()
                        EsportsScoreScheduler.schedule(context, soundPrefs.getString("esports_mode", "off") ?: "off")
                        EsportsReminders.refreshSoon(context)
                    }
                }
            }
            NotificationPreferenceRow(txt("15-minute reminder", "15 dakika hatırlatması"),
                txt("Saved matches, teams and tournaments", "Kayıtlı maç, takım ve turnuvalar"), reminderNotice) {
                reminderNotice = it; notificationProfile = "custom"; soundPrefs.edit().putString("esports_notification_profile", "custom").putBoolean("notify_esports_reminders", it).apply()
                EsportsReminders.sync(context, SavedEsportsMatches.get(context) + EsportsRepository(context).cached()?.upcoming.orEmpty())
            }
            NotificationPreferenceRow(txt("Final result", "Maç sonucu"),
                txt("One notification when a followed match ends", "Takip edilen maç bitince tek bildirim"), resultNotice) {
                resultNotice = it; notificationProfile = "custom"; soundPrefs.edit().putString("esports_notification_profile", "custom").putBoolean("notify_esports_results", it).apply()
            }
            NotificationPreferenceRow(txt("Map result", "Harita sonucu"),
                txt("One alert when a followed match completes a map", "Takip edilen maçta her harita bitince tek uyarı"), mapNotice) {
                mapNotice = it; notificationProfile = "custom"; soundPrefs.edit().putString("esports_notification_profile", "custom").putBoolean("notify_esports_maps", it).apply()
            }
            NotificationPreferenceRow(txt("Live score summary", "Canlı skor özeti"),
                txt("Compact ongoing card for followed live matches", "Takip edilen canlı maçlar için tek özet kartı"), liveNotice) {
                liveNotice = it; notificationProfile = "custom"; soundPrefs.edit().putString("esports_notification_profile", "custom").putBoolean("notify_esports_live", it).apply()
                EsportsScoreScheduler.schedule(context, esportsMode)
            }
        }
        SectionTitle(txt("Live score display", "Canlı skor gösterimi"))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Chip(txt("Off","Kapalı"), esportsMode == "off") { onEsportsMode("off") }
            Chip(txt("Favorite teams","Favori takımlar"), esportsMode == "favorites") { onEsportsMode("favorites") }
            Chip(txt("All live","Tüm canlılar"), esportsMode == "all") { onEsportsMode("all") }
        }
        Text(if(esportsTeams.isEmpty()) txt("Follow teams from Esports to filter scores.", "Skorları filtrelemek için Espor'dan takım takip et.")
            else esportsTeams.sorted().joinToString(" · "), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
        HorizontalDivider(color = Raised.copy(alpha = .7f), modifier = Modifier.padding(top = 22.dp))
        Row(Modifier.fillMaxWidth().clickable { deliveryOpen = !deliveryOpen }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("Delivery & permissions", "İzinler ve çalışma şekli"), color = White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if(deliveryOpen) "⌃" else "⌄", color = Muted)
        }
        if(deliveryOpen) {
            NotificationPreferenceRow(txt("Battery saver", "Pil tasarrufu"),
                txt("Default · widgets every 2 hours, background scores hourly, live matches every 30 seconds", "Varsayılan · widget 2 saatte bir, arka plan skorları saatte bir, canlı maçlar 30 saniyede bir"), batterySaver) {
                batterySaver = it
                soundPrefs.edit().putBoolean("battery_saver", it).apply()
                WidgetRefreshScheduler.schedule(context)
                EsportsScoreScheduler.schedule(context, esportsMode)
            }
            Note(if(batterySaver) txt("Battery saver is on. Live tracking starts at match time and updates only when the displayed score changes. Turn it off only if you prefer faster background checks.",
                "Pil tasarrufu açık. Canlı takip maç saatinde başlar ve yalnızca ekrandaki skor değişince güncellenir. Daha sık arka plan kontrolü istersen kapatabilirsin.") else txt("Fast checks are on: widgets refresh every 30 minutes, background scores every 15 minutes, and explicit live tracking can poll every 10 seconds.",
                "Sık kontrol açık: widget 30 dakikada bir, arka plan skorları 15 dakikada bir yenilenir; doğrudan takip edilen canlı maç 10 saniyede bir kontrol edilebilir."))
            TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
                Text(txt("Android notification settings", "Android bildirim ayarları"))
            }
            if(Build.VERSION.SDK_INT >= 31 && !EsportsReminders.exactAllowed(context)) TextButton(onClick = {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"))) }
            }) { Text(txt("Allow precise reminders", "Kesin hatırlatıcı izni")) }
            Note(txt("Samsung Now Bar: enable Live notifications and lock-screen notifications in your phone settings. Availability depends on your device and One UI version.",
                "Samsung Now Bar: Telefon ayarlarından Canlı bildirimler ve kilit ekranı bildirimlerini aç. Görünürlük cihazına ve One UI sürümüne bağlıdır."))
        }
        HorizontalDivider(color = Raised.copy(alpha = .7f))
        Row(Modifier.fillMaxWidth().clickable { trustOpen = !trustOpen }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("Account & trust", "Hesap ve güven"), color = White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if(trustOpen) "⌃" else "⌄", color = Muted)
        }
        if(trustOpen) {
            if(account != null) TextButton(onClick = onLogout) {
                Text(txt("Sign out of this device", "Bu cihazdan çıkış yap"), color = MaterialTheme.colorScheme.error)
            }
            Note(txt("You enter your password on Riot's HTTPS page. Tokens are encrypted on this device; there is no Raund account server. Sign out clears the session and browser cookies.",
                "Parolanı Riot'un HTTPS sayfasında girersin. Oturum bu cihazda şifrelenir; Raund hesap sunucusu yoktur. Çıkış oturumu ve çerezleri siler."))
            Note(txt("Independent and not approved by Riot. Store tracking uses undocumented services, so no ban-free guarantee is possible.",
                "Bağımsız ve Riot tarafından onaylı değil. Mağaza takibi belgelenmemiş servisler kullanır; ban olmayacağı garantisi verilemez."))
            Note(txt("Esports source: VLR.gg. Source credit is not a license; redistribution requires provider permission. Google/Apple sign-in may require an approved Riot integration rather than this embedded browser.",
                "Espor kaynağı: VLR.gg. Kaynak belirtmek lisans değildir; dağıtım için sağlayıcı izni gerekir. Google/Apple girişi bu gömülü tarayıcı yerine onaylı Riot entegrasyonu gerektirebilir."))
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://support-developer.riotgames.com/hc/en-us/articles/22698769097107-VALORANT"))) }) {
                Text(txt("Riot policy ↗", "Riot politikası ↗"))
            }
        }
        CompanionSettings(onLogin)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun NotificationPreferenceRow(title: String, subtitle: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = White, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(enabled, onCheckedChange = onChange)
    }
}

@Composable private fun SettingsDisclosure(title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Text(if(expanded) "−" else "+", color = Muted, fontSize = 22.sp)
    }
}

@Composable internal fun DetailPage(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Ink).navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(txt("‹ Back","‹ Geri"), color = White) }
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), content = content)
    }
}
@Composable private fun SkinDetailPage(offer: StoreOffer, saved: Boolean, owned: Boolean, onWish: () -> Unit, onCompare: () -> Unit, onClose: () -> Unit) {
    var variant by remember(offer.id) { mutableIntStateOf(-1) }
    var imageFailed by remember(offer.id, variant) { mutableStateOf(false) }
    var playingVideo by remember(offer.id) { mutableStateOf<String?>(null) }
    DetailPage(offer.name, onClose) {
        val image = offer.variants.getOrNull(variant)?.image ?: offer.imageUrl
        if(offer.collection == "playertitles" || image.isNullOrBlank() || imageFailed) {
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                Text(offer.name, color = White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            AsyncImage(image, offer.name, Modifier.fillMaxWidth().height(240.dp).padding(10.dp), contentScale = ContentScale.Fit,
                onError = { imageFailed = true })
        }
        playingVideo?.let { video ->
            EmbeddedSkinPreview(video, offer.name) { playingVideo = null }
        }
        Header(offerDisplayName(offer), offer.weaponLabel())
        if(offer.metadataPending) Note(txt("This item is preserved while its public name and artwork are pending. Refresh the store later; a missing image does not mean it is not owned.",
            "Bu öğe, herkese açık ad ve görseli beklenirken korunuyor. Mağazayı daha sonra yenile; görsel eksikliği öğeye sahip olmadığın anlamına gelmez."))
        if(owned) Text(txt("✓ Owned in your Riot inventory","✓ Riot envanterinde sahipsin"), color = Green,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        else Price(offer)
        if(owned && offer.ownedLevelNames.orEmpty().isNotEmpty()) {
            SectionTitle(txt("Owned levels","Sahip olduğun seviyeler"), offer.ownedLevelNames.orEmpty().size.toString())
            offer.ownedLevelNames.orEmpty().forEach { level ->
                Text("✓  $level", color = White, fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
            }
        }
        Button(onClick = onWish, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = if(saved) Raised else Accent)) { Text(if(saved) txt("♥ Saved · remove","♥ Kaydedildi · kaldır") else txt("♡ Add to favorites","♡ Favorilere ekle"), color = if(saved) White else MaterialTheme.colorScheme.onPrimary) }
        TextButton(onClick = onCompare, modifier = Modifier.fillMaxWidth()) { Text(txt("Compare with another skin", "Başka bir skinle karşılaştır")) }
        if(offer.variants.isNotEmpty()) {
            SectionTitle(txt("Variants & upgrades","Renkler ve geliştirmeler"))
            if(owned) Note(txt("These are catalog previews; only the levels listed above are confirmed owned.",
                "Bunlar katalog önizlemeleridir; yalnızca yukarıda yazan seviyelerin sahipliği doğrulandı."))
            offer.variants.forEachIndexed { index, value ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(variant == index) Raised else Color.Transparent).clickable { variant = index }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(value.image ?: offer.imageUrl, value.name, Modifier.width(75.dp).height(48.dp), contentScale = ContentScale.Fit)
                    Text(value.name, color = White, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    value.video?.let { video -> TextButton(onClick = { playingVideo = video }) {
                        Text(if(playingVideo == video) txt("Playing","Oynuyor") else txt("▶ Preview","▶ Ön izle"), color = Accent)
                    } }
                }
            }
        }
        if(!owned && offer.priceVp < 0) Note(txt("Collection entry. Availability and price appear only when Riot returns an active offer. Limited and Battle Pass skins may not enter the daily store.", "Koleksiyon kaydı. Satış durumu ve fiyat, Riot aktif teklif döndürdüğünde görünür. Sınırlı ve Savaş Bileti skinleri günlük mağazaya gelmeyebilir."))
    }
}

@Composable private fun EmbeddedSkinPreview(url: String, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumePlayback by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { loading = state == Player.STATE_BUFFERING }
            override fun onPlayerError(cause: PlaybackException) { error = true; loading = false }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP) { resumePlayback = player.playWhenReady; player.pause() }
            if(event == Lifecycle.Event.ON_START && resumePlayback) { player.play(); resumePlayback = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("In-app preview","Uygulama içi ön izleme"), color = White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("×") }
        }
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = true } },
                update = { it.player = player }, modifier = Modifier.fillMaxSize())
            if(loading && !error) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            if(error) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(txt("Preview could not be played", "Ön izleme oynatılamadı"), color = Color.White, fontSize = 12.sp)
                TextButton(onClick = { error = false; loading = true; player.prepare(); player.play() }) { Text(txt("Retry", "Tekrar dene")) }
            }
        }
        Text(title, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable private fun VerifiedRiotLogin(onSession: (RiotSession) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val tr = LocalTurkish.current
    val latestSession by rememberUpdatedState(onSession)
    val latestCancel by rememberUpdatedState(onCancel)
    var launched by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val access = result.data?.getStringExtra(RiotLoginActivity.ACCESS)
        val id = result.data?.getStringExtra(RiotLoginActivity.ID)
        if(result.resultCode == android.app.Activity.RESULT_OK && !access.isNullOrBlank() && !id.isNullOrBlank())
            latestSession(RiotSession(access, id))
        else latestCancel()
    }
    LaunchedEffect(Unit) {
        if(!launched) {
            launched = true
            launcher.launch(Intent(context, RiotLoginActivity::class.java).putExtra(RiotLoginActivity.LOCALE, if(tr) "tr-tr" else "en-us"))
        }
    }
}
