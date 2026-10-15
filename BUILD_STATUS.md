# BUILD_STATUS — Macro Monitor

_Last updated: 2026-10-15 (Phase 7: 5-nectar levels under Planters, `?close Roblox` target, reports row de-cluttered/centered, account switcher on every tab, 45 tests)

Phase 7 (2026-10-15):
- [x] Nectar: patched `submacros/StatMonitor.ahk` `DetectBuffs()` (live 61 + 69, backups `.bak-20261015-014903`) to write `[Status] NectarComforting/Motivating/Satisfying/Refreshing/Invigorating`. Bridge reads them into `PlantersResponse.nectars`; app renders 5 colour-coded bars under the planter chips. Empty when unpatched (no fabricated zeros). Reload requires F3→F1.
- [x] `?close` now always sent as `?close Roblox` (Natro's `close` is a no-op without a window title).
- [x] Reports list: removed the dated icon tile; row text centred.
- [x] Account switcher moved to `MainScaffold` so it stays visible on Dashboard, Reports and Logs.
- [x] Bridge tests: 45 passed. Android `compileDebugKotlin`: BUILD SUCCESSFUL. No APK rebuilt (deferred by request).

Phase 6 (2026-10-14): one-card-per-item dashboard redesign, balloon icon for Converting, launcher icon smaller, auto Wi-Fi/5G URL selection, connect screen branding, 41 tests.

Legend: [x] done & verified · [~] partial · [ ] not started · [!] blocked

## 0. Environment (verified this session)
- [x] OS: Windows (win32, PowerShell 5.1)
- [x] Python 3.14.4 at `C:\Users\67\AppData\Local\Programs\Python\Python314\python.exe`
- [x] Node v24.19.0
- [x] JDK 21 Temurin installed at `C:\Users\67\devtools\jdk-21.0.12.1+1`
- [x] Android SDK installed at `C:\Users\67\devtools\android-sdk` (platform-35, build-tools 35.0.0, platform-tools)
- [x] Gradle 8.9 (wrapper generated for the android project)
- [x] FastAPI stack installs cleanly into `bridge/.venv` on Python 3.14

## 1. Natro integration discovery (verified against reference copies)
Reference copies (safe to modify; live builds untouched):
`natro_reference/THIS ONE - 61 LEADER`, `natro_reference/THIS ONE - 67 FOLLOWER`
- [x] Macro state model: `MacroState` 0=stopped, 1=paused, 2=running (natrio_macro.ahk:2076)
- [x] Live state + event log: `settings/debug_log.txt`, written by `Status.ahk:685`
- [x] Session stats persist to `nm_config.ini [Status]` (honey, rate, runtime, disconnects...)
- [x] Configured fields: `nm_config.ini [Gather]`
- [x] Planters: `nm_config.ini [Planters]` real names/fields/ETAs
- [x] Command receiver = Discord bot polling `MainChannelID`; `discordUIDCommands` empty
- [x] Command control path = `PostMessage 0x5550 {1,2,3}`
- [x] Hourly report image = Discord attachment on `ReportChannelID`
- [!] 67 & 69 share bot token + MainChannelID → command routing ambiguous (documented)
- [x] **Live patch (61 + 69, user-authorized)**: `submacros/StatMonitor.ahk` `DetectHoney()` now also writes `[Status] CurrentHoney` (`FormatNumber(current_honey)`) each OCR cycle. Backups: `StatMonitor.ahk.bak-20261013-204721` in each install. Reload requires F3→F1 (StatMonitor is a separate process).

## 2. Windows bridge (`bridge/`)
- [x] Project scaffold + config loader
- [x] Auth (bearer token, constant-time)
- [x] Natro adapters (ini, debug log, discord)
- [x] Endpoints: /health, accounts, status, reports, logs, planters, commands
- [x] Tests (41 passing, incl. integration against real reference copies)
- [x] Verified against real Natro data (status, honey, planters read correctly)
- [x] Discord transport uses Windows `curl.exe` (schannel). Python `httpx` is blocked by Cloudflare `403 error 1010` on this machine.
- [x] Hourly reports fetched end-to-end (image lives in the embed `image.url`; messages have no attachments) + image proxy verified (HTTP 200, image/png)
- [x] Status is **session-scoped** (`SessionRuntime`/`SessionDisconnects`/`SessionPlantersCollected`), never all-time totals; boost derived from `debug_log` only; stale data reports `macro_state=unknown`
- [x] `/reports` and `/logs` return `detail` (reason) on failure instead of silently empty
- [x] Auto-start: `run_bridge_guard.ps1` + Scheduled Task `MacroMonitorBridge` + firewall rule TCP 8787 (verified on loopback + LAN 192.168.1.15)
- [x] **Real current honey** (`honey_total`): reads `[Status] CurrentHoney` written by the patched Natro `StatMonitor.ahk` (in-game OCR); falls back to `startup-report OCR + SessionTotalHoney` math on unpatched builds. `honey_per_hour` = `[Status] HoneyAverage` (the F5 Timers "Per Hour" cell); `honey_session` = `[Status] SessionTotalHoney`.
- [x] **Field timer**: `/status` now returns `field_since` (when the current gather started, reset on every fresh gather even into the same field) and `field_limit_seconds` (parsed from `Limit MM:SS`), so the app shows `elapsed / limit`.
- [x] **Field-aware staleness**: a long gather (which writes no log lines for its whole duration) is no longer flagged stale; the gather's scheduled end (`field_since + limit`) is treated as the last expected update, with the 180s grace on top.
- [x] **Activity-log filtering**: recurring `Current Honey/Pollen` snapshots are dropped from `/logs` (over-fetches to still return `limit` real macro actions).
- [x] Command allowlist now includes `close` (Natro `close` command).
- [x] **Current activity label**: `/status` now returns `activity` + `activity_detail` derived from the newest `debug_log` line (`Converting`, `Traveling`, `Resetting`, `Planting`, `Collecting`, `Fighting`, `Starting`, …). While the macro is at the hive converting, the app shows "Converting" (with the detail, e.g. `Balloon`) instead of the last-gathered field, and never looks idle while busy.
- [x] **Converting-aware staleness**: states where Natro is legitimately quiet (Converting 420s, Traveling/Resetting 240s, Placing/Collecting/Holding/Attacking 300s) get a wider grace window, so the "not sending updates" banner no longer fires during a balloon refresh / conversion wait. When genuinely stale, `field` **and** `activity` are dropped so the app shows only the stale banner (no stale card).
- [x] `config/accounts.json` now lists only `leader61` + `follower69` (the unused PC 67 option was removed); follower69 keeps `command_ambiguous` so its shared-token safeguard is preserved.

## 3. Android app (`android/`)
- [x] Toolchain (JDK21 + Android SDK) installed
- [x] Gradle + Compose scaffold (Kotlin 2.0.21, AGP 8.7.3, compileSdk 35, minSdk 26)
- [x] UI (dashboard, reports + detail/zoom, logs, connect, plain-text prefs)
- [x] Networking (Retrofit + Gson, bearer auth interceptor, connectivity diagnostics)
- [x] Account switcher on the Dashboard (jidozacac_1 PC 61 / slyazer PC 69), with "shared" tag for ambiguous accounts
- [x] Stale banner ("macro not sending updates") + reports/logs error `detail` banner (now field-aware; no false positives during long gathers)
- [x] Responsive fixes: removed fixed card heights (`heightIn(min=)`), ellipsized/wrapped text, root content boxed under the bottom nav (no overlap)
- [x] **Dashboard simplified**: only "Current honey" and "Honey per hour" metric cards (icon moved to the right, no subtitle/footer clutter); green "Macro status / PLAYING" card removed
- [x] **Current activity**: shows the actual macro action from the newest log line — the field with a live `elapsed / limit` timer while gathering, or the action itself (`Converting`, `Traveling`, `Resetting`, …) with its detail otherwise; the empty "Idle — no field detected right now." placeholder was removed and the whole card is hidden when there is genuinely nothing to show (e.g. stopped)
- [x] **Balloon icon for Converting**: downloaded the BSS wiki "Pink Balloon" sprite (`wikiassets.net/.../db68831d…webp` via the weserv proxy) as `ic_balloon.png`; `Assets.activityIcon()` maps any `Converting`/balloon activity to it, so the activity card shows a balloon while the macro is converting at the hive
- [x] **1-second ticker** for time-derived labels (field timer, planter ETAs) — numbers/timers refresh every second; network polling cadence unchanged
- [x] **Command console**: 2×3 grid (`?start ?stop ?pause ?resume ?close ?timers`) of colour-coded buttons with an "Are you sure?" confirmation dialog before sending (manual text input + history + "Command console"/"REMOTE" header removed)
- [x] App renamed **"Spirit Bear's Helper App"** (launcher label + in-app top-bar title)
- [x] **Reload button** in the top bar (next to the account name) — re-reads `accounts.json` (picks up added/renamed accounts) and refreshes all data
- [x] **Current activity** card is now colour-gradient, with only `elapsed / limit`; the subtitle under both "Current activity" and "Planters" removed; field card + planter chips enlarged
- [x] **One-card-per-item redesign (no nested boxes)**: the outer container `PanelCard` was removed from the Current activity, Planters, and Commands sections. Current activity is a single gradient card with a small "Current activity" label + the action/field and live timer; the 3 planters are standalone cards under a plain "Planters" header; the command grid sits directly on the background (all three now share the 16dp side margin used by the account chips). `FieldTile` gained `label`/`gradient`/`borderColor` params and now uses the standard 19dp card shape.
- [x] Metric-card icons (honey, honey-per-hour) doubled (30→60dp)
- [x] Planter artwork fixed for Natro's compact ids (`BlueClayPlanter`/`RedClayPlanter`/`HeatTreatedPlanter`/`PlanterOfPlenty`); chips show readable names (e.g. "Blue Clay")
- [x] **Launcher icon fix**: the bear art had transparent top/bottom bands, so the dark background showed inside the circular mask. Generated a cover-cropped `ic_launcher_bear.png` (fills the full frame) and used it full-bleed as the adaptive foreground — the bear now fills the circle with no dark rim
- [x] Real BSS assets: 51 bundled PNGs (`drawable-nodpi`), `Assets.field()`/`mob()`/`booster()`/`activity()`/`activityIcon()`/`planter()` contains-matching, no emoji fallbacks (text monogram fallback only)
- [x] Reports zoom viewer: pinch (1x–6x), pan, double-tap reset, tap-to-close, FAB Close
- [x] Logs redesigned: Discord-style accent-bar cards, inline embed/attachment images, no username line, honey/pollen noise filtered
- [x] Token/bridge URL stored in **plain text** `SharedPreferences` (persist across app updates/reinstalls); `allowBackup` + backup/transfer rules; removed the security-crypto dependency
- [x] App icon = Spirit Bear (adaptive launcher foreground, cover-cropped + full-bleed so it fills the circle; in-app top-bar logo)
- [x] Coil auth header scoped to the bridge host only (never leaks the bearer token to Discord CDN)
- [x] Debug APK built: `MacroMonitor-0.1.0-debug.apk` (20.06 MB, package com.macromonitor.app)

## 4. Documentation
- [x] ARCHITECTURE.md
- [x] API.md
- [x] SETUP_WINDOWS.md
- [x] SETUP_ANDROID.md
- [x] .env.example
- [x] Final summary

## Verified
- Bridge tests: 41 passing (incl. integration against real Natro reference copies)
- Bridge reads real data: leader61 (running, field + live timer, honey/session/rate) and follower69 (running)
- `/status` returns `activity`/`activity_detail` live (both accounts currently `activity=Gathering`); converting-state and stale-drop covered by new tests
- `/status` returns `honey_total` = current balance (from `CurrentHoney`; leader61 shows `2.056 T`, notes "read directly") plus `field_since`/`field_limit_seconds`
- Field-aware staleness verified live: leader61 gather (mtime 5 min stale) correctly reported `stale=false`; converting (5 min quiet) also `stale=false`
- `/logs` verified to drop `Current Honey/Pollen` snapshots and keep macro actions
- `/reports` returns real `[HH:00:00] Hourly Report` entries from the live Discord channel; report image proxy returns HTTP 200 `image/png`
- Live uvicorn smoke test over real HTTP: `/health` + `/api/v1/accounts` OK with auth
- Auto-start verified: bridge reachable on loopback + LAN after process kill (guard/Scheduled Task restarts it)
- Android `assembleDebug` compiles clean; APK staged at repo root (20.06 MB) — includes the one-card-per-item dashboard, `ic_balloon.png`, smaller launcher icon (16dp inset), auto Wi‑Fi/5G URL selection, and connect screen rebrand (confirmed present in the packaged APK)
- ADB install not yet performed (no phone/emulator attached) — not claimed

## Environment notes
- Repaired a corrupted base-Python stdlib file: `...\Python314\Lib\concurrent\futures\thread.py` had 103 embedded NUL bytes (broke `asyncio.to_thread`, hence all Discord calls). Replaced with the clean CPython v3.14.4 copy; backup at temp `thread.py.corrupt.bak`.

## Known limitations
- Honey/rate update per StatMonitor OCR cadence (~1 min), not continuously.
- The in-app `elapsed` timer for a field with no parsed limit shows elapsed only (no `/limit`).
- The `?close` command needs a window title argument to do anything (Natro's `close` takes the target window name); without one it is a no-op.
