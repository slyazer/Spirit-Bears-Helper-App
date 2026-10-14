# OpenCode implementation brief — Macro Monitor

You are the lead engineer implementing **Macro Monitor**, a private Android companion app for monitoring and controlling the user's own Natro Macro instances running Bee Swarm Simulator on a Windows PC. The supplied `macro_monitor_prototype.html` is the visual and interaction reference. **Read it first and preserve its visual design and interactions. Do not replace it with a generic dashboard.** The prototype currently contains sample values, simulated commands, and generated report previews; the goal is to turn it into a real, secure, tested application.

## 0. Work style and deliverables

1. Inspect the workspace, operating system, installed SDKs, current Natro Macro files, and the supplied HTML before choosing an implementation. Do not assume a repository path; locate it safely and ask only if the required files genuinely cannot be found.
2. Start with a concise plan, then implement rather than stopping at a plan. Keep a `BUILD_STATUS.md` checklist updated. Use small, reviewable changes and run the relevant tests/build after each stage.
3. Create a project-root `AGENTS.md` containing durable build commands, architecture, security requirements, and testing instructions. OpenCode supports project guidance in `AGENTS.md`; use it rather than relying on this prompt alone.
4. Deliver source code, backend, Android app, configuration examples, setup instructions, troubleshooting guide, and a debug APK if the installed Android SDK permits a build. Do not claim a successful build unless the build command actually succeeds.
5. Preserve the supplied UI as the design source of truth. It is okay to refactor the HTML into reusable components or a WebView frontend if that best preserves the design, but document the trade-off. Prefer a maintainable implementation over a giant single file in the final product.
6. Do not hardcode secrets, credentials, public IP addresses, or personal account tokens. Include `.env.example` or equivalent with placeholders only, and keep secrets out of Git.
7. When a requested integration is not supported by Natro's real interfaces, explain the limitation and implement the safest adapter possible. Never fake live data or mark a simulated command as successful.

## 1. Product goal and deployment layout

The user wants to monitor Natro Macro remotely from an Android phone, including while away from home on mobile data. The Windows PC that runs Natro Macro and MuMu Player is the **host PC**. A separate PC is used for ordinary editing/VS Code; Android Studio and Android Platform Tools are installed on the host PC. The app is private for v1; architecture should not prevent adding other authorized users later.

Preferred architecture, after inspecting the workspace:

- **Android app:** native Kotlin app (Jetpack Compose is preferred) or a carefully justified WebView shell that uses the supplied HTML UI. It must behave well on Android phones, support network checks, secure credentials, and touch gestures.
- **Windows bridge/backend:** a small local service on the host PC, preferably Python + FastAPI unless the workspace or installed tooling makes another stack clearly better. It reads real Natro status/log/report data and dispatches only explicitly allowed commands through a supported local integration.
- **Remote access:** use a private overlay network such as Tailscale between the phone and host PC. Do **not** expose the backend to the public internet by opening a router port or binding an unauthenticated service to `0.0.0.0`. Bind to loopback or the private interface as appropriate, require authentication, and document the exact private-network setup. The app must work on Wi-Fi and mobile data when the phone's private tunnel is connected.
- Keep an adapter boundary between the app and Natro. The app must not depend on undocumented assumptions scattered through UI code.

If the repository or installed tools strongly favor another architecture, explain why in `ARCHITECTURE.md` before implementing it. The app must be a real build, not merely a web page that continues to simulate everything.

## 2. Visual identity and responsive UI

Use the supplied `macro_monitor_prototype.html` as the reference and verify the final layout on narrow Android phone widths as well as desktop browser preview.

### Branding and assets

- Product name: **Macro Monitor**.
- Logo: the user's chosen **Spirit Bear** close-up from the Bee Swarm Simulator wiki. Source page: `https://bee-swarm-simulator.fandom.com/wiki/File:Spiritcloseup.png`. Resolve the actual image URL from that wiki file page or its `Special:FilePath` route; do not use an unrelated placeholder if it fails. Provide a graceful fallback and preserve asset attribution/licensing requirements.
- Current honey card: actual BSS Honey image supplied by user: `https://static.wikia.nocookie.net/bee-swarm-simulator/images/c/c6/Honey.png/revision/latest?cb=20230410071605`. Golden/amber gradient.
- Honey/hour card: **Gifted Diamond Bee**, not Buoyant Bee, because this card uses a cyan/blue gradient. User-provided image: `https://static.wikia.nocookie.net/bee-swarm-simulator/images/b/bb/Gifted_Diamond_Bee.png/revision/latest?cb=20200404001105`.
- Field icons, all real BSS wiki assets:
  - Pine Tree Forest: `https://static.wikia.nocookie.net/bee-swarm-simulator/images/d/db/Pine_Tree_Forest_Icon.png/revision/latest?cb=20230410080237`
  - Blue Flower Field: `https://bee-swarm-simulator.fandom.com/wiki/Special:FilePath/Blue_Flower_Field_Icon.png`
  - Bamboo Field: `https://static.wikia.nocookie.net/bee-swarm-simulator/images/b/b0/Bamboo_Field_Icon.png/revision/latest?cb=20230410080335`
- Planters: use real icons for Plastic Planter, Blue Clay Planter, and Candy Planter (or the actual planters supported by the user's Natro config) from the BSS wiki. Confirm URLs/files resolve. Do not invent icons and do not use AI-generated assets in place of these game assets.
- Wealth Clock / buff icons: inspect the user's actual Natro Macro source and bundled images, especially `submacros/StatMonitor.ahk` and `nm_image_assets/statmonitor/bitmaps.ahk`. The current official source has `StatMonitor.ahk` including the StatMonitor bitmap asset file and maps the clock buff under `clock` / `pBMclock`; verify the exact current implementation in the local source instead of relying on this note alone. Prefer reusing the actual Natro-rendered hourly report image for reports rather than recreating Natro's graphics.
- Use a consistent text color on all gradient cards (white/off-white with accessible contrast). Card interiors need visible multi-stop gradients even in mobile Chrome; specify explicit CSS colors rather than relying on unsupported effects. Keep a polished midnight/navy background, subtle borders, and rich but controlled amber/cyan/purple card gradients.
- Make all game icons a little larger than the early prototype. Images sit on the **right side of their stat card**, not as tiny decorations. In the Macro Status card, the running/paused/stopped glyph must be vertically centered on the **right side** of the card, not pinned to the top-right corner. Use clear generic interface glyphs: play triangle = running, pause bars = paused, square = stopped. Keep the label/status text on the left.
- Dashboard, Reports, and Logs navigation buttons and account selector must be large, high-contrast, touch-friendly controls. Respect Android safe areas and scrolling.

## 3. Dashboard requirements

The dashboard should remain focused and not be crowded with stats that already appear in hourly reports.

- Account dropdown at the top, never tabs for accounts. Show account name and editable hive level in the label, e.g. `slyazer · Hive Lv. 20` and `jidozacac_1 · Hive Lv. 15`.
- Include an obvious edit action for hive level. Persist the chosen level across app restarts. Support other account entries, and allow account names / levels to be configured instead of hardcoded into production. Seed the two known defaults above; keep other sample accounts clearly identified as sample configuration until the user confirms their real account names and levels.
- Current honey card: BSS Honey icon, amber/gold gradient, large value.
- Honey per hour card: Gifted Diamond Bee icon, cyan gradient, large value.
- Macro status card: purple gradient, status text, status icon on the right at mid-height. Do not use the icon as a top-right badge.
- Current activity: compact card showing **only the current field being macroed** and a small `BOOSTED` label only when Natro's Blue Field Booster checkbox/state is actually enabled. If not boosted, show no extra label. Do not add boss kills, buffs, disconnect count, or other hourly-report stats to this dashboard.
- Planters: include a compact strip of real BSS planter icons. Show useful current planter state/timer only when the backend can obtain it; otherwise display a clearly marked “Not connected” / “Unavailable” state, never pretend sample timers are live.
- Latest hourly report: show only the **latest actual hourly report image** in this card. Tapping it opens a full-screen or near-full-screen image viewer.
- Command console: small input and Send button; send immediately without a confirmation modal. Include quick/pinned commands, command history, and a clear success/failure result. Initial allowlist candidates: `?start`, `?stop`, `?pause`, `?resume`, `?planters`, `?timers`, `?status`, `?help`. Verify which commands really exist in the user's actual command receiver before enabling them. Unknown commands should be rejected, not sent as arbitrary shell commands. Route each command to the currently selected account/instance.

## 4. Hourly reports and image viewer

- **Use actual images attached to hourly Natro StatMonitor webhook reports**. Inspect the local Natro source and the webhook payload / Discord message format to learn exactly how reports are generated and sent. Do not keep the SVG sample chart as if it were real data. If the existing Discord/webhook message contains an attachment URL, store the URL/attachment metadata and display that image. If the image is only created locally, adapt the source in a maintainable way or use a supported local bridge to retrieve it. Respect Natro's GPL license and attribution if any source code is reused.
- Dashboard displays only the newest report image and its arrival date/time. Tap image to open viewer.
- Reports tab is a simple newest-first chronological list. Each row shows **only arrival time and date**, plus a subtle chevron; no honey, field, period, or other stats in the list. A report received at 21:00 is labelled `21:00` even if it covers 20:00–21:00.
- Tapping a row opens a detail screen with the actual report image, zoom gestures, and the report's recorded data/graph if available. Keep the X/close action in the top-right and a clear back action to the report list.
- Touch zoom on all report images: pinch with two fingers to zoom, double-tap to zoom/reset, drag to pan when zoomed. No dedicated plus/minus zoom buttons. Support desktop wheel/trackpad when using browser preview. Make the gesture implementation reliable on Chrome for Android and avoid breaking normal page scroll outside the viewer.
- Do **not** enable image zoom on the Logs screen.

## 5. Webhook logs

- Logs screen opens full-screen with an X in the top-right to return to the dashboard. It scrolls normally and must not open the image zoom viewer.
- Preserve the Discord webhook appearance as faithfully as available data allows: embed sidebars/colors, title, description, line breaks, timestamps, account names, images/screenshots/attachments, and formatting. Do not flatten everything into plain text if structured embed data is available.
- On initial open, show the newest messages. Follow new messages automatically only if the user is already near the bottom. If the user scrolls upward to read older entries, do not force-scroll them back down. Keep per-account log filtering or clearly label the selected account.
- Never expose webhook tokens or private credentials in the UI or logs.

## 6. Connectivity diagnostics and alerts

Implement two independent checks and display honest, recoverable status:

1. **Phone internet test:** determine whether the phone itself has usable internet. The user suggested pinging `google.com`, but ICMP ping is frequently blocked and a browser/webview cannot reliably run a system ping. In the native Android layer, use Android network connectivity capabilities plus a short-timeout HTTPS connectivity probe to a Google endpoint such as `https://www.google.com/generate_204` (or another verified HTTPS probe). Optionally add DNS/ICMP diagnostics if technically available, but do not use failed ICMP alone to claim the phone is offline. Respect Android network security policies; do not add a broad cleartext exception.
2. **Host/backend check:** only if phone internet works, call the authenticated backend health endpoint over the private tunnel. If this fails, show a **red** alert with wording like `The PC is off?` and clarify that the PC may be off, disconnected, the tunnel may be disconnected, or the bridge service may be stopped. Do not claim certainty that the PC is powered off.

- If the phone has no usable internet, show an **orange** alert: `You might need to connect`.
- If the phone has internet but the host service is unreachable, show a **red** alert: `The PC is off?` plus the explanation above.
- Retry automatically with sensible backoff and clear alerts when the connection returns. Do not flood the network or battery.
- Retain last known values when disconnected but clearly mark them as **stale / last updated at [time]**. Never label stale values as live.
- Test the logic in all cases: phone offline; phone online + backend down; both online; recovery after either failure. Provide a debug/test mode so these cases can be tested without really disconnecting a device.

## 7. Backend integration with Natro Macro

First inspect the real local Natro files and current version. Useful starting points include the official repository and these files, but use the local checkout as the source of truth:

- Repository: `https://github.com/NatroTeam/NatroMacro`
- `submacros/StatMonitor.ahk`: `https://github.com/NatroTeam/NatroMacro/blob/main/submacros/StatMonitor.ahk`
- `nm_image_assets/statmonitor/bitmaps.ahk` (included by StatMonitor) for report icons.

Determine how the existing environment exposes:

- current honey and honey/hour (OCR / StatMonitor data if enabled);
- current field and Blue Field Booster checkbox/config state;
- macro status: running, paused, stopped, plus last-updated time;
- hourly report arrival time, image attachment and structured data;
- logs/webhook events;
- planters and timers, if the actual installed Natro version exposes them;
- command routing for multiple MuMu/Roblox accounts.

Do not assume a Discord webhook can receive commands: outgoing webhooks are not automatically a bidirectional command API. Find the user's existing receiver/bot/local integration or implement a small local bridge that accepts only an allowlist of commands and forwards them through a real supported mechanism. If no command receiver exists, clearly report that limitation and implement the bridge boundary without pretending the command reached Natro.

Prefer read-only file/config/log access or supported local integration over invasive modifications to Natro. Avoid modifying upstream Natro files unless necessary and documented. If changes to Natro source are required, preserve a clean patch, explain upgrade implications, and comply with the project's license. Never scrape the screen as the first option if StatMonitor already exposes the relevant data. Keep polling efficient and use a structured normalized data model.

Suggested API (adjust to real implementation and document it):

- `GET /health` — service health and bridge version, no secrets.
- `GET /api/v1/accounts` — configured accounts/instances and configured hive levels.
- `GET /api/v1/accounts/{id}/status` — honey, rate, field, booster state, macro status, last-updated timestamp, stale flag.
- `GET /api/v1/accounts/{id}/reports` — newest-first report metadata and image references.
- `GET /api/v1/reports/{id}` — report metadata/details and actual image URL or authenticated image endpoint.
- `GET /api/v1/accounts/{id}/logs` — paginated/streamable log entries preserving embeds and attachments.
- `POST /api/v1/accounts/{id}/commands` — JSON command request validated against allowlist; returns accepted/rejected/failed and a correlation ID. Do not accept arbitrary shell strings.
- `GET /api/v1/accounts/{id}/planters` — only if real planter data can be obtained; otherwise return a documented unavailable state.

Use an authenticated event stream (SSE/WebSocket) if practical for live updates; otherwise implement sensible polling and backoff. Add request timeouts, input validation, bounded response sizes, log rotation, and structured logs with secrets redacted.

## 8. Security and privacy

- V1 is private to this user. Do not deploy a publicly reachable unauthenticated server. Prefer Tailscale/private VPN; bind backend to the intended interface only.
- Require a strong random app/API token or equivalent authentication. Store Android credentials using Android Keystore / encrypted preferences. Keep server secrets in environment variables or a protected config file with restrictive Windows permissions.
- Use TLS for any connection that leaves the device/host boundary; when using Tailscale, document the chosen transport and certificate approach. Never disable certificate validation.
- CORS is not authentication. Do not rely on obscurity, a secret URL, or a public port. Do not put tokens in query strings.
- Restrict commands to an explicit allowlist and selected account ID. Never pass user input to `cmd.exe`, PowerShell, `eval`, or arbitrary process launch. Prevent command injection and cross-account mistakes.
- Validate image URLs and limit attachment sizes/types. Do not load arbitrary remote HTML in a WebView with privileged JavaScript bridges.
- Add rate limits, authentication failures handling, and a minimal audit log of command attempts without leaking credentials.
- Document what data is stored locally and how to remove it.

## 9. Persistence and configuration

- Persist account list, account display name, hive level, server/tailnet address, user preferences, and pinned commands across app restarts.
- Never persist fake sample stats as if they were real. Use sample mode only when explicitly labelled `DEMO`.
- Provide a simple first-run setup: enter private backend address, authenticate, test connection, then select/configure accounts.
- Provide a Windows service/startup option for the bridge if practical, with instructions to start/stop/restart and inspect logs. Do not silently install persistence or change Windows settings without telling the user.
- Include an example account configuration with `slyazer` hive level 20 and `jidozacac_1` hive level 15. Other accounts must be editable and clearly marked as examples until confirmed.

## 10. Tests and acceptance criteria

Write and run tests for as many of these as the environment permits. Include the results in `BUILD_STATUS.md`.

### UI / interaction
- The HTML reference remains responsive in Chrome mobile and desktop widths.
- Account dropdown changes the selected account; hive-level edits persist.
- Stat card gradients and white text are readable on Android Chrome.
- Honey icon uses Honey; cyan honey/hour uses Gifted Diamond Bee; logo uses Spirit Bear.
- Macro status glyph is at the right side, vertically centered; it changes correctly for running, paused, stopped.
- Current activity shows field and only shows `BOOSTED` when booster state is true.
- Reports list rows contain only date and arrival time; tapping opens the correct image/detail.
- Dashboard latest report image opens touch zoom. Pinch, double-tap and drag work; logs remain scroll-only and never open zoom.
- Log view close/back navigation works and preserves scroll-follow behavior.
- Command buttons send immediately, show real accepted/failed feedback, and unknown commands are rejected.

### Connectivity
- Phone internet down => orange warning and retry.
- Phone internet up + backend down => red warning with non-certain `The PC is off?` wording.
- Both online => connected.
- Recovery clears alert and refreshes data.
- Stale data is visibly labelled.

### Integration and safety
- Report image shown is the actual image from the hourly Natro report, not an SVG preview.
- Honey/rate/field/booster/status come from the real installed instance or are explicitly marked unavailable.
- Logs retain actual timestamps, colors/embeds, content and attachments when available.
- No command path executes arbitrary shell input.
- API rejects missing/invalid auth and invalid commands.
- No secrets are committed or displayed.
- Build Android debug APK if SDK is available; run backend tests and lint/type checks; run JavaScript syntax checks if HTML/JS remains.

## 11. Documentation to produce

Create:
- `AGENTS.md` — project-specific instructions for future OpenCode sessions.
- `ARCHITECTURE.md` — app, bridge, Natro data flow, network model, and security boundaries.
- `SETUP_WINDOWS.md` — install/run/configure the host bridge, find the local Natro path, enable any needed StatMonitor/report source, set up private networking, and troubleshoot.
- `SETUP_ANDROID.md` — build/install APK using Android Studio or `adb`, configure backend URL/token, test phone internet and host connection.
- `API.md` — endpoints, schemas, authentication, command allowlist, error cases.
- `BUILD_STATUS.md` — checklist, test results, known limitations, exact commands used, and output APK path if built.
- `.env.example` and safe sample account configuration.

## 12. Implementation order

1. Inventory files, SDKs, Natro version, existing command receiver/webhook config; report discoveries.
2. Inspect the HTML reference and test its inline JS syntax. Patch only obvious reference inconsistencies (Spirit Bear logo, Diamond Bee for cyan rate card, larger icons, status glyph position) without losing current interactions.
3. Design the backend adapter and data model around what the actual Natro install exposes. Document any unavailable data before choosing a fallback.
4. Build the authenticated Windows bridge and tests; implement real health/status/reports/logs/command endpoints with safe failure behavior.
5. Build the Android app and preserve the HTML design. Add secure configuration, persisted account levels, network diagnostics, alerts, image viewer gestures, and live data refresh.
6. Connect the report screen to actual hourly image attachments and preserve the original report image. Connect logs to actual webhook/embed data. Connect commands only through the verified receiver.
7. Add Tailscale/private access instructions, first-run setup, startup management and troubleshooting.
8. Run tests and a debug APK build; fix failures. Review every acceptance criterion and update `BUILD_STATUS.md` honestly.
9. End with a concise summary of implemented features, exact build/install commands, APK location, steps the user must complete manually, and any remaining limitations. Do not claim remote access works until the user has configured and tested the private tunnel on both devices.

## 13. Sources and license awareness

Use current official docs while implementing:
- OpenCode project instructions: `https://docs.opencode.ai/docs/rules/`
- OpenCode configuration docs: `https://docs.opencode.ai/docs/config/`
- Natro Macro source: `https://github.com/NatroTeam/NatroMacro`
- Natro StatMonitor: `https://github.com/NatroTeam/NatroMacro/blob/main/submacros/StatMonitor.ahk`

Natro Macro is open source under GPL-3.0-or-later as described in its source header. If code is copied or modified, preserve required license notices and comply with the license. Reuse of images/icons should respect their source's license/attribution terms. Prefer links and original output data over copying large upstream source sections.

**Start now:** inspect the files and environment, make a plan, then implement. Do not return only another design mockup or a generic tutorial.
