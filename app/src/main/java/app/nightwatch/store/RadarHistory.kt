package app.nightwatch.store

import android.content.Context
import com.google.gson.Gson
import java.security.MessageDigest
import kotlin.math.abs

internal data class StoreObservation(val expiresAt: Long, val observedAt: Long, val offers: List<StoreOffer>)
internal data class StoreChange(val arrived: List<StoreOffer>, val left: List<StoreOffer>)
internal data class RadarStats(val rotations: Int, val uniqueSkins: Int, val topWeapon: String?, val topWeaponCount: Int)

internal object RadarHistory {
    fun accountKey(name: String, region: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$region\u0000$name".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun record(history: List<StoreObservation>, snapshot: StoreSnapshot, now: Long): List<StoreObservation> {
        if(snapshot.isPreview || snapshot.refreshEpochSeconds <= now || snapshot.offers.isEmpty()) return history
        val latest = history.maxByOrNull { it.expiresAt }
        // Server remaining-duration rounding must not create a new rotation every refresh.
        if(latest != null && snapshot.refreshEpochSeconds < latest.expiresAt - 120) return history
        val same = latest != null && abs(latest.expiresAt - snapshot.refreshEpochSeconds) <= 120
        val observation = StoreObservation(
            if(same) latest.expiresAt else snapshot.refreshEpochSeconds,
            if(same) latest.observedAt else now,
            snapshot.offers.distinctBy { it.id }.map { it.copy(variants = emptyList()) }
        )
        return (history.filterNot { same && it.expiresAt == latest.expiresAt } + observation)
            .sortedBy { it.expiresAt }.takeLast(30)
    }

    fun changes(previous: StoreObservation, current: StoreObservation): StoreChange {
        val before = previous.offers.map { it.id }.toSet()
        val after = current.offers.map { it.id }.toSet()
        return StoreChange(current.offers.filter { it.id !in before }, previous.offers.filter { it.id !in after })
    }

    // Bundle contents are not necessarily individually purchasable at the returned price.
    fun purchasableFavorites(snapshot: StoreSnapshot?, ids: Set<String>, now: Long): List<StoreOffer> {
        if(snapshot == null || snapshot.isPreview) return emptyList()
        val active = StoreFreshness.active(snapshot, now)
        return (active.offers + if(active.nightMarketAvailable) active.nightOffers else emptyList())
            .filter { it.id in ids && it.currency == "VP" && it.priceVp >= 0 }
            .groupBy { it.id }.values.map { offers -> offers.minBy { it.priceVp } }.sortedBy { it.priceVp }
    }

    fun summary(history: List<StoreObservation>): RadarStats {
        val allOffers = history.flatMap { it.offers }
        val unique = allOffers.map { it.id }.toSet().size
        val top = allOffers.filter { it.skinOffer() }
            .groupingBy { it.weaponLabel() }.eachCount()
            .maxByOrNull { it.value }
        return RadarStats(history.size, unique, top?.key, top?.value ?: 0)
    }
}

internal class RadarRepository(context: Context, preferencesName: String = "nightwatch_radar") {
    private val secure = SessionStore(context, preferencesName)
    fun read(account: LiveStoreAccount): List<StoreObservation> = synchronized(lock) {
        val id = account.accountId?.takeIf { it.isNotBlank() } ?: return@synchronized emptyList()
        runCatching {
            secure.get(RadarHistory.accountKey(id, account.region))?.let {
                Gson().fromJson(it, Array<StoreObservation>::class.java).toList()
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
    fun record(account: LiveStoreAccount) = synchronized(lock) {
        val id = account.accountId?.takeIf { it.isNotBlank() } ?: return@synchronized
        val previous = read(account)
        val next = RadarHistory.record(previous, account.snapshot, System.currentTimeMillis()/1000)
        if(next != previous) secure.put(RadarHistory.accountKey(id, account.region), Gson().toJson(next))
    }
    fun clear(account: LiveStoreAccount) = synchronized(lock) {
        val id = account.accountId?.takeIf { it.isNotBlank() } ?: return@synchronized
        secure.put(RadarHistory.accountKey(id, account.region), "[]")
    }
    private companion object { val lock = Any() }
}
