package app.nightwatch.store

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object InventoryParser {
    private const val SKIN_LEVEL = "e7c63390-eda7-46e0-bb7a-a6abdacd2433"

    fun skinLevels(response: JSONObject): List<String> {
        val direct = response.optJSONArray("Entitlements")
        val grouped = response.optJSONArray("EntitlementsByTypes")
        require(direct != null || grouped != null) { "Inventory response has no entitlement list" }
        val ids = linkedSetOf<String>()
        fun collect(items: JSONArray) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val id = item.optString("ItemID").trim()
                require(id.isNotEmpty() && id != "null") { "Inventory item has no ID" }
                ids += id.lowercase(Locale.ROOT)
            }
        }
        direct?.let(::collect)
        if (grouped != null) for (index in 0 until grouped.length()) {
            val group = grouped.getJSONObject(index)
            if (group.optString("ItemTypeID", SKIN_LEVEL).equals(SKIN_LEVEL, ignoreCase = true)) {
                collect(group.getJSONArray("Entitlements"))
            }
        }
        return ids.toList()
    }
}
