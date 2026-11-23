package app.nightwatch.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage

@Composable internal fun SettingsPage(
    account: LiveStoreAccount?,
    savedAccounts: List<SavedAccountSlot>,
    themeMode: String,
    onTheme: (String) -> Unit,
    language: String,
    alerts: Boolean,
    onLanguage: (String) -> Unit,
    onAlerts: (Boolean) -> Unit,
    esportsMode: String,
    onEsportsMode: (String) -> Unit,
    esportsTeams: Set<String>,
    matchAlerts: Boolean,
    onMatchAlerts: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onAddAccount: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onShowOnboarding: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var deliveryOpen by remember { mutableStateOf(false) }
    var trustOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var alertsOpen by remember { mutableStateOf(false) }
    val soundPrefs = remember { context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE) }
    var sounds by remember { mutableStateOf(soundPrefs.getBoolean("interaction_sounds", true)) }
    var nowBar by remember { mutableStateOf(soundPrefs.getBoolean("now_bar", true)) }
    var storeNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_store", true)) }
    var reminderNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_esports_reminders", true)) }
    var resultNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_esports_results", true)) }
    var mapNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_esports_maps", true)) }
    var liveNotice by remember { mutableStateOf(soundPrefs.getBoolean("notify_esports_live", true)) }
    var batterySaver by remember { mutableStateOf(soundPrefs.getBoolean("battery_saver", true)) }
    var notificationProfile by remember { mutableStateOf(soundPrefs.getString("esports_notification_profile", "custom") ?: "custom") }
    var autoStore by remember { mutableStateOf(soundPrefs.getBoolean("auto_store_refresh", true)) }
    var spoiler by remember { mutableStateOf(SpoilerPolicy.enabled(context)) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) permissionRevision++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    val canPromote = remember(permissionRevision) { Build.VERSION.SDK_INT >= 36 && context.getSystemService(android.app.NotificationManager::class.java).canPostPromotedNotifications() }
    val liveCapability = LiveUpdateCapability.state(Build.VERSION.SDK_INT, nowBar, ScoreNotifications.permitted(context), canPromote)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Header(txt("Settings", "Ayarlar"), "Raund · $appVersion")
        Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            account?.playerCard?.small?.let { art ->
                AsyncImage(art, txt("Player card", "Oyuncu kartı"), Modifier.padding(end = 14.dp).size(58.dp), contentScale = ContentScale.Fit)
            }
            Column(Modifier.weight(1f)) {
                Text(account?.name ?: txt("Riot account", "Riot hesabı"), color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if(account != null) txt("Connected on this device", "Bu cihazda bağlı") else txt("Not connected", "Bağlı değil"),
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            TextButton(onClick = onLogin) { Text(if(account == null) txt("Connect", "Bağlan") else txt("Renew", "Yenile")) }
        }
        if(account != null || savedAccounts.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(txt("Accounts on this device", "Bu cihazdaki hesaplar") + " · ${savedAccounts.size}",
                    color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if(savedAccounts.size < 5) TextButton(onClick = onAddAccount) {
                    Text(txt("+ Add account", "+ Hesap ekle"), fontSize = 12.sp)
                }
            }
            savedAccounts.forEach { slot ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !slot.active) { onSwitchAccount(slot.id) }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(slot.name + if(slot.active) " · " + txt("Active", "Aktif") else "",
                            color = if(slot.active) Accent else White, fontSize = 13.sp, fontWeight = if(slot.active) FontWeight.SemiBold else FontWeight.Normal)
                        Text(slot.region, color = Muted, fontSize = 10.sp)
                    }
                    if(!slot.active) TextButton(onClick = { onSwitchAccount(slot.id) }) { Text(txt("Switch", "Geç"), fontSize = 12.sp) }
                    if(savedAccounts.size > 1) TextButton(onClick = { onRemoveAccount(slot.id) }) {
                        Text("×", color = Muted, fontSize = 16.sp)
                    }
                }
            }
        }
        HorizontalDivider(color = Raised.copy(alpha = .7f))
        SettingsDisclosure(txt("Appearance & language", "Görünüm ve dil"),
            (if(themeMode == "light") txt("Light", "Açık") else if(themeMode == "dark") txt("Dark", "Koyu") else txt("System", "Sistem")) + " · " + if(language == "TR") "Türkçe" else "English", appearanceOpen) { appearanceOpen = !appearanceOpen }
        if(appearanceOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(txt("System", "Sistem"), themeMode == "system") { onTheme("system") }
                Chip(txt("Light", "Açık"), themeMode == "light") { onTheme("light") }
                Chip(txt("Dark", "Koyu"), themeMode == "dark") { onTheme("dark") }
            }
            SectionTitle(txt("Language", "Dil"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("Türkçe", language == "TR") { onLanguage("TR") }; Chip("English", language == "EN") { onLanguage("EN") } }
            TextButton(onClick = onShowOnboarding, modifier = Modifier.padding(top = 6.dp)) { Text(txt("View welcome guide", "Karşılama rehberini göster")) }
        }
        HorizontalDivider(color = Raised.copy(alpha = .55f))
        NotificationPreferenceRow(txt("Hide match scores", "Maç skorlarını gizle"),
            txt("Shared by Esports, widgets and notifications · reveal per match", "Espor, widget ve bildirimlerde ortak · maç içinde açabilirsin"), spoiler) {
            spoiler = it; SpoilerPolicy.change(context, it)
        }
        HorizontalDivider(color = Raised.copy(alpha = .55f))
        Row(Modifier.fillMaxWidth().heightIn(min = 70.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(txt("Interaction sounds", "İşlem sesleri"), color = White, fontSize = 14.sp)
                Text(txt("Tabs, filters and favorites · follows system sound", "Sekme, filtre ve favoriler · sistem sesine uyar"), color = Muted, fontSize = 11.sp)
            }
            Switch(sounds, onCheckedChange = { sounds = it; soundPrefs.edit().putBoolean("interaction_sounds", it).apply() })
        }
        HorizontalDivider(color = Raised.copy(alpha = .55f))
        NotificationPreferenceRow(txt("Automatic store check", "Otomatik mağaza kontrolü"),
            txt("Updates your store and widgets in the background · no extra alerts", "Mağaza ve widget’lar arka planda güncellenir · ek bildirim göndermez"), autoStore) {
            autoStore = it; soundPrefs.edit().putBoolean("auto_store_refresh", it).apply()
            WidgetRefreshScheduler.schedule(context)
            if(it) WidgetRefreshScheduler.refresh(context)
        }
        Text(txt("Home screen: long-press → Widgets → Raund. Each widget has its own settings and can be resized.",
            "Ana ekrana basılı tut → Widget’lar → Raund. Her widget ayrı ayarlanabilir ve boyutlandırılabilir."),
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp))
        HorizontalDivider(color = Raised.copy(alpha = .55f))
        SettingsDisclosure(txt("Notifications & live scores", "Bildirimler ve canlı skor"), txt("Skin alerts, matches and followed teams", "Skin uyarıları, maçlar ve takip edilen takımlar"), alertsOpen) { alertsOpen = !alertsOpen }
        if(alertsOpen) {
            Row(Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(txt("Live updates / Samsung Now Bar", "Canlı güncellemeler / Samsung Now Bar"), color = White, fontSize = 14.sp)
                    Text(when(liveCapability) {
                        "off" -> txt("Disabled", "Kapalı")
                        "notifications" -> txt("Android notifications are disabled", "Android bildirimleri kapalı")
                        "standard" -> txt("Standard ongoing notification · Android 16 Live Updates unavailable", "Standart sürekli bildirim · Android 16 Live Updates kullanılamıyor")
                        "permission" -> txt("Live update permission required", "Canlı güncelleme izni gerekli")
                        else -> txt("System-eligible; lock-screen placement depends on your phone", "Sistem için uygun; kilit ekranındaki görünüm telefona bağlı")
                    }, color = Muted, fontSize = 12.sp)
                }
                Switch(nowBar, onCheckedChange = { nowBar = it; soundPrefs.edit().putBoolean("now_bar", it).apply() })
            }
            if(Build.VERSION.SDK_INT >= 36 && nowBar && !canPromote) TextButton(onClick = {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
            }) { Text(txt("Allow live updates", "Canlı güncelleme izni ver")) }
            Row(Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(txt("Store & favorite alerts", "Mağaza ve favori uyarıları"), color = White, fontSize = 14.sp)
                    Text(txt("Twice daily · around 03:00 and 09:00", "Günde iki kez · yaklaşık 03.00 ve 09.00"), color = Muted, fontSize = 11.sp)
                }
                Switch(alerts, onCheckedChange = onAlerts)
            }
            if(alerts) NotificationPreferenceRow(txt("Daily store summary", "Günlük mağaza özeti"),
                txt("Favorite skins and Night Market · 03:00 / 09:00", "Favori skinler ve Gece Pazarı · 03.00 / 09.00"), storeNotice) {
                storeNotice = it; soundPrefs.edit().putBoolean("notify_store", it).apply(); FavoriteAlerts.schedule(context, it)
            }
            HorizontalDivider(color = Raised.copy(alpha = .55f))
            Row(Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(txt("Match reminders", "Maç hatırlatıcıları"), color = White, fontSize = 14.sp)
                    Text(txt("Saved matches & followed teams", "Kayıtlı maçlar ve takip edilen takımlar"), color = Muted, fontSize = 11.sp)
                }
                Switch(matchAlerts, onCheckedChange = onMatchAlerts)
            }
            if(matchAlerts) {
                SectionTitle(txt("Alert intensity", "Bildirim yoğunluğu"))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        Triple("important", txt("Important only", "Sadece önemli"), listOf(true, true, false, false)),
                        Triple("detailed", txt("Every map", "Her harita"), listOf(true, true, true, true)),
                        Triple("silent", txt("Silent tracking", "Sessiz takip"), listOf(false, false, false, true))
                    ).forEach { (key, label, values) ->
                        Chip(label, notificationProfile == key) {
                            notificationProfile = key
                            reminderNotice = values[0]; resultNotice = values[1]; mapNotice = values[2]; liveNotice = values[3]
                            soundPrefs.edit().putString("esports_notification_profile", key)
                                .putBoolean("notify_esports_reminders", reminderNotice)
                                .putBoolean("notify_esports_results", resultNotice)
                                .putBoolean("notify_esports_maps", mapNotice)
                                .putBoolean("notify_esports_live", liveNotice).apply()
                            EsportsScoreScheduler.schedule(context, soundPrefs.getString("esports_mode", "off") ?: "off")
                            EsportsReminders.refreshSoon(context)
                        }
                    }
                }
                NotificationPreferenceRow(txt("15-minute reminder", "15 dakika hatırlatması"),
                    txt("Saved matches, teams and tournaments", "Kayıtlı maç, takım ve turnuvalar"), reminderNotice) {
                    reminderNotice = it; notificationProfile = "custom"; soundPrefs.edit().putString("esports_notification_profile", "custom").putBoolean("notify_esports_reminders", it).apply()
                    EsportsReminders.sync(context, SavedEsportsMatches.get(context) + EsportsRepository(context).cached()?.upcoming.orEmpty())
                }
                NotificationPreferenceRow(txt("Final result", "Maç sonucu"),
                    txt("One notification when a followed match ends", "Takip edilen maç bitince tek bildirim"), resultNotice) {
                    resultNotice = it; notificationProfile = "custom"; soundPrefs.edit().putString("esports_notification_profile", "custom").putBoolean("notify_esports_results", it).apply()
                }
                NotificationPreferenceRow(txt("Map result", "Harita sonucu"),
                    txt("One alert when a followed match completes a map", "Takip edilen maçta her harita bitince tek uyarı"), mapNotice) {
                    mapNotice = it; notificationProfile = "custom"; soundPrefs.edit().putString("esports_notification_profile", "custom").putBoolean("notify_esports_maps", it).apply()
                }
                NotificationPreferenceRow(txt("Live score summary", "Canlı skor özeti"),
                    txt("Compact ongoing card for followed live matches", "Takip edilen canlı maçlar için tek özet kartı"), liveNotice) {
                    liveNotice = it; notificationProfile = "custom"; soundPrefs.edit().putString("esports_notification_profile", "custom").putBoolean("notify_esports_live", it).apply()
                    EsportsScoreScheduler.schedule(context, esportsMode)
                }
            }
            SectionTitle(txt("Live score display", "Canlı skor gösterimi"))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Chip(txt("Off", "Kapalı"), esportsMode == "off") { onEsportsMode("off") }
                Chip(txt("Favorite teams", "Favori takımlar"), esportsMode == "favorites") { onEsportsMode("favorites") }
                Chip(txt("All live", "Tüm canlılar"), esportsMode == "all") { onEsportsMode("all") }
            }
            Text(if(esportsTeams.isEmpty()) txt("Follow teams from Esports to filter scores.", "Skorları filtrelemek için Espor'dan takım takip et.")
                else esportsTeams.sorted().joinToString(" · "), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
        HorizontalDivider(color = Raised.copy(alpha = .7f), modifier = Modifier.padding(top = 22.dp))
        Row(Modifier.fillMaxWidth().clickable { deliveryOpen = !deliveryOpen }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("Delivery & permissions", "İzinler ve çalışma şekli"), color = White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if(deliveryOpen) "⌃" else "⌄", color = Muted)
        }
        if(deliveryOpen) {
            NotificationPreferenceRow(txt("Battery saver", "Pil tasarrufu"),
                txt("Default · widgets every 2 hours, background scores hourly, live matches every 30 seconds", "Varsayılan · widget 2 saatte bir, arka plan skorları saatte bir, canlı maçlar 30 saniyede bir"), batterySaver) {
                batterySaver = it
                soundPrefs.edit().putBoolean("battery_saver", it).apply()
                WidgetRefreshScheduler.schedule(context)
                EsportsScoreScheduler.schedule(context, esportsMode)
            }
            Note(if(batterySaver) txt("Battery saver is on. Live tracking starts at match time and updates only when the displayed score changes. Turn it off only if you prefer faster background checks.",
                "Pil tasarrufu açık. Canlı takip maç saatinde başlar ve yalnızca ekrandaki skor değişince güncellenir. Daha sık arka plan kontrolü istersen kapatabilirsin.") else txt("Fast checks are on: widgets refresh every 30 minutes, background scores every 15 minutes, and explicit live tracking can poll every 10 seconds.",
                "Sık kontrol açık: widget 30 dakikada bir, arka plan skorları 15 dakikada bir yenilenir; doğrudan takip edilen canlı maç 10 saniyede bir kontrol edilebilir."))
            TextButton(onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) } }) {
                Text(txt("Android notification settings", "Android bildirim ayarları"))
            }
            if(Build.VERSION.SDK_INT >= 31 && !EsportsReminders.exactAllowed(context)) TextButton(onClick = {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"))) }
            }) { Text(txt("Allow precise reminders", "Kesin hatırlatıcı izni")) }
            Note(txt("Samsung Now Bar: enable Live notifications and lock-screen notifications in your phone settings. Availability depends on your device and One UI version.",
                "Samsung Now Bar: Telefon ayarlarından Canlı bildirimler ve kilit ekranı bildirimlerini aç. Görünürlük cihazına ve One UI sürümüne bağlıdır."))
        }
        HorizontalDivider(color = Raised.copy(alpha = .7f))
        Row(Modifier.fillMaxWidth().clickable { trustOpen = !trustOpen }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("Account & trust", "Hesap ve güven"), color = White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if(trustOpen) "⌃" else "⌄", color = Muted)
        }
        if(trustOpen) {
            if(account != null) TextButton(onClick = onLogout) {
                Text(txt("Sign out of this device", "Bu cihazdan çıkış yap"), color = MaterialTheme.colorScheme.error)
            }
            Note(txt("You enter your password on Riot's HTTPS page. Tokens are encrypted on this device; there is no Raund account server. Sign out clears the session and browser cookies.",
                "Parolanı Riot'un HTTPS sayfasında girersin. Oturum bu cihazda şifrelenir; Raund hesap sunucusu yoktur. Çıkış oturumu ve çerezleri siler."))
            Note(txt("Independent and not approved by Riot. Store tracking uses undocumented services, so no ban-free guarantee is possible.",
                "Bağımsız ve Riot tarafından onaylı değil. Mağaza takibi belgelenmemiş servisler kullanır; ban olmayacağı garantisi verilemez."))
            Note(txt("Esports source: VLR.gg. Source credit is not a license; redistribution requires provider permission. Google/Apple sign-in may require an approved Riot integration rather than this embedded browser.",
                "Espor kaynağı: VLR.gg. Kaynak belirtmek lisans değildir; dağıtım için sağlayıcı izni gerekir. Google/Apple girişi bu gömülü tarayıcı yerine onaylı Riot entegrasyonu gerektirebilir."))
            TextButton(onClick = { openHttpsUrl(context,
                "https://support-developer.riotgames.com/hc/en-us/articles/22698769097107-VALORANT") }) {
                Text(txt("Riot policy ↗", "Riot politikası ↗"))
            }
        }
        CompanionSettings(onLogin)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun NotificationPreferenceRow(title: String, subtitle: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = White, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(enabled, onCheckedChange = onChange)
    }
}

@Composable private fun SettingsDisclosure(title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Text(if(expanded) "−" else "+", color = Muted, fontSize = 22.sp)
    }
}
