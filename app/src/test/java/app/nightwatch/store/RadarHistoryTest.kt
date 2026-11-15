package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test

class RadarHistoryTest {
    private val offer = StoreOffer("one", "One", "", WeaponType.VANDAL, "", 1775, emptyList())
    private fun snapshot(expiry: Long = 5000, offers: List<StoreOffer> = listOf(offer)) = StoreSnapshot(offers, expiry, false, false)

    @Test fun repeatedRefreshWithRoundingDriftKeepsOneRotationAndFirstObservation() {
        val first = RadarHistory.record(emptyList(), snapshot(), 1000)
        val next = RadarHistory.record(first, snapshot(5002), 2000)
        assertEquals(1, next.size)
        assertEquals(1000L, next.single().observedAt)
        assertEquals(5000L, next.single().expiresAt)
    }
    @Test fun expiredEmptyAndPreviewSnapshotsNeverCreateHistory() {
        assertTrue(RadarHistory.record(emptyList(), snapshot(1000), 1000).isEmpty())
        assertTrue(RadarHistory.record(emptyList(), snapshot(offers = emptyList()), 1000).isEmpty())
        assertTrue(RadarHistory.record(emptyList(), snapshot().copy(isPreview = true), 1000).isEmpty())
    }
    @Test fun lateResponsesCannotReplaceNewerRotationsAndHistoryIsBounded() {
        var history = emptyList<StoreObservation>()
        repeat(40) { i -> history = RadarHistory.record(history, snapshot(5000L+i*86400), 1000L+i*86400) }
        assertEquals(30, history.size)
        assertEquals(history, RadarHistory.record(history, snapshot(), 1000))
        assertEquals(5000L+10*86400, history.first().expiresAt)
    }
    @Test fun diffUsesSkinIdsNotNamesAndKeepsRecurringSkins() {
        val before = StoreObservation(5000, 1000, listOf(offer, offer.copy(id="left")))
        val after = StoreObservation(95000, 91000, listOf(offer.copy(name="Localized name"), offer.copy(id="new")))
        val changes = RadarHistory.changes(before, after)
        assertEquals(listOf("new"), changes.arrived.map { it.id })
        assertEquals(listOf("left"), changes.left.map { it.id })
    }
    @Test fun accountHistoriesAreSeparatedWithoutPlaintextIdentifiers() {
        val key = RadarHistory.accountKey("Player#EU", "eu")
        assertEquals(key, RadarHistory.accountKey("Player#EU", "eu"))
        assertNotEquals(key, RadarHistory.accountKey("Player#EU", "na"))
        assertNotEquals(key, RadarHistory.accountKey("Other#EU", "eu"))
        assertEquals(64, key.length)
        assertFalse(key.contains("Player"))
    }
    @Test fun budgetUsesLowestActiveIndividualVpPriceAndExcludesBundles() {
        val store = snapshot().copy(nightMarketAvailable = true,
            nightOffers = listOf(offer.copy(priceVp=900), offer.copy(id="kc",currency="KC"), offer.copy(id="unknown",priceVp=-1)),
            bundles = listOf(StoreBundle("b","Bundle",null,100,5000,listOf(offer.copy(id="bundle")))))
        val ids = setOf("one","kc","unknown","bundle")
        assertEquals(listOf(900), RadarHistory.purchasableFavorites(store, ids, 1000).map { it.priceVp })
        assertTrue(RadarHistory.purchasableFavorites(store, ids, 5000).isEmpty())
        assertTrue(RadarHistory.purchasableFavorites(store.copy(isPreview=true), ids, 1000).isEmpty())
        assertEquals(1775, RadarHistory.purchasableFavorites(store.copy(nightMarketAvailable=false), ids, 1000).single().priceVp)
    }
}
