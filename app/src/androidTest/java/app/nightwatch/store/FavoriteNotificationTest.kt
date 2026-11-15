package app.nightwatch.store

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoriteNotificationTest {
    @Test fun favoritePostsOncePerScheduledSlot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if(Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}").close()
            Thread.sleep(200)
        }
        val prefsName = "nightwatch_notification_test"
        val prefs = context.getSharedPreferences(prefsName, 0)
        val manager = context.getSystemService(NotificationManager::class.java)
        val item = StoreOffer("notification-test-id", "Nightwatch notification test", "", WeaponType.VANDAL, "", 0, emptyList())
        val snapshot = StoreSnapshot(listOf(item), System.currentTimeMillis()/1000 + 3600, false, false)
        prefs.edit().putBoolean("notifications", true).putStringSet("wishlist", setOf(item.id)).commit()
        fun posted(): android.service.notification.StatusBarNotification {
            repeat(20) {
                manager.activeNotifications.firstOrNull { it.id == 71 }?.let { return it }
                Thread.sleep(100)
            }
            error("Favorite notification was not posted")
        }
        try {
            assertEquals(1, FavoriteAlerts.matches(snapshot, setOf(item.id)).size)
            val slotTime = java.time.ZonedDateTime.now().withHour(3).withMinute(0)
            FavoriteAlerts.check(context, snapshot, 3, prefsName, slotTime)
            val first = posted()
            assertTrue(first.notification.extras.getString("android.title").orEmpty().contains("Nightwatch notification test"))
            FavoriteAlerts.check(context, snapshot, 3, prefsName, slotTime)
            val second = posted()
            assertEquals(first.postTime, second.postTime)
            FavoriteAlerts.check(context, snapshot, 9, prefsName, slotTime.withHour(9))
            val morningTwin = posted()
            assertEquals(first.postTime, morningTwin.postTime)
        } finally {
            manager.cancel(71)
            prefs.edit().clear().commit()
            context.getSharedPreferences("${prefsName}_delivery", 0).edit().clear().commit()
        }
    }
}
