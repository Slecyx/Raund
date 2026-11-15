package app.nightwatch.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import androidx.work.*
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object StoreBackgroundRefresh {
    private val mutex = Mutex()
    fun merge(fresh: LiveStoreAccount, previous: LiveStoreAccount?): LiveStoreAccount {
        val old = previous?.takeIf { it.accountId != null && it.accountId == fresh.accountId }
        return fresh.copy(career = fresh.career ?: old?.career, progress = fresh.progress ?: old?.progress,
            inventory = fresh.inventory ?: old?.inventory, playerCard = fresh.playerCard ?: old?.playerCard)
    }
    fun cache(context: Context, fresh: LiveStoreAccount): LiveStoreAccount {
        val store = SessionStore(context)
        val merged = merge(fresh, store.cached())
        store.cache(merged)
        return merged
    }
    fun cacheAgeMillis(force: Boolean, storeWidget: Boolean): Long = when {
        force -> 60_000L
        storeWidget -> 30 * 60_000L
        else -> 4 * 60 * 60_000L
    }
    suspend fun refresh(context: Context, force: Boolean, storeWidget: Boolean = false): LiveStoreAccount = mutex.withLock {
        val secure = SessionStore(context)
        val old = secure.cached()
        val now = System.currentTimeMillis()
        if(old != null && !old.snapshot.isPreview && old.snapshot.refreshEpochSeconds > now / 1000 &&
            now - old.updatedAt < cacheAgeMillis(force, storeWidget)) return@withLock old
        cache(context, RiotLiveGateway(context).loadStore(secure.validSession(), includeDetails = false))
    }
}

internal object WidgetArtwork {
    private fun file(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "widget_art"), "$hash.png")
    }
    fun cached(context: Context, url: String): Bitmap? = runCatching { BitmapFactory.decodeFile(file(context, url).path) }.getOrNull()
    suspend fun prepare(context: Context, urls: List<String>) {
        val loader = ImageLoader(context)
        try {
            urls.distinct().take(16).forEach { url ->
                if(!url.startsWith("https://") || cached(context, url) != null) return@forEach
                runCatching {
                    val result = loader.execute(ImageRequest.Builder(context).data(url).size(160, 80).allowHardware(false).build()) as? SuccessResult
                    result?.drawable?.toBitmap()?.let { bitmap ->
                        val target = file(context, url)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        } finally { loader.shutdown() }
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val ids = RaundWidgetProvider.ids(context)
        val widgetPrefs = context.getSharedPreferences("raund_widgets", 0)
        val modes = ids.map { id -> widgetPrefs.getString("mode_$id", RaundWidgetProvider.defaultMode(
            android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className)) }
        var failed = false
        if(prefs.getBoolean("auto_store_refresh", true) || inputData.getBoolean("manual", false)) {
            if(SessionStore(context).session() != null) try {
                val account = StoreBackgroundRefresh.refresh(context, inputData.getBoolean("manual", false), modes.any { it in setOf("auto", "store", "night") })
                RadarRepository(context).record(account)
                prefs.edit().putLong("last_background_check", System.currentTimeMillis()).putBoolean("background_login_required", false).apply()
            } catch(cancel: CancellationException) { throw cancel }
            catch(_: LoginRequired) { prefs.edit().putBoolean("background_login_required", true).apply() }
            catch(_: Exception) { failed = true }
        }
        if(modes.any { it in setOf("auto", "matches") }) try {
            val repo = EsportsRepository(context)
            repo.load(force = inputData.getBoolean("manual", false))
            val selected = ids.mapNotNull { widgetPrefs.getString("match_$it", null) }.toSet()
            WidgetModels.matches(context).filter { it.id in selected }.take(4).forEach { repo.details(it) }
        } catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { failed = true }
        WidgetArtwork.prepare(context, ids.flatMap { WidgetModels.read(context, it,
            RaundWidgetProvider.defaultMode(android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetInfo(it)?.provider?.className)).rows.flatMap { row -> listOfNotNull(row.image, row.secondaryImage) } })
        RaundWidgetProvider.updateAll(context)
        if(failed) Result.retry() else Result.success()
    }
}

internal object WidgetRefreshScheduler {
    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences("nightwatch", 0)
        val enabled = RaundWidgetProvider.ids(context).isNotEmpty() || prefs.getBoolean("auto_store_refresh", true) && prefs.getBoolean("connected", false)
        val work = WorkManager.getInstance(context)
        if(!enabled) { work.cancelUniqueWork("raund-widget-refresh"); return }
        val saver = BatteryPolicy.saverEnabled(context)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(saver).build()
        work.enqueueUniquePeriodicWork("raund-widget-refresh", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(BatteryPolicy.widgetRefreshMinutes(context), TimeUnit.MINUTES)
                .setConstraints(constraints).build())
    }
    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("raund-widget-refresh-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInputData(workDataOf("manual" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}
