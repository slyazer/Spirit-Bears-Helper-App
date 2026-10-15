# Spirit Bear's Helper App

A private Android companion + Windows bridge for monitoring and commanding your own
**Natro Macro** instances (Bee Swarm Simulator) over your LAN or a Tailscale network.

**No cloud, no accounts on a server.** Everything runs on your PC; the phone talks only to
your bridge on the local network. Nothing is phoned home, and no game/account data is collected.

## Features
- Real-time status: current activity, field timer (`elapsed / limit`), planters + ETAs, honey metrics
- One-card dashboard with real Bee Swarm Simulator sprites (no emoji stand-ins)
- Reports & logs fetched from Discord, with inline images and a pinch-to-zoom viewer
- Command allowlist with confirmation (`?start`, `?stop`, `?pause`, `?resume`, `?close`, `?timers`)
- Field-aware staleness and converting-aware grace (no false "not updating" alerts)
- Multi-instance support (single owner, many Natro instances) with an account switcher
- Session vs all-time honey, and a "stale" indicator when the macro stops writing updates

## Security (v0.2.0)
- **Username + password login** (Argon2id hashing) instead of a shared static token
- Short-lived, revocable **session tokens** (absolute + idle expiry)
- **QR pairing** with single-use, expiring codes for one-tap setup
- **mDNS discovery** so the phone finds the bridge on Wi‑Fi with no manual address
- Login **rate limiting / lockout** against brute force
- The phone stores the session token **encrypted with the Android Keystore**
- The bridge is bound to your LAN and never exposed to the public internet by default

See [SECURITY.md](SECURITY.md) and [PRIVACY.md](PRIVACY.md).

## Getting started

### 1. Windows bridge
```powershell
powershell -ExecutionPolicy Bypass -File .\setup.ps1
```
The wizard installs the Python environment, creates your login account, adds a firewall rule
(LAN/Tailscale only), registers an auto-start task, and prints a **QR pairing code**.
Full details: [SETUP_WINDOWS.md](SETUP_WINDOWS.md).

### 2. Android app
Build the APK, install it, then tap **Scan the QR code** (or use *Find nearby* / *Enter
details* and sign in). Full details: [SETUP_ANDROID.md](SETUP_ANDROID.md).

### 3. Remote access (optional)
Install [Tailscale](https://tailscale.com/) on the PC and phone and use the PC's Tailscale
address. **Do not** port-forward the bridge to the public internet.

## Repository layout
- `android/` — Kotlin + Jetpack Compose app
- `bridge/` — Python FastAPI Windows bridge (Natro adapters, auth, REST API)
- `natro_reference/` — unmodified reference copies of Natro (not for commit in typical forks; see `.gitignore`)
- `setup.ps1` / `uninstall.ps1` — Windows installer / remover
- `.env.example`, `API.md`, `ARCHITECTURE.md`, `SETUP_WINDOWS.md`, `SETUP_ANDROID.md`, `BUILD_STATUS.md`

## Documentation
- [API.md](API.md) — REST API reference (auth, accounts, status, reports, logs, planters, commands)
- [ARCHITECTURE.md](ARCHITECTURE.md) — how the app, bridge, and Natro instances fit together
- [RELEASE.md](RELEASE.md) — how to version, build, sign, verify, and distribute a release
- [CHANGELOG.md](CHANGELOG.md) — release history
- [BUILD_STATUS.md](BUILD_STATUS.md) — current implementation state
- [SECURITY.md](SECURITY.md) · [PRIVACY.md](PRIVACY.md) · [NOTICE](NOTICE) (third-party notices)

## Requirements
- Windows PC with **Python 3.10+** and Natro Macro installed
- Android phone on **Android 8.0+** (API 26)
- Discord bot token configured in Natro (used for reports, logs, and commands)

## License & attribution
Licensed under the [MIT License](LICENSE). Third-party components and their licenses are
listed in [NOTICE](NOTICE). Natro Macro is GPL-3.0-or-later; this project reads Natro's
output files and uses its public Discord integration but does **not** copy Natro source.
Game icons/logos are used from the Bee Swarm Simulator wiki per their terms.
