# Changelog

All notable changes to Spirit Bear's Helper App (Android app + Windows bridge) are
documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

See [RELEASE.md](RELEASE.md) for how to cut a release (version bump locations, build,
sign, verify, and distribute).

## [Unreleased]

_Nothing yet._

## [0.2.0] - 2026-10-15

Public-release readiness: replaced the shared API token with a real auth model, added QR
pairing and LAN discovery, and shipped a guided Windows installer.

### Added
- **QR pairing**: single-use, TTL-bound pairing codes with a `macromonitor://pair?...`
  payload; rendered as a PNG (`segno`) plus an ASCII fallback. New CLI command
  `python -m app.cli pair`.
- **LAN discovery (mDNS)**: the bridge advertises
  `Macro Monitor Bridge._macromonitor._tcp.local.`; the app discovers bridges with
  `NsdManager`.
- **Username/password login** with revocable bearer **session tokens** (absolute + idle
  TTL, persisted). Sessions survive restarts.
- **Auth CLI** (`app/cli.py`): `create-user`, `reset-password`, `list-users`, `pair`,
  `info`.
- **Public `GET /api/v1/info`** endpoint (name, bridge version, API version, pairing
  status, accounts, scheme). Everything else requires a valid session.
- **Windows installer** `setup.ps1`: detects Python, creates the venv, installs
  dependencies, writes `.env`, creates the login account, seeds `config/accounts.json`,
  adds a Private/Domain firewall rule, registers the auto-start task, and shows a pairing
  QR.
- **Windows uninstaller** `uninstall.ps1`: removes the scheduled task and firewall rule,
  with optional venv/config purge. Never touches Natro data.
- Android **QR scanner** (CameraX + ML Kit), **bridge discovery**, and encrypted session
  storage via Android Keystore (AES-GCM).
- Release/licensing docs: `PRIVACY.md`, `SECURITY.md`, `NOTICE`, `LICENSE` (MIT),
  `RELEASE.md`, `CHANGELOG.md`.

### Changed
- **Auth model reworked**: removed the shared `MM_API_TOKEN`. Single owner + many
  instances, per-user accounts. `/api/v1/*` (except `info` and `health`) is now behind
  `require_session`.
- Login is rate-limited (sliding window + lockout) with typed `401/409/422/429` errors and
  `Retry-After`.
- Passwords hashed with Argon2id (scrypt fallback), constant-time comparison. Changing a
  password revokes sibling sessions.
- Android: `versionCode 2`, `versionName 0.2.0`; session token stored via
  `KeystoreCrypto`; backup/transfer rules exclude the token preferences.
- `.env.example`, `.gitignore`, `API.md`, `ARCHITECTURE.md`, `README.md`,
  `SETUP_WINDOWS.md`, `SETUP_ANDROID.md`, `BUILD_STATUS.md` updated for the new flow.
- Android dependencies: added CameraX, ML Kit barcode scanning, and mDNS/NSD usage.

### Fixed
- Nectar data: patched `submacros/StatMonitor.ahk` `DetectBuffs()` to write
  `[Status] NectarComforting/Motivating/Satisfying/Refreshing/Invigorating`; the bridge
  reads them into the planters response (empty when unpatched - no fabricated zeros).
- `?close` is sent as `?close Roblox` (Natro's `close` is a no-op without a window title).

### Notes
- Updating requires installing the new APK over the old one (same package + signing key;
  `versionCode` increases). App data is preserved.
- On the bridge side, stop and start the `MacroMonitorBridge` scheduled task so the new
  code is actually running (see `RELEASE.md`).
- Bridge tests: 90 passing.

## [0.1.0] - 2026-10-15

First working build: Android dashboard for Natro Macro via a local Windows bridge.

### Added
- Windows **Python FastAPI bridge** with Natro adapters (ini, debug log, Discord).
- REST API: `/health`, accounts, status, reports, logs, planters, commands.
- **Android app** (Kotlin + Jetpack Compose): dashboard, reports (with zoom viewer),
  activity log, connect screen, account switcher.
- Real-time status: current field with `elapsed / limit`, current activity (e.g.
  `Converting`, `Traveling`), and field-aware staleness detection.
- Honey metrics: current honey, honey per hour, session totals (session-scoped, never
  all-time).
- Remote commands via a colour-coded console (`?start ?stop ?pause ?resume ?close ?timers`)
  with a confirmation dialog.
- Bundled real BSS assets (fields, mobs, boosters, planters, activity icons).
- Auto-start on Windows via `run_bridge_guard.ps1` + the `MacroMonitorBridge` scheduled
  task + firewall rule.

[Unreleased]: https://github.com/slyazer/Spirit-Bears-Helper-App/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/slyazer/Spirit-Bears-Helper-App/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/slyazer/Spirit-Bears-Helper-App/releases/tag/v0.1.0
