package app.nightwatch.store

import com.google.gson.Gson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecurityHardeningTest {
    @Test fun riotIdentifiersMustBeStrictUuids() {
        assertTrue(RiotLiveGateway.isRiotId("e7c63390-eda7-46e0-bb7a-a6abdacd2433"))
        assertTrue(RiotLiveGateway.isRiotId("E7C63390-EDA7-46E0-BB7A-A6ABDACD2433"))
    }

    @Test fun pathOrHostInjectionIsRejectedBeforeBuildingAuthenticatedUrls() {
        listOf(
            null, "", "../../store/v1/wallet", "e7c63390-eda7-46e0-bb7a-a6abdacd2433/../x",
            "e7c63390-eda7-46e0-bb7a-a6abdacd2433?x=1", "e7c63390-eda7-46e0-bb7a-a6abdacd2433\n",
            "evil.example#", "e7c63390eda746e0bb7aa6abdacd2433"
        ).forEach { assertFalse("accepted: $it", RiotLiveGateway.isRiotId(it)) }
    }

    @Test fun socialLinksVerifyHostRatherThanSubstring() {
        assertTrue(EsportsExploreParser.isAllowedSocialUrl("https://x.com/valorant"))
        assertTrue(EsportsExploreParser.isAllowedSocialUrl("https://www.twitch.tv/valorant"))
        assertTrue(EsportsExploreParser.isAllowedSocialUrl("https://youtube.com/@valorant"))
        assertFalse(EsportsExploreParser.isAllowedSocialUrl("https://evil.example/x.com/phish"))
        assertFalse(EsportsExploreParser.isAllowedSocialUrl("https://x.com.evil.example/user"))
        assertFalse(EsportsExploreParser.isAllowedSocialUrl("http://x.com/valorant"))
        assertFalse(EsportsExploreParser.isAllowedSocialUrl("https://user@x.com/valorant"))
        assertFalse(EsportsExploreParser.isAllowedSocialUrl("javascript:alert(1)//x.com/"))
    }

    @Test fun hostileMatchIdAttributeCannotCrashCssSelectorParsing() {
        val html = """
            <a class="m-item" href="/12345/team-a-vs-team-b">
                <div class="m-item-team-name">Team A</div>
                <div class="m-item-team-name">Team B</div>
                <div class="m-item-result mod-win" data-match-id='"][invalid'><span>2</span><span>0</span></div>
            </a>
        """.trimIndent()
        val parsed = EsportsExploreParser.playerMatches(html)
        assertEquals(1, parsed.size)
        assertEquals("12345", parsed.single().id)
        assertTrue(parsed.single().maps.isEmpty())
    }

    @Test fun largeV2FullArchiveRestoresSuccessfullyBeyondPreferenceLimit() {
        val padding = "a".repeat(300_000)
        val match = MatchSummary("m1", "Ascent", "Competitive", true, "13-7", 20, 10, 5, 22, 1_800_000_000_000L, """{"pad":"$padding"}""", "puuid", "agent")
        val account = LiveStoreAccount(
            name = "Player#TR1",
            region = "EU",
            snapshot = StoreSnapshot(emptyList(), 1_800_000_000L, false, false),
            career = CareerSummary("Diamond 1", 18, 50, listOf(match)),
            progress = null
        )
        val raw = JSONObject()
            .put("app", "Raund")
            .put("version", 2)
            .put("preferences", JSONObject().put("theme", "dark"))
            .put("account", JSONObject(Gson().toJson(account)))
            .toString()
        assertTrue(raw.length > 250_000)
        val decoded = RaundArchive.decode(raw)
        assertEquals(mapOf("theme" to "dark"), decoded.preferences)
        assertEquals("Player#TR1", decoded.account?.name)
        assertEquals(1, decoded.account?.career?.matches?.size)
    }

    @Test fun v2ArchiveSanitizesUnsafeUrlsAndRejectsNullRequiredFields() {
        val offerWithBadUrl = """{"id":"skin-1","name":"Vandal","collection":"ION","weapon":"VANDAL","tier":"Premium","priceVp":1775,"palette":[1],"imageUrl":"file:///data/secret","variants":[{"name":"Level 1","image":"javascript:alert(1)","video":"http://insecure.example/v.mp4"}]}"""
        val raw = """{"app":"Raund","version":2,"preferences":{"theme":"dark"},"account":{"name":"Player#1","region":"EU","snapshot":{"offers":[$offerWithBadUrl],"refreshEpochSeconds":100,"isPreview":false,"nightMarketAvailable":false},"playerCard":{"id":"card-1","small":"content://bad","wide":"https://media.valorant-api.com/ok.png"}}}"""
        val decoded = RaundArchive.decode(raw)
        val offer = decoded.account!!.snapshot.offers.single()
        assertNull(offer.imageUrl)
        assertNull(offer.variants.single().image)
        assertNull(offer.variants.single().video)
        assertNull(decoded.account.playerCard?.small)
        assertEquals("https://media.valorant-api.com/ok.png", decoded.account.playerCard?.wide)

        try {
            RaundArchive.decode("""{"app":"Raund","version":2,"preferences":{},"account":{"name":null,"region":"EU","snapshot":null}}""")
            fail("Expected IllegalArgumentException for null required fields")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun gsonNullArraysAndElementsFailCleanlyAsIllegalArgument() {
        listOf("null", "[null]", """[{"id":null,"team1":"A","team2":"B","status":"live"}]""").forEach { bad ->
            try {
                PreferenceBackup.decode(JSONObject().put("app", "Raund").put("version", 1)
                    .put("preferences", JSONObject().put("esports_matches", bad)).toString())
                fail("Accepted malformed esports_matches: $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        try {
            WidgetPresets.decode("[null]")
            fail("Accepted null widget preset")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            WatchLater.decode("null")
            fail("Accepted null watch later array")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
