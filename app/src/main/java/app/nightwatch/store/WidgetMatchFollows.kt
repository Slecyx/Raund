package app.nightwatch.store

import android.content.Context

internal object WidgetMatchFollows {
    fun ids(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("raund_widgets", 0)
        return RaundWidgetProvider.ids(context).filter { prefs.getString("mode_$it", "") == "matches" }
            .mapNotNull { prefs.getString("match_$it", null) }.toSet()
    }
    fun sync(context: Context) {
        val settings = context.getSharedPreferences("nightwatch", 0)
        val candidates = WidgetModels.matches(context).filter { it.id in ids(context) }
        EsportsReminders.sync(context, SavedEsportsMatches.get(context) + EsportsRepository(context).cached()?.upcoming.orEmpty() + candidates)
        EsportsScoreScheduler.schedule(context, settings.getString("esports_mode", "off") ?: "off")
        if(settings.getBoolean("match_alerts", true)) candidates.filter { it.status == "live" }.forEach {
            runCatching { EsportsScoreScheduler.monitorRealtime(context, it) }
        }
    }
}
