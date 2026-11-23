package app.nightwatch.store

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class EsportsMatch(
    val id: String,
    val team1: String,
    val team2: String,
    val score1: Int?,
    val score2: Int?,
    val event: String,
    val series: String,
    val status: String,
    val whenText: String,
    val url: String,
    val startAtMillis: Long? = null,
    val team1Logo: String? = null,
    val team2Logo: String? = null,
    val eventIcon: String? = null
) {
    val score: String get() = if(score1 != null && score2 != null) "$score1 - $score2" else "—"
}

internal data class EsportsDetail(val match: EsportsMatch, val format: String, val maps: List<String>,
                                  val streams: List<Pair<String, String>>,
                                  val team1Url: String? = null, val team2Url: String? = null,
                                  val eventUrl: String? = null,
                                  val players: List<EsportsPlayerLine>? = null,
                                  val mapResults: List<EsportsMapResult>? = null,
                                  val veto: String? = null, val patch: String? = null,
                                  val vods: List<Pair<String, String>>? = null,
                                  val mapPlayers: Map<String, List<EsportsPlayerLine>> = emptyMap())

internal data class EsportsMapResult(val name: String, val score1: Int?, val score2: Int?,
                                    val duration: String, val pick: String,
                                    val attack1: Int? = null, val defense1: Int? = null,
                                    val attack2: Int? = null, val defense2: Int? = null)

internal data class EsportsPlayerLine(val team: String, val name: String, val agentIcon: String?, val playerUrl: String?,
                                     val rating: String, val acs: String, val kills: String, val deaths: String,
                                     val assists: String, val adr: String, val hs: String,
                                     val kast: String? = null, val firstKills: String? = null, val firstDeaths: String? = null)

internal data class EsportsFeed(val live: List<EsportsMatch>, val upcoming: List<EsportsMatch>, val results: List<EsportsMatch>, val checkedAt: Long,
    val resultsCheckedAt: Long = checkedAt, val partialResults: Boolean = false)

internal object EsportsCachePolicy {
    fun merge(saved: EsportsMatch, incoming: EsportsMatch): EsportsMatch {
        require(saved.id == incoming.id)
        // Navigating from an old saved card must not turn a finished game back into a live one.
        if(saved.status == "result" && incoming.status in setOf("live", "upcoming", "postponed")) return saved
        if(incoming.status in setOf("cancelled", "postponed")) return saved.copy(status = incoming.status,
            score1 = null, score2 = null, startAtMillis = incoming.startAtMillis ?: saved.startAtMillis)
        return saved.copy(score1 = incoming.score1 ?: saved.score1, score2 = incoming.score2 ?: saved.score2,
            status = incoming.status, startAtMillis = incoming.startAtMillis ?: saved.startAtMillis)
    }
}

internal object EsportsParser {
    fun parse(html: String, fallbackStatus: String): List<EsportsMatch> {
        val document: Document = Jsoup.parse(html, "https://www.vlr.gg")
        return document.select("a.match-item").mapNotNull { item ->
            val href = item.attr("href")
            val id = Regex("^/(\\d+)/").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val teams = item.select(".match-item-vs-team")
            if(teams.size < 2) return@mapNotNull null
            val names = teams.take(2).map { it.selectFirst(".match-item-vs-team-name .text-of")?.text()?.trim().orEmpty() }
            if(names.any { it.isBlank() }) return@mapNotNull null
            val scores = teams.take(2).map { it.selectFirst(".match-item-vs-team-score")?.text()?.trim()?.toIntOrNull() }
            val logos = teams.take(2).map { it.selectFirst("img")?.absUrl("src")?.takeIf { url -> url.startsWith("https://") } }
            val siteStatus = item.selectFirst(".ml-status")?.text()?.lowercase(Locale.ROOT).orEmpty()
            // The VLR schedule contains placeholder slots for unseeded bracket games.
            // They are not real upcoming matches and used to flood the app's feed.
            if(names.all { it.equals("TBD", ignoreCase = true) } || siteStatus == "tbd") return@mapNotNull null
            val status = when {
                siteStatus.contains("cancel") -> "cancelled"
                siteStatus.contains("postpon") -> "postponed"
                siteStatus.contains("live") -> "live"
                siteStatus.contains("complet") || siteStatus.contains("final") || siteStatus.contains("result") -> "result"
                siteStatus.contains("upcoming") -> "upcoming"
                else -> fallbackStatus
            }
            val event = item.selectFirst(".match-item-event")
            val heading = item.parent()?.previousElementSibling()?.takeIf { it.hasClass("wf-label") }?.text().orEmpty()
            val clock = item.selectFirst(".match-item-time")?.text().orEmpty()
            // VLR renders these times in the requester's timezone; the list does not expose its offset.
            // Keep the visible source text instead of incorrectly treating it as UTC.
            val whenText = listOf(heading, clock).filter { it.isNotBlank() }.joinToString(" · ")
            EsportsMatch(id, names[0], names[1], scores[0], scores[1], event?.ownText()?.trim().orEmpty(),
                event?.selectFirst(".match-item-event-series")?.text().orEmpty(), status, whenText, "https://www.vlr.gg$href",
                team1Logo = logos.getOrNull(0), team2Logo = logos.getOrNull(1),
                eventIcon = item.selectFirst(".match-item-icon img")?.absUrl("src")?.takeIf { it.startsWith("https://") })
        }.distinctBy { it.id }
    }

    fun detail(match: EsportsMatch, html: String): EsportsDetail {
        val page = Jsoup.parse(html, match.url)
        val clock = page.select(".match-header-date .moment-tz-convert").getOrNull(1)
        val rawDate = page.selectFirst(".match-header-date .moment-tz-convert")?.attr("data-utc-ts").orEmpty()
        val offsetClock = clock?.text()?.trim().orEmpty()
        val start = runCatching {
            val date = rawDate.substringBefore(' ')
            val pieces = Regex("(\\d{1,2}:\\d{2}\\s*[AP]M)\\s*(UTC|[+-]\\d{2}(?::?\\d{2})?)", RegexOption.IGNORE_CASE)
                .find(offsetClock) ?: error("No timezone in match detail")
            val local = LocalDateTime.parse("$date ${pieces.groupValues[1].replace(Regex("\\s+"), " ")}",
                DateTimeFormatter.ofPattern("uuuu-MM-dd h:mm a", Locale.ENGLISH))
            val zone = pieces.groupValues[2].let { if(it.equals("UTC", true)) ZoneOffset.UTC else ZoneOffset.of(it) }
            local.toInstant(zone).toEpochMilli()
        }.getOrNull()
        val format = page.select(".match-header-vs-note").map { it.text().trim() }
            .firstOrNull { it.matches(Regex("(?i)bo[1-9]")) }.orEmpty()
        val maps = page.select(".vm-stats-gamesnav-item:not(.mod-all)").map { it.text().trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }.distinct().take(7)
        val streams = page.select(".match-streams a[href]").mapNotNull { link ->
            val url = link.absUrl("href")
            if(!url.startsWith("https://")) null else (link.text().trim().ifBlank { "Stream" } to url)
        }.distinctBy { it.second }.take(5)
        val vods = page.select(".match-vods a[href]").mapNotNull { link ->
            val url = link.absUrl("href")
            if(!url.startsWith("https://")) null else (link.text().trim().ifBlank { "VOD" } to url)
        }.distinctBy { it.second }.take(10)
        val mapGames = page.select(".vm-stats-game[data-game-id]").filter { it.attr("data-game-id") != "all" }
        val mapResults = mapGames
            .mapNotNull { game ->
                val header = game.selectFirst(".vm-stats-game-header") ?: return@mapNotNull null
                val name = header.selectFirst(".map")?.ownText()?.trim().orEmpty().ifBlank {
                    header.selectFirst(".map span")?.ownText()?.trim().orEmpty()
                }
                if(name.isBlank()) return@mapNotNull null
                val teams = header.select(".team")
                EsportsMapResult(name, teams.getOrNull(0)?.selectFirst(".score")?.text()?.trim()?.toIntOrNull(),
                    teams.getOrNull(1)?.selectFirst(".score")?.text()?.trim()?.toIntOrNull(),
                    header.selectFirst(".map-duration")?.text()?.trim().orEmpty(),
                    header.selectFirst(".picked")?.text()?.trim().orEmpty(),
                    teams.getOrNull(0)?.selectFirst(".mod-t")?.text()?.trim()?.toIntOrNull(),
                    teams.getOrNull(0)?.selectFirst(".mod-ct")?.text()?.trim()?.toIntOrNull(),
                    teams.getOrNull(1)?.selectFirst(".mod-t")?.text()?.trim()?.toIntOrNull(),
                    teams.getOrNull(1)?.selectFirst(".mod-ct")?.text()?.trim()?.toIntOrNull())
            }.distinctBy { it.name }
        val veto = page.selectFirst(".match-header-note")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val patch = page.select(".match-header-date div").map { it.text().trim() }.firstOrNull { it.startsWith("Patch ") }
        val teams = page.select(".match-header-link")
        val logos = teams.take(2).map { it.selectFirst("img")?.absUrl("src")?.takeIf { url -> url.startsWith("https://") } }
        val urls = teams.take(2).map { it.absUrl("href").takeIf { url -> Regex("^https://www\\.vlr\\.gg/team/\\d+(?:/.*)?$").matches(url) } }
        val eventUrl = page.selectFirst(".match-header-event")?.absUrl("href")
            ?.takeIf { Regex("^https://www\\.vlr\\.gg/event/\\d+(?:/.*)?$").matches(it) }
        val headerScores = page.select(".match-header-vs-score .sp-hide > span")
            .mapNotNull { it.text().trim().toIntOrNull() }.take(2)
        val headerState = page.selectFirst(".match-header-vs-score > .match-header-vs-note")
            ?.text()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val resolvedStatus = when {
            headerState.contains("cancel") -> "cancelled"
            headerState.contains("postpon") -> "postponed"
            headerState == "final" || headerState == "completed" -> "result"
            headerState.contains("live") -> "live"
            else -> match.status
        }
        val resolvedMatch = match.copy(
            score1 = if(resolvedStatus in setOf("cancelled", "postponed")) null else headerScores.getOrNull(0) ?: match.score1,
            score2 = if(resolvedStatus in setOf("cancelled", "postponed")) null else headerScores.getOrNull(1) ?: match.score2,
            status = resolvedStatus,
            startAtMillis = start ?: match.startAtMillis, team1Logo = logos.getOrNull(0) ?: match.team1Logo, team2Logo = logos.getOrNull(1) ?: match.team2Logo
        )
        fun players(game: Element): List<EsportsPlayerLine> = game.select(".ovw-table").take(2)
            .flatMapIndexed { teamIndex, table ->
                table.select(".ovw-row:not(.mod-head)").mapNotNull { row ->
                    val name = row.selectFirst(".ovw-player-name")?.text()?.trim().orEmpty()
                    if(name.isBlank()) return@mapNotNull null
                    fun stat(column: String) = row.selectFirst(".ovw-cell[data-col=$column] .side.mod-both")?.text()?.trim()
                        ?: row.selectFirst(".ovw-kda-stat[data-col=$column] .side.mod-both")?.text()?.trim().orEmpty()
                    val playerUrl = row.selectFirst(".ovw-player a")?.absUrl("href")
                        ?.takeIf { Regex("^https://www\\.vlr\\.gg/player/\\d+(?:/.*)?$").matches(it) }
                    EsportsPlayerLine(if(teamIndex == 0) match.team1 else match.team2, name,
                        row.selectFirst(".ovw-agents img")?.absUrl("src")?.takeIf { it.startsWith("https://") }, playerUrl,
                        stat("rating2"), stat("acs"), stat("kills"), stat("deaths"), stat("assists"), stat("adr"), stat("hsp"),
                        stat("kast"), stat("fb"), stat("fd"))
                }
            }
        val allMapGame = page.selectFirst(".vm-stats-game[data-game-id=all]")
        val scoreboard = allMapGame?.let(::players).orEmpty().ifEmpty {
            page.selectFirst(".vm-stats-game.mod-active")?.let(::players).orEmpty().ifEmpty { mapGames.firstOrNull()?.let(::players).orEmpty() }
        }
        val perMapPlayers = mapGames.mapNotNull { game ->
            val name = game.selectFirst(".vm-stats-game-header .map")?.ownText()?.trim().orEmpty().ifBlank {
                game.selectFirst(".vm-stats-game-header .map span")?.ownText()?.trim().orEmpty()
            }
            name.takeIf(String::isNotBlank)?.let { it to players(game) }
        }.toMap()
        return EsportsDetail(resolvedMatch,
            format, maps, streams, urls.getOrNull(0), urls.getOrNull(1), eventUrl, scoreboard,
            mapResults, veto, patch, vods, perMapPlayers)
    }
}

internal class EsportsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("esports_public_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val detailCache = context.getSharedPreferences("esports_detail_cache", Context.MODE_PRIVATE)
    fun cached(): EsportsFeed? {
        val raw = prefs.getString("feed", null) ?: return null
        if(raw == EsportsMemory.feedRaw) return EsportsMemory.feed
        return runCatching { gson.fromJson(raw, EsportsFeed::class.java) }.getOrNull()?.also {
            EsportsMemory.feed = it; EsportsMemory.feedRaw = raw
        }
    }
    fun cachedDetail(id: String): EsportsDetail? {
        val raw = detailCache.getString(id, null) ?: return null
        if(raw == EsportsMemory.detailRaw[id]) return EsportsMemory.details[id]
        return runCatching { gson.fromJson(raw, EsportsDetail::class.java) }.getOrNull()?.also {
            EsportsMemory.details[id] = it; EsportsMemory.detailRaw[id] = raw
        }
    }
    fun cachedDetailAt(id: String): Long = detailCache.getLong("${id}_at", 0L)

    suspend fun details(match: EsportsMatch, force: Boolean = false): EsportsDetail = withContext(Dispatchers.IO) {
        val saved = detailCache.getString(match.id, null)?.let { runCatching { gson.fromJson(it, EsportsDetail::class.java) }.getOrNull() }
        val age = System.currentTimeMillis() - detailCache.getLong("${match.id}_at", 0L)
        val completeResult = saved?.mapResults.orEmpty().isNotEmpty() && saved?.mapResults.orEmpty().all { it.score1 != null && it.score2 != null }
        val maxAge = when(match.status) {
            "live" -> 30_000L
            "result" -> if(completeResult) 10 * 60_000L else 0L
            else -> 10 * 60_000L
        }
        if(!force && saved != null && age < maxAge)
            return@withContext saved.copy(match = EsportsCachePolicy.merge(saved.match, match))
        // Build the URL from the numeric ID, never from arbitrary page content.
        require(Regex("^[0-9]{1,12}$").matches(match.id)) { "Invalid match ID" }
        val html = Jsoup.connect("https://www.vlr.gg/${match.id}")
            .userAgent("Raund/0.11 Android companion").timeout(12_000).maxBodySize(1_500_000).get().outerHtml()
        EsportsParser.detail(match, html).also {
            EsportsMemory.details[match.id] = it
            val raw = gson.toJson(it)
            EsportsMemory.detailRaw[match.id] = raw
            detailCache.edit().apply {
                putString(match.id, raw)
                putLong("${match.id}_at", System.currentTimeMillis())
                // Bound cached match details so SharedPreferences does not grow indefinitely.
                detailCache.all.entries
                    .filter { it.key.endsWith("_at") && it.value is Long }
                    .sortedByDescending { it.value as Long }
                    .drop(80)
                    .forEach { (atKey, _) ->
                        val id = atKey.removeSuffix("_at")
                        remove(atKey); remove(id)
                        EsportsMemory.details.remove(id); EsportsMemory.detailRaw.remove(id)
                    }
            }.apply()
        }
    }

    suspend fun load(force: Boolean = false): EsportsFeed = withContext(Dispatchers.IO) {
        val old = cached()
        val maxAge = if(old?.live?.isNotEmpty() == true) 60_000L else 5 * 60_000L
        if(!force && old != null && System.currentTimeMillis() - old.checkedAt < maxAge) return@withContext old
        fun fetch(path: String) = Jsoup.connect("https://www.vlr.gg$path")
            .userAgent("Raund/0.11 Android companion")
            .timeout(12_000).maxBodySize(1_000_000).get().outerHtml()
        val schedule = EsportsParser.parse(fetch("/matches"), "upcoming")
        val resultFetch = runCatching { EsportsParser.parse(fetch("/matches/results"), "result") }
        val results = resultFetch.getOrElse { old?.results.orEmpty() }
        if(schedule.isEmpty() && results.isEmpty()) error("No match data returned")
        val feed = EsportsFeed(schedule.filter { it.status == "live" }, schedule.filter { it.status == "upcoming" },
            (schedule.filter { it.status == "result" } + results).distinctBy { it.id }.take(25), System.currentTimeMillis(),
            resultsCheckedAt = if(resultFetch.isSuccess) System.currentTimeMillis() else old?.resultsCheckedAt ?: 0,
            partialResults = resultFetch.isFailure)
        val raw = gson.toJson(feed)
        prefs.edit().putString("feed", raw).apply()
        feed.also { EsportsMemory.feed = it; EsportsMemory.feedRaw = raw }
    }

    suspend fun refreshLive(): EsportsFeed = withContext(Dispatchers.IO) {
        val schedule = EsportsParser.parse(Jsoup.connect("https://www.vlr.gg/matches")
            .userAgent("Raund/0.11 Android companion").timeout(12_000).maxBodySize(1_000_000).get().outerHtml(), "upcoming")
        val old = cached()
        if(schedule.isEmpty()) error("No match data returned")
        val feed = EsportsFeed(schedule.filter { it.status == "live" }, schedule.filter { it.status == "upcoming" },
            (schedule.filter { it.status == "result" } + old?.results.orEmpty()).distinctBy { it.id }, System.currentTimeMillis(),
            resultsCheckedAt = old?.resultsCheckedAt ?: 0, partialResults = old?.partialResults ?: true)
        val raw = gson.toJson(feed)
        prefs.edit().putString("feed", raw).apply()
        feed.also { EsportsMemory.feed = it; EsportsMemory.feedRaw = raw }
    }
}

private object EsportsMemory {
    @Volatile var feed: EsportsFeed? = null
    @Volatile var feedRaw: String? = null
    val details = ConcurrentHashMap<String, EsportsDetail>()
    val detailRaw = ConcurrentHashMap<String, String>()
}
