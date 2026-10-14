# Spirit Bear's Helper App

Private Android companion + Windows FastAPI bridge for monitoring and commanding Natro Macro (Bee Swarm Simulator) over Tailscale.

## Features
- Real-time status: field timer, activity (including Converting), planters, honey metrics
- One-card dashboard, real BSS sprites, balloon icon for Converting
- Auto Wi-Fi/Tailscale endpoint selection on connect
- Reports & logs with inline images
- Command allowlist with confirmation
- Field-aware staleness & converting-aware grace

## Repo layout
- ndroid/ — Kotlin + Jetpack Compose app
- ridge/ — FastAPI Windows bridge (Natro adapters, auth)
- 
atro_reference/ — reference copies (not for commit in typical forks; see .gitignore)
- docs/ — API/ARCHITECTURE/SETUP

See BUILD_STATUS.md for current state. APK: MacroMonitor-0.1.0-debug.apk (if present).
