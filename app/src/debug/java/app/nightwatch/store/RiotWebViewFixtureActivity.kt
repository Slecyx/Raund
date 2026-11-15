package app.nightwatch.store

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Debug-only host: a signed-in Riot cookie can close the real login before a fixture loads. */
class RiotWebViewFixtureActivity : ComponentActivity() {
    internal lateinit var fixture: RiotWebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixture = RiotWebView(this)
        setContentView(fixture)
    }
}
