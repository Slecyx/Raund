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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable internal fun SkinComparePage(base: StoreOffer, catalog: List<StoreOffer>, favorites: Set<String>, owned: Set<String>, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var search by remember { mutableStateOf("") }
    var other by remember(base.id) { mutableStateOf<StoreOffer?>(null) }
    val candidates = remember(catalog, search, base.id) { catalog.distinctBy { it.id }.filter {
        it.id != base.id && it.skinOffer() && (search.isBlank() || normalized(it.name).contains(normalized(search)))
    }.take(80) }
    LazyColumn(Modifier.fillMaxSize().background(Ink), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text(txt("← Skin", "← Skin")) }
                Text(txt("Compare skins", "Skin karşılaştır"), color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if(other == null) {
                Header(txt("Choose the second skin", "İkinci skini seç"), base.name)
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(15.dp), placeholder = { Text(txt("Search skins", "Skin ara")) })
            } else CompareHero(base, other!!, favorites, owned) { other = null }
        }
        if(other == null) items(candidates, key = { it.id }) { offer ->
            Row(Modifier.fillMaxWidth().clickable { other = offer }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(offer.imageUrl, offer.name, Modifier.width(120.dp).height(66.dp), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(offer.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(offer.weaponLabel(), color = Muted, fontSize = 10.sp)
                }
                Text("›", color = Muted, fontSize = 20.sp)
            }
            HorizontalDivider(color = Raised)
        }
        item { Spacer(Modifier.navigationBarsPadding().height(20.dp)) }
    }
}

@Composable private fun CompareHero(a: StoreOffer, b: StoreOffer, favorites: Set<String>, owned: Set<String>, change: () -> Unit) {
    Header(txt("Side by side", "Yan yana"), txt("Catalog facts only", "Yalnızca katalog bilgileri"), txt("Change", "Değiştir"), change)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        CompareSkin(a, a.id in favorites, a.id in owned || a.ownedLevelNames.orEmpty().isNotEmpty(), Modifier.weight(1f))
        CompareSkin(b, b.id in favorites, b.id in owned || b.ownedLevelNames.orEmpty().isNotEmpty(), Modifier.weight(1f))
    }
    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Raised)
    CompareLine(txt("Weapon", "Silah"), a.weaponLabel(), b.weaponLabel())
    CompareLine(txt("Variants", "Renkler"), a.variants.size.toString(), b.variants.size.toString())
    CompareLine(txt("Video upgrades", "Videolu geliştirme"), a.variants.count { it.video != null }.toString(), b.variants.count { it.video != null }.toString())
    CompareLine(txt("Price now", "Şu anki fiyat"), if(a.priceVp >= 0) "${a.priceVp} ${a.currency}" else "—", if(b.priceVp >= 0) "${b.priceVp} ${b.currency}" else "—")
    Note(txt("Prices are shown only when Riot returns an active offer. Variant count is not a quality score.",
        "Fiyat yalnızca Riot aktif teklif döndürdüğünde gösterilir. Renk sayısı bir kalite puanı değildir."))
}

@Composable private fun CompareSkin(offer: StoreOffer, favorite: Boolean, owned: Boolean, modifier: Modifier) {
    Column(modifier) {
        AsyncImage(offer.imageUrl, offer.name, Modifier.fillMaxWidth().height(115.dp), contentScale = ContentScale.Fit)
        Text(offer.name, color = White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 3)
        Text(listOfNotNull(if(owned) txt("Owned", "Sahipsin") else null, if(favorite) txt("Favorite", "Favori") else null).joinToString(" · ").ifBlank { "—" },
            color = if(owned) Green else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun CompareLine(label: String, left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.width(105.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(right, color = White, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
