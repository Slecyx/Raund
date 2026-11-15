package app.nightwatch.store

enum class WeaponType { VANDAL, PHANTOM, OPERATOR, SHERIFF, CLASSIC, SHORTY, FRENZY, GHOST, BANDIT, STINGER, SPECTRE, BUCKY, JUDGE, BULLDOG, GUARDIAN, MARSHAL, OUTLAW, ARES, ODIN, MELEE, OTHER }

internal fun weaponTypeFromDisplayName(value: String): WeaponType {
    val normalized = value.uppercase(java.util.Locale.ROOT)
    return WeaponType.entries.asSequence()
        .filter { it != WeaponType.OTHER && it != WeaponType.MELEE }
        .sortedByDescending { it.name.length }
        .firstOrNull { Regex("(^|[^A-Z])${Regex.escape(it.name)}([^A-Z]|$)").containsMatchIn(normalized) }
        ?: if(listOf("KNIFE", "BLADE", "KARAMBIT", "AXE", "MELEE", "KUNAI", "FAN").any(normalized::contains)) WeaponType.MELEE
        else WeaponType.OTHER
}

data class StoreOffer(
    val id: String,
    val name: String,
    val collection: String,
    val weapon: WeaponType,
    val tier: String,
    val priceVp: Int,
    val palette: List<Long>,
    val imageUrl: String? = null,
    val variants: List<SkinVariant> = emptyList(),
    val currency: String = "VP",
    val originalPrice: Int? = null,
    val ownedLevelNames: List<String>? = null,
    val weaponName: String? = null,
    val isWeaponSkin: Boolean = false,
    val metadataPending: Boolean = false,
    val priceSource: String? = null,
    val skinThemeId: String? = null
)

internal fun StoreOffer.skinOffer() = isWeaponSkin || weapon != WeaponType.OTHER || collection == "weapons/skinlevels"
internal fun StoreOffer.weaponLabel() = weaponName?.takeIf { it.isNotBlank() }
    ?: weapon.name.lowercase(java.util.Locale.ROOT).replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }

data class StoreSnapshot(
    val offers: List<StoreOffer>,
    val refreshEpochSeconds: Long,
    val isPreview: Boolean,
    val nightMarketAvailable: Boolean,
    val bundles: List<StoreBundle> = emptyList(),
    val nightOffers: List<StoreOffer> = emptyList(),
    val accessories: List<StoreOffer> = emptyList(),
    val balances: Map<String, Int> = emptyMap(),
    val nightMarketExpiresAt: Long? = null,
    val accessoryExpiresAt: Long? = null
)

data class SkinVariant(val name: String, val image: String?, val video: String? = null)
data class StoreBundle(val id: String, val name: String, val image: String?, val price: Int?, val expiresAt: Long, val items: List<StoreOffer>)

interface StoreGateway {
    fun loadStore(): StoreSnapshot
}

class DemoStoreGateway : StoreGateway {
    override fun loadStore() = StoreSnapshot(
        offers = listOf(
            StoreOffer("ion-vandal", "Ion Vandal", "ION", WeaponType.VANDAL, "Premium", 1775, listOf(0xFF182530, 0xFF263A49, 0xFF9DE4FF), "https://media.valorant-api.com/weaponskins/596ce51d-40e3-dc21-b02d-b08d070a7883/displayicon.png"),
            StoreOffer("reaver-phantom", "Reaver Phantom", "REAVER", WeaponType.PHANTOM, "Premium", 1775, listOf(0xFF20192A, 0xFF38264E, 0xFFB794E8), "https://media.valorant-api.com/weaponskins/044b28ba-4c3b-d315-140d-d9a249da5567/displayicon.png"),
            StoreOffer("elderflame-op", "Elderflame Operator", "ELDERFLAME", WeaponType.OPERATOR, "Ultra", 2475, listOf(0xFF291713, 0xFF482119, 0xFFFF8268), "https://media.valorant-api.com/weaponskins/d722313d-43cb-b38d-7841-75880a3ed2cb/displayicon.png"),
            StoreOffer("neo-frontier", "Neo Frontier Sheriff", "NEO FRONTIER", WeaponType.SHERIFF, "Exclusive", 2175, listOf(0xFF272219, 0xFF42351F, 0xFFEAC273), "https://media.valorant-api.com/weaponskins/b73f455a-4d77-44e7-fd90-1db9db82a8f6/displayicon.png"),
            StoreOffer("prime-vandal", "Prime Vandal", "PRIME", WeaponType.VANDAL, "Premium", 1775, listOf(0xFF211E2B, 0xFF37304D, 0xFFFFD36B), "https://media.valorant-api.com/weaponskins/b9ee2457-481c-6776-3f5b-0ca8e8f90c89/displayicon.png"),
            StoreOffer("spectrum-phantom", "Spectrum Phantom", "SPECTRUM", WeaponType.PHANTOM, "Exclusive", 2675, listOf(0xFF152629, 0xFF1D4243, 0xFF74E8DD), "https://media.valorant-api.com/weaponskins/980fa063-436e-e51f-c38d-70a5b93a0f1c/displayicon.png"),
            StoreOffer("origin-operator", "Origin Operator", "ORIGIN", WeaponType.OPERATOR, "Premium", 1775, listOf(0xFF182621, 0xFF294138, 0xFF8BDFBD), "https://media.valorant-api.com/weaponskins/17831113-4ff0-a6c9-0b20-6f9c077d74a2/displayicon.png"),
            StoreOffer("singularity-sheriff", "Singularity Sheriff", "SINGULARITY", WeaponType.SHERIFF, "Exclusive", 2175, listOf(0xFF21182A, 0xFF3B2450, 0xFFE063F1), "https://media.valorant-api.com/weaponskins/bfd9e773-4376-1f6a-98f2-dc93f0c0607c/displayicon.png")
        ),
        refreshEpochSeconds = ((System.currentTimeMillis() / 1000 / 86400) + 1) * 86400,
        isPreview = true,
        nightMarketAvailable = false
    )
}
