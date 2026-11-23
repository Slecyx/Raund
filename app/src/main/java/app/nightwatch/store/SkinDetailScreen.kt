package app.nightwatch.store

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

@Composable internal fun SkinDetailPage(
    offer: StoreOffer,
    saved: Boolean,
    owned: Boolean,
    onWish: () -> Unit,
    onCompare: () -> Unit,
    onClose: () -> Unit
) {
    var variant by remember(offer.id) { mutableIntStateOf(-1) }
    var imageFailed by remember(offer.id, variant) { mutableStateOf(false) }
    var playingVideo by remember(offer.id) { mutableStateOf<String?>(null) }
    DetailPage(offer.name, onClose) {
        val image = offer.variants.getOrNull(variant)?.image ?: offer.imageUrl
        if(offer.collection == "playertitles" || image.isNullOrBlank() || imageFailed) {
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                Text(offer.name, color = White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            AsyncImage(image, offer.name, Modifier.fillMaxWidth().height(240.dp).padding(10.dp), contentScale = ContentScale.Fit,
                onError = { imageFailed = true })
        }
        playingVideo?.let { video ->
            EmbeddedSkinPreview(video, offer.name) { playingVideo = null }
        }
        Header(offerDisplayName(offer), offer.weaponLabel())
        if(offer.metadataPending) Note(txt("This item is preserved while its public name and artwork are pending. Refresh the store later; a missing image does not mean it is not owned.",
            "Bu öğe, herkese açık ad ve görseli beklenirken korunuyor. Mağazayı daha sonra yenile; görsel eksikliği öğeye sahip olmadığın anlamına gelmez."))
        if(owned) Text(txt("✓ Owned in your Riot inventory", "✓ Riot envanterinde sahipsin"), color = Green,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        else Price(offer)
        if(owned && offer.ownedLevelNames.orEmpty().isNotEmpty()) {
            SectionTitle(txt("Owned levels", "Sahip olduğun seviyeler"), offer.ownedLevelNames.orEmpty().size.toString())
            offer.ownedLevelNames.orEmpty().forEach { level ->
                Text("✓  $level", color = White, fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
            }
        }
        Button(onClick = onWish, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = if(saved) Raised else Accent)) { Text(if(saved) txt("♥ Saved · remove", "♥ Kaydedildi · kaldır") else txt("♡ Add to favorites", "♡ Favorilere ekle"), color = if(saved) White else MaterialTheme.colorScheme.onPrimary) }
        TextButton(onClick = onCompare, modifier = Modifier.fillMaxWidth()) { Text(txt("Compare with another skin", "Başka bir skinle karşılaştır")) }
        if(offer.variants.isNotEmpty()) {
            SectionTitle(txt("Variants & upgrades", "Renkler ve geliştirmeler"))
            if(owned) Note(txt("These are catalog previews; only the levels listed above are confirmed owned.",
                "Bunlar katalog önizlemeleridir; yalnızca yukarıda yazan seviyelerin sahipliği doğrulandı."))
            offer.variants.forEachIndexed { index, value ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(variant == index) Raised else Color.Transparent).clickable { variant = index }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(value.image ?: offer.imageUrl, value.name, Modifier.width(75.dp).height(48.dp), contentScale = ContentScale.Fit)
                    Text(value.name, color = White, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    value.video?.let { video -> TextButton(onClick = { playingVideo = video }) {
                        Text(if(playingVideo == video) txt("Playing", "Oynuyor") else txt("▶ Preview", "▶ Ön izle"), color = Accent)
                    } }
                }
            }
        }
        if(!owned && offer.priceVp < 0) Note(txt("Collection entry. Availability and price appear only when Riot returns an active offer. Limited and Battle Pass skins may not enter the daily store.", "Koleksiyon kaydı. Satış durumu ve fiyat, Riot aktif teklif döndürdüğünde görünür. Sınırlı ve Savaş Bileti skinleri günlük mağazaya gelmeyebilir."))
    }
}

@Composable private fun EmbeddedSkinPreview(url: String, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumePlayback by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { loading = state == Player.STATE_BUFFERING }
            override fun onPlayerError(cause: PlaybackException) { error = true; loading = false }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP) { resumePlayback = player.playWhenReady; player.pause() }
            if(event == Lifecycle.Event.ON_START && resumePlayback) { player.play(); resumePlayback = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(txt("In-app preview", "Uygulama içi ön izleme"), color = White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("×") }
        }
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = true } },
                update = { it.player = player }, modifier = Modifier.fillMaxSize())
            if(loading && !error) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            if(error) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(txt("Preview could not be played", "Ön izleme oynatılamadı"), color = Color.White, fontSize = 12.sp)
                TextButton(onClick = { error = false; loading = true; player.prepare(); player.play() }) { Text(txt("Retry", "Tekrar dene")) }
            }
        }
        Text(title, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable internal fun VerifiedRiotLogin(onSession: (RiotSession) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val tr = LocalTurkish.current
    val latestSession by rememberUpdatedState(onSession)
    val latestCancel by rememberUpdatedState(onCancel)
    var launched by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val access = result.data?.getStringExtra(RiotLoginActivity.ACCESS)
        val id = result.data?.getStringExtra(RiotLoginActivity.ID)
        if(result.resultCode == android.app.Activity.RESULT_OK && !access.isNullOrBlank() && !id.isNullOrBlank())
            latestSession(RiotSession(access, id))
        else latestCancel()
    }
    LaunchedEffect(Unit) {
        if(!launched) {
            launched = true
            launcher.launch(Intent(context, RiotLoginActivity::class.java).putExtra(RiotLoginActivity.LOCALE, if(tr) "tr-tr" else "en-us"))
        }
    }
}
