package app.nightwatch.store

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object EsportsReminders {
    const val LEAD_MILLIS = 15 * 60 * 1000L
    const val MONITOR_ACTION = "app.nightwatch.store.MATCH_MONITOR_START"
    fun reminderAt(startAtMillis: Long): Long = startAtMillis - LEAD_MILLIS
    /**
     * The precise alarm exists to begin monitoring a match, not to warm up a foreground
     * service. Starting two minutes early kept a network poller alive while there was no
     * score to show. Start at the scheduled kick-off instead.
     */
    fun monitorAt(startAtMillis: Long): Long = startAtMillis

    private fun prefs(context: Context) = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
    private fun intent(context: Context, id: String, monitor: Boolean) = Intent(context, EsportsReminderReceiver::class.java)
        .setAction(if(monitor) MONITOR_ACTION else "app.nightwatch.store.MATCH_REMINDER")
        .setData(Uri.parse("raund://esports/${if(monitor) "monitor" else "reminder"}/$id"))

    private fun pending(context: Context, id: String, match: EsportsMatch? = null, monitor: Boolean = false): PendingIntent {
        val request = intent(context, id, monitor)
        if(match != null) request.putExtra("match", Gson().toJson(match))
        return PendingIntent.getBroadcast(context, id.hashCode(), request,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private const val LEGACY_ALARMS_CLEARED = "esports_legacy_alarms_cleared_v1"
    private fun cancelLegacy(context: Context, alarm: AlarmManager, id: String, monitor: Boolean) {
        val legacy = Intent(context, EsportsReminderReceiver::class.java)
            .setAction(if(monitor) MONITOR_ACTION else "app.nightwatch.store.MATCH_REMINDER")
            .setData(Uri.parse("valorshop://esports/${if(monitor) "monitor" else "reminder"}/$id"))
        PendingIntent.getBroadcast(context, id.hashCode(), legacy, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let { alarm.cancel(it); it.cancel() }
    }

    fun sync(context: Context, candidates: List<EsportsMatch>) {
        val settings = prefs(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val saved = SavedEsportsMatches.get(context).map { it.id }.toSet() + WidgetMatchFollows.ids(context)
        val teams = settings.getStringSet("esports_teams", emptySet()).orEmpty()
        val events = SavedEsportsEvents.get(context).map { it.title }
        val enabled = settings.getBoolean("match_alerts", true) &&
            (settings.getBoolean("notify_esports_reminders", true) || settings.getBoolean("notify_esports_results", true) ||
                settings.getBoolean("notify_esports_maps", true) || settings.getBoolean("notify_esports_live", true))
        val now = System.currentTimeMillis()
        // Following an entire tournament can expose hundreds of future rows. Only alarms that
        // can realistically become relevant should exist on-device; the next worker refresh
        // will schedule later rounds as the calendar advances.
        val wanted = if(!enabled) emptyList() else candidates.distinctBy { it.id }.filter { match ->
            match.status == "upcoming" && match.startAtMillis?.let { it > now } == true &&
                follows(match, saved, teams, events)
        }.sortedBy { it.startAtMillis }.take(24)
        val before = settings.getStringSet("esports_reminder_ids", emptySet()).orEmpty()
        val next = wanted.map { it.id }.toSet()
        // Builds before 0.42.1 used the "valorshop" data scheme. Those alarms no longer match the
        // current intents, so cancel them explicitly; otherwise they would fire alongside the new ones.
        if(!settings.getBoolean(LEGACY_ALARMS_CLEARED, false)) {
            before.forEach { id -> listOf(false, true).forEach { monitor -> cancelLegacy(context, alarm, id, monitor) } }
            settings.edit().putBoolean(LEGACY_ALARMS_CLEARED, true).apply()
        }
        (before - next).forEach { alarm.cancel(pending(context, it)); alarm.cancel(pending(context, it, monitor = true)) }
        wanted.forEach { match ->
            fun schedule(at: Long, monitor: Boolean) {
                val operation = pending(context, match.id, match, monitor)
                if(exactAllowed(context)) {
                    try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation) }
                    catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation) }
                } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            }
            val start = match.startAtMillis!!
            if(reminderAt(start) > now && settings.getBoolean("notify_esports_reminders", true)) schedule(reminderAt(start), false)
            else alarm.cancel(pending(context, match.id))
            if(settings.getBoolean("notify_esports_results", true) || settings.getBoolean("notify_esports_maps", true) ||
                settings.getBoolean("notify_esports_live", true))
                schedule(monitorAt(start).coerceAtLeast(now + 1000), true)
            else alarm.cancel(pending(context, match.id, monitor = true))
        }
        settings.edit().putStringSet("esports_reminder_ids", next).apply()
    }

    internal fun follows(match: EsportsMatch, saved: Set<String>, teams: Set<String>, events: List<String>): Boolean =
        match.id in saved || teams.any { it.equals(match.team1, true) || it.equals(match.team2, true) } ||
            events.any { it.equals(match.event, true) }

    fun refreshSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("esports-reminder-refresh", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EsportsScoreWorker>().setInitialDelay(1, TimeUnit.SECONDS).build())
    }
}

internal object EsportsTeamLinks {
    private const val KEY = "esports_team_urls"
    fun put(context: Context, name: String, url: String?) {
        if(url == null || !Regex("^https://www\\.vlr\\.gg/team/\\d+(?:/.*)?$").matches(url)) return
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        val saved = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        prefs.edit().putString(KEY, saved.put(name, url).toString()).apply()
    }
    fun get(context: Context, name: String): String? {
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        val saved = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrNull() ?: return null
        return saved.optString(name).takeIf { it.startsWith("https://www.vlr.gg/team/") }
    }
}

class EsportsReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val match = runCatching { Gson().fromJson(intent.getStringExtra("match"), EsportsMatch::class.java) }.getOrNull()
            ?.takeIf(MatchNavigation::valid) ?: return
        val prefs = context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        if(!prefs.getBoolean("match_alerts", true) || !ScoreNotifications.permitted(context)) return
        val saved = SavedEsportsMatches.get(context).any { it.id == match.id } || match.id in WidgetMatchFollows.ids(context)
        val teams = prefs.getStringSet("esports_teams", emptySet()).orEmpty()
        val events = SavedEsportsEvents.get(context).map { it.title }
        if(!EsportsReminders.follows(match, if(saved) setOf(match.id) else emptySet(), teams, events)) return
        if(intent.action == EsportsReminders.MONITOR_ACTION) {
            if(prefs.getBoolean("notify_esports_results", true) || prefs.getBoolean("notify_esports_maps", true) ||
                prefs.getBoolean("notify_esports_live", true))
                runCatching { EsportsScoreScheduler.monitorRealtime(context, match) }
            return
        }
        if(!prefs.getBoolean("notify_esports_reminders", true)) return
        val start = match.startAtMillis ?: return
        val key = "${match.id}:$start"
        val sent = prefs.getStringSet("esports_reminded_keys", emptySet()).orEmpty()
        if(key in sent || start < System.currentTimeMillis() - 2 * 60_000L) return
        NotificationDelivery.once(context, "reminder:$key") {
            context.getSystemService(NotificationManager::class.java)
                .notify(ScoreNotifications.reminderId(match), ScoreNotifications.reminder(context, match))
        }
    }
}

class EsportsAlarmRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        val cached = EsportsRepository(context).cached()
        val widgetMatches = WidgetModels.matches(context).filter { it.id in WidgetMatchFollows.ids(context) }
        EsportsReminders.sync(context, SavedEsportsMatches.get(context) + cached?.upcoming.orEmpty() + widgetMatches)
        EsportsReminders.refreshSoon(context)
    }
}
