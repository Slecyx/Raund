package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CompanionTest {
    private val offer = StoreOffer("skin-level-id", "Test Vandal", "Test", WeaponType.VANDAL, "", 1775, emptyList())
    private val future = System.currentTimeMillis()/1000 + 3600
    @Test fun favoriteMatchingDeduplicatesDailyAndBundleOffers() {
        val store = StoreSnapshot(listOf(offer), future, false, false, bundles = listOf(StoreBundle("bundle","Bundle",null,1775,future,listOf(offer))))
        assertEquals(listOf(offer), FavoriteAlerts.matches(store, setOf(offer.id)))
        assertTrue(FavoriteAlerts.matches(store, setOf("unrelated")).isEmpty())
    }
    @Test fun previewsAndExpiredOffersNeverTriggerAlerts() {
        val store = StoreSnapshot(listOf(offer), future, true, false)
        assertTrue(FavoriteAlerts.matches(store, setOf(offer.id)).isEmpty())
        assertTrue(FavoriteAlerts.matches(store.copy(isPreview = false, refreshEpochSeconds = 1), setOf(offer.id)).isEmpty())
    }
    @Test fun damageAndHeadshotMetricsUseOnlySelectedPlayer() {
        val raw = JSONObject("""{"players":[{"subject":"me","stats":{"roundsPlayed":2,"score":450}}],"roundResults":[{"playerStats":[{"subject":"me","damage":[{"damage":200,"headshots":2,"bodyshots":1,"legshots":1}]},{"subject":"other","damage":[{"damage":1000,"headshots":100}]}]},{"playerStats":[{"subject":"me","damage":[{"damage":100,"headshots":0,"bodyshots":1,"legshots":0}]}]}]}""")
        val result = MatchAnalysis.metrics(raw, "me")
        assertEquals(225, result.acs)
        assertEquals(150, result.adr)
        assertEquals(40, result.headshotPercent)
        assertEquals(300, result.damage)
    }
    @Test fun missingHitDataIsUnavailableRatherThanZeroPercent() {
        assertNull(MatchAnalysis.metrics(JSONObject("{}"), "me").headshotPercent)
        assertEquals(0, MatchAnalysis.metrics(JSONObject("{}"), "me").rounds)
    }
}
