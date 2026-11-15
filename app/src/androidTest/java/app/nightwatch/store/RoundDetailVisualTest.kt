package app.nightwatch.store

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class RoundDetailVisualTest {
    @Test fun roundSelectorOpensFocusedDetail() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val details = """{"players":[{"subject":"self","teamId":"Red","characterId":"a","stats":{"score":3200,"roundsPlayed":2}},{"subject":"other","teamId":"Blue","characterId":"b"}],"roundResults":[{"roundNum":0,"winningTeam":"Red","roundResult":"Eliminated","playerStats":[{"subject":"self","kills":[{"killer":"self","victim":"other","roundTime":21000}],"damage":[{"damage":180,"headshots":1,"bodyshots":1,"legshots":0}],"economy":{"loadoutValue":3900,"spent":2900,"remaining":1000}}]},{"roundNum":1,"winningTeam":"Blue","roundResult":"BombDetonated","bombPlanter":"other","plantSite":"A","playerStats":[{"subject":"self","kills":[],"damage":[{"damage":45}],"economy":{"loadoutValue":2000,"spent":1800,"remaining":200}}]}]}"""
        val match = MatchSummary("fixture", "Ascent", "Unrated", true, "1-1", 1, 1, 0, null, System.currentTimeMillis(), details, "self")
        fun find(node: AccessibilityNodeInfo?, name: String): AccessibilityNodeInfo? {
            if(node == null) return null
            if(node.text?.toString() == name) return node
            for(i in 0 until node.childCount) find(node.getChild(i),name)?.let { return it }
            return null
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalTurkish provides true) {
                        NightwatchTheme("dark") { MatchDetailPage(match, emptyMap(), emptyMap()) {} }
                    }
                }
            }
            Thread.sleep(1200)
            instrument.uiAutomation.takeScreenshot()?.let { shot ->
                FileOutputStream(File(instrument.targetContext.getExternalFilesDir(null),"v12-round-before.png")).use {
                    shot.compress(Bitmap.CompressFormat.PNG,100,it)
                }
            }
            val node = find(instrument.uiAutomation.rootInActiveWindow,"Raund Replay")
            assertNotNull(node)
            var clickable = node
            while(clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertTrue(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            Thread.sleep(500)
            assertNotNull(find(instrument.uiAutomation.rootInActiveWindow,"Raunt seç"))
            assertNotNull(find(instrument.uiAutomation.rootInActiveWindow,"Galibiyet"))
            assertNotNull(find(instrument.uiAutomation.rootInActiveWindow,"Rauntta ne oldu"))
            assertNotNull(find(instrument.uiAutomation.rootInActiveWindow,"Öldürmen"))
            instrument.uiAutomation.takeScreenshot()?.let { shot ->
                FileOutputStream(File(instrument.targetContext.getExternalFilesDir(null),"v12-round-detail.png")).use {
                    shot.compress(Bitmap.CompressFormat.PNG,100,it)
                }
            }
        }
    }
}
