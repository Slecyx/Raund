package app.nightwatch.store

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object ScoreNotifications {
    const val LIVE_ID = 90
    const val SUMMARY_ID = 91
    const val REALTIME_ID = 92
    const val CHANNEL = "esports_scores"
    const val REMINDER_CHANNEL = "esports_reminders"
    fun favoriteId(id: String): Int = 1000 + (id.hashCode() and 0x00ffffff)
    fun favoriteId(match: EsportsMatch): Int = favoriteId(match.id)
    fun resultId(match: EsportsMatch): Int = 20_000_000 + (match.id.hashCode() and 0x007fffff)
    fun mapResultId(match: EsportsMatch, mapNumber: Int): Int = 40_000_000 + ("${match.id}:$mapNumber".hashCode() and 0x007fffff)
    fun reminderId(match: EsportsMatch): Int = 30_000_000 + (match.id.hashCode() and 0x00ffffff)
    fun permitted(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val tr = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
            .getString("language", Locale.getDefault().language.uppercase()) == "TR"
        manager.createNotificationChannel(NotificationChannel(CHANNEL,if(tr) "Espor skorları" else "Esports scores",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL,
            if(tr) "Espor maç hatırlatıcıları" else "Esports match reminders", NotificationManager.IMPORTANCE_HIGH))
    }
    fun reminder(context: Context, match: EsportsMatch): android.app.Notification {
        channel(context)
        val tr = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
            .getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val title = (if(tr) "15 dakika sonra · " else "In 15 minutes · ") + "${match.team1} × ${match.team2}"
        val open = MatchNavigation.pending(context, match, reminderId(match))
        return NotificationCompat.Builder(context, REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
            .setContentText(match.event).setStyle(NotificationCompat.BigTextStyle().bigText(match.event))
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL).build()
    }
    fun item(context: Context, match: EsportsMatch, promote: Boolean): android.app.Notification {
        channel(context)
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        val tr = prefs.getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val hide = SpoilerPolicy.enabled(context)
        val open = MatchNavigation.pending(context, match, 90)
        val unpin = PendingIntent.getBroadcast(context, 90, Intent(context, UnfollowMatchReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = SpoilerPolicy.title(match, hide)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(match.event.ifBlank { if(tr) "Canlı maç" else "Live match" })
            .setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
            .setSilent(true).setCategory(NotificationCompat.CATEGORY_EVENT)
            .setSubText(if(tr) "Canlı skor" else "Live score")
            .setStyle(NotificationCompat.BigTextStyle().bigText(match.event.ifBlank { if(tr) "Canlı VALORANT maçı" else "Live VALORANT match" }))
            .setRequestPromotedOngoing(promote && LiveCardPolicy.allowed(context, match) && Build.VERSION.SDK_INT >= 36)
            .setDeleteIntent(LiveCardPolicy.dismissal(context, listOf(match)))
            .setShortCriticalText(if(hide) null else match.score.takeIf { it != "—" })
            .addAction(0, if(tr) "Takibi bırak" else "Unpin", unpin).build()
    }
    fun summary(context: Context, matches: List<EsportsMatch>): android.app.Notification {
        channel(context)
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        val tr = prefs.getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val hide = SpoilerPolicy.enabled(context)
        val title = if(matches.size == 1 && matches[0].status != "live") "${matches[0].team1} × ${matches[0].team2}"
            else if(matches.size == 1) SpoilerPolicy.title(matches[0], hide)
            else if(tr) "${matches.size} canlı espor maçı" else "${matches.size} live esports matches"
        val one = matches.singleOrNull()
        val currentMap = one?.let { EsportsRepository(context).cachedDetail(it.id)?.mapResults.orEmpty().lastOrNull { map -> map.score1 != null && map.score2 != null } }
        val lines = if(one != null) listOfNotNull(currentMap?.let { if(hide) it.name else "${it.name}  ${it.score1} – ${it.score2}" }, one.event).joinToString(" · ")
            else matches.take(3).joinToString("\n") { SpoilerPolicy.title(it, hide) }
        val open = if(one != null) MatchNavigation.pending(context, one, 91) else PendingIntent.getActivity(context, 91,
            Intent(context, MainActivity::class.java).putExtra("open_esports",true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nearStart = one?.status == "upcoming" && one.startAtMillis?.let { it > System.currentTimeMillis() && it - System.currentTimeMillis() <= 120_000L } == true
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(lines).setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
            .setSilent(true).setSubText(if(matches.any { it.status == "live" }) if(tr) "Canlı skor" else "Live score"
                else if(tr) "Yaklaşan maç" else "Upcoming match")
            .setCategory(NotificationCompat.CATEGORY_EVENT).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(one != null && (one.status == "live" || nearStart) && LiveCardPolicy.allowed(context, one) && Build.VERSION.SDK_INT >= 36)
            .setDeleteIntent(LiveCardPolicy.dismissal(context, matches))
            .setShortCriticalText(if(hide) null else matches.singleOrNull()?.score?.takeIf { it != "—" })
        if(nearStart) builder.setWhen(one.startAtMillis).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        return builder.build()
    }

    fun favoriteMatch(context: Context, match: EsportsMatch): android.app.Notification {
        channel(context)
        val tr = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
            .getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val hide = SpoilerPolicy.enabled(context)
        val open = MatchNavigation.pending(context, match, favoriteId(match))
        val unpin = PendingIntent.getBroadcast(context, favoriteId(match),
            Intent(context, UnsaveEsportsMatchReceiver::class.java).putExtra("match_id", match.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if(match.status == "live") SpoilerPolicy.title(match, hide)
            else "${match.team1} × ${match.team2}"
        val message = if(match.status == "live") (if(tr) "Canlı · " else "Live · ") + match.event
            else (if(tr) "Maça kalan süre · " else "Match countdown · ") + match.event
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(message).setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOnlyAlertOnce(true)
            .addAction(0, if(tr) "Favoriden çıkar" else "Remove favorite", unpin)
        if(match.status == "live") builder.setOngoing(true)
            .setRequestPromotedOngoing(Build.VERSION.SDK_INT >= 36 && LiveCardPolicy.allowed(context, match))
            .setDeleteIntent(LiveCardPolicy.dismissal(context, listOf(match))).setShortCriticalText(if(hide) null else match.score)
        if(match.status == "upcoming" && match.startAtMillis != null && match.startAtMillis > System.currentTimeMillis())
            builder.setWhen(match.startAtMillis).setUsesChronometer(true).setChronometerCountDown(true)
        else builder.setShowWhen(false)
        return builder.build()
    }

    fun result(context: Context, match: EsportsMatch): android.app.Notification {
        channel(context)
        val tr = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
            .getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val open = MatchNavigation.pending(context, match, resultId(match))
        val title = SpoilerPolicy.title(match, SpoilerPolicy.enabled(context))
        val message = (if(tr) "Maç tamamlandı" else "Match finished") + match.event.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return NotificationCompat.Builder(context, REMINDER_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL).build()
    }

    fun mapResult(context: Context, match: EsportsMatch, map: EsportsMapResult, mapNumber: Int): android.app.Notification {
        channel(context)
        val tr = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
            .getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val hide = SpoilerPolicy.enabled(context)
        val open = MatchNavigation.pending(context, match, mapResultId(match, mapNumber))
        val winner = when {
            (map.score1 ?: 0) > (map.score2 ?: 0) -> match.team1
            (map.score2 ?: 0) > (map.score1 ?: 0) -> match.team2
            else -> null
        }
        val title = if(hide) {
            if(tr) "Harita tamamlandı" else "Map finished"
        } else if(tr) {
            "$winner · ${map.name} haritasını kazandı"
        } else {
            "$winner won ${map.name}"
        }
        val message = if(hide) match.event else if(tr) "Harita ${map.score1}–${map.score2} · Seri ${match.score}"
            else "Map ${map.score1}–${map.score2} · Series ${match.score}"
        return NotificationCompat.Builder(context, REMINDER_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL).build()
    }
}

class UnsaveEsportsMatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("match_id") ?: return
        val before = SavedEsportsMatches.get(context)
        EsportsScoreScheduler.syncSaved(context, before, before.filterNot { it.id == id })
    }
}

internal object SavedEsportsMatches {
    private fun prefs(context: Context) = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
    fun get(context: Context): List<EsportsMatch> = runCatching {
        Gson().fromJson(prefs(context).getString("esports_matches", "[]"), Array<EsportsMatch>::class.java).toList()
    }.getOrDefault(emptyList())
    fun put(context: Context, matches: List<EsportsMatch>) {
        prefs(context).edit().putString("esports_matches", Gson().toJson(matches)).apply()
        EsportsScoreScheduler.schedule(context, prefs(context).getString("esports_mode", "off") ?: "off")
    }
}

internal object SavedEsportsEvents {
    private fun prefs(context: Context) = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
    fun get(context: Context): List<EsportsEvent> = runCatching {
        Gson().fromJson(prefs(context).getString("esports_events", "[]"), Array<EsportsEvent>::class.java).toList()
    }.getOrDefault(emptyList())
    fun put(context: Context, events: List<EsportsEvent>) {
        prefs(context).edit().putString("esports_events", Gson().toJson(events.distinctBy { it.id })).apply()
        EsportsScoreScheduler.schedule(context, prefs(context).getString("esports_mode", "off") ?: "off")
    }
}

internal object EsportsAlertPolicy {
    fun completed(watched: List<EsportsMatch>, previouslyActive: Set<String>, notified: Set<String>): List<EsportsMatch> =
        watched.filter { it.status == "result" && it.score1 != null && it.score2 != null &&
            it.id in previouslyActive && it.id !in notified }.distinctBy { it.id }

    fun relevantLive(mode: String?, feedLive: List<EsportsMatch>, watched: List<EsportsMatch>,
                     favoriteTeams: Set<String>, trackedId: String?): List<EsportsMatch> =
        (feedLive.filter { match ->
            mode == "all" || (mode == "favorites" && favoriteTeams.any {
                it.equals(match.team1, true) || it.equals(match.team2, true)
            })
        } + watched.filter { it.status == "live" })
            .filterNot { it.id == trackedId }.distinctBy { it.id }
}

/**
 * Result alerts are transitions, never a replay of an old saved result.  Earlier versions only
 * kept a set of IDs, so an interrupted worker could retain an "active" ID indefinitely and emit
 * a completion alert days later.  The timestamped format deliberately treats those legacy IDs as
 * already reconciled on first run.
 */
internal object EsportsResultTransitionPolicy {
    private const val separator = "|"
    private const val activeTtlMillis = 18 * 60 * 60_000L

    data class Transition(val completed: List<EsportsMatch>, val nextEntries: Set<String>)

    fun advance(watched: List<EsportsMatch>, storedEntries: Set<String>, notified: Set<String>, now: Long): Transition {
        val active = storedEntries.mapNotNull { entry ->
            val split = entry.lastIndexOf(separator)
            // Plain IDs are legacy entries. Do not convert them into a fresh transition.
            if(split <= 0) null else entry.substring(0, split).takeIf { it.isNotBlank() }?.let { id ->
                entry.substring(split + 1).toLongOrNull()?.let { timestamp -> id to timestamp }
            }
        }.filter { (_, timestamp) -> timestamp in (now - activeTtlMillis)..now }.toMap().toMutableMap()

        val completed = watched.filter { match ->
            match.status == "result" && match.score1 != null && match.score2 != null &&
                match.id in active && match.id !in notified
        }.distinctBy { it.id }
        completed.forEach { active.remove(it.id) }
        watched.filter { it.status != "result" && it.status != "cancelled" }.forEach { match ->
            active[match.id] = now
        }
        return Transition(completed, active.map { (id, timestamp) -> "$id$separator$timestamp" }.toSet())
    }
}

/**
 * Background work is deliberately conservative by default.  Android's system scheduler
 * may batch these jobs further; the foreground score service is reserved for a match that
 * is actually due/live and never runs just to keep an empty lock-screen card fresh.
 */
internal object BatteryPolicy {
    private fun prefs(context: Context) = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
    fun saverEnabled(context: Context): Boolean = prefs(context).getBoolean("battery_saver", true)
    fun widgetRefreshMinutes(context: Context): Long = if(saverEnabled(context)) 120 else 30
    fun scoreRefreshMinutes(context: Context): Long = if(saverEnabled(context)) 60 else 15
    fun livePollMillis(context: Context, explicitMatch: Boolean): Long = when {
        saverEnabled(context) -> 30_000L
        explicitMatch -> 10_000L
        else -> 15_000L
    }
}

class UnfollowMatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE).edit().remove("tracked_match_id").apply()
        context.stopService(Intent(context, LiveMatchService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(ScoreNotifications.LIVE_ID)
    }
}

class LiveMatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var polling: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val matchId = intent?.getStringExtra("match_id") ?: getSharedPreferences("nightwatch", MODE_PRIVATE).getString("tracked_match_id", null)
        val label = intent?.getStringExtra("label").orEmpty()
        if(matchId.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        val placeholder = EsportsMatch(matchId, label.ifBlank { "VALORANT" }, "", null, null, "", "", "live", "", "")
        val notification = ScoreNotifications.item(this, placeholder, true)
        if(Build.VERSION.SDK_INT >= 29) startForeground(ScoreNotifications.LIVE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ScoreNotifications.LIVE_ID, notification)
        getSharedPreferences("nightwatch", MODE_PRIVATE).edit().putString("tracked_match_id", matchId).apply()
        polling?.cancel()
        polling = scope.launch {
            var failures = 0
            var elapsed = 0
            var missingChecks = 0
            while(isActive && elapsed < 20_700_000) {
                var nextDelay = 30_000L
                try {
                    val repository = EsportsRepository(this@LiveMatchService)
                    val match = repository.refreshLive().live.find { it.id == matchId }
                    if(match == null) {
                        val result = repository.load(force = true).results.find { it.id == matchId }
                        if(result != null) {
                            NotificationDelivery.result(this@LiveMatchService, result)
                            break
                        }
                        // VLR removes a match from the live feed shortly before publishing the final result.
                        // Keep checking briefly instead of ending tracking during that gap.
                        missingChecks++
                        if(missingChecks >= 18) break
                        nextDelay = 20_000L
                    } else {
                        missingChecks = 0
                        if(ScoreNotifications.permitted(this@LiveMatchService))
                            getSystemService(NotificationManager::class.java).notify(ScoreNotifications.LIVE_ID, ScoreNotifications.item(this@LiveMatchService, match, true))
                        RaundWidgetProvider.updateAll(this@LiveMatchService)
                    }
                    failures = 0
                } catch (_: Exception) {
                    failures++
                    if(failures >= 3) break
                }
                delay(nextDelay)
                elapsed += nextDelay.toInt()
            }
            getSharedPreferences("nightwatch", MODE_PRIVATE).edit().remove("tracked_match_id").apply()
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() {
        getSharedPreferences("nightwatch", MODE_PRIVATE).edit().remove("tracked_match_id").apply()
        polling?.cancel(); scope.cancel(); super.onDestroy()
    }
}

/** Keeps followed tournament/team matches fresh while they are actually being played. */
class TournamentLiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling: Job? = null
    private val key = "esports_realtime_matches"
    override fun onBind(intent: Intent?) = null

    private fun stored(): List<EsportsMatch> = synchronized(TournamentLiveService::class.java) { runCatching {
        Gson().fromJson(getSharedPreferences("nightwatch", MODE_PRIVATE).getString(key, "[]"), Array<EsportsMatch>::class.java).toList()
    }.getOrDefault(emptyList()) }

    private fun save(matches: List<EsportsMatch>) = synchronized(TournamentLiveService::class.java) {
        getSharedPreferences("nightwatch", MODE_PRIVATE).edit()
            .putString(key, Gson().toJson(matches.distinctBy { it.id })).apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incoming = runCatching { Gson().fromJson(intent?.getStringExtra("match"), EsportsMatch::class.java) }.getOrNull()
        val initial = synchronized(TournamentLiveService::class.java) {
            (stored() + listOfNotNull(incoming)).distinctBy { it.id }.also { save(it) }
        }
        if(initial.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val notification = ScoreNotifications.summary(this, initial)
        synchronized(ScoreNotifications) {
            getSystemService(NotificationManager::class.java).cancel(ScoreNotifications.SUMMARY_ID)
            incoming?.let { getSystemService(NotificationManager::class.java).cancel(ScoreNotifications.reminderId(it)) }
            if(Build.VERSION.SDK_INT >= 29) startForeground(ScoreNotifications.REALTIME_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ScoreNotifications.REALTIME_ID, notification)
        }
        if(polling?.isActive != true) polling = scope.launch { poll() }
        return START_REDELIVER_INTENT
    }

    private suspend fun poll() {
        var elapsed = 0L
        var failures = 0
        while(currentCoroutineContext().isActive && elapsed < 20_700_000L) {
                var nextDelay = BatteryPolicy.livePollMillis(this@TournamentLiveService, explicitMatch = false)
            try {
                val prefs = getSharedPreferences("nightwatch", MODE_PRIVATE)
                val saved = SavedEsportsMatches.get(this@TournamentLiveService).map { it.id }.toSet() + WidgetMatchFollows.ids(this@TournamentLiveService)
                val teams = prefs.getStringSet("esports_teams", emptySet()).orEmpty()
                val events = SavedEsportsEvents.get(this@TournamentLiveService).map { it.title }
                val original = stored()
                val tracked = prefs.getString("tracked_match_id", null)
                val automatic = prefs.getBoolean("match_alerts", true) &&
                    (prefs.getBoolean("notify_esports_results", true) || prefs.getBoolean("notify_esports_maps", true) ||
                        prefs.getBoolean("notify_esports_live", true))
                var monitored = original.filter { it.id == tracked || automatic && EsportsReminders.follows(it, saved, teams, events) }
                    .filter { match ->
                        match.status == "live" || match.startAtMillis?.let { start ->
                            // Do not keep a foreground service alive for a future tournament round.
                            nowInServiceWindow(start)
                        } == true
                    }
                if(monitored.isEmpty()) break

                val repository = EsportsRepository(this@TournamentLiveService)
                val now = System.currentTimeMillis()
                val activeWindow = monitored.filter { match ->
                    match.status == "live" || match.startAtMillis?.let { it <= now + 2 * 60_000L } == true
                }
                val detailed = coroutineScope {
                    activeWindow.map { match -> async { runCatching { repository.details(match, force = true) }.getOrNull() } }
                        .awaitAll().filterNotNull()
                }
                val detailById = detailed.associateBy { it.match.id }
                val freshById = detailById.mapValues { it.value.match }
                val mapCompletions = original.mapNotNull { previous ->
                    detailById[previous.id]?.let { detail -> EsportsMapAlertPolicy.transition(previous, detail) }
                }
                monitored = monitored.map { freshById[it.id] ?: it }
                val cancelled = monitored.filter { it.status == "cancelled" }
                if(cancelled.isNotEmpty()) {
                    cancelled.forEach { match ->
                        DeliveryJournal.record(this@TournamentLiveService, "cancelled:${match.id}", "cancelled")
                        if(match.id == tracked) prefs.edit().remove("tracked_match_id").apply()
                    }
                    monitored = monitored.filterNot { it.status == "cancelled" }
                }
                val completed = monitored.filter { it.status == "result" && it.score1 != null && it.score2 != null }
                if(completed.isNotEmpty()) {
                    completed.forEach { result ->
                        NotificationDelivery.result(this@TournamentLiveService, result)
                        if(result.id == tracked) prefs.edit().remove("tracked_match_id").apply()
                    }
                    monitored = monitored.filterNot { item -> completed.any { it.id == item.id } }
                }
                mapCompletions.forEach { completion ->
                    NotificationDelivery.mapResult(this@TournamentLiveService, completion)
                }
                synchronized(TournamentLiveService::class.java) {
                    // A follow can arrive while the network requests are in flight.
                    monitored = (monitored + stored().filterNot { fresh -> original.any { it.id == fresh.id } }).distinctBy { it.id }
                    save(monitored)
                }
                if(monitored.isEmpty()) break
                val shown = monitored.filter { it.status == "live" }.ifEmpty { monitored.take(3) }
                val presentation = shown.joinToString("|") { "${it.id}:${it.status}:${it.score}:${it.score1}:${it.score2}" }
                if(presentation != lastPresentation) {
                    lastPresentation = presentation
                    RaundWidgetProvider.updateAll(this@TournamentLiveService)
                    if(prefs.getBoolean("notify_esports_live", true) || tracked != null)
                        getSystemService(NotificationManager::class.java)
                            .notify(ScoreNotifications.REALTIME_ID, ScoreNotifications.summary(this@TournamentLiveService, shown))
                }
                failures = 0
                nextDelay = if(activeWindow.isNotEmpty())
                    BatteryPolicy.livePollMillis(this@TournamentLiveService, explicitMatch = tracked != null)
                else 60_000L
            } catch (_: Exception) {
                failures++
                nextDelay = if(failures < 3) BatteryPolicy.livePollMillis(this@TournamentLiveService, explicitMatch = false) else 60_000L
            }
            delay(nextDelay)
            elapsed += nextDelay
        }
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() {
        polling?.cancel(); scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun nowInServiceWindow(start: Long): Boolean {
        val now = System.currentTimeMillis()
        return now >= start - 60_000L && now - start <= 6 * 60 * 60_000L
    }

    private var lastPresentation: String? = null
}

class EsportsScoreWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        val mode = prefs.getString("esports_mode", "off")
        if(!ScoreNotifications.permitted(context)) return Result.success()
        return try {
            val favoriteTeams = prefs.getStringSet("esports_teams", emptySet()).orEmpty()
            val tracked = prefs.getString("tracked_match_id", null)
            val feed = EsportsRepository(context).load(force = true)
            val manager = context.getSystemService(NotificationManager::class.java)
            val latest = (feed.live + feed.upcoming + feed.results).associateBy { it.id }
            val savedMatches = SavedEsportsMatches.get(context)
            // Migration/reconciliation for follows created before timestamped transitions existed.
            // A match whose scheduled start is long past is baseline history, even if an old cache
            // still labels it upcoming.
            val staleSavedIds = savedMatches.filter { match ->
                match.startAtMillis?.let { it < System.currentTimeMillis() - 18 * 60 * 60_000L } == true
            }.map { it.id }.toSet()
            if(staleSavedIds.isNotEmpty()) prefs.edit().putStringSet(
                "esports_result_baselined", prefs.getStringSet("esports_result_baselined", emptySet()).orEmpty() + staleSavedIds
            ).apply()
            val followedUpcoming = feed.upcoming.filter { match ->
                savedMatches.any { it.id == match.id } ||
                    favoriteTeams.any { it.equals(match.team1, true) || it.equals(match.team2, true) }
            }
            val teamMatches = favoriteTeams.flatMap { team ->
                val url = EsportsTeamLinks.get(context, team) ?: return@flatMap emptyList()
                runCatching { EsportsExploreRepository(context).team(url).matches }
                    .getOrDefault(emptyList())
            }
            val eventMatches = SavedEsportsEvents.get(context).flatMap { event ->
                runCatching { EsportsExploreRepository(context).tournament(event, force = true).matches }.getOrDefault(emptyList())
            }
            val widgetMatches = WidgetModels.matches(context).filter { it.id in WidgetMatchFollows.ids(context) }
            val watched = (savedMatches + teamMatches + eventMatches + followedUpcoming + widgetMatches)
                .distinctBy { it.id }.map { latest[it.id] ?: it }
            // v34 migration: older builds opened one ongoing notification per future match.
            // Remove those legacy cards once, then keep only a compact live-score summary.
            if(!prefs.getBoolean("esports_compact_notifications_v34", false)) {
                if(Build.VERSION.SDK_INT >= 23) manager.activeNotifications
                    .filter { it.id in 1000 until 20_000_000 }
                    .forEach { manager.cancel(it.id) }
                prefs.edit().putBoolean("esports_compact_notifications_v34", true).apply()
            }
            watched.forEach { manager.cancel(ScoreNotifications.favoriteId(it)) }
            val live = EsportsAlertPolicy.relevantLive(mode, feed.live, watched, favoriteTeams, tracked)
            synchronized(ScoreNotifications) {
                val realtimeVisible = manager.activeNotifications.any { it.id == ScoreNotifications.REALTIME_ID }
                if(live.isEmpty() || !prefs.getBoolean("notify_esports_live", true) || realtimeVisible) manager.cancel(ScoreNotifications.SUMMARY_ID)
                else manager.notify(ScoreNotifications.SUMMARY_ID, ScoreNotifications.summary(context, live))
            }
            EsportsScoreScheduler.scheduleRapidResultCheck(context, live.isNotEmpty())
            val timed = watched.filter { it.status == "upcoming" }.map { match ->
                if(match.startAtMillis != null) match else runCatching { EsportsRepository(context).details(match) }
                    .onSuccess { detail ->
                        EsportsTeamLinks.put(context, match.team1, detail.team1Url)
                        EsportsTeamLinks.put(context, match.team2, detail.team2Url)
                    }.getOrNull()?.match ?: match
            }
            EsportsReminders.sync(context, timed)
            if(prefs.getBoolean("match_alerts", true) && prefs.getBoolean("notify_esports_results", true)) {
                val notified = prefs.getStringSet("esports_result_notified", emptySet()).orEmpty() +
                    prefs.getStringSet("esports_result_baselined", emptySet()).orEmpty()
                val transition = EsportsResultTransitionPolicy.advance(
                    watched, prefs.getStringSet("esports_active_seen", emptySet()).orEmpty(), notified, System.currentTimeMillis()
                )
                transition.completed.forEach { match ->
                    manager.cancel(ScoreNotifications.favoriteId(match))
                    NotificationDelivery.result(context, match)
                }
                prefs.edit().putStringSet("esports_active_seen", transition.nextEntries).apply()
            }
            RaundWidgetProvider.updateAll(context)
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}

internal object EsportsScoreScheduler {
    private const val RAPID_WORK = "esports-live-result-check"
    fun schedule(context: Context, mode: String) {
        val work = WorkManager.getInstance(context)
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        val hasReminders = prefs.getBoolean("match_alerts", true) &&
            (SavedEsportsMatches.get(context).isNotEmpty() || SavedEsportsEvents.get(context).isNotEmpty() ||
                prefs.getStringSet("esports_teams", emptySet()).orEmpty().isNotEmpty() || WidgetMatchFollows.ids(context).isNotEmpty())
        if(mode == "off" && !hasReminders) {
            work.cancelUniqueWork("esports-score-check")
            context.getSystemService(NotificationManager::class.java).cancel(ScoreNotifications.SUMMARY_ID)
            return
        }
        val saver = BatteryPolicy.saverEnabled(context)
        val request = PeriodicWorkRequestBuilder<EsportsScoreWorker>(BatteryPolicy.scoreRefreshMinutes(context), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(saver).build()).build()
        work.enqueueUniquePeriodicWork("esports-score-check", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun follow(context: Context, match: EsportsMatch) {
        LiveCardPolicy.resume(context, match.id)
        // Explicit tracking uses the same foreground card as followed tournament matches.
        context.stopService(Intent(context, LiveMatchService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(ScoreNotifications.LIVE_ID)
        context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE).edit().putString("tracked_match_id", match.id).commit()
        monitorRealtime(context, match)
    }
    fun monitorRealtime(context: Context, match: EsportsMatch) {
        ContextCompat.startForegroundService(context, Intent(context, TournamentLiveService::class.java)
            .putExtra("match", Gson().toJson(match)))
    }
    fun scheduleRapidResultCheck(context: Context, hasLiveMatches: Boolean) {
        val work = WorkManager.getInstance(context)
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        if(!hasLiveMatches || BatteryPolicy.saverEnabled(context)) {
            work.cancelUniqueWork(RAPID_WORK)
            prefs.edit().remove("esports_rapid_next_at").apply()
            return
        }
        val now = System.currentTimeMillis()
        if(prefs.getLong("esports_rapid_next_at", 0L) > now + 30_000L) return
        prefs.edit().putLong("esports_rapid_next_at", now + 2 * 60_000L).apply()
        val request = OneTimeWorkRequestBuilder<EsportsScoreWorker>()
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.beginUniqueWork(RAPID_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
    }
    fun unfollow(context: Context) {
        context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE).edit().remove("tracked_match_id").apply()
        context.stopService(Intent(context, LiveMatchService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(ScoreNotifications.LIVE_ID)
        EsportsReminders.refreshSoon(context)
    }
    fun syncSaved(context: Context, before: List<EsportsMatch>, after: List<EsportsMatch>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        (before.map { it.id } - after.map { it.id }.toSet()).forEach { id ->
            before.find { it.id == id }?.let { manager.cancel(ScoreNotifications.favoriteId(it)) }
        }
        after.forEach { manager.cancel(ScoreNotifications.favoriteId(it)) }
        // A match that is already complete when it is saved is history, not a new result alert.
        // Persisting this baseline also prevents old saved cards from resurfacing after updates.
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val historicalResults = after.filter { it.status == "result" && it.score1 != null && it.score2 != null }.map { it.id }.toSet()
        val staleSaved = after.filter { match -> match.startAtMillis?.let { it < now - 18 * 60 * 60_000L } == true }.map { it.id }.toSet()
        if(historicalResults.isNotEmpty() || staleSaved.isNotEmpty()) prefs.edit()
            .putStringSet("esports_result_notified", prefs.getStringSet("esports_result_notified", emptySet()).orEmpty() + historicalResults)
            .putStringSet("esports_result_baselined", prefs.getStringSet("esports_result_baselined", emptySet()).orEmpty() + staleSaved)
            .apply()
        SavedEsportsMatches.put(context, after)
        after.filter { it.status == "live" }.forEach { runCatching { monitorRealtime(context, it) } }
        EsportsReminders.sync(context, after + EsportsRepository(context).cached()?.upcoming.orEmpty())
        if(after.any { it.status == "upcoming" && it.startAtMillis == null }) EsportsReminders.refreshSoon(context)
        RaundWidgetProvider.updateAll(context)
    }
}
