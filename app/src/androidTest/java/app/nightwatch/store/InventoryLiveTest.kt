package app.nightwatch.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in check using the locally signed-in account; never logs IDs, tokens or item names. */
@RunWith(AndroidJUnit4::class)
class InventoryLiveTest {
    @Test fun ownedSkinLevelsCanBeReadForSignedInAccount() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_inventory") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context)
        assumeTrue(store.session() != null)
        val account = runBlocking { RiotLiveGateway(context).loadStore(store.validSession(), includeDetails = true) }
        assertNotNull("Owned skin endpoint did not return inventory", account.inventory)
        assertFalse("Owned skin request failed", account.warnings.contains("inventory"))
        val inventory = account.inventory!!
        assertTrue(inventory.skinLevelIds.size == inventory.skinLevelIds.distinct().size)
        if(inventory.skinLevelIds.isNotEmpty()) {
            val matched = ContentCatalog(context).ownedSkins(inventory.skinLevelIds)
            assertTrue("No owned skin names could be resolved", matched.isNotEmpty())
        }
    }
}
