# Architecture — Macro Monitor

## Overview

Macro Monitor is a private, Android-first companion app for monitoring and controlling the
user's own **Natro Macro** instances running Bee Swarm Simulator on a Windows host PC.

```
┌──────────────────────────┐         Tailscale private network        ┌────────────────────────────┐
│  Android app (Kotlin)    │  ───────────────────────────────────────▶ │  Windows bridge (FastAPI)  │
│  Jetpack Compose UI      │   HTTP + Bearer token (HTTPS on tailnet)  │  localhost / tailnet only  │
│  Encrypted prefs         │  ◀─────────────────────────────────────── │  allowlisted commands only │
└──────────────────────────┘                                           └──────────────┬─────────────┘
                                                                                      │ reads files + Discord API
                                                                                      ▼
                                          ┌───────────────────────────────────────────────────────────┐
                                          │  Natro Macro instance(s) on the host PC                    │
                                          │  • settings/nm_config.ini  (status, honey, planters, cfg)   │
                                          │  • settings/debug_log.txt  (live state + field + events)    │
                                          │  • Discord bot/webhook     (report images + log embeds)     │
                                          │  • command receiver: Discord messages beginning with "?"    │
                                          └───────────────────────────────────────────────────────────┘
```

## Components

### 1. Android app (`android/`)
- **Kotlin + Jetpack Compose**, single debug APK.
- Modules/layers kept separate: `ui/` (Compose screens + theme), `data/` (Retrofit API,
  DTOs, repository), `domain/` (models + command validation), `di/` (manual wiring).
- Secure credential storage: the login **session token** is encrypted with AES-GCM via the
  Android Keystore (`KeystoreCrypto`) and stored in plain prefs; backups of that prefs file
  are excluded because the Keystore key is device-bound.
- Bridge discovery: **mDNS/NSD** (`NsdManager`) finds the bridge on the local network with
  no configuration; a Wi‑Fi multicast lock is held while scanning.
- Pairing: accepts a `macromonitor://pair?...` payload via QR scan (CameraX + ML Kit) or an
  app-link/deep intent, then claims a single-use pairing code on the bridge to obtain a
  session.
- Connectivity diagnostics: Android `ConnectivityManager` + a short HTTPS probe to
  `https://www.google.com/generate_204`; separately calls the bridge `/health`.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA` (QR), `CHANGE_WIFI_MULTICAST_STATE`
  (mDNS). No hardcoded IPs or credentials.

### 2. Windows bridge (`bridge/`)
- **Python 3.14 + FastAPI + Uvicorn**; extra deps: `python-multipart`, `argon2-cffi`,
  `zeroconf` (mDNS), `segno` (QR), `curl` via stdlib for Discord fallback.
- Binds to `0.0.0.0:8787` by default so phones on the LAN/tailnet can reach it; it is never
  port-forwarded to the public internet. Bind address/port are config-driven (`.env`).
- **Auth (v0.2.0)**: username/password login with **Argon2id** (scrypt fallback) → short-lived
  bearer **session token** (absolute + idle TTL, revocable, persisted). Login is rate-limited
  (in-memory window + lockout). Optional **pairing** grants a session from a single-use,
  hashed code (printed as a QR). No shared static API token.
- Versioned REST API under `/api/v1`; documented in `API.md`. `/health` and `/api/v1/info`
  are public (no secrets); everything else requires a session.
- **Adapter boundary**: all Natro knowledge lives in `bridge/app/natro/`; the API layer and
  models are Natro-agnostic so a future web dashboard can reuse them.
- Structured logging with secrets redacted; bounded responses; request timeouts.
- CLI (`python -m app.cli`): `create-user`, `reset-password`, `list-users`, `pair`, `info`.
- Windows auto-start: `setup.ps1` registers a `MacroMonitorBridge` scheduled task + a
  Private/Domain firewall rule; `uninstall.ps1` removes them without touching Natro data.

### 3. Natro data adapters (`bridge/app/natro/`)
Real, verified sources on the host (paths are per-account, configured, never hardcoded):

| Data | Source | Notes |
|------|--------|-------|
| Session honey, honey/hour | `nm_config.ini [Status]` `SessionTotalHoney`, `HoneyAverage` | Written by StatMonitor hourly |
| Total runtime / disconnects / planters / boss/bug kills | `nm_config.ini [Status]` | Real counters |
| Configured fields | `nm_config.ini [Gather]` `FieldName1..3`, `CurrentFieldNum` | |
| Blue Field Booster state | `nm_config.ini [Boost]` `<Field>BoosterCheck` (+ `AutoFieldBoost*`) | |
| Real planters + ETA | `nm_config.ini [Planters]` `PlanterName1..3`, `PlanterField1..3`, `PlanterHarvestTime1..3`, `PlanterEstPercent1..3` | |
| **Live state + current field + event log** | `settings/debug_log.txt` lines `[MM/DD][HH:MM:SS] State: Detail` | Written by `Status.ahk:685` |
| Bot token / channel ids | `nm_config.ini [Status]` `BotToken`, `MainChannelID`, `ReportChannelID`, `DiscordMode`, `commandPrefix` | Secrets — never logged |
| Hourly report images + log embeds | Discord REST API (`/channels/{id}/messages`) via the account's bot token | Real attachments & embeds |

**Macro state** is derived from the tail of `debug_log.txt`:
`End: Macro` / `Startup: UI` → **stopped**; `Paused: ...` → **paused**;
`Begin: Macro`, `Gathering: ...`, `Traveling: ...`, etc. → **running**.
Each response carries `last_updated` + `stale` (if the log/file has not changed recently).

### 4. Command dispatch
Natro's supported command receiver is its Discord bot: `Status.ahk` calls
`discord.GetCommands(MainChannelID)` and enqueues any message whose content starts with the
configured `commandPrefix` (default `?`). Its own start/pause/stop path is
`PostMessage 0x5550 {1,2,3}` to the macro window.

The bridge's `POST /api/v1/accounts/{id}/commands` endpoint:
1. Validates the command against a strict allowlist (`?start`, `?stop`, `?pause`, `?resume`,
   `?planters`, `?timers`, `?status`, `?help`) — arbitrary input is rejected, never shelled.
2. Dispatches via the **Discord transport**: posts `?<cmd>` to that account's command channel
   using its bot token — exactly the message shape Natro already listens for.
3. Returns `accepted`/`rejected`/`failed` with a correlation id.

> **Known limitation:** two instances that share both bot token *and* channel id (e.g. 67 & 69)
> cannot be individually addressed through the Discord receiver — a posted command may be
> consumed by either. The bridge detects shared (token, channel) pairs and marks those accounts
> with `command_ambiguous: true`; commands to them are refused unless explicitly overridden.

### 5. Network & security model
- LAN-first with optional Tailscale for off-LAN. No public port-forwarding; the bridge
  firewall rule is scoped to the **Private + Domain** profiles only.
- Unauthenticated endpoints are limited to a bare liveness `/health` and `/api/v1/info`
  (no secrets). Every other route requires a valid session bearer token.
- Passwords are hashed with Argon2id (memory-hard) and never stored or logged in plaintext;
  on successful login a weak/scrypt-hashed password is transparently rehashed.
- Session tokens are random, compared in constant time, expire (absolute + idle), can be
  revoked (logout / password change revokes siblings), and are persisted with restrictive
  file perms.
- Login and pairing are rate-limited/locked out to resist brute force; pairing codes are
  salted-hashed, single-use, and TTL-bound.
- The device stores only the session token, encrypted with the Android Keystore.
- Command input is validated against an allowlist and scoped to one account id.
- Attachment proxying validates URL host/size/type; no arbitrary URL fetching.

## Data flow (live status)
1. Android calls `GET /api/v1/accounts/{id}/status` with bearer token.
2. Bridge reads the account's `nm_config.ini` + tails `debug_log.txt`, normalizes to a
   `StatusResponse`, and returns it with `source` + `stale` flags.
3. If a value cannot be sourced (e.g. OCR-disabled honey), the field is returned as
   `null` with a documented reason — never faked.

## Future: PC web dashboard (out of scope for v1)
The bridge API is UI-independent and versioned, so a browser client can be added later without
rewriting the bridge. Do not build it now.

## License / attribution
This project is MIT-licensed (see `LICENSE`); third-party components are listed in `NOTICE`.
Natro Macro is GPL-3.0-or-later. Macro Monitor reads Natro's output files and uses its public
Discord integration; it does not copy Natro source into this project. Game icons/logos are
linked from the Bee Swarm Simulator wiki per their terms.
