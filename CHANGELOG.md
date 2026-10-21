# Changelog

The newest section is published on the download page and in `release.json`.

## v0.5.0
- Alpha7A now asks for the Termux command permission and verifies it, instead of failing with
  "Not allowed to start service ... without permission com.termux.permission.RUN_COMMAND".
- The dashboard shows a truthful state per component, a stage based progress area and a
  readable activity log with a separate technical log.
- The SSH configuration (Termux and Debian accounts, passwords and ports) is set in the app and
  applied to the appliance; no password is baked into the build any more.
- Tailscale supports two modes: the official Android app (default) and the official pinned
  client inside Termux, which is installed only when you ask for it and is checksum verified.
- No Tailscale auth key is embedded in the published APK any more. Paste the key in the app
  when you need one.
- The published page now shows the version code, the source commit, the workflow run, the build
  time, the release notes and a link to the release metadata.
