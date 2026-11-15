package app.nightwatch.store

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in network smoke test. Never submits credentials or touches the password field. */
@RunWith(AndroidJUnit4::class)
class RiotLivePageTest {
    @Test fun actualRiotFormIsLightAndAcceptsUsernameInOrder() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_riot") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var web: RiotWebView
        fun find(view: View): RiotWebView? {
            if(view is RiotWebView) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        fun js(script: String): String {
            val latch = CountDownLatch(1)
            var answer = ""
            instrumentation.runOnMainSync { web.evaluateJavascript(script) { answer = it; latch.countDown() } }
            check(latch.await(5, TimeUnit.SECONDS))
            return answer
        }
        ActivityScenario.launch<RiotLoginActivity>(Intent(instrumentation.targetContext, RiotLoginActivity::class.java).putExtra(RiotLoginActivity.LOCALE, "tr-tr")).use { scenario ->
            scenario.onActivity { web = find(it.window.decorView)!! }
            val readyScript = "['auth.riotgames.com','authenticate.riotgames.com'].includes(location.hostname) && !!document.querySelector('input[name=username]') && !!document.querySelector('input[type=password]')"
            var ready = false
            repeat(40) {
                if(!ready) { ready = js(readyScript) == "true"; if(!ready) Thread.sleep(500) }
            }
            val diagnostic = if(ready) "" else js("JSON.stringify({host:location.hostname,title:document.title,inputs:Array.from(document.querySelectorAll('input')).map(e=>({name:e.name,type:e.type,id:e.id}))})")
            assertTrue("Riot login fields unavailable; no credentials submitted. $diagnostic", ready)
            assertEquals("false", js("matchMedia('(prefers-color-scheme:dark)').matches"))
            assertEquals("true", js("""
                (() => {
                    let e = document.querySelector('input[name=username]');
                    while(e && e !== document.body && e !== document.documentElement) {
                        const c = getComputedStyle(e).backgroundColor;
                        if(c === 'rgb(255, 255, 255)' || c === 'rgb(249, 249, 249)' || c === 'rgb(247, 247, 247)') return true;
                        e = e.parentElement;
                    }
                    return false;
                })()
            """.trimIndent()))
            // Do not overwrite anything supplied by the user or a password manager.
            assumeTrue(js("Array.from(document.querySelectorAll('input')).filter(e => e.type==='text' || e.type==='password').every(e => e.value.length===0)") == "true")
            scenario.onActivity { web.requestFocus() }
            js("document.querySelector('input[name=username]').focus()")
            Thread.sleep(300)
            var connection: InputConnection? = null
            instrumentation.runOnMainSync { connection = web.onCreateInputConnection(EditorInfo()) }
            val editor = requireNotNull(connection)
            try {
                for(char in "nightwatch_test123") {
                    instrumentation.runOnMainSync { editor.commitText(char.toString(), 1) }
                    Thread.sleep(80)
                }
                assertEquals("true", js("document.querySelector('input[name=username]').value === 'nightwatch_test123'"))
                instrumentation.runOnMainSync { editor.setSelection(5,5) }
                Thread.sleep(100)
                instrumentation.runOnMainSync { editor.commitText("X",1) }
                Thread.sleep(100)
                assertEquals("true", js("document.querySelector('input[name=username]').value === 'nightXwatch_test123'"))
            } finally {
                val length = js("document.querySelector('input[name=username]').value.length").toInt()
                instrumentation.runOnMainSync { editor.setSelection(0,length); editor.commitText("",1) }
                Thread.sleep(100)
                assertEquals("true", js("document.querySelector('input[name=username]').value.length === 0"))
            }
        }
    }
}
