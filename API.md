# API — Macro Monitor Bridge v1

Base URL: `http://<host>:8787` (default: all interfaces, reachable on the LAN and
your Tailscale tailnet). The bridge is **not** meant to be exposed to the public
internet.

Authentication is by **username + password** (created on the PC), which returns a
revocable session token. Send that token on every protected route:

```
Authorization: Bearer <session-token>
```

Auth failures: `401` (missing/invalid/expired session), `429` (too many login
attempts — see `Retry-After`). Unknown account: `404`. Unsupported command:
returned as `result: "rejected"` with `200`.

---

## `GET /health`
Unauthenticated liveness probe. No secrets returned.

```json
{ "status": "ok", "bridge_version": "0.2.0", "server_time": "2026-10-13T15:17:41Z", "auth": "ok" }
```

---

## `GET /api/v1/info`
Unauthenticated, secret-free service descriptor. Used by the app after LAN
discovery to show the server name before signing in.

```json
{ "name": "Macro Monitor Bridge", "bridge_version": "0.2.0",
  "pairing_enabled": true, "accounts": 2, "scheme": "macromonitor", "api_version": "v1" }
```

### Version compatibility policy

`api_version` is the contract between the Android app and the PC bridge.
`bridge_version` is informational only — a bridge can bump it without breaking
the API — so it never decides compatibility.

The app declares the one API version it was built against
(`VersionCompatibility.SUPPORTED_API_VERSION`, currently `v1`) and compares it
against `api_version` from `GET /api/v1/info` **once per connected session**:

| `api_version` from the bridge | Result    | Behaviour |
| ----------------------------- | --------- | --------- |
| equal to `v1`                 | Compatible | No notice shown. |
| different (e.g. `v2`)         | Incompatible | Persistent banner naming both versions; update the app **and** the bridge together. |
| missing / blank               | Unknown  | Non-blocking warning — likely an older bridge. Diagnostics stay reachable. |
| endpoint absent (HTTP error)  | Unknown  | Same non-blocking warning. |

The check is informational, never blocking: a version mismatch must never stop
the user from reaching status, reports, and logs to diagnose the problem.

Changing `SUPPORTED_API_VERSION` is a breaking contract change and must be made
on both sides in the same release.

---

## `POST /api/v1/auth/login`
Exchange username + password for a session token. Rate-limited per client and per
username (lockout returns `429`).

```json
{ "username": "bob", "password": "...", "device_name": "pixel-7" }
```

```json
{ "token": "<opaque-session-token>", "token_type": "Bearer",
  "username": "bob", "expires_at": "2026-11-14T15:17:41Z" }
```

## `POST /api/v1/auth/pair`
Redeem a short-lived, single-use QR pairing code for a session (no password
typing on first run). Returns the same shape as login. `401` on bad/expired/
already-used codes, `404` when pairing is disabled.

```json
{ "code": "A8EC8594", "device_name": "pixel-7" }
```

## `GET /api/v1/auth/me`
Identity + session metadata for the current token (requires a session).

## `POST /api/v1/auth/logout`
Revoke the current session token.

## `POST /api/v1/auth/password`
Change the signed-in user's password. Requires `current_password`; on success all
*other* sessions for that user are revoked. Weak passwords return `422`.

```json
{ "current_password": "...", "new_password": "..." }
```

---

## `GET /api/v1/accounts`
List configured accounts.

```json
{
  "accounts": [
    { "id": "leader", "name": "leader (PC A)", "hive_level": null, "command_ambiguous": false }
  ]
}
```

`command_ambiguous: true` means this account shares its bot token + channel with another
account, so commands can't be safely addressed to it (see Commands).

---

## `GET /api/v1/accounts/{id}/status`
Live macro state derived from `debug_log.txt` + session stats from `nm_config.ini`.

```json
{
  "id": "leader",
  "name": "leader (PC A)",
  "hive_level": null,
  "macro_state": "running",
  "field": null,
  "field_boosted": null,
  "honey_total": "2.644 B",
  "honey_per_hour": "6.189 B",
  "runtime_seconds": 138704,
  "disconnected_count": 1,
  "planters_collected": null,
  "last_updated": "2026-08-31T17:11:50Z",
  "stale": true,
  "source": "natro-files",
  "notes": null
}
```

- `macro_state`: `running` | `paused` | `stopped` | `unknown`.
- `field`: current field when known; `null` right after a run starts, or when the last
  meaningful line was not a gathering/traveling event.
- `honey_*`: hourly-cadence values written by StatMonitor; may lag up to ~1 hour.
- `stale`: `true` when the log has not changed within `MM_STALE_AFTER_SECONDS`.
- Unavailable values are `null` (never faked).

---

## `GET /api/v1/accounts/{id}/reports?limit=10`
Hourly report images, fetched from the account's Discord report channel.

```json
{
  "id": "leader",
  "reports": [
    {
      "report_id": "1401...",
      "arrival_time": "2026-08-31T17:00:00Z",
      "image_url": "https://cdn.discordapp.com/...",
      "image_proxy_url": "/api/v1/accounts/leader/reports/1401.../image",
      "author": "leader (PC A)",
      "summary": null
    }
  ]
}
```

Use `image_proxy_url` so the app fetches images through the authenticated bridge
(the raw `image_url` is a short-lived signed Discord URL).

---

## `GET /api/v1/accounts/{id}/reports/{report_id}/image`
Proxied image bytes (`image/png`). Requires the bearer token. `404` if not found.

---

## `GET /api/v1/accounts/{id}/logs?limit=30`
Recent status embeds/messages from the account's command channel.

```json
{
  "id": "leader",
  "logs": [
    { "entry_id": "1401...:0", "timestamp": "2026-08-31T17:00:00Z",
      "title": "Session Report", "body": "...", "color": "#57F287",
      "author": "leader (PC A)", "image_url": null }
  ]
}
```

---

## `GET /api/v1/accounts/{id}/planters`
Current planters with harvest ETA and completion estimate.

```json
{
  "id": "leader",
  "available": true,
  "reason": null,
  "planters": [
    { "slot": 1, "name": "PesticidePlanter", "field": "Pineapple",
      "harvest_eta": "2026-10-13T15:52:54Z", "est_percent": 10.8 }
  ]
}
```

`harvest_eta` is derived from Natro's Unix `PlanterHarvestTime{n}`. When no planters are
reported, `available` is `false` and `reason` explains why.

---

## `POST /api/v1/accounts/{id}/commands`
Dispatch an allowlisted command to one account.

Request:

```json
{ "command": "start", "correlation_id": "optional-client-id" }
```

`command` may be given with or without the configured prefix (`?` is optional).

Allowed: `start`, `stop`, `pause`, `resume`, `planters`, `timers`, `status`, `help`.
Anything else → `result: "rejected"`. Arbitrary input is never shelled or forwarded.

Response:

```json
{ "id": "leader", "command": "start", "result": "accepted",
  "correlation_id": "optional-client-id",
  "detail": "Command posted to the macro's command channel." }
```

- `accepted`: the `?<cmd>` message was posted to the account's command channel, where
  Natro's own `GetCommands` poller picks it up.
- `rejected`: not allowlisted, or the account is `command_ambiguous`.
- `failed`: no bot token/channel configured, or Discord was unreachable.

---

## `GET /api/v1/roblox/population`
Latest known population of the owner's Roblox private server. Read-only, global
(not per-account). Populated by the Windows capture agent — see
`tools/roblox-population-capture.ps1` and `SETUP_WINDOWS.md`.

```json
{
  "available": true,
  "playing": 3,
  "max_players": 6,
  "captured_at": "2026-10-18T12:00:00+00:00",
  "received_at": "2026-10-18T12:00:01+00:00",
  "age_seconds": 12,
  "stale": false,
  "source": "roblox-web-screenshot",
  "detail": null
}
```

- `available: false` (with `playing`/`max_players` `null`) until the first capture
  succeeds; `detail` explains the wait.
- `stale: true` when the newest capture is older than
  `MM_ROBLOX_POPULATION_STALE_SECONDS`. The last-known value is still returned so
  the app can show it dimmed — it is never presented as current.
- Only the two integers are ever present. No player names, ids, tokens, access
  codes, or screenshots are stored or returned.

## `POST /api/v1/roblox/population`
Ingest a validated population sample. Used by the capture agent; requires a
session. The body is validated (both fields integers, `0 <= playing <= max_players`,
plausible bounds) and rejected with `422` otherwise.

```json
{ "playing": 3, "max_players": 6, "captured_at": "2026-10-18T12:00:00+00:00" }
```

Returns the same shape as `GET`. `captured_at` is optional.

---

## Errors & versioning
- All routes are under `/api/v1`; breaking changes will move to `/api/v2`.
- Unauthenticated routes: `GET /health`, `GET /api/v1/info`, `POST /api/v1/auth/login`,
  `POST /api/v1/auth/pair`. Everything else requires a session.
- Error bodies use FastAPI's `{ "detail": "..." }` shape.
- Secrets (passwords, session tokens, bot tokens) are never returned or logged.
