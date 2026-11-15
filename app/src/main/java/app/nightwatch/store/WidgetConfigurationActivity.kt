package app.nightwatch.store

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if(id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val prefs = getSharedPreferences("raund_widgets", 0)
        val manager = AppWidgetManager.getInstance(this)
        val defaultMode = RaundWidgetProvider.defaultMode(manager.getAppWidgetInfo(id)?.provider?.className)
        val tr = getSharedPreferences("nightwatch", 0).getString("language", Locale.getDefault().language.uppercase()) == "TR"
        val matches = WidgetModels.matches(this).filter { it.status != "result" }.sortedBy { it.startAtMillis ?: 0L }.take(40)
        val favoriteTeams = getSharedPreferences("nightwatch", 0).getStringSet("esports_teams", emptySet()).orEmpty().sorted()
        val imported = WidgetPresets.imported(this)
        setContent {
            NightwatchTheme(getSharedPreferences("nightwatch", 0).getString("theme", "system") ?: "system") {
                var mode by remember { mutableStateOf(prefs.getString("mode_$id", defaultMode) ?: defaultMode) }
                var all by remember { mutableStateOf(prefs.getBoolean("all_$id", false)) }
                var match by remember { mutableStateOf(prefs.getString("match_$id", null)) }
                var matchScope by remember { mutableStateOf(prefs.getString("match_scope_$id", if(all) "all" else "follows") ?: "follows") }
                var chosenTeam by remember { mutableStateOf(prefs.getString("team_$id", null)) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                        Text(if(tr) "Ana ekran widget'ı" else "Home screen widget", fontSize = 25.sp)
                        Text(if(tr) "Her widget için ayrı bir görünüm seç. Boyutunu ana ekranda kenarlarından değiştirebilirsin."
                            else "Choose a view for each widget. Resize it from its edges on your home screen.",
                            color = Muted, modifier = Modifier.padding(top = 12.dp, bottom = 20.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            if(imported.isNotEmpty()) {
                                item { Text(if(tr) "Yedekten gelen widget seçimleri" else "Imported widget presets", fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                                items(imported) { preset ->
                                    WidgetChoice(preset.mode + (preset.match?.let { " · #$it" } ?: ""), mode == preset.mode && all == preset.all && match == preset.match) {
                                        mode = preset.mode; all = preset.all; match = preset.match
                                    }
                                }
                                item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
                            }
                            items(listOf("auto" to if(tr) "Akıllı özet" else "Smart summary", "store" to if(tr) "Günlük mağazam" else "Daily store",
                                "night" to if(tr) "Gece Pazarı" else "Night Market", "matches" to if(tr) "Maçlar ve canlı skor" else "Matches and live scores",
                                "career" to if(tr) "Rank ve kariyer" else "Rank and career")) { (value, label) ->
                                WidgetChoice(label, mode == value) { mode = value }
                            }
                            item {
                                Text(if(mode == "night") if(tr) "Altı teklif, büyütülmüş widget'ta iki sütunda görünür. Küçük görünüm yalnızca özet sunar."
                                    else "All six offers appear in two columns when expanded. The small view shows a summary."
                                    else if(tr) "Yenile simgesi hemen kontrol eder. Otomatik kontrolleri Android pil ve bağlantı durumuna göre geciktirebilir."
                                    else "Refresh checks now. Android may delay automatic checks based on battery and connectivity.",
                                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                            }
                            if(mode == "matches") {
                                item { HorizontalDivider(Modifier.padding(vertical = 15.dp)); Text(if(tr) "Gösterilecek maçlar" else "Matches to show", fontSize = 17.sp) }
                                item { WidgetChoice(if(tr) "Takip ettiğim maç, takım ve turnuvalar" else "My followed matches, teams and tournaments", matchScope == "follows" && match == null) { matchScope = "follows"; all = false; match = null; chosenTeam = null } }
                                item { WidgetChoice(if(tr) "Yalnızca favori takımlarımın maçları" else "Only my favourite teams’ matches", matchScope == "teams" && match == null) { matchScope = "teams"; all = false; match = null; chosenTeam = null } }
                                if(favoriteTeams.isNotEmpty()) {
                                    item { Text(if(tr) "Tek favori takım" else "One favourite team", fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 3.dp)) }
                                    items(favoriteTeams) { team -> WidgetChoice(team, matchScope == "team" && chosenTeam == team) { matchScope = "team"; chosenTeam = team; all = false; match = null } }
                                }
                                item { WidgetChoice(if(tr) "Tüm canlı ve yaklaşan maçlar" else "All live and upcoming matches", matchScope == "all" && match == null) { matchScope = "all"; all = true; match = null; chosenTeam = null } }
                                items(matches) { item ->
                                    WidgetChoice("${item.team1} × ${item.team2}\n${item.event}", match == item.id) { match = item.id; matchScope = "single"; all = false; chosenTeam = null }
                                }
                            }
                        }
                        Button(onClick = {
                            prefs.edit().putString("mode_$id", mode).putBoolean("all_$id", all).putString("match_scope_$id", matchScope).apply {
                                if(chosenTeam == null) remove("team_$id") else putString("team_$id", chosenTeam)
                                if(match == null) { remove("match_$id"); remove("match_data_$id") }
                                else { putString("match_$id", match); matches.find { it.id == match }?.let { putString("match_data_$id", com.google.gson.Gson().toJson(it)) } }
                            }.commit()
                            WidgetRenderer.render(this@WidgetConfigurationActivity, manager, id, mode)
                            WidgetRefreshScheduler.schedule(this@WidgetConfigurationActivity)
                            WidgetRefreshScheduler.refresh(this@WidgetConfigurationActivity)
                            WidgetMatchFollows.sync(this@WidgetConfigurationActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(if(tr) "Kaydet" else "Save") }
                    }
                }
            }
        }
    }
}

@Composable private fun WidgetChoice(label: String, selected: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = action)
        Text(label, Modifier.padding(start = 8.dp), fontSize = 15.sp)
    }
}
