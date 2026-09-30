#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

REPO="https://raw.githubusercontent.com/arunramakantsingh-coder/turmux-tailscal-debian/main/bootstrap/setup.sh"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

command -v curl >/dev/null 2>&1 || {
  echo "curl is required. Run: pkg install curl"
  exit 1
}

curl -fsSL "$REPO" -o "$TMP"
exec bash "$TMP"
