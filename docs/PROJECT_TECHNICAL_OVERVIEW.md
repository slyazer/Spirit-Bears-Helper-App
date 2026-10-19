# Spirit Bear's Helper App — Project Technical Overview

> Phase 1 deliverable (environment preparation, repository discovery, documentation).
> Produced from source inspection at commit `6edda3b88da8dfe2221a191842f9fadbbf6950c1`
> (`main`, slyazer, 2026-10-15). No application code was modified.
> All paths are relative to the repository root. No secrets or credential values appear here.

---

## 1. Project purpose and verified functionality

**Macro Monitor** (product name) / **Spirit Bear's Helper App** (repo name) lets a phone remotely
monitor a **Natro Macro** Bee Swarm Simulator macro that is running on the owner's Windows PC.

Data does **not** come from a cloud backend. A local Python bridge on the PC reads Natro's own
output files and talks to the owner's own Discord bot, normalizes that into a clean schema, and
exposes it over a local HTTP API. The Android app is a client for that API.

Verified functionality (source-confirmed, not merely claimed):

| Feature | Backend (bridge) | Android (app) |
|---|---|---|
| Account listing | `GET /api/v1/accounts` → `bridge/app/routers.py:54` | `data/api/MacroApi.kt` `accounts()` |
| Live macro status (field, activity, honey, staleness) | `GET /api/v1/accounts/{id}/status` → `routers.py:59`; produced by `bridge/app/natro/service.py` `status_async()` | `ui/screens/DashboardScreen.kt`, `ui/RootViewModel.kt` |
| Planters + nectar | `GET .../planters` → `routers.py:92`; `PlantersResponse` in `bridge/app/models.py:123` | `MacroApi.kt` `planters()` |
| Hourly Discord reports (list + image) | `GET .../reports` (`routers.py:66`), `GET .../reports/{report_id}/image` (`routers.py:73`) | `ui/screens/ReportsScreen.kt` (with pinch-zoom overlay) |
| Webhook log feed | `GET .../logs` → `routers.py:85`; `bridge/app/natro/log_adapter.py` | `ui/screens/LogsScreen.kt` |
| Remote commands (`?start ?stop ?pause ?resume ?close ?timers`) | `POST .../commands` → `routers.py:99`; allowlist in `bridge/app/natro/service.py` | `domain/MacroCommands.kt`, `ui/screens/DashboardScreen.kt` |
| Login / session / logout / password change | `bridge/app/auth_routes.py` | `data/api/MacroApi.kt`, `data/prefs/SecureStore.kt` |
| QR pairing + LAN discovery | `bridge/app/pairing.py`, `bridge/app/discovery.py` | `ui/screens/QrScanScreen.kt`, `net/BridgeDiscovery.kt` |

Not present (confirmed absent, not assumed): in-app updater, update notification, background
release polling, analytics/telemetry, crash reporter, any vendor backend, GitHub Releases/CI
pipeline, Android unit/UI tests, TLS by default, Windows service.

Natro integration is **read-mostly**: it parses `settings/nm_config.ini`
(`bridge/app/natro/ini_adapter.py`), `settings/debug_log.txt` (`bridge/app/natro/log_adapter.py`),
and Discord messages (`bridge/app/natro/discord_adapter.py`). The only writes it performs are
sending command URIs (e.g. `?close Roblox`) to Natro's command receiver. Per `PRIVACY.md` and
`NOTICE`, no Natro source is vendored and Natro's own data is not modified.

---

## 2. Repository structure

```
/
├── README.md, ARCHITECTURE.md, API.md, SECURITY.md, PRIVACY.md, NOTICE, LICENSE
├── BUILD_STATUS.md, CHANGELOG.md, RELEASE.md, SETUP_WINDOWS.md, SETUP_ANDROID.md
├── .env.example, .gitignore
├── setup.ps1              # guided Windows installer (15 KB)
├── setup-lib.ps1          # .env host/port binding helpers (upgrade-critical)
├── uninstall.ps1          # removes task + firewall, optional purge
├── tests/
│   └── setup-lib.Tests.ps1  # Pester tests for setup-lib.ps1
├── MacroMonitor-0.1.0-debug.apk   # committed artifact — see §14 (finding F1)
├── bridge/                # Python FastAPI application
│   ├── run.py, run_bridge_guard.ps1, requirements.txt, pytest.ini
│   ├── config/accounts.example.json
│   ├── app/               # FastAPI code (§6)
│   ├── app/natro/         # Natro adapters (§6)
│   └── tests/             # pytest suite (11 files, §13)
└── android/               # Kotlin + Jetpack Compose application (§5)
```

Generated/ignored (not in tree): `bridge/.env`, `bridge/.venv/`, `bridge/config/users.json`,
`sessions.json`, `pairing.json`, `accounts.json`, `bridge/logs/`, `android/local.properties`,
`android/app/build/`, `natro_reference/`.

---

## 3. Environment requirements

| Requirement | Project constraint | Source |
|---|---|---|
| Git | needed for clone | — |
| Python | 3.10+ (docs: "verified with 3.14.x"); bridge deps need CPython 3.10–3.14 wheels | `SETUP_WINDOWS.md`, `bridge/requirements.txt` |
| JDK | **JDK 21** to run Gradle (compile target is Java 17) | `SETUP_ANDROID.md` |
| Android SDK | `platform-tools`, `platforms;android-35`, `build-tools;35.0.0` | `compileSdk = 35` in `android/app/build.gradle.kts` |
| Gradle | **Gradle 8.9 via wrapper** (no global Gradle needed) | `android/gradle/wrapper/gradle-wrapper.properties` |
| PowerShell | 5.1+ for `setup.ps1` / `uninstall.ps1` | scripts |
| Android Studio | not required for the documented CLI build path | `SETUP_ANDROID.md` |

The bridge uses a local virtual environment (`bridge/.venv`), not a global install.

---

## 4. Technology stack and versions

### Verified dependency versions (`bridge/requirements.txt`)

| Package | Version |
|---|---|
| fastapi | 0.143.0 |
| uvicorn[standard] | 0.54.0 |
| pydantic | 2.14.1 |
| pydantic-settings | 2.15.0 |
| httpx | 0.28.1 |
| python-dotenv | 1.2.4 |
| argon2-cffi | 25.1.0 |
| zeroconf | 0.151.5 |
| segno | 1.6.6 |
| pytest / pytest-asyncio | 8.4.2 / 1.2.0 |

### Android (`android/app/build.gradle.kts`, `android/gradle/libs.versions.toml`)

| Item | Value |
|---|---|
| applicationId / namespace | `com.macromonitor.app` |
| versionName / versionCode | `0.2.0` / `2` |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Java source/target, `jvmTarget` | 17 |
| AGP / Kotlin / Gradle wrapper | 8.7.3 / 2.0.21 / 8.9 |
| Compose BOM | 2024.10.01 |
| Retrofit / OkHttp / Gson | 2.11.0 / 4.12.0 (with logging-interceptor) |
| CameraX | 1.4.0 (core, camera2, lifecycle, view) |
| ML Kit barcode-scanning | 17.3.0 (bundled model, on-device) |
| Coil | 2.7.0 |

Build-system versions verified live on this machine: Python 3.14.7, Temurin JDK 21.0.12.1,
Gradle 8.9 (wrapper), Android SDK platform 35 + build-tools 35.0.0 (34.0.0 auto-added by AGP).

---

## 5. Android architecture

Single-module, single-Activity Jetpack Compose app. Pattern is pragmatic MVVM + a manual
DI container (no Hilt/Koin).

**Entry point** → `MainActivity.kt` sets content to `ui/MacroMonitorRoot.kt`.
`MacroMonitorApplication.kt` builds `di/AppContainer.kt`, which wires `RootViewModel`.

**Layers**

- `data/api/` — `ApiClient.kt` (Retrofit + OkHttp factory, attaches `Authorization: Bearer`),
  `MacroApi.kt` (interface, one method per endpoint), `ApiModels.kt` (Gson DTOs).
- `data/repository/MacroRepository.kt` — orchestrates API calls, error → user-facing message.
- `data/prefs/SecureStore.kt` + `data/security/KeystoreCrypto.kt` — persisted bridge address,
  username, and **AES-GCM encrypted session token** backed by the Android Keystore
  (device-bound key; excluded from backup via `res/xml/backup_rules.xml` and
  `data_extraction_rules.xml`).
- `net/BridgeDiscovery.kt` — mDNS/NSD lookup of `Macro Monitor Bridge._macromonitor._tcp.local.`.
  `net/ConnectivityChecker.kt` — reachability probe.
- `domain/` — `BridgeEndpoints.kt` (route names), `MacroCommands.kt` (allowlisted commands),
  `PairPayload.kt` (parses `macromonitor://pair?...`).
- `ui/RootViewModel.kt` (407 lines) — central state holder: connection phase, account list,
  selection, status/logs/reports/planters, auth session, error banners. Everything is exposed as
  `StateFlow`/`collectAsState`.
- `ui/screens/` — `ConnectScreen.kt` (discovery, address entry, login, QR entry),
  `DashboardScreen.kt` (status tiles + command console), `ReportsScreen.kt`, `LogsScreen.kt`,
  `QrScanScreen.kt`. `ui/components/Components.kt` — reusable `PanelCard`, `CardHeader`, `TinyTag`.
  `ui/theme/` — dark "professional dashboard" theme (`Color.kt`, `Theme.kt`, `Type.kt`).

**User flow:** startup → `RootViewModel` restores last session → discovery/known address →
`GET /health` + `GET /api/v1/info` probe → if no token, `ConnectScreen` (password login or QR
pair) → `POST /api/v1/auth/{login|pair}` → token stored encrypted → accounts list → dashboard
polling (`status`, `logs`, `reports`, `planters`) → optional `POST .../commands`.

**Coroutines/lifecycle:** all work in `viewModelScope` via `suspend` Retrofit methods;
`QrScanScreen` uses a dedicated single-thread executor for ML Kit and unbinds the camera in
`DisposableEffect.onDispose`.

**Permissions:** `CAMERA` (runtime-requested only in `QrScanScreen.kt`); `INTERNET` implicit.
**Build variants:** `debug` and `release` only. Release signing is env-driven and, when
credentials are absent, the release APK stays **unsigned** (`android/app/build.gradle.kts:40-44`).

**Real vs placeholder:** screens render real API data and surface bridge-supplied `detail`
strings; empty states are honest ("No reports yet…"). There is no mock/fake data path in the
shipping source. `ui/Assets.kt` embeds the bundled BSS asset IDs used by the UI.

---

## 6. Windows bridge architecture

**Entry points**

- `bridge/run.py` → `uvicorn.run("app.main:app", host=…, port=…)` (see finding F3 for a
  docstring error).
- `bridge/run_bridge_guard.ps1` → supervisor used by the scheduled task.
- `bridge/app/main.py` → `create_app()` factory + `lifespan` (logs startup, starts mDNS
  advertiser `MdnsAdvertiser`, warns if no users exist).

**Module map (`bridge/app/`)**

| Module | Role |
|---|---|
| `config.py` | `Settings` (pydantic-settings, env prefix `MM_`, file `.env`), `AccountConfig`, `resolve_path()` |
| `models.py` | Normalized Pydantic response schemas (Natro-agnostic) |
| `routers.py` | `/health`, public `/api/v1/info`, and the authenticated `/api/v1/*` API router |
| `auth.py` | `require_session`, `bearer_token`, `client_ip` |
| `auth_routes.py` | login, pair, me, logout, password change |
| `security.py` | Argon2id hashing (scrypt fallback), `PasswordPolicyError` |
| `users.py` | `UserStore` (JSON-backed, role-aware, normalized usernames) |
| `sessions.py` | `SessionStore` — opaque tokens, absolute + idle TTL, persistence |
| `ratelimit.py` | `LoginLimiter` — sliding window + lockout (in-memory) |
| `pairing.py` | single-use TTL pairing codes, QR PNG/ASCII, `local_ipv4_addresses()` |
| `discovery.py` | mDNS advertisement via `zeroconf` |
| `registry.py` | `AccountRegistry` — lazily builds `NatroService` per account |
| `state.py` | cached stores/limiters |
| `cli.py` | `create-user`, `reset-password`, `list-users`, `pair`, `info` |
| `natro/service.py` (748 lines) | core: status/report/log/planter parsing, command send, staleness, failsafe |
| `natro/ini_adapter.py` / `log_adapter.py` / `discord_adapter.py` | format readers |

**Configuration loading:** `Settings` reads `MM_*` from `bridge/.env` (via
`SettingsConfigDict(env_file=".env", env_prefix="MM_", extra="ignore")`); relative paths are
resolved against the bridge root by `config.resolve_path()`, not the CWD.

**Error/absence behavior (important):**
- Unknown account id → `404`.
- Natro stopped / file missing → adapters return safe defaults (`ini_adapter` treats a missing
  file as empty; `log_adapter` reports `stopped`), and `natro/service.py` returns
  `status.source = "natro-files"` with `stale: true` past `MM_STALE_AFTER_SECONDS` (180 s).
- Discord fetch failure → `ReportsResponse.detail` / `LogsResponse.detail` are populated so the
  app can explain *why* rather than silently showing nothing.
- Nectar values are `None`/empty when the build is unpatched — deliberately **no fabricated
  zeros** (see `CHANGELOG.md` "Fixed").
- `PlantersResponse.available = false` + `reason` when planters can't be read.

**Concurrency:** FastAPI async endpoints delegate to `NatroService` methods
(`status_async()`); Natro file/Discord reads are I/O-bound and executed via `asyncio` executors
inside `natro/service.py`. Rate limiting state is in-process (`ratelimit.py`) — single-process
assumption (see §15).

**Windows-specific behavior:** process lifetime is a scheduled task + a PowerShell guard
(`run_bridge_guard.ps1`), **not** a Windows service (verified: `Register-ScheduledTask`, not
`New-Service`). Network binding, firewall, and task registration are done by `setup.ps1` (§10).

**Hardcoded assumptions:** default bind `127.0.0.1:8787` (`config.py:53-54`), Discord API base
`https://discord.com/api/v10`, service name `Macro Monitor Bridge`, pairing scheme
`macromonitor://`, mDNS service type `_macromonitor._tcp.local.`, command prefix `?`.

---

## 7. API contracts

Base: `{bridge_url}`. The whole `/api/v1` router is wrapped in `Depends(require_session)`
(`bridge/app/routers.py:27`), except `/health` and the auth + info routes.

| Method | Path | Auth | Request | Response (model) | Notable codes |
|---|---|---|---|---|---|
| GET | `/health` | public | — | `HealthResponse{status, bridge_version, server_time, auth}` | 200 |
| GET | `/api/v1/info` | public | — | `InfoResponse{name, bridge_version, pairing_enabled, accounts, scheme, api_version}` | 200 |
| POST | `/api/v1/auth/login` | public | `LoginRequest{username, password, device_name?}` (JSON) | `TokenResponse{token, token_type, username, expires_at}` | 200, 401, 422, **429 + `Retry-After`** |
| POST | `/api/v1/auth/pair` | public (if `enable_pairing`) | `PairRequest{code, device_name?}` | `TokenResponse` | 200, 401, 404, 409, 429 |
| GET | `/api/v1/auth/me` | session | — | `MeResponse{username, role, device, created_at, expires_at}` | 200, 401 |
| POST | `/api/v1/auth/logout` | session | — | `{status:"ok"}` | 200 |
| POST | `/api/v1/auth/password` | session | `ChangePasswordRequest{current_password, new_password}` | `{status:"ok"}` | 200, 401, 422 |
| GET | `/api/v1/accounts` | session | — | `AccountsResponse{accounts:[AccountSummary]}` | 200, 401 |
| GET | `/api/v1/accounts/{id}/status` | session | — | `StatusResponse` | 200, 401, 404 |
| GET | `/api/v1/accounts/{id}/reports` | session | `?limit=10` | `ReportsResponse{id, reports[], detail?}` | 200, 401, 404 |
| GET | `/api/v1/accounts/{id}/reports/{rid}/image` | session | — | binary (`media_type`) | 200, 404 |
| GET | `/api/v1/accounts/{id}/logs` | session | `?limit=30` | `LogsResponse{id, logs[], detail?}` | 200, 401, 404 |
| GET | `/api/v1/accounts/{id}/planters` | session | — | `PlantersResponse{id, available, reason?, planters[], nectars[]}` | 200, 401, 404 |
| POST | `/api/v1/accounts/{id}/commands` | session | `CommandRequest{command, correlation_id?}` | `CommandResponse{id, command, result, correlation_id?, detail?}` | 200 (`result` ∈ accepted/rejected/failed), 401, 404 |

**Client-side mapping:** `android/.../data/api/MacroApi.kt` declares exactly these paths and
methods; DTO field names in `data/api/ApiModels.kt` match the snake_case JSON produced by
Pydantic/Gson.

**Contract checks performed (no mismatches found):**
- Endpoint paths and HTTP methods: identical.
- Auth scheme: bridge expects `Authorization: Bearer <token>` (`auth.py:28-33`);
  app sends `builder.header("Authorization", "Bearer $bearer")` (`ApiClient.kt:29`).
- Nullable/optional fields: app DTOs use nullable Kotlin types for bridge `| None` fields.
- `scheme` / `api_version`: `InfoResponse` defaults (`models.py:31-32`) are exactly what
  `API.md` documents — the router passes only `name`, `bridge_version`, `pairing_enabled`,
  `accounts` (`routers.py:39-44`) and the defaults fill the rest. **Not** a mismatch.
- Default `limit` differs (bridge 10 vs app default 12) — harmless, the app always sends the
  query parameter.
- **No version handshake on the client** (confirmed by `RELEASE.md` §10): the app reads
  `InfoDto` but does not compare `api_version`. See finding F6.

---

## 8. Authentication and security

**Model** (replaced the old shared `MM_API_TOKEN` in 0.2.0):

1. **Login** — `POST /api/v1/auth/login` with a JSON username/password. `users.verify()`
   normalizes the username, matches case-insensitively, and compares with Argon2id
   (`security.py`, `argon2-cffi`; `hashlib.scrypt` fallback). Constant-time comparison.
2. **Sessions** — opaque random token, persisted in `bridge/config/sessions.json`.
   Absolute TTL 2 592 000 s (30 d) and idle TTL 604 800 s (7 d); expired sessions dropped on
   read. Revoked by logout or by a password change (`sessions.revoke_all()` — the caller's own
   session is re-created, `auth_routes.py:187-191`).
3. **Pairing** — `python -m app.cli pair` mints a single-use, TTL-bound (600 s) code with an
   unambiguous alphabet; rendered as a QR PNG (`segno`) plus ASCII. The phone scans it
   (`QrScanScreen.kt`) and calls `POST /api/v1/auth/pair`, which returns a session for the
   **owner** account (`auth_routes.py:139`).
4. **Authorization** — `require_session` guards the whole `/api/v1` router. There is no
   per-role authorization layer: see finding F2.

**Credential storage**
- PC: `bridge/config/users.json` (hashes only), `sessions.json`. `.gitignore` excludes
  `**/users.json`, `**/sessions.json`, `**/accounts.json`, `.env`.
- Phone: session token encrypted with AES-GCM under an Android Keystore key
  (`KeystoreCrypto.kt`); excluded from device/cloud backups.

**Network exposure / binding**
- `setup.ps1` binds `0.0.0.0` by default and adds an inbound firewall rule for TCP 8787 limited
  to **Private + Domain** profiles (`setup.ps1:273`), explicitly targeting LAN + Tailscale.
- The app permits **cleartext** HTTP to the bridge (`res/xml/network_security_config.xml`) —
  see finding F4.
- Optional TLS is supported (`Settings.tls_cert_file`/`tls_key_file`, `.env.example:48-51`) but
  off by default.

**Input validation:** Pydantic `Field(min_length/max_length)` on all auth bodies; command
allowlist rejects unknown commands; account ids validated by registry lookup (404).

**Sensitive data in logs:** bridge logging is `logging.INFO` with no secrets (`main.py:18-21`);
`config.py` docstring and `SECURITY.md` state tokens/bot tokens are never logged. On the phone,
`HttpLoggingInterceptor` is set to `Level.BASIC` (`ApiClient.kt:19`), which logs
method/URL/status/size but **not headers or bodies** — the bearer token is not printed.

**Development-only auth paths:** none found (no `if DEBUG: bypass`). `enable_pairing` can be set
false (`MM_ENABLE_PAIRING=false`) to disable QR enrollment.

**Configuration defaults worth noting:** `host` defaults to `127.0.0.1` in code while
`.env.example` and `setup.ps1` use `0.0.0.0` — see finding F5.

---

## 9. Configuration and filesystem assumptions

| File | Contents | Created by | Ignored |
|---|---|---|---|
| `bridge/.env` | `MM_*` settings | `setup.ps1` (from `.env.example`) | yes |
| `bridge/config/users.json` | user records + password hashes | setup wizard / `app.cli create-user` | yes |
| `bridge/config/sessions.json` | live sessions | runtime | yes |
| `bridge/config/pairing.json` | pending pairing codes | `app.cli pair` | yes |
| `bridge/config/accounts.json` | Natro account paths | `setup.ps1` (seeded only if missing) | yes (example is committed) |
| `bridge/logs/`, `*.log` | bridge logs (may contain local IPs) | runtime | yes |
| `natro_reference/` | large Natro copies for integration tests | regenerated locally | yes |
| `android/local.properties` | SDK path | *not created by us* — we used env vars instead | yes |

Path assumptions: `AccountConfig.natro_dir` is an **absolute** path to a Natro install folder
containing `settings/nm_config.ini` and `settings/debug_log.txt`. Relative settings paths are
anchored to the bridge root via `config.resolve_path()`, so the bridge does **not** depend on the
caller's CWD for config — but `bridge/run.py` imports `app.config`, which requires the working
directory to be `bridge/` (or `bridge` on `sys.path`), so it must be launched from `bridge/`
(scheduled task sets `-WorkingDirectory $Bridge`, `setup.ps1:289`).

---

## 10. Installation, startup, and uninstallation

(Read only — installers/uninstallers were **not** executed during Phase 1.)

**`setup.ps1` sequence:** parameter defaults (`Port=8787`, `BindHost='0.0.0.0'`) →
elevation check (`IsInRole(Administrator)`, `setup.ps1:113`) → Python detection/venv creation →
dependency install → read existing `.env` (`setup-lib.ps1` `Get-EnvBinding`) →
`Get-EffectiveBinding` preserves existing `MM_HOST`/`MM_PORT` unless `-BindHost`/`-Port` were
explicit → `Stop-ExistingBridge` (stops `MacroMonitorBridge` task and any PID still holding the
port) → write `.env` with backup + `MM_PORT` sanity check (`setup.ps1:218-223`) → create login
account → seed `config/accounts.json` if missing → firewall rule (elevated only) → register
scheduled task → start task → show pairing QR.

**Startup mechanism:** `Register-ScheduledTask` `MacroMonitorBridge`, action
`powershell.exe … run_bridge_guard.ps1`, trigger **AtLogOn**, settings
`StartWhenAvailable -RestartCount 3 -RestartInterval 1min` (`setup.ps1:289-292`). This is a
task, **not** a Windows service.

**Uninstall (`uninstall.ps1`):** removes the scheduled task and the firewall rule; optional
`-PurgeConfig` deletes accounts/sessions/secrets. It never touches Natro data. (Inspected; not run.)

**Upgrade contract** (`RELEASE.md` §9): rerunning `setup.ps1` stops the old bridge, preserves
custom `MM_HOST`/`MM_PORT`, backs up `.env` to `.env.bak-<timestamp>` and restores on failure,
preserves `config/accounts.json` and sessions, then starts the new code. `versionCode` must
strictly increase; package name and signing key must stay constant.

**Idempotency/duplication:** firewall creation checks `Get-NetFirewallRule` first
(`setup.ps1:269`); task registration uses `-Force`; port-free verification after stop
(`setup.ps1:101-104`). Duplicate-process prevention is by port ownership, which is why
`Get-BridgeListenerPids` exists.

**Path/working-directory handling:** `-WorkingDirectory $Bridge` is set explicitly on the task;
quote handling exists for the argument string; spaces in install paths are handled by the
`Join-Path`/quoted-call style used throughout. **Pester coverage** exists for the binding logic
(`tests/setup-lib.Tests.ps1`, 14 assertions).

---

## 11. Build and release procedures

**Android**
- Debug: `android\.\gradlew.bat assembleDebug` → `android/app/build/outputs/apk/debug/app-debug.apk`
  (always debug-signed).
- Release: `.\gradlew.bat assembleRelease` → `app-release.apk` when `MM_KEYSTORE`,
  `MM_KEYSTORE_PASSWORD`, `MM_KEY_ALIAS`, `MM_KEY_PASSWORD` are all present (env var or `-P`
  property), otherwise `app-release-unsigned.apk` with a clear build log line
  (`build.gradle.kts:40-44`). Credentials are read from the environment only — never committed.
- Verification: `apksigner verify --print-certs`, `aapt dump badging`
  (`build-tools/35.0.0`). Distribution is manual (Discord) with a published SHA-256; there is no
  CI/CD and no in-app updater (`RELEASE.md` §8).

**Bridge:** no packaging step — `requirements.txt` + venv via `setup.ps1`.

**Versioning:** `bridge/app/__init__.__version__ = "0.2.0"`, `versionName = "0.2.0"`,
`versionCode = 2`; `api_version = "v1"` is a separate contract string (`models.py:32`).

**Actually tested vs only inspected (Phase K):**
- **Tested (ran successfully):** `gradlew.bat --version` (Gradle 8.9 + JDK 21),
  `gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**, producing a 45 MB debug APK.
- **Inspected only:** `assembleRelease`, APK signing/verification, the PowerShell installers,
  the scheduled-task lifecycle, mDNS advertisement at runtime, actual device pairing.

**Status:** no real release keystore exists; distributed builds are still debug-signed
(explicitly stated in `RELEASE.md` §5).

---

## 12. Important integration points

1. **Bridge start → HTTP bind:** `setup.ps1` writes `MM_HOST`/`MM_PORT` → scheduled task runs
   `run_bridge_guard.ps1` → `bridge/run.py` → `uvicorn.run("app.main:app", host, port)` —
   `0.0.0.0:8787` by default.
2. **Phone discovers the PC:** mDNS `Macro Monitor Bridge._macromonitor._tcp.local.` advertised
   by `bridge/app/discovery.py` (`MdnsAdvertiser`, started in `main.py` `lifespan`); discovered
   on Android by `net/BridgeDiscovery.kt` (NsdManager). Fallback: manual address entry in
   `ConnectScreen.kt`.
3. **Pairing/auth:** QR payload `macromonitor://pair?...` (`domain/PairPayload.kt`) →
   `POST /api/v1/auth/pair` → bearer token → stored AES-GCM-encrypted in Keystore
   (`SecureStore.kt`).
4. **Data path:** Android `MacroApi` → HTTP + Bearer → `routers.py` → `AccountRegistry` →
   `NatroService` → `ini_adapter` / `log_adapter` / `discord_adapter` → Pydantic `models.py` →
   Gson DTOs → `RootViewModel` StateFlows → Compose screens.
5. **Commands:** `DashboardScreen` → `MacroCommands.kt` allowlist →
   `POST /api/v1/accounts/{id}/commands` → `NatroService.send_command()` → Natro's command
   receiver URI (e.g. `?close Roblox`).
6. **Images:** `report_image()` returns raw bytes with a media type; the app builds an absolute
   URL via `ApiClient.absoluteImageUrl()` and renders with Coil.

---

## 13. Existing tests and verification results

**Bridge (pytest) — command run:**
`& "C:\Users\67\dev\Spirit-Bears-Helper-App\bridge\.venv\Scripts\python.exe" -m pytest -q`
(from `bridge/`) → **87 passed, 3 skipped, 1 warning in 6.94s** (90 collected).

The 3 skips are `bridge/tests/test_real_reference.py`, which is `skipif`'d when
`natro_reference/` is absent (it is gitignored and not present). `RELEASE.md` §3 says "Expected
at time of writing: 90 passing" — consistent with 90 collected, 87 executed + 3 skipped.

| Test file | Covers |
|---|---|
| `test_api_auth.py` (17 tests) | public `/health` + `/info`, route protection, login, case-insensitivity, wrong password, rate-limit lockout, logout revocation, `/me`, password change (+ weak rejection), pair (single-use, bad code, disabled) |
| `test_security.py` | hash/verify roundtrip, salting, rehash, password policy, garbage hash |
| `test_sessions.py` | create/get/revoke/revoke-all, expiry, persistence across reload |
| `test_users.py` | add/verify, normalization, duplicate/invalid/weak rejection, persistence |
| `test_pairing.py` | create/claim, single-use, alphabet, case-insensitivity, expiry, lifecycle, payload roundtrip, QR PNG bytes, ASCII grid |
| `test_commands.py` | allowlist, unknown rejection, prefix normalization, ambiguous-account refusal, `?close Roblox`, verbatim passthrough |
| `test_log_adapter.py` (10) | running/stopped/paused, field stripping, limits, field change/re-gather, last-line state |
| `test_ini_adapter.py` | value/type reading, key case, missing-file safety |
| `test_honey_reports.py` (12) | parse/format roundtrip, newest selection, NA handling, bot-id filtering/dedupe, stale-drop, converting-as-activity |
| `test_service_failsafe.py` | boosted field, session-scoped stats, planters/nectar present-vs-unpatched |
| `test_real_reference.py` (3, skipped) | integration against real Natro copies |

**Installer (Pester):** `Invoke-Pester .\tests\setup-lib.Tests.ps1` → `tests/setup-lib.Tests.ps1`
covers `Get-EnvBinding`, `Get-EffectiveBinding`, `Set-EnvBinding`, and a `.env` upgrade round
trip (14 `It` blocks). **Not executed during Phase 1** (Pester not confirmed present; running it
is safe but was left for the verification checkpoint).

**Coverage gaps (Phase L):** no tests for `Android networking`, `UI state`, `connection
failures`, `startup behavior`, `installation/uninstallation end-to-end`, `release compatibility`,
`serialization` beyond what FastAPI/pydantic does implicitly, or `filesystem operations` beyond
the adapters. No Android unit or instrumentation tests exist at all.

**Recommended minimal strategy for Phase 2:**
1. Keep the existing 90 bridge tests green as the regression gate for every bridge change.
2. Add Pester execution to the routine gate (`Invoke-Pester .\tests\setup-lib.Tests.ps1`) —
   it is already written and fast.
3. Add contract tests asserting the exact JSON of `/api/v1/info` and `StatusResponse` against
   `android/.../ApiModels.kt` field names (catches the version-handshake gap in F6).
4. Add Android unit tests for `PairPayload.parse`, `MacroCommands` allowlist, and
   `KeystoreCrypto` round-trip — pure logic, high value, no emulator needed.
5. Add a bridge test for the 404/absent-account and Natro-stopped paths end-to-end at the
   router level (partially covered; not router-level).

---

## 14. Technical debt and prioritized risks

Ordered by practical consequence. Confidence reflects how directly the source proves the claim.

### F1 — Committed debug APK contradicts `.gitignore` (High / High confidence)
`MacroMonitor-0.1.0-debug.apk` (21 036 618 bytes) sits at the repo root while `.gitignore:29-30`
excludes `*.apk` / `*.aab`. It is version `0.1.0` (stale — current `versionName` is `0.2.0`,
`versionCode 2`), and `BUILD_STATUS.md` references a `MacroMonitor-0.2.0-debug.apk` that is not
in the tree. Consequences: a 21 MB artifact in every clone, a stale binary people could sideload,
and a `.gitignore` rule that does not actually work (the file was committed before/among the rule
or force-added). *Evidence:* repo root listing; `.gitignore:29`; `build.gradle.kts:54-55`.

### F2 — `/api/v1/auth/me` hardcodes `role="owner"` (High / High confidence)
`bridge/app/auth_routes.py:150` returns `role="owner"` unconditionally for every authenticated
session, while `users.py` maintains real roles and `auth_routes.py:67-77` (`_owner_username`)
exists specifically to find the actual owner. Consequence: the API misreports identity; any
future feature that branches on `role` will silently be wrong for non-owner users. Today it is
cosmetic because there is no role-based authorization anywhere else. *Evidence:*
`auth_routes.py:146-154` vs `auth_routes.py:67-77`.

### F3 — `bridge/run.py` documents a non-existent launch command (Medium / Medium confidence)
The docstring says `python -m app.run` (from inside `bridge/`), but `run.py` lives at
`bridge/run.py`, not `bridge/app/run.py` — there is no `app/run.py`, so `python -m app.run`
fails with `No module named app.run`. The working invocations are `python run.py` (from
`bridge/`) or `python bridge/run.py` (from root). Consequence: a copy-pasted first-run command
from the module header fails. *Evidence:* `bridge/run.py:4-9`; module inventory (no
`bridge/app/run.py`).

### F4 — Cleartext HTTP permitted to the bridge (Medium / Medium confidence)
`android/app/src/main/res/xml/network_security_config.xml` allows cleartext (the bridge is
reached over plain HTTP, and `config.py:79-82` documents TLS as optional). Combined with the
default `0.0.0.0` bind and a Private+Domain firewall rule, any host on the LAN can observe or
inject API traffic — including the bearer token — without TLS. The project's own threat model
(`.env.example:10-12`, `SECURITY.md`) accepts this for a "trusted LAN or tailnet". Consequence:
acceptable for the documented model, but it becomes a real exposure if the bridge is ever
reachable beyond a trusted network (e.g. a mis-scoped firewall profile or a public tailnet). *Risk
category: potential/conditional, not a confirmed vulnerability in the documented deployment.*

### F5 — Config default disagrees with docs/installer (Low-Medium / High confidence)
`bridge/app/config.py:53` defaults `host = "127.0.0.1"`, while `.env.example:13` sets
`MM_HOST=0.0.0.0` and `setup.ps1:35` defaults `BindHost = '0.0.0.0'`. Consequence: running the
bridge **without** a `.env` binds only loopback and the phone cannot connect — a confusing
failure mode for anyone bypassing `setup.ps1`. It is safe-by-default for security but
inconsistent with the documented "phones reach the bridge" setup. (Not a bug in the installer
path; it only bites manual/headless launches.)

### F6 — No API version handshake (Medium / Medium confidence)
`InfoResponse` exposes `api_version` (`models.py:32`) but `android/.../data/api/ApiModels.kt`
+ `RootViewModel.kt` never compare it to anything. `RELEASE.md` §10 states this explicitly.
Consequence: a phone on 0.1.0 and a bridge on 0.3.0 will fail with opaque 401/404/JSON errors
rather than "please update". Given the manual Discord distribution model (§11), version skew is
likely in practice.

### F7 — `ARCHITECTURE.md` lists `python-multipart` which is not installed (Low / High confidence)
`ARCHITECTURE.md:45` says the stack includes "extra deps: `python-multipart`, `argon2-cffi`",
but `bridge/requirements.txt` has no `python-multipart`. This is **not** a runtime bug: the
auth endpoints take JSON bodies (`auth_routes.py:23-37`, `LoginRequest(BaseModel)`), so FastAPI's
form-parsing dependency is never required — confirmed by the passing test suite. Consequence:
documentation drift only; a future move to OAuth2 form login would break without it.

### F8 — README references `natro_reference/`, which is absent (Low / High confidence)
The README mentions Natro reference data for integration testing; `.gitignore:35` excludes
`natro_reference/`, so a fresh clone gets 3 skipped tests and no reference-data instructions.
Consequence: contributors see "3 skipped" without knowing how to enable them (the mechanism is
only documented inside `bridge/tests/test_real_reference.py` via `MM_NATRO_REFERENCE`).

### F9 — Minor code hygiene in `ui/RootViewModel.kt` (Low / High confidence)
`refreshAll` declares a parameter that is unused at its call site, and `refreshStatus(showError)`
is invoked without the argument. Consequence: compiler warnings only; no behavioral impact
observed.

### F10 — Process/rate-limit state is in-process and unauthenticated-to-the-network (Low / Medium
confidence)
`ratelimit.py` `LoginLimiter` state lives in memory (reset on restart) and `sessions.json` /
`users.json` are single-file JSON stores. Consequence: a restart clears lockouts; concurrent
writers could race. Acceptable for a single-process personal tool; would matter if the bridge
were ever run multi-worker.

---

## 15. Unverified assumptions and unanswered questions

Unverified because Phase 1 forbade running the installers, modifying state, or using devices:

1. **End-to-end runtime behavior.** The bridge was never started and the APK was never installed
   on a device. mDNS advertisement, QR pairing, and a live login were verified only in source and
   unit tests, not by execution.
2. **`setup.ps1` / `uninstall.ps1` behavior** — read and Pester-covered, but not executed. The
   elevation warning path, firewall creation, scheduled-task registration, and `.env` rollback are
   untested on this machine.
3. **Pester results** — `tests/setup-lib.Tests.ps1` was not run (Pester availability not
   confirmed).
4. **Release build + signing** — `assembleRelease`, `apksigner verify`, and `aapt dump badging`
   were not executed; no keystore exists.
5. **Natro integration accuracy** — `natro/service.py` (748 lines) parses Natro's real file
   formats; only the synthetic-format tests ran. The real reference data (`natro_reference/`) is
   absent, so real-file parsing is unverified here.
6. **APK contents** — the built debug APK was not inspected for the absence of leaked config.
7. **TLS path** — `MM_TLS_CERT_FILE`/`MM_TLS_KEY_FILE` code path not exercised.
8. **Discord adapter** — `discord_adapter.py` was inspected but not run against live Discord data
   (would require a bot token; deliberately not attempted).

Questions the repository cannot answer on its own (relegated to §I of the final report):
- Is `MacroMonitor-0.1.0-debug.apk` intentionally kept at the root as a distribution artifact, or
  should it be removed / replaced with a `0.2.0` build?
- Is there a release signing keystore (or a plan to create one), or will builds stay debug-signed?
- Should non-owner users be supported (affects F2 and future authorization), or is "single owner"
  the intended permanent model?
- Is the intended deployment always a trusted LAN/tailnet (affects F4 severity)?

---

## 16. Recommended order of future engineering work

Phase 2+ sequence — stabilize before adding features. Nothing below was executed in Phase 1.

**Step 1 — Verification baseline (no code changes)**
Run the two existing suites and record results: bridge `pytest -q` (done: 87 passed/3 skipped)
and `Invoke-Pester .\tests\setup-lib.Tests.ps1`. This is the regression gate.

**Step 2 — Repository hygiene (trivial, unblocks everything)**
Resolve F1 (decide the APK's fate: remove it, or replace with a current build and stop
committing binaries), and fix the three documentation drifts F3, F7, F8. Low risk, high clarity.

**Step 3 — Bridge/API correctness (small, high value)**
Fix F2 (return the session's real role from `/me` or drop the field), and add a contract test
pinning `/api/v1/info` + `StatusResponse` JSON (Step 5.3). Consider aligning the `config.py`
default with the documented deployment (F5) or documenting the loopback default prominently.

**Step 4 — Client/bridge compatibility guard (medium)**
Implement the version handshake from F6: have the app read `InfoDto.api_version` /
`bridge_version` and surface a clear "bridge too old / app too old" message instead of an opaque
401/404. This directly addresses the manual-distribution skew risk.

**Step 5 — Android test coverage (medium)**
Add the four unit-test targets named in §13 (4.). Cheap, emulator-free, protects the pairing,
command allowlist, and token-storage logic.

**Step 6 — Security posture review (judgment call)**
Re-evaluate F4 only if the deployment model changes (bridge reachable beyond a trusted
LAN/tailnet). If it does, enable TLS (`MM_TLS_*`) and tighten `network_security_config.xml`.
Otherwise, document the trust assumption where users will see it.

**Step 7 — Installer lifecycle hardening (after Steps 1–3)**
Only after the gate is green: exercise `setup.ps1` re-run/upgrade/uninstall on a clean machine,
verify the `.env` preservation and rollback paths end-to-end, and confirm the uninstaller leaves
nothing behind (or removes nothing it shouldn't).

**Step 8 — Release readiness**
Create and protect a release keystore, run `assembleRelease` + `apksigner verify` + `aapt`
badging, publish a checksum, and only then claim production signing (`RELEASE.md` §5 status).

---

*Prepared in Phase 1 (inspection and documentation only). No application source, scripts, build
files, or existing documentation were modified; this file is the only addition.*
