package app.nightwatch.store

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHost
import android.os.Bundle
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetRenderingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun everyProviderIsRegisteredAndResizableInBothDirections() {
        val registered = AppWidgetManager.getInstance(context).installedProviders
        RaundWidgetProvider.providers.forEach { type ->
            val provider = registered.firstOrNull { it.provider == ComponentName(context, type) }
            assertNotNull("Missing provider: ${type.name}", provider)
            assertEquals(3, provider!!.resizeMode)
            assertNotNull(provider.configure)
            assertTrue(provider.minResizeWidth < provider.minWidth)
            assertTrue(provider.minResizeHeight < provider.minHeight)
        }
    }

    @Test fun realWidgetHostAcceptsResizeUpdatesAndKeepsPerWidgetConfiguration() {
        val host = AppWidgetHost(context, 773388)
        val manager = AppWidgetManager.getInstance(context)
        val prefs = context.getSharedPreferences("raund_widgets", 0)
        val id = host.allocateAppWidgetId()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, StoreWidgetProvider::class.java)))
            prefs.edit().putString("mode_$id", "store").commit()
            listOf(120 to 65, 260 to 220, 380 to 280).forEach { (width, height) ->
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                }
                manager.updateAppWidgetOptions(id, options)
                StoreWidgetProvider().onAppWidgetOptionsChanged(context, manager, id, options)
                assertEquals(width, manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
                assertEquals(height, manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
                assertEquals("store", prefs.getString("mode_$id", null))
            }
        } finally {
            host.deleteAppWidgetId(id)
            prefs.edit().remove("mode_$id").commit()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun actualRemoteViewsFitCompactMediumAndExpandedSizes() {
        val content = WidgetContent("MAĞAZAM", "04:23 kaldı", "Reaver Vandal", "Dört kişisel teklif", "store",
            listOf("Reaver Vandal", "Prime Phantom", "Oni Sheriff", "Champions 2026 Knife").map { WidgetRow(it, "1775 VP") })
        verify(content, "store")
        verify(WidgetContent("GECE PAZARI", "3 gün", "Skin 1", "6 teklif", "store",
            (1..6).map { WidgetRow("Skin $it", "900 VP") }, nightMarket = true), "night")
        verify(WidgetContent("CANLI MAÇ", "1 - 0", "FNATIC  1 - 0  Team Heretics", "Champions · Upper Final", "esports",
            (1..4).map { WidgetRow("Team Alpha × Team Beta", "2 - 1") }), "score")
    }

    private fun verify(content: WidgetContent, name: String) {
        instrumentation.runOnMainSync {
            listOf(Triple(120, 65, 0), Triple(260, 110, 0), Triple(260, 220, 3), Triple(380, 280, 4)).forEach { (width, height, rows) ->
                val parent = FrameLayout(context)
                val view = WidgetRenderer.views(context, 901, content, rows, height < 110).apply(context, parent)
                val density = context.resources.displayMetrics.density
                val w = (width * density).toInt(); val h = (height * density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
                fun checkBounds(node: View) {
                    if(node.visibility == View.GONE) return
                    val location = IntArray(2)
                    var top = node.top; var left = node.left
                    var ancestor = node.parent as? View
                    while(ancestor != null && ancestor != view) { top += ancestor.top; left += ancestor.left; ancestor = ancestor.parent as? View }
                    assertTrue("$name ${width}x$height clips view ${node.id}: bottom=${top + node.height}, root=$h", top + node.height <= h + 1)
                    assertTrue("$name ${width}x$height horizontal overflow ${node.id}", left + node.width <= w + 1)
                    if(node is ViewGroup) (0 until node.childCount).forEach { checkBounds(node.getChildAt(it)) }
                }
                checkBounds(view)
                if(content.nightMarket && rows > 0) {
                    fun titles(node: View): List<String> = (if(node is TextView && node.visibility == View.VISIBLE) listOf(node.text.toString()) else emptyList()) +
                        (if(node is ViewGroup) (0 until node.childCount).flatMap { titles(node.getChildAt(it)) } else emptyList())
                    val texts = titles(view)
                    content.rows.forEach { assertTrue("Missing night offer ${it.title} at ${width}x$height", it.title in texts) }
                }
                if(height >= 110) assertTrue(view.findViewById<TextView>(R.id.widget_eyebrow).text.isNotBlank())
                assertNotNull(view.findViewById<View>(R.id.widget_refresh))
                assertNotNull(view.findViewById<View>(R.id.widget_config))
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(context.getExternalFilesDir(null), "v48-widget-$name-${width}x$height.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun independentWidgetInstancesKeepDifferentModes() {
        val prefs = context.getSharedPreferences("raund_widgets", 0)
        try {
            prefs.edit().putString("mode_99001", "store").putString("mode_99002", "matches").commit()
            assertEquals("store", WidgetModels.read(context, 99001, "auto").page)
            assertEquals("esports", WidgetModels.read(context, 99002, "auto").page)
        } finally { prefs.edit().remove("mode_99001").remove("mode_99002").commit() }
    }

    @Test fun widgetConfigurationLetsTheUserChooseAllMatchesAndASeparateStoreWidget() {
        val prefs = context.getSharedPreferences("raund_widgets", 0)
        val settings = context.getSharedPreferences("nightwatch", 0)
        val language = settings.getString("language", null)
        settings.edit().putString("language", "EN").commit()
        fun click(label: String) {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if(node == null) return null
                if(node.text?.toString() == label) return node
                (0 until node.childCount).forEach { find(node.getChild(it))?.let { found -> return found } }
                return null
            }
            val deadline = System.currentTimeMillis() + 4000
            while(System.currentTimeMillis() < deadline) {
                var node = find(instrumentation.uiAutomation.rootInActiveWindow)
                while(node != null && !node.isClickable) node = node.parent
                if(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { Thread.sleep(200); return }
                Thread.sleep(50)
            }
            error("Widget option not found: $label")
        }
        try {
            ActivityScenario.launch<WidgetConfigurationActivity>(Intent(context, WidgetConfigurationActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 99003)).use {
                click("Matches and live scores")
                click("All live and upcoming matches")
                click("Save")
            }
            assertEquals("matches", prefs.getString("mode_99003", null))
            assertTrue(prefs.getBoolean("all_99003", false))
            ActivityScenario.launch<WidgetConfigurationActivity>(Intent(context, WidgetConfigurationActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 99004)).use {
                click("Daily store"); click("Save")
            }
            assertEquals("store", prefs.getString("mode_99004", null))
            assertEquals("matches", prefs.getString("mode_99003", null))
        } finally {
            prefs.edit().remove("mode_99003").remove("all_99003").remove("mode_99004").remove("all_99004").commit()
            settings.edit().apply { if(language == null) remove("language") else putString("language", language) }.commit()
        }
    }
}
