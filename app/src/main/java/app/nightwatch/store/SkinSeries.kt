package app.nightwatch.store

internal data class SkinSeries(val id: String, val name: String, val owned: List<StoreOffer>, val missing: List<StoreOffer>) {
    val total: Int get() = owned.size + missing.size
}

/** Counts known weapon skins by their catalog theme UUID, never by guessed bundle membership. */
internal object SkinSeriesProgress {
    fun group(catalog: List<StoreOffer>, inventory: List<StoreOffer>, names: Map<String, String>): List<SkinSeries> {
        val owned = inventory.filterNot { it.metadataPending }.map { it.id }.toSet()
        return catalog.filter { it.skinOffer() && !it.metadataPending && !it.skinThemeId.isNullOrBlank() }
            .distinctBy { it.id }.groupBy { it.skinThemeId!! }.mapNotNull { (id, members) ->
                val title = names[id]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val have = members.filter { it.id in owned }
                if(members.size < 2 || have.isEmpty()) return@mapNotNull null
                SkinSeries(id, title, have, members.filterNot { it.id in owned })
            }.sortedWith(compareBy<SkinSeries> { it.missing.isEmpty() }.thenBy { it.missing.size }.thenBy { it.name })
    }
}
