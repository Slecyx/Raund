package app.nightwatch.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EsportsResultTransitionPolicyTest {
    private fun match(id: String, status: String, score1: Int? = null, score2: Int? = null) =
        EsportsMatch(id, "Alpha", "Bravo", score1, score2, "Event", "", status, "", "")

    @Test fun legacyActiveIdsAreBaselinedInsteadOfAlerted() {
        val transition = EsportsResultTransitionPolicy.advance(listOf(match("old", "result", 2, 0)), setOf("old"), emptySet(), 1_000_000L)
        assertTrue(transition.completed.isEmpty())
    }

    @Test fun recentlyObservedTransitionAlertsOnce() {
        val first = EsportsResultTransitionPolicy.advance(listOf(match("new", "live")), emptySet(), emptySet(), 1_000_000L)
        val completed = EsportsResultTransitionPolicy.advance(listOf(match("new", "result", 2, 1)), first.nextEntries, emptySet(), 1_100_000L)
        assertEquals(listOf("new"), completed.completed.map { it.id })
    }

    @Test fun expiredActiveEntryCannotAlertLater() {
        val transition = EsportsResultTransitionPolicy.advance(listOf(match("late", "result", 2, 1)), setOf("late|1"), emptySet(), 80_000_000L)
        assertTrue(transition.completed.isEmpty())
    }
}
