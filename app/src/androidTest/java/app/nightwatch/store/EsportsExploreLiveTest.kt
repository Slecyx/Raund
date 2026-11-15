package app.nightwatch.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EsportsExploreLiveTest {
    @Test fun eventBracketRosterPlayerAndNewsAreReadable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_esports") == "true")
        val repo = EsportsExploreRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val events = runBlocking { repo.events(force = true) }
        val news = runBlocking { repo.news(force = true) }
        assertTrue(events.isNotEmpty())
        assertTrue(news.isNotEmpty())
        val event = events.first()
        val tournament = runBlocking { repo.tournament(event) }
        assertTrue(tournament.event.title.isNotBlank())
        val teamUrl = tournament.teams.firstOrNull()?.second
        if(teamUrl != null) {
            val team = runBlocking { repo.team(teamUrl) }
            assertTrue(team.name.isNotBlank())
            assertTrue("Team match history was empty", team.matches.isNotEmpty())
            team.players.firstOrNull()?.let { player ->
                val profile = runBlocking { repo.player(player.url) }
                assertTrue(profile.player.name.isNotBlank())
                assertTrue("Player stats were empty", profile.agents.isNotEmpty())
            }
        }
    }
}
