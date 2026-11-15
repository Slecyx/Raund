package app.nightwatch.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in structural check; never prints account IDs, tokens, or player names. */
@RunWith(AndroidJUnit4::class)
class PlayerCardDiagnosticTest {
    @Test fun inspectCachedIdentityFields() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("card_diagnostic") == "true")
        val account = SessionStore(InstrumentationRegistry.getInstrumentation().targetContext).cached()
        val matches = account?.career?.matches.orEmpty().mapNotNull { it.detailsJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() } }
        val own = matches.mapNotNull { details ->
            details.optJSONArray("players").objects().firstOrNull { it.optString("subject") == account?.accountId }
        }
        val card = own.firstOrNull()?.opt("playerCard")
        println("CARD_DIAGNOSTIC matches=${matches.size} ownPlayers=${own.size} identityKeys=${own.firstOrNull()?.keys()?.asSequence()?.filter { it.contains("ident", true) || it.contains("card", true) }?.toList()} cardType=${card?.javaClass?.simpleName} cardKeys=${(card as? JSONObject)?.keys()?.asSequence()?.toList()}")
    }
}
