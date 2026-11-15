package app.nightwatch.store

import android.content.Context
import android.app.NotificationManager
import java.security.MessageDigest
import java.time.ZonedDateTime

/** All alert producers share the same durable, synchronous delivery gate. */
internal object NotificationDelivery {
    @Synchronized
    fun once(context: Context, key: String, preferencesName: String = "nightwatch_delivery", deliver: () -> Unit): Boolean {
        val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        if(prefs.contains(key)) { DeliveryJournal.record(context, key, "duplicate"); return false }
        if(!prefs.edit().putLong(key, System.currentTimeMillis()).commit()) { DeliveryJournal.record(context, key, "failed"); return false }
        try { deliver() }
        catch(error: Exception) { prefs.edit().remove(key).commit(); DeliveryJournal.record(context, key, "failed"); throw error }
        DeliveryJournal.record(context, key, "sent")
        // Retain recent deliveries across service restarts and application updates.
        val old = prefs.all.entries.sortedByDescending { (it.value as? Long) ?: 0L }.drop(1000)
        if(old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it.key) } }.commit()
        return true
    }

    fun result(context: Context, match: EsportsMatch): Boolean {
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        if(match.status != "result" || match.score1 == null || match.score2 == null) return false
        if(!prefs.getBoolean("match_alerts", true) || !prefs.getBoolean("notify_esports_results", true)) {
            DeliveryJournal.record(context, "result:${match.id}", "settings"); return false
        }
        if(!ScoreNotifications.permitted(context)) { DeliveryJournal.record(context, "result:${match.id}", "permission"); return false }
        if(match.id in prefs.getStringSet("esports_result_notified", emptySet()).orEmpty()) {
            DeliveryJournal.record(context, "result:${match.id}", "duplicate"); return false
        }
        return once(context, "result:${match.id}") {
            context.getSystemService(NotificationManager::class.java)
                .notify(ScoreNotifications.resultId(match), ScoreNotifications.result(context, match))
            prefs.edit().putStringSet("esports_result_notified", prefs.getStringSet("esports_result_notified", emptySet()).orEmpty() + match.id).commit()
        }
    }

    fun mapResult(context: Context, completion: EsportsMapCompletion): Boolean {
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        val match = completion.match
        if(!prefs.getBoolean("match_alerts", true) || !prefs.getBoolean("notify_esports_maps", true)) {
            DeliveryJournal.record(context, "map:${match.id}:${completion.mapNumber}", "settings"); return false
        }
        if(!ScoreNotifications.permitted(context)) {
            DeliveryJournal.record(context, "map:${match.id}:${completion.mapNumber}", "permission"); return false
        }
        return once(context, "map:${match.id}:${completion.mapNumber}") {
            context.getSystemService(NotificationManager::class.java).notify(
                ScoreNotifications.mapResultId(match, completion.mapNumber),
                ScoreNotifications.mapResult(context, match, completion.map, completion.mapNumber)
            )
        }
    }
}

internal data class EsportsMapCompletion(val match: EsportsMatch, val map: EsportsMapResult, val mapNumber: Int)

internal object EsportsMapAlertPolicy {
    /** Emits only the newest live map transition; a final result notification replaces the final-map alert. */
    fun transition(previous: EsportsMatch, detail: EsportsDetail): EsportsMapCompletion? {
        val fresh = detail.match
        if(previous.id != fresh.id || fresh.status != "live") return null
        val before = (previous.score1 ?: 0) + (previous.score2 ?: 0)
        val after = (fresh.score1 ?: 0) + (fresh.score2 ?: 0)
        if(after <= before) return null
        val completed = detail.mapResults.orEmpty().filter {
            it.score1 != null && it.score2 != null && it.score1 != it.score2
        }
        val map = completed.getOrNull(after - 1) ?: completed.lastOrNull() ?: return null
        return EsportsMapCompletion(fresh, map, after)
    }
}

internal object StoreAlertPolicy {
    // A delayed 03:00 worker must not send a second morning alert alongside the 09:00 worker.
    fun eligibleSlot(requested: Int, now: ZonedDateTime): String? {
        val current = when { now.hour >= 9 -> 9; now.hour >= 3 -> 3; else -> return null }
        return if(requested == current) "${now.toLocalDate()}-$current" else null
    }

    /** 03:00, 09:00 and app restarts share one key while the actual offers stay unchanged. */
    fun deliveryKey(found: List<StoreOffer>, active: StoreSnapshot): String? {
        val source = when {
            found.isNotEmpty() -> "favorites:${active.refreshEpochSeconds}:${found.map { it.id }.sorted().joinToString(",")}" 
            active.nightMarketAvailable && active.nightOffers.isNotEmpty() ->
                "night:${active.nightMarketExpiresAt ?: 0}:${active.nightOffers.map { it.id }.sorted().joinToString(",")}" 
            else -> return null
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "store-content:$digest"
    }
}
