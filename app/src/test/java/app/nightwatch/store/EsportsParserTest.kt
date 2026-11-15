package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test

class EsportsParserTest {
    private val html = """
        <div class="wf-label mod-large">Sun, September 20, 2026</div><div class="wf-card">
          <a href="/123/a-vs-b" class="match-item">
            <div class="match-item-time">11:00 AM</div>
            <div class="match-item-vs">
              <div class="match-item-vs-team"><img src="https://owcdn.net/alpha.png"><div class="match-item-vs-team-name"><div class="text-of">Alpha</div></div><div class="match-item-vs-team-score">1</div></div>
              <div class="match-item-vs-team"><div class="match-item-vs-team-name"><div class="text-of">Beta</div></div><div class="match-item-vs-team-score">2</div></div>
            </div><div class="ml-status">LIVE</div>
            <div class="match-item-event"><div class="match-item-event-series">Final</div>Champions</div>
          </a>
          <a href="/124/c-vs-d" class="match-item">
            <div class="match-item-vs">
              <div class="match-item-vs-team"><div class="match-item-vs-team-name"><div class="text-of">Gamma</div></div><div class="match-item-vs-team-score mod-upcoming">–</div></div>
              <div class="match-item-vs-team"><div class="match-item-vs-team-name"><div class="text-of">Delta</div></div><div class="match-item-vs-team-score mod-upcoming">–</div></div>
            </div><div class="ml-status">Upcoming</div>
          </a>
        </div>
    """.trimIndent()

    @Test fun liveScoreAndSourceAreParsedWithoutInventingData() {
        val parsed = EsportsParser.parse(html, "upcoming")
        assertEquals(2, parsed.size)
        assertEquals("live", parsed[0].status)
        assertEquals("1 - 2", parsed[0].score)
        assertEquals("Champions", parsed[0].event)
        assertEquals("Final", parsed[0].series)
        assertEquals("https://www.vlr.gg/123/a-vs-b", parsed[0].url)
        assertEquals("https://owcdn.net/alpha.png", parsed[0].team1Logo)
        assertEquals("upcoming", parsed[1].status)
        assertNull(parsed[1].score1)
        assertEquals("—", parsed[1].score)
    }

    @Test fun malformedLinksAndMissingTeamsAreExcluded() {
        assertTrue(EsportsParser.parse("<a href='https://elsewhere/10/a' class='match-item'></a>","live").isEmpty())
        assertTrue(EsportsParser.parse("<a href='/5/a' class='match-item'><div class='match-item-vs-team'></div></a>","live").isEmpty())
    }

    @Test fun matchDetailUsesDisplayedTimezoneForCountdown() {
        val match = EsportsParser.parse(html, "upcoming")[0]
        val detail = EsportsParser.detail(match, """<div class='match-header-date'>
          <div class='moment-tz-convert' data-utc-ts='2026-09-24 05:00:00'>Thursday, September 24</div>
          <div class='moment-tz-convert'>12:00 PM +03</div></div>
          <a class='match-header-link' href='/team/1/alpha'><img src='https://owcdn.net/alpha.png'></a>
          <a class='match-header-link' href='/team/2/beta'><img src='https://owcdn.net/beta.png'></a>
          <div class='match-header-vs-note'>Bo3</div>
          <div class='vm-stats-gamesnav-item'>1 Ascent</div>
          <div class='match-streams'><a href='https://www.youtube.com/watch?v=test'>VCT (YT)</a></div>""")
        assertEquals(java.time.Instant.parse("2026-09-24T09:00:00Z").toEpochMilli(), detail.match.startAtMillis)
        assertEquals("Bo3", detail.format)
        assertEquals("https://owcdn.net/alpha.png", detail.match.team1Logo)
        assertEquals("https://www.vlr.gg/team/2/beta", detail.team2Url)
        assertEquals(listOf("1 Ascent"), detail.maps)
        assertEquals("VCT (YT)", detail.streams[0].first)
    }

    @Test fun countdownUsesDaysHoursAndMinutesWithoutRoundingToZero() {
        val now = 1_000_000L
        assertEquals("1g 2s 3dk", esportsCountdown(now + 93_780_000L, now))
        assertEquals("00:01", esportsCountdown(now + 1L, now))
        assertEquals("00:00", esportsCountdown(now - 1L, now))
    }

    @Test fun sourceDateUsesAppLanguageWithoutChangingDisplayedClock() {
        assertEquals("24 Eyl 2026 · 12:00", esportsDisplayWhen("Thu, September 24, 2026 · 12:00 PM", true))
        assertEquals("Thu, September 24, 2026 · 12:00 PM", esportsDisplayWhen("Thu, September 24, 2026 · 12:00 PM", false))
        assertEquals("TBD", esportsDisplayWhen("TBD", true))
    }

    @Test fun matchDaysAreGroupedInTheSelectedAppLanguage() {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.Instant.parse("2026-09-25T10:00:00Z").toEpochMilli()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayMillis = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val tomorrowMillis = today.plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Bugün", esportsDaySection(todayMillis, turkish = true, now = now))
        assertEquals("Tomorrow", esportsDaySection(tomorrowMillis, turkish = false, now = now))
        assertEquals("TBD", esportsDaySection(null, "TBD · unknown", true, now))
    }

    @Test fun matchDetailReadsPlayerScoreboard() {
        val match = EsportsMatch("123", "A", "B", 2, 1, "Event", "Final", "result", "", "https://www.vlr.gg/123/x")
        val detail = EsportsParser.detail(match, """<div class='vm-stats-game' data-game-id='all'>
          <div class='ovw-table'><div class='ovw-row'><div class='ovw-player'><a href='/player/8/alias'><div class='ovw-player-name'>alias</div></a></div>
          <div class='ovw-agents'><img src='/img/vlr/game/agents/omen.png'></div>
          <div class='ovw-cell' data-col='acs'><span class='side mod-both'>210</span></div>
          <span class='ovw-kda-stat' data-col='kills'><span class='side mod-both'>20</span></span>
          <span class='ovw-kda-stat' data-col='deaths'><span class='side mod-both'>10</span></span></div></div>
          <div class='ovw-table'><div class='ovw-row'><div class='ovw-player-name'>other</div></div></div>
          </div>""")
        assertEquals(2, detail.players?.size)
        assertEquals("A", detail.players?.get(0)?.team)
        assertEquals("210", detail.players?.get(0)?.acs)
        assertEquals("https://www.vlr.gg/player/8/alias", detail.players?.get(0)?.playerUrl)
        assertEquals("B", detail.players?.get(1)?.team)
    }

    @Test fun completedMatchKeepsMapScoresVetoAndVod() {
        val match = EsportsMatch("123", "A", "B", 2, 1, "Event", "Final", "result", "", "https://www.vlr.gg/123/x")
        val detail = EsportsParser.detail(match, """
            <div class='match-header-date'><div>Patch 11.08</div></div>
            <div class='match-header-vs-score'><div class='sp-hide'><span class='match-header-vs-score-winner'>3</span><span>:</span><span class='match-header-vs-score-loser'>1</span></div></div>
            <div class='match-header-note'>A ban Bind; B pick Haven</div>
            <div class='vm-stats-game' data-game-id='1'><div class='vm-stats-game-header'>
              <div class='team'><div class='score'>13</div></div><div class='map'>Haven</div>
              <div class='team'><div class='score'>7</div></div><div class='map-duration'>48:30</div>
            </div></div>
            <div class='match-vods'><a href='https://www.youtube.com/watch?v=test'>VOD</a></div>
        """)
        assertEquals("Patch 11.08", detail.patch)
        assertEquals("A ban Bind; B pick Haven", detail.veto)
        assertEquals("Haven", detail.mapResults?.single()?.name)
        assertEquals(13, detail.mapResults?.single()?.score1)
        assertEquals(7, detail.mapResults?.single()?.score2)
        assertEquals("VOD", detail.vods?.single()?.first)
        assertEquals("3 - 1", detail.match.score)
        assertEquals("result", detail.match.status)
    }

    @Test fun seriesScoreDoesNotFinishALiveMatchBeforeVlrMarksItFinal() {
        val live = EsportsMatch("55", "Alpha", "Beta", 1, 0, "Event", "Round", "live", "", "https://www.vlr.gg/55/x")
        val stillLive = EsportsParser.detail(live, """
            <div class='match-header-vs-score'>
              <div class='match-header-vs-note'>live</div>
              <div class='sp-hide'><span>1</span><span>:</span><span>0</span></div>
            </div>
        """).match
        assertEquals("live", stillLive.status)
        val final = EsportsParser.detail(live, """
            <div class='match-header-vs-score'>
              <div class='match-header-vs-note'>final</div>
              <div class='sp-hide'><span>2</span><span>:</span><span>0</span></div>
            </div>
        """).match
        assertEquals("result", final.status)
        assertEquals("2 - 0", final.score)
    }

    @Test fun perMapAgentsStatsAndSideScoresAreParsed() {
        val match = EsportsMatch("123", "Alpha", "Beta", 1, 0, "Event", "Final", "result", "", "https://www.vlr.gg/123/x")
        val detail = EsportsParser.detail(match, """
          <div class='vm-stats-game' data-game-id='1'>
            <div class='vm-stats-game-header'>
              <div class='team'><div class='score'>13</div><span class='mod-t'>7</span><span class='mod-ct'>6</span></div>
              <div class='map'>Lotus <span class='picked mod-1'>PICK</span></div>
              <div class='team'><div class='score'>8</div><span class='mod-ct'>5</span><span class='mod-t'>3</span></div>
            </div>
            <div class='ovw-table'><div class='ovw-row'><div class='ovw-player-name'>one</div>
              <div class='ovw-agents'><img src='https://owcdn.net/jett.png'></div>
              <div class='ovw-cell' data-col='acs'><span class='side mod-both'>250</span></div></div></div>
            <div class='ovw-table'><div class='ovw-row'><div class='ovw-player-name'>two</div>
              <div class='ovw-agents'><img src='https://owcdn.net/sova.png'></div></div></div>
          </div>""")
        val map = detail.mapResults!!.single()
        assertEquals(7, map.attack1)
        assertEquals(6, map.defense1)
        assertEquals(3, map.attack2)
        assertEquals(5, map.defense2)
        assertEquals(listOf("one", "two"), detail.mapPlayers["Lotus"]!!.map { it.name })
        assertEquals("250", detail.mapPlayers["Lotus"]!!.first().acs)
    }
}
