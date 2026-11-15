package app.nightwatch.store

import org.json.JSONObject

internal object StoreFreshness {
    fun active(snapshot: StoreSnapshot, now: Long): StoreSnapshot {
        if(snapshot.isPreview) return snapshot.copy(offers = emptyList(), bundles = emptyList(), nightOffers = emptyList(), accessories = emptyList(), balances = emptyMap())
        val daily = snapshot.refreshEpochSeconds > now
        val night = (snapshot.nightMarketExpiresAt ?: snapshot.refreshEpochSeconds) > now
        val accessories = (snapshot.accessoryExpiresAt ?: snapshot.refreshEpochSeconds) > now
        return snapshot.copy(
            offers = if(daily) snapshot.offers else emptyList(),
            bundles = snapshot.bundles.filter { it.expiresAt > now },
            nightOffers = if(night) snapshot.nightOffers else emptyList(),
            nightMarketAvailable = snapshot.nightMarketAvailable && night,
            accessories = if(accessories) snapshot.accessories else emptyList(),
            balances = if(daily) snapshot.balances else emptyMap()
        )
    }
}

internal object StorePricing {
    const val VP = "85ad13f7-3d1b-5128-9eb2-7cd8ee0b5741"
    const val KC = "85ca954a-41f2-ce94-9b45-8ca3dd39a00d"
    fun cost(prices: JSONObject?, currency: String = VP): Int {
        val raw = prices?.opt(currency) as? Number ?: return -1
        val value = raw.toLong()
        return if(value in 0..Int.MAX_VALUE.toLong() && value.toDouble() == raw.toDouble()) value.toInt() else -1
    }
    fun skinId(value: Any): String {
        if(value is String) return value
        val offer = value as? JSONObject ?: return ""
        return offer.optJSONArray("Rewards").objects().firstOrNull {
            it.optString("ItemTypeID") == "e7c63390-eda7-46e0-bb7a-a6abdacd2433"
        }?.textOrNull("ItemID") ?: offer.textOrNull("OfferID") ?: offer.optString("OfferId")
    }
}
