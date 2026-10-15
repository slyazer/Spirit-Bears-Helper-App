# Privacy

**Spirit Bear's Helper App is local-first and does not collect, transmit, or sell any data.**

## What it does
The Windows bridge runs entirely on your own PC. It reads your own Natro Macro's output
files and talks to **your own Discord bot** to fetch reports, logs, and send commands. The
Android app talks only to that bridge over your LAN or Tailscale.

## What is stored
- **On the PC:** your login username, an Argon2id password hash (never the plaintext), your
  session tokens, your Natro account paths, and normal application logs. All under `bridge/`.
- **On the phone:** the bridge address, your username, and your session token — the token is
  encrypted with the Android Keystore. These are excluded from device/cloud backups.

## What is never collected
- No analytics, telemetry, crash reporters, or advertising SDKs.
- No game screenshots, credentials, or gameplay data are uploaded anywhere.
- No personal information beyond the username you choose.
- Nothing is sent to the developer. There is no backend server of ours in the path.

## Third-party services you opt into
- **Discord** — only reached because you configured a bot token in Natro; reports/logs are
  read from your own channels.
- **Tailscale** (optional) — if you use it for remote access, your traffic is covered by
  Tailscale's own privacy policy.

## Data deletion
- Sign out in the app to revoke and clear the stored session.
- Run `uninstall.ps1` (add `-PurgeConfig`) to remove the bridge's accounts/sessions/secrets.
- Uninstall the Android app to remove its local storage.
- Natro Macro's own data is never modified or removed by this project.

## Contact
Open an issue on the repository if you have questions.
