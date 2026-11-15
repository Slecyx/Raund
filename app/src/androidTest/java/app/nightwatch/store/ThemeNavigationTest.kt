package app.nightwatch.store

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ThemeNavigationTest {
    @Test fun themeControlsChangeRealUiAndPersistWithoutReplacingSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("nightwatch",0)
        val previous = prefs.getString("theme",null)
        val sessionBefore = SessionStore(context).session()
        var testWindow: android.view.Window? = null
        fun find(node: AccessibilityNodeInfo?, name: String): AccessibilityNodeInfo? {
            if(node == null) return null
            if(node.text?.toString() == name || node.contentDescription?.toString() == name) return node
            for(index in 0 until node.childCount) find(node.getChild(index), name)?.let { return it }
            return null
        }
        fun click(vararg names: String) {
            val deadline = System.currentTimeMillis()+5000
            while(System.currentTimeMillis()<deadline) {
                if(android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val candidates = mutableListOf<AccessibilityNodeInfo>()
                fun collect(node: AccessibilityNodeInfo?) {
                    if(node == null) return
                    if(node.contentDescription?.toString() in names) candidates.add(0, node)
                    else if(node.text?.toString() in names) candidates.add(node)
                    for(index in 0 until node.childCount) collect(node.getChild(index))
                }
                collect(root)
                for(node in candidates) {
                    var parent: AccessibilityNodeInfo? = node
                    while(parent != null && !parent.isClickable) parent = parent.parent
                    if(parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        Thread.sleep(500)
                        return
                    }
                }
                Thread.sleep(100)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                FileOutputStream(File(context.getExternalFilesDir(null),"v11-theme-test-failure.png")).use { shot.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            error("Missing theme/navigation control: ${names.joinToString()}; root=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}; windows=${instrumentation.uiAutomation.windows.map { it.type }}")
        }
        fun awaitTheme(dark: Boolean): Bitmap {
            val until = System.currentTimeMillis() + 8000
            var lastColor = 0
            while(System.currentTimeMillis() < until) {
                val window = requireNotNull(testWindow)
                var bitmap: Bitmap? = null
                val copied = java.util.concurrent.CountDownLatch(1)
                var copyResult = -1
                instrumentation.runOnMainSync {
                    val destination = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
                    bitmap = destination
                    android.view.PixelCopy.request(window, destination, { result -> copyResult = result; copied.countDown() },
                        android.os.Handler(android.os.Looper.getMainLooper()))
                }
                check(copied.await(3, java.util.concurrent.TimeUnit.SECONDS) && copyResult == android.view.PixelCopy.SUCCESS) {
                    "Could not capture rendered Activity window: $copyResult"
                }
                bitmap?.let { bitmap ->
                    lastColor = android.graphics.Color.red(bitmap.getPixel(10, 150))
                    if(if(dark) lastColor < 60 else lastColor > 220) return bitmap
                    File(context.getExternalFilesDir(null), "v47-theme-render-failure.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                Thread.sleep(100)
            }
            error("Theme did not render: dark=$dark, backgroundRed=$lastColor")
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                it.onActivity { activity -> testWindow = activity.window }
                Thread.sleep(1200)
                click("Ayarlar","Settings")
                click("Görünüm ve dil", "Appearance & language")
                click("Koyu","Dark")
                assertEquals("dark",prefs.getString("theme",null))
                val dark = awaitTheme(true)
                FileOutputStream(File(context.getExternalFilesDir(null),"v11-dark-settings.png")).use { stream -> dark.compress(Bitmap.CompressFormat.PNG,100,stream) }
                click("Kariyer","Career")
                Thread.sleep(800)
                val career = awaitTheme(true)
                FileOutputStream(File(context.getExternalFilesDir(null),"v11-dark-career.png")).use { stream -> career.compress(Bitmap.CompressFormat.PNG,100,stream) }
                click("Ayarlar","Settings")
                click("Görünüm ve dil", "Appearance & language")
                click("Açık","Light")
                assertEquals("light",prefs.getString("theme",null))
                val light = awaitTheme(false)
                FileOutputStream(File(context.getExternalFilesDir(null),"v11-light-settings.png")).use { stream -> light.compress(Bitmap.CompressFormat.PNG,100,stream) }
                assertTrue("Theme controls must preserve the session",sessionBefore == SessionStore(context).session())
            }
        } finally {
            prefs.edit().apply { if(previous == null) remove("theme") else putString("theme",previous) }.commit()
        }
    }
}
