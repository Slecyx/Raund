package app.nightwatch.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.Gson

/** A strict allow-list: session tokens, cookies, account data and delivery dedup keys are never exported. */
internal object PreferenceBackup {
    private val booleans = setOf("esports_spoiler_mode", "interaction_sounds", "now_bar", "notifications", "match_alerts",
        "notify_store", "notify_esports_reminders", "notify_esports_results", "notify_esports_maps", "notify_esports_live", "auto_store_refresh", "battery_saver")
    private val choices = mapOf("theme" to setOf("system", "light", "dark"), "language" to setOf("TR", "EN"),
        "esports_mode" to setOf("off", "favorites", "all"), "esports_last_section" to setOf("matches", "events", "news", "teams"),
        "esports_last_filter" to setOf("live", "upcoming", "results", "followed", "saved", "watch_later"),
        "esports_notification_profile" to setOf("important", "detailed", "silent", "custom"))
    private val sets = setOf("wishlist", "esports_teams", "esports_players")
    private val lists = setOf("esports_matches", "esports_events", "widget_presets", WatchLater.KEY)
    private val uuid = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$")
    fun encode(values: Map<String, *>): String {
        val safe = JSONObject()
        values.forEach { (key, value) -> when {
            key in booleans && value is Boolean -> safe.put(key, value)
            key in choices && value in choices.getValue(key) -> safe.put(key, value)
            key in sets && value is Set<*> -> safe.put(key, JSONArray(value.filterIsInstance<String>().take(1000)))
            key == "career_rr_goal" && value is Int -> safe.put(key, value.coerceIn(5, 100))
            key in lists && value is String -> safe.put(key, sanitizedList(key, value))
        } }
        return JSONObject().put("app", "Raund").put("version", 1).put("preferences", safe).toString(2)
    }
    fun decode(raw: String): Map<String, Any> {
        require(raw.length <= 250_000) { "Backup too large" }
        val root = JSONObject(raw)
        require(root.optString("app") == "Raund" && root.optInt("version") == 1) { "Unsupported backup" }
        val source = root.getJSONObject("preferences")
        val values = mutableMapOf<String, Any>()
        source.keys().forEach { key ->
            val value = source.get(key)
            when {
                key in booleans -> { require(value is Boolean); values[key] = value }
                key in choices -> { require(value is String && value in choices.getValue(key)); values[key] = value }
                key in sets -> {
                    require(value is JSONArray && value.length() <= 1000)
                    val items = (0 until value.length()).map { value.getString(it) }
                    require(items.all { it.isNotBlank() && it.length <= 160 && (key != "wishlist" || uuid.matches(it)) })
                    values[key] = items.toSet()
                }
                key == "career_rr_goal" -> { require(value is Int && value in 5..100); values[key] = value }
                key in lists -> { require(value is String); values[key] = sanitizedList(key, value) }
                // Unknown fields are ignored rather than applied to preferences.
            }
        }
        return values
    }
    private fun sanitizedList(key: String, raw: String): String {
        require(raw.length <= 200_000)
        if(key == "widget_presets") return Gson().toJson(WidgetPresets.decode(raw))
        if(key == WatchLater.KEY) return Gson().toJson(WatchLater.decode(raw))
        fun image(url: String?) = url?.takeIf { it.startsWith("https://owcdn.net/") || it.startsWith("https://www.vlr.gg/img/") }
        if(key == "esports_matches") {
            val matches = Gson().fromJson(raw, Array<EsportsMatch?>::class.java)?.toList()
                ?: throw IllegalArgumentException("Invalid matches")
            require(matches.size <= 200 && matches.all(MatchNavigation::valid))
            return Gson().toJson(matches.filterNotNull().distinctBy { it.id }.map {
                EsportsMatch(it.id, it.team1, it.team2, it.score1, it.score2, it.event.take(200), it.series.take(200), it.status,
                    it.whenText.take(120), "https://www.vlr.gg/${it.id}", it.startAtMillis, image(it.team1Logo), image(it.team2Logo), image(it.eventIcon))
            })
        }
        val events = Gson().fromJson(raw, Array<EsportsEvent?>::class.java)?.toList()
            ?: throw IllegalArgumentException("Invalid events")
        require(events.size <= 100 && events.all { event ->
            event != null &&
                (event.id as String?)?.let { Regex("^[0-9]{1,12}$").matches(it) } == true &&
                !(event.title as String?).isNullOrBlank() && event.title.length <= 200 &&
                (event.status as String?) != null && (event.dates as String?) != null
        })
        return Gson().toJson(events.filterNotNull().distinctBy { it.id }.map {
            EsportsEvent(it.id, it.title, "https://www.vlr.gg/event/${it.id}", image(it.image), it.status.take(80), it.dates.take(120))
        })
    }
    fun restore(context: Context, values: Map<String, Any>) {
        // Revalidate even when this entry point is used outside the import screen.
        val safe = decode(encode(values))
        val edit = context.getSharedPreferences("nightwatch", 0).edit()
        safe.forEach { (key, value) -> when(value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit())
        if(safe.containsKey("esports_spoiler_mode")) SpoilerPolicy.change(context, safe["esports_spoiler_mode"] == true)
        RaundWidgetProvider.updateAll(context)
        WidgetRefreshScheduler.schedule(context)
        val prefs = context.getSharedPreferences("nightwatch", 0)
        FavoriteAlerts.schedule(context, prefs.getBoolean("notifications", false) && prefs.getBoolean("notify_store", true))
        EsportsScoreScheduler.schedule(context, context.getSharedPreferences("nightwatch", 0).getString("esports_mode", "off") ?: "off")
        EsportsReminders.refreshSoon(context)
    }
}

/** A portable, non-secret archive. Riot tokens/cookies remain in Android Keystore and are never exported. */
internal data class RaundArchivePayload(val preferences: Map<String, Any>, val account: LiveStoreAccount?)
internal object RaundArchive {
    const val MAX_SIZE = 25_000_000

    fun encode(context: Context): String {
        val values = context.getSharedPreferences("nightwatch", 0).all.toMutableMap()
        values["widget_presets"] = WidgetPresets.capture(context)
        val root = JSONObject(PreferenceBackup.encode(values)).put("version", 2)
        // Account data contains store/career/progression/inventory snapshots, not credentials.
        SessionStore(context).cached()?.let { account -> root.put("account", JSONObject(Gson().toJson(account))) }
        return root.toString(2).also { require(it.length <= MAX_SIZE) { "Archive too large" } }
    }

    fun decode(raw: String): RaundArchivePayload {
        require(raw.length <= MAX_SIZE) { "Archive too large" }
        val root = JSONObject(raw)
        require(root.optString("app") == "Raund") { "Unsupported backup" }
        val version = root.optInt("version")
        // Validate preferences in isolation so a large v2 account snapshot does not trip the
        // 250 KB preference-only size limit.
        val preferenceRoot = JSONObject()
            .put("app", "Raund")
            .put("version", 1)
            .put("preferences", root.getJSONObject("preferences"))
        val preferences = PreferenceBackup.decode(preferenceRoot.toString())
        if(version == 1) return RaundArchivePayload(preferences, null)
        require(version == 2) { "Unsupported backup" }
        val account = root.optJSONObject("account")?.let { value ->
            val candidate = Gson().fromJson(value.toString(), LiveStoreAccount::class.java)
                ?: throw IllegalArgumentException("Invalid account")
            sanitizeAccount(candidate)
        }
        return RaundArchivePayload(preferences, account)
    }

    private fun httpsOnly(url: String?): String? = url?.takeIf { it.startsWith("https://") && it.length <= 512 }

    private fun sanitizeOffer(offer: StoreOffer): StoreOffer {
        val id: String? = offer.id
        val name: String? = offer.name
        val collection: String? = offer.collection
        val weapon: WeaponType? = offer.weapon
        val tier: String? = offer.tier
        val currency: String? = offer.currency
        require(!id.isNullOrBlank() && id.length <= 128 && !name.isNullOrBlank() && name.length <= 200)
        val safeVariants = (offer.variants as List<SkinVariant?>?).orEmpty().filterNotNull().take(20).mapNotNull { variant ->
            val variantName: String? = variant.name
            if(variantName.isNullOrBlank()) null
            else SkinVariant(variantName.take(160), httpsOnly(variant.image), httpsOnly(variant.video))
        }
        return offer.copy(
            id = id,
            name = name,
            collection = collection.orEmpty().take(160),
            weapon = weapon ?: WeaponType.OTHER,
            tier = tier.orEmpty().take(80),
            palette = (offer.palette as List<Long>?).orEmpty().take(8),
            imageUrl = httpsOnly(offer.imageUrl),
            variants = safeVariants,
            currency = currency?.takeIf { it in setOf("VP", "KC", "RP") } ?: "VP",
            ownedLevelNames = offer.ownedLevelNames?.filterNotNull()?.take(20)?.map { it.take(160) }
        )
    }

    private fun sanitizeAccount(candidate: LiveStoreAccount): LiveStoreAccount {
        // Gson can inject null into Kotlin non-null properties when restoring untrusted JSON.
        val name: String? = candidate.name
        val region: String? = candidate.region
        val snapshot: StoreSnapshot? = candidate.snapshot
        require(!name.isNullOrBlank() && name.length in 1..100)
        require(!region.isNullOrBlank() && region.length in 1..16)
        require(snapshot != null)
        val rawOffers = (snapshot.offers as List<StoreOffer?>?).orEmpty()
        val rawNight = (snapshot.nightOffers as List<StoreOffer?>?).orEmpty()
        val rawAccessories = (snapshot.accessories as List<StoreOffer?>?).orEmpty()
        val rawBundles = (snapshot.bundles as List<StoreBundle?>?).orEmpty()
        require(rawOffers.size <= 50 && rawNight.size <= 50 && rawAccessories.size <= 100 && rawBundles.size <= 20)
        val safeSnapshot = snapshot.copy(
            offers = rawOffers.filterNotNull().map(::sanitizeOffer),
            nightOffers = rawNight.filterNotNull().map(::sanitizeOffer),
            accessories = rawAccessories.filterNotNull().map(::sanitizeOffer),
            bundles = rawBundles.filterNotNull().mapNotNull { bundle ->
                val bundleId: String? = bundle.id
                val bundleName: String? = bundle.name
                if(bundleId.isNullOrBlank() || bundleName.isNullOrBlank()) null
                else bundle.copy(
                    id = bundleId.take(128),
                    name = bundleName.take(200),
                    image = httpsOnly(bundle.image),
                    items = (bundle.items as List<StoreOffer?>?).orEmpty().filterNotNull().take(40).map(::sanitizeOffer)
                )
            },
            balances = (snapshot.balances as Map<String, Int>?).orEmpty().filterKeys { it in setOf("VP", "RP", "KC") }
        )
        val safeCareer = candidate.career?.let { career ->
            val rawMatches = (career.matches as List<MatchSummary?>?).orEmpty()
            require(rawMatches.size <= 400)
            career.copy(
                rank = (career.rank as String?).orEmpty().take(80).ifBlank { "Unrated" },
                matches = rawMatches.filterNotNull().mapNotNull { match ->
                    val matchId: String? = match.id
                    if(matchId.isNullOrBlank() || matchId.length > 128) null
                    else match.copy(
                        id = matchId,
                        map = (match.map as String?).orEmpty().take(80),
                        mode = (match.mode as String?).orEmpty().take(80),
                        score = (match.score as String?).orEmpty().take(32),
                        playerId = (match.playerId as String?).orEmpty().take(128),
                        agentId = (match.agentId as String?).orEmpty().take(128),
                        mapImage = httpsOnly(match.mapImage)
                    )
                }
            )
        }
        val safeProgress = candidate.progress?.let { progress ->
            progress.copy(
                battlePassName = progress.battlePassName?.take(160),
                rewards = (progress.rewards as List<PassReward?>?).orEmpty().filterNotNull().take(100).map { reward ->
                    reward.copy(name = (reward.name as String?).orEmpty().take(160), image = httpsOnly(reward.image))
                },
                missions = (progress.missions as List<MissionProgress?>?).orEmpty().filterNotNull().take(50).map { mission ->
                    mission.copy(name = (mission.name as String?).orEmpty().take(200))
                }
            )
        }
        val safeInventory = candidate.inventory?.let { inventory ->
            val ids = (inventory.skinLevelIds as List<String?>?).orEmpty().filterNotNull()
            require(ids.size <= 10_000)
            inventory.copy(skinLevelIds = ids.filter { it.isNotBlank() && it.length <= 128 })
        }
        val safeCard = candidate.playerCard?.let { card ->
            val cardId: String? = card.id
            if(cardId.isNullOrBlank() || cardId.length > 128) null
            else card.copy(id = cardId, small = httpsOnly(card.small), wide = httpsOnly(card.wide))
        }
        return candidate.copy(
            name = name,
            region = region,
            snapshot = safeSnapshot,
            career = safeCareer,
            progress = safeProgress,
            warnings = (candidate.warnings as List<String?>?).orEmpty().filterNotNull().take(16),
            accountId = candidate.accountId?.take(128),
            inventory = safeInventory,
            playerCard = safeCard
        )
    }

    fun restore(context: Context, payload: RaundArchivePayload) {
        PreferenceBackup.restore(context, payload.preferences)
        payload.account?.let { SessionStore(context).cache(it) }
        RaundWidgetProvider.updateAll(context)
    }
}

internal data class DeliveryEntry(val key: String, val state: String, val at: Long)
internal object DeliveryJournal {
    @Synchronized fun record(context: Context, key: String, state: String) {
        val prefs = context.getSharedPreferences("notification_audit", 0)
        val id = "$state:$key"
        // A retry loop should not fill the history with identical reasons.
        if(System.currentTimeMillis() - prefs.getLong(id, 0) < 3_600_000L) return
        val editor = prefs.edit().putLong(id, System.currentTimeMillis())
        prefs.all.entries.sortedByDescending { (it.value as? Long) ?: 0 }.drop(79).forEach { editor.remove(it.key) }
        editor.apply()
    }
    fun read(context: Context): List<DeliveryEntry> = context.getSharedPreferences("notification_audit", 0).all
        .mapNotNull { (key, value) -> (value as? Long)?.let { DeliveryEntry(key.substringAfter(':'), key.substringBefore(':'), it) } }
        .sortedByDescending { it.at }.take(30)
}
