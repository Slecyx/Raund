package app.nightwatch.store

import org.junit.Assert.assertEquals
import org.junit.Test

class RaundWidgetDataTest {
    private fun match(id: String, status: String, start: Long?, teams: Pair<String,String> = "A" to "B", score: Pair<Int?,Int?> = null to null) =
        EsportsMatch(id, teams.first, teams.second, score.first, score.second, "Event", "", status, "Soon", "https://www.vlr.gg/$id", start)
    @Test fun liveFavoriteWinsOverUpcoming() {
        val result = RaundWidgetData.select(listOf(match("1","upcoming",2000,"A" to "B"), match("2","live",null,"A" to "C",1 to 0)), setOf("A"), emptyList(), true, 1000)
        assertEquals("CANLI MAÇ", result.eyebrow); assertEquals("1 - 0", result.badge)
    }
    @Test fun ignoresUnfollowedTeamsAndChoosesNearest() {
        val result = RaundWidgetData.select(listOf(match("1","upcoming",5000,"X" to "Y"), match("2","upcoming",3000,"A" to "C")), setOf("A"), emptyList(), false, 1000)
        assertEquals("A  ×  C", result.title)
    }
    @Test fun storeFavoriteIsFallback() {
        val skin = StoreOffer("id","Skin","",WeaponType.VANDAL,"",1775, emptyList())
        assertEquals("favorites", RaundWidgetData.select(emptyList(), emptySet(), listOf(skin), false, 0).page)
    }
    @Test fun rankIsShownWhenThereIsNoUrgentMatchOrSkin() {
        val career = CareerSummary("Gold 2", 13, 64, emptyList())
        val result = RaundWidgetData.select(emptyList(), emptySet(), emptyList(), true, 0, career)
        assertEquals("career", result.page)
        assertEquals("64 RR", result.badge)
    }
}
