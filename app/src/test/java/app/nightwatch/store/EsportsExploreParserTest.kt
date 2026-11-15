package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test

class EsportsExploreParserTest {
    @Test fun teamSearchReturnsDirectTeamProfiles() {
        val rows = EsportsExploreParser.teamSearch("""<a class='wf-module-item search-item' href='/search/r/team/2593/idx'>
          <div class='search-item-thumb'><img src='//owcdn.net/fnatic.png'></div>
          <div class='search-item-title'>FNATIC</div></a>""")
        assertEquals(1, rows.size)
        assertEquals("FNATIC", rows.single().name)
        assertEquals("https://www.vlr.gg/team/2593", rows.single().url)
        assertEquals("https://owcdn.net/fnatic.png", rows.single().image)
    }

    @Test fun playerSearchReturnsDirectPlayerProfiles() {
        val rows = EsportsExploreParser.playerSearch("""<a class='search-item' href='/search/r/player/9/idx'>
          <div class='search-item-thumb'><img src='//owcdn.net/p.png'></div><div class='search-item-title'>alfajer</div>
          <div class='search-item-desc'>FNATIC</div></a>""")
        assertEquals("alfajer", rows.single().name)
        assertEquals("https://www.vlr.gg/player/9", rows.single().url)
        assertEquals("FNATIC", rows.single().description)
    }

    @Test fun standingsAreCalculatedFromFinishedMatches() {
        fun result(id: String, a: String, b: String, one: Int, two: Int) =
            EsportsMatch(id, a, b, one, two, "Event", "Group", "result", "", "https://www.vlr.gg/$id/x")
        val rows = tournamentStandings(listOf(result("1", "Alpha", "Beta", 2, 0), result("2", "Alpha", "Gamma", 1, 2)))
        assertEquals("Alpha", rows.first().team)
        assertEquals(2, rows.first().played)
        assertEquals(1, rows.first().wins)
        assertEquals(1, rows.first().mapDifference)
    }
    @Test fun eventAndNewsListsRequireSourceLinks() {
        val events = EsportsExploreParser.events("""<a class='event-item' href='/event/9/champions'>
          <div class='event-item-title'>Champions</div><div class='event-item-desc-item-status'>ongoing</div>
          <div class='event-item-desc-item mod-dates'>Sep 1—20<div>Dates</div></div>
          <div class='event-item-thumb'><img src='//owcdn.net/event.png'></div></a>""")
        assertEquals(1, events.size)
        assertEquals("https://owcdn.net/event.png", events[0].image)
        assertEquals("9", events[0].id)
        val news = EsportsExploreParser.news("""<a class='wf-module-item' href='/123/player-moves'>
          <div><div style='font-weight: 700; font-size: 15px'>Player moves</div>
          <div style='font-size: 13px'>Transfer update</div><div class='ge-text-light'>Sep 19, 2026</div></div></a>""")
        assertEquals("Player moves", news[0].title)
        assertEquals("Transfer update", news[0].summary)
    }

    @Test fun bracketOnlyMarksLowerBracketLoserEliminated() {
        val event = EsportsEvent("9", "Champions", "https://www.vlr.gg/event/9", null, "ongoing", "")
        val detail = EsportsExploreParser.tournament(event, """<h1 class='event-header-main-title'>Champions</h1>
          <div class='event-brackets-container'><div class='bracket-container mod-lower'><div class='bracket-col'>
          <div class='bracket-col-label'>Lower Final</div><a class='bracket-item' href='/123/a-vs-b'>
          <div class='bracket-item-team'><div class='bracket-item-team-name'><img src='//owcdn.net/a.png'><span>A</span></div><div class='bracket-item-team-score'>2</div></div>
          <div class='bracket-item-team'><div class='bracket-item-team-name'><img src='//owcdn.net/b.png'><span>B</span></div><div class='bracket-item-team-score'>1</div></div>
          </a></div></div></div>""")
        assertEquals(1, detail.bracket.size)
        assertEquals("B", detail.bracket[0].eliminated)
        assertEquals("https://owcdn.net/a.png", detail.bracket[0].logo1)
        assertEquals("2 - 1", detail.matches.single().score)
        assertEquals("Lower Final", detail.matches.single().series)
    }

    @Test fun teamAndPlayerProfilesKeepActualPortraitsAndStats() {
        val team = EsportsExploreParser.team("https://www.vlr.gg/team/9", """<div class='team-header-logo'><img src='//owcdn.net/team.png'></div>
          <div class='team-header-name'><h1>Team A</h1></div><div class='team-roster-item'><a href='/player/8/alias'>
          <div class='team-roster-item-img'><img src='//owcdn.net/person.png'></div>
          <div class='team-roster-item-name-alias'>alias</div><div class='team-roster-item-name-real'>Real Name</div></a></div>""")
        assertEquals("Team A", team.name)
        assertEquals("alias", team.players[0].name)
        assertEquals("https://owcdn.net/person.png", team.players[0].image)
        val profile = EsportsExploreParser.player("https://www.vlr.gg/player/8", """<div class='player-header'>
          <div class='wf-avatar'><img src='//owcdn.net/person.png'></div><h1>alias</h1>
          <h2 class='player-real-name'>Real Name</h2><div class='ge-text-light'><i class='flag'></i>COUNTRY</div></div>
          <table class='st-table mod-agent-rows'><tbody><tr><td><img alt='Cypher' src='/cypher.png'></td>
          <td>50%</td><td>100</td><td>1.10</td><td>210</td></tr></tbody></table>""")
        assertEquals("COUNTRY", profile.country)
        assertEquals("Cypher", profile.agents[0].name)
        assertEquals("210", profile.agents[0].acs)
    }

    @Test fun teamHistoryKeepsScoresAndUpcomingMatches() {
        val rows = EsportsExploreParser.teamMatches("https://www.vlr.gg/team/14/t1", """<div class='team-summary-container'>
          <a class='m-item' href='/123/t1-vs-a'><div class='m-item-event'><div>Champions</div>Group</div>
          <span class='m-item-team-name'>T1</span><span class='m-item-team-name'>A</span>
          <div class='m-item-logo'><img src='//owcdn.net/t1.png'></div><div class='m-item-logo'><img src='//owcdn.net/a.png'></div>
          <div class='m-item-result mod-tbd'></div><div class='m-item-date'>2026/09/27 12:00 pm</div></a>
          <a class='m-item' href='/122/t1-vs-b'><div class='m-item-event'><div>Pacific</div>Final</div>
          <span class='m-item-team-name'>T1</span><span class='m-item-team-name'>B</span>
          <div class='m-item-result'><span>2</span><span>1</span></div></a></div>""")
        assertEquals(2, rows.size)
        assertEquals("upcoming", rows[0].status)
        assertEquals("https://owcdn.net/t1.png", rows[0].team1Logo)
        assertEquals("2 - 1", rows[1].score)
    }

    @Test fun tournamentIncludesOverviewStagesAndSchedule() {
        val event = EsportsEvent("9", "Champions", "https://www.vlr.gg/event/9/champions", null, "ongoing", "")
        val detail = EsportsExploreParser.tournament(event, """<div class='event-header-main'>
          <h1 class='event-header-main-title'>Champions</h1><h2 class='event-header-main-desc'>World championship</h2>
          <div class='event-header-main-meta'><div><div class='label'>Prize</div><div class='value'>$2,000,000</div></div></div></div>
          <a class='wf-subnav-item'><div class='ge-text-light'>Sep 1–10</div><div class='wf-subnav-item-title'>Playoffs</div></a>
          <a class='wf-card event-group-series-match mod-dark' href='/123/a-vs-b'><div>Sep 3 8:00 pm</div>
          <div class='team'><div class='team-name'>A</div><div class='team-img'><img src='//owcdn.net/a.png'></div></div>
          <div class='score'><div class='score-left'>2</div><div class='score-right'>1</div></div>
          <div class='team'><div class='team-name'>B</div><div class='team-img'><img src='//owcdn.net/b.png'></div></div>
          <span class='ss-name mod-full'>Final</span></a>""")
        assertEquals("World championship", detail.description)
        assertEquals("$2,000,000", detail.prize)
        assertEquals(listOf("Sep 1–10 · Playoffs"), detail.stages)
        assertEquals("A", detail.matches.single().team1)
        assertEquals("2 - 1", detail.matches.single().score)
    }

    @Test fun tournamentAcceptsPrizePoolLabelAndRelativePortraits() {
        val event = EsportsEvent("9", "Cup", "https://www.vlr.gg/event/9/cup", null, "ongoing", "")
        val detail = EsportsExploreParser.tournament(event, """<div class='event-header-main-meta'><div>
          <div class='label'>Prize Pool</div><div class='value'>€250,000</div></div></div>""")
        assertEquals("€250,000", detail.prize)
        val profile = EsportsExploreParser.player("https://www.vlr.gg/player/8", """<div class='player-header'>
          <div class='wf-avatar'><img src='/players/eight.png'></div><h1>Eight</h1></div>""")
        assertEquals("https://www.vlr.gg/players/eight.png", profile.player.image)
    }

    @Test fun playerDossierParsesSocialsDetailedAgentsPlacementsAndMapForm() {
        val overview = """<div class='player-header'><div class='wf-avatar'><img src='//owcdn.net/p.png'></div>
          <h1>ace</h1><h2 class='player-real-name'>A Player</h2><a target='_blank' href='https://x.com/ace'>@ace</a>
          <a target='_blank' href='file:///private'>bad</a><div class='ge-text-light'><i class='flag'></i>TR</div></div>
          <table class='st-table mod-agent-rows'><tbody><tr><td><img alt='Jett' src='/jett.png'></td>
          <td>60% 6</td><td>120</td><td>1.20</td><td>245</td><td>1.30</td><td>74%</td><td>160</td>
          <td>0.90</td><td>0.30</td><td>8:4</td><td>60</td><td>46</td><td>20</td><td>8</td><td>4</td></tr></tbody></table>
          <div class='player-summary-container-2'><a class='wf-module-item' href='/444/ace-wins' title='Ace wins the final'>
          <div class='ge-text-light'>2026/01/02</div></a>
          <a class='wf-module-item player-event-item' href='/event/44/cup'><div><div class='text-of'>World Cup</div>
          <div style='margin-top: 5px'>Playoffs – 1st <b>$10,000</b> Team A</div></div><div>2026</div></a></div>
          <h2 class='wf-label'>Current Teams</h2><div class='wf-card'><a href='/team/9/team-a'><img src='//owcdn.net/a.png'>
          <div style='flex: 1'><div>Team A</div><div class='ge-text-light'>joined in May 2022</div></div></a></div>
          <h2 class='wf-label'>Past Teams</h2><div class='wf-card'><a href='/team/8/team-old'><img src='//owcdn.net/old.png'>
          <div style='flex: 1'><div>Team Old</div><div class='ge-text-light'>2020 – 2022</div><div class='ge-text-light'>Inactive from April</div></div></a></div>
          <div><div class='wf-module-label'>Total Winnings</div><span>$123,456</span></div>"""
        val matches = """<a class='wf-card fc-flex m-item' href='/123/a-vs-b'><div class='m-item-event'><div>Champions</div>Final</div>
          <span class='m-item-team-name'>Team A</span><span class='m-item-team-name'>Team B</span>
          <div class='m-item-logo'><img src='//owcdn.net/a.png'></div><div class='m-item-result mod-win' data-match-id='99'><span>2</span><span>1</span></div>
          <div class='m-item-logo'><img src='//owcdn.net/b.png'></div><div class='m-item-date'>2026/01/02</div></a>
          <div class='m-item-games match-id-99'><a class='m-item-games-item' href='/123/a-vs-b/?game=1'>
          <div class='m-item-games-comp-group'><img alt='Jett' src='/jett.png'></div><div class='m-item-games-result mod-win'><div class='map'>Ascent</div><div class='score'>13-8</div></div>
          <div class='pg-stat mod-r' title='Rating 2.0'><div class='pg-v'>1.42</div></div>
          <div class='pg-stat' title='Kills / deaths / assists'><div class='pg-v'>24/12/5</div></div></a></div>"""
        val profile = EsportsExploreParser.player("https://www.vlr.gg/player/8/ace", overview, matches, "90d")
        assertEquals("90d", profile.period)
        assertEquals(listOf("https://x.com/ace"), profile.socials.map { it.url })
        assertEquals("120", profile.agents.single().rounds)
        assertEquals("74%", profile.agents.single().kast)
        assertEquals("20", profile.agents.single().assists)
        assertEquals("World Cup", profile.achievements.single().event)
        assertEquals("Ace wins the final", profile.news.single().title)
        assertTrue(profile.achievements.single().results.single().contains("1st"))
        assertEquals("2 - 1", profile.matches.single().asMatch().score)
        assertEquals("Ascent", profile.matches.single().maps.single().map)
        assertEquals(24, profile.matches.single().maps.single().kills)
        assertEquals("1.42", profile.matches.single().maps.single().rating)
        assertEquals("$123,456", profile.totalWinnings)
        assertEquals(listOf("Team A", "Team Old"), profile.teams.map { it.name })
        assertTrue(profile.teams.first().current)
        assertEquals("2020 – 2022", profile.teams.last().period)
    }

    @Test fun playerAnalyticsWeightsAgentStatsAndUsesPublishedMatchFormOnly() {
        val agents = listOf(
            PlayerAgent("Jett", null, "60% 6", "1.20", "240", kills = "60", deaths = "40", rounds = "100", assists = "20"),
            PlayerAgent("Sage", null, "40% 4", "0.80", "160", kills = "20", deaths = "30", rounds = "50", assists = "30"))
        fun match(id: String, won: Boolean, rating: String) = PlayerMatchPerformance(id, "Cup", "", "A", "B", null, null,
            if(won) 2 else 0, if(won) 0 else 2, won, "", "https://www.vlr.gg/$id",
            listOf(PlayerMapPerformance("Jett", null, "Map", "13-8", rating, 10, 8, 2, won, "https://www.vlr.gg/$id")))
        val summary = PlayerAnalytics.summary(PlayerProfile(EsportsPlayer("ace", "", null, "https://www.vlr.gg/player/8"), "TR", agents,
            matches = listOf(match("1", true, "1.30"), match("2", false, "0.70"))))
        assertEquals(80, summary.kills)
        assertEquals(50, summary.assists)
        assertEquals(2, summary.decidedMatches)
        assertEquals(1, summary.wins)
        assertEquals(1.066, summary.rating!!, .002)
        assertEquals(1.0, summary.recentRating!!, .001)
        assertEquals("Jett", summary.bestAgent)
        val profile = PlayerProfile(EsportsPlayer("ace", "", null, "https://www.vlr.gg/player/8"), "TR", agents,
            matches = listOf(match("1", true, "1.30"), match("2", false, "0.70")))
        assertEquals(2, PlayerAnalytics.mapPool(profile).single().played)
        assertEquals(2, PlayerAnalytics.opponents(profile).single().matches)
    }
}
