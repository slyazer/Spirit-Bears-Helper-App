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

### Transport (HTTP vs HTTPS)
By default the bridge speaks **plain HTTP** and the app permits **cleartext** to it. This is
acceptable only because the intended transport is a trusted private LAN or a Tailscale/WireGuard
tailnet (already encrypted end-to-end). On an untrusted network a sniffer could read the login
password and session token.

The Android network security config permits cleartext **globally** rather than per host because
the app connects to **IP literals** (`192.168.x.x`, `100.x.x.x` tailnet, `127.0.0.1`), and Android
cannot scope a network security policy to an IP literal - only to domain names.

TLS is available **on the bridge**: supply **both** `MM_TLS_CERT_FILE` and `MM_TLS_KEY_FILE`
(both must exist and be readable, otherwise startup fails with a clear error) and `run.py`
serves HTTPS. Setting only one of the two logs a warning and falls back to plain HTTP.

> **TLS is server-side only for now.** The app can reach an `https://` address **typed in
> manually** at sign-in, but **mDNS discovery and QR pairing are not TLS-ready end-to-end**:
> the discovery result and the QR payload carry an `http://` URL, so those two paths work only
> over plain HTTP. Because the app connects to IP literals (not hostnames), a self-signed
> certificate must also be trusted on the phone. The supported default therefore remains HTTP
> on a trusted LAN/tailnet; use TLS only if you terminate it yourself and enter the address by
> hand. This extends but does not remove the cleartext caveat above.

## On-device (Android)
- The session token is encrypted with **AES-GCM** using an **Android Keystore** key
  (device-bound, non-exportable) — not stored in plaintext and not recoverable from backups.
- Backup/transfer rules exclude the prefs file holding the token.
- Camera permission is requested only for QR pairing; the mDNS scan uses a Wi-Fi multicast
  lock and does not send data off-device.

## Roblox population capture (read-only)
- A separate Windows agent (`tools/roblox-population-capture.ps1`) opens the owner's
  private-servers page in the **existing, already signed-in** browser, screenshots the
  primary monitor, and OCRs only the two population integers. It never authenticates to
  Roblox, never reads `.ROBLOSECURITY` or cookies, and never drives the session.
- The screenshot is written to the temp directory and **deleted on every path** (success
  and failure). It is never uploaded, logged, or retained.
- Only the two integers (`playing`, `max_players`) are ever sent to the bridge. Player
  names, ids, tokens, and access codes are neither parsed nor transmitted; OCR text is
  treated as untrusted and validated before use.
- The agent signs in as a **dedicated** bridge account, never your owner login. Its
  password lives in `bridge/config/roblox_capture.json`, which the setup script writes
  with an inherited-ACL-stripped, owner-only (+SYSTEM/Administrators) ACL and which is
  git-ignored.

## Secrets handling
- No API tokens or passwords are hardcoded anywhere. Configuration comes from `.env` and
  local JSON files that are git-ignored.
- `.env`, `users.json`, `sessions.json`, `pairing.json`, and `roblox_capture.json` are
  excluded from version control.
- Logs redact secrets. Bot tokens are never returned by the API.

## Command safety
- Commands are validated against a strict **allowlist**; arbitrary input is rejected and
  never passed to a shell.
- Commands are scoped to a single account id, and accounts flagged `command_ambiguous`
  (shared bot token + channel) refuse commands by default.

## Limitations
- This is a hobby project with no formal audit. Use it on a trusted network. If you need
  strong remote security, keep it behind Tailscale/VPN and do not expose the port.
