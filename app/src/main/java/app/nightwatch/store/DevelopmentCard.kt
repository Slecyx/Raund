package app.nightwatch.store

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson

@Composable internal fun DevelopmentCard(matches: List<MatchSummary>, goals: List<CoachGoal>, onMatch: (MatchSummary) -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("nightwatch", 0)
    var checkpoint by remember { mutableStateOf(DevelopmentEvidence.decode(prefs.getString("development_checkpoint", null))) }
    SectionTitle(txt("Your next five matches", "Sonraki beş maçın"))
    val current = checkpoint
    if(current == null) {
        Text(txt("Choose one measurable focus. Compare the next five ranked matches with your current sample.",
            "Tek bir ölçülebilir odak seç. Sonraki beş dereceli maçını mevcut örneklemle karşılaştır."), color = Muted, fontSize = 12.sp)
        goals.forEach { goal -> TextButton(onClick = {
            val value = DevelopmentEvidence.start(goal, matches, System.currentTimeMillis())
            prefs.edit().putString("development_checkpoint", Gson().toJson(value)).apply(); checkpoint = value
        }) { Text(if(LocalTurkish.current) goal.titleTr else goal.titleEn) } }
        return
    }
    val newer = remember(current, matches) { DevelopmentEvidence.after(current, matches) }
    val sample = remember(current, newer) { DevelopmentEvidence.measure(current.key, newer, current.map) }
    Text(if(LocalTurkish.current) current.titleTr else current.titleEn, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text("${newer.size}/5 " + txt("new ranked matches", "yeni dereceli maç"), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
    LinearProgressIndicator(progress = { newer.size / 5f }, modifier = Modifier.fillMaxWidth(), color = Accent)
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Stat(current.baseline.value?.let { "$it%" } ?: "—", txt("Before", "Önce") + " · ${current.baseline.matches} " + txt("matches", "maç"), Modifier.weight(1f))
        Stat(sample.value?.let { "$it%" } ?: "—", txt("After", "Sonra") + " · ${sample.matches} " + txt("matches", "maç"), Modifier.weight(1f))
    }
    Text(when(current.key) {
        "opening" -> txt("First deaths / opening duels · lower is better", "İlk ölüm / açılış düelloları · düşük daha iyi")
        "fullbuy" -> txt("Full-buy round wins · higher is better", "Full-buy raunt kazanma · yüksek daha iyi")
        "map" -> txt("Recorded map win rate", "Haritada gözlenen kazanma oranı")
        else -> "KAST"
    } + " · ${sample.observations} " + txt("observations", "gözlem"), color = Muted, fontSize = 12.sp)
    Text(txt("Small samples are observations, not proof that a change caused improvement.",
        "Küçük örneklemler gözlemdir; bir değişikliğin gelişime neden olduğunu kanıtlamaz."), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
    newer.takeLast(3).forEach { match ->
        Text("${match.map} · ${match.score} · ${match.kills}/${match.deaths}/${match.assists}  ›", color = White, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().clickable { onMatch(match) }.padding(vertical = 10.dp))
    }
    TextButton(onClick = { prefs.edit().remove("development_checkpoint").apply(); checkpoint = null }) { Text(txt("Choose another focus", "Başka odak seç")) }
}
