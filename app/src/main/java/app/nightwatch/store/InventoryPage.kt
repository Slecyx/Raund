package app.nightwatch.store

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable internal fun InventoryPage(account: LiveStoreAccount?, refreshing: Boolean, onRefresh: () -> Unit,
                                      onSkin: (StoreOffer, Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val turkish = LocalTurkish.current
    var localizedNames by remember(turkish) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(turkish) {
        if(turkish) localizedNames = withContext(Dispatchers.IO) {
            runCatching { ContentCatalog(context).localizedSkinNames("tr-TR") }.getOrDefault(emptyMap())
        }
    }
    val inventory = account?.inventory
    var skins by remember(inventory?.skinLevelIds) { mutableStateOf<List<StoreOffer>?>(null) }
    var metadataError by remember(inventory?.skinLevelIds) { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var weapon by remember { mutableStateOf<String?>(null) }
    var weaponMenu by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var showSeries by remember { mutableStateOf(false) }
    var seriesCatalog by remember { mutableStateOf<List<StoreOffer>?>(null) }
    var themeNames by remember(turkish) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var seriesError by remember { mutableStateOf(false) }
    var expandedSeries by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showSeries, turkish, retry) {
        if(!showSeries) return@LaunchedEffect
        seriesError = false
        runCatching { withContext(Dispatchers.IO) {
            val source = ContentCatalog(context)
            source.skins() to source.data("themes", if(turkish) "tr-TR" else "en-US").objects()
                .mapNotNull { row -> row.textOrNull("uuid")?.let { id -> row.textOrNull("displayName")?.let { id to it } } }.toMap()
        } }.onSuccess { (all, names) -> seriesCatalog = all; themeNames = names; seriesError = names.isEmpty() }
            .onFailure { seriesError = true }
    }
    val series = remember(seriesCatalog, skins, themeNames) { SkinSeriesProgress.group(seriesCatalog.orEmpty(), skins.orEmpty(), themeNames) }
    LaunchedEffect(Unit) {
        if(account != null && (inventory == null || inventory.schemaVersion < 1) && !refreshing) onRefresh()
    }
    LaunchedEffect(inventory?.skinLevelIds, inventory?.checkedAt, retry) {
        if(inventory == null) return@LaunchedEffect
        metadataError = false
        runCatching { withContext(Dispatchers.IO) { ContentCatalog(context).ownedSkins(inventory.skinLevelIds) } }
            .onSuccess { skins = it; metadataError = false }
            .onFailure { metadataError = true }
    }
    val filtered = skins.orEmpty().filter {
        (weapon == null || it.weaponLabel() == weapon) &&
            (normalized(it.name).contains(normalized(search)) || normalized(localizedNames[it.id].orEmpty()).contains(normalized(search)))
    }.map { offer -> localizedNames[offer.id]?.let { offer.copy(name = it) } ?: offer }
        .sortedWith(compareBy<StoreOffer> { it.weaponLabel() }.thenBy { it.name })
    Column(Modifier.fillMaxSize().background(Ink).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(txt("← Store","← Mağaza")) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !refreshing && account != null) {
                Text(if(refreshing) txt("Updating…","Yenileniyor…") else txt("Refresh","Yenile"))
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp)) {
            item {
                Text(txt("My inventory","Envanterim"), color = White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(account?.name.orEmpty(), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if(inventory != null && inventory.schemaVersion >= 1) {
                    Text((skins?.size?.toString() ?: "…") + " " + txt(if(skins?.size == 1) "owned weapon skin" else "owned weapon skins","sahip olduğun skin"),
                        color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 17.dp))
                    Text(txt("Checked ","Kontrol ") + timeLabel(inventory.checkedAt), color = Muted,
                        fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                }
                when {
                    account == null -> Note(txt("Connect your Riot account from Store to see owned skins.","Sahip olduğun skinleri görmek için Mağaza'dan Riot hesabını bağla."))
                    inventory == null && refreshing -> Note(txt("Loading your inventory…","Envanterin yükleniyor…"))
                    inventory == null -> Note(txt("Inventory could not be loaded. Tap Refresh to retry.","Envanter yüklenemedi. Yeniden denemek için Yenile'ye dokun."))
                    metadataError -> {
                        Note(txt("Skin names and images could not be loaded.","Skin adları ve görselleri yüklenemedi."))
                        TextButton(onClick = { retry++ }) { Text(txt("Try again", "Tekrar dene")) }
                    }
                    skins == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                    inventory.schemaVersion < 1 -> Note(txt("Updating your inventory. Retry with Refresh if needed.","Envanterin güncelleniyor. Gerekirse Yenile ile tekrar dene."))
                    inventory.skinLevelIds.isEmpty() -> Note(txt("No owned skins were found for this account.","Bu hesapta sahip olunan skin bulunamadı."))
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                            Chip(txt("Skins", "Skinler"), !showSeries) { showSeries = false }
                            Chip(txt("Series progress", "Seri ilerlemesi"), showSeries) { showSeries = true }
                        }
                        if(!showSeries) {
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                            shape = RoundedCornerShape(14.dp), placeholder = { Text(txt("Search your skins","Skinlerinde ara")) })
                        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                TextButton(onClick = { weaponMenu = true }) {
                                    Text((weapon
                                        ?: txt("All weapons","Tüm silahlar")) + "  ▾", color = Accent)
                                }
                                DropdownMenu(expanded = weaponMenu, onDismissRequest = { weaponMenu = false }) {
                                    DropdownMenuItem(text = { Text(txt("All weapons","Tüm silahlar")) }, onClick = { weapon = null; weaponMenu = false })
                                    skins.orEmpty().map { it.weaponLabel() }.distinct().sorted().forEach { type ->
                                        DropdownMenuItem(text = { Text(type) },
                                            onClick = { weapon = type; weaponMenu = false })
                                    }
                                }
                            }
                            Text("${filtered.size} " + txt("skins","skin"), color = Muted, fontSize = 11.sp)
                        }
                        }
                    }
                }
            }
            if(showSeries && inventory != null && skins != null) {
                item {
                    Note(txt("Known weapon skins in series you own. Counts exclude accessories and any items not yet in the public catalog.",
                        "Sahip olduğun serilerdeki bilinen silah skinleri. Aksesuarlar ve henüz kataloğa gelmeyen öğeler sayılmaz."))
                    if(skins.orEmpty().any { it.metadataPending }) Note(txt("Some owned items are still being identified; totals may be incomplete.",
                        "Bazı sahip olunan öğelerin bilgisi bekleniyor; sayılar eksik olabilir."))
                    if(seriesError) {
                        Note(txt("Series data could not be loaded.", "Seri bilgileri alınamadı."))
                        TextButton(onClick = { retry++ }) { Text(txt("Try again", "Tekrar dene")) }
                    } else if(seriesCatalog == null || themeNames.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                    else if(series.isEmpty()) Note(txt("No matching multi-skin series found in the catalog.", "Katalogda eşleşen çoklu skin serisi bulunamadı."))
                }
                items(series, key = { "series-${it.id}" }) { group ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { expandedSeries = if(expandedSeries == group.id) null else group.id },
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(group.name, color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text(if(group.missing.isEmpty()) txt("All known skins owned", "Bilinen skinlerin hepsine sahipsin")
                                    else "${group.missing.size} " + txt(if(group.missing.size == 1) "missing skin · Tap to explore" else "missing skins · Tap to explore", "eksik skin · İncelemek için dokun"),
                                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            Text("${group.owned.size}/${group.total}", color = Accent, fontSize = 17.sp)
                        }
                        LinearProgressIndicator(progress = { group.owned.size.toFloat() / group.total },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = Accent, trackColor = Raised)
                        if(expandedSeries == group.id) group.missing.forEach { raw ->
                            val skin = localizedNames[raw.id]?.let { raw.copy(name = it) } ?: raw
                            Row(Modifier.fillMaxWidth().clickable { onSkin(skin, false) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                GameArtwork(skin.imageUrl, skin.name, Modifier.width(90.dp).height(45.dp))
                                Text(skin.name, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                                Text("›", color = Muted)
                            }
                        }
                    }
                    HorizontalDivider(color = Raised.copy(alpha = .65f))
                }
            }
            if(!showSeries && inventory != null && inventory.schemaVersion >= 1 && skins != null && !metadataError) {
                var previous: String? = null
                filtered.forEach { offer ->
                    if(offer.weaponLabel() != previous) {
                        previous = offer.weaponLabel()
                        item(key = "section-${offer.weaponLabel()}") {
                            Text(offer.weaponLabel() + " · ${filtered.count { it.weaponLabel() == offer.weaponLabel() }}",
                                color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                modifier = Modifier.padding(top = 19.dp, bottom = 6.dp))
                        }
                    }
                    item(key = offer.id) {
                        Row(Modifier.fillMaxWidth().clickable { onSkin(offer, true) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            GameArtwork(offer.imageUrl, offer.name, Modifier.width(112.dp).height(72.dp).padding(end = 16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(offerDisplayName(offer), color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(txt("Owned · View details", "Sahipsin · Detayları gör"), color = Muted, fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp))
                                if(offer.metadataPending) Text(txt("Name and image pending · ownership preserved", "Ad ve görsel bekleniyor · sahiplik korunuyor"),
                                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            Text("›", color = Muted, fontSize = 20.sp)
                        }
                        HorizontalDivider(color = Raised.copy(alpha = .65f))
                    }
                }
                if(filtered.isEmpty() && skins.orEmpty().isNotEmpty()) item {
                    Note(txt("No skins match these filters.","Bu filtrelere uygun skin bulunamadı."))
                    TextButton(onClick = { search = ""; weapon = null }) { Text(txt("Clear filters", "Filtreleri temizle")) }
                }
            }
            item { Spacer(Modifier.height(26.dp)) }
        }
    }
}
