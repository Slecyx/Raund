package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class StoreIntegrityTest {
    private val item = StoreOffer("item", "Test", "", WeaponType.VANDAL, "", 1775, emptyList())
    @Test fun missingOrOtherCurrencyNeverBecomesFreeVp() {
        assertEquals(-1, StorePricing.cost(null))
        assertEquals(-1, StorePricing.cost(JSONObject().put(StorePricing.KC, 2500)))
        assertEquals(1775, StorePricing.cost(JSONObject().put(StorePricing.KC, 9999).put(StorePricing.VP, 1775)))
        assertEquals(0, StorePricing.cost(JSONObject().put(StorePricing.VP, 0)))
        assertEquals(-1, StorePricing.cost(JSONObject().put(StorePricing.VP, -1)))
        assertEquals(-1, StorePricing.cost(JSONObject().put(StorePricing.VP, 1.25)))
    }
    @Test fun rewardItemIdIsUsedInsteadOfAnUnrelatedOfferId() {
        val raw = JSONObject("""{"OfferID":"offer-id","Rewards":[{"ItemTypeID":"e7c63390-eda7-46e0-bb7a-a6abdacd2433","ItemID":"actual-skin-level"}]}""")
        assertEquals("actual-skin-level", StorePricing.skinId(raw))
        assertEquals("fallback", StorePricing.skinId("fallback"))
    }
    @Test fun eachStoreSectionExpiresIndependently() {
        val store = StoreSnapshot(listOf(item), 1000, false, true,
            bundles = listOf(StoreBundle("expired","Expired",null,0,999,listOf(item)), StoreBundle("active","Active",null,100,2000,listOf(item))),
            nightOffers = listOf(item), accessories = listOf(item), balances = mapOf("VP" to 500),
            nightMarketExpiresAt = 3000, accessoryExpiresAt = 4000)
        val current = StoreFreshness.active(store, 1000)
        assertTrue(current.offers.isEmpty())
        assertTrue(current.balances.isEmpty())
        assertEquals(listOf("active"), current.bundles.map { it.id })
        assertEquals(1,current.nightOffers.size)
        assertEquals(1,current.accessories.size)
        assertTrue(StoreFreshness.active(store,3000).nightOffers.isEmpty())
        assertTrue(StoreFreshness.active(store,4000).accessories.isEmpty())
        assertEquals(listOf(item), RadarHistory.purchasableFavorites(store,setOf("item"),1000))
    }
    @Test fun legacyCacheWithoutIndependentExpiryUsesConservativeDailyExpiry() {
        val store = StoreSnapshot(listOf(item), 1000, false, true, nightOffers=listOf(item), accessories=listOf(item))
        assertTrue(StoreFreshness.active(store,1000).nightOffers.isEmpty())
        assertTrue(StoreFreshness.active(store,1000).accessories.isEmpty())
        assertTrue(StoreFreshness.active(store.copy(isPreview=true),1).offers.isEmpty())
    }
    @Test fun recentMatchPlayerCardIsReadOnlyForTheSignedInPlayer() {
        val details = JSONObject("""{"players":[{"subject":"other","playerCard":"wrong"},{"subject":"self","playerCard":"real-card"}]}""")
        assertEquals("real-card", playerCardIdFromMatch(details, "self"))
        assertEquals("wrong", playerCardIdFromMatch(details, "other"))
        assertNull(playerCardIdFromMatch(details, "missing"))
    }
}
