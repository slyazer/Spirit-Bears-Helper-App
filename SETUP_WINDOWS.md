# SETUP — Windows bridge

Runs the Macro Monitor bridge on the same PC as Natro Macro. It reads Natro's
files and talks to Discord; it **never modifies** your Natro Macro installs.

## Prerequisites
- Python **3.10+** (verified on 3.14.4), with `Add python.exe to PATH` ticked.
- Network access to `discord.com` (for reports / logs / commands).
- Optional: [Tailscale](https://tailscale.com/) for phone access off-LAN.
- Optional: a webcam/phone camera on the phone to scan the pairing QR.

## Quick setup (recommended)

**Double-click `MacroMonitor-Setup.exe`** in this folder (next to `bridge\`).

Windows shows a **UAC prompt** asking for administrator permission — the setup needs
elevation to add the LAN firewall rule and register the auto-start task, and that is
the only reason it asks. Approve it and the wizard runs in a console window.
`MacroMonitor-Setup.exe` is a thin wrapper that runs `setup.ps1` in place, so **keep it
in the project folder** beside `setup-lib.ps1` and `bridge\`; it does not copy the
bridge anywhere.

The wizard will:

1. Locate Python and create `bridge\.venv`, installing the dependencies.
2. Write `bridge\.env` (listens on `0.0.0.0:8787` by default). On a re-run your
   existing `MM_HOST` / `MM_PORT` are **kept** unless you pass `-BindHost` / `-Port`,
   and the previous `.env` is backed up first.
3. Create your **login account** (username + password) — this replaces the old
   API token. Passwords are hashed with Argon2id and never stored in plaintext.
4. Copy `accounts.example.json` → `accounts.json` if missing (edit it to point at
   your Natro Macro folders).
5. Add an inbound firewall rule for TCP `8787` on the **Private + Domain**
   profiles only (LAN/Tailscale, never the public internet).
6. Register the `MacroMonitorBridge` auto-start scheduled task (restarts the
   bridge automatically if it ever exits).
7. Ask whether to install **Tailscale** for off-LAN access (default **No**; it is
   never installed silently — see below).
8. Start the bridge and print a **QR pairing code** — scan it with the app.

### Tailscale (off-LAN access)
Step 7 is opt-in. If you answer `y` (or pass `-InstallTailscale`), the wizard installs
the official Tailscale client — via `winget` when available, otherwise the signed MSI
from `pkgs.tailscale.com` — and then tells you to run `tailscale up` and sign in
yourself. The wizard **never logs you into a tailnet** and never installs Tailscale
without asking. Pass `-SkipTailscale` to suppress the prompt entirely.

### Administrator permission
- The firewall rule and the auto-start task are machine-level, which is why the EXE
  requests administrator rights.
- Running `setup.ps1` yourself in a **non-elevated** shell still installs everything
  else; it just skips steps 5–6 and prints the exact commands to add them.
- Prefer elevating as the **same account you log in with**; if you elevate as a
  different admin, the auto-start task is registered for that other account.

### Setup options
```powershell
# scripts (the EXE wraps the same options); advanced/manual use
.\setup.ps1 -Port 8888              # different port
.\setup.ps1 -BindHost 127.0.0.1     # loopback only (no LAN/Tailscale)
.\setup.ps1 -SkipFirewall -SkipTask
.\setup.ps1 -InstallTailscale       # install Tailscale without prompting
.\setup.ps1 -SkipTailscale          # never offer Tailscale
```

## Updating an existing install

Re-running the installer is the upgrade path. **Double-click `MacroMonitor-Setup.exe`
again** (or re-run `setup.ps1`) — it is safe:

1. It **stops** the running bridge (including any leftover process holding the
   port), refreshes the code/dependencies, then **starts** it again — so the new
   version is actually running instead of the old process lingering.
2. It **backs up** `bridge\.env` to `.env.bak-<timestamp>` before editing and
   restores it automatically if the edit fails.
3. It **keeps** your current `MM_HOST` / `MM_PORT`, accounts, and sessions. To
   deliberately change the bind address/port, pass `-BindHost` / `-Port`.

```powershell
# upgrade in place, keeping current host/port/config (same as re-running the EXE)
powershell -ExecutionPolicy Bypass -File .\setup.ps1
```

If you ran with `-NoStart`, or the wizard warned it was not elevated, restart the
task yourself (`Stop-ScheduledTask -TaskName MacroMonitorBridge` then
`Start-ScheduledTask -TaskName MacroMonitorBridge`).

## Connecting the phone

In the app, tap **Scan the QR code** and point it at the QR the wizard printed.
If you'd rather not scan:

- **Find nearby** — the app discovers the bridge automatically over mDNS on the
  same Wi‑Fi.
- **Enter details** — type the address (e.g. `http://192.168.1.100:8787`) and sign
  in with your username and password.

### Remote access off-LAN
1. Install Tailscale on the PC and the phone, log into the same tailnet.
2. Enter the PC's Tailscale address (e.g. `http://100.x.y.z:8787`).
3. **Do not** port-forward 8787 to the public internet.

## Roblox private-server population (optional)

The app can show how many players are in your Bee Swarm Simulator private server
(e.g. `3/6`). This is **read-only** and works with the browser you are already
signed into — it never asks for your Roblox password or cookie.

How it works: a small Windows agent opens the private-servers page in **Firefox**,
screenshots the screen, reads the two numbers with the built-in Windows OCR, and
sends only those two integers to the bridge.

### One-time setup
Make sure you are **signed into Roblox in Firefox** on this PC, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\roblox-population-setup.ps1 -Start
```

It will create a dedicated bridge account (`roblox-capture`, role `device`), write
`bridge\config\roblox_capture.json` with an owner-only ACL, and register the
`MacroMonitorRobloxPopulation` logon task that refreshes the population every 5
minutes. No administrator rights are needed. Re-run it any time — it is idempotent
and rotates the agent's password.

Verify with a single capture (watch it open a Firefox window, then close it):

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\roblox-population-capture.ps1 -Once
```

### Options (`roblox-population-setup.ps1`)
```powershell
-FirefoxPath "C:\Program Files\Mozilla Firefox\firefox.exe"
-IntervalSeconds 300        # how often to refresh
-VipServerId 3355460963     # your private server id (default configured)
-SkipTask                   # write config/account only, register no task
-Start                      # start the task immediately
```

### Notes & expectations
- Firefox must be **signed into Roblox** and the PC left logged in; the agent opens
  and closes its own window and never touches your other tabs.
- The capture needs the browser window to be able to come to the foreground; if the
  screen is locked or covered, that cycle is skipped and retried next interval.
- Only the two integers ever leave the PC. Screenshots are temp files deleted every
  time. See `SECURITY.md`.
- Remove the feature with `Unregister-ScheduledTask -TaskName MacroMonitorRobloxPopulation`
  and delete `bridge\config\roblox_capture.json` (and, optionally, the `roblox-capture`
  account from `bridge\config\users.json`).

## Manual setup (advanced)

```powershell
cd bridge
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
Copy-Item ..\.env.example .env          # then edit
.\.venv\Scripts\python.exe -m app.cli create-user <name>
.\.venv\Scripts\python.exe run.py
```

Useful CLI commands (run from `bridge\`):

```powershell
.\.venv\Scripts\python.exe -m app.cli create-user <name>   # add an account
.\.venv\Scripts\python.exe -m app.cli list-users           # list accounts
.\.venv\Scripts\python.exe -m app.cli reset-password <name>
.\.venv\Scripts\python.exe -m app.cli pair                 # new QR code
.\.venv\Scripts\python.exe -m app.cli info                 # config summary
```

Smoke test:
```powershell
curl http://127.0.0.1:8787/health
curl http://127.0.0.1:8787/api/v1/info
```

## Uninstall

**Double-click `MacroMonitor-Uninstall.exe`** (or run the script). It asks for
administrator permission, stops the bridge, and removes the auto-start task and
firewall rule.

```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
```

Your configuration is **preserved** by default; add `-RemoveVenv` and/or
`-PurgeConfig` for a deeper clean. Natro Macro data is never removed. Uninstalling
**does not remove Tailscale** (uninstall it from Settings → Apps if you no longer
want it).

## Troubleshooting
- **Nothing answers on `/health`** — check `bridge\logs\`. Start the bridge
  manually from `bridge\` with `.\.venv\Scripts\python.exe run.py` to see errors.
- **`/status` shows `stale: true`** — the macro isn't running / the log isn't
  updating. Stale accounts report `macro_state: "unknown"` (never "running"), and
  stats are session-scoped.
- **Login returns 401** — wrong username/password. After repeated failures the
  bridge rate-limits with `429` and a `Retry-After` header.
- **Login returns 429** — too many attempts; wait the indicated time or restart
  the bridge.
- **Commands `failed`** — no valid bot token in `nm_config.ini [Status]`, or
  Discord unreachable.
- **Reports/logs empty** — check the `detail` field in the response.
- **Discord `403 error 1010` (Cloudflare)** — Python `httpx` is blocked on this
  machine; the bridge falls back to `curl.exe` (schannel). Confirm
  `C:\Windows\System32\curl.exe` exists.
- **`SyntaxError: ... null bytes`** on Discord calls — a base-Python stdlib file
  is corrupted; repair the Python install.
