package app.nightwatch.store

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object WidgetRenderer {
    fun render(context: Context, manager: AppWidgetManager, id: Int, mode: String) {
        val content = WidgetModels.read(context, id, mode)
        val options = manager.getAppWidgetOptions(id)
        val matchRows = if(content.page == "esports") 1 else 0
        val views = if(Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
            SizeF(120f, 65f) to views(context, id, content, 0, true),
            SizeF(160f, 110f) to views(context, id, content, matchRows),
            SizeF(220f, 110f) to views(context, id, content, matchRows),
            SizeF(240f, 220f) to views(context, id, content, 3),
            SizeF(320f, 220f) to views(context, id, content, 4),
            SizeF(240f, 300f) to views(context, id, content, 5),
            SizeF(320f, 360f) to views(context, id, content, 6)))
        else views(context, id, content, maxOf(matchRows, rowCount(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) < 110)
        manager.updateAppWidget(id, views)
    }

    fun rowCount(width: Int, height: Int): Int = if(width < 190 || height < 170) 0 else when {
        height < 245 -> 3
        height < 305 -> 4
        height < 365 -> 5
        else -> 6
    }

    internal fun views(context: Context, id: Int, content: WidgetContent, rows: Int, compact: Boolean = false): RemoteViews {
        val views = RemoteViews(context.packageName, if(rows > 0) R.layout.widget_raund_expanded else R.layout.widget_raund)
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val tr = prefs.getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val dark = when(prefs.getString("theme", "system")) {
            "light" -> false; "dark" -> true
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val colors = AppThemes.colors(dark)
        views.setInt(R.id.widget_root, "setBackgroundResource", if(dark) R.drawable.widget_background else R.drawable.widget_background_light)
        listOf(R.id.widget_title, R.id.widget_badge).forEach { views.setTextColor(it, colors.onSurface.toArgb()) }
        listOf(R.id.widget_subtitle, R.id.widget_eyebrow).forEach { views.setTextColor(it, colors.onSurfaceVariant.toArgb()) }
        listOf(R.id.widget_logo, R.id.widget_refresh, R.id.widget_config).forEach {
            views.setInt(it, "setColorFilter", colors.onSurface.toArgb())
        }
        val checked = content.checkedAt.takeIf { it > 0 }?.let {
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        }
        views.setTextViewText(R.id.widget_eyebrow, content.eyebrow + checked?.let { " · $it" }.orEmpty())
        // Static relative countdowns become misleading between background refreshes. Render the deadline instead.
        val deadline = content.deadline?.let {
            DateTimeFormatter.ofPattern("d MMM HH:mm", if(tr) Locale.forLanguageTag("tr") else Locale.ENGLISH)
                .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        }
        views.setTextViewText(R.id.widget_badge, deadline ?: content.badge)
        views.setContentDescription(R.id.widget_root, listOf(content.title, content.subtitle,
            if(checked == null) if(tr) "Henüz kontrol edilmedi" else "Not checked yet" else
                if(tr) "Son kontrol $checked" else "Last checked $checked").joinToString(". "))
        views.setTextViewText(R.id.widget_title, content.title)
        views.setTextViewText(R.id.widget_subtitle, content.subtitle)
        views.setViewVisibility(R.id.widget_subtitle, if(compact) View.GONE else View.VISIBLE)
        if(compact) {
            views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            views.setInt(R.id.widget_title, "setMaxLines", 1)
            views.setTextViewText(R.id.widget_eyebrow, "")
            val density = context.resources.displayMetrics.density
            views.setViewPadding(R.id.widget_root, (10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            views.setViewVisibility(R.id.widget_badge, View.GONE)
        }
        val open = content.match?.let { MatchNavigation.intent(context, it) } ?: Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(when(content.page) { "esports" -> "open_esports"; "favorites" -> "open_favorites"; "career" -> "open_career"; else -> "open_store" }, true)
        }
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id,
            Intent(context, RaundWidgetProvider::class.java).setAction(RaundWidgetProvider.REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        views.setOnClickPendingIntent(R.id.widget_config, PendingIntent.getActivity(context, id + 50_000,
            Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if(rows > 0) {
            views.removeAllViews(R.id.widget_rows)
            if(content.page == "store" && (rows == 3 || content.nightMarket)) {
                content.rows.take(if(content.nightMarket) 6 else 4).chunked(2).forEach { pair ->
                    val grid = RemoteViews(context.packageName, if(content.nightMarket) R.layout.widget_raund_night_row else R.layout.widget_raund_grid_row)
                    pair.forEachIndexed { index, row ->
                        val title = if(index == 0) R.id.widget_grid_title1 else R.id.widget_grid_title2
                        val detail = if(index == 0) R.id.widget_grid_detail1 else R.id.widget_grid_detail2
                        val image = if(index == 0) R.id.widget_grid_image1 else R.id.widget_grid_image2
                        grid.setTextViewText(title, row.title); grid.setTextViewText(detail, row.detail)
                        grid.setTextColor(title, colors.onSurface.toArgb()); grid.setTextColor(detail, colors.onSurfaceVariant.toArgb())
                        row.image?.let { WidgetArtwork.cached(context, it) }?.let { grid.setImageViewBitmap(image, it) }
                    }
                    if(pair.size < 2) grid.setViewVisibility(R.id.widget_grid_cell2, View.INVISIBLE)
                    views.addView(R.id.widget_rows, grid)
                }
            } else content.rows.take(rows).forEach { row ->
                val item = RemoteViews(context.packageName, R.layout.widget_raund_row)
                item.setTextViewText(R.id.widget_row_title, row.title)
                item.setTextViewText(R.id.widget_row_detail, row.detail)
                item.setTextColor(R.id.widget_row_title, colors.onSurface.toArgb())
                item.setTextColor(R.id.widget_row_detail, colors.onSurfaceVariant.toArgb())
                row.match?.let { match -> item.setOnClickPendingIntent(R.id.widget_row_root,
                    MatchNavigation.pending(context, match, id * 31 + match.id.hashCode())) }
                val art = row.image?.let { WidgetArtwork.cached(context, it) }
                if(art == null) item.setViewVisibility(R.id.widget_row_image, View.GONE)
                else item.setImageViewBitmap(R.id.widget_row_image, art)
                val secondary = row.secondaryImage?.let { WidgetArtwork.cached(context, it) }
                if(secondary == null) item.setViewVisibility(R.id.widget_row_image_secondary, View.GONE)
                else item.setImageViewBitmap(R.id.widget_row_image_secondary, secondary)
                views.addView(R.id.widget_rows, item)
            }
            views.setViewVisibility(R.id.widget_subtitle, if(content.rows.isEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_title, if((content.page == "store" || rows == 1) && content.rows.isNotEmpty()) View.GONE else View.VISIBLE)
        }
        return views
    }
}
