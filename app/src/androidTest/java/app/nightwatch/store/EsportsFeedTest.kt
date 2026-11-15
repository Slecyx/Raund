package app.nightwatch.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EsportsFeedTest {
    @Test fun completedMatchProvidesInAppPlayerScoreboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_esports") == "true")
        val match = EsportsMatch("743596", "Gen.G GC", "Rising Esports GC", 2, 0,
            "Game Changers 2026: Pacific", "Upper Quarterfinals", "result", "", "https://www.vlr.gg/743596")
        val detail = kotlinx.coroutines.runBlocking { EsportsRepository(instrumentation.targetContext).details(match) }
        assertTrue("No player rows in match detail", detail.players.orEmpty().size >= 10)
        assertTrue(detail.players.orEmpty().all { it.name.isNotBlank() && it.acs.isNotBlank() })
        assertTrue("No map scores in completed match", detail.mapResults.orEmpty().size >= 2)
        assertTrue("Map names or scores missing", detail.mapResults.orEmpty().all { it.name.isNotBlank() && it.score1 != null && it.score2 != null })
        assertTrue("No KAST player stats", detail.players.orEmpty().any { !it.kast.isNullOrBlank() })
    }
    @Test fun publicMatchPageCanPopulateTheFeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_esports") == "true")
        val context = instrumentation.targetContext
        val feed = kotlinx.coroutines.runBlocking { EsportsRepository(context).load(force = true) }
        assertTrue(feed.live.isNotEmpty() || feed.upcoming.isNotEmpty() || feed.results.isNotEmpty())
        assertTrue((feed.live + feed.upcoming + feed.results).all { it.url.startsWith("https://www.vlr.gg/") })
        feed.upcoming.firstOrNull()?.let { upcoming ->
            val detail = kotlinx.coroutines.runBlocking { EsportsRepository(context).details(upcoming) }
            assertEquals(upcoming.id, detail.match.id)
            assertNotNull("Upcoming match must expose a timezone-aware start", detail.match.startAtMillis)
            assertTrue(detail.match.startAtMillis!! > System.currentTimeMillis())
        }
    }
}
