# Raund privacy notice (development build)

Last updated: 11 November 2026

Raund is designed to process account and preference data on the device. It does not operate an application backend and does not sell, share, or use account data for advertising.

## Data stored locally

- Riot access/ID tokens and account cache are encrypted with an Android Keystore AES-GCM key.
- Riot browser cookies remain in Android's app-private WebView storage.
- Favorites, notification choices, widget choices, local store history, budget settings, and eSports cache are stored locally.
- The app disables Android cloud backup and device-transfer backup for its data.

## Network requests

The app connects directly to Riot authentication/game-client endpoints after an account connection, to Riot's public server-status and PlayValorant patch-notes pages, to public VALORANT metadata (`valorant-api.com`), to its configured eSports source (`vlr.gg`), and to privacy-enhanced YouTube embeds (`youtube-nocookie.com`) when you open lineup videos. Raund has no intermediary server.

## Notifications

Notifications are generated on-device from enabled store/eSports checks. They can be switched off in Android or in the app. Android's scheduler and battery restrictions may delay them.

## Retention and deletion

Use the in-app sign-out action to remove session/cache data and browser storage. Local favorites and settings remain until removed in the app or the app's storage is cleared in Android Settings.

## Important limitation

This notice describes the current development build, not an official Riot integration. A public release requires an approved Riot RSO implementation and a hosted privacy-policy URL.
