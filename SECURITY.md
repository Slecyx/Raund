# Security and release checklist

## Reporting a vulnerability

Do not post suspected credential, token, account, or remote-execution issues in a public issue. Until a dedicated contact is published, report them privately to the repository owner through GitHub.

## Mandatory release checks

- [ ] Confirm `keystore.properties`, `.signing/`, `local.properties`, `work/`, `outputs/`, build directories, and test attachments are ignored and absent from the staged diff.
- [ ] Run `./gradlew.bat testDebugUnitTest lintRelease assembleRelease` from a clean checkout.
- [ ] Verify the release APK signature and install it on an Android 8 device and a current Android device.
- [ ] Test sign-in, sign-out, account deletion path, notification permission denial, offline mode, dark/light/system theme, tablet layout, widgets, and TalkBack labels.
- [ ] Review `PRIVACY.md`, including a real support contact and public URL, before any public distribution.
- [ ] Complete Riot registration/production RSO approval and replace or license the eSports feed before describing the app as publicly launchable.

## Security decisions in this build

- Cleartext HTTP is disabled.
- Android backups/device transfer are disabled for all app data.
- Login runs in a non-exported activity with screen capture disabled.
- Session data uses Android Keystore-backed AES-GCM encryption.
- Release builds are compiled with the Android release configuration. R8 shrinking remains disabled until it can be verified within the project’s memory budget.

These controls reduce risk but do not turn the current undocumented Riot/store flow into an approved production integration.
