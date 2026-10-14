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
  DTOs, repository), `domain/` (models + command validation), `di/` (manual wiring or Hilt).
- Secure credential storage via `EncryptedSharedPreferences` (Android Keystore-backed).
- Connectivity diagnostics: Android `ConnectivityManager` + a short HTTPS probe to
  `https://www.google.com/generate_204`; separately calls the bridge `/health`.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE` only.
- UI is derived from `Macro_Monitor_UI_Reference.html` (branding, gradients, cards, bottom nav,
  report zoom gestures) — the HTML is the design source of truth, not shipped.

### 2. Windows bridge (`bridge/`)
- **Python 3.14 + FastAPI + Uvicorn** (verified installable on this host).
- Binds to loopback / Tailscale interface only; never `0.0.0.0` public.
- Bearer-token authentication; token stored in a local `.env`/config with restrictive perms.
- Versioned REST API under `/api/v1`; documented in `API.md`.
- **Adapter boundary**: all Natro knowledge lives in `bridge/app/natro/`; the API layer and
  models are Natro-agnostic so a future web dashboard can reuse them.
- Structured logging with secrets redacted; bounded responses; request timeouts.

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
- Remote access only over a private overlay network (Tailscale). The bridge binds to
  `127.0.0.1` by default and to the Tailscale interface when enabled.
- No public port-forwarding; no unauthenticated endpoints except a bare liveness `/health`
  (no secrets) — all `/api/v1` routes require the bearer token.
- Tokens are never returned by the API, never logged, and never committed.
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
Natro Macro is GPL-3.0-or-later. Macro Monitor reads Natro's output files and uses its public
Discord integration; it does not copy Natro source into this project. Game icons/logos are
linked from the Bee Swarm Simulator wiki per their terms.
