package app.nightwatch.store

import android.content.Context
import com.google.gson.Gson

internal data class WatchLaterEntry(val match: EsportsMatch, val savedAt: Long, val watched: Boolean = false)

/** A local replay queue, independent from match follows and notification subscriptions. */
internal object WatchLater {
    const val KEY = "esports_watch_later"
    fun sanitize(entries: List<WatchLaterEntry>): List<WatchLaterEntry> = entries.filter { MatchNavigation.valid(it.match) }
        .distinctBy { it.match.id }.take(100).map { entry ->
            fun image(url: String?) = url?.takeIf { it.startsWith("https://owcdn.net/") || it.startsWith("https://www.vlr.gg/img/") }
            entry.copy(savedAt = entry.savedAt.coerceAtLeast(0), match = entry.match.copy(score1 = null, score2 = null,
                url = "https://www.vlr.gg/${entry.match.id}", event = entry.match.event.take(200), series = "",
                whenText = entry.match.whenText.take(120), team1Logo = image(entry.match.team1Logo), team2Logo = image(entry.match.team2Logo),
                eventIcon = image(entry.match.eventIcon)))
        }
    fun decode(raw: String): List<WatchLaterEntry> {
        require(raw.length <= 200_000)
        return sanitize(Gson().fromJson(raw, Array<WatchLaterEntry>::class.java).toList())
    }
    fun get(context: Context): List<WatchLaterEntry> = runCatching {
        decode(context.getSharedPreferences("nightwatch", 0).getString(KEY, "[]") ?: "[]")
    }.getOrDefault(emptyList())
    fun put(context: Context, entries: List<WatchLaterEntry>): List<WatchLaterEntry> {
        val safe = sanitize(entries)
        context.getSharedPreferences("nightwatch", 0).edit().putString(KEY, Gson().toJson(safe)).apply()
        return safe
    }
}
