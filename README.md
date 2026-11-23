<div align="center">

# Raund

**Privacy-first, serverless Android companion for VALORANT**  
*Native Kotlin · Jetpack Compose · Android 8+ (API 26–36) · Turkish & English*

[![Android CI](https://img.shields.io/badge/Android-SDK%2036-3DDC84?logo=android&logoColor=white)](.github/workflows/android.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Download APK](https://img.shields.io/badge/Download-APK%20(Releases)-FF4655?logo=android&logoColor=white)](https://github.com/Slecyx/Raund/releases/latest)

</div>

---

> **Research & independent build — not affiliated with Riot Games.**  
> Raund is an independent open-source companion. Do not describe it as Riot-approved, ban-free, or production-ready for public store distribution without completing the [Public-release gate](#public-release-gate).

---

## Highlights

- **Zero backend, zero ads, zero tracking:** All requests go directly from your device to Riot, `valorant-api.com`, `vlr.gg`, and privacy-enhanced YouTube embeds (`youtube-nocookie.com`).
- **On-device encryption:** Riot access/ID tokens, multi-account slots (up to 5 accounts), account cache, and store history are encrypted with **Android Keystore AES-GCM**.
- **Built for daily speed:** Pull-to-refresh across core tabs, 4 resizable home-screen widgets, Android 16 Live Updates support, Dark/Light/System themes, and full **Turkish / English** localization (including Android 13+ per-app `localeConfig`).

---

## Feature Overview

| Area | What Raund Includes |
| --- | --- |
| **Store & Multi-Account** | Daily 4-skin rotation, featured bundles & contents, active Night Market with discount %, Kingdom Credit accessories, VP/RP/KC wallet balances, one-tap **multi-account switcher** (up to 5 encrypted accounts), and quick text sharing. |
| **Inventory & Mobile Loadout** | Owned weapon skins by weapon with exact owned upgrade levels, series completion tracker, **store-priced Inventory VP value calculator**, **Radianite (RP) upgrade calculator**, and **in-app Loadout switcher** (swap owned skins & chromas). |
| **Favorites & Radar** | Full searchable skin catalog with chromas & in-app ExoPlayer video previews, hide-owned/video/sort filters, VP budget planner, **30-rotation encrypted store history** with summary stats, and twice-daily WorkManager alerts (`03:00` / `09:00`). |
| **Career & Match Analysis** | Current rank & RR, season/act selector, K/D, win rate, KAST, ADR, opening duels, RR curve, Battle Pass & missions, round-by-round kill/economy/spike replay with minimap heatmaps, duo/player history, and **tactical map callouts**. |
| **Game Hub & Player Lookup** | Live **Riot server status** by region (`EU/NA/AP/KR/BR/LATAM`), official **PlayValorant patch notes**, **in-app YouTube lineup video player** (map/agent/side/site filters + saved clips), distance-based **weapon damage table**, **agent ability guide**, **pro crosshair library** (live Canvas preview + 1-tap copy), and **friend/player rank lookup**. |
| **Esports (VLR.gg)** | Live, upcoming & completed matches with **VCT / Tier-1** & region filters (`EMEA`, `Americas`, `Pacific`, `CN`, `Masters/Champions`), instant match search, **5v5 map agent compositions**, tournament brackets & standings, team & player profiles, in-app news reader, local Pick'em with accuracy stats, calendar export, spoiler-free mode, and Android 16 Live Updates / 15-min reminders. |
| **Home-Screen Widgets** | 4 dedicated resizable widgets (`Smart Summary`, `Daily Store / Night Market`, `Matches & Live Score`, `Rank & Career`) with per-widget configuration and battery-saver scheduling. |

---

## Security & Privacy Architecture

Raund is engineered so credentials never pass through any third-party server:

- **Native Riot WebView Login ([`RiotLoginActivity.kt`](app/src/main/java/app/nightwatch/store/RiotLoginActivity.kt)):** Non-exported activity with `FLAG_SECURE` (blocks screenshots/screen recording), disabled third-party cookies, disabled cleartext/mixed content, SSL error hard-cancel, visible HTTPS host bar, and strict OAuth `state` + redirect host/path verification.
- **Keystore AES-GCM Storage ([`SessionStore.kt`](app/src/main/java/app/nightwatch/store/SessionStore.kt)):** Access/ID tokens, multi-account slots, cached account snapshots, and Radar store history are encrypted at rest; Android cloud backup and device-transfer backups are disabled (`allowBackup="false"`, `backup_rules.xml`, `data_extraction_rules.xml`).
- **Hardened Networking ([`RiotLiveGateway.kt`](app/src/main/java/app/nightwatch/store/RiotLiveGateway.kt), [`ContentCatalog.kt`](app/src/main/java/app/nightwatch/store/ContentCatalog.kt)):** Cleartext HTTP is blocked globally via `network_security_config.xml`. Authenticated Riot calls validate strict UUIDs and shard allow-lists, disable automatic redirects, and bound response sizes to prevent OOM.
- **Hardened Imports & External Intents ([`CompanionPreferences.kt`](app/src/main/java/app/nightwatch/store/CompanionPreferences.kt), [`CompanionPolicy.kt`](app/src/main/java/app/nightwatch/store/CompanionPolicy.kt)):** Full local JSON backups exclude all tokens/cookies, sanitize all URLs to HTTPS, and guard against Gson null-injection. External links use `openHttpsUrl` (`https` + `CATEGORY_BROWSABLE`).
- **Release Hardening ([`app/build.gradle.kts`](app/build.gradle.kts), [`app/proguard-rules.pro`](app/proguard-rules.pro)):** R8 code shrinking, resource shrinking, `Log.v`/`Log.d` stripping, APK Signature Scheme v2/v3/v4 (v1 disabled for `minSdk 26`), and CI-friendly signing via `RAUND_STORE_*` environment variables.

See [`SECURITY.md`](SECURITY.md) and [`PRIVACY.md`](PRIVACY.md) for the full security checklist and privacy notice.

---

## Project Structure

```text
app/src/main/java/app/nightwatch/store/
├── MainActivity.kt               # Navigation host, Store, Career, Favorites & shared UI primitives
├── SettingsScreen.kt             # Settings UI, multi-account switcher, notification profiles
├── SkinDetailScreen.kt           # Skin detail, chromas, ExoPlayer preview & Riot login launcher
├── SessionStore.kt               # Android Keystore AES-GCM session & multi-account storage
├── RiotLoginActivity.kt          # Hardened non-exported WebView for Riot HTTPS sign-in
├── RiotLiveGateway.kt            # Riot store, career, inventory, loadout & player lookup gateway
├── LoadoutAndValue.kt            # Mobile loadout editor & store-priced inventory VP calculator
├── GameGuideAndStatus.kt         # Server status, patch notes, weapons, agents & player lookup hub
├── LineupGuide.kt                # In-app YouTube lineup video discovery & youtube-nocookie player
├── CompanionTools.kt             # Crosshair library, map callouts, RP calc, VCT filter, cache tools
├── InventoryPage.kt              # Owned skins, series progress, RP calculator & loadout entry
├── RadarPage.kt / RadarHistory.kt# VP budget planner & 30-rotation encrypted store history
├── MatchDetails.kt               # Match metrics, scoreboard, round timeline & minimap analysis
├── EsportsPage.kt                # Esports hub, VCT/region filters, 5v5 comps, match detail
├── EsportsRepository.kt          # VLR.gg feed/detail parser & bounded local cache
├── EsportsExploreRepository.kt   # Tournaments, teams, players & news parser
├── EsportsScoreNotifications.kt  # Live score services, WorkManager checks & Live Updates
├── WidgetRenderer.kt / Models    # 4 resizable Android home-screen widgets
└── CompanionPreferences.kt       # Allow-listed JSON backup/restore (v1 & v2 archives)
```

---

## Building & Testing

**Requirements:** JDK 21 and Android SDK 36.

```bash
# Run unit tests, release lint, and build R8-minified release APK
./gradlew testDebugUnitTest lintRelease assembleRelease

# Build debug APK
./gradlew assembleDebug

# Build instrumentation tests
./gradlew assembleDebugAndroidTest
```

On Windows PowerShell (if your default JDK is not 21, point `JAVA_HOME` to JDK 21):

```powershell
.\gradlew.bat testDebugUnitTest lintRelease assembleRelease
```

### Release Signing (Optional)

Without signing secrets (such as pull-request CI), `assembleRelease` produces an unsigned release APK. To sign locally, create an untracked `keystore.properties` in the project root (or set `RAUND_STORE_FILE`, `RAUND_STORE_PASSWORD`, `RAUND_KEY_ALIAS`, `RAUND_KEY_PASSWORD` in CI):

```properties
storeFile=.signing/raund-release.jks
storePassword=your-store-password
keyAlias=raund-release
keyPassword=your-key-password
```

---

## Public-Release Gate

Before distributing a public Play Store or GitHub binary release, complete all items below (and see [`SECURITY.md`](SECURITY.md)):

1. Register with Riot Games and migrate to an approved production RSO client & redirect flow.
2. Replace the VLR.gg HTML parser with a licensed esports data feed or obtain written permission.
3. Publish a hosted privacy policy URL and real support contact.
4. Verify signed release APKs on both Android 8 and current Android devices.

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see the [`LICENSE`](LICENSE) file for details.

*VALORANT and all related assets belong to Riot Games, Inc. Raund is an independent, unofficial open-source project.*
