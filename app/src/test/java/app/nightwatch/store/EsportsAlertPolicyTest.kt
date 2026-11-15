package app.nightwatch.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EsportsAlertPolicyTest {
    private fun match(id: String, status: String, score1: Int? = null, score2: Int? = null) =
        EsportsMatch(id, "A", "B", score1, score2, "Event", "Final", status, "", "https://www.vlr.gg/$id/x")

    @Test fun onlyARealTransitionToACompleteResultAlerts() {
        val watched = listOf(match("old", "result", 2, 0), match("transition", "result", 2, 1), match("partial", "result", null, null))
        assertEquals(listOf("transition"), EsportsAlertPolicy.completed(watched, setOf("transition", "partial"), emptySet()).map { it.id })
    }

    @Test fun aResultAlreadyNotifiedNeverAlertsAgain() {
        assertTrue(EsportsAlertPolicy.completed(listOf(match("one", "result", 2, 0)), setOf("one"), setOf("one")).isEmpty())
    }

    @Test fun vetoAbbreviationsResolveToTheCorrectTeam() {
        assertEquals(1, esportsVetoTeam("TYL ban Abyss", "TYLOO", "G2 Esports"))
        assertEquals(2, esportsVetoTeam("G2 pick Sunset", "TYLOO", "G2 Esports"))
        assertEquals(null, esportsVetoTeam("Lotus remains", "TYLOO", "G2 Esports"))
    }

    @Test fun liveNotificationsAreCombinedAndDeduplicated() {
        val live = match("live", "live", 1, 0)
        val followed = match("followed", "live", 0, 1)
        assertEquals(listOf("live", "followed"), EsportsAlertPolicy.relevantLive(
            "all", listOf(live), listOf(live, followed), emptySet(), null
        ).map { it.id })
        assertEquals(listOf("followed"), EsportsAlertPolicy.relevantLive(
            "all", listOf(live), listOf(followed), emptySet(), "live"
        ).map { it.id })
    }

    @Test fun favoriteModeOnlyIncludesFavoriteTeamsAndExplicitFollows() {
        val favorite = match("favorite", "live").copy(team1 = "FNATIC")
        val unrelated = match("other", "live")
        val explicit = match("explicit", "live")
        assertEquals(listOf("favorite", "explicit"), EsportsAlertPolicy.relevantLive(
            "favorites", listOf(favorite, unrelated), listOf(explicit), setOf("fnatic"), null
        ).map { it.id })
    }

    @Test fun mapPickIsAssignedToTheTeamThatSelectedIt() {
        assertEquals(2, esportsMapSelection("Sunset", "TYL ban Abyss; G2 pick Sunset", "TYLOO", "G2 Esports").first)
        assertEquals(null, esportsMapSelection("Lotus", "Lotus remains", "TYLOO", "G2 Esports").first)
    }

    @Test fun followedTournamentMatchesReceiveReminders() {
        val upcoming = match("future", "upcoming").copy(event = "Masters Tokyo")
        assertTrue(EsportsReminders.follows(upcoming, emptySet(), emptySet(), listOf("Masters Tokyo")))
    }

    @Test fun aLiveSeriesScoreChangeEmitsExactlyTheCompletedMap() {
        val previous = match("map-alert", "live", 0, 0)
        val fresh = previous.copy(score1 = 1, score2 = 0)
        val ascent = EsportsMapResult("Ascent", 13, 9, "42:00", "Alpha pick")
        val current = EsportsMapResult("Lotus", 4, 3, "", "Beta pick")
        val completion = EsportsMapAlertPolicy.transition(previous,
            EsportsDetail(fresh, "bo3", emptyList(), emptyList(), mapResults = listOf(ascent, current)))
        assertEquals(1, completion?.mapNumber)
        assertEquals("Ascent", completion?.map?.name)
    }

    @Test fun unchangedScoreAndFinalResultDoNotCreateDuplicateMapAlerts() {
        val live = match("same", "live", 1, 0)
        val map = EsportsMapResult("Ascent", 13, 9, "42:00", "")
        assertEquals(null, EsportsMapAlertPolicy.transition(live,
            EsportsDetail(live, "bo3", emptyList(), emptyList(), mapResults = listOf(map))))
        assertEquals(null, EsportsMapAlertPolicy.transition(live,
            EsportsDetail(live.copy(status = "result", score1 = 2), "bo3", emptyList(), emptyList(), mapResults = listOf(map))))
    }
}
