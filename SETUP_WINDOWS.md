# SETUP — Windows bridge

Runs the Macro Monitor bridge on the same PC as Natro Macro. It reads Natro's files and
talks to Discord; it never modifies your Natro installs.

## Prerequisites
- Python 3.11+ (verified on 3.14.4). On this PC:
  `C:\Users\67\AppData\Local\Programs\Python\Python314\python.exe`
- Network access to `discord.com` (for reports/logs/commands).
- Optional: Tailscale for phone access off-LAN.

## 1. Create the venv and install deps
```powershell
cd bridge
& "C:\Users\67\AppData\Local\Programs\Python\Python314\python.exe" -m venv .venv
& ".\.venv\Scripts\python.exe" -m pip install -r requirements.txt
```

## 2. Configure environment
```powershell
Copy-Item ..\.env.example .env
# then edit .env:
#   MM_API_TOKEN=<long random token>
#   MM_HOST=127.0.0.1        (or your Tailscale IP)
#   MM_ACCOUNTS_FILE=config/accounts.json
```
Generate a token:
```powershell
& ".\.venv\Scripts\python.exe" -c "import secrets; print(secrets.token_urlsafe(48))"
```
Lock down secrets:
```powershell
icacls .env /inheritance:r /grant:r "$env:USERNAME:R"
```

## 3. Define your accounts
```powershell
Copy-Item config\accounts.example.json config\accounts.json
```
Edit it so each `natro_dir` points at a real Natro Macro folder (the folder that contains
`settings\nm_config.ini`). Bot tokens are read from each install's ini — do **not** put
tokens here.

> If two accounts share the same bot token **and** command channel, set
> `"command_ambiguous": true` on each so the bridge refuses to send ambiguous commands.

## 4. Run
```powershell
& ".\.venv\Scripts\python.exe" run.py
```
Smoke test:
```powershell
curl http://127.0.0.1:8787/health
curl -H "Authorization: Bearer <token>" http://127.0.0.1:8787/api/v1/accounts
```

## 5. Remote access (phone)
1. Install Tailscale on the PC and phone, log into the same tailnet.
2. Set `MM_HOST` to the PC's Tailscale IP (e.g. `100.x.y.z`), or keep `127.0.0.1` and use
   `tailscale serve`/SSH tunnels.
3. Do **not** port-forward 8787 to the public internet.
4. Enter `http://<tailscale-ip>:8787` and the `MM_API_TOKEN` in the Android app.

## 6. Autostart (already configured — do not repeat)
The bridge is installed with the **`MacroMonitorBridge`** Scheduled Task (trigger: At log on,
run hidden) which launches `bridge\run_bridge_guard.ps1`. The guard starts the bridge and
**automatically restarts it if it ever exits**, so no manual start/restart is needed.

Already done on this machine:
- `bridge\.env` created → bind `0.0.0.0:8787`, real `MM_API_TOKEN`.
- `bridge\config\accounts.json` → point at the live installs (61 leader, 67 follower, 69 follower;
  67/69 are `command_ambiguous` because they share one bot token + channel).
- Windows Firewall inbound allow rule **"Macro Monitor Bridge"** TCP **8787** (Private + Domain
  profiles only — the bridge is reachable on the LAN/Tailscale, not the public internet).

To (re)build it from scratch:
1. `schtasks /create /tn MacroMonitorBridge ...` or `Register-ScheduledTask` with
   action `powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File
   <repo>\bridge\run_bridge_guard.ps1`, working dir = `bridge`, trigger "At log on".
2. Add firewall rule:
   `netsh advfirewall firewall add rule name="Macro Monitor Bridge" dir=in action=allow protocol=TCP localport=8787 profile=private,domain`
   (run from an elevated prompt).

Check it is up: `curl http://127.0.0.1:8787/health`. Logs land in `bridge\logs\`.

## Troubleshooting
- `503 Bridge API token is not configured` → set a real `MM_API_TOKEN`.
- `/status` shows `stale: true` → the macro isn't running / log isn't updating. Status now reports
  `macro_state: "unknown"` (not "running") for stale accounts, and stats are **session-scoped**
  (they never show all-time totals).
- Commands `failed` → no valid bot token in `nm_config.ini [Status]`, or Discord unreachable.
- Reports/logs empty → check the `detail` field in the response; it now carries the reason
  (missing channel config, or `Discord API <status>`).
- **Discord `403 error 1010` (Cloudflare)** → Python `httpx` is blocked on this machine; the bridge
  calls `curl.exe` (schannel) instead. If Discord calls start failing, confirm
  `C:\Windows\System32\curl.exe` exists and that the base Python install is intact (see below).
- **`SyntaxError: source code string cannot contain null bytes`** on Discord/threaded calls →
  a base-Python stdlib file is corrupted. This build repaired
  `...\Python314\Lib\concurrent\futures\thread.py` (had embedded NUL bytes). To verify:
  `python -c "import concurrent.futures.thread"`. If it errors, reinstall/repair Python 3.14.
