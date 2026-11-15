package app.nightwatch.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext

/**
 * Artwork endpoints occasionally lag behind new game content or remove an old asset.  A
 * broken image must never leave a blank hole in Store, Inventory or Career.
 */
@Composable
internal fun GameArtwork(url: String?, label: String, modifier: Modifier,
                         contentScale: ContentScale = ContentScale.Fit) {
    val context = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    val validUrl = url?.takeIf { it.startsWith("https://") }
    val request = remember(validUrl) {
        validUrl?.let { ImageRequest.Builder(context).data(it).size(560).allowHardware(false).crossfade(false).build() }
    }
    // Content images already contain their intended artwork/background.  Painting a
    // fixed surface behind every image made transparent PNGs look like grey tiles.
    Box(modifier, contentAlignment = Alignment.Center) {
        if(request != null && !failed) {
            AsyncImage(request, label, Modifier.fillMaxSize(), contentScale = contentScale,
                onError = { failed = true })
        }
        if(request == null || failed) {
            Box(Modifier.fillMaxSize().background(Raised.copy(alpha = .42f)), contentAlignment = Alignment.Center) {
                Text(label.trim().take(2).uppercase().ifBlank { "R" }, color = Color.White.copy(alpha = .72f),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
