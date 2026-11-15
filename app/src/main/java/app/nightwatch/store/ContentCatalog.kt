package app.nightwatch.store

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.textOrNull(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

/** Public game assets only. No Riot credentials are sent to this service. */
class ContentCatalog(private val context: Context) {
    companion object {
        private val cacheLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val pendingItems = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, StoreOffer>>()
    }
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONArray>>()
    private var skinMemory: List<StoreOffer>? = null
    private var skinMemoryAt = 0L
    fun data(path: String, language: String = "en-US", force: Boolean = false): JSONArray {
        require(language in listOf("en-US", "tr-TR"))
        val key = path + if(language == "en-US") "" else "-$language"
        synchronized(cacheLocks.getOrPut(key) { Any() }) {
        val maxAge = if(path in setOf("weapons", "bundles", "weapons/skinlevels", "buddies", "playercards", "sprays", "playertitles", "flex")) 3_600_000L else 86_400_000L
        memory[key]?.takeIf { CatalogFreshness.fresh(it.first, System.currentTimeMillis(), if(force) 60_000L else maxAge) }?.let { return it.second }
        val file = File(context.cacheDir, "content-${key.replace('/', '-')}.json")
        val atomic = AtomicFile(file)
        val cached = runCatching { JSONObject(String(atomic.readFully(), Charsets.UTF_8)).getJSONArray("data") }.getOrNull()
        if (cached != null && CatalogFreshness.fresh(file.lastModified(), System.currentTimeMillis(), if(force) 60_000L else maxAge)) return cached.also { memory[key] = file.lastModified() to it }
        return try {
            val connection = URL("https://valorant-api.com/v1/$path?language=$language").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            val raw = try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
            val result = JSONObject(raw).getJSONArray("data")
            val output = atomic.startWrite()
            try { output.write(raw.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch(error: Exception) { atomic.failWrite(output); throw error }
            result.also { memory[key] = System.currentTimeMillis() to it }
        } catch (error: Exception) {
            cached ?: throw error
        }
        }
    }

    fun localizedSkinNames(language: String): Map<String, String> = data("weapons", language).objects()
        .flatMap { it.optJSONArray("skins").objects() }.flatMap { skin ->
            skin.optJSONArray("levels").objects().map { it.optString("uuid") to skin.optString("displayName") }
        }.filter { it.first.isNotBlank() && it.second.isNotBlank() }.toMap()

    @Synchronized fun skins(force: Boolean = false): List<StoreOffer> {
        if(!force && System.currentTimeMillis() - skinMemoryAt < 3_600_000L) skinMemory?.let { return it }
        return data("weapons", force = force).objects().flatMap { weapon ->
        val type = weaponTypeFromDisplayName(weapon.optString("displayName"))
        weapon.optJSONArray("skins").objects().mapNotNull { skin ->
            val levels = skin.optJSONArray("levels").objects()
            val base = levels.firstOrNull() ?: return@mapNotNull null
            val name = skin.optString("displayName")
            StoreOffer(
                id = base.optString("uuid"), name = name, collection = name.substringBeforeLast(' ', name),
                weapon = type, tier = "", priceVp = -1, palette = listOf(0xFF1C1C20, 0xFF25252B, 0xFFBBBBCC),
                weaponName = weapon.optString("displayName"), isWeaponSkin = true, skinThemeId = skin.textOrNull("themeUuid"),
                // Some newly shipped skins receive their display icon a little after the
                // catalog entry. A chroma render is a valid, much better fallback than a
                // blank Store/Inventory tile.
                imageUrl = skin.textOrNull("displayIcon") ?: skin.optJSONArray("chromas").objects().firstNotNullOfOrNull {
                    it.textOrNull("fullRender") ?: it.textOrNull("displayIcon")
                } ?: base.textOrNull("displayIcon"),
                variants = (skin.optJSONArray("chromas").objects().map { SkinVariant(it.optString("displayName"), it.textOrNull("fullRender") ?: it.textOrNull("displayIcon"), it.textOrNull("streamedVideo")) } +
                    levels.map { SkinVariant(it.optString("displayName"), it.textOrNull("displayIcon") ?: skin.textOrNull("displayIcon") ?: base.textOrNull("displayIcon"), it.textOrNull("streamedVideo")) }).distinctBy { it.name + it.image + it.video }
            )
        }
        }.also { skinMemory = it; skinMemoryAt = System.currentTimeMillis() }
    }

    fun ownedSkins(levelIds: Collection<String>): List<StoreOffer> {
        if(levelIds.isEmpty()) return emptyList()
        val wanted = levelIds.toSet()
        val matched = runCatching { data("weapons").objects() }.getOrDefault(emptyList()).flatMap { it.optJSONArray("skins").objects() }
            .filter { skin -> skin.optJSONArray("levels").objects().any { it.optString("uuid") in wanted } }
        val roots = matched.mapNotNull { it.optJSONArray("levels")?.optJSONObject(0)?.textOrNull("uuid") }.toSet()
        val mapped = runCatching { skins() }.getOrDefault(emptyList()).filter { it.id in roots }.map { offer ->
            val skin = matched.firstOrNull { it.optJSONArray("levels")?.optJSONObject(0)?.optString("uuid") == offer.id }
            offer.copy(ownedLevelNames = skin?.optJSONArray("levels").objects()
                .filter { it.optString("uuid") in wanted }.mapNotNull { it.textOrNull("displayName") }.distinct())
        }
        val mappedRoots = mapped.map { it.id }.toSet()
        val knownLevels = matched.filter { it.optJSONArray("levels")?.optJSONObject(0)?.optString("uuid") in mappedRoots }
            .flatMap { it.optJSONArray("levels").objects().map { level -> level.optString("uuid") } }.toSet()
        val unresolved = wanted - knownLevels
        // One batched lookup rather than a request fan-out during a catalog outage.
        val levelMetadata = if(unresolved.isEmpty()) emptyMap() else runCatching {
            data("weapons/skinlevels").objects().associateBy { it.optString("uuid") }
        }.getOrDefault(emptyMap())
        return (mapped + unresolved.map { id ->
            levelMetadata[id]?.let { value -> PendingCatalog.skin(id).copy(
                name = value.textOrNull("displayName") ?: "Item · ${id.take(8)}",
                imageUrl = value.textOrNull("displayIcon"), metadataPending = value.textOrNull("displayName") == null)
            } ?: PendingCatalog.skin(id)
        }).distinctBy { it.id }
    }

    fun playerCardArt(id: String): PlayerCardArt? {
        val card = data("playercards").objects().firstOrNull { it.optString("uuid").equals(id, true) } ?: return null
        return PlayerCardArt(id, card.textOrNull("smallArt") ?: card.textOrNull("displayIcon"),
            card.textOrNull("wideArt") ?: card.textOrNull("largeArt"))
    }

    private fun single(path: String, id: String): JSONObject? = runCatching {
        val connection = URL("https://valorant-api.com/v1/$path/$id?language=en-US").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        val raw = try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
        JSONObject(raw).optJSONObject("data")
    }.getOrNull()

    fun bundle(id: String): JSONObject? = data("bundles").objects().firstOrNull { it.optString("uuid").equals(id, true) }
        ?: single("bundles", id)

    fun item(id: String, type: String, price: Int, currency: String = "VP"): StoreOffer {
        if (type == "Currency") return StoreOffer(id, when(id) { "e59aa87c-4cbf-517a-5983-6e81511be9b7" -> "Radianite"; "85ca954a-41f2-ce94-9b45-8ca3dd39a00d" -> "Kingdom Credits"; else -> "Currency" }, "", WeaponType.OTHER, "", -1, listOf(0xFF1C1C20,0xFF25252B,0xFFBBBBCC))
        val skinType = type == "e7c63390-eda7-46e0-bb7a-a6abdacd2433" || type == "EquippableSkinLevel"
        val pendingKey = "$type:$id"
        // New bundles can reach the authenticated Store a few minutes before public content
        // metadata. Keep a short negative cache, but do not hide an image for five minutes.
        pendingItems[pendingKey]?.takeIf { CatalogFreshness.fresh(it.first, System.currentTimeMillis(), 60_000L) }
            ?.let { return it.second.copy(priceVp = price, currency = currency) }
        fun parentSkin(force: Boolean): StoreOffer? {
            val catalog = skins(force)
            catalog.find { it.id.equals(id, true) }?.let { return it }
            val weapons = data("weapons")
            val skin = weapons.objects().flatMap { weapon ->
                val weaponType = weaponTypeFromDisplayName(weapon.optString("displayName"))
                weapon.optJSONArray("skins").objects().map { weaponType to it }
            }.firstOrNull { (_, skin) -> skin.optJSONArray("levels").objects().any { it.optString("uuid").equals(id, true) } }
                ?: return null
            val root = skin.second.optJSONArray("levels")?.optJSONObject(0)?.optString("uuid") ?: return null
            return catalog.find { it.id.equals(root, true) }?.copy(id = id)
        }
        if(skinType) (runCatching { parentSkin(false) ?: parentSkin(true) }.getOrNull())?.let {
            pendingItems.remove(pendingKey)
            return it.copy(priceVp = price, currency = currency)
        }
        val preferred = when (type) {
            "dd3bf334-87f3-40bd-b043-682a57a8dc3a", "EquippableCharmLevel" -> "buddies"
            "3f296c07-64c3-494c-923b-fe692a4fa1bd", "PlayerCard" -> "playercards"
            "d5f120f8-ff8c-4aac-92ea-f2b5acbe9475", "Spray" -> "sprays"
            "de7caa6b-adf7-4588-bbd1-143831e786c6", "Title" -> "playertitles"
            "03a572de-4234-17f8-1d6e-ab8e137e35e9", "Flex" -> "flex"
            else -> "weapons/skinlevels"
        }
        // A known item type must never be resolved against a different category.
        val knownType = skinType || preferred != "weapons/skinlevels"
        val paths = if(knownType) listOf(preferred) else
            (listOf(preferred) + listOf("buddies", "playercards", "sprays", "playertitles", "flex")).distinct()
        fun find(force: Boolean): Pair<String, JSONObject>? = paths.firstNotNullOfOrNull { path ->
            runCatching { data(path, force = force).objects().firstOrNull { value ->
                value.optString("uuid").equals(id, true) || value.optJSONArray("levels").objects().any { level -> level.optString("uuid").equals(id, true) }
            }?.let { path to it } }.getOrNull()
        }
        val found = find(false) ?: find(true) ?: paths.firstNotNullOfOrNull { path -> single(path, id)?.let { path to it } }
        val path = found?.first ?: preferred
        val metadata = found?.second
        val level = metadata?.optJSONArray("levels").objects().find { it.optString("uuid").equals(id, true) }
        val image = listOfNotNull(
            level?.textOrNull("displayIcon"), metadata?.textOrNull("displayIcon"),
            metadata?.textOrNull("fullIcon"), metadata?.textOrNull("fullTransparentIcon"),
            metadata?.textOrNull("largeArt"), metadata?.textOrNull("wideArt"),
            metadata?.textOrNull("smallArt"), metadata?.textOrNull("largeIcon"),
            metadata?.textOrNull("fullRender"), level?.textOrNull("fullIcon"), level?.textOrNull("fullTransparentIcon"),
            metadata?.textOrNull("wallpaper")
        ).firstOrNull()
        val displayName = (if(path == "playertitles") metadata?.textOrNull("titleText") else null) ?: metadata?.textOrNull("displayName") ?: "Item · ${id.take(8)}"
        val weapon = if(path == "weapons/skinlevels") weaponTypeFromDisplayName(displayName) else WeaponType.OTHER
        return StoreOffer(id, displayName, if(path == "weapons/skinlevels") displayName.substringBeforeLast(' ', displayName) else path, weapon, "", price,
            listOf(0xFF1C1C20, 0xFF25252B, 0xFFBBBBCC), image, currency = currency,
            isWeaponSkin = skinType || path == "weapons/skinlevels", metadataPending = metadata == null || displayName.startsWith("Item ·"))
            .also { if(it.metadataPending) {
                pendingItems[pendingKey] = System.currentTimeMillis() to it
                if(pendingItems.size > 500) pendingItems.entries.sortedBy { row -> row.value.first }.take(100).forEach { row -> pendingItems.remove(row.key) }
            } else pendingItems.remove(pendingKey) }
    }
}
