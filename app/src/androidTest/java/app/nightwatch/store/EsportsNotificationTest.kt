package app.nightwatch.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EsportsNotificationTest {
    @Test fun pinnedScoreIsPublicAndUserDismissableFromAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val match = EsportsMatch("123","Alpha","Beta",1,2,"Champions","Final","live","","https://www.vlr.gg/123/a")
        val notification = ScoreNotifications.item(context, match, true)
        assertTrue(notification.extras.getString("android.title").orEmpty().contains("1 - 2"))
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue(notification.actions.isNotEmpty())
        assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
    }

    @Test fun savedUpcomingMatchShowsSystemCountdownOnLockscreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = System.currentTimeMillis() + 3_600_000
        val match = EsportsMatch("124", "Alpha", "Beta", null, null, "Champions", "Final", "upcoming", "", "https://www.vlr.gg/124/a", start)
        val notification = ScoreNotifications.favoriteMatch(context, match)
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(start, notification.`when`)
        assertTrue(notification.extras.getBoolean("android.showChronometer"))
        assertTrue(notification.extras.getBoolean("android.chronometerCountDown"))
        assertTrue(notification.actions.isNotEmpty())
    }

    @Test fun matchReminderIsSeparateAndFifteenMinutesBeforeStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = System.currentTimeMillis() + 3_600_000
        val match = EsportsMatch("125", "Alpha", "Beta", null, null, "Champions", "Final", "upcoming", "", "https://www.vlr.gg/125/a", start)
        assertEquals(start - 900_000L, EsportsReminders.reminderAt(start))
        val notification = ScoreNotifications.reminder(context, match)
        assertTrue(notification.extras.getString("android.title").orEmpty().contains("Alpha"))
        assertEquals(ScoreNotifications.REMINDER_CHANNEL, notification.channelId)
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, notification.visibility)
    }
}
