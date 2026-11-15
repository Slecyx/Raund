package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson

class SeriesAndWatchLaterTest {
    private fun skin(id: String, theme: String? = "theme", name: String = "Same Prefix Vandal") =
        StoreOffer(id, name, "Same Prefix", WeaponType.VANDAL, "", -1, emptyList(), skinThemeId = theme)
    private val match = EsportsMatch("123", "Alpha", "Beta", 2, 1, "Champions", "Upper final", "result", "Yesterday", "https://evil.example/", team1Logo = "https://evil.example/logo")

    @Test fun seriesCountsDistinctRootsNotLevelsOrRepeatedCatalogRows() {
        val a = skin("a"); val b = skin("b"); val c = skin("c")
        val progress = SkinSeriesProgress.group(listOf(a, a, b, c), listOf(a, a), mapOf("theme" to "Reaver")).single()
        assertEquals(3, progress.total)
        assertEquals(1, progress.owned.size)
        assertEquals(listOf("b", "c"), progress.missing.map { it.id })
    }
    @Test fun equalNamesCannotInventBundleMembership() {
        val all = listOf(skin("a"), skin("b"), skin("c", "other"), skin("d", null))
        val progress = SkinSeriesProgress.group(all, listOf(all.first()), mapOf("theme" to "Theme")).single()
        assertEquals(2, progress.total)
    }
    @Test fun unknownThemesAndPendingOwnershipDoNotClaimACompletedSeries() {
        val a = skin("a"); val b = skin("b")
        assertTrue(SkinSeriesProgress.group(listOf(a, b), listOf(a), emptyMap()).isEmpty())
        val group = SkinSeriesProgress.group(listOf(a, b), listOf(a, b.copy(metadataPending = true)), mapOf("theme" to "Theme")).single()
        assertEquals(1, group.missing.size)
    }
    @Test fun completeKnownSeriesHasNoMissingSkins() {
        val all = listOf(skin("a"), skin("b"))
        val group = SkinSeriesProgress.group(all, all, mapOf("theme" to "Theme")).single()
        assertTrue(group.missing.isEmpty())
    }
    @Test fun queueNeverPersistsScoresWinnerStageOrUntrustedLinks() {
        val entry = WatchLater.sanitize(listOf(WatchLaterEntry(match, 10))).single()
        assertNull(entry.match.score1); assertNull(entry.match.score2)
        assertEquals("", entry.match.series)
        assertNull(entry.match.team1Logo)
        assertEquals("https://www.vlr.gg/123", entry.match.url)
    }
    @Test fun queueBoundsDeduplicatesAndRejectsInvalidMatchIds() {
        val invalid = WatchLaterEntry(match.copy(id = "../attack"), 10)
        val many = (1..120).map { WatchLaterEntry(match.copy(id = it.toString()), 10) }
        val safe = WatchLater.sanitize(listOf(invalid) + many + many)
        assertEquals(100, safe.size)
        assertEquals(100, safe.map { it.match.id }.distinct().size)
    }
    @Test fun watchedStateSurvivesExportWithoutReintroducingScores() {
        val entries = listOf(WatchLaterEntry(match, 100, true))
        val decoded = WatchLater.decode(Gson().toJson(entries)).single()
        assertTrue(decoded.watched)
        assertNull(decoded.match.score1)
        assertEquals(100L, decoded.savedAt)
    }
    @Test fun widgetCacheDoesNotReuseAFourHourStoreSnapshot() {
        assertEquals(30 * 60_000L, StoreBackgroundRefresh.cacheAgeMillis(false, true))
        assertEquals(4 * 60 * 60_000L, StoreBackgroundRefresh.cacheAgeMillis(false, false))
        assertEquals(60_000L, StoreBackgroundRefresh.cacheAgeMillis(true, true))
    }
}
