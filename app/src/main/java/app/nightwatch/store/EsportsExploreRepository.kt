package app.nightwatch.store

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

internal data class EsportsEvent(val id: String, val title: String, val url: String, val image: String?,
                                  val status: String, val dates: String)
internal data class EsportsNews(val title: String, val summary: String, val date: String, val url: String)
internal data class BracketEntry(val round: String, val phase: String, val team1: String, val team2: String,
                                 val score1: Int?, val score2: Int?, val logo1: String?, val logo2: String?,
                                 val matchUrl: String, val eliminated: String? = null, val startAtMillis: Long? = null)
internal data class TournamentDetail(val event: EsportsEvent, val bracket: List<BracketEntry>,
                                     val teams: List<Pair<String, String>>, val description: String = "",
                                     val prize: String = "", val region: String = "", val stages: List<String> = emptyList(),
                                     val matches: List<EsportsMatch> = emptyList())
internal data class TournamentStanding(val team: String, val played: Int, val wins: Int, val losses: Int,
                                       val mapDifference: Int, val logo: String?)
internal data class EsportsPlayer(val name: String, val realName: String, val image: String?, val url: String)
internal data class EsportsTeam(val name: String, val image: String?, val url: String, val players: List<EsportsPlayer>,
                                val matches: List<EsportsMatch> = emptyList())
internal data class EsportsTeamSearchResult(val name: String, val image: String?, val url: String)
internal data class EsportsPlayerSearchResult(val name: String, val image: String?, val url: String, val description: String)
internal data class PlayerAgent(val name: String, val image: String?, val usage: String, val rating: String, val acs: String,
                                val kd: String = "", val adr: String = "", val kills: String = "", val deaths: String = "",
                                val rounds: String = "", val kast: String = "", val kpr: String = "", val apr: String = "",
                                val assists: String = "", val firstKills: String = "", val firstDeaths: String = "")
internal data class PlayerSocial(val label: String, val url: String)
internal data class PlayerAchievement(val event: String, val eventUrl: String, val results: List<String>, val year: String)
internal data class PlayerTeamTenure(val name: String, val logo: String?, val url: String, val period: String,
                                     val note: String = "", val current: Boolean = false)
internal data class PlayerMapPerformance(val agent: String, val agentIcon: String?, val map: String, val score: String,
                                         val rating: String, val kills: Int?, val deaths: Int?, val assists: Int?,
                                         val won: Boolean?, val url: String)
internal data class PlayerMatchPerformance(val id: String, val event: String, val stage: String, val team: String,
                                           val opponent: String, val teamLogo: String?, val opponentLogo: String?,
                                           val score1: Int?, val score2: Int?, val won: Boolean?, val date: String,
                                           val url: String, val maps: List<PlayerMapPerformance>) {
    fun asMatch() = EsportsMatch(id, team, opponent, score1, score2, event, stage,
        if(score1 != null && score2 != null) "result" else "upcoming", date, "https://www.vlr.gg/$id",
        team1Logo = teamLogo, team2Logo = opponentLogo)
}
internal data class PlayerProfile(val player: EsportsPlayer, val country: String, val agents: List<PlayerAgent>,
                                  val period: String = "60d", val socials: List<PlayerSocial> = emptyList(),
                                  val achievements: List<PlayerAchievement> = emptyList(),
                                  val matches: List<PlayerMatchPerformance> = emptyList(),
                                  val news: List<EsportsNews> = emptyList(),
                                  val teams: List<PlayerTeamTenure> = emptyList(),
                                  val totalWinnings: String = "")

internal fun tournamentStandings(matches: List<EsportsMatch>): List<TournamentStanding> {
    data class MutableStanding(var played: Int = 0, var wins: Int = 0, var losses: Int = 0, var diff: Int = 0, var logo: String? = null)
    val table = linkedMapOf<String, MutableStanding>()
    matches.filter { it.score1 != null && it.score2 != null }.distinctBy { it.id }.forEach { match ->
        val first = table.getOrPut(match.team1) { MutableStanding(logo = match.team1Logo) }
        val second = table.getOrPut(match.team2) { MutableStanding(logo = match.team2Logo) }
        first.played++; second.played++
        first.diff += match.score1!! - match.score2!!; second.diff += match.score2 - match.score1
        if(match.score1 > match.score2) { first.wins++; second.losses++ }
        else if(match.score2 > match.score1) { second.wins++; first.losses++ }
    }
    return table.map { (team, row) -> TournamentStanding(team, row.played, row.wins, row.losses, row.diff, row.logo) }
        .sortedWith(compareByDescending<TournamentStanding> { it.wins }.thenByDescending { it.mapDifference }.thenBy { it.team })
}

internal object EsportsPortraits {
    private val images = ConcurrentHashMap<String, String>()
    operator fun get(playerUrl: String): String? = images[playerUrl]
    fun remember(players: List<EsportsPlayer>) = players.forEach { player -> player.image?.let { images[player.url] = it } }
    fun remember(player: EsportsPlayer) { player.image?.let { images[player.url] = it } }
}

internal object EsportsExploreParser {
    private fun safeImage(raw: String?): String? = raw?.trim()?.let {
        when {
            it.startsWith("https://") -> it
            it.startsWith("//") -> "https:$it"
            it.startsWith("/") -> "https://www.vlr.gg$it"
            else -> null
        }
    }?.takeIf { !it.contains("/img/base/ph/") && !it.endsWith("/img/vlr/tmp/vlr.png") }

    fun events(html: String): List<EsportsEvent> {
        val page = Jsoup.parse(html, "https://www.vlr.gg")
        return page.select("a.event-item[href^=/event/]").mapNotNull { row ->
            val href = row.attr("href")
            val id = Regex("^/event/(\\d+)").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = row.selectFirst(".event-item-title")?.text()?.trim().orEmpty()
            if(title.isBlank()) return@mapNotNull null
            EsportsEvent(id, title, "https://www.vlr.gg$href", safeImage(row.selectFirst(".event-item-thumb img")?.absUrl("src")),
                row.selectFirst(".event-item-desc-item-status")?.text()?.trim().orEmpty(),
                row.selectFirst(".event-item-desc-item.mod-dates")?.ownText()?.trim().orEmpty())
        }.distinctBy { it.id }
    }

    fun news(html: String): List<EsportsNews> {
        val page = Jsoup.parse(html, "https://www.vlr.gg")
        return page.select("a.wf-module-item[href]").mapNotNull { row ->
            val href = row.attr("href")
            if(!Regex("^/\\d+/[^/]+$").matches(href)) return@mapNotNull null
            val title = row.selectFirst("div[style*=font-weight: 700]")?.text()?.trim().orEmpty()
            if(title.isBlank()) return@mapNotNull null
            EsportsNews(title, row.selectFirst("div[style*=font-size: 13px]")?.text()?.trim().orEmpty(),
                row.selectFirst(".ge-text-light")?.text()?.trim().orEmpty(), "https://www.vlr.gg$href")
        }.distinctBy { it.url }
    }

    fun teamSearch(html: String): List<EsportsTeamSearchResult> {
        val page = Jsoup.parse(html, "https://www.vlr.gg")
        return page.select("a.search-item[href*=/search/r/team/]").mapNotNull { row ->
            val id = Regex("/search/r/team/(\\d+)/").find(row.attr("href"))?.groupValues?.get(1)
                ?: return@mapNotNull null
            val name = row.selectFirst(".search-item-title")?.text()?.trim().orEmpty()
            if(name.isBlank()) return@mapNotNull null
            EsportsTeamSearchResult(name, safeImage(row.selectFirst(".search-item-thumb img")?.absUrl("src")),
                "https://www.vlr.gg/team/$id")
        }.distinctBy { it.url }
    }

    fun playerSearch(html: String): List<EsportsPlayerSearchResult> {
        val page = Jsoup.parse(html, "https://www.vlr.gg")
        return page.select("a.search-item[href*=/search/r/player/]").mapNotNull { row ->
            val id = Regex("/search/r/player/(\\d+)/").find(row.attr("href"))?.groupValues?.get(1)
                ?: return@mapNotNull null
            val name = row.selectFirst(".search-item-title")?.text()?.trim().orEmpty()
            if(name.isBlank()) return@mapNotNull null
            EsportsPlayerSearchResult(name, safeImage(row.selectFirst(".search-item-thumb img")?.absUrl("src")),
                "https://www.vlr.gg/player/$id", row.selectFirst(".search-item-desc")?.text()?.trim().orEmpty())
        }.distinctBy { it.url }
    }

    fun tournament(event: EsportsEvent, html: String): TournamentDetail {
        val page = Jsoup.parse(html, event.url)
        val image = safeImage(page.selectFirst(".event-header-thumb img")?.absUrl("src")) ?: event.image
        val title = page.selectFirst(".event-header-main-title")?.text()?.trim().orEmpty().ifBlank { event.title }
        val description = page.selectFirst(".event-header-main-desc")?.text()?.trim().orEmpty()
        val meta = page.select(".event-header-main-meta > div").associate { row ->
            row.selectFirst(".label")?.text()?.trim()?.lowercase().orEmpty() to row.selectFirst(".value")?.text()?.trim().orEmpty()
        }
        val prize = meta.entries.firstOrNull { it.key.contains("prize") }?.value.orEmpty().ifBlank {
            page.select(".event-prize .prize, .event-prize-distribution .prize, [class*=prize] .value")
                .map { it.text().trim() }.firstOrNull { it.contains(Regex("[$€£¥]\\s*[0-9]")) }.orEmpty()
        }
        val stages = page.select(".wf-subnav-item").mapNotNull { row ->
            val name = row.selectFirst(".wf-subnav-item-title")?.text()?.trim().orEmpty()
            val dates = row.selectFirst(".ge-text-light")?.text()?.trim().orEmpty()
            name.takeIf { it.isNotBlank() }?.let { listOf(dates, name).filter(String::isNotBlank).joinToString(" · ") }
        }.distinct()
        val bracket = page.select(".event-brackets-container .bracket-col").flatMap { column ->
            val round = column.selectFirst(".bracket-col-label")?.text()?.trim().orEmpty()
            val parent = column.parents().firstOrNull { it.hasClass("bracket-container") }
            val phase = when {
                parent?.hasClass("mod-lower") == true -> "lower"
                parent?.hasClass("mod-upper") == true -> "upper"
                else -> "main"
            }
            column.select("a.bracket-item[href]").mapNotNull { row ->
                val href = row.attr("href")
                if(!Regex("^/\\d+/[^/]+$").matches(href)) return@mapNotNull null
                val teams = row.select(".bracket-item-team")
                if(teams.size < 2) return@mapNotNull null
                val names = teams.take(2).map { it.selectFirst(".bracket-item-team-name span")?.text()?.trim().orEmpty().ifBlank { "TBD" } }
                val scores = teams.take(2).map { it.selectFirst(".bracket-item-team-score")?.text()?.trim()?.toIntOrNull() }
                val logos = teams.take(2).map { safeImage(it.selectFirst("img")?.absUrl("src")) }
                val elimination = if((phase == "lower" || round.contains("grand final", true)) && scores[0] != null && scores[1] != null && scores[0] != scores[1])
                    names[if(scores[0]!! < scores[1]!!) 0 else 1] else null
                BracketEntry(round, phase, names[0], names[1], scores[0], scores[1], logos[0], logos[1],
                    "https://www.vlr.gg$href", elimination,
                    row.selectFirst(".bracket-item-status")?.attr("data-utc-ts")?.toLongOrNull()?.times(1000))
            }
        }.distinctBy { it.matchUrl }
        val teams = page.select("a.event-team-name[href^=/team/]").mapNotNull { row ->
            val href = row.attr("href")
            if(!Regex("^/team/\\d+/.+").matches(href)) null else row.text().trim() to "https://www.vlr.gg$href"
        }.distinctBy { it.second }
        val matches = page.select("a.event-group-series-match[href]").mapNotNull { row ->
            val href = row.attr("href")
            val id = Regex("^/(\\d+)/").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val matchTeams = row.select(".team-name").take(2).map { it.text().trim() }
            if(matchTeams.size < 2) return@mapNotNull null
            val score1 = row.selectFirst(".score-left")?.text()?.trim()?.toIntOrNull()
            val score2 = row.selectFirst(".score-right")?.text()?.trim()?.toIntOrNull()
            val logos = row.select(".team-img img").take(2).map { safeImage(it.absUrl("src")) }
            val whenText = row.children().firstOrNull()?.text()?.trim().orEmpty()
            val series = row.selectFirst(".ss-name.mod-full")?.text()?.trim().orEmpty()
            EsportsMatch(id, matchTeams[0], matchTeams[1], score1, score2, title, series,
                if(score1 != null && score2 != null) "result" else "upcoming", whenText, "https://www.vlr.gg$href",
                team1Logo = logos.getOrNull(0), team2Logo = logos.getOrNull(1), eventIcon = image)
        }.distinctBy { it.id }
        val bracketMatches = bracket.mapNotNull { row ->
            val id = Regex("/([0-9]+)/").find(row.matchUrl)?.groupValues?.get(1) ?: return@mapNotNull null
            EsportsMatch(id, row.team1, row.team2, row.score1, row.score2, title, row.round,
                if(row.score1 != null && row.score2 != null) "result" else "upcoming", "", row.matchUrl,
                startAtMillis = row.startAtMillis, team1Logo = row.logo1, team2Logo = row.logo2, eventIcon = image)
        }
        val allMatches = (matches + bracketMatches).distinctBy { it.id }
        return TournamentDetail(event.copy(title = title, image = image), bracket, teams, description,
            prize, meta.entries.firstOrNull { it.key.contains("region") || it.key.contains("location") }?.value.orEmpty(), stages, allMatches)
    }

    fun team(url: String, html: String): EsportsTeam {
        val page = Jsoup.parse(html, url)
        val name = page.selectFirst(".team-header-name h1")?.text()?.trim().orEmpty()
        val image = safeImage(page.selectFirst(".team-header-logo img")?.absUrl("src"))
        val players = page.select(".team-roster-item > a[href^=/player/]").mapNotNull { row ->
            val href = row.attr("href")
            if(!Regex("^/player/\\d+/.+").matches(href)) return@mapNotNull null
            val alias = row.selectFirst(".team-roster-item-name-alias")?.text()?.trim().orEmpty()
            if(alias.isBlank()) return@mapNotNull null
            EsportsPlayer(alias, row.selectFirst(".team-roster-item-name-real")?.text()?.trim().orEmpty(),
                safeImage(row.selectFirst(".team-roster-item-img img")?.absUrl("src")), "https://www.vlr.gg$href")
        }.distinctBy { it.url }
        return EsportsTeam(name, image, url, players, teamMatches(url, html))
    }

    fun teamMatches(url: String, html: String): List<EsportsMatch> {
        val page = Jsoup.parse(html, url)
        return page.select("a.m-item[href]").mapNotNull { row ->
            val href = row.attr("href")
            val id = Regex("^/(\\d+)/").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val names = row.select(".m-item-team-name").take(2).map { it.text().trim() }
            if(names.size < 2 || names.any { it.isBlank() }) return@mapNotNull null
            val scores = row.select(".m-item-result > span").take(2).map { it.text().trim().toIntOrNull() }
            val result = row.selectFirst(".m-item-result")
            val logos = row.select(".m-item-logo img").take(2).map { safeImage(it.absUrl("src")) }
            EsportsMatch(id, names[0], names[1], scores.getOrNull(0), scores.getOrNull(1),
                row.selectFirst(".m-item-event div")?.text()?.trim().orEmpty(),
                row.selectFirst(".m-item-event")?.ownText()?.trim().orEmpty(),
                if(result?.hasClass("mod-tbd") == true) "upcoming" else "result",
                row.selectFirst(".m-item-date")?.text()?.trim().orEmpty(), "https://www.vlr.gg$href",
                team1Logo = logos.getOrNull(0), team2Logo = logos.getOrNull(1),
                eventIcon = safeImage(row.selectFirst(".m-item-thumb img")?.absUrl("src")))
        }.distinctBy { it.id }
    }

    fun player(url: String, html: String, matchesHtml: String? = null, period: String = "60d"): PlayerProfile {
        val page: Document = Jsoup.parse(html, url)
        val header = page.selectFirst(".player-header")
        val player = EsportsPlayer(header?.selectFirst("h1")?.text()?.trim().orEmpty(),
            header?.selectFirst(".player-real-name")?.text()?.trim().orEmpty(),
            safeImage(header?.selectFirst(".wf-avatar img")?.absUrl("src")), url)
        val country = header?.select(".ge-text-light")?.firstOrNull { it.selectFirst("i.flag") != null }?.text()?.trim().orEmpty()
        val socials = page.select(".player-header a[target=_blank][href]").mapNotNull { link ->
            val href = link.absUrl("href").takeIf { social -> social.startsWith("https://") &&
                listOf("x.com/", "twitter.com/", "twitch.tv/", "youtube.com/", "instagram.com/").any(social::contains) }
                ?: return@mapNotNull null
            PlayerSocial(link.text().trim().ifBlank { UriLabel.social(href) }, href)
        }.distinctBy { it.url }
        val agents = page.select("table.st-table.mod-agent-rows tbody tr").mapNotNull { row ->
            val cells = row.select("td")
            if(cells.size < 5) return@mapNotNull null
            val img = cells[0].selectFirst("img")
            PlayerAgent(img?.attr("alt")?.trim().orEmpty(), safeImage(img?.absUrl("src")),
                cells[1].text().trim(), cells[3].text().trim(), cells[4].text().trim(),
                cells.getOrNull(5)?.text()?.trim().orEmpty(), cells.getOrNull(7)?.text()?.trim().orEmpty(),
                cells.getOrNull(11)?.text()?.trim().orEmpty(), cells.getOrNull(12)?.text()?.trim().orEmpty(),
                cells.getOrNull(2)?.text()?.trim().orEmpty(), cells.getOrNull(6)?.text()?.trim().orEmpty(),
                cells.getOrNull(8)?.text()?.trim().orEmpty(), cells.getOrNull(9)?.text()?.trim().orEmpty(),
                cells.getOrNull(13)?.text()?.trim().orEmpty(), cells.getOrNull(14)?.text()?.trim().orEmpty(),
                cells.getOrNull(15)?.text()?.trim().orEmpty())
        }
        val achievements = page.select("a.player-event-item[href^=/event/]").mapNotNull { row ->
            val event = row.selectFirst(".text-of")?.text()?.trim().orEmpty()
            if(event.isBlank()) return@mapNotNull null
            val href = row.attr("href")
            val results = row.select("div[style*=margin-top: 5px]").map { it.text().trim() }.filter(String::isNotBlank)
            val year = row.children().lastOrNull()?.ownText()?.trim().orEmpty()
            PlayerAchievement(event, "https://www.vlr.gg$href", results, year)
        }.distinctBy { it.eventUrl }
        fun teamTenures(label: String, current: Boolean): List<PlayerTeamTenure> {
            val heading = page.select("h2.wf-label").firstOrNull { it.text().trim().equals(label, true) } ?: return emptyList()
            val card = heading.nextElementSibling() ?: return emptyList()
            return card.select("a[href^=/team/]").mapNotNull { row ->
                val href = row.attr("href")
                if(!Regex("^/team/\\d+").containsMatchIn(href)) return@mapNotNull null
                val body = row.selectFirst("div[style*=flex: 1]") ?: return@mapNotNull null
                val name = body.children().firstOrNull()?.text()?.trim().orEmpty()
                if(name.isBlank()) return@mapNotNull null
                val lines = body.select(".ge-text-light").map { it.text().trim() }.filter(String::isNotBlank)
                PlayerTeamTenure(name, safeImage(row.selectFirst("img")?.absUrl("src")), "https://www.vlr.gg$href",
                    lines.firstOrNull().orEmpty(), lines.drop(1).joinToString(" · "), current)
            }.distinctBy { it.url }
        }
        val teams = teamTenures("Current Teams", true) + teamTenures("Past Teams", false)
        val winnings = page.select(".wf-module-label").firstOrNull { it.text().trim().equals("Total Winnings", true) }
            ?.parent()?.selectFirst("span")?.text()?.trim().orEmpty()
        val playerNews = page.select(".player-summary-container-2 a.wf-module-item[href]").mapNotNull { row ->
            if(row.hasClass("player-event-item")) return@mapNotNull null
            val href = row.attr("href")
            if(!Regex("^/\\d+/[^/]+$").matches(href)) return@mapNotNull null
            val title = row.attr("title").trim().ifBlank {
                row.select("div").firstOrNull { it.attr("style").contains("font-weight") }?.text()?.trim().orEmpty()
            }
            if(title.isBlank()) return@mapNotNull null
            EsportsNews(title, "", row.selectFirst(".ge-text-light")?.text()?.trim().orEmpty(), "https://www.vlr.gg$href")
        }.distinctBy { it.url }
        val matches = matchesHtml?.let(::playerMatches).orEmpty()
        return PlayerProfile(player, country, agents, period, socials, achievements, matches, playerNews, teams, winnings)
    }

    fun playerMatches(html: String): List<PlayerMatchPerformance> {
        val page = Jsoup.parse(html, "https://www.vlr.gg")
        return page.select("a.wf-card.fc-flex.m-item[href], a.m-item[href]").mapNotNull { row ->
            val href = row.attr("href")
            val id = Regex("^/(\\d+)/").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val teams = row.select(".m-item-team-name").take(2).map { it.text().trim() }
            if(teams.size < 2 || teams.any(String::isBlank)) return@mapNotNull null
            val result = row.selectFirst(".m-item-result")
            val scores = result?.children()?.filter { it.tagName() == "span" }?.take(2)
                ?.map { it.text().trim().toIntOrNull() }.orEmpty()
            val logos = row.select(".m-item-logo img").take(2).map { safeImage(it.absUrl("src")) }
            val internal = result?.attr("data-match-id").orEmpty()
            val mapRows = internal.takeIf(String::isNotBlank)?.let { page.selectFirst(".m-item-games.match-id-$it") }
                ?.select("a.m-item-games-item[href]").orEmpty().map { game ->
                    val kda = game.selectFirst(".pg-stat[title*=Kills] .pg-v")?.text()?.split('/')?.map { it.trim().toIntOrNull() }.orEmpty()
                    val outcome = game.selectFirst(".m-item-games-result")
                    PlayerMapPerformance(
                        game.selectFirst(".m-item-games-comp-group img")?.attr("alt")?.trim().orEmpty(),
                        safeImage(game.selectFirst(".m-item-games-comp-group img")?.absUrl("src")),
                        game.selectFirst(".map")?.text()?.trim().orEmpty(), game.selectFirst(".score")?.text()?.trim().orEmpty(),
                        game.selectFirst(".pg-stat.mod-r .pg-v")?.text()?.trim().orEmpty(), kda.getOrNull(0), kda.getOrNull(1),
                        kda.getOrNull(2), when { outcome?.hasClass("mod-win") == true -> true; outcome?.hasClass("mod-loss") == true -> false; else -> null },
                        "https://www.vlr.gg${game.attr("href")}")
                }
            val eventNode = row.selectFirst(".m-item-event")
            PlayerMatchPerformance(id, eventNode?.selectFirst("div")?.text()?.trim().orEmpty(),
                eventNode?.ownText()?.replace('·', ' ')?.trim().orEmpty(), teams[0], teams[1], logos.getOrNull(0), logos.getOrNull(1),
                scores.getOrNull(0), scores.getOrNull(1), when { result?.hasClass("mod-win") == true -> true; result?.hasClass("mod-loss") == true -> false; else -> null },
                row.selectFirst(".m-item-date")?.text()?.trim().orEmpty(), "https://www.vlr.gg$href", mapRows)
        }.distinctBy { it.id }.take(50)
    }
}

private object UriLabel {
    fun social(url: String): String = when {
        "twitch.tv/" in url -> "Twitch"
        "youtube.com/" in url -> "YouTube"
        "instagram.com/" in url -> "Instagram"
        else -> "X"
    }
}

internal class EsportsExploreRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("esports_explore_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private fun page(path: String) = Jsoup.connect("https://www.vlr.gg$path")
        .userAgent("Raund/0.11 Android companion").timeout(14_000).maxBodySize(2_000_000).get().outerHtml()
    fun cachedEvents(): List<EsportsEvent> = ExploreMemory.events ?: runCatching {
        gson.fromJson(prefs.getString("events", "[]"), Array<EsportsEvent>::class.java).toList()
    }.getOrDefault(emptyList()).also { ExploreMemory.events = it }
    fun cachedNews(): List<EsportsNews> = ExploreMemory.news ?: runCatching {
        gson.fromJson(prefs.getString("news", "[]"), Array<EsportsNews>::class.java).toList()
    }.getOrDefault(emptyList()).also { ExploreMemory.news = it }
    suspend fun events(force: Boolean = false): List<EsportsEvent> = withContext(Dispatchers.IO) {
        val old = cachedEvents()
        if(!force && old.isNotEmpty() && System.currentTimeMillis() - prefs.getLong("events_at", 0) < 3_600_000) return@withContext old
        val fresh = EsportsExploreParser.events(page("/events"))
        if(fresh.isEmpty()) error("No events returned")
        prefs.edit().putString("events", gson.toJson(fresh)).putLong("events_at", System.currentTimeMillis()).apply()
        fresh.also { ExploreMemory.events = it }
    }
    suspend fun news(force: Boolean = false): List<EsportsNews> = withContext(Dispatchers.IO) {
        val old = cachedNews()
        if(!force && old.isNotEmpty() && System.currentTimeMillis() - prefs.getLong("news_at", 0) < 3_600_000) return@withContext old
        val fresh = EsportsExploreParser.news(page("/news"))
        if(fresh.isEmpty()) error("No news returned")
        prefs.edit().putString("news", gson.toJson(fresh)).putLong("news_at", System.currentTimeMillis()).apply()
        fresh.also { ExploreMemory.news = it }
    }
    suspend fun searchTeams(query: String): List<EsportsTeamSearchResult> = withContext(Dispatchers.IO) {
        val normalized = query.trim().lowercase().take(60)
        if(normalized.length < 2) return@withContext emptyList()
        val key = "team_search_${normalized.hashCode()}"
        val old = prefs.getString(key, null)?.let {
            runCatching { gson.fromJson(it, Array<EsportsTeamSearchResult>::class.java).toList() }.getOrNull()
        }
        if(old != null && System.currentTimeMillis() - prefs.getLong("${key}_at", 0L) < 6 * 60 * 60_000L)
            return@withContext old
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val fresh = EsportsExploreParser.teamSearch(page("/search/?q=$encoded&type=teams"))
        prefs.edit().putString(key, gson.toJson(fresh)).putLong("${key}_at", System.currentTimeMillis()).apply()
        fresh
    }
    suspend fun searchPlayers(query: String): List<EsportsPlayerSearchResult> = withContext(Dispatchers.IO) {
        val normalized = query.trim().lowercase().take(60)
        if(normalized.length < 2) return@withContext emptyList()
        val key = "player_search_${normalized.hashCode()}"
        val old = prefs.getString(key, null)?.let {
            runCatching { gson.fromJson(it, Array<EsportsPlayerSearchResult>::class.java).toList() }.getOrNull()
        }
        if(old != null && System.currentTimeMillis() - prefs.getLong("${key}_at", 0L) < 6 * 60 * 60_000L)
            return@withContext old
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val fresh = EsportsExploreParser.playerSearch(page("/search/?q=$encoded&type=players"))
        prefs.edit().putString(key, gson.toJson(fresh)).putLong("${key}_at", System.currentTimeMillis()).apply()
        fresh
    }
    fun cachedTournament(event: EsportsEvent): TournamentDetail? = prefs.getString("event_v4_${event.id}", null)?.let {
        runCatching { gson.fromJson(it, TournamentDetail::class.java) }.getOrNull()
    }
    fun cachedTeam(url: String): EsportsTeam? {
        val id = Regex("^https://www\\.vlr\\.gg/team/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        return ExploreMemory.teams[id] ?: prefs.getString("team_v2_$id", null)?.let {
            runCatching { gson.fromJson(it, EsportsTeam::class.java) }.getOrNull()?.also { team -> ExploreMemory.teams[id] = team }
        }
    }
    fun cachedPlayer(url: String, period: String = "60d"): PlayerProfile? {
        val id = Regex("^https://www\\.vlr\\.gg/player/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        val key = "${id}_$period"
        return ExploreMemory.players[key] ?: (prefs.getString("player_v4_$key", null) ?: prefs.getString("player_v3_$key", null))?.let {
            runCatching { gson.fromJson(it, PlayerProfile::class.java) }.getOrNull()?.also { profile -> ExploreMemory.players[key] = profile }
        }
    }
    fun tournamentCheckedAt(event: EsportsEvent) = prefs.getLong("event_v4_${event.id}_at", 0)
    fun playerCheckedAt(url: String, period: String = "60d"): Long {
        val id = Regex("^https://www\\.vlr\\.gg/player/(\\d+)").find(url)?.groupValues?.get(1) ?: return 0
        return maxOf(prefs.getLong("player_v4_${id}_${period}_at", 0), prefs.getLong("player_v3_${id}_${period}_at", 0))
    }
    suspend fun tournament(event: EsportsEvent, force: Boolean = false): TournamentDetail = withContext(Dispatchers.IO) {
        require(event.id.matches(Regex("\\d+")))
        val key = "event_v4_${event.id}"
        val old = prefs.getString(key, null)?.let { runCatching { gson.fromJson(it, TournamentDetail::class.java) }.getOrNull() }
        if(!force && old != null && System.currentTimeMillis() - prefs.getLong("${key}_at", 0L) < 30 * 60_000L) return@withContext old
        EsportsExploreParser.tournament(event, page("/event/${event.id}")).also {
            prefs.edit().putString(key, gson.toJson(it)).putLong("${key}_at", System.currentTimeMillis()).apply()
        }
    }
    suspend fun team(url: String): EsportsTeam = withContext(Dispatchers.IO) {
        val id = Regex("^https://www\\.vlr\\.gg/team/(\\d+)").find(url)?.groupValues?.get(1) ?: error("Invalid team URL")
        val cacheKey = "team_v2_$id"
        val old = prefs.getString(cacheKey, null)?.let { runCatching { gson.fromJson(it, EsportsTeam::class.java) }.getOrNull() }
        if(old != null && System.currentTimeMillis() - prefs.getLong("${cacheKey}_at", 0L) < 3_600_000L) {
            EsportsPortraits.remember(old.players); return@withContext old
        }
        val html = page("/team/$id")
        val team = EsportsExploreParser.team(url, html)
        val history = runCatching { EsportsExploreParser.teamMatches(url, page("/team/matches/$id")) }.getOrDefault(emptyList())
        team.copy(matches = (team.matches + history).distinctBy { it.id }).also {
            EsportsPortraits.remember(it.players)
            ExploreMemory.teams[id] = it
            prefs.edit().putString(cacheKey, gson.toJson(it)).putLong("${cacheKey}_at", System.currentTimeMillis()).apply()
        }
    }
    suspend fun player(url: String, period: String = "60d", force: Boolean = false, includeMatches: Boolean = true): PlayerProfile = withContext(Dispatchers.IO) {
        val id = Regex("^https://www\\.vlr\\.gg/player/(\\d+)").find(url)?.groupValues?.get(1) ?: error("Invalid player URL")
        require(period in setOf("30d", "60d", "90d", "all"))
        val memoryKey = "${id}_$period"
        val key = "player_v4_$memoryKey"
        val old = ExploreMemory.players[memoryKey] ?: (prefs.getString(key, null) ?: prefs.getString("player_v3_$memoryKey", null))?.let {
            runCatching { gson.fromJson(it, PlayerProfile::class.java) }.getOrNull()
        }
        val matchKey = "player_matches_v4_$id"
        val cachedMatches = ExploreMemory.playerMatches[id] ?: (prefs.getString(matchKey, null) ?: prefs.getString("player_matches_v3_$id", null))?.let {
            runCatching { gson.fromJson(it, Array<PlayerMatchPerformance>::class.java).toList() }.getOrNull()
        }?.also { ExploreMemory.playerMatches[id] = it }
        val now = System.currentTimeMillis()
        val overviewAt = maxOf(prefs.getLong("${key}_at", 0L), prefs.getLong("player_v3_${memoryKey}_at", 0L))
        val matchesAt = maxOf(prefs.getLong("${matchKey}_at", 0L), prefs.getLong("player_matches_v3_${id}_at", 0L))
        val overviewFresh = !force && old != null && now - overviewAt < 30 * 60_000L
        val matchesFresh = !force && cachedMatches != null && now - matchesAt < 6 * 60 * 60_000L
        if(overviewFresh && (!includeMatches || matchesFresh)) {
            val withMatches = if(includeMatches) old.copy(matches = cachedMatches.orEmpty()) else old
            val enriched = if(withMatches.player.image == null) withMatches.copy(player = withMatches.player.copy(image = EsportsPortraits[url])) else withMatches
            EsportsPortraits.remember(enriched.player); return@withContext enriched
        }
        val base = if(overviewFresh) old else EsportsExploreParser.player(url, page("/player/$id/?timespan=$period"), period = period)
        val matches = if(!includeMatches) cachedMatches ?: base.matches else if(matchesFresh) cachedMatches.orEmpty() else runCatching {
            val first = page("/player/matches/$id")
            val maxPage = Jsoup.parse(first, "https://www.vlr.gg").select("a[href*=page=]")
                .mapNotNull { Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull()?.coerceIn(1, 5) ?: 1
            val pages = coroutineScope { (2..maxPage).map { number -> async { page("/player/matches/$id/?page=$number") } }.awaitAll() }
            (listOf(first) + pages).flatMap(EsportsExploreParser::playerMatches).distinctBy { it.id }
        }.getOrElse { cachedMatches.orEmpty() }.also { fresh ->
            ExploreMemory.playerMatches[id] = fresh
            prefs.edit().putString(matchKey, gson.toJson(fresh)).putLong("${matchKey}_at", System.currentTimeMillis()).apply()
        }
        base.copy(matches = matches).let { loaded ->
            if(loaded.player.image == null) loaded.copy(player = loaded.player.copy(image = EsportsPortraits[url])) else loaded
        }.also {
            EsportsPortraits.remember(it.player)
            ExploreMemory.players[memoryKey] = it
            prefs.edit().putString(key, gson.toJson(it)).putLong("${key}_at", System.currentTimeMillis()).apply()
        }
    }
}

private object ExploreMemory {
    @Volatile var events: List<EsportsEvent>? = null
    @Volatile var news: List<EsportsNews>? = null
    val teams = ConcurrentHashMap<String, EsportsTeam>()
    val players = ConcurrentHashMap<String, PlayerProfile>()
    val playerMatches = ConcurrentHashMap<String, List<PlayerMatchPerformance>>()
}
