package app.nightwatch.store

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

data class PlayerLoadoutSnapshot(
    val rawJson: String,
    val ownedSkinLevelIds: Set<String>,
    val ownedChromaIds: Set<String>,
    val region: String,
    val puuid: String
)

data class LookedUpPlayer(
    val puuid: String,
    val riotId: String,
    val region: String,
    val career: CareerSummary
)

internal data class InventoryValueSummary(
    val totalVp: Int,
    val pricedSkins: Int,
    val unpricedSkins: Int,
    val totalSkins: Int
)

internal object InventoryValueCalculator {
    /**
     * Computes total VP value strictly from known Riot store offer prices.
     * Battle Pass, Agent Contract, and non-store skins stay in unpricedSkins rather than being guessed.
     */
    fun calculate(ownedSkins: List<StoreOffer>, offerPricesByLevelId: Map<String, Int>): InventoryValueSummary {
        var total = 0
        var priced = 0
        ownedSkins.forEach { skin ->
            val price = offerPricesByLevelId[skin.id.lowercase(Locale.ROOT)]
            if(price != null && price > 0) {
                total += price
                priced++
            }
        }
        return InventoryValueSummary(total, priced, (ownedSkins.size - priced).coerceAtLeast(0), ownedSkins.size)
    }
}

internal data class LoadoutGunOption(
    val skinUuid: String,
    val skinName: String,
    val levelUuid: String,
    val chromaUuid: String,
    val chromaName: String,
    val imageUrl: String?
)

internal data class EquippedGunRow(
    val weaponUuid: String,
    val weaponName: String,
    val currentSkinName: String,
    val currentImage: String?,
    val currentSkinUuid: String,
    val currentChromaUuid: String,
    val ownedOptions: List<LoadoutGunOption>
)

internal object LoadoutBuilder {
    fun buildRows(loadoutJson: JSONObject, weaponsCatalog: List<JSONObject>, ownedLevels: Set<String>, ownedChromas: Set<String>): List<EquippedGunRow> {
        val weaponById = weaponsCatalog.associateBy { it.optString("uuid").lowercase(Locale.ROOT) }
        val guns = loadoutJson.optJSONArray("Guns").objects()
        return guns.mapNotNull { gun ->
            val weaponId = gun.optString("ID").lowercase(Locale.ROOT)
            val weapon = weaponById[weaponId] ?: return@mapNotNull null
            val weaponName = weapon.textOrNull("displayName") ?: return@mapNotNull null
            val equippedSkinId = gun.optString("SkinID").lowercase(Locale.ROOT)
            val equippedChromaId = gun.optString("ChromaID").lowercase(Locale.ROOT)
            val skins = weapon.optJSONArray("skins").objects()
            var currentName = weaponName
            var currentImage = weapon.textOrNull("displayIcon")
            val ownedOptions = mutableListOf<LoadoutGunOption>()
            skins.forEach { skin ->
                val skinId = skin.optString("uuid").lowercase(Locale.ROOT)
                val skinName = skin.textOrNull("displayName") ?: return@forEach
                val levels = skin.optJSONArray("levels").objects()
                val chromas = skin.optJSONArray("chromas").objects()
                val isStandard = skin.optString("themeUuid").equals("5a629df4-4765-0214-bd40-fbb96542941f", true) ||
                    skinName.startsWith("Standard ", true)
                val ownedSkinLevels = levels.filter { it.optString("uuid").lowercase(Locale.ROOT) in ownedLevels }
                val baseLevel = ownedSkinLevels.lastOrNull() ?: if(isStandard) levels.firstOrNull() else null
                val baseLevelId = baseLevel?.optString("uuid")?.lowercase(Locale.ROOT)
                if(skinId == equippedSkinId) {
                    currentName = skinName
                    val matchingChroma = chromas.firstOrNull { it.optString("uuid").lowercase(Locale.ROOT) == equippedChromaId }
                    currentImage = matchingChroma?.textOrNull("fullRender")
                        ?: matchingChroma?.textOrNull("displayIcon")
                        ?: skin.textOrNull("displayIcon")
                        ?: baseLevel?.textOrNull("displayIcon")
                        ?: currentImage
                }
                if(baseLevelId != null) {
                    chromas.forEachIndexed { index, chroma ->
                        val chromaId = chroma.optString("uuid").lowercase(Locale.ROOT)
                        if(index == 0 || chromaId in ownedChromas) {
                            val chromaImage = chroma.textOrNull("fullRender")
                                ?: chroma.textOrNull("displayIcon")
                                ?: skin.textOrNull("displayIcon")
                                ?: baseLevel.textOrNull("displayIcon")
                            ownedOptions += LoadoutGunOption(
                                skinUuid = skinId,
                                skinName = skinName,
                                levelUuid = baseLevelId,
                                chromaUuid = chromaId,
                                chromaName = chroma.textOrNull("displayName") ?: skinName,
                                imageUrl = chromaImage
                            )
                        }
                    }
                }
            }
            EquippedGunRow(weaponId, weaponName, currentName, currentImage, equippedSkinId, equippedChromaId, ownedOptions.distinctBy { it.chromaUuid })
        }.sortedBy { it.weaponName }
    }

    fun applySelection(loadoutJson: JSONObject, weaponUuid: String, option: LoadoutGunOption): JSONObject {
        val copy = JSONObject(loadoutJson.toString())
        val guns = copy.optJSONArray("Guns") ?: return copy
        for(i in 0 until guns.length()) {
            val gun = guns.optJSONObject(i) ?: continue
            if(gun.optString("ID").equals(weaponUuid, ignoreCase = true)) {
                gun.put("SkinID", option.skinUuid)
                gun.put("SkinLevelID", option.levelUuid)
                gun.put("ChromaID", option.chromaUuid)
                break
            }
        }
        return copy
    }
}

@Composable internal fun LoadoutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tr = LocalTurkish.current
    var snapshot by remember { mutableStateOf<PlayerLoadoutSnapshot?>(null) }
    var rows by remember { mutableStateOf<List<EquippedGunRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingWeapon by remember { mutableStateOf<EquippedGunRow?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey, tr) {
        loading = true; error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val session = SessionStore(context).validSession()
                val loaded = RiotLiveGateway(context).loadPlayerLoadout(session)
                val weapons = ContentCatalog(context).data("weapons", if(tr) "tr-TR" else "en-US").objects()
                loaded to LoadoutBuilder.buildRows(JSONObject(loaded.rawJson), weapons, loaded.ownedSkinLevelIds, loaded.ownedChromaIds)
            }
        }.onSuccess { (loaded, built) ->
            snapshot = loaded; rows = built
        }.onFailure {
            error = if(tr) "Loadout yüklenemedi. Oturumunu yenileyip tekrar dene." else "Could not load loadout. Refresh your session and retry."
        }
        loading = false
    }

    val appliedMsg = txt("Loadout updated on Riot servers. Changes apply on next menu/match sync.", "Loadout Riot sunucularında güncellendi. Değişiklik sonraki menü/maç eşitlemesinde görünür.")
    val saveFailMsg = txt("Could not save loadout change.", "Loadout değişikliği kaydedilemedi.")

    DetailPage(txt("Loadout", "Ekipman (Loadout)"), onBack) {
        Header(txt("Equipped skins", "Takılı skinler"), txt("Tap any weapon to swap between your owned skins and variants.", "Sahip olduğun skin ve renkler arasında geçiş yapmak için silaha dokun."),
            txt("Refresh", "Yenile")) { reloadKey++ }
        Note(txt("Writes directly to Riot's unofficial loadout service. Only skins/variants you already own can be equipped.",
            "Riot'un belgelenmemiş loadout servisine doğrudan yazar. Yalnızca zaten sahip olduğun skin ve renkler takılabilir."))
        if(loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Accent)
        statusMessage?.let { Text(it, color = Green, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        error?.let { Note(it) }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !saving && row.ownedOptions.isNotEmpty()) { pickingWeapon = row }.padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(row.currentImage, row.currentSkinName, Modifier.width(104.dp).height(52.dp), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(row.weaponName, color = Muted, fontSize = 11.sp)
                    Text(row.currentSkinName, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${row.ownedOptions.size} " + txt("owned options", "sahip olunan seçenek"), color = Muted, fontSize = 10.sp)
                }
                Text("›", color = Accent, fontSize = 20.sp)
            }
            HorizontalDivider(color = Raised.copy(alpha = .55f))
        }
    }

    pickingWeapon?.let { weapon ->
        AlertDialog(
            onDismissRequest = { pickingWeapon = null },
            title = { Text("${weapon.weaponName} · " + txt("Choose skin", "Skin seç")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(weapon.ownedOptions, key = { it.chromaUuid }) { option ->
                        val equipped = option.chromaUuid == weapon.currentChromaUuid
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if(equipped) Raised else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(enabled = !saving) {
                                val currentSnap = snapshot ?: return@clickable
                                pickingWeapon = null
                                saving = true; statusMessage = null; error = null
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            val updated = LoadoutBuilder.applySelection(JSONObject(currentSnap.rawJson), weapon.weaponUuid, option)
                                            val session = SessionStore(context).validSession()
                                            val response = RiotLiveGateway(context).updatePlayerLoadout(session, updated.toString())
                                            val weapons = ContentCatalog(context).data("weapons", if(tr) "tr-TR" else "en-US").objects()
                                            val nextSnap = currentSnap.copy(rawJson = response.toString())
                                            nextSnap to LoadoutBuilder.buildRows(response, weapons, nextSnap.ownedSkinLevelIds, nextSnap.ownedChromaIds)
                                        }
                                    }.onSuccess { (nextSnap, rebuilt) ->
                                        snapshot = nextSnap; rows = rebuilt; statusMessage = appliedMsg
                                    }.onFailure { error = saveFailMsg }
                                    saving = false
                                }
                            }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(option.imageUrl, option.chromaName, Modifier.width(72.dp).height(40.dp), contentScale = ContentScale.Fit)
                            Text(option.chromaName, color = if(equipped) Accent else White, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            if(equipped) Text("✓", color = Accent, fontSize = 14.sp)
                        }
                        HorizontalDivider(color = Raised.copy(alpha = .4f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingWeapon = null }) { Text(txt("Close", "Kapat")) } }
        )
    }
}
