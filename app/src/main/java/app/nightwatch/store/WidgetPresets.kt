package app.nightwatch.store

import android.content.Context
import com.google.gson.Gson

internal data class WidgetPreset(val mode: String, val all: Boolean, val match: String?)
internal object WidgetPresets {
    fun decode(raw: String): List<WidgetPreset> {
        require(raw.length <= 10_000)
        val values = Gson().fromJson(raw, Array<WidgetPreset>::class.java).toList()
        require(values.size <= 32)
        require(values.all { it.mode in setOf("auto", "store", "night", "matches", "career") &&
            (it.match == null || Regex("^[0-9]{1,12}$").matches(it.match)) })
        return values.distinct()
    }
    fun capture(context: Context): String {
        val prefs = context.getSharedPreferences("raund_widgets", 0)
        val rows = RaundWidgetProvider.ids(context).map { id -> WidgetPreset(prefs.getString("mode_$id", "auto") ?: "auto",
            prefs.getBoolean("all_$id", false), prefs.getString("match_$id", null)) }
        return Gson().toJson(rows.distinct().take(32))
    }
    fun imported(context: Context): List<WidgetPreset> = runCatching {
        decode(context.getSharedPreferences("nightwatch", 0).getString("widget_presets", "[]") ?: "[]")
    }.getOrDefault(emptyList())
}
