package app.nightwatch.store

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun WatchLaterRow(entry: WatchLaterEntry, onOpen: () -> Unit, onWatched: () -> Unit, onRemove: () -> Unit) {
    val match = entry.match
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(match.event, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(match.team1 to match.team1Logo, match.team2 to match.team2Logo).forEachIndexed { index, (name, logo) ->
                if(index == 1) Text("×", color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    TeamLogo(name, logo, 40.dp)
                    Text(name, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                        textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onWatched) { Text(if(entry.watched) txt("Mark unwatched", "İzlenmedi olarak işaretle") else txt("Mark watched", "İzlendi olarak işaretle")) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text(txt("Remove", "Kaldır"), color = Muted) }
        }
        HorizontalDivider(color = Raised.copy(alpha = .6f))
    }
}
