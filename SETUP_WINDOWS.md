# SETUP — Windows bridge

Runs the Macro Monitor bridge on the same PC as Natro Macro. It reads Natro's
files and talks to Discord; it **never modifies** your Natro Macro installs.

## Prerequisites
- Python **3.10+** (verified on 3.14.4), with `Add python.exe to PATH` ticked.
- Network access to `discord.com` (for reports / logs / commands).
- Optional: [Tailscale](https://tailscale.com/) for phone access off-LAN.
- Optional: a webcam/phone camera on the phone to scan the pairing QR.

## Quick setup (recommended)

Open PowerShell in this folder and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\setup.ps1
```

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
7. Start the bridge and print a **QR pairing code** — scan it with the app.

> Steps 5–6 need an elevated (Administrator) PowerShell. Without elevation the
> wizard still installs everything else and prints the exact commands to add the
> firewall rule / task yourself.

### Setup options
```powershell
.\setup.ps1 -Port 8888            # different port
.\setup.ps1 -BindHost 127.0.0.1   # loopback only (no LAN/Tailscale)
.\setup.ps1 -SkipFirewall -SkipTask
```

## Updating an existing install

Re-running `setup.ps1` is the upgrade path and is safe:

1. It **stops** the running bridge (including any leftover process holding the
   port), refreshes the code/dependencies, then **starts** it again — so the new
   version is actually running instead of the old process lingering.
2. It **backs up** `bridge\.env` to `.env.bak-<timestamp>` before editing and
   restores it automatically if the edit fails.
3. It **keeps** your current `MM_HOST` / `MM_PORT`, accounts, and sessions. To
   deliberately change the bind address/port, pass `-BindHost` / `-Port`.

```powershell
# upgrade in place, keeping current host/port/config
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

```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
```

Removes the auto-start task and firewall rule. Your configuration is **preserved**
by default; add `-RemoveVenv` and/or `-PurgeConfig` for a deeper clean. Natro
Macro data is never removed.

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
