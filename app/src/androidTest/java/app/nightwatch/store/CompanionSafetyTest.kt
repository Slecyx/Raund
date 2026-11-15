package app.nightwatch.store

import android.app.NotificationManager
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import androidx.test.core.app.ActivityScenario
import android.view.accessibility.AccessibilityNodeInfo

@RunWith(AndroidJUnit4::class)
class CompanionSafetyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun match(id: String = "9876501", status: String = "live") = EsportsMatch(id, "Alpha", "Beta", 2, 1, "Fixture event", "Final",
        status, "", "https://www.vlr.gg/$id/fixture", System.currentTimeMillis() + 60_000)

    @Test fun allNotificationBuildersUseTheSameSpoilerPreference() {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val old = prefs.getBoolean("esports_spoiler_mode", false)
        try {
            prefs.edit().putBoolean("esports_spoiler_mode", true).commit()
            val notifications = listOf(ScoreNotifications.item(context, match(), true), ScoreNotifications.summary(context, listOf(match())),
                ScoreNotifications.favoriteMatch(context, match()), ScoreNotifications.result(context, match(status = "result")))
            notifications.forEach {
                assertTrue(it.extras.getString("android.title").orEmpty().contains("•••"))
                assertFalse(it.extras.getString("android.title").orEmpty().contains("2 - 1"))
                assertFalse(it.extras.getString("android.text").orEmpty().contains("2 - 1"))
                assertNotNull(it.contentIntent)
            }
        } finally { prefs.edit().putBoolean("esports_spoiler_mode", old).commit() }
    }
    @Test fun notificationRoutesKeepDistinctIDsAndCanonicalizeUntrustedURLs() {
        val first = MatchNavigation.intent(context, match())
        val read = MatchNavigation.read(first)!!
        assertEquals("9876501", read.id)
        assertEquals("https://www.vlr.gg/9876501", read.url)
        first.putExtra(MatchNavigation.EXTRA, Gson().toJson(match().copy(url = "file:///data/private")))
        assertEquals("https://www.vlr.gg/9876501", MatchNavigation.read(first)?.url)
        assertNotEquals(MatchNavigation.pending(context, match(), 44), MatchNavigation.pending(context, match("9876502"), 44))
        assertNull(MatchNavigation.read(Intent().putExtra(MatchNavigation.EXTRA, "x".repeat(12_001))))
        assertNull(MatchNavigation.read(Intent().putExtra(MatchNavigation.EXTRA, "{}")))
    }
    @Test fun dismissingLiveUpdatePreventsRePromotionUntilExplicitFollow() {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val old = prefs.getStringSet("live_card_dismissed", emptySet()).orEmpty()
        val oldNow = prefs.getBoolean("now_bar", true)
        try {
            prefs.edit().putBoolean("now_bar", true).commit()
            LiveCardPolicy.resume(context, match().id)
            assertTrue(LiveCardPolicy.allowed(context, match()))
            LiveCardDismissReceiver().onReceive(context, Intent().putExtra("ids", arrayOf(match().id)))
            assertFalse(LiveCardPolicy.allowed(context, match()))
            assertFalse(ScoreNotifications.summary(context, listOf(match())).extras.getBoolean("android.requestPromotedOngoing"))
            LiveCardPolicy.resume(context, match().id)
            assertTrue(LiveCardPolicy.allowed(context, match()))
        } finally { prefs.edit().putStringSet("live_card_dismissed", old).putBoolean("now_bar", oldNow).commit() }
    }
    @Test fun widgetUsesAppLightAndDarkColorsAndAbsoluteTime() {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val old = prefs.getString("theme", "system")
        try {
            instrumentation.runOnMainSync {
                listOf("light" to Color.rgb(23,23,24), "dark" to Color.rgb(244,243,239)).forEach { (theme, foreground) ->
                    prefs.edit().putString("theme", theme).commit()
                    val content = WidgetContent("MATCH", "5 minutes left", "Alpha × Beta", "Fixture", "esports", match = match(),
                        deadline = 1_800_000_000_000L, checkedAt = 1_800_000_000_000L - 60_000)
                    val view = WidgetRenderer.views(context, 8899, content, 0).apply(context, FrameLayout(context))
                    assertEquals(foreground, view.findViewById<TextView>(R.id.widget_title).currentTextColor)
                    assertFalse(view.findViewById<TextView>(R.id.widget_badge).text.contains("left"))
                    assertTrue(view.findViewById<TextView>(R.id.widget_eyebrow).text.contains("·"))
                }
            }
        } finally { prefs.edit().putString("theme", old).commit() }
    }
    @Test fun unknownOwnedLevelAndFutureWeaponSurviveCatalogResolution() {
        val directory = File(context.cacheDir, "catalog-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getCacheDir() = directory }
        val root = "00000000-0000-0000-0000-000000000001"
        val upgraded = "00000000-0000-0000-0000-000000000002"
        val unknown = "00000000-0000-0000-0000-000000000003"
        try {
            File(directory, "content-weapons.json").writeText("""{"data":[{"displayName":"Future Weapon","skins":[{"displayName":"Future Set Weapon","levels":[{"uuid":"$root","displayName":"Base"},{"uuid":"$upgraded","displayName":"Level 2"}],"chromas":[]}]}]}""")
            File(directory, "content-weapons-skinlevels.json").writeText("""{"data":[]}""")
            val values = ContentCatalog(isolated).ownedSkins(listOf(upgraded, unknown))
            assertEquals(2, values.size)
            assertEquals("Future Weapon", values.first { it.id == root }.weaponName)
            assertTrue(values.first { it.id == root }.skinOffer())
            assertEquals(listOf("Level 2"), values.first { it.id == root }.ownedLevelNames)
            assertTrue(values.first { it.id == unknown }.metadataPending)
        } finally { directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete() }
    }
    @Test fun journalDeduplicatesRepeatedFailureReasonsWithoutChangingDeliveryGate() {
        val key = "fixture:${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences("notification_audit", 0)
        try {
            repeat(10) { DeliveryJournal.record(context, key, "permission") }
            assertEquals(1, DeliveryJournal.read(context).count { it.key == key && it.state == "permission" })
        } finally { prefs.edit().remove("permission:$key").commit() }
    }
    @Test fun notificationIntentOpensTheActualMatchDetailAndDoesNotReopenAfterLeaving() {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val cache = context.getSharedPreferences("esports_detail_cache", 0)
        val source = context.getSharedPreferences("esports_public_cache", 0)
        val fixture = match("9876509", "result")
        val id = fixture.id
        val oldDetail = cache.getString(id, null); val oldAt = cache.getLong("${id}_at", 0)
        val oldFeed = source.getString("feed", null)
        val oldSpoiler = prefs.getBoolean("esports_spoiler_mode", false)
        val oldGuide = prefs.getBoolean("quick_guide_seen", false)
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if(node == null) return null
            if(node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for(index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
            return null
        }
        fun waitFor(vararg labels: String): AccessibilityNodeInfo {
            val until = System.currentTimeMillis() + 8000
            while(System.currentTimeMillis() < until) {
                if(android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                labels.forEach { find(instrumentation.uiAutomation.rootInActiveWindow, it)?.let { found -> return found } }
                Thread.sleep(80)
            }
            error("Expected UI control: ${labels.joinToString()}")
        }
        fun click(vararg labels: String) {
            val until = System.currentTimeMillis() + 5000
            while(System.currentTimeMillis() < until) {
                if(android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                val candidates = mutableListOf<AccessibilityNodeInfo>()
                fun collect(node: AccessibilityNodeInfo?) {
                    if(node == null) return
                    if(node.contentDescription?.toString() in labels) candidates.add(0, node)
                    else if(node.text?.toString() in labels) candidates.add(node)
                    for(index in 0 until node.childCount) collect(node.getChild(index))
                }
                collect(instrumentation.uiAutomation.rootInActiveWindow)
                for(candidate in candidates) {
                    var target: AccessibilityNodeInfo? = candidate
                    while(target != null && !target.isClickable) target = target.parent
                    if(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { Thread.sleep(350); return }
                }
                Thread.sleep(100)
            }
            error("Could not activate ${labels.joinToString()}")
        }
        fun selectTab(vararg labels: String) {
            val until = System.currentTimeMillis() + 8000
            fun tab(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if(node == null) return null
                if(node.contentDescription?.toString() in labels) {
                    var actionable: AccessibilityNodeInfo? = node
                    // Selected tabs expose selected=true but no click action on Android.
                    while(actionable != null && !actionable.isClickable && !actionable.isSelected) actionable = actionable.parent
                    if(actionable != null) return actionable
                }
                for(index in 0 until node.childCount) tab(node.getChild(index))?.let { return it }
                return null
            }
            while(System.currentTimeMillis() < until) {
                // Compose replaces virtual semantics nodes during AnimatedContent transitions.
                // Query a fresh tree instead of clicking cached nodes from the preceding page.
                if(android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                val target = tab(instrumentation.uiAutomation.rootInActiveWindow)
                if(target?.isSelected == true) return
                target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(200)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "v47-route-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            error("Tab was not selected: ${labels.joinToString()}")
        }
        try {
            prefs.edit().putBoolean("esports_spoiler_mode", false).putBoolean("quick_guide_seen", true).commit()
            cache.edit().putString(id, Gson().toJson(EsportsDetail(fixture, "BO3", listOf("Ascent"), emptyList(),
                mapResults = listOf(EsportsMapResult("Ascent", 13, 7, "30m", "")))))
                .putLong("${id}_at", System.currentTimeMillis()).commit()
            source.edit().putString("feed", Gson().toJson(EsportsFeed(emptyList(), emptyList(), listOf(fixture), System.currentTimeMillis()))).commit()
            ActivityScenario.launch<MainActivity>(MatchNavigation.intent(context, fixture)).use { scenario ->
                assertNotNull(ScoreNotifications.result(context, fixture).contentIntent)
                scenario.onActivity { assertEquals(fixture.id, MatchNavigation.read(it.intent)?.id) }
                waitFor("Alpha"); waitFor("Beta"); waitFor("2 - 1")
                click("← Maçlar", "← Matches")
                selectTab("Favori", "Saved")
                waitFor("Favoriler", "Favorites")
                selectTab("Espor", "Esports")
                waitFor("Maçlar, takımlar ve turnuvalar", "Matches, teams and tournaments")
                Thread.sleep(300)
                assertNull("Consumed notification route should not reopen on tab changes", find(instrumentation.uiAutomation.rootInActiveWindow, "← Maçlar"))
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "← Matches"))
            }
        } finally {
            cache.edit().apply { if(oldDetail == null) remove(id) else putString(id, oldDetail); putLong("${id}_at", oldAt) }.commit()
            source.edit().apply { if(oldFeed == null) remove("feed") else putString("feed", oldFeed) }.commit()
            prefs.edit().putBoolean("esports_spoiler_mode", oldSpoiler).putBoolean("quick_guide_seen", oldGuide).commit()
        }
    }
}
