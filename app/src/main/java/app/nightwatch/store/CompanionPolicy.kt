package app.nightwatch.store

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson

internal object CatalogFreshness {
    fun fresh(checked: Long, now: Long, ttl: Long) = checked > 0 && now >= checked && now - checked < ttl
}

internal object PendingCatalog {
    fun skin(id: String) = StoreOffer(id, "Item · ${id.take(8)}", "weapons/skinlevels", WeaponType.OTHER,
        "", -1, emptyList(), isWeaponSkin = true, metadataPending = true)
}

internal object SpoilerPolicy {
    fun enabled(context: Context) = context.getSharedPreferences("nightwatch", 0).getBoolean("esports_spoiler_mode", false)
    fun score(match: EsportsMatch, hide: Boolean) = if(hide) "•••" else match.score
    fun title(match: EsportsMatch, hide: Boolean) = "${match.team1}  ${score(match, hide)}  ${match.team2}"
    fun change(context: Context, enabled: Boolean) {
        context.getSharedPreferences("nightwatch", 0).edit().putBoolean("esports_spoiler_mode", enabled).commit()
        // Remove old score-bearing surfaces immediately; active services rebuild with the new policy.
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.activeNotifications.filter { it.notification.channelId == ScoreNotifications.CHANNEL ||
            it.id >= 20_000_000 && it.id < 30_000_000 }.forEach { manager.cancel(it.id) }
        RaundWidgetProvider.updateAll(context)
    }
}

internal object MatchNavigation {
    const val EXTRA = "raund_match"
    fun valid(match: EsportsMatch?): Boolean {
        if(match == null) return false
        // Gson bypasses Kotlin null-safety when deserializing untrusted JSON.
        val id: String? = match.id
        val team1: String? = match.team1
        val team2: String? = match.team2
        val status: String? = match.status
        val event: String? = match.event
        val series: String? = match.series
        val whenText: String? = match.whenText
        return id != null && Regex("^[0-9]{1,12}$").matches(id) &&
            !team1.isNullOrBlank() && team1.length <= 120 &&
            !team2.isNullOrBlank() && team2.length <= 120 &&
            status in setOf("live", "upcoming", "result", "cancelled", "postponed") &&
            event != null && series != null && whenText != null
    }
    fun intent(context: Context, match: EsportsMatch): Intent = Intent(context, MainActivity::class.java)
        .setData(Uri.parse("raund://match/${match.id}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("open_esports", true).putExtra(EXTRA, Gson().toJson(match))
    fun read(intent: Intent): EsportsMatch? = runCatching {
        val raw = intent.getStringExtra(EXTRA)?.takeIf { it.length <= 12_000 } ?: return null
        Gson().fromJson(raw, EsportsMatch::class.java).takeIf(::valid)?.let {
            it.copy(url = "https://www.vlr.gg/${it.id}")
        }
    }.getOrNull()
    fun pending(context: Context, match: EsportsMatch, request: Int) = PendingIntent.getActivity(context, request,
        intent(context, match), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

internal object LiveUpdateCapability {
    fun state(api: Int, preference: Boolean, notifications: Boolean, promotion: Boolean): String = when {
        !preference -> "off"
        !notifications -> "notifications"
        api < 36 -> "standard"
        !promotion -> "permission"
        else -> "eligible"
    }
}

internal object LiveCardPolicy {
    fun allowed(context: Context, match: EsportsMatch) = context.getSharedPreferences("nightwatch", 0).let { prefs ->
        prefs.getBoolean("now_bar", true) && match.id !in prefs.getStringSet("live_card_dismissed", emptySet()).orEmpty()
    }
    fun dismissal(context: Context, matches: List<EsportsMatch>): PendingIntent = PendingIntent.getBroadcast(context,
        matches.joinToString { it.id }.hashCode(), Intent(context, LiveCardDismissReceiver::class.java)
            .setData(Uri.parse("raund://dismiss/${matches.joinToString("-") { it.id }}"))
            .putExtra("ids", matches.map { it.id }.toTypedArray()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun resume(context: Context, id: String) {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        prefs.edit().putStringSet("live_card_dismissed", prefs.getStringSet("live_card_dismissed", emptySet()).orEmpty() - id).commit()
    }
}

class LiveCardDismissReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getStringArrayExtra("ids").orEmpty().filter { Regex("^[0-9]{1,12}$").matches(it) }
        if(ids.isEmpty()) return
        val prefs = context.getSharedPreferences("nightwatch", 0)
        prefs.edit().putStringSet("live_card_dismissed", (prefs.getStringSet("live_card_dismissed", emptySet()).orEmpty() + ids).toList().takeLast(100).toSet()).commit()
    }
}

/** Opens only verified HTTPS web links in a browsable activity without crashing if none exists. */
internal fun openHttpsUrl(context: Context, rawUrl: String) {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return
    if(uri.scheme != "https" || uri.host.isNullOrBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
}
