package app.nightwatch.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class InventoryParserTest {
    @Test fun readsScopedResponseAndDeduplicates() {
        assertEquals(listOf("abc", "def"), InventoryParser.skinLevels(JSONObject("""{"Entitlements":[{"ItemID":"ABC"},{"ItemID":"abc"},{"ItemID":"def"}]}""")))
    }
    @Test fun readsGroupedResponseAndIgnoresOtherTypes() {
        assertEquals(listOf("skin"), InventoryParser.skinLevels(JSONObject("""{"EntitlementsByTypes":[{"ItemTypeID":"e7c63390-eda7-46e0-bb7a-a6abdacd2433","Entitlements":[{"ItemID":"skin"}]},{"ItemTypeID":"other","Entitlements":[{"ItemID":"card"}]}]}""")))
    }
    @Test fun emptyInventoryIsValid() {
        assertEquals(emptyList<String>(), InventoryParser.skinLevels(JSONObject("""{"Entitlements":[]}""")))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedResponseIsNotAnEmptyInventory() {
        InventoryParser.skinLevels(JSONObject("""{"error":"unavailable"}"""))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedItemIsNotSilentlyIgnored() {
        InventoryParser.skinLevels(JSONObject("""{"Entitlements":[{}]}"""))
    }
}
