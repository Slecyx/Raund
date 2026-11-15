package app.nightwatch.store

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

class FavoriteWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("nightwatch", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notifications", false) || !prefs.getBoolean("notify_store", true)) return Result.success()
        return try {
            val account = StoreBackgroundRefresh.refresh(applicationContext, force = true)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                StoreBackgroundRefresh.cache(applicationContext, account)
                RadarRepository(applicationContext).record(account)
            }
            FavoriteAlerts.check(applicationContext, account.snapshot, inputData.getInt("notification_slot", 3))
            RaundWidgetProvider.updateAll(applicationContext)
            prefs.edit().putLong("last_background_check", System.currentTimeMillis()).putBoolean("background_login_required", false).apply()
            Result.success()
        } catch (_: LoginRequired) {
            prefs.edit().putBoolean("background_login_required", true).apply()
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Result.retry() }
    }
}

object FavoriteAlerts {
    private val slots = listOf(3, 9)

    internal fun nextDelayMillis(hour: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
        var target = now.toLocalDate().atTime(hour, 0).atZone(now.zone)
        if(!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis().coerceAtLeast(0)
    }

    internal fun slotKey(date: LocalDate, hour: Int) = "$date-$hour"

    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork("favorite-store-check")
        slots.forEach { work.cancelUniqueWork("favorite-store-check-v2-$it") }
        if (!enabled) {
            slots.forEach { work.cancelUniqueWork("favorite-store-check-v3-$it") }
            return
        }
        slots.forEach { hour ->
            val request = PeriodicWorkRequestBuilder<FavoriteWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(nextDelayMillis(hour), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("notification_slot" to hour))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
            work.enqueueUniquePeriodicWork("favorite-store-check-v3-$hour", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
    fun matches(snapshot: StoreSnapshot, ids: Set<String>): List<StoreOffer> {
        val active = StoreFreshness.active(snapshot, System.currentTimeMillis()/1000)
        return (active.offers + active.nightOffers + active.accessories + active.bundles.flatMap { it.items }).filter { it.id in ids }.distinctBy { it.id }
    }
    fun check(context: Context, snapshot: StoreSnapshot, slotHour: Int, preferencesName: String = "nightwatch", now: ZonedDateTime = ZonedDateTime.now()) {
        if (snapshot.isPreview) return
        val key = StoreAlertPolicy.eligibleSlot(slotHour, now) ?: return
        val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notifications", false) || !prefs.getBoolean("notify_store", true)) {
            DeliveryJournal.record(context, "store:$key", "settings"); return
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            DeliveryJournal.record(context, "store:$key", "permission"); return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) { DeliveryJournal.record(context, "store:$key", "permission"); return }
        val tr = prefs.getString("language", Locale.getDefault().language.uppercase()) == "TR"
        manager.createNotificationChannel(NotificationChannel("favorites", if(tr) "Favori mağaza bildirimleri" else "Favorite store alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val found = matches(snapshot, prefs.getStringSet("wishlist", emptySet()).orEmpty())
        val active = StoreFreshness.active(snapshot, System.currentTimeMillis()/1000)
        val hasNightMarket = active.nightMarketAvailable && active.nightOffers.isNotEmpty()
        // Refresh silently unless a favorite or an active Night Market actually needs attention.
        if(found.isEmpty() && !hasNightMarket) return
        val deliveryKey = StoreAlertPolicy.deliveryKey(found, active) ?: return
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .putExtra(if(found.isNotEmpty()) "open_favorites" else "open_store", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when {
            found.size == 1 -> if(tr) "${found[0].name} mağazana geldi" else "${found[0].name} is in your store"
            found.size > 1 -> if(tr) "${found.size} favori skin mağazanda" else "${found.size} favorite skins are in your store"
            hasNightMarket -> if(tr) "Gece Pazarı tekliflerin hazır" else "Your Night Market offers are ready"
            else -> return
        }
        val text = when {
            found.size == 1 -> found[0].priceVp.takeIf { it >= 0 }?.let { "$it ${found[0].currency}" }
                ?: if(tr) "Fiyat için mağazayı aç" else "Open the store for price"
            found.size > 1 -> found.joinToString { it.name }
            hasNightMarket -> if(tr) "İndirimli tekliflerini görmek için dokun." else "Tap to see your discounted offers."
            else -> return
        }
        val notification = NotificationCompat.Builder(context, "favorites").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(intent).setAutoCancel(true).build()
        NotificationDelivery.once(context, deliveryKey, "${preferencesName}_delivery") {
            manager.notify(71, notification)
        }
    }
}
