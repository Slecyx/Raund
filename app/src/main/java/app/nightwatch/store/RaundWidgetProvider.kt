package app.nightwatch.store

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class WidgetRow(val title: String, val detail: String, val image: String? = null,
                              val secondaryImage: String? = null, val match: EsportsMatch? = null)
internal data class WidgetContent(val eyebrow: String, val badge: String, val title: String, val subtitle: String, val page: String,
    val rows: List<WidgetRow> = emptyList(), val match: EsportsMatch? = null, val deadline: Long? = null,
    val checkedAt: Long = 0, val nightMarket: Boolean = false)

internal object RaundWidgetData {
    fun select(matches: List<EsportsMatch>, favoriteTeams: Set<String>, storeFavorites: List<StoreOffer>, turkish: Boolean, now: Long,
               career: CareerSummary? = null, savedIds: Set<String> = emptySet()): WidgetContent {
        val relevant = matches.filter { match -> match.id in savedIds || favoriteTeams.any { it.equals(match.team1, true) || it.equals(match.team2, true) } }
        val live = relevant.firstOrNull { it.status == "live" }
        if(live != null) return WidgetContent(if(turkish) "CANLI MAÇ" else "LIVE MATCH", live.score,
            "${live.team1}  ${live.score}  ${live.team2}", live.event, "esports")
        val next = relevant.filter { it.status == "upcoming" && (it.startAtMillis ?: Long.MAX_VALUE) >= now }
            .minByOrNull { it.startAtMillis ?: Long.MAX_VALUE }
        if(next != null) {
            val whenText = next.startAtMillis?.let { DateTimeFormatter.ofPattern("d MMM · HH:mm", if(turkish) Locale.forLanguageTag("tr") else Locale.ENGLISH)
                .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: next.whenText
            return WidgetContent(if(turkish) "SIRADAKİ MAÇ" else "NEXT MATCH", whenText,
                "${next.team1}  ×  ${next.team2}", next.event, "esports")
        }
        val skin = storeFavorites.firstOrNull()
        if(skin != null) return WidgetContent(if(turkish) "FAVORİN MAĞAZADA" else "FAVORITE IN STORE", "${skin.priceVp} ${skin.currency}",
            skin.name, if(turkish) "Teklif yenilenmeden mağazada gör" else "Open before the store refreshes", "favorites")
        if(career != null && career.rankTier >= 3) return WidgetContent(if(turkish) "REKABETÇİ" else "COMPETITIVE", if(career.rankedRating >= 0) "${career.rankedRating} RR" else "— RR",
            career.rank, if(turkish) "Kariyer ve performans merkezini aç" else "Open career and performance center", "career")
        return WidgetContent("RAUND", "", if(turkish) "Takip ettiklerin burada" else "Your follows at a glance",
            if(turkish) "Takım veya skin favorile; kart otomatik güncellensin." else "Favorite a team or skin and this card updates automatically.", "store")
    }

    fun read(context: Context): WidgetContent {
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        val tr = prefs.getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val teams = prefs.getStringSet("esports_teams", emptySet()).orEmpty()
        val feed = EsportsRepository(context).cached()
        val matches = (SavedEsportsMatches.get(context) + feed?.live.orEmpty() + feed?.upcoming.orEmpty()).distinctBy { it.id }
        val wished = prefs.getStringSet("wishlist", emptySet()).orEmpty()
        val account = SessionStore(context).cached()
        val available = account?.let { FavoriteAlerts.matches(it.snapshot, wished) }.orEmpty()
        return select(matches, teams, available, tr, System.currentTimeMillis(), account?.career)
    }
}

open class RaundWidgetProvider : AppWidgetProvider() {
    protected open val mode = "auto"
    override fun onEnabled(context: Context) { WidgetRefreshScheduler.schedule(context); WidgetRefreshScheduler.refresh(context) }
    override fun onDisabled(context: Context) { WidgetRefreshScheduler.schedule(context) }
    override fun onDeleted(context: Context, ids: IntArray) {
        context.getSharedPreferences("raund_widgets", 0).edit().apply {
            ids.forEach { remove("mode_$it"); remove("all_$it"); remove("match_$it"); remove("match_data_$it"); remove("match_scope_$it"); remove("team_$it") }
        }.apply()
        WidgetMatchFollows.sync(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if(intent.action == REFRESH) WidgetRefreshScheduler.refresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        WidgetRenderer.render(context, manager, id, mode)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            WidgetRenderer.render(context, manager, id, mode)
        }
        WidgetRefreshScheduler.schedule(context)
    }

    companion object {
        const val REFRESH = "app.nightwatch.store.WIDGET_REFRESH"
        val providers = listOf(RaundWidgetProvider::class.java, StoreWidgetProvider::class.java, MatchWidgetProvider::class.java, CareerWidgetProvider::class.java)
        fun defaultMode(name: String?) = when(name) {
            StoreWidgetProvider::class.java.name -> "store"; MatchWidgetProvider::class.java.name -> "matches"
            CareerWidgetProvider::class.java.name -> "career"; else -> "auto"
        }
        fun ids(context: Context): List<Int> = providers.flatMap {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, it)).toList()
        }
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            ids(context).forEach { WidgetRenderer.render(context, manager, it, defaultMode(manager.getAppWidgetInfo(it)?.provider?.className)) }
        }
    }
}

class StoreWidgetProvider : RaundWidgetProvider() { override val mode = "store" }
class MatchWidgetProvider : RaundWidgetProvider() { override val mode = "matches" }
class CareerWidgetProvider : RaundWidgetProvider() { override val mode = "career" }
