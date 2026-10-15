# SETUP — Android app

The app at `android/` builds a debug APK with Kotlin + Jetpack Compose. It talks only to
your Windows bridge on the local network (or Tailscale). Your login **session token** is
stored encrypted via the Android Keystore — never in plain text.

## Build the APK (one-time)
Prereqs installed this session at `C:\devtools\`:
- JDK 21 (Temurin) — `C:\devtools\jdk-21`
- Android SDK — `C:\devtools\android-sdk` (platform 35, build-tools 35.0.0)
- Gradle 8.9 (wrapper is checked into the project)

```powershell
cd android
$env:JAVA_HOME = "C:\devtools\jdk-21"
$env:ANDROID_HOME = "C:\devtools\android-sdk"
.\gradlew.bat assembleDebug
```

APK output: `app\build\outputs\apk\debug\app-debug.apk`
(A copy is at the project root as `MacroMonitor-0.2.0-debug.apk`.)

## Install on your phone
1. Enable **Install unknown apps** for your file manager / ADB source.
2. Copy `app-debug.apk` to the phone and tap it, or use ADB:
   ```powershell
   & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
   ```
3. Open **Spirit Bear's Helper App**.

## Connect (three ways)

Make sure the bridge is running on the PC (see `SETUP_WINDOWS.md`). The phone must be on
the same Wi‑Fi/LAN, or on the same Tailscale network.

1. **Scan the QR code** (easiest) — on the PC, run `python -m app.cli pair` (the setup
   wizard prints one automatically). In the app tap **Scan the QR code** and point the
   camera at it. Camera permission is requested on first use; the code carries the
   bridge address and a single-use pairing token.
2. **Find nearby** — the app discovers the bridge automatically over mDNS on the same
   Wi‑Fi. Tap the discovered host, then sign in.
3. **Enter details** — type the address (e.g. `http://192.168.1.100:8787`) and sign in
   with your **username and password**.

Signing in exchanges the password for a **session token**, which the app keeps in
Keystore-encrypted storage and sends as a bearer header on every call.

### Remote access off-LAN
Install [Tailscale](https://tailscale.com/) on the PC and phone (same tailnet) and use
the PC's Tailscale address (e.g. `http://100.x.y.z:8787`). **Do not** expose the bridge
port to the public internet.

### QR / deep link
The app also registers the `macromonitor://` scheme, so a pairing link can be opened
directly (e.g. from a message) to auto-fill the connect screen.

## Notes & limitations
- Command buttons (`?start` etc.) are disabled while a command is in flight; the app
  shows the bridge's verdict (accepted / rejected / failed).
- Accounts that share a bot token + channel are marked `command_ambiguous` by the
  bridge, which refuses commands to them (see `accounts.json`).
- Honey/rate values update on the macro's hourly StatMonitor cadence.
- The app allows cleartext HTTP so it can reach the bridge over Tailscale/LAN; do not
  expose the bridge port to the public internet.
- **Sign out** (Connect screen) revokes the session on the bridge and clears the local
  token. Reinstall/updates: `adb install -r` keeps the stored session.
- Pairing codes are single-use and expire; generate a fresh one with `python -m app.cli pair`.
