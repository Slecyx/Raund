package app.nightwatch.store

import android.graphics.Bitmap
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EsportsLayoutAndReplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fixture = EsportsMatch("9876548", "Team Alpha", "Team Beta", 2, 1, "Champions · Test fixture", "Upper final", "result", "Today", "https://www.vlr.gg/9876548")

    private fun node(label: String): AccessibilityNodeInfo? {
        if(Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(root == null) return null
            if(root.text?.toString() == label || root.contentDescription?.toString() == label) return root
            for(i in 0 until root.childCount) find(root.getChild(i))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(label: String): AccessibilityNodeInfo {
        val until = System.currentTimeMillis() + 6000
        while(System.currentTimeMillis() < until) { node(label)?.let { return it }; Thread.sleep(80) }
        error("Missing control: $label")
    }
    private fun click(label: String) {
        var target: AccessibilityNodeInfo? = await(label)
        while(target != null && !target.isClickable) target = target.parent
        assertTrue("Could not click $label", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(350)
    }
    private fun capture(scenario: ActivityScenario<MainActivity>, name: String) {
        val copied = CountDownLatch(1)
        var result = -1
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            val shot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            bitmap = shot
            PixelCopy.request(activity.window, shot, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(copied.await(3, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        bitmap!!.let { shot ->
            File(context.getExternalFilesDir(null), "v48-$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
        }
    }
    @Test fun redesignedListAndReplayQueueKeepResultsHiddenUntilExplicitReveal() {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val cache = context.getSharedPreferences("esports_detail_cache", 0)
        val source = context.getSharedPreferences("esports_public_cache", 0)
        val oldFilter = prefs.getString("esports_last_filter", null)
        val oldSection = prefs.getString("esports_last_section", null)
        val oldQueue = prefs.getString(WatchLater.KEY, null)
        val oldDetail = cache.getString(fixture.id, null); val oldAt = cache.getLong("${fixture.id}_at", 0)
        val oldFeed = source.getString("feed", null)
        try {
            prefs.edit().putString("esports_last_filter", "results").putString("esports_last_section", "matches").commit()
            WatchLater.put(context, listOf(WatchLaterEntry(fixture, System.currentTimeMillis())))
            cache.edit().putString(fixture.id, Gson().toJson(EsportsDetail(fixture, "BO3", listOf("Ascent"), emptyList(),
                mapResults = listOf(EsportsMapResult("Ascent", 13, 7, "30m", "")))))
                .putLong("${fixture.id}_at", System.currentTimeMillis()).commit()
            source.edit().putString("feed", Gson().toJson(EsportsFeed(emptyList(), emptyList(), listOf(fixture), System.currentTimeMillis()))).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalTurkish provides false) {
                        NightwatchTheme("dark") { Surface(Modifier.fillMaxSize(), color = Ink) {
                            EsportsPage(emptySet(), {}, emptySet(), {}, null, {}, false, {})
                        } }
                    }
                } }
                await("Team Alpha"); await("Team Beta"); await("2 - 1")
                capture(scenario, "matches-dark")
                click("Results  ▾"); click("Watch later")
                await("Mark watched")
                assertNull(node("2 - 1"))
                capture(scenario, "watch-later-dark")
                click("Team Alpha"); await("Reveal result")
                assertNull(node("2 - 1"))
                click("Reveal result"); await("2 - 1")
                click("← Matches"); click("Mark watched")
                assertTrue(WatchLater.get(context).single().watched)
                click("To watch"); await("Mark unwatched")
                click("Remove")
                assertTrue(WatchLater.get(context).isEmpty())
            }
        } finally {
            prefs.edit().apply {
                if(oldFilter == null) remove("esports_last_filter") else putString("esports_last_filter", oldFilter)
                if(oldSection == null) remove("esports_last_section") else putString("esports_last_section", oldSection)
                if(oldQueue == null) remove(WatchLater.KEY) else putString(WatchLater.KEY, oldQueue)
            }.commit()
            cache.edit().apply { if(oldDetail == null) remove(fixture.id) else putString(fixture.id, oldDetail); putLong("${fixture.id}_at", oldAt) }.commit()
            source.edit().apply { if(oldFeed == null) remove("feed") else putString("feed", oldFeed) }.commit()
        }
    }
    @Test fun replayQueueDoesNotSubscribeMatchesOrChangeNotificationDedup() {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val before = prefs.getString("esports_matches", null)
        val oldQueue = prefs.getString(WatchLater.KEY, null)
        val gate = context.getSharedPreferences("nightwatch_delivery", 0)
        val beforeGate = gate.all
        try {
            val saved = WatchLater.put(context, listOf(WatchLaterEntry(fixture, 10)))
            WatchLater.put(context, saved.map { it.copy(watched = true) })
            assertEquals(before, prefs.getString("esports_matches", null))
            assertEquals(beforeGate, gate.all)
            assertNull(WatchLater.get(context).single().match.score1)
        } finally { prefs.edit().apply { if(oldQueue == null) remove(WatchLater.KEY) else putString(WatchLater.KEY, oldQueue) }.commit() }
    }
    @Test fun inventorySeriesCountsUpgradedOwnershipAndOpensMissingSkinAsNotOwned() {
        val directory = File(context.cacheDir, "series-fixture-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getCacheDir() = directory }
        val root = "00000000-0000-0000-0000-000000000001"
        val missing = "00000000-0000-0000-0000-000000000002"
        val level = "00000000-0000-0000-0000-000000000003"
        val account = LiveStoreAccount("Fixture player", "eu", StoreSnapshot(emptyList(), 0, true, false), null, null,
            inventory = OwnedInventory(listOf(level), System.currentTimeMillis()))
        var openedOwned: Boolean? = null
        var openedId: String? = null
        try {
            File(directory, "content-weapons.json").writeText("""{"data":[{"displayName":"Vandal","skins":[{"themeUuid":"theme","displayName":"Fixture Vandal","levels":[{"uuid":"$root","displayName":"Base"},{"uuid":"$level","displayName":"Level 2"}],"chromas":[]},{"themeUuid":"theme","displayName":"Missing Vandal","levels":[{"uuid":"$missing","displayName":"Base"}],"chromas":[]}]}]}""")
            File(directory, "content-themes.json").writeText("""{"data":[{"uuid":"theme","displayName":"Fixture series"}]}""")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalTurkish provides false, LocalContext provides isolated) {
                        NightwatchTheme("dark") { Surface(Modifier.fillMaxSize(), color = Ink) {
                            InventoryPage(account, false, {}, { skin, owned -> openedId = skin.id; openedOwned = owned }, {})
                        } }
                    }
                } }
                await("Series progress"); click("Series progress")
                await("Fixture series"); await("1/2")
                click("Fixture series"); await("Missing Vandal")
                capture(scenario, "inventory-series-dark")
                click("Missing Vandal")
                assertEquals(missing, openedId); assertEquals(false, openedOwned)
            }
        } finally { directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete() }
    }
}
