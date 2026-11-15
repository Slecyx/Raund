package app.nightwatch.store

import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RiotWebViewTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: RiotWebView

    @Test fun lightPageAndNativeKeyboardPreserveOrderCompositionAndMiddleInsertion() {
        ActivityScenario.launch(RiotWebViewFixtureActivity::class.java).use { scenario ->
            val loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                web = activity.fixture
                // Test only: replace the remote document with a local fixture. No account data is entered.
                web.webChromeClient = null
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) { loaded.countDown() }
                }
                web.loadDataWithBaseURL("https://fixture.invalid/", """
                    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <meta name="color-scheme" content="light dark"><style>
                    body { background: white; color: black; } input { font-size:20px; margin:20px; }
                    @media(prefers-color-scheme:dark) { body { background:black; color:white; } }
                    </style></head><body><input id="username" autocomplete="off"><input id="password" type="password"></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            assertTrue("Local fixture did not load", loaded.await(10, TimeUnit.SECONDS))
            assertEquals("false", js("matchMedia('(prefers-color-scheme: dark)').matches"))
            assertEquals("\"rgb(255, 255, 255)\"", js("getComputedStyle(document.body).backgroundColor"))
            if(Build.VERSION.SDK_INT >= 34) scenario.onActivity { assertFalse(web.isAutoHandwritingEnabled) }
            scenario.onActivity { web.requestFocus() }
            js("document.getElementById('username').focus()")
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            val connection = connection()
            for(char in "nightwatch123") {
                instrumentation.runOnMainSync { connection.commitText(char.toString(), 1) }
                Thread.sleep(40)
            }
            assertEquals("\"nightwatch123\"", js("document.getElementById('username').value"))
            instrumentation.runOnMainSync { connection.setSelection(5, 5) }
            Thread.sleep(80)
            instrumentation.runOnMainSync { connection.commitText("X", 1) }
            Thread.sleep(80)
            assertEquals("\"nightXwatch123\"", js("document.getElementById('username').value"))
            instrumentation.runOnMainSync { connection.deleteSurroundingText(1, 0) }
            Thread.sleep(80)
            assertEquals("\"nightwatch123\"", js("document.getElementById('username').value"))
            instrumentation.runOnMainSync { connection.setSelection(13, 13); connection.setComposingText("abc", 1) }
            Thread.sleep(80)
            instrumentation.runOnMainSync { connection.setComposingText("abcde", 1); connection.finishComposingText() }
            Thread.sleep(80)
            assertEquals("\"nightwatch123abcde\"", js("document.getElementById('username').value"))
            js("document.getElementById('password').focus()")
            Thread.sleep(200)
            val passwordConnection = connection()
            instrumentation.runOnMainSync { passwordConnection.commitText("Test-only_42!", 1) }
            Thread.sleep(100)
            assertEquals("true", js("document.getElementById('password').value === 'Test-only_42!' && document.getElementById('password').type === 'password'"))
        }
    }
    private fun connection(): InputConnection {
        var result: InputConnection? = null
        instrumentation.runOnMainSync { result = web.onCreateInputConnection(EditorInfo()) }
        return requireNotNull(result) { "Editor input connection missing" }
    }
    private fun js(script: String): String {
        val ready = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { value = it; ready.countDown() } }
        check(ready.await(5, TimeUnit.SECONDS)) { "Fixture script timed out" }
        return value
    }
}
