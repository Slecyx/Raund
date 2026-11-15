package app.nightwatch.store

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A short, skippable first-run introduction. It deliberately avoids account fields. */
@Composable
internal fun RaundOnboarding(onExplore: () -> Unit, onConnect: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val ink = Ink
    val accent = Accent
    val white = White
    val muted = Muted
    val raised = Raised
    val titles = listOf(
        txt("Your VALORANT day, in one place", "VALORANT günün, tek yerde"),
        txt("The store, without the noise", "Mağaza, gürültüsüz"),
        txt("Follow the moments that matter", "Önemli anları takip et")
    )
    val copy = listOf(
        txt("Raund brings your store, career and esports follows into a calm, fast home.",
            "Raund; mağazanı, kariyerini ve eSpor takiplerini hızlı, sakin bir ana ekranda toplar."),
        txt("See today’s offers and save only the skins you care about. No artificial tiles behind the artwork.",
            "Günün tekliflerini gör, yalnızca istediğin skinleri kaydet. Görsellerin arkasında yapay kutular yok."),
        txt("Keep favourite teams and matches close. Alerts are intentional, limited and always under your control.",
            "Favori takımların ve maçların yanında. Uyarılar ölçülü, sınırlı ve her zaman senin kontrolünde.")
    )
    Box(Modifier.fillMaxSize().background(ink)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(accent.copy(alpha = .14f), radius = size.minDimension * .62f, center = androidx.compose.ui.geometry.Offset(size.width * .9f, size.height * .08f))
            drawCircle(Color.White.copy(alpha = .025f), radius = size.minDimension * .75f, center = androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .76f))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RaundMark(Modifier.size(34.dp))
                Text("RAUND", color = white, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                    modifier = Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                if(page < 2) Text(txt("Skip", "Geç"), color = muted, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onExplore))
            }
            Spacer(Modifier.weight(.58f))
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { OnboardingIllustration(page) }
            Spacer(Modifier.height(42.dp))
            Text(titles[page], color = white, fontSize = 31.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
            Text(copy[page], color = muted, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 15.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(3) { index ->
                    Box(Modifier.height(4.dp).weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if(index == page) accent else raised))
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { if(page < 2) page++ else onConnect() }, modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = ink)) {
                Text(if(page < 2) txt("Continue", "Devam et") else txt("Connect Riot account", "Riot hesabını bağla"), fontWeight = FontWeight.Bold)
            }
            if(page == 2) Text(txt("Explore first", "Önce keşfet"), color = muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onExplore).padding(top = 17.dp, bottom = 4.dp))
        }
    }
}

@Composable private fun RaundMark(modifier: Modifier = Modifier) {
    val white = White
    val accent = Accent
    Canvas(modifier) {
        val stroke = size.minDimension * .13f
        drawCircle(white, radius = size.minDimension * .38f, style = Stroke(stroke))
        drawLine(white, androidx.compose.ui.geometry.Offset(size.width * .26f, size.height * .69f), androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .27f), stroke)
        drawCircle(accent, radius = size.minDimension * .10f, center = androidx.compose.ui.geometry.Offset(size.width * .74f, size.height * .25f))
    }
}

@Composable private fun OnboardingIllustration(page: Int) {
    val highlight = when(page) { 0 -> Accent; 1 -> White; else -> Accent }
    val panel = Panel
    val raised = Raised
    val white = White
    val accent = Accent
    Box(Modifier.size(204.dp).clip(RoundedCornerShape(42.dp)).background(Brush.linearGradient(listOf(panel, raised.copy(alpha = .72f))))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(highlight.copy(alpha = .16f), radius = size.minDimension * .49f, center = androidx.compose.ui.geometry.Offset(size.width * .78f, size.height * .17f))
            drawRoundRect(white.copy(alpha = .10f), topLeft = androidx.compose.ui.geometry.Offset(size.width*.16f, size.height*.21f), size = androidx.compose.ui.geometry.Size(size.width*.68f, size.height*.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(22f, 22f))
            drawRoundRect(white.copy(alpha = if(page == 1) .74f else .18f), topLeft = androidx.compose.ui.geometry.Offset(size.width*.16f, size.height*.41f), size = androidx.compose.ui.geometry.Size(size.width*.49f, size.height*.20f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f, 28f))
            drawRoundRect(highlight.copy(alpha = .75f), topLeft = androidx.compose.ui.geometry.Offset(size.width*.69f, size.height*.41f), size = androidx.compose.ui.geometry.Size(size.width*.15f, size.height*.20f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f, 28f))
            drawLine(white.copy(alpha = .28f), androidx.compose.ui.geometry.Offset(size.width*.23f, size.height*.77f), androidx.compose.ui.geometry.Offset(size.width*.77f, size.height*.77f), 4f)
            if(page == 2) {
                drawCircle(accent, radius = 10f, center = androidx.compose.ui.geometry.Offset(size.width*.28f, size.height*.77f))
                drawCircle(accent, radius = 10f, center = androidx.compose.ui.geometry.Offset(size.width*.58f, size.height*.77f))
            }
        }
    }
}
