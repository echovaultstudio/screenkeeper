# Screenkeeper

A screen-time and parental-control app for Android phones, tablets and Android TV (NVIDIA Shield). Written from scratch on Android's public usage-stats APIs — no code from tvusage/ScreenUsage.

## Features

- Daily and 7-day usage per app, with a 7-day bar chart (device-wide and per app)
- Daily time limit per app, with a warning notification 5 minutes before
- Allowed hours per app (windows can cross midnight, e.g. 20:00–02:00)
- Watch breaks: after X minutes of continuous use, a Y-minute break with countdown
- Block an app completely, or mark it unlimited
- 4-digit PIN: protect settings, lock individual apps, or grant 15 extra minutes from the block screen
- Wrong-PIN lockout (5 tries, then 30 s)
- Works with touch, D-pad, and remote number keys; shows on the Android TV home row
- Restarts after reboot; no internet permission, nothing leaves the device

## Get the APK

**Option A — direct download (no tools needed)**
Every push to `main` builds the app and commits the result to [`dist/Screenkeeper.apk`](dist/Screenkeeper.apk). Permanent link for phones, the Shield's *Downloader* app, or `wget`:

```
https://github.com/echovaultstudio/tvlocker/raw/main/dist/Screenkeeper.apk
```

The same APK is also attached to each **Actions** run as the `Screenkeeper-apk` artifact, and to a Release when you create a tag like `v1.0.0`.

**Option B — Android Studio**
Open the folder, wait for sync, then *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

**Command line** (Android SDK + JDK 17 installed): `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

### Keep updates installable (recommended)
Without your own key, each CI build is signed with a throwaway debug key, so updating means uninstalling first (which wipes your rules). Make one key once:

```
keytool -genkeypair -v -keystore release.jks -alias screenkeeper -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # macOS: base64 -i release.jks
```

Add repository secrets (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`screenkeeper`), `KEY_PASSWORD`. Keep `release.jks` safe and out of the repo.

## Install on your phone

1. Download `Screenkeeper.apk` on the phone and open it. Allow "Install unknown apps" for your browser/file manager when asked.
2. Open Screenkeeper and tap each permission row: **Usage access** and **Display over other apps** (required), plus notifications and battery optimization (recommended).
3. Samsung/Xiaomi/OnePlus etc.: also set Screenkeeper's battery usage to *Unrestricted* in App info, or the system may stop it.

## Install on NVIDIA Shield TV

1. On the Shield: **Settings → Device Preferences → About → Build**, press OK 7 times to unlock Developer options.
2. **Settings → Device Preferences → Developer options → Network debugging: On**. Note the IP address shown.
3. On a computer with [platform-tools](https://developer.android.com/tools/releases/platform-tools):
   ```
   adb connect 192.168.1.50        # your Shield's IP; accept the prompt on the TV
   adb install Screenkeeper.apk
   ```
   (No computer? Install the *Downloader* app on the Shield, enter the APK's URL from your GitHub Release, and allow it under Special app access → Install unknown apps.)
4. Open Screenkeeper from the Apps row and grant the two permissions. On the Shield they're under **Settings → Apps → Special app access → Usage access** and **Display over other apps**.
5. If a settings screen is missing, grant everything over adb instead:
   ```
   adb shell appops set app.screenkeeper GET_USAGE_STATS allow
   adb shell appops set app.screenkeeper SYSTEM_ALERT_WINDOW allow
   adb shell dumpsys deviceidle whitelist +app.screenkeeper
   ```

## Using it

- Main screen: today's total, 7-day chart, apps sorted by use. Select an app to set rules; long-press to open it.
- Set a PIN first if kids use the device, then turn on rules. Settings re-lock 10 minutes after the PIN is entered (or press **Lock settings**).
- When a rule triggers, a full-screen notice covers the app. **Go home** leaves; **Enter PIN** unlocks a PIN-locked app for that visit, or gives 15 more minutes for other blocks.

## Limits and ideas

- A determined user can uninstall it or revoke permissions in Settings. tvusage closes this with an Accessibility service that guards the uninstall screens; that's a reasonable next feature. Locking the Settings app with a PIN rule is a simple stopgap.
- Usage is measured per device; there's no cloud sync between your phone and the Shield.
- Rename the app in `app/src/main/res/values/strings.xml` (`app_name`) and the package ID in `app/build.gradle.kts` (`applicationId`).

## Code map

| File | What it does |
|---|---|
| `Usage.kt` | Foreground time from raw usage events; live front-app watcher |
| `Enforcer.kt` | Pure block/allow decision and watch-break tracking (unit tested) |
| `GuardService.kt` | Foreground service, 1 s check loop, warnings, boot receiver |
| `RuleStore.kt` | Rules, salted PIN hash, overrides, break state |
| `MainActivity.kt` | Setup, PIN gate, dashboard |
| `AppDetailActivity.kt` | Per-app stats and rule editor |
| `BlockActivity.kt` | Block screen with break countdown and PIN unlock |
| `Ui.kt` | D-pad-friendly widgets, PIN pad, bar chart |
