package app.nightwatch.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionSecurityTest {
    @Test fun authRedirectRequiresExactHttpsHostPathAndState() {
        val good = "https://playvalorant.com/opt_in#access_token=test-access&id_token=test-id&state=expected"
        assertNotNull(SessionStore.sessionFromRedirect(good, "expected"))
        assertNull(SessionStore.sessionFromRedirect(good, "wrong"))
        assertNull(SessionStore.sessionFromRedirect(good.replace("https:", "http:"), "expected"))
        assertNull(SessionStore.sessionFromRedirect(good.replace("playvalorant.com", "playvalorant.com.attacker.invalid"), "expected"))
        assertNull(SessionStore.sessionFromRedirect(good.replace("/opt_in", "/different"), "expected"))
        assertNull(SessionStore.sessionFromRedirect(good.replace("&state=expected", ""), "expected"))
    }
    @Test fun keystoreCiphertextSurvivesAStoreInstanceRestart() {
        // A separate preference file avoids touching the user's live session.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "nightwatch_security_test"
        val name = "roundtrip-test"
        SessionStore(context, preferencesName).put(name, "test-only-session-value")
        assertEquals("test-only-session-value", SessionStore(context, preferencesName).get(name))
        val raw = context.getSharedPreferences(preferencesName, 0).getString(name, "")!!
        assertFalse(raw.contains("test-only-session-value"))
        context.getSharedPreferences(preferencesName, 0).edit().remove(name).commit()
    }
}
