package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class WidgetAndDeliveryPolicyTest {
    private val now = 1_800_000_000_000L
    private fun skin(id: String, price: Int = 1775) = StoreOffer(id, "Skin $id", "", WeaponType.VANDAL, "", price, emptyList())
    private fun match(id: String, status: String = "upcoming", start: Long? = now + 60_000) =
        EsportsMatch(id, "Alpha", "Beta", if(status == "live") 1 else null, if(status == "live") 0 else null,
            "Champions", "Final", status, "", "https://www.vlr.gg/$id/a", start)

    @Test fun savedMatchDoesNotRequireAFavoriteTeam() {
        val content = RaundWidgetData.select(listOf(match("1", "live")), emptySet(), emptyList(), true, now, savedIds = setOf("1"))
        assertEquals("CANLI MAÇ", content.eyebrow)
    }
    @Test fun allMatchesWidgetShowsUnfollowedTeams() {
        val content = WidgetModels.matchContent(listOf(match("1", "live")), false, now)
        assertEquals("Alpha  1 - 0  Beta", content.title)
    }
    @Test fun widgetOrdersLiveBeforeUpcomingAndOmitsExpiredStarts() {
        val content = WidgetModels.matchContent(listOf(match("old", start = now - 1000), match("future"), match("live", "live")), true, now)
        assertEquals("CANLI MAÇ", content.eyebrow)
        assertEquals(2, content.rows.size)
    }
    @Test fun storeWidgetShowsAllFourRealDailyOffersWithoutWishlist() {
        val snapshot = StoreSnapshot((1..4).map { skin("$it") }, now / 1000 + 3600, false, false)
        val content = WidgetModels.storeContent(snapshot, true, now)
        assertEquals(4, content.rows.size)
        assertEquals("01:00 kaldı", content.badge)
        assertEquals("1775 VP", content.rows.first().detail)
    }
    @Test fun expiredStoreNeverShowsStaleOffers() {
        val snapshot = StoreSnapshot(listOf(skin("expired")), now / 1000 - 1, false, false)
        assertTrue(WidgetModels.storeContent(snapshot, true, now).rows.isEmpty())
    }
    @Test fun previewCannotAppearAsAPersonalStoreWidget() {
        val snapshot = StoreSnapshot(listOf(skin("demo")), now / 1000 + 3600, true, false)
        assertTrue(WidgetModels.storeContent(snapshot, false, now).rows.isEmpty())
    }
    @Test fun unknownPriceIsNotDisplayedAsMinusOne() {
        val snapshot = StoreSnapshot(listOf(skin("pending", -1)), now / 1000 + 3600, false, false)
        assertEquals("Fiyat bekleniyor", WidgetModels.storeContent(snapshot, true, now).rows.first().detail)
    }
    @Test fun inactiveNightMarketHasAnExplicitEmptyState() {
        val snapshot = StoreSnapshot(listOf(skin("day")), now / 1000 + 3600, false, false)
        assertEquals("Şu anda Gece Pazarı yok", WidgetModels.storeContent(snapshot, true, now, true).title)
    }
    @Test fun nightMarketUsesItsOwnExpiryAndDiscountPrices() {
        val snapshot = StoreSnapshot(emptyList(), now / 1000 - 1, false, true,
            nightOffers = listOf(skin("night", 900)), nightMarketExpiresAt = now / 1000 + 7200)
        val content = WidgetModels.storeContent(snapshot, false, now, true)
        assertEquals("900 VP", content.rows.first().detail)
        assertEquals("02:00 left", content.badge)
    }
    @Test fun nightMarketKeepsAllSixOffersAndItsOwnLayoutKind() {
        val snapshot = StoreSnapshot(emptyList(), now / 1000 + 3600, false, true,
            nightOffers = (1..6).map { skin("night-$it", 900) }, nightMarketExpiresAt = now / 1000 + 7200)
        val content = WidgetModels.storeContent(snapshot, true, now, true)
        assertEquals(6, content.rows.size)
        assertTrue(content.nightMarket)
        assertTrue(content.subtitle.contains("6 teklif"))
    }
    @Test fun partialNightDataDoesNotManufactureSixOffers() {
        val snapshot = StoreSnapshot(emptyList(), now / 1000 + 3600, false, true,
            nightOffers = listOf(skin("one")), nightMarketExpiresAt = now / 1000 + 7200)
        assertEquals(1, WidgetModels.storeContent(snapshot, true, now, true).rows.size)
    }
    @Test fun rankWidgetExcludesDeathmatchAndUnrated() {
        fun careerMatch(id: String, mode: String) = MatchSummary(id, "Ascent", mode, true, "13 - 7", 20, 10, 6, 15, now)
        val career = CareerSummary("Gold 2", 13, 64, listOf(careerMatch("1", "Competitive"), careerMatch("2", "Deathmatch"), careerMatch("3", "Unrated")))
        val content = WidgetModels.careerContent(career, true)
        assertEquals("64 RR", content.badge)
        assertEquals(1, content.rows.size)
        assertEquals("1 galibiyet / 1 maç", content.subtitle)
    }
    @Test fun delayedNightWorkerCannotProduceAMorningTwin() {
        val morning = ZonedDateTime.parse("2026-10-03T09:03:00+03:00[Europe/Istanbul]")
        assertNull(StoreAlertPolicy.eligibleSlot(3, morning))
        assertEquals("2026-10-03-9", StoreAlertPolicy.eligibleSlot(9, morning))
    }
    @Test fun midnightAndNextDayRemainDistinctDeliveryWindows() {
        assertNull(StoreAlertPolicy.eligibleSlot(9, ZonedDateTime.parse("2026-10-04T00:30:00+03:00")))
        assertEquals("2026-10-04-3", StoreAlertPolicy.eligibleSlot(3, ZonedDateTime.parse("2026-10-04T03:05:00+03:00")))
    }
    @Test fun unchangedNightMarketHasOneContentKeyAcrossEveryCheck() {
        val night = StoreSnapshot(emptyList(), now / 1000 + 3600, false, true,
            nightOffers = listOf(skin("b"), skin("a")), nightMarketExpiresAt = now / 1000 + 86_400)
        assertEquals(StoreAlertPolicy.deliveryKey(emptyList(), night),
            StoreAlertPolicy.deliveryKey(emptyList(), night.copy(nightOffers = night.nightOffers.reversed())))
        assertNotEquals(StoreAlertPolicy.deliveryKey(emptyList(), night),
            StoreAlertPolicy.deliveryKey(emptyList(), night.copy(nightOffers = night.nightOffers + skin("c"))))
    }
    @Test fun ordinaryStoreWithoutFavoriteNeverCreatesAnAlertKey() {
        val daily = StoreSnapshot(listOf(skin("ordinary")), now / 1000 + 3600, false, false)
        assertNull(StoreAlertPolicy.deliveryKey(emptyList(), daily))
    }
    @Test fun ongoingScoreCardStartsNearKickoffInsteadOfDuplicatingTheFifteenMinuteReminder() {
        assertEquals(now - 15 * 60_000L, EsportsReminders.reminderAt(now))
        assertEquals(now, EsportsReminders.monitorAt(now))
    }
    @Test fun responsiveLayoutsUseOnlyRowsThatFitTheViewport() {
        assertEquals(0, WidgetRenderer.rowCount(120, 65))
        assertEquals(0, WidgetRenderer.rowCount(180, 300))
        assertEquals(3, WidgetRenderer.rowCount(260, 220))
        assertEquals(6, WidgetRenderer.rowCount(400, 400))
    }
    @Test fun backgroundStoreRefreshPreservesTheSameAccountsCareerAndInventory() {
        val snapshot = StoreSnapshot(listOf(skin("new")), now / 1000 + 3600, false, false)
        val old = LiveStoreAccount("Alpha#1", "EU", snapshot, CareerSummary("Gold 2", 13, 64, emptyList()),
            null, accountId = "alpha", inventory = OwnedInventory(listOf("owned"), now))
        val fresh = old.copy(career = null, inventory = null, snapshot = snapshot.copy(offers = listOf(skin("fresh"))))
        val merged = StoreBackgroundRefresh.merge(fresh, old)
        assertEquals("fresh", merged.snapshot.offers.first().id)
        assertEquals(old.career, merged.career)
        assertEquals(old.inventory, merged.inventory)
    }
    @Test fun pickemResolutionUsesOnlyThePublishedFinalWinner() {
        val upcoming = match("pick", "upcoming")
        assertNull(PickemStore.correct(upcoming, "Alpha"))
        assertTrue(PickemStore.correct(upcoming.copy(status = "result", score1 = 2, score2 = 1), "Alpha") == true)
        assertTrue(PickemStore.correct(upcoming.copy(status = "result", score1 = 0, score2 = 2), "Alpha") == false)
    }
    @Test fun switchingAccountsNeverCopiesAnotherPlayersRankOrInventory() {
        val snapshot = StoreSnapshot(emptyList(), now / 1000 + 3600, false, false)
        val old = LiveStoreAccount("Alpha#1", "EU", snapshot, CareerSummary("Gold 2", 13, 64, emptyList()),
            null, accountId = "alpha", inventory = OwnedInventory(listOf("owned"), now))
        val fresh = old.copy(name = "Beta#1", accountId = "beta", career = null, inventory = null)
        val merged = StoreBackgroundRefresh.merge(fresh, old)
        assertNull(merged.career)
        assertNull(merged.inventory)
    }
}
