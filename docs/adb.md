# Installing Screenkeeper with adb

`adb` (Android Debug Bridge) installs an APK from your computer onto a phone, tablet or TV. It is the easiest way onto an NVIDIA Shield, and on phones it has one extra advantage: Android 13+ treats adb-installed apps as trusted, so the **Usage access** toggle isn't locked behind "Allow restricted settings".

## 1. Get adb on your computer

You only need Google's *platform-tools*, not Android Studio.

| | |
|---|---|
| **macOS** | `brew install --cask android-platform-tools` (Homebrew), or download below |
| **Windows** | Download, unzip, then open a terminal in that folder (or add it to PATH) |
| **Linux** | `sudo apt install adb` (Debian/Ubuntu) or download below |

Download: https://developer.android.com/tools/releases/platform-tools

Check it works: `adb version`

## 2. Download the APK

`https://github.com/echovaultstudio/screenkeeper/raw/main/dist/Screenkeeper.apk`

With a terminal: `curl -LO https://github.com/echovaultstudio/screenkeeper/raw/main/dist/Screenkeeper.apk`

## 3. Connect the device

### NVIDIA Shield / Android TV (over Wi-Fi)

1. On the TV: **Settings → Device Preferences → About → Build**, press OK on *Build* seven times until "You are now a developer" appears.
2. **Settings → Device Preferences → Developer options → Network debugging → On**. The TV shows its IP address there (also under *Settings → Network*).
3. On the computer:
   ```
   adb connect 192.168.1.50       # your TV's IP
   ```
   The first time, the TV asks *Allow USB debugging?* — tick *Always allow from this computer* and choose OK, then run `adb connect` again if it said "failed to authenticate".
4. `adb devices` should list the TV as `device` (not `unauthorized` or `offline`).

### Phone or tablet, over Wi-Fi (Android 11+)

1. **Settings → About phone → Build number**, tap seven times to unlock Developer options.
2. **Settings → System → Developer options → Wireless debugging → On**, then tap **Pair device with pairing code**. The phone shows a pairing code and an `IP:port`.
3. On the computer: `adb pair 192.168.1.20:37123` (the IP:port from the *pairing* dialog) and type the code.
4. Back on the Wireless debugging screen, note the main `IP:port` shown at the top (a different port), then `adb connect 192.168.1.20:41234`.

### Phone or tablet, over USB

1. Unlock Developer options as above, then **Developer options → USB debugging → On**.
2. Plug in the cable; on the phone accept *Allow USB debugging?* (tick *Always allow*).
3. `adb devices` should list it as `device`. On Windows, if it doesn't appear, install your phone maker's USB driver.

## 4. Install

```
adb install -r Screenkeeper.apk
```

`-r` keeps the app's data when installing over an older version. You should see `Success`.

If several devices are connected, pick one: `adb -s 192.168.1.50:5555 install -r Screenkeeper.apk` (the name as shown by `adb devices`).

## 5. Grant the permissions

Open Screenkeeper on the device and tap the permission rows. If a settings screen doesn't exist on your TV, grant them from the computer instead:

```
adb shell appops set app.screenkeeper GET_USAGE_STATS allow
adb shell appops set app.screenkeeper SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +app.screenkeeper
```

Then open the app again; the rows show as allowed.

## Updating

Download the new APK and run `adb install -r Screenkeeper.apk` again. Rules and the PIN are kept.

## Uninstalling

```
adb uninstall app.screenkeeper
```

This removes the rules and the PIN too. Without adb: on a phone long-press the icon → *App info* → *Uninstall*; on the Shield *Settings → Apps → See all apps → Screenkeeper → Uninstall*.

## Problems

| Message | Meaning |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was signed with a different key (for example a build before the release key existed). Uninstall first: `adb uninstall app.screenkeeper` — this deletes the rules and PIN. |
| `failed to connect` / `connection refused` | Wrong IP, network debugging off, or the TV went to sleep. Wake it and run `adb connect` again. |
| `unauthorized` in `adb devices` | The *Allow debugging?* prompt on the device hasn't been accepted yet. |
| `offline` | Run `adb disconnect` then `adb connect <ip>` again. |
| `INSTALL_FAILED_USER_RESTRICTED` | A work profile or parental control forbids installs; use the main profile. |

When you're done, you can turn Network / Wireless debugging off again on the device; the app keeps working.
