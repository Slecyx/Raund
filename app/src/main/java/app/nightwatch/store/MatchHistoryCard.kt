package app.nightwatch.store

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable internal fun MatchHistoryCard(match: MatchSummary, agentImage: String?, mapImage: String?, onClick: () -> Unit) {
    val outcome = if(match.won == true) Color(0xFF9CE3BC) else if(match.won == false) Color(0xFFFF9AAA) else Color(0xFFD7DAE2)
    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 146.dp)
        .clip(RoundedCornerShape(20.dp)).background(Color(0xFF1E2630)).clickable(onClick = onClick)) {
        AsyncImage(mapImage, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xFF0C1118).copy(.90f), Color(0xFF0C1118).copy(.62f)))))
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(match.mode, color = Color.White.copy(.8f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if(match.won == true) txt("WIN","GALİBİYET") else if(match.won == false) txt("LOSS","MAĞLUBİYET") else txt("RESULT","SONUÇ"),
                    color = outcome, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(match.map, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text(timeLabel(match.startedAt), color = Color.White.copy(.74f), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Text(match.score, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(agentImage, null, Modifier.size(24.dp))
                Text(if(match.detailsJson != null) "${match.kills} / ${match.deaths} / ${match.assists}" else "—",
                    color = Color.White.copy(.86f), fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                match.rrChange?.takeIf { it != 0 }?.let {
                    Text((if(it > 0) "+" else "") + "$it RR", color = if(it > 0) Color(0xFF9CE3BC) else Color(0xFFFF9AAA), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
