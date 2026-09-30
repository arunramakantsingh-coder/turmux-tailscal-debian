#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

REF="main"
URL="https://raw.githubusercontent.com/arunramakantsingh-coder/turmux-tailscal-debian/$REF/bootstrap/setup.sh"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

command -v curl >/dev/null 2>&1 || { echo "[ERROR] curl is unavailable"; exit 1; }
curl --fail --silent --show-error --location --retry 4 --retry-delay 2 "$URL" -o "$TMP"
exec bash "$TMP"
