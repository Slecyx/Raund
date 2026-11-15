package app.nightwatch.store

import android.content.Context
import com.google.gson.Gson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object WidgetModels {
    fun matchContent(matches: List<EsportsMatch>, tr: Boolean, now: Long, hideScores: Boolean = false): WidgetContent {
        val eligible = matches.filter { it.status == "live" || it.status == "upcoming" && (it.startAtMillis ?: Long.MAX_VALUE) >= now }
            .distinctBy { it.id }.sortedWith(compareBy<EsportsMatch> { if(it.status == "live") 0 else 1 }.thenBy { it.startAtMillis ?: Long.MAX_VALUE })
        val first = eligible.firstOrNull() ?: return WidgetContent(if(tr) "MAÇLAR" else "MATCHES", "",
            if(tr) "Sıradaki maç bekleniyor" else "Waiting for the next match",
            if(tr) "Espor'dan maç, takım veya turnuva takip et." else "Follow a match, team or tournament in Esports.", "esports")
        val live = first.status == "live"
        fun time(match: EsportsMatch) = match.startAtMillis?.let { DateTimeFormatter.ofPattern("d MMM · HH:mm", if(tr) Locale.forLanguageTag("tr") else Locale.ENGLISH)
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: match.whenText
        return WidgetContent(if(live) if(tr) "CANLI MAÇ" else "LIVE MATCH" else if(tr) "SIRADAKİ MAÇ" else "NEXT MATCH",
            if(live) SpoilerPolicy.score(first, hideScores) else first.startAtMillis?.let { esportsCountdown(it, now, tr) } ?: time(first),
            if(live) SpoilerPolicy.title(first, hideScores) else "${first.team1}  ×  ${first.team2}",
            listOf(first.event, if(live) "" else time(first)).filter(String::isNotBlank).joinToString(" · "), "esports",
            eligible.take(4).map { WidgetRow("${it.team1} × ${it.team2}", if(it.status == "live") SpoilerPolicy.score(it, hideScores) else time(it), it.team1Logo, it.team2Logo, it) },
            match = first, deadline = if(live) null else first.startAtMillis)
    }

    fun storeContent(snapshot: StoreSnapshot?, tr: Boolean, now: Long, night: Boolean = false): WidgetContent {
        val active = snapshot?.let { StoreFreshness.active(it, now / 1000) }
        val offers = if(night) active?.nightOffers.orEmpty() else active?.offers.orEmpty()
        val name = if(night) if(tr) "GECE PAZARI" else "NIGHT MARKET" else if(tr) "MAĞAZAM" else "MY STORE"
        if(offers.isEmpty()) return WidgetContent(name, "", if(night && snapshot != null)
            if(tr) "Şu anda Gece Pazarı yok" else "No Night Market right now"
            else if(tr) "Mağazanı yenile" else "Refresh your store",
            if(snapshot == null) if(tr) "Riot hesabını uygulamada bağla." else "Connect your Riot account in the app."
            else if(tr) "Güncel teklifler için yenileye dokun." else "Tap refresh for current offers.", "store")
        val end = if(night) active?.nightMarketExpiresAt else active?.refreshEpochSeconds
        val remaining = end?.let { ((it - now / 1000).coerceAtLeast(0) / 60) }
        val timer = remaining?.let { "%02d:%02d".format(Locale.ROOT, it / 60, it % 60) }.orEmpty()
        return WidgetContent(name, if(tr) "$timer kaldı" else "$timer left", offers.first().name,
            if(night) if(tr) "${offers.size} teklif · Hepsi için widget'ı büyüt" else "${offers.size} offers · Resize to see all"
                else offers.take(4).joinToString(" · ") { it.name }, "store",
            offers.take(if(night) 6 else 4).map { WidgetRow(it.name,
                if(it.priceVp >= 0) "${it.priceVp} ${it.currency}" else if(tr) "Fiyat bekleniyor" else "Price pending", it.imageUrl) },
            deadline = end?.times(1000), nightMarket = night)
    }

    fun careerContent(career: CareerSummary?, tr: Boolean): WidgetContent {
        if(career == null || career.rankTier < 3) return WidgetContent(if(tr) "KARİYER" else "CAREER", "",
            if(tr) "Kariyerini aç" else "Open your career", if(tr) "Dereceli maçlarını uygulamada güncelle." else "Update ranked matches in the app.", "career")
        val ranked = career.matches.filter { it.mode.equals("competitive", true) }
        val wins = ranked.count { it.won == true }
        val played = ranked.count { it.won != null }
        return WidgetContent(if(tr) "REKABETÇİ" else "COMPETITIVE", if(career.rankedRating >= 0) "${career.rankedRating} RR" else "— RR", career.rank,
            if(tr) "$wins galibiyet / $played maç" else "$wins wins / $played matches", "career",
            ranked.take(3).map { WidgetRow(it.map, "${it.score} · ${it.kills}/${it.deaths}/${it.assists}", it.mapImage) })
    }

    fun matches(context: Context): List<EsportsMatch> {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val feed = EsportsRepository(context).cached()
        val realtime = runCatching { Gson().fromJson(prefs.getString("esports_realtime_matches", "[]"), Array<EsportsMatch>::class.java).toList() }.getOrDefault(emptyList())
        val specific = context.getSharedPreferences("raund_widgets", 0).all.filterKeys { it.startsWith("match_data_") }.values.mapNotNull {
            runCatching { Gson().fromJson(it as? String, EsportsMatch::class.java) }.getOrNull()
        }
        val repo = EsportsRepository(context)
        return (realtime + feed?.live.orEmpty() + feed?.upcoming.orEmpty() + feed?.results.orEmpty() + SavedEsportsMatches.get(context) + specific).distinctBy { it.id }.map { match ->
            val detail = repo.cachedDetail(match.id)?.match
            if(detail != null && (detail.status == "result" || repo.cachedDetailAt(match.id) > (feed?.checkedAt ?: 0))) detail else match
        }
    }

    fun read(context: Context, id: Int, defaultMode: String): WidgetContent {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val settings = context.getSharedPreferences("raund_widgets", 0)
        val tr = prefs.getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val teams = prefs.getStringSet("esports_teams", emptySet()).orEmpty()
        val saved = SavedEsportsMatches.get(context).map { it.id }.toSet()
        val events = SavedEsportsEvents.get(context).map { it.title }
        val matches = matches(context)
        val followed = matches.filter { EsportsReminders.follows(it, saved, teams, events) || it.id == prefs.getString("tracked_match_id", null) }
        val account = SessionStore(context).cached()
        val now = System.currentTimeMillis()
        val hide = SpoilerPolicy.enabled(context)
        val content = when(settings.getString("mode_$id", defaultMode)) {
            "store" -> storeContent(account?.snapshot, tr, now)
            "night" -> storeContent(account?.snapshot, tr, now, night = true)
            "career" -> careerContent(account?.career, tr)
            "matches" -> {
                val specific = settings.getString("match_$id", null)
                val scope = settings.getString("match_scope_$id", null) ?: if(settings.getBoolean("all_$id", false)) "all" else "follows"
                val chosenTeam = settings.getString("team_$id", null)
                val pool = when {
                    specific != null -> matches.filter { it.id == specific }
                    scope == "all" -> matches
                    scope == "teams" -> matches.filter { it.team1 in teams || it.team2 in teams }
                    scope == "team" && !chosenTeam.isNullOrBlank() -> matches.filter { it.team1.equals(chosenTeam, true) || it.team2.equals(chosenTeam, true) }
                    else -> followed
                }
                val result = pool.firstOrNull { it.id == specific && it.status == "result" }
                if(result != null) WidgetContent(if(tr) "MAÇ TAMAMLANDI" else "MATCH FINISHED", SpoilerPolicy.score(result, hide),
                    SpoilerPolicy.title(result, hide), result.event, "esports", match = result) else matchContent(pool, tr, now, hide)
            }
            else -> {
                val selected = RaundWidgetData.select(followed, teams,
                    account?.let { FavoriteAlerts.matches(it.snapshot, prefs.getStringSet("wishlist", emptySet()).orEmpty()) }.orEmpty(),
                    tr, now, account?.career, followed.map { it.id }.toSet())
                if(selected.page == "esports") matchContent(followed, tr, now, hide)
                else if(selected.page == "career") careerContent(account?.career, tr)
                else if(selected.page == "store") storeContent(account?.snapshot, tr, now) else selected
            }
        }
        val checked = when(content.page) { "esports" -> {
            val repo = EsportsRepository(context)
            maxOf(repo.cached()?.checkedAt ?: 0, content.match?.let { repo.cachedDetailAt(it.id) } ?: 0)
        }; else -> account?.updatedAt ?: 0 }
        return content.copy(checkedAt = checked)
    }
}
