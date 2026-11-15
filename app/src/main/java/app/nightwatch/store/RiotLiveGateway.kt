package app.nightwatch.store

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class RiotSession(val accessToken: String, val idToken: String)

data class MatchSummary(
    val id: String,
    val map: String,
    val mode: String,
    val won: Boolean?,
    val score: String,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val rrChange: Int?,
    val startedAt: Long,
    val detailsJson: String? = null,
    val playerId: String = "",
    val agentId: String = "",
    val mapImage: String? = null
)

data class CareerSummary(
    val rank: String,
    val rankTier: Int,
    val rankedRating: Int,
    val matches: List<MatchSummary>,
    val hasMore: Boolean = false,
    val rankSchema: Int = 1
)

data class ProgressSummary(
    val accountLevel: Int,
    val accountXp: Int,
    val battlePassLevel: Int?,
    val battlePassXp: Int,
    val battlePassXpRequired: Int,
    val battlePassName: String? = null,
    val rewards: List<PassReward> = emptyList(),
    val missions: List<MissionProgress> = emptyList()
)
data class OwnedInventory(val skinLevelIds: List<String>, val checkedAt: Long, val schemaVersion: Int = 1)
data class PlayerCardArt(val id: String, val small: String?, val wide: String?, val source: String? = null)
data class PassReward(val level: Int, val name: String, val image: String?, val unlocked: Boolean)
data class MissionProgress(val name: String, val progress: Int, val target: Int, val complete: Boolean, val xp: Int)

data class LiveStoreAccount(
    val name: String,
    val region: String,
    val snapshot: StoreSnapshot,
    val career: CareerSummary?,
    val progress: ProgressSummary?,
    val updatedAt: Long = System.currentTimeMillis(),
    val warnings: List<String> = emptyList(),
    val accountId: String? = null,
    val inventory: OwnedInventory? = null,
    val playerCard: PlayerCardArt? = null
)

internal fun playerCardIdFromMatch(details: JSONObject?, puuid: String): String? {
    val player = details?.optJSONArray("players").objects().firstOrNull { it.optString("subject") == puuid }
    return player?.textOrNull("playerCard")
        ?: player?.optJSONObject("playerIdentity")?.textOrNull("playerCardId")
}

/**
 * Uses the same private services as the PC game client. These endpoints are undocumented and may
 * change without notice. Credentials are never handled here; only short-lived tokens returned by
 * Riot's web sign-in page are accepted. SessionStore encrypts retained tokens on the device.
 */
class RiotLiveGateway(private val context: Context) {
    private val catalog = ContentCatalog(context)
    suspend fun loadStore(session: RiotSession, includeDetails: Boolean = true, historyLimit: Int = 20, onStoreReady: suspend (LiveStoreAccount) -> Unit = {}): LiveStoreAccount = withContext(Dispatchers.IO) {
        val authHeaders = mapOf("Authorization" to "Bearer ${session.accessToken}")
        val user = stage("Riot account") { requestJson("https://auth.riotgames.com/userinfo", "GET", authHeaders) }
        val puuid = user.getString("sub")
        val account = user.optJSONObject("acct")
        val accountName = listOfNotNull(account?.optString("game_name"), account?.optString("tag_line"))
            .filter { it.isNotBlank() }.joinToString("#").ifBlank { "Riot player" }

        val entitlement = stage("Riot entitlement") { requestJson(
            "https://entitlements.auth.riotgames.com/api/token/v1", "POST", authHeaders, "{}"
        ).getString("entitlements_token") }

        val region = runCatching {
            requestJson(
                "https://riot-geo.pas.si.riotgames.com/pas/v1/product/valorant", "PUT", authHeaders,
                JSONObject().put("id_token", session.idToken).toString()
            ).getJSONObject("affinities").getString("live")
        }.getOrElse {
            // Riot's geo edge occasionally serves a certificate chain Android cannot complete.
            // Authentication has already succeeded at this point, so derive a conservative shard
            // fallback from the verified account country instead of reporting a password failure.
            fallbackRegion(user.optString("country"))
        }
        val version = stage("Client version") { requestJson("https://valorant-api.com/v1/version", "GET") }.getJSONObject("data")
            .optString("riotClientVersion", "release-01.00-shipping-1-1")
        val clientHeaders = authHeaders + mapOf(
            "X-Riot-Entitlements-JWT" to entitlement,
            "X-Riot-ClientVersion" to version,
            "X-Riot-ClientPlatform" to CLIENT_PLATFORM
        )
        val store = stage("Personal store") { try {
            requestJson("https://pd.$region.a.pvp.net/store/v3/storefront/$puuid", "POST", clientHeaders, "{}")
        } catch (_: Exception) {
            requestJson("https://pd.$region.a.pvp.net/store/v2/storefront/$puuid", "GET", clientHeaders)
        } }
        val panel = store.getJSONObject("SkinsPanelLayout")
        val rawOffers = panel.optJSONArray("SingleItemStoreOffers")?.takeIf { it.length() > 0 }
            ?: panel.getJSONArray("SingleItemOffers")
        val offers = (0 until rawOffers.length()).mapNotNull { index ->
            parseOffer(rawOffers.get(index))
        }
        if (offers.isEmpty()) error("Riot mağazası teklif döndürmedi")
        val duration = panel.optLong("SingleItemOffersRemainingDurationInSeconds", 0L)
        val hasNightMarket = store.optJSONObject("BonusStore")
            ?.optJSONArray("BonusStoreOffers")?.length()?.let { it > 0 } ?: false
        val initial = LiveStoreAccount(
            name = accountName,
            region = region.uppercase(),
            snapshot = StoreSnapshot(
                offers = offers,
                refreshEpochSeconds = Instant.now().epochSecond + duration,
                isPreview = false,
                nightMarketAvailable = hasNightMarket
            ),
            career = null,
            progress = null,
            accountId = puuid,
            inventory = SessionStore(context).cached()?.takeIf { it.accountId == puuid }?.inventory,
            playerCard = SessionStore(context).cached()?.takeIf { it.accountId == puuid }?.playerCard
        )
        onStoreReady(initial)
        if (!includeDetails) return@withContext initial.copy(snapshot = runCatching { enrichStore(initial.snapshot, store, region, puuid, clientHeaders) }.getOrDefault(initial.snapshot))
        coroutineScope {
            val cachedAccount = SessionStore(context).cached()?.takeIf { it.accountId == puuid }
            val cachedCareer = cachedAccount?.career
            val cachedProgress = cachedAccount?.progress
            val career = async { runCatching { loadCareer(region, puuid, clientHeaders, historyLimit) } }
            val progress = async { runCatching { loadProgress(region, puuid, clientHeaders) } }
            val storefront = async { runCatching { enrichStore(initial.snapshot, store, region, puuid, clientHeaders) } }
            val inventory = async { runCatching { loadInventory(region, puuid, clientHeaders) } }
            val identity = async { runCatching {
                requestJson("https://pd.$region.a.pvp.net/personalization/v2/players/$puuid/playerloadout", "GET", clientHeaders)
                    .optJSONObject("Identity")?.textOrNull("PlayerCardID")
            } }
            val cachedCardId = cachedCareer?.matches?.asSequence()?.mapNotNull { match ->
                playerCardIdFromMatch(match.detailsJson?.let { runCatching { JSONObject(it) }.getOrNull() }, puuid)
            }?.firstOrNull()
            val cachedCard = cachedCardId?.let { runCatching { catalog.playerCardArt(it)?.copy(source = "match") }.getOrNull() }
            if(cachedCard != null) onStoreReady(initial.copy(playerCard = cachedCard))
            val currentCardId = identity.await().getOrNull()
            val currentCard = currentCardId?.let { runCatching { catalog.playerCardArt(it)?.copy(source = "loadout") }.getOrNull() }
            if(currentCard != null) onStoreReady(initial.copy(playerCard = currentCard))
            val c = career.await(); val p = progress.await(); val s = storefront.await(); val i = inventory.await()
            val cardId = c.getOrNull()?.matches?.asSequence()?.mapNotNull { match ->
                val raw = match.detailsJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                playerCardIdFromMatch(raw, puuid)
            }?.firstOrNull()
            val card = currentCard ?: cardId?.let { runCatching { catalog.playerCardArt(it)?.copy(source = "match") }.getOrNull() }
            // Storefront, career and progress are independent Riot endpoints. A temporary
            // failure in one must not make already-loaded career data disappear.
            initial.copy(career = c.getOrNull() ?: cachedCareer, progress = p.getOrNull() ?: cachedProgress, snapshot = s.getOrDefault(initial.snapshot),
                inventory = i.getOrNull() ?: initial.inventory,
                playerCard = card ?: cachedCard ?: initial.playerCard,
                warnings = listOfNotNull(if(c.isFailure) "career" else null, if(p.isFailure) "progress" else null, if(s.isFailure) "bundles" else null,
                    if(i.isFailure) "inventory" else null))
        }
    }

    private fun loadInventory(region: String, puuid: String, headers: Map<String, String>): OwnedInventory {
        val result = requestJson("https://pd.$region.a.pvp.net/store/v1/entitlements/$puuid/e7c63390-eda7-46e0-bb7a-a6abdacd2433", "GET", headers)
        val ids = InventoryParser.skinLevels(result)
        return OwnedInventory(ids, System.currentTimeMillis())
    }

    private suspend fun loadCareer(region: String, puuid: String, headers: Map<String, String>, limit: Int): CareerSummary {
        val updates = runCatching {
            requestJson("https://pd.$region.a.pvp.net/mmr/v1/players/$puuid/competitiveupdates?startIndex=0&endIndex=12", "GET", headers)
        }.getOrNull()
        val rankedMatches = updates?.optJSONArray("Matches") ?: JSONArray()
        val currentRank = runCatching {
            val now = Instant.now()
            val seasonId = catalog.data("seasons").objects().firstOrNull {
                it.optString("type").endsWith("::Act") && runCatching {
                    !now.isBefore(Instant.parse(it.optString("startTime"))) && now.isBefore(Instant.parse(it.optString("endTime")))
                }.getOrDefault(false)
            }?.textOrNull("uuid")
            CurrentRankParser.parse(requestJson("https://pd.$region.a.pvp.net/mmr/v1/players/$puuid", "GET", headers), seasonId)
        }.getOrDefault(CurrentRank(-1, -1))
        val tier = currentRank.tier
        val rr = currentRank.rr
        val rrByMatch = mutableMapOf<String, Int>()
        for (index in 0 until rankedMatches.length()) {
            rankedMatches.optJSONObject(index)?.let { match ->
                rrByMatch[match.optString("MatchID")] = match.optInt("RankedRatingEarned", 0)
            }
        }
        val history = requestJson("https://pd.$region.a.pvp.net/match-history/v1/history/$puuid?startIndex=0&endIndex=$limit", "GET", headers).optJSONArray("History") ?: JSONArray()
        val limiter = Semaphore(4)
        val cachedMatches = SessionStore(context).cached()?.career?.matches?.associateBy { it.id }.orEmpty()
        val matches = coroutineScope { (0 until history.length()).map { index -> async { limiter.withPermit {
            val item = history.optJSONObject(index) ?: return@withPermit null
            val matchId = item.optString("MatchID")
            if (matchId.isBlank()) return@withPermit null
            val details = cachedMatches[matchId]?.detailsJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: runCatching {
                requestJson("https://pd.$region.a.pvp.net/match-details/v1/matches/$matchId", "GET", headers)
            }.getOrNull()
            parseMatch(item, details, puuid, rrByMatch[matchId])
        } } }.awaitAll().filterNotNull() }
        return CareerSummary(rankName(tier), tier, rr, matches, history.length() >= limit)
    }

    private fun parseMatch(history: JSONObject, details: JSONObject?, puuid: String, rrChange: Int?): MatchSummary {
        val info = details?.optJSONObject("matchInfo")
        val players = details?.optJSONArray("players") ?: JSONArray()
        var player: JSONObject? = null
        for (index in 0 until players.length()) {
            val candidate = players.optJSONObject(index)
            if (candidate?.optString("subject") == puuid) { player = candidate; break }
        }
        val stats = player?.optJSONObject("stats")
        val teams = details?.optJSONArray("teams") ?: JSONArray()
        val playerTeam = player?.optString("teamId").orEmpty()
        var ownTeam: JSONObject? = null
        var enemyTeam: JSONObject? = null
        for (index in 0 until teams.length()) {
            val team = teams.optJSONObject(index) ?: continue
            if (team.optString("teamId") == playerTeam) ownTeam = team else enemyTeam = team
        }
        val mapPath = info?.optString("mapId").takeUnless { it.isNullOrBlank() } ?: history.optString("MapID")
        val queue = info?.optString("queueID").takeUnless { it.isNullOrBlank() } ?: history.optString("QueueID")
        val mapMetadata = runCatching { catalog.data("maps").objects().find { it.optString("mapUrl") == mapPath } }.getOrNull()
        return MatchSummary(
            id = history.optString("MatchID"),
            map = mapMetadata?.textOrNull("displayName") ?: friendlyMap(mapPath),
            mode = friendlyMode(queue),
            won = ownTeam?.let { it.optBoolean("won") },
            score = if (ownTeam != null && enemyTeam != null) "${ownTeam.optInt("roundsWon")}-${enemyTeam.optInt("roundsWon")}" else "—",
            kills = stats?.optInt("kills") ?: 0,
            deaths = stats?.optInt("deaths") ?: 0,
            assists = stats?.optInt("assists") ?: 0,
            rrChange = rrChange,
            startedAt = info?.optLong("gameStartMillis") ?: history.optLong("GameStartTime"),
            detailsJson = details?.toString(), playerId = puuid,
            agentId = player?.optString("characterId").orEmpty(),
            mapImage = mapMetadata?.textOrNull("splash") ?: mapMetadata?.textOrNull("listViewIcon")
        )
    }

    private fun loadProgress(region: String, puuid: String, headers: Map<String, String>): ProgressSummary {
        val account = requestJson("https://pd.$region.a.pvp.net/account-xp/v1/players/$puuid", "GET", headers).optJSONObject("Progress")
        val response = requestJson("https://pd.$region.a.pvp.net/contracts/v1/contracts/$puuid", "GET", headers)
        val contracts = response.optJSONArray("Contracts").objects()
        val now = Instant.now()
        val season = catalog.data("seasons").objects().firstOrNull {
            it.optString("type").endsWith("::Act") && runCatching { now >= Instant.parse(it.optString("startTime")) && now < Instant.parse(it.optString("endTime")) }.getOrDefault(false)
        }
        val definition = catalog.data("contracts").objects().firstOrNull {
            it.optJSONObject("content")?.optString("relationType") == "Season" && it.optJSONObject("content")?.optString("relationUuid") == season?.optString("uuid")
        }
        val battlePass = contracts.firstOrNull { it.optString("ContractDefinitionID") == definition?.optString("uuid") }
        val bpLevel = battlePass?.optInt("ProgressionLevelReached")
        val bpXp = battlePass?.optInt("ProgressionTowardsNextLevel", 0) ?: 0
        val levels = definition?.optJSONObject("content")?.optJSONArray("chapters").objects().flatMap { it.optJSONArray("levels").objects() }
        val rewards = levels.mapIndexed { index, level ->
            val reward = level.optJSONObject("reward")
            val item = reward?.let { catalog.item(it.optString("uuid"), it.optString("type"), -1) }
            PassReward(index + 1, item?.name ?: "Reward ${index + 1}", item?.imageUrl, index < (bpLevel ?: 0))
        }
        val missionDefinitions = runCatching { catalog.data("missions").objects().associateBy { it.optString("uuid") } }.getOrDefault(emptyMap())
        val missions = response.optJSONArray("Missions").objects().mapNotNull { mission ->
            val metadata = missionDefinitions[mission.optString("ID")] ?: return@mapNotNull null
            val objectives = mission.optJSONObject("Objectives")
            MissionProgress(metadata.textOrNull("title") ?: metadata.optString("displayName"),
                objectives?.keys()?.asSequence()?.sumOf { objectives.optInt(it) } ?: 0,
                metadata.optInt("progressToComplete"), mission.optBoolean("Complete"), metadata.optInt("xpGrant"))
        }
        return ProgressSummary(
            accountLevel = account?.optInt("Level", 0) ?: 0,
            accountXp = account?.optInt("XP", 0) ?: 0,
            battlePassLevel = bpLevel,
            battlePassXp = bpXp,
            battlePassXpRequired = levels.getOrNull(bpLevel ?: 0)?.optInt("xp", 0) ?: 0,
            battlePassName = definition?.optString("displayName"), rewards = rewards, missions = missions
        )
    }

    private fun enrichStore(snapshot: StoreSnapshot, store: JSONObject, region: String, puuid: String, headers: Map<String, String>): StoreSnapshot {
        fun cost(value: JSONObject?): Int = StorePricing.cost(value)
        fun item(offer: JSONObject, discounted: Int? = null, currency: String = "VP"): StoreOffer? {
            val reward = offer.optJSONArray("Rewards").optFirst()
                ?: offer.optJSONObject("Reward")
                ?: offer.optJSONObject("Item")
                ?: offer.takeIf { it.has("ItemID") }
                ?: return null
            val original = StorePricing.cost(offer.optJSONObject("Cost"), if(currency == "KC") StorePricing.KC else StorePricing.VP)
            val id = reward.optString("ItemID").ifBlank { reward.optString("ID") }
            val type = reward.optString("ItemTypeID").ifBlank { offer.optString("ItemTypeID") }
            if(id.isBlank()) return null
            return catalog.item(id, type, discounted ?: original, currency).copy(originalPrice = if(discounted != null && discounted < original) original else null)
        }
        val bundleMetadata = runCatching { catalog.data("bundles").objects().associateBy { it.optString("uuid") } }.getOrDefault(emptyMap())
        val featured = store.optJSONObject("FeaturedBundle")
        val rawBundles = (featured?.optJSONArray("Bundles").objects() + listOfNotNull(featured?.optJSONObject("Bundle"))).distinctBy { it.optString("ID") }
        val bundles = rawBundles.map { raw ->
            val metadataId = raw.optString("DataAssetID").ifBlank { raw.optString("ID") }
            val metadata = bundleMetadata[metadataId] ?: catalog.bundle(metadataId)
            val offerItems = raw.optJSONArray("ItemOffers").objects().mapNotNull { row ->
                val offer = row.optJSONObject("Offer") ?: row
                item(offer, cost(row.optJSONObject("DiscountedCost")).takeIf { it >= 0 })
            }
            val rawItems = raw.optJSONArray("Items").objects().mapNotNull { row ->
                val nested = row.optJSONObject("Item") ?: row
                item(nested, row.optInt("DiscountedPrice", -1).takeIf { it >= 0 })
            }
            val items = (offerItems + rawItems).distinctBy { it.id }.map { it.copy(priceSource = "bundle") }
            val image = metadata?.textOrNull("displayIcon") ?: metadata?.textOrNull("displayIcon2") ?: metadata?.textOrNull("verticalPromoImage")
            StoreBundle(raw.optString("ID"), metadata?.textOrNull("displayName") ?: "Bundle", image,
                cost(raw.optJSONObject("TotalDiscountedCost")).takeIf { it >= 0 }, Instant.now().epochSecond + raw.optLong("DurationRemainingInSeconds", featured?.optLong("BundleRemainingDurationInSeconds") ?: 0), items)
        }
        val night = store.optJSONObject("BonusStore")?.optJSONArray("BonusStoreOffers").objects().mapNotNull { row ->
            item(row.optJSONObject("Offer") ?: row, cost(row.optJSONObject("DiscountCosts")).takeIf { it >= 0 })
        }
        val accessories = store.optJSONObject("AccessoryStore")?.optJSONArray("AccessoryStoreOffers").objects().mapNotNull { row ->
            val offer = row.optJSONObject("Offer") ?: row
            item(offer, currency = "KC")
        }.distinctBy { it.id }
        val wallet = runCatching { requestJson("https://pd.$region.a.pvp.net/store/v1/wallet/$puuid", "GET", headers).optJSONObject("Balances") }.getOrNull()
        val currencies = mapOf("85ad13f7-3d1b-5128-9eb2-7cd8ee0b5741" to "VP", "e59aa87c-4cbf-517a-5983-6e81511be9b7" to "RP", "85ca954a-41f2-ce94-9b45-8ca3dd39a00d" to "KC")
        val balances = currencies.mapNotNull { (id, name) -> if(wallet?.has(id) == true) name to wallet.optInt(id) else null }.toMap()
        val observedAt = Instant.now().epochSecond
        val nightExpires = store.optJSONObject("BonusStore")?.optLong("BonusStoreRemainingDurationInSeconds")?.takeIf { it > 0 }?.plus(observedAt)
        val accessoryExpires = store.optJSONObject("AccessoryStore")?.optLong("AccessoryStoreRemainingDurationInSeconds")?.takeIf { it > 0 }?.plus(observedAt)
        return snapshot.copy(bundles = bundles, nightOffers = night, accessories = accessories, balances = balances,
            nightMarketExpiresAt = nightExpires, accessoryExpiresAt = accessoryExpires)
    }

    private fun JSONArray?.optFirst(): JSONObject? = this?.optJSONObject(0)

    private fun rankName(tier: Int): String {
        val names = listOf("Unrated", "Unrated", "Unrated", "Iron 1", "Iron 2", "Iron 3", "Bronze 1", "Bronze 2", "Bronze 3", "Silver 1", "Silver 2", "Silver 3", "Gold 1", "Gold 2", "Gold 3", "Platinum 1", "Platinum 2", "Platinum 3", "Diamond 1", "Diamond 2", "Diamond 3", "Ascendant 1", "Ascendant 2", "Ascendant 3", "Immortal 1", "Immortal 2", "Immortal 3", "Radiant")
        return names.getOrElse(tier) { "Unrated" }
    }

    private fun friendlyMap(path: String): String = when (path.substringAfterLast('/').lowercase()) {
        "bonsai" -> "Split"; "duality" -> "Bind"; "triad" -> "Haven"; "port" -> "Icebox"
        "foxtrot" -> "Breeze"; "canyon" -> "Fracture"; "pitt" -> "Pearl"; "jam" -> "Lotus"
        "juliett" -> "Sunset"; "infinity" -> "Abyss"; "rook" -> "Corrode"
        else -> path.substringAfterLast('/').ifBlank { "Unknown map" }
    }

    private fun friendlyMode(queue: String): String = when (queue.lowercase()) {
        "competitive" -> "Competitive"; "unrated" -> "Unrated"; "spikerush" -> "Spike Rush"
        "deathmatch" -> "Deathmatch"; "swiftplay" -> "Swiftplay"; "ggteam" -> "Escalation"
        "hurm" -> "Team Deathmatch"; "onefa" -> "Replication"; "snowball" -> "Snowball Fight"
        "premier" -> "Premier"; "newmap" -> "New Map"; "custom" -> "Custom"
        else -> queue.replaceFirstChar { it.uppercase() }.ifBlank { "Custom" }
    }

    private fun parseOffer(value: Any): StoreOffer? {
        val objectValue = value as? JSONObject
        val id = StorePricing.skinId(value)
        if (id.isBlank()) return null
        val cost = StorePricing.cost(objectValue?.optJSONObject("Cost"))
        runCatching { catalog.skins().find { it.id == id } }.getOrNull()?.let { return it.copy(priceVp = cost) }
        val metadata = runCatching {
            requestJson("https://valorant-api.com/v1/weapons/skinlevels/$id", "GET").getJSONObject("data")
        }.getOrNull()
        val displayName = metadata?.optString("displayName")?.takeIf { it.isNotBlank() } ?: "Store offer"
        val weapon = weaponTypeFromDisplayName(displayName)
        val collection = displayName.substringBeforeLast(' ', displayName).uppercase()
        return StoreOffer(
            id = id,
            name = displayName,
            collection = collection,
            weapon = weapon,
            tier = "Live offer",
            priceVp = cost,
            palette = paletteFor(id),
            imageUrl = metadata?.optString("displayIcon")?.takeIf { it.isNotBlank() }
        )
    }

    private fun fallbackRegion(country: String): String = when (country.lowercase()) {
        "us", "ca" -> "na"
        "br" -> "br"
        "mx", "ar", "cl", "co", "pe" -> "latam"
        "kr" -> "kr"
        "jp", "au", "nz", "sg", "ph", "th", "vn", "id", "my", "hk", "tw" -> "ap"
        else -> "eu"
    }

    private fun paletteFor(id: String): List<Long> {
        val palettes = listOf(
            listOf(0xFF0B1724, 0xFF2C8EBB, 0xFFA5E8FF),
            listOf(0xFF170C22, 0xFF7042BB, 0xFFE0B9FF),
            listOf(0xFF21100C, 0xFFC1442E, 0xFFFFB55E),
            listOf(0xFF17140C, 0xFFA17B38, 0xFFFFD889)
        )
        return palettes[(id.hashCode() and Int.MAX_VALUE) % palettes.size]
    }

    private fun requestJson(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): JSONObject {
        val target = URL(url)
        val connection = target.openConnection() as HttpURLConnection
        try {
            if (connection is HttpsURLConnection && target.host.endsWith(".pvp.net")) {
                connection.sslSocketFactory = riotStoreSslContext.socketFactory
            }
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "RiotClient/Nightwatch (Windows;10;;Professional, x64)")
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) throw LoginRequired()
            if (code !in 200..299) error("Riot servisi HTTP $code")
            val payload = text.trim()
            return if (payload.startsWith("[")) {
                JSONObject().put("_array", JSONArray(payload))
            } else {
                JSONObject(payload)
            }
        } finally {
            connection.disconnect()
        }
    }

    private inline fun <T> stage(name: String, block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        Log.e("NightwatchNetwork", name, error)
        throw IllegalStateException("$name: ${error.message ?: error.javaClass.simpleName}", error)
    }

    /**
     * Riot's PVP edge can staple an expired OCSP response even while its certificate chain is
     * valid. Android rejects that response before the request is sent. This context remains pinned
     * to Riot's published HydrantID chain and leaves normal hostname verification enabled; the
     * wrapper deliberately validates the supplied chain without consuming the broken OCSP staple.
     */
    private val riotStoreSslContext: SSLContext by lazy {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val certificates = listOf(
            R.raw.identrust_commercial_root_ca_1,
            R.raw.hydrantid_server_ca_o1
        )
        certificates.forEachIndexed { index, resource ->
            context.resources.openRawResource(resource).use { input ->
                keyStore.setCertificateEntry("riot-$index", certificateFactory.generateCertificate(input))
            }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val chainOnlyTrustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                delegate.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
                delegate.checkServerTrusted(chain, authType)
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(chainOnlyTrustManager), SecureRandom())
        }
    }

    companion object {
        private const val CLIENT_PLATFORM = "ew0KCSJwbGF0Zm9ybVR5cGUiOiAiUEMiLA0KCSJwbGF0Zm9ybU9TIjogIldpbmRvd3MiLA0KCSJwbGF0Zm9ybU9TVmVyc2lvbiI6ICIxMC4wLjE5MDQyLjEuMjU2LjY0Yml0IiwNCgkicGxhdGZvcm1DaGlwc2V0IjogIlVua25vd24iDQp9"
    }
}
