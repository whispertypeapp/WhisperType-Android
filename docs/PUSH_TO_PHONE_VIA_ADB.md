# Push to Phone via ADB

How to connect to the test phone over ADB through the Tailscale tailnet and push the
current build. This is the normal deployment loop for the WhisperType rebuild on a
phone that is not plugged in via USB.

## Why wireless ADB over Tailscale works

- The phone runs the **Tailscale** app and joins the tailnet, so it has a stable
  **`100.x.y.z` address** reachable from this machine — no USB cable, no same-Wi-Fi
  requirement.
- ADB's **Wireless debugging** feature exposes a pairing port and a connect port on
  that Tailscale address.
- Because the address is stable, the workflow below stays the same across reboots
  (only the connect port may change after the phone reboots; see the device
  reference below).

> Note: The Tailscale tunnel is only used to reach the phone. It does not need to be
> (and is not) a full VPN — normal internet traffic from the phone is unaffected.

## One-time: enable Wireless debugging + pair

On the phone:

1. **Settings → Developer options → Wireless debugging** — turn it ON.
2. Tap **"Pair device with pairing code"**. Note the **pairing port** and the
   **6-digit code** (they rotate every few minutes).
3. From this machine:

   ```bash
   printf '<CODE>\n' | adb pair <PHONE_TAILSCALE_IP>:<PAIRING_PORT>
   ```

   Example:

   ```bash
   printf '102237\n' | adb pair 100.127.110.79:46585
   # → Successfully paired to 100.127.110.79:46585
   ```

4. On the Wireless debugging main screen, read the **"IP address & Port"** — this is
   the **connect port** (different from the pairing port).

## Connect

```bash
adb kill-server 2>&1
adb start-server 2>&1
adb connect <PHONE_TAILSCALE_IP>:<CONNECT_PORT>
adb devices
```

Example:

```bash
adb connect 100.127.110.79:33395
# → connected to 100.127.110.79:33395
adb devices
# → 100.127.110.79:33395   device
```

## Push the current build

Build first if needed (the signed release APK is the pushed artifact):

```bash
cd /workspace/projects/WhisperType-Android
./gradlew assembleRelease
```

Then install, preserving app data (`-r` = reinstall; keeps settings/permissions):

```bash
adb -s <SERIAL> install -r app/build/outputs/apk/release/app-release.apk
```

Example:

```bash
adb -s 100.127.110.79:40633 install -r app/build/outputs/apk/release/app-release.apk
# → Success
```

Useful variations:

| Goal | Command |
| --- | --- |
| Fresh install, clear data | `adb -s <SERIAL> uninstall com.whispertype.android && adb -s <SERIAL> install app/build/outputs/apk/release/app-release.apk` |
| Launch the app | `adb -s <SERIAL> shell am start -n com.whispertype.android/.MainActivity` |
| Check package path | `adb -s <SERIAL> shell pm path com.whispertype.android` |
| Service state | `adb -s <SERIAL> shell dumpsys activity services com.whispertype.android` |
| APK SHA-256 | `sha256sum app/build/outputs/apk/release/app-release.apk` |

> Do not `uninstall` during upgrade tests — it wipes the stored Gemini credential and
> permissions. Use `install -r` unless a clean slate is intentional.
> Never run `cmd statusbar` tile commands via adb — they can replace the user's
> entire Quick Settings tile list (the 1.0.5-era incident). Add/remove tiles by hand
> in the Quick Settings editor only.

## If the device drops or goes offline

Wireless ADB sessions drop (especially on Samsung, and always after a phone reboot).
When `adb devices` shows `offline` or is empty:

1. Re-establish the link:

   ```bash
   adb kill-server 2>&1; adb start-server 2>&1; sleep 1
   adb connect 100.127.110.79:33395 2>&1
   sleep 3; adb devices
   ```

2. If the phone **rebooted**, the connect port likely changed. Ask the user to open
   **Wireless debugging** again and read the new "IP address & Port", then connect to
   that.
3. If pairing expired, re-pair with a fresh code (see "One-time: enable Wireless
   debugging + pair").
4. Check reachability of the Tailscale address first if the connect keeps failing:

   ```bash
   ping -c 2 100.127.110.79
   timeout 5 bash -c 'echo > /dev/tcp/100.127.110.79/<CONNECT_PORT>' && echo open
   ```

## Scoped logs during a test

```bash
# Clear the buffer first (does NOT touch app data)
adb -s <SERIAL> logcat -c

# Resolve the app pid (re-resolve after a restart)
adb -s <SERIAL> shell pidof com.whispertype.android

# Follow that process's logs only
adb -s <SERIAL> logcat --pid=<PID>
```

## Current device reference (verified 2026-09-05)

- **Device:** Samsung Galaxy S25 (`SM-S921B`), Android 16 (SDK 36), 1080x2340 @ 480dpi
- **Tailscale IP:** `100.127.110.79`
- **ADB connect port:** `40633` — re-read after each phone reboot (Wireless debugging)
- **Package:** `com.whispertype.android` (currently `versionName 1.0.8`, `versionCode 53`)
- **Branch:** `main`
- **APK:** `app/build/outputs/apk/release/app-release.apk`
- **GitHub download:** https://github.com/chaosmanage/WhisperType-Android/releases/download/v1.0.8/app-release.apk
