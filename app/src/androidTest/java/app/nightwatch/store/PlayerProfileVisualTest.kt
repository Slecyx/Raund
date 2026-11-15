package app.nightwatch.store

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
class PlayerProfileVisualTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val playerUrl = "https://www.vlr.gg/player/9810/alfajer"
    private fun profile(): PlayerProfile {
        val agents = listOf(
            PlayerAgent("Cypher", "https://www.vlr.gg/img/vlr/game/agents/cypher.png", "48% 12", "1.24", "247", "1.38", "164", "83", "60", "286", "76%", ".91", ".31", "24", "12", "8"),
            PlayerAgent("Veto", null, "32% 8", "1.08", "225", "1.16", "151", "58", "50", "190", "72%", ".84", ".28", "18", "9", "7"),
            PlayerAgent("Jett", "https://www.vlr.gg/img/vlr/game/agents/jett.png", "20% 5", ".98", "211", "1.02", "148", "42", "41", "121", "68%", ".79", ".25", "12", "6", "6"))
        fun match(id: String, opponent: String, won: Boolean, rating: String) = PlayerMatchPerformance(id, "VCT EMEA", "Playoffs · Upper Final",
            "FNATIC", opponent, null, null, if(won) 2 else 1, if(won) 0 else 2, won, "04 Oct · 18:00", "https://www.vlr.gg/$id",
            listOf(PlayerMapPerformance("Cypher", null, "Ascent", if(won) "13-7" else "9-13", rating, 22, 14, 6, won, "https://www.vlr.gg/$id?game=1"),
                PlayerMapPerformance("Jett", null, "Lotus", if(won) "13-10" else "11-13", if(won) "1.17" else ".91", 18, 16, 4, won, "https://www.vlr.gg/$id?game=2")))
        return PlayerProfile(EsportsPlayer("Alfajer", "Emir Ali Beder", null, playerUrl), "Türkiye", agents, "60d",
            listOf(PlayerSocial("@Alfajervl", "https://x.com/Alfajervl")),
            listOf(PlayerAchievement("VALORANT Champions 2025", "https://www.vlr.gg/event/1", listOf("Playoffs – 2nd · $80,000 · FNATIC"), "2025"),
                PlayerAchievement("VCT EMEA Stage 1", "https://www.vlr.gg/event/2", listOf("Playoffs – 1st · $20,000 · FNATIC"), "2025")),
            listOf(match("9001", "Team Heretics", true, "1.42"), match("9002", "Team Liquid", false, ".88"), match("9003", "BBL Esports", true, "1.26")))
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        if(android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node == null) return null
            if(node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for(index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(label: String): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + 6000
        while(System.currentTimeMillis() < deadline) { find(label)?.let { return it }; Thread.sleep(80) }
        error("Missing player control: $label")
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = await(label)
        while(node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(350)
    }
    private fun capture(scenario: ActivityScenario<MainActivity>, name: String) {
        val done = CountDownLatch(1); var result = -1; var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val shot = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
            bitmap = shot
            PixelCopy.request(activity.window, shot, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        bitmap!!.let { shot -> File(context.getExternalFilesDir(null), "v49-player-$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle() }
    }
    @Test fun playerDossierRendersEverySectionAndRoutesMatches() {
        val prefs = context.getSharedPreferences("esports_explore_cache", 0)
        val key = "player_v3_9810_60d"; val at = "${key}_at"
        val matchKey = "player_matches_v3_9810"; val matchAt = "${matchKey}_at"
        val old = prefs.getString(key, null); val oldAt = prefs.getLong(at, 0)
        val oldMatches = prefs.getString(matchKey, null); val oldMatchesAt = prefs.getLong(matchAt, 0)
        var opened: String? = null
        try {
            val fixture = profile()
            prefs.edit().putString(key, Gson().toJson(fixture)).putLong(at, System.currentTimeMillis())
                .putString(matchKey, Gson().toJson(fixture.matches)).putLong(matchAt, System.currentTimeMillis()).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalTurkish provides false) { NightwatchTheme("dark") {
                        Surface(Modifier.fillMaxSize(), color = Ink) { PlayerScreen(playerUrl, emptyList(), true, {}, {}, { opened = it.id }) }
                    } }
                } }
                await("Alfajer"); await("1.14")
                capture(scenario, "overview")
                click("Matches"); await("Team Heretics"); capture(scenario, "matches")
                click("Team Heretics"); assertEquals("9001", opened)
                click("Agents"); await("Agent pool"); await("Cypher"); capture(scenario, "agents")
                click("Career"); await("Tournament record"); await("VALORANT Champions 2025"); capture(scenario, "career")
            }
        } finally { prefs.edit().apply {
            if(old == null) remove(key) else putString(key, old); putLong(at, oldAt)
            if(oldMatches == null) remove(matchKey) else putString(matchKey, oldMatches); putLong(matchAt, oldMatchesAt)
        }.commit() }
    }
}
