# SETUP — Android app

The app at `android/` builds a debug APK with Kotlin + Jetpack Compose. It talks only to
your Windows bridge via the bearer token stored in encrypted (Keystore-backed)
preferences.

## Build the APK (one-time)
Prereqs installed this session at `C:\Users\67\devtools\`:
- JDK 21 (Temurin) — `C:\Users\67\devtools\jdk-21.0.12.1+1`
- Android SDK — `C:\Users\67\devtools\android-sdk` (platform 35, build-tools 35.0.0)
- Gradle 8.9 (wrapper is checked into the project)

```powershell
cd android
$env:JAVA_HOME = "C:\Users\67\devtools\jdk-21.0.12.1+1"
$env:ANDROID_HOME = "C:\Users\67\devtools\android-sdk"
.\gradlew.bat assembleDebug
```

APK output: `app\build\outputs\apk\debug\app-debug.apk`
(A copy is at the project root as `MacroMonitor-0.1.0-debug.apk`.)

## Install on your phone
1. Enable **Install unknown apps** for your file manager / ADB source.
2. Copy `app-debug.apk` to the phone and tap it, or use ADB:
   ```powershell
   & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
   ```
3. Open **Macro Monitor**.

## First connect
1. Make sure the bridge is running on the PC (see SETUP_WINDOWS.md) and the phone is on
   the same Tailscale network.
2. In the app's connect screen enter:
   - **Bridge address**: `http://<PC-tailscale-ip>:8787`
   - **API token**: the `MM_API_TOKEN` value from `bridge\.env`
3. Tap **Connect**. The app pings `/health`, then loads accounts.
4. If connection fails, the app shows whether the cause is no internet, unreachable
   bridge, or bad auth.

## Notes & limitations
- Command buttons (`?start` etc.) are disabled while a command is in flight; the app
  shows the bridge's verdict (accepted / rejected / failed).
- Accounts that share a bot token + channel (e.g. the 67 & 69 builds) are marked
  `command_ambiguous` by the bridge, which refuses commands to them. Use the 61 account
  for remote commands, or give each build its own bot.
- Honey/rate values update on the macro's hourly StatMonitor cadence.
- The app allows cleartext HTTP so it can reach the bridge over Tailscale; do not expose
  the bridge port to the public internet.
- Reinstall/updates: `adb install -r` keeps the stored bridge address + token.