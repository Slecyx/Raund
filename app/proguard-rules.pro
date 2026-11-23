# Keep Gson-serialized data models, Kotlin Pair, and enums intact under R8 shrinking/obfuscation.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

-keep class kotlin.Pair { *; }
-keep class app.nightwatch.store.RiotSession { *; }
-keep class app.nightwatch.store.MatchSummary { *; }
-keep class app.nightwatch.store.CareerSummary { *; }
-keep class app.nightwatch.store.ProgressSummary { *; }
-keep class app.nightwatch.store.OwnedInventory { *; }
-keep class app.nightwatch.store.PlayerCardArt { *; }
-keep class app.nightwatch.store.PassReward { *; }
-keep class app.nightwatch.store.MissionProgress { *; }
-keep class app.nightwatch.store.LiveStoreAccount { *; }
-keep class app.nightwatch.store.StoreOffer { *; }
-keep class app.nightwatch.store.StoreSnapshot { *; }
-keep class app.nightwatch.store.SkinVariant { *; }
-keep class app.nightwatch.store.StoreBundle { *; }
-keep class app.nightwatch.store.WeaponType { *; }
-keep class app.nightwatch.store.StoreObservation { *; }
-keep class app.nightwatch.store.EsportsMatch { *; }
-keep class app.nightwatch.store.EsportsDetail { *; }
-keep class app.nightwatch.store.EsportsMapResult { *; }
-keep class app.nightwatch.store.EsportsPlayerLine { *; }
-keep class app.nightwatch.store.EsportsFeed { *; }
-keep class app.nightwatch.store.EsportsEvent { *; }
-keep class app.nightwatch.store.EsportsNews { *; }
-keep class app.nightwatch.store.BracketEntry { *; }
-keep class app.nightwatch.store.TournamentDetail { *; }
-keep class app.nightwatch.store.EsportsPlayer { *; }
-keep class app.nightwatch.store.EsportsTeam { *; }
-keep class app.nightwatch.store.EsportsTeamSearchResult { *; }
-keep class app.nightwatch.store.EsportsPlayerSearchResult { *; }
-keep class app.nightwatch.store.PlayerAgent { *; }
-keep class app.nightwatch.store.PlayerSocial { *; }
-keep class app.nightwatch.store.PlayerAchievement { *; }
-keep class app.nightwatch.store.PlayerTeamTenure { *; }
-keep class app.nightwatch.store.PlayerMapPerformance { *; }
-keep class app.nightwatch.store.PlayerMatchPerformance { *; }
-keep class app.nightwatch.store.PlayerProfile { *; }
-keep class app.nightwatch.store.WatchLaterEntry { *; }
-keep class app.nightwatch.store.WidgetPreset { *; }
-keep class app.nightwatch.store.EvidenceSample { *; }
-keep class app.nightwatch.store.DevelopmentCheckpoint { *; }
-keep class app.nightwatch.store.SavedAccountSlot { *; }
-keep class app.nightwatch.store.LineupVideo { *; }

# Keep WorkManager workers instantiated by reflection.
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Strip verbose/debug logs in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
