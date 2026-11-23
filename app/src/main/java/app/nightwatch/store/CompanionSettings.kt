package app.nightwatch.store

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun CompanionSettings(onConnect: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var historyOpen by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }
    var crosshairOpen by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { CacheMaintenance.bytes(context) } }
    var restore by remember { mutableStateOf<RaundArchivePayload?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val success = txt("Full local archive saved. No sign-in information is included.", "Kapsamlı yerel yedek kaydedildi. Giriş bilgileri dahil edilmez.")
    val failure = txt("Could not read or save this backup. Choose a valid Raund JSON file.", "Yedek okunamadı veya kaydedilemedi. Geçerli bir Raund JSON dosyası seç.")
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching {
                val raw = RaundArchive.encode(context)
                context.contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray(Charsets.UTF_8)) } ?: error("No stream")
            }.isSuccess }
            message = if(saved) success else failure
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(8_192)
                    while(true) {
                        val count = reader.read(buffer)
                        if(count < 0) break
                        require(output.length + count <= RaundArchive.MAX_SIZE) { "Archive too large" }
                        output.append(buffer, 0, count)
                    }
                    output.toString()
                } ?: error("No stream")
                RaundArchive.decode(raw)
            } }
            read.onSuccess { restore = it }.onFailure { message = failure }
        }
    }
    HorizontalDivider(color = Raised)
    TextButton(onClick = { guideOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(txt("Quick start guide", "Hızlı başlangıç rehberi")) }
    if(guideOpen) RaundQuickGuide(onDismiss = { guideOpen = false }, onConnect = { guideOpen = false; onConnect() })
    TextButton(onClick = { historyOpen = true }, modifier = Modifier.fillMaxWidth()) {
        Text(txt("Notification history & diagnostics", "Bildirim geçmişi ve teşhis"))
    }
    TextButton(onClick = { crosshairOpen = true }, modifier = Modifier.fillMaxWidth()) {
        Text(txt("Pro crosshair library", "Pro nişangâh kütüphanesi"))
    }
    if(crosshairOpen) CrosshairLibraryDialog { crosshairOpen = false }
    val cacheClearedMessage = txt("Public caches cleared. Your sign-in and favorites are untouched.", "Genel önbellek temizlendi. Oturumun ve favorilerin korundu.")
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(txt("Public cache", "Genel önbellek"), color = White, fontSize = 14.sp)
            Text(txt("Catalog, esports and news · ", "Katalog, espor ve haberler · ") + CacheMaintenance.formatSize(cacheBytes), color = Muted, fontSize = 11.sp)
        }
        TextButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { CacheMaintenance.clearPublicCaches(context) }
                cacheBytes = withContext(Dispatchers.IO) { CacheMaintenance.bytes(context) }
                message = cacheClearedMessage
            }
        }) { Text(txt("Clear", "Temizle")) }
    }
    Text(txt("Full local archive", "Kapsamlı yerel yedek"), color = White, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
    Text(txt("Preferences, favorites, follows, widgets, notification settings, Store, Career, season progress and inventory. Your Riot session stays on this device.",
        "Tercihler, favoriler, takipler, widget'lar, bildirim ayarları, Mağaza, Kariyer, sezon ilerlemesi ve envanter. Riot oturumu bu cihazda kalır."), color = Muted, fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 8.dp))
    Row {
        TextButton(onClick = { export.launch("Raund-full-backup.json") }) { Text(txt("Save archive", "Yedeği kaydet")) }
        TextButton(onClick = { import.launch(arrayOf("application/json", "text/plain")) }) { Text(txt("Restore archive", "Yedeği yükle")) }
    }
    message?.let { Text(it, color = Muted, fontSize = 12.sp) }
    restore?.let { archive ->
        AlertDialog(onDismissRequest = { restore = null }, title = { Text(txt("Restore full archive?", "Kapsamlı yedek yüklensin mi?")) },
            text = { Text(txt("Preferences, follows and cached Store/Career data will be replaced. Your account session will not change.",
                "Tercihler, takipler ve kayıtlı Mağaza/Kariyer verileri değişir. Hesap oturumun değişmez.")) },
            confirmButton = { TextButton(onClick = {
                scope.launch { val ok = withContext(Dispatchers.IO) { runCatching { RaundArchive.restore(context, archive) }.isSuccess }
                    restore = null; if(ok) activity?.recreate() else message = failure }
            }) { Text(txt("Restore", "Yükle")) } }, dismissButton = { TextButton(onClick = { restore = null }) { Text(txt("Cancel", "Vazgeç")) } })
    }
    if(historyOpen) AlertDialog(onDismissRequest = { historyOpen = false }, title = { Text(txt("Notification history", "Bildirim geçmişi")) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(txt("Sent means passed to Android, not confirmed shown. Duplicate events are suppressed.",
                "Gönderildi: Android'a iletildi; ekranda görüldüğü doğrulanmaz. Yinelenen olaylar engellenir."), color = Muted, fontSize = 12.sp)
            val entries = DeliveryJournal.read(context)
            if(entries.isEmpty()) Text(txt("No deliveries recorded yet.", "Henüz kayıt yok."), modifier = Modifier.padding(top = 16.dp))
            entries.forEach { entry ->
                val matchId = entry.key.takeIf { it.startsWith("result:") || it.startsWith("reminder:") }?.split(':')?.getOrNull(1)
                val match = matchId?.let { id -> WidgetModels.matches(context).firstOrNull { it.id == id } ?: EsportsRepository(context).cachedDetail(id)?.match }
                Column(Modifier.fillMaxWidth().clickable(enabled = match != null) { match?.let { context.startActivity(MatchNavigation.intent(context, it)) } }.padding(vertical = 12.dp)) {
                    Text(when(entry.state) { "sent" -> txt("Sent to Android", "Android'a gönderildi"); "duplicate" -> txt("Duplicate blocked", "Tekrar engellendi")
                        "settings" -> txt("Disabled in settings", "Ayarlardan kapalı"); "permission" -> txt("Permission missing", "İzin eksik");
                        "cancelled" -> txt("Source marked match cancelled", "Kaynak maçı iptal olarak işaretledi"); else -> txt("Could not send", "Gönderilemedi") }, fontSize = 14.sp)
                    Text(match?.let { "${it.team1} × ${it.team2}" } ?: entry.key, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(timeLabel(entry.at), color = Muted, fontSize = 11.sp)
                }
                HorizontalDivider(color = Raised)
            }
        } }, confirmButton = { TextButton(onClick = { historyOpen = false }) { Text(txt("Close", "Kapat")) } })
}

@Composable internal fun RaundQuickGuide(onDismiss: () -> Unit, onConnect: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(txt("Your daily Raund", "Günlük Raund’ın")) },
        text = { Column {
            Text(txt("01  Your store", "01  Kendi mağazan"), color = White, fontSize = 16.sp)
            Text(txt("Connect on Riot’s HTTPS page. No password is entered into a Raund form.",
                "Riot’un HTTPS sayfasından bağlan. Raund formuna parola girilmez."), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            Text(txt("02  Follow only what matters", "02  İlgilendiklerini takip et"), color = White, fontSize = 16.sp)
            Text(txt("Save skins, teams and matches. Store alerts are limited to around 03:00 and 09:00; match reminders arrive before kickoff.",
                "Skin, takım ve maç kaydet. Mağaza uyarıları yaklaşık 03.00 ve 09.00 ile sınırlıdır; maç hatırlatması başlangıçtan önce gelir."),
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            Text(txt("03  Put it on your home screen", "03  Ana ekranına taşı"), color = White, fontSize = 16.sp)
            Text(txt("Long-press your home screen → Widgets → Raund. Resize each widget and choose its content with the settings icon.",
                "Ana ekrana basılı tut → Widget’lar → Raund. Her widget’ı boyutlandır; ayar simgesinden içeriğini seç."),
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        } }, confirmButton = { TextButton(onClick = onConnect) { Text(txt("Connect Riot", "Riot’a bağlan")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(txt("Explore first", "Önce keşfet")) } })
}
