package app.nightwatch.store

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DeliveryConcurrencyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun thirtyTwoSimultaneousProducersDeliverOnlyOnce() {
        val name = "delivery_test_${UUID.randomUUID()}"
        val count = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val delivered = executor.invokeAll((1..32).map { Callable {
                NotificationDelivery.once(context, "result:123", name) { count.incrementAndGet() }
            } }).count { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, delivered)
            assertEquals(1, count.get())
            // Re-read durable preferences after the competing calls have completed.
            assertTrue(context.getSharedPreferences(name, 0).contains("result:123"))
            assertFalse(NotificationDelivery.once(context, "result:123", name) { count.incrementAndGet() })
        } finally { executor.shutdownNow(); context.getSharedPreferences(name, 0).edit().clear().commit() }
    }

    @Test fun unsuccessfulDeliveryCanRetryButSuccessfulDeliveryCannot() {
        val name = "delivery_retry_${UUID.randomUUID()}"
        try {
            try { NotificationDelivery.once(context, "store:day", name) { error("notification manager failure") }; fail("Expected failure") }
            catch(_: IllegalStateException) { }
            assertTrue(NotificationDelivery.once(context, "store:day", name) { })
            assertFalse(NotificationDelivery.once(context, "store:day", name) { fail("Duplicate delivery") })
            assertTrue(NotificationDelivery.once(context, "store:next-day", name) { })
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }

    @Test fun resultFromServiceAndWorkerIsNotRepostedAfterDismissal() {
        if(Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue("Notification permission must be established before exercising delivery", ScoreNotifications.permitted(context))
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val oldAlerts = prefs.getBoolean("match_alerts", true)
        val oldResults = prefs.getBoolean("notify_esports_results", true)
        prefs.edit().putBoolean("match_alerts", true).putBoolean("notify_esports_results", true).commit()
        val match = EsportsMatch("test-${UUID.randomUUID()}", "Alpha", "Beta", 2, 1, "Test Event", "Final", "result", "", "")
        val manager = context.getSystemService(NotificationManager::class.java)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val calls = executor.invokeAll((1..12).map { Callable { NotificationDelivery.result(context, match) } })
            assertEquals(1, calls.count { it.get(5, TimeUnit.SECONDS) })
            Thread.sleep(200)
            assertEquals(1, manager.activeNotifications.count { it.id == ScoreNotifications.resultId(match) })
            manager.cancel(ScoreNotifications.resultId(match))
            Thread.sleep(200)
            assertFalse(NotificationDelivery.result(context, match))
            assertFalse(manager.activeNotifications.any { it.id == ScoreNotifications.resultId(match) })
        } finally {
            executor.shutdownNow(); manager.cancel(ScoreNotifications.resultId(match))
            prefs.edit().putBoolean("match_alerts", oldAlerts).putBoolean("notify_esports_results", oldResults)
                .putStringSet("esports_result_notified", prefs.getStringSet("esports_result_notified", emptySet()).orEmpty() - match.id).commit()
            context.getSharedPreferences("nightwatch_delivery", 0).edit().remove("result:${match.id}").commit()
        }
    }

    @Test fun incompleteOrLiveScoreCannotSendAFinalResult() {
        val match = EsportsMatch("no-final", "Alpha", "Beta", 1, 0, "", "", "live", "", "")
        assertFalse(NotificationDelivery.result(context, match))
        assertFalse(NotificationDelivery.result(context, match.copy(status = "result", score2 = null)))
    }

    @Test fun completedMapPostsOnceEvenWhenMultiplePollersObserveIt() {
        if(Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val oldAlerts = prefs.getBoolean("match_alerts", true)
        val oldMaps = prefs.getBoolean("notify_esports_maps", true)
        prefs.edit().putBoolean("match_alerts", true).putBoolean("notify_esports_maps", true).commit()
        val match = EsportsMatch("map-${UUID.randomUUID()}", "Alpha", "Beta", 1, 0, "Test Event", "Final", "live", "", "")
        val completion = EsportsMapCompletion(match, EsportsMapResult("Ascent", 13, 9, "42:00", "Alpha pick"), 1)
        val manager = context.getSystemService(NotificationManager::class.java)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val calls = executor.invokeAll((1..12).map { Callable { NotificationDelivery.mapResult(context, completion) } })
            assertEquals(1, calls.count { it.get(5, TimeUnit.SECONDS) })
            Thread.sleep(200)
            assertEquals(1, manager.activeNotifications.count { it.id == ScoreNotifications.mapResultId(match, 1) })
            manager.cancel(ScoreNotifications.mapResultId(match, 1))
            assertFalse(NotificationDelivery.mapResult(context, completion))
        } finally {
            executor.shutdownNow(); manager.cancel(ScoreNotifications.mapResultId(match, 1))
            prefs.edit().putBoolean("match_alerts", oldAlerts).putBoolean("notify_esports_maps", oldMaps).commit()
            context.getSharedPreferences("nightwatch_delivery", 0).edit().remove("map:${match.id}:1").commit()
        }
    }
}
