package app.nightwatch.store

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun RadarPage(account: LiveStoreAccount?, catalog: List<StoreOffer>, wished: List<String>, onOpen: (StoreOffer) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE) }
    val repository = remember { RadarRepository(context) }
    val scope = rememberCoroutineScope()
    var history by remember(account?.name, account?.region) { mutableStateOf<List<StoreObservation>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var budgetText by rememberSaveable { mutableStateOf(prefs.getString("radar_budget_vp", "").orEmpty()) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmClear by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()/1000) }
    LaunchedEffect(Unit) { while(true) { now = System.currentTimeMillis()/1000; delay(30_000) } }
    LaunchedEffect(account) {
        historyLoading = true
        history = withContext(Dispatchers.IO) { account?.let { repository.read(it) }.orEmpty() }
        historyLoading = false
    }
    val available = RadarHistory.purchasableFavorites(account?.snapshot, wished.toSet(), now)
    val selected = available.filter { it.id in selectedIds }
    val total = selected.sumOf { it.priceVp.toLong() }
    val budget = budgetText.toLongOrNull()
    val favorites = (account?.snapshot?.offers.orEmpty() + catalog).distinctBy { it.id }.filter { it.id in wished }
    val tr = LocalTurkish.current
    fun date(seconds: Long): String = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM · HH:mm", if(tr) Locale.forLanguageTag("tr") else Locale.ENGLISH))
    DetailPage(txt("Favorite Radar","Favori Radarı"), onClose) {
        Header(txt("Your favorites.\nYour call.","Favorilerin.\nKontrol sende."), txt("Your store, recorded on this device.","Mağazan, yalnızca bu cihazda kayıtlı."))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(txt("Today","Bugün"), tab == 0) { tab = 0 }
            Chip(txt("Waiting","Beklediklerim"), tab == 1) { tab = 1 }
            Chip(txt("History","Geçmiş"), tab == 2) { tab = 2 }
        }
        when(tab) {
            0 -> {
                SectionTitle(txt("My VP plan","VP planım"))
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    OutlinedTextField(budgetText, { value ->
                        budgetText = value.filter { it in '0'..'9' }.take(7)
                        prefs.edit().putString("radar_budget_vp", budgetText).apply()
                    }, Modifier.fillMaxWidth(), label = { Text(txt("My limit · VP","Kendi limitim · VP")) },
                        singleLine = true, shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Stat("$total VP", txt("Selected total","Seçilenlerin toplamı"), Modifier.weight(1f))
                        Stat(budget?.let { "${kotlin.math.abs(it-total)} VP" } ?: "—",
                            if(budget != null && total > budget) txt("Over my limit","Limitin üzerinde") else txt("Remaining limit","Kalan limit"), Modifier.weight(1f))
                    }
                    Note(txt("A personal limit, not your wallet balance. Nothing is purchased here.","Bu senin belirlediğin limit; cüzdan bakiyen değil. Buradan satın alma yapılmaz."))
                }
                SectionTitle(txt("Favorites available now","Şu an satıştaki favoriler"), "${available.size}")
                if(available.isEmpty()) Note(if(account == null) txt("Connect your Riot account to check live offers.","Canlı teklifleri görmek için Riot hesabını bağla.")
                    else txt("No active, individually priced VP favorites found. Refresh the store if your data is old.","Aktif, tekil VP fiyatı olan favori bulunamadı. Veriler eskiyse mağazayı yenile."))
                available.forEach { offer ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(offer.id in selectedIds, { checked -> selectedIds = if(checked) (selectedIds + offer.id).distinct() else selectedIds - offer.id })
                        Column(Modifier.weight(1f).clickable { onOpen(offer) }.padding(start = 4.dp)) {
                            Text(offer.name, color = White, fontSize = 14.sp)
                            Text("${offer.priceVp} VP", color = Muted, fontSize = 12.sp)
                        }
                        if(budget != null) Text(if(offer.priceVp <= budget) txt("Fits","Limit içinde") else "+${offer.priceVp-budget} VP", color = Muted, fontSize = 11.sp)
                    }
                }
                Note(txt("Daily store and active Night Market only. Bundle-only prices are excluded. Offers may expire before your next refresh.","Yalnızca günlük mağaza ve aktif Gece Pazarı. Pakete özel fiyatlar dahil değil. Teklifler bir sonraki yenilemeden önce sona erebilir."))
            }
            1 -> {
                SectionTitle(txt("On your list","Listendekiler"), "${wished.size}")
                if(wished.isEmpty()) Note(txt("Save skins in Favorites to start tracking them.","Takibe başlamak için Favoriler’den skin ekle."))
                favorites.sortedBy { prefs.getLong("favorite_since-${it.id}", Long.MAX_VALUE) }.forEach { offer ->
                    val since = prefs.getLong("favorite_since-${offer.id}", 0)/1000
                    val observed = history.filter { entry -> entry.offers.any { it.id == offer.id } }
                    val wait = if(since == 0L) txt("Added before tracking","Takip öncesinde eklendi") else {
                        val days = ((now-since).coerceAtLeast(0)/86400)
                        if(days == 0L) txt("Saved today","Bugün eklendi") else txt("Saved $days days ago","$days gün önce eklendi")
                    }
                    RadarOffer(offer, wait) { item -> onOpen(available.firstOrNull { it.id == item.id } ?: item.copy(priceVp = -1, originalPrice = null)) }
                    val last = observed.lastOrNull()
                    Text(if(last == null) txt("Not seen in recorded daily stores","Kayıtlı günlük mağazalarda görülmedi")
                        else txt("Seen ${observed.size} times · ${date(last.observedAt)}","${observed.size} kez görüldü · ${date(last.observedAt)}"),
                        color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp, bottom = 14.dp))
                }
                if(favorites.size < wished.size) Note(txt("Some saved skin details are still loading.","Bazı favorilerin bilgileri henüz yüklenmedi."))
                Note(txt("Counts cover only the last 30 recorded rotations, including observations before you saved a skin. They do not predict when it will return. Limited and Battle Pass skins may never enter the daily store.",
                    "Sayılar, favoriye eklemeden öncekiler dahil son 30 kayıtlı mağazayı kapsar; geri gelme tahmini değildir. Sınırlı ve Savaş Bileti skinleri günlük mağazaya hiç gelmeyebilir."))
            }
            2 -> {
                SectionTitle(txt("Store memory","Mağaza hafızası"), "${history.size}/30")
                if(historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(history.isEmpty() && !historyLoading) Empty(txt("The first page is yours","İlk sayfa seni bekliyor"), txt("A successful live store refresh starts your history. Old rotations cannot be recovered.","İlk başarılı canlı mağaza yenilemesiyle kayıt başlar. Geçmiş günler geriye dönük alınamaz."))
                if(history.size >= 2) {
                    val change = RadarHistory.changes(history[history.lastIndex-1], history.last())
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(txt("Since the previous record","Önceki kayda göre"), color = White, fontWeight = FontWeight.SemiBold)
                        Note(txt("${change.arrived.size} new · ${change.left.size} no longer listed","${change.arrived.size} yeni · ${change.left.size} listeden çıktı"))
                        change.arrived.forEach { Text("+ ${it.name}", color = White, fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp)) }
                        change.left.forEach { Text("− ${it.name}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp)) }
                    }
                }
                history.asReversed().forEach { entry ->
                    SectionTitle(date(entry.observedAt), if(entry.expiresAt > now) txt("Active","Aktif") else txt("Past","Geçmiş"))
                    entry.offers.forEach { offer -> RadarOffer(offer, "${offer.priceVp} ${offer.currency}") { onOpen(it.copy(priceVp = -1, originalPrice = null)) } }
                }
                Note(txt("Only successfully checked daily stores are recorded; skipped days are unknown. The last 30 rotations stay encrypted on this device, separated by account and region. Renaming your Riot ID keeps your history.",
                    "Yalnızca başarıyla kontrol edilen günlük mağazalar kaydedilir; atlanan günler bilinmez. Son 30 kayıt cihazda şifreli, hesap ve bölgeye göre ayrı tutulur. Riot ID değişse de geçmişin korunur."))
                if(history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(txt("Clear this account's history","Bu hesabın geçmişini sil")) }
            }
        }
    }
    if(confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(txt("Clear store history?","Mağaza geçmişi silinsin mi?")) },
        text = { Text(txt("This cannot be undone. Favorites, budget and sign-in are kept.","Bu işlem geri alınamaz. Favoriler, bütçe ve oturum korunur.")) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            scope.launch { withContext(Dispatchers.IO) { account?.let { repository.clear(it) } }; history = emptyList() }
        }) { Text(txt("Clear history","Geçmişi sil")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(txt("Cancel","Vazgeç")) } })
}

@Composable private fun RadarOffer(offer: StoreOffer, subtitle: String, onOpen: (StoreOffer) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(offer) }.padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(offer.imageUrl, offer.name, Modifier.width(80.dp).height(46.dp), contentScale = ContentScale.Fit)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(offer.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
