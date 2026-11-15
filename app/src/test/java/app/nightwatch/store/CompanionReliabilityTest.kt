package app.nightwatch.store

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CompanionReliabilityTest {
    private val now = 1_800_000_000_000L
    private fun match(status: String = "live", score1: Int? = 1, score2: Int? = 0) =
        EsportsMatch("123", "Alpha", "Beta", score1, score2, "Champions", "Final", status, "", "https://www.vlr.gg/123/a", now + 60_000)

    @Test fun memoryCacheActuallyExpiresAndRejectsFutureTimestamps() {
        assertTrue(CatalogFreshness.fresh(now, now + 3599, 3600))
        assertFalse(CatalogFreshness.fresh(now, now + 3600, 3600))
        assertFalse(CatalogFreshness.fresh(now + 1, now, 3600))
        assertFalse(CatalogFreshness.fresh(0, now, 3600))
    }
    @Test fun futureWeaponIsStillASkinAndKeepsItsActualWeaponName() {
        val item = PendingCatalog.skin("new").copy(weaponName = "New Weapon")
        assertEquals(WeaponType.OTHER, item.weapon)
        assertTrue(item.skinOffer())
        assertEquals("New Weapon", item.weaponLabel())
    }
    @Test fun unresolvedOwnershipIsExplicitAndPriceIsNeverInvented() {
        val item = PendingCatalog.skin("unresolved")
        assertEquals("unresolved", item.id)
        assertTrue(item.metadataPending)
        assertEquals(-1, item.priceVp)
        assertTrue(item.skinOffer())
    }
    @Test fun liveAndFinalScoresObeyTheSameSpoilerPolicy() {
        listOf(match(), match("result", 2, 1)).forEach {
            assertEquals("•••", SpoilerPolicy.score(it, true))
            assertFalse(SpoilerPolicy.title(it, true).contains(it.score))
            assertTrue(SpoilerPolicy.title(it, false).contains(it.score))
        }
    }
    @Test fun widgetRowsDoNotLeakScoresWhenTheHeaderIsHidden() {
        val content = WidgetModels.matchContent(listOf(match()), true, now, true)
        assertEquals("•••", content.badge)
        assertFalse(content.title.contains("1 - 0"))
        assertEquals("•••", content.rows.single().detail)
        assertEquals("123", content.rows.single().match?.id)
        assertEquals("123", content.match?.id)
    }
    @Test fun upcomingWidgetExposesAnAbsoluteDeadlineForRendering() {
        val content = WidgetModels.matchContent(listOf(match("upcoming", null, null)), false, now)
        assertEquals(now + 60_000, content.deadline)
    }
    @Test fun unknownRRNeverAppearsAsNegativeOne() {
        assertEquals("— RR", WidgetModels.careerContent(CareerSummary("Gold 2", 13, -1, emptyList()), true).badge)
        assertEquals("— RR", RaundWidgetData.select(emptyList(), emptySet(), emptyList(), false, now,
            CareerSummary("Gold 2", 13, -1, emptyList())).badge)
    }
    @Test fun oldSavedCardsCannotDowngradeFinishedMatches() {
        val final = match("result", 2, 1)
        assertEquals(final, EsportsCachePolicy.merge(final, match()))
        assertEquals(final, EsportsCachePolicy.merge(final, match("upcoming", null, null)))
    }
    @Test fun finalScoreCorrectionsAndMissingIncomingScoresAreHandled() {
        assertEquals(3, EsportsCachePolicy.merge(match("result", 2, 1), match("result", 3, 1)).score1)
        assertEquals(1, EsportsCachePolicy.merge(match(), match(score1 = null, score2 = null)).score1)
    }
    @Test(expected = IllegalArgumentException::class) fun differentGamesCannotBeMerged() {
        EsportsCachePolicy.merge(match(), match().copy(id = "456"))
    }
    @Test fun navigationPayloadRejectsUnknownStatusesAndInvalidIDs() {
        assertTrue(MatchNavigation.valid(match()))
        assertTrue(MatchNavigation.valid(match("cancelled")))
        assertTrue(MatchNavigation.valid(match("postponed")))
        assertFalse(MatchNavigation.valid(match().copy(id = "../settings")))
        assertFalse(MatchNavigation.valid(match().copy(team2 = "")))
        assertFalse(MatchNavigation.valid(match("unknown")))
    }
    @Test fun androidFifteenIsNotAdvertisedAsLiveUpdateReady() {
        assertEquals("standard", LiveUpdateCapability.state(35, true, true, true))
        assertEquals("permission", LiveUpdateCapability.state(36, true, true, false))
        assertEquals("eligible", LiveUpdateCapability.state(36, true, true, true))
        assertEquals("notifications", LiveUpdateCapability.state(36, true, false, true))
        assertEquals("off", LiveUpdateCapability.state(36, false, true, true))
    }
    @Test fun backupOmitsSessionsCookiesAccountsAndDeliveryKeys() {
        val raw = PreferenceBackup.encode(mapOf("theme" to "light", "access_token" to "secret-access",
            "id_token" to "secret-id", "connected" to true, "result:123" to now, "cookie" to "secret-cookie",
            "account" to "secret-account", "esports_spoiler_mode" to true))
        assertFalse(raw.contains("secret")); assertFalse(raw.contains("result:123")); assertFalse(raw.contains("connected"))
        assertEquals(mapOf("theme" to "light", "esports_spoiler_mode" to true), PreferenceBackup.decode(raw))
    }
    @Test fun preferenceRoundTripPreservesSkinAndTeamFavorites() {
        val id = "00000000-0000-0000-0000-000000000001"
        val values = mapOf("wishlist" to setOf(id), "esports_teams" to setOf("Alpha"), "career_rr_goal" to 75, "language" to "TR")
        assertEquals(values, PreferenceBackup.decode(PreferenceBackup.encode(values)))
    }
    @Test fun maliciousBackupCannotSetAnAuthPreference() {
        assertEquals(emptyMap<String, Any>(), PreferenceBackup.decode("""{"app":"Raund","version":1,"preferences":{"access_token":"bad","connected":true}}"""))
    }
    @Test fun fullArchiveRemainsCompatibleWithExistingPreferenceBackups() {
        val archive = RaundArchive.decode("""{"app":"Raund","version":1,"preferences":{"theme":"dark","battery_saver":true}}""")
        assertEquals(mapOf("theme" to "dark", "battery_saver" to true), archive.preferences)
        assertNull(archive.account)
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedBackupsAreRejectedBeforeParsing() {
        PreferenceBackup.decode("x".repeat(250_001))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidThemesAreNotImported() {
        PreferenceBackup.decode("""{"app":"Raund","version":1,"preferences":{"theme":"javascript:alert(1)"}}""")
    }
    @Test(expected = IllegalArgumentException::class) fun invalidFavoriteIDsAreNotImported() {
        PreferenceBackup.decode("""{"app":"Raund","version":1,"preferences":{"wishlist":["../../session"]}}""")
    }
    @Test fun turkishSearchNormalizesBothAccentsAndDotlessI() {
        assertEquals("cagri", normalized("ÇAĞRI"))
        assertEquals(normalized("ŞAMPİYON"), normalized("sampiyon"))
    }
    private fun career(id: String, start: Long = now + 1, mode: String = "Competitive", raw: String? = """{"players":[],"roundResults":[]}""") =
        MatchSummary(id, "Ascent", mode, true, "13-8", 10, 8, 3, 20, start, raw, "me", "agent")
    @Test fun missingRoundDataCannotBecomeAClaimOfPerfectForm() {
        val sample = DevelopmentEvidence.measure("kast", listOf(career("1")))
        assertNull(sample.value); assertEquals(0, sample.observations)
        val goal = CompetitiveCoachAnalysis.goals(listOf(career("1"))).single()
        assertEquals(0, goal.progress)
    }
    @Test fun developmentCountsOnlyGamesActuallyStartedAfterTheCheckpoint() {
        val goal = CoachGoal("kast", "KAST", "KAST", "", "", 0)
        val checkpoint = DevelopmentEvidence.start(goal, listOf(career("known")), now)
        val after = DevelopmentEvidence.after(checkpoint, listOf(career("known"), career("old-loaded-later", now - 1),
            career("unrated", mode = "Unrated"), career("missing", raw = null), career("new"), career("new")))
        assertEquals(listOf("new"), after.map { it.id })
    }
    @Test fun developmentUsesFiveChronologicalGamesNotTheLatestFive() {
        val goal = CoachGoal("kast", "KAST", "KAST", "", "", 0)
        val checkpoint = DevelopmentEvidence.start(goal, emptyList(), now)
        assertEquals(listOf("1", "2", "3", "4", "5"), DevelopmentEvidence.after(checkpoint,
            (9 downTo 1).map { career("$it", now + it) }).map { it.id })
    }
    @Test fun followedMatchBackupCanonicalizesURLsAndDropsUnknownFields() {
        val raw = """[{"id":"123","team1":"Alpha","team2":"Beta","score1":2,"score2":1,"event":"Final","series":"BO3","status":"result","whenText":"","url":"file:///private","access_token":"do-not-export"}]"""
        val encoded = PreferenceBackup.encode(mapOf("esports_matches" to raw))
        assertFalse(encoded.contains("do-not-export")); assertFalse(encoded.contains("file:///"))
        assertTrue(encoded.contains("www.vlr.gg/123"))
        assertTrue(PreferenceBackup.decode(encoded).containsKey("esports_matches"))
    }
    @Test fun widgetPresetsArePortableWithoutReusingAndroidWidgetIDs() {
        val raw = """[{"mode":"matches","all":false,"match":"123"},{"mode":"store","all":false,"match":null}]"""
        val saved = PreferenceBackup.decode(PreferenceBackup.encode(mapOf("widget_presets" to raw)))
        assertEquals(2, WidgetPresets.decode(saved.getValue("widget_presets") as String).size)
    }
    @Test(expected = IllegalArgumentException::class) fun widgetPresetCannotNavigateToAnArbitraryResource() {
        WidgetPresets.decode("""[{"mode":"matches","all":false,"match":"../../settings"}]""")
    }
    @Test fun aPostponedMatchIsNotTurnedIntoACompletedGame() {
        val parsed = EsportsParser.detail(match(), """<div class="match-header-vs-score"><div class="match-header-vs-note">Postponed</div></div>""")
        assertEquals("postponed", parsed.match.status)
        assertEquals(match().startAtMillis, parsed.match.startAtMillis)
    }
    @Test fun aCancelledMatchIsNotKeptLiveByItsSavedCard() {
        val parsed = EsportsParser.detail(match(), """<div class="match-header-vs-score"><div class="match-header-vs-note">Cancelled</div></div>""")
        assertEquals("cancelled", parsed.match.status)
        assertNull(parsed.match.score1)
        assertFalse(MatchNavigation.valid(parsed.match.copy(id = "invalid")))
    }
    @Test fun authoritativeCancellationClearsAnOldScore() {
        val cancelled = EsportsCachePolicy.merge(match("result", 2, 1), match("cancelled"))
        assertEquals("cancelled", cancelled.status)
        assertNull(cancelled.score1); assertNull(cancelled.score2)
    }
    @Test fun accessoryFavoritesCanAlsoBecomeAvailableWithoutDuplicateAlerts() {
        val item = StoreOffer("card", "Player Card", "playercards", WeaponType.OTHER, "", 4500, emptyList(), currency = "KC")
        val snapshot = StoreSnapshot(emptyList(), System.currentTimeMillis() / 1000 + 3600, false, false, accessories = listOf(item))
        assertEquals(listOf(item), FavoriteAlerts.matches(snapshot, setOf(item.id)))
    }
}
