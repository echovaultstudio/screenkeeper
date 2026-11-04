# Screenkeeper

A screen-time and parental-control app for Android phones, tablets and Android TV (NVIDIA Shield). Written from scratch on Android's public usage-stats APIs — no code from tvusage/ScreenUsage.

## Features

- Daily and 7-day usage per app, with a 7-day bar chart (device-wide and per app)
- **Statistics**: month and year views with totals, daily average, a chart per day or month, time-of-day profile, most used apps (time, share and times opened) and a yearly review; history is kept on the device indefinitely
- Daily time limit per app, with a warning notification 5 minutes before
- Allowed hours per app (windows can cross midnight, e.g. 20:00–02:00)
- Watch breaks: after X minutes of continuous use, a Y-minute break with countdown
- Block an app completely, or mark it unlimited
- 4-digit PIN: protect settings, lock individual apps, or grant 15 extra minutes from the block screen
- **PIN times**: require the PIN only during certain hours, per app (e.g. app A only 21:00–00:00) or with a device-wide **PIN schedule** where you tick *All apps* or just the apps it covers
- Wrong-PIN lockout (5 tries, then 30 s)
- Works with touch, D-pad, and remote number keys; shows on the Android TV home row
- **Phone remote**: pair your phone with the Shield once (6-digit code on the TV) and set limits, block apps, set or change the PIN, lock the TV's settings and see its statistics from the phone
- **Phone keyboard**: type into search boxes and logins on the TV from your phone's keyboard; the TV shows what's being typed
- **Update the TV from your phone**: installs Screenkeeper (or any APK) on the Shield over Network debugging — no computer
- Restarts after reboot; nothing leaves your home network (the remote feature talks TV ↔ phone over local Wi-Fi only; there is no cloud)

## Get the APK

**Option A — F-Droid (updates automatically)**
In F-Droid (or Droid-ify / Neo Store): *Settings → Repositories → +*, add `https://siansa.app/fdroid/repo`, then search for Screenkeeper. New versions show up as normal updates.

**Option B — direct download (no tools needed)**
Every push to `main` builds the app and commits the result to [`dist/Screenkeeper.apk`](dist/Screenkeeper.apk). Permanent link for phones, the Shield's *Downloader* app, or `wget`:

```
https://github.com/echovaultstudio/screenkeeper/raw/main/dist/Screenkeeper.apk
```

The same APK is attached to the GitHub Release of each version (the Releases page is updated by every build of `main`) and to each **Actions** run as the `Screenkeeper-apk` artifact.

**Option C — Android Studio**
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
   On Android 13+ the Usage access toggle is greyed out ("Controlled by restricted setting") for apps installed from a browser or file manager: open **App info → ⋮ → Allow restricted settings** once, then tap the row again. Installing with `adb install` avoids this — see [docs/adb.md](docs/adb.md).
3. Samsung/Xiaomi/OnePlus etc.: also set Screenkeeper's battery usage to *Unrestricted* in App info, or the system may stop it.

## Install on NVIDIA Shield TV

The short version is below; [docs/adb.md](docs/adb.md) has the full adb guide (installing adb on macOS/Windows/Linux, pairing phones over Wi-Fi or USB, updating, error messages).

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

## Control the Shield from your phone

1. Install the same APK on both the Shield and the phone.
2. On the Shield: open Screenkeeper → **Phone remote** → **Allow control from a phone: On**. A 6-digit pairing code and the TV's IP address appear.
3. On the phone: open Screenkeeper → **Control a TV from this device**. The Shield shows up by itself (DNS-SD); if not, use **Enter IP address…**. Enter the code.
4. The phone now shows the TV's usage and app list. Select an app to set its daily limit, allowed hours, breaks, blocks or PIN lock, give 15 extra minutes, or open it on the TV. The top buttons set, change or remove the TV's PIN and re-lock its settings.

Each pairing creates a random token that is stored on both devices; the code is rotated after every successful pairing and the TV waits a minute after 5 wrong codes. **Unpair all phones** on the TV revokes everything. Both devices have to be on the same network (port 47317, plain HTTP on your LAN).

### Type on the TV from the phone

1. On the TV: Screenkeeper → **Phone remote** → **Phone keyboard** opens the accessibility settings; turn on **Screenkeeper phone keyboard** (on a Shield: *Settings → Device Preferences → Accessibility*). Or use **Grant permissions on TV** from the phone (below), which does it over adb.
2. On the TV, select a search box or text field. On the phone: TV dashboard → **Keyboard** and type. Text appears on the TV as you type (backspace, autocorrect, paste and voice typing work), and a bubble at the top of the TV screen shows what's being typed. **Enter / Search** presses the TV keyboard's action key and clears the box on the phone, ready for the next search; the TV keeps what was sent.

It works with regular Android text fields (Play Store, browsers, most login screens, Kodi, Plex…). Apps that draw their own letter grid instead of a real text field can't be typed into this way. The service only acts when a paired phone sends text.

### Update the TV from the phone (adb, no computer)

1. On the TV, once: *Settings → Device Preferences → About → Build* ×7, then *Developer options → Network debugging: On*. Note the IP.
2. On the phone: **Control a TV** → **Update TV over ADB**. The paired TV's address is filled in (port 5555).
3. **Install this phone's Screenkeeper on the TV** sends the version installed on the phone — update the phone first, then press it. **Pick an APK…** installs any file. The first time, the TV asks *Allow network debugging?* — tick *Always allow* and choose OK.
4. **Grant permissions on TV** runs the usage-access, overlay and battery grants and turns on the phone keyboard (keeping any other accessibility services you use).

Updates keep the TV's rules and PIN as long as both APKs come from the same signing key (see *Keep updates installable*). The phone's adb key is stored in the app; revoke it on the TV under *Developer options → Revoke USB debugging authorizations*.

## Using it

- Main screen: today's total, 7-day chart, apps sorted by use. Select an app to set rules; long-press to open it.
- **Statistics** (main screen, or the TV dashboard on the phone for the paired TV's numbers): switch between **Month** and **Year**, step with ‹ ›, or tap the title to jump to any month or year with data. In the year view, tap a month to open it.
- Set a PIN first if kids use the device, then turn on rules. Settings re-lock 10 minutes after the PIN is entered (or press **Lock settings**).
- **PIN times per app**: open an app → **Require PIN to open** → *Off*, *Always*, or *Only between certain times…* and pick the start and end (windows can cross midnight, e.g. 21:00–00:00).
- **PIN schedule for many apps**: main screen → **PIN schedule** (on the phone: TV dashboard → **PIN schedule** for the TV). Tick *Use the PIN schedule*, set *From* and *Until*, then tick *All apps* or untick it and tick the apps you want. Changes save straight away. An app needs the PIN if either its own PIN times or the schedule say so; Screenkeeper and the home screen are never locked.
  - An app whose own **Require PIN to open** is *Always* (every app locked before 1.4.0) keeps asking outside the schedule's hours. The schedule screen lists these apps; **Let the schedule decide for these apps** turns their own setting off (and ticks them if the schedule covers only ticked apps). The block screen also says which setting asked for the PIN.
- When a rule triggers, a full-screen notice covers the app. **Go home** leaves; **Enter PIN** unlocks a PIN-locked app for that visit, or gives 15 more minutes for other blocks.

## Limits and ideas

- A determined user can uninstall it or revoke permissions in Settings. tvusage closes this with an Accessibility service that guards the uninstall screens; that's a reasonable next feature. Locking the Settings app with a PIN rule is a simple stopgap.
- Usage is measured per device; there's no cloud sync between your phone and the Shield. The phone can view the TV's statistics over the local network.
- Android only keeps detailed usage events for about a week, so Screenkeeper saves its own history (one small file per month in the app's storage, written every 10 minutes). Daily detail starts about a week before you installed 1.3.0; earlier months are filled from Android's monthly totals, per app only, and marked as estimated. Uninstalling the app deletes the history.
- Rename the app in `app/src/main/res/values/strings.xml` (`app_name`) and the package ID in `app/build.gradle.kts` (`applicationId`).

## Code map

| File | What it does |
|---|---|
| `Usage.kt` | Foreground time from raw usage events; live front-app watcher |
| `UsageHistory.kt` | Long-term history (per-day app time, opens, hour profile) and month/year summaries (`Summaries` is unit tested) |
| `StatsActivity.kt` | Statistics dashboard, for this device or a paired TV |
| `Enforcer.kt` | Pure block/allow decision and watch-break tracking (unit tested) |
| `GuardService.kt` | Foreground service, 1 s check loop, warnings, boot receiver |
| `RuleStore.kt` | Rules, salted PIN hash, overrides, break state |
| `MainActivity.kt` | Setup, PIN gate, dashboard |
| `AppDetailActivity.kt` | Per-app stats and rule editor |
| `BlockActivity.kt` | Block screen with break countdown and PIN unlock |
| `Ui.kt` | D-pad-friendly widgets, PIN pad, bar chart |
| `RuleEditor.kt` | The rules section, shared by the local and remote app pages |
| `PinScheduleActivity.kt` | Device-wide PIN schedule with tick boxes, for this device or a paired TV |
| `Remote.kt` | Protocol constants and the HTTP server the TV runs (pairing, state, rules, PIN, PIN schedule, keyboard, statistics) |
| `RemoteClient.kt` | Phone side: HTTP client and DNS-SD discovery of TVs |
| `RemoteActivity.kt` / `RemoteAppActivity.kt` | Phone screens: find/pair a TV, its dashboard, per-app rules over the network |
| `KeyboardService.kt` | TV: accessibility service that sets the focused text field's text for the phone keyboard |
| `RemoteKeyboardActivity.kt` | Phone: the keyboard screen, mirrors what you type to the TV |
| `AdbClient.kt` | Minimal adb-over-TCP client (RSA auth, streamed `pm install`, shell) |
| `AdbUpdateActivity.kt` | Phone: install/update an APK and grant permissions on the TV over adb |

## License

GPL-3.0-or-later, like Siansa. See `LICENSE`.
