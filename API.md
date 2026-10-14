# API — Macro Monitor Bridge v1

Base URL: `http://<host>:8787` (default loopback; use your Tailscale IP for remote access).

All `/api/v1/*` routes require a bearer token:

```
Authorization: Bearer <MM_API_TOKEN>
```

Auth failures: `401` (missing/invalid token), `503` (bridge token not configured).
Unknown account: `404`. Unsupported command: returned as `result: "rejected"` with `200`.

---

## `GET /health`
Unauthenticated liveness probe. No secrets returned.

```json
{ "status": "ok", "bridge_version": "0.1.0", "server_time": "2026-10-13T15:17:41Z", "auth": "ok" }
```

---

## `GET /api/v1/accounts`
List configured accounts.

```json
{
  "accounts": [
    { "id": "follower67", "name": "slyazer", "hive_level": null, "command_ambiguous": false }
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
  "id": "follower67",
  "name": "slyazer",
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
  "id": "follower67",
  "reports": [
    {
      "report_id": "1401...",
      "arrival_time": "2026-08-31T17:00:00Z",
      "image_url": "https://cdn.discordapp.com/...",
      "image_proxy_url": "/api/v1/accounts/follower67/reports/1401.../image",
      "author": "slyazer",
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
  "id": "follower67",
  "logs": [
    { "entry_id": "1401...:0", "timestamp": "2026-08-31T17:00:00Z",
      "title": "Session Report", "body": "...", "color": "#57F287",
      "author": "slyazer", "image_url": null }
  ]
}
```

---

## `GET /api/v1/accounts/{id}/planters`
Current planters with harvest ETA and completion estimate.

```json
{
  "id": "leader61",
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
{ "id": "follower67", "command": "start", "result": "accepted",
  "correlation_id": "optional-client-id",
  "detail": "Command posted to the macro's command channel." }
```

- `accepted`: the `?<cmd>` message was posted to the account's command channel, where
  Natro's own `GetCommands` poller picks it up.
- `rejected`: not allowlisted, or the account is `command_ambiguous`.
- `failed`: no bot token/channel configured, or Discord was unreachable.

---

## Errors & versioning
- All routes are under `/api/v1`; breaking changes will move to `/api/v2`.
- Error bodies use FastAPI's `{ "detail": "..." }` shape.
- Secrets (API token, bot tokens) are never returned.
