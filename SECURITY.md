# Security

## Reporting a vulnerability
Please open a private security advisory or an issue on the repository. Do not post exploit
details publicly.

## Threat model
This is a **local/private** tool: the bridge is meant to run on your own PC and be reached
only by your own phone over a trusted LAN or Tailscale. It is **not** designed to be exposed
to the public internet, and the default configuration does not do so.

## Authentication & sessions
- Passwords are hashed with **Argon2id** (memory-hard), with a scrypt fallback. Plaintext
  passwords are never stored or logged. A weaker stored hash is transparently rehashed on a
  successful login.
- Login returns a random bearer **session token** compared in constant time.
- Sessions expire by both **absolute** and **idle** timeouts and are persisted with
  restrictive file permissions (owner-only on POSIX-like perms).
- **Logout** revokes the session; **changing your password** revokes all other sessions.
- **Login rate limiting** locks a client out after repeated failures and returns `429` with a
  `Retry-After` hint. The limiter is in-memory (per-process).

## Pairing
- Pairing codes are random, **salted-hashed** before storage, **single-use**, and TTL-bound.
- Claiming a code grants a normal session and deletes the code so it cannot be replayed.
- Codes can be regenerated/invalidated from the CLI (`python -m app.cli pair`).

## Network
- The bridge binds to the LAN (`0.0.0.0` by default) so phones can connect; it is never
  port-forwarded to the public internet by the installer.
- The Windows firewall rule created by `setup.ps1` is scoped to the **Private + Domain**
  profiles only (not Public).
- Only `/health` and `/api/v1/info` are unauthenticated, and they expose no secrets.
- Optional TLS via `MM_TLS_CERT_FILE` / `MM_TLS_KEY_FILE` if you terminate TLS yourself.

## On-device (Android)
- The session token is encrypted with **AES-GCM** using an **Android Keystore** key
  (device-bound, non-exportable) — not stored in plaintext and not recoverable from backups.
- Backup/transfer rules exclude the prefs file holding the token.
- Camera permission is requested only for QR pairing; the mDNS scan uses a Wi-Fi multicast
  lock and does not send data off-device.

## Secrets handling
- No API tokens or passwords are hardcoded anywhere. Configuration comes from `.env` and
  local JSON files that are git-ignored.
- `.env`, `users.json`, `sessions.json`, and `pairing.json` are excluded from version control.
- Logs redact secrets. Bot tokens are never returned by the API.

## Command safety
- Commands are validated against a strict **allowlist**; arbitrary input is rejected and
  never passed to a shell.
- Commands are scoped to a single account id, and accounts flagged `command_ambiguous`
  (shared bot token + channel) refuse commands by default.

## Limitations
- This is a hobby project with no formal audit. Use it on a trusted network. If you need
  strong remote security, keep it behind Tailscale/VPN and do not expose the port.
