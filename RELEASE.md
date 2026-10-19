# Release guide (Macro Monitor)

How to cut, build, sign, verify, and distribute a release of Spirit Bear's Helper App.

Updates are **manual**. There is no in-app updater, no update notification, and no
background download. When you publish, you hand out the new build and tell people how to
install it over the old one (their data is preserved - see "Upgrade contract" below).

---

## 1. Versioning

The project uses [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

- `MAJOR` - breaking change to the app or the bridge API.
- `MINOR` - new features, backwards compatible.
- `PATCH` - bug fixes, backwards compatible.

Two version strings must be kept in sync per release, plus one monotonic integer:

| What | File | Field | Example |
|------|------|-------|---------|
| Bridge version | `bridge/app/__init__.py` | `__version__` | `"0.2.0"` |
| Android version name | `android/app/build.gradle.kts` | `versionName` | `"0.2.0"` |
| Android version code | `android/app/build.gradle.kts` | `versionCode` | `2` |

Rules:

- `versionName` and the bridge `__version__` should match for a given release.
- **`versionCode` must strictly increase** on every published Android build, even for a
  rebuild of the same `versionName`. Android only allows an in-place upgrade when the new
  `versionCode` is greater than the installed one.
- The bridge API version is declared once in `bridge/app/models.py`
  (`api_version: str = "v1"`) and is part of the public `GET /api/v1/info` response. Bump
  it only on a contract-breaking API change.

Add a matching entry to [`CHANGELOG.md`](CHANGELOG.md) for every release.

---

## 2. Release checklist

1. Bump `versionName` + `versionCode` in `android/app/build.gradle.kts`.
2. Bump `__version__` in `bridge/app/__init__.py`.
3. Add a dated section to `CHANGELOG.md` (move the `Unreleased` items down under the new
   version).
4. Run the test suites (bridge + installer; see section 3).
5. Build the Android APK (see section 4).
6. If you have a release keystore, export the four `MM_KEY*` variables (see section 5) so
   the build is signed; otherwise it stays unsigned/debug-signed - note which.
7. Verify the APK metadata and signature (see section 6).
8. Compute the SHA-256 of the APK to publish alongside it (see section 7).
9. Copy the APK to the repo root as `MacroMonitor-<version>-debug.apk` (or
   `-release.apk`) and note it in the release message.
10. Build the Windows installers (see section 4b) so `MacroMonitor-Setup.exe` /
    `MacroMonitor-Uninstall.exe` are current.
11. Post the build + notes + checksum in the distribution channel (see section 8).

---

## 3. Run the tests

### Bridge (Python)

From the `bridge` directory, using the project venv:

```powershell
& .\.venv\Scripts\python.exe -m pytest -q
```

Expected at time of writing: 108 passing, 3 skipped (the skips need a local Natro
reference copy - see `natro_reference/` in the README). Do not ship if tests fail.

### Installer & packaging (PowerShell / Pester)

Covers the upgrade-critical `.env` host/port preservation logic, the Tailscale step,
the repo-root resolver used by the EXEs, and the EXE build output:

```powershell
Invoke-Pester .\tests\setup-lib.Tests.ps1, .\tests\packaging.Tests.ps1
```

Expected at time of writing: 55 passing (the packaging suite's EXE assertions skip
until `packaging/build-exes.ps1` has been run).

---

## 4. Build the Android APK

The Android project is a standard Gradle build.

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
# run from the android/ directory
.\gradlew.bat assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk` (always debug-signed).

For a release build:

```powershell
.\gradlew.bat assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release.apk`.

- If **release signing credentials are present** (section 5), the output is signed
  (`app-release.apk`) - use this to distribute.
- If **not configured**, the output is **unsigned** (`app-release-unsigned.apk`) and Gradle
  logs `Release signing: not configured`. Nothing breaks; you just cannot ship it as-is.

Builds log which mode they are in:
`Release signing: ENABLED (keystore '...')` or
`Release signing: not configured - the release APK will be UNSIGNED.`

---

## 4b. Build the Windows installers (EXEs)

The user-facing installers are compiled from the scripts by
[`packaging/build-exes.ps1`](packaging/build-exes.ps1) using **PS2EXE** (fetched from the
PowerShell Gallery into `packaging\.tools\` on first build; the module is used only at build
time):

```powershell
powershell -ExecutionPolicy Bypass -File .\packaging\build-exes.ps1
```

Output (written to the repo root by default):

| File | Source | Notes |
|------|--------|-------|
| `MacroMonitor-Setup.exe` | `setup.ps1` | x64 PE, embedded icon, `requireAdministrator` manifest |
| `MacroMonitor-Uninstall.exe` | `uninstall.ps1` | same |

Options: `-Version 0.2.0.0` (stamped into both), `-OutputDir <dir>`,
`-IconPath <ico>`, `-NoRequireAdmin`.

**Important:** the EXEs are **thin, repository-anchored wrappers** — they bundle no payload
and resolve the repo root from their own location at runtime, then run `setup.ps1` /
`uninstall.ps1` **in place**. They must be distributed **in the project folder**, next to
`setup-lib.ps1` and `bridge\`; they will not work as standalone downloads. The `.exe` files
are build artifacts and are git-ignored (`/*.exe`); attach them to the release message
rather than committing them, or `git add -f` deliberately if the project decides to track
them (the repo does track the current APK artifact).

Verify the built EXEs (architecture, metadata, manifest, signature):

```powershell
$p = ".\MacroMonitor-Setup.exe"
$b = [IO.File]::ReadAllBytes($p); $pe = [BitConverter]::ToInt32($b,0x3C)
"arch=0x{0:X4}" -f [BitConverter]::ToUInt16($b,$pe+4)      # expect 0x8664 (x64)
(Get-Item $p).VersionInfo | Format-List ProductName,CompanyName,FileVersion
(Get-AuthenticodeSignature $p).Status                       # NotSigned unless you sign it
# manifest: confirm 'requireAdministrator' is embedded
[Text.Encoding]::ASCII.GetString($b).Contains('requireAdministrator')
```

---

## 5. Signing

Release signing is wired up in `android/app/build.gradle.kts` and reads credentials
**only from the environment or Gradle properties** - never from a committed file:

| Variable | Meaning |
|----------|---------|
| `MM_KEYSTORE` | path to the release keystore (`.jks` / `.keystore`); relative paths resolve against the repo root |
| `MM_KEYSTORE_PASSWORD` | keystore password |
| `MM_KEY_ALIAS` | key alias |
| `MM_KEY_PASSWORD` | key password |

Provide them any of these ways:

- environment variables (e.g. a `release.ps1` you keep locally, or a CI secret store), or
- Gradle properties passed with `-P` (`.\gradlew.bat assembleRelease -PMM_KEYSTORE=...`), or
- a **user-scoped** `%USERPROFILE%\.gradle\gradle.properties` (outside the repo).

Do **not** put credentials in the project `android\gradle.properties` or `local.properties`
unless they are git-ignored. `*.keystore` / `*.jks` are already in `.gitignore`.

If any value is missing, the release variant stays **unsigned** and debug builds are
completely unaffected.

### Creating the release key (do this once, then guard it forever)

```powershell
keytool -genkeypair -v -keystore release.keystore -alias macromonitor `
  -keyalg RSA -keysize 2048 -validity 10000
```

Then sign a build by exporting the four variables before `assembleRelease`:

```powershell
$env:MM_KEYSTORE          = "C:\keys\release.keystore"
$env:MM_KEYSTORE_PASSWORD = "<keystore password>"
$env:MM_KEY_ALIAS         = "macromonitor"
$env:MM_KEY_PASSWORD      = "<key password>"
.\gradlew.bat assembleRelease
```

**Critical:** Android will refuse to install an update signed with a different key than
the installed app. Whatever key signs the *first* build people install must sign **every**
future build, or users must uninstall (losing app-local data) and reinstall.

- Pick a signing key **before** the first public release and never change it.
- Back the keystore up somewhere safe and offline. If it is lost, you can never ship an
  update to existing installs.
- Never commit the keystore or its passwords.

> **Status:** the signing *plumbing* is implemented and verified with a throwaway key. No
> real release keystore has been configured yet, so distributed builds are still
> debug-signed. Do not claim production signing until you have created the release key,
> built with it, and verified the result (section 6).

---

## 6. Verify the APK

Set `JAVA_HOME` first (both tools are Java programs). Tools live in
`<android-sdk>\build-tools\<version>\`.

Signature:

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs <apk>
```

Confirm the signer certificate (SHA-256 / DN) matches the key you intend. For an in-place
upgrade the new APK's signer must equal the installed app's signer.

Metadata (package + versions):

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt.exe" dump badging <apk> |
  Select-String "package:|application-label"
```

Confirm:
- `package: name='com.macromonitor.app'` (must not change - it is the app identity).
- `versionCode='N'` greater than the previous release.
- `versionName='X.Y.Z'` matches `versionName` and the bridge `__version__`.

---

## 7. Checksum for distribution

```powershell
Get-FileHash -Algorithm SHA256 .\MacroMonitor-0.2.0-debug.apk
```

Post the hash next to the download so people can verify the file arrived intact.

---

## 8. Distribution (manual, via Discord)

- There is **no** GitHub Releases / store pipeline and **no** auto-update. Publish by
  posting the APK (and, for a bridge change, the updated repo/zip) in the project's Discord
  server.
- Include in the message:
  - version (`versionName` / `versionCode`),
  - a short summary (copy from `CHANGELOG.md`),
  - the SHA-256 checksum,
  - install/upgrade instructions (install over the existing app; data is preserved),
  - any bridge-side step (see below).
- Keep the previous build available for people who need to roll back.

---

## 9. Upgrade contract (what must stay stable)

Users install the new build **over** the old one. For that to work without data loss:

**Android**
- Package name stays `com.macromonitor.app`.
- The signing key is unchanged (section 5).
- `versionCode` increases (section 1).
- `AndroidManifest.xml` keeps `android:allowBackup="true"` and the backup rules that
  **exclude** the encrypted session-token preferences (device-bound key). The stored token
  cannot survive a new device anyway; the user just signs in / re-pairs again.

**Windows bridge**
- `setup.ps1` re-run:
  - **stops** any running bridge and **starts** it again at the end, so the new code is
    actually running (no more silently keeping the old process);
  - **preserves** the existing `MM_HOST` / `MM_PORT` in `bridge\.env` unless you explicitly
    pass `-BindHost` / `-Port`;
  - **backs up** `bridge\.env` to `.env.bak-<timestamp>` before changing it and restores it
    if the write fails;
  - preserves `bridge\config\accounts.json` (created only if missing) and skips account
    creation when a user already exists. Sessions live under `bridge\config\` and survive.

### Bridge upgrade steps

Just re-run the installer after replacing the bridge files - double-click
**`MacroMonitor-Setup.exe`**, or run the script:

```powershell
powershell -ExecutionPolicy Bypass -File .\setup.ps1
```

It stops the old bridge, upgrades, and starts the new one automatically. To change the bind
address/port at the same time, pass them explicitly
(`.\setup.ps1 -BindHost <host> -Port <port>`); otherwise your current values are kept.

If you ran with `-NoStart`, or the script warned it was not elevated, restart the task
yourself:

```powershell
Stop-ScheduledTask  -TaskName MacroMonitorBridge
Start-ScheduledTask -TaskName MacroMonitorBridge
```

(`Start-ScheduledTask` alone is a no-op while the task is already running, so stop first.)
The installer also cleans up any orphaned process still holding the port.

If a `.env` edit goes wrong, the installer restores the previous file automatically; you can
also recover manually from `bridge\.env.bak-<timestamp>`.

---

## 10. API compatibility

- The bridge exposes its API version in the public `GET /api/v1/info` response
  (`api_version`), alongside `bridge_version`.
- Bump `api_version` only for breaking changes; add new optional fields instead when you
  can.
- The app currently does **not** perform a version handshake, so mismatches are not
  detected automatically. When changing the API, note the required minimum in the release
  message and `CHANGELOG.md`.

---

## What this project deliberately does NOT do

- No in-app update checker.
- No update notifications.
- No background release polling.
- No update download/install mechanism.

Distribution is manual. Do not add the above unless the project direction changes.
