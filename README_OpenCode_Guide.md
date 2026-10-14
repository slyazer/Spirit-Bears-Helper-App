# Macro Monitor — OpenCode handoff guide

## Files in this handoff

- `macro_monitor_prototype.html` — current UI reference. It is still a prototype: sample data and simulated commands are not connected to Natro.
- `Macro_Monitor_OpenCode_Prompt.md` — the complete implementation prompt. Paste its contents into OpenCode after opening the project folder, or ask OpenCode to read the file and follow it.
- `README_OpenCode_Guide.md` — this guide.

## Recommended workflow

1. Put the HTML reference and prompt in a clean project folder on the Windows PC that has OpenCode and access to the Natro Macro installation/source. If Natro's files live elsewhere, keep them in place; tell OpenCode the actual path rather than copying the whole game/macro folder unnecessarily.
2. Open that folder in OpenCode. Start by asking it to read `Macro_Monitor_OpenCode_Prompt.md`, inspect the environment and local Natro version, and follow the prompt's staged plan. Let it create `AGENTS.md` and `BUILD_STATUS.md` first.
3. Review the architecture proposal before allowing any changes to firewall rules, Windows startup tasks, or remote-access settings. The design should use a private network such as Tailscale and authentication, not a public unauthenticated port.
4. Ask OpenCode to implement and test the Windows bridge first, then the Android app, then the real Natro data/command adapters. This makes it easier to isolate whether a failure is in the phone, bridge, tunnel, or Natro integration.
5. Install Tailscale on the host PC and phone yourself, sign both into the same private tailnet, and test the backend only after OpenCode documents the bind address and auth setup. Do not share API tokens in chat or commit them.
6. Have OpenCode build a debug APK and install it with Android Studio or `adb install <path-to-apk>`. Use the actual APK path reported by the successful build; do not assume a path.
7. Test the app in three situations: both devices online, phone internet unavailable, and phone online while the host bridge is stopped. Confirm the orange/red alerts are correctly distinguished and clear when recovered.
8. Test one account and one non-destructive command first. Confirm the command is routed to the selected instance before enabling additional commands.

## Important technical note on the Google connectivity check

A raw `ping google.com` is not a reliable test of whether a phone has internet: some networks block ICMP, and a browser/WebView cannot generally run a system ping. The prompt therefore asks OpenCode to use Android network capabilities plus a short-timeout HTTPS probe to a Google connectivity endpoint. The host check is separate and tests the authenticated bridge over the private tunnel. This avoids falsely blaming the PC when the phone itself has no internet.

## What must not be treated as finished yet

The HTML still uses fictional sample honey values and generated report-preview images. Its commands are simulated. The actual hourly report image, account status, field/booster state, logs, planters, and command delivery only become real after the bridge has been connected to the installed Natro version and the relevant interfaces have been verified.
