# Raund · Android companion

Version 0.42.1 / build 57. Native Kotlin / Jetpack Compose, with a native WebView login activity. Android 8 or newer. The Android package remains `app.nightwatch.store` so the update retains existing account data.

> **Publication status — research build, not an official Riot product.** Do not describe Raund as Riot-approved, ban-free, secure in comparison with another product, or production-ready. Its account/store and eSports sources need the approvals described in [Public-release gate](#public-release-gate) before a public launch.

## Features

- Live daily store, featured bundles with contents/prices, active Night Market, Kingdom Credit accessories and VP/RP/KC balances.
- Encrypted session persistence and last-account cache. Daily offers render before career/progression. Public metadata, images and match details are cached.
- All current weapon types, including Bandit and melee; searchable skin collection, persistent favorites, available chromas/upgrades and preview-video links.
- Optional favorite notifications using Android WorkManager, roughly hourly with internet. The alert title names the skin that appeared. Daily, Night Market and bundle matches are deduplicated within each rotation.
- Career and progression share one page: compact Battle Pass/account summary, expandable missions/rewards, match history, queue/map/result filters, K/D, win rate and agent/map summaries. Match cards use their actual map artwork with readable score and result overlays.
- Match details: ACS, ADR, headshot rate, agent-based team scoreboard, readable won/lost round summaries with personal K/D/damage, focused personal-event timeline, economy and spike events. Event time uses the response's `roundTime` field; missing times are not shown as `00:00`. No historic player IDs or pre-match opponent scouting.
- Active Battle Pass matched by season, exact tier XP requirement, reward track, account AP and available missions. Reached reward tiers do not imply premium reward ownership.
- Favorite Radar: user-defined VP limit and selectable active-favorite plan, time since favoriting, and daily-store changes across the last 30 observed rotations. Daily and active Night Market individual prices only; bundle-only items are excluded from the budget plan.
- Encrypted, account-separated store history from successful foreground/background checks. No retroactive records, return-date predictions or fabricated availability probabilities. Earlier favorites show an unknown start date; new favorites track their actual save date. History can be cleared per account.
- Ratings and all skin/match notes have been removed at the user's request. A one-time migration deletes only the old local notes preferences, retaining sign-in, favorites and settings.
- Turkish/English UI, initially selected from the device language. Game asset names come from the metadata provider.
- System/light/dark appearance in Settings, persisted across launches. Both palettes use semantic surface/text roles; photo overlays retain readable white text. Riot login keeps its original light appearance.
- Five navigation destinations: Store, Career, Espor, Favorites and Settings. The flat, compact bottom navigation uses readable labels, hides when scrolling down, and stays out of esports detail pages.
- Public eSports feed from VLR.gg: live, upcoming and completed matches, search, saved teams, separate saved matches and an in-app match view with schedule, time-zone-aware countdown, format, maps and stream links where the source provides them. A full VLR.gg link is optional.
- eSports exploration has match, tournament and news lists. Match rows place the two teams and their large logos side by side around the score, without enclosing cards. Logo URLs are read from the feed where possible; visible rows use cached match details when the feed omits them. Its Following view brings together favorited teams, profile links and their known fixtures/results. Search is an understated underline; known dates follow the selected app language. Tournament pages display horizontally scrolling, connected upper/lower/final bracket rounds with team logos, dates and scores, plus participant teams. Team profiles show roster and readable two-team match history; player profiles show portraits, agent K/D, ADR and kill/death counts where supplied. Completed match details show in-app player scoreboards with ACS, ADR and headshot rate when the source provides them. Images absent from the source fall back to initials.
- My inventory lists actually owned weapon skin levels by weapon, with search and public skin imagery. Exact owned level names are shown in weapon details where Riot returns matching entitlements; catalog previews are not presented as owned. Accessory metadata tries level icons and full-size image fields before showing a blank image.
- Store header separates Riot ID and tag, and attempts to show the signed-in player's current card art from their loadout. If the card is unavailable, it shows a compact identity row rather than invented artwork. The inactive Night Market explains its state and offers a recheck action; accessories use a compact image-led list.
- The store header first tries the private loadout route and otherwise shows the actual player card recorded for the signed-in account in its latest available match, labeled accordingly. It updates as soon as cached card art resolves. Weapon skins in the store, search and detail view have no enclosing artwork tile. Esports team logos have no gray discs; visible match logos are prefetched into the image cache.
- Round detail now defaults to a complete, time-ordered kill/spike flow with clear actor-to-victim labels, highlighted personal involvement and a compact K/D/A, damage and economy summary.
- Esports match detail separates overview, map scores, player stats and watch links. It uses source-provided map duration/picks, veto, patch, KAST, first kills/deaths and VODs when available. Logo initials are hidden after a real logo loads, avoiding ghost letters behind transparent marks. Settings are shorter, with appearance/language, notification controls and expandable permission/trust information.
- Saved upcoming matches can show a public lock-screen notification with Android's system chronometer countdown. Saved matches and followed teams' upcoming matches with a known start can schedule a separate 15-minute pre-match reminder; exact timing requires Android's Alarms & reminders access, otherwise an inexact alarm is used. Feed refresh, notification permission, connectivity, battery restrictions and source accuracy affect delivery. Saved live matches request an Android 16 Live Update and receive background score checks roughly every 15 minutes when Android allows. A manually pinned live match runs a user-started foreground service for approximately one-minute score checks and has an Unpin action. Settings can disable saved-match countdowns and choose off/favorite teams/all live for general score alerts. Notification permission, lock-screen content and device support govern visibility.
- Daily, bundle, Night Market and accessory expirations are handled independently. Expired offers are hidden and foreground refresh is requested. Missing prices are explicitly unknown, never fabricated as free; VP/KC costs use the matching currency ID. Skin rewards are resolved by ItemID rather than an unrelated offer ID. Refreshes do not merge obsolete store sections from cache. Returned discounts are shown as percentages.

## Authentication and trust

Login starts at https://auth.riotgames.com and currently redirects to Riot's own authenticate.riotgames.com form. It is not a reconstructed form. The non-exported native login activity uses a light Android theme, white WebView background, disabled forced darkening, stable native keyboard focus and disabled automatic handwriting. Username autocorrection is disabled; Chromium still owns text selection and composition. No production JavaScript or CSS is injected, and no password field or JavaScript bridge belongs to ValorShop. The current host is visible above the page. OAuth redirects require the expected HTTPS host, path and random state. Screen capture is blocked during login.

Access/ID tokens, account cache and Radar history use Android Keystore AES-GCM encryption. Riot browser cookies remain in WebView's app-private storage. No ValorShop server receives account data. Sign-out cancels loading and background checks and clears session/cache, cookies and browser storage. Favorites, budget and encrypted Radar history remain on this device. History is keyed separately by the stable Riot account UUID and region; a display-name change does not reset it.

Riot can expire/revoke sessions, require MFA or reject cookie renewal. Permanent login is not guaranteed. Notifications need permission, a valid session and connectivity. Android battery restrictions can delay checks; a force-stopped app cannot check until reopened.

This is not a Riot-approved RSO integration. The store uses undocumented game-client services, and Riot currently lists store tracking as an unapproved use case. No ban-free or comparative-security guarantee is made. Public distribution requires addressing Riot registration/policy requirements; production RSO needs Riot approval and credentials.

The PVP TLS adapter validates the chain against the bundled Riot-service CAs and retains hostname verification. It omits the problematic stapled OCSP response that caused earlier Android chain-validation failures. It is not a trust-all manager, but it is also not equivalent to normal platform revocation handling.

Public metadata comes from valorant-api.com without Riot credentials. It can lag new patches. Only actual returned prices/offers are shown. No purchases, gameplay modification or anti-cheat interaction are implemented.

eSports information is parsed from public VLR.gg pages, without the Riot session. It may break when the page markup changes or the site limits requests; this is not a licensed or official VLR feed. [VLR.gg terms](https://www.vlr.gg/terms) prohibit automated access, so public distribution of this integration requires permission or a licensed replacement feed. Samsung [Now Brief](https://www.samsung.com/uk/support/mobile-devices/how-to-set-up-and-use-now-brief-on-your-samsung-galaxy-device/) is a separate Samsung-managed briefing surface: this app cannot insert its own cards there. A saved upcoming match with a known start can be added to the device calendar via a user-confirmed event; Samsung may include calendar events in Now Brief when that card is enabled, but its appearance is not guaranteed. On supported devices, [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update) may appear in Samsung Now Bar; ordinary saved-match notifications can appear on the lock screen. Actual One UI behavior has not been verified on a Samsung device.

## Public-release gate

Before GitHub Releases, Play distribution or public promotion, complete all of the following:

1. Register the product with Riot and obtain an approved production RSO client and redirect flow. The current game-client/store connection is not an approved production RSO integration.
2. Remove the VLR.gg parser or obtain written permission/a licensed feed. Automated VLR access cannot be assumed permitted.
3. Publish an accurate privacy policy and support contact at a stable public URL, then complete real-device accessibility, battery and notification tests.
4. Run the release checklist in `SECURITY.md`, scan the final APK, and verify that no account/session data or signing material is in the repository.

`PRIVACY.md` and `SECURITY.md` are the source-of-truth publication documents. Do not publish the current APK as an official Riot companion until this gate is complete.

## Build

Requires JDK 21 and Android SDK 36.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat assembleDebugAndroidTest
```

The local debug APK is at app/build/outputs/apk/debug/app-debug.apk. Install over a previous build to retain app data. This is a debug-signed test build, not a Play Store release.

For tests on a development emulator, install both the app APK and app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk, grant notification permission to the test app's target, then run:

```powershell
adb shell am instrument -w app.nightwatch.store.test/androidx.test.runner.AndroidJUnitRunner
```

Use a dedicated test emulator. The Gradle connected-test runner in this environment uninstalls its target after execution; do not use it on the user's active installation. The tests cover matching/deduplication, stale/preview suppression, match metrics, OAuth host/path/state checks, Keystore persistence and notification delivery without duplicates.

Build 57 verification: release minification/resource shrinking, unit tests and lint must pass for every candidate. Exact store parity still requires a same-time in-game comparison; no claim is made that every Riot response, network condition or Samsung One UI device was tested.

The native login keyboard test uses a local fixture, checking light-mode rendering, sequential typing, middle insertion, deletion, IME composition and a masked synthetic password. An additional opt-in network test checks the actual Riot page's light form surface and types/deletes a synthetic username through Android InputConnection. It never enters a password or submits login. Test-only JavaScript inspects the fixture/page; none is used in production.

```powershell
adb shell am instrument -w -e live_riot true -e class app.nightwatch.store.RiotLivePageTest app.nightwatch.store.test/androidx.test.runner.AndroidJUnitRunner
```

## References

- [Riot VALORANT policy](https://support-developer.riotgames.com/hc/en-us/articles/22698769097107-VALORANT)
- [Apple tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- [Apple Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/liquid-glass)
- [WWDC25 Meet Liquid Glass transcript](https://developer.apple.com/videos/play/wwdc2025/219/)
- [Material navigation bar guidelines](https://m3.material.io/components/navigation-bar/guidelines)
- [Unofficial Storefront schema](https://valapidocs.techchrism.me/endpoint/storefront)
- [ValPal feature comparison](https://www.valpal-companion.com/en/)
- [Public metadata](https://valorant-api.com/)
- [Unofficial game-client docs](https://valapidocs.techchrism.me/)
- [Android WebView light/dark behavior](https://developer.android.com/develop/ui/views/layout/webapps/dark-theme)
- [Android automatic handwriting](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields)
- [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)
- [Android periodic work minimum interval](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest)
- [Samsung Now Bar support](https://www.samsung.com/ca/support/mobile-devices/how-to-use-the-now-bar-on-the-lock-screen-of-your-samsung-galaxy-device/)
- [Samsung Now Brief support](https://www.samsung.com/uk/support/mobile-devices/how-to-set-up-and-use-now-brief-on-your-samsung-galaxy-device/)
- [VLR.gg terms](https://www.vlr.gg/terms)

VALORANT assets belong to Riot Games. Raund is an independent, unaffiliated companion.
