package app.nightwatch.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionToolsTest {
    @Test fun radianiteCalculatorComputesNeedAndDeficitAccurately() {
        val ready = RadianitePlan.calculate(levels = 3, chromas = 1, walletRp = 50)
        assertEquals(45, ready.requiredRp)
        assertEquals(0, ready.deficitRp)

        val short = RadianitePlan.calculate(levels = 4, chromas = 2, walletRp = 35)
        assertEquals(70, short.requiredRp)
        assertEquals(35, short.deficitRp)

        val clamped = RadianitePlan.calculate(levels = -5, chromas = -2, walletRp = null)
        assertEquals(0, clamped.requiredRp)
    }

    @Test fun mapCalloutParserGroupsAndSortsRegionsFromValorantApiSchema() {
        val meta = JSONObject("""
            {
              "displayIcon": "https://media.valorant-api.com/maps/ascent/displayicon.png",
              "callouts": [
                {"regionName": "Tree", "superRegionName": "A"},
                {"regionName": "Garden", "superRegionName": "A"},
                {"regionName": "Tree", "superRegionName": "A"},
                {"regionName": "Market", "superRegionName": "Mid"}
              ]
            }
        """.trimIndent())
        val guide = MapCalloutParser.parse("Ascent", meta)
        assertEquals("Ascent", guide.name)
        assertEquals("https://media.valorant-api.com/maps/ascent/displayicon.png", guide.minimapUrl)
        assertEquals(2, guide.groups.size)
        assertEquals("A", guide.groups[0].superRegion)
        assertEquals(listOf("Garden", "Tree"), guide.groups[0].regions)
        assertEquals("Mid", guide.groups[1].superRegion)
        assertEquals(listOf("Market"), guide.groups[1].regions)
    }

    @Test fun shareSummariesRespectLanguageAndSpoilerMode() {
        val match = EsportsMatch("42", "Fnatic", "PRX", 2, 1, "Masters", "Final", "result", "", "https://www.vlr.gg/42")
        val hidden = ShareSummaries.esportsMatchSummary(match, hideScores = true, turkish = true)
        assertTrue(hidden.contains("•••"))
        assertFalse(hidden.contains("2 - 1"))
        val revealed = ShareSummaries.esportsMatchSummary(match, hideScores = false, turkish = false)
        assertTrue(revealed.contains("2 - 1"))
        assertTrue(revealed.contains("https://www.vlr.gg/42"))
    }

    @Test fun pickemSummaryComputesAccuracyAcrossDecidedMatchesOnly() {
        val matches = listOf(
            EsportsMatch("1", "A", "B", 2, 0, "E", "S", "result", "", "https://www.vlr.gg/1"),
            EsportsMatch("2", "C", "D", 1, 2, "E", "S", "result", "", "https://www.vlr.gg/2"),
            EsportsMatch("3", "E", "F", null, null, "E", "S", "upcoming", "", "https://www.vlr.gg/3")
        )
        val summary = PickemStore.summarize(mapOf("1" to "A", "2" to "C", "3" to "E"), matches)
        assertEquals(3, summary.totalPicks)
        assertEquals(2, summary.decided)
        assertEquals(1, summary.correct)
        assertEquals(50, summary.accuracyPercent)

        val empty = PickemStore.summarize(mapOf("3" to "E"), matches)
        assertNull(empty.accuracyPercent)
    }

    @Test fun crosshairProfilesAllContainImportableValorantCodes() {
        assertTrue(CrosshairLibrary.profiles.size >= 6)
        CrosshairLibrary.profiles.forEach { profile ->
            assertTrue(profile.name.isNotBlank())
            assertTrue("Invalid code for ${profile.name}", profile.code.startsWith("0;"))
        }
    }

    @Test fun cacheSizeFormattingHandlesBytesKbAndMb() {
        assertEquals("512 B", CacheMaintenance.formatSize(512))
        assertEquals("24 KB", CacheMaintenance.formatSize(24 * 1024))
        assertEquals("2.5 MB", CacheMaintenance.formatSize((2.5 * 1_048_576).toLong()))
    }

    @Test fun esportsTierFilterSeparatesVctFromChallengersAndFiltersByRegion() {
        val vctEmea = EsportsMatch("1", "Fnatic", "FUT", 2, 0, "VCT 2026: EMEA Stage 1", "Playoffs", "live", "", "https://www.vlr.gg/1")
        val masters = EsportsMatch("2", "PRX", "Sentinels", null, null, "Valorant Masters Toronto", "Final", "upcoming", "", "https://www.vlr.gg/2")
        val challengers = EsportsMatch("3", "Team X", "Team Y", null, null, "Challengers League EMEA", "Group", "upcoming", "", "https://www.vlr.gg/3")
        assertTrue(EsportsTierFilter.isTierOne(vctEmea))
        assertTrue(EsportsTierFilter.isTierOne(masters))
        assertFalse(EsportsTierFilter.isTierOne(challengers))

        val filtered = EsportsTierFilter.filter(listOf(vctEmea, masters, challengers), tierOneOnly = true, region = "emea", query = "fut")
        assertEquals(listOf("1"), filtered.map { it.id })
    }

    @Test fun radarHistorySummaryComputesUniqueSkinsAndMostSeenWeapon() {
        val v1 = StoreOffer("1", "Prime Vandal", "PRIME", WeaponType.VANDAL, "", 1775, emptyList(), isWeaponSkin = true)
        val v2 = StoreOffer("2", "Reaver Vandal", "REAVER", WeaponType.VANDAL, "", 1775, emptyList(), isWeaponSkin = true)
        val p1 = StoreOffer("3", "Oni Phantom", "ONI", WeaponType.PHANTOM, "", 1775, emptyList(), isWeaponSkin = true)
        val history = listOf(
            StoreObservation(1000, 900, listOf(v1, p1)),
            StoreObservation(2000, 1900, listOf(v2, v1))
        )
        val stats = RadarHistory.summary(history)
        assertEquals(2, stats.rotations)
        assertEquals(3, stats.uniqueSkins)
        assertEquals("Vandal", stats.topWeapon)
        assertEquals(3, stats.topWeaponCount)
    }

    @Test fun inventoryValueCalculatorUsesOnlyKnownStoreOfferPrices() {
        val prime = StoreOffer("uuid-1", "Prime Vandal", "PRIME", WeaponType.VANDAL, "", -1, emptyList(), isWeaponSkin = true)
        val bp = StoreOffer("uuid-2", "Battle Pass Vandal", "BP", WeaponType.VANDAL, "", -1, emptyList(), isWeaponSkin = true)
        val summary = InventoryValueCalculator.calculate(listOf(prime, bp), mapOf("uuid-1" to 1775))
        assertEquals(1775, summary.totalVp)
        assertEquals(1, summary.pricedSkins)
        assertEquals(1, summary.unpricedSkins)
        assertEquals(2, summary.totalSkins)
    }

    @Test fun riotStatusParserExtractsLocalizedIncidentsAndMaintenances() {
        val raw = JSONObject("""
            {
              "incidents": [{"incident_severity": "warning", "titles": [{"locale": "en_US", "content": "Login issues"}, {"locale": "tr_TR", "content": "Giriş sorunları"}]}],
              "maintenances": [{"maintenance_status": "in_progress", "titles": [{"locale": "en_US", "content": "Patch deploy"}]}]
            }
        """.trimIndent())
        val tr = RiotStatusParser.parse("eu", raw, turkish = true)
        assertEquals("EU", tr.region)
        assertEquals(2, tr.notices.size)
        assertEquals("Giriş sorunları", tr.notices[0].title)
        assertEquals("Patch deploy", tr.notices[1].title)
    }

    @Test fun gameGuideParserReadsWeaponDamageRangesAndPlayableAgents() {
        val weapon = JSONObject("""
            {
              "uuid": "w1", "displayName": "Vandal", "displayIcon": "https://media.valorant-api.com/w1.png",
              "shopData": {"cost": 2900, "categoryText": "Assault Rifles"},
              "weaponStats": {"fireRate": 9.75, "magazineSize": 25, "wallPenetration": "EWallPenetrationDisplayType::Medium",
                "damageRanges": [{"rangeStartMeters": 0, "rangeEndMeters": 50, "headDamage": 160, "bodyDamage": 40, "legDamage": 34}]}
            }
        """.trimIndent())
        val parsedWeapon = GameGuideParser.weapons(listOf(weapon)).single()
        assertEquals("Vandal", parsedWeapon.name)
        assertEquals(2900, parsedWeapon.cost)
        assertEquals(160, parsedWeapon.ranges.single().head)

        val agent = JSONObject("""
            {
              "uuid": "a1", "displayName": "Jett", "isPlayableCharacter": true, "description": "Agile duelist",
              "role": {"displayName": "Duelist"},
              "abilities": [{"slot": "Ability1", "displayName": "Updraft", "description": "Jump high"}]
            }
        """.trimIndent())
        val nonPlayable = JSONObject("""{"uuid": "a2", "displayName": "Old Sova", "isPlayableCharacter": false}""")
        val parsedAgents = GameGuideParser.agents(listOf(agent, nonPlayable))
        assertEquals(1, parsedAgents.size)
        assertEquals("Jett", parsedAgents.single().name)
        assertEquals("Updraft", parsedAgents.single().abilities.single().name)
    }

    @Test fun loadoutBuilderUpdatesOnlyTargetedWeaponSlot() {
        val raw = JSONObject("""
            {
              "Subject": "u1", "Version": 5,
              "Guns": [
                {"ID": "vandal-id", "SkinID": "old-skin", "SkinLevelID": "old-lvl", "ChromaID": "old-chroma"},
                {"ID": "phantom-id", "SkinID": "keep-skin", "SkinLevelID": "keep-lvl", "ChromaID": "keep-chroma"}
              ]
            }
        """.trimIndent())
        val updated = LoadoutBuilder.applySelection(raw, "vandal-id",
            LoadoutGunOption("new-skin", "Prime Vandal", "new-lvl", "new-chroma", "Yellow", null))
        val guns = updated.getJSONArray("Guns")
        assertEquals("new-skin", guns.getJSONObject(0).getString("SkinID"))
        assertEquals("new-chroma", guns.getJSONObject(0).getString("ChromaID"))
        assertEquals("keep-skin", guns.getJSONObject(1).getString("SkinID"))
        assertEquals(5, updated.getInt("Version"))
    }

    @Test fun lineupCatalogParsesYoutubeSearchJsonAndValidatesVideoIds() {
        val sampleHtml = """
            <html><script>var ytInitialData = {"contents":{"a":[{"videoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"runs":[{"text":"Ascent Sova A Site Recon Lineup"}]},"ownerText":{"runs":[{"text":"LineupPro"}]},"lengthText":{"simpleText":"0:45"}}},{"videoRenderer":{"videoId":"../invalid","title":{"simpleText":"Bad"}}}]}};</script></html>
        """.trimIndent()
        val videos = LineupCatalog.parseVideos(sampleHtml, "Ascent", "Sova")
        assertEquals(1, videos.size)
        assertEquals("dQw4w9WgXcQ", videos[0].videoId)
        assertEquals("Ascent Sova A Site Recon Lineup", videos[0].title)
        assertEquals("LineupPro", videos[0].channel)
        assertEquals("0:45", videos[0].duration)
        assertTrue(videos[0].embedUrl.startsWith("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
    }
}
