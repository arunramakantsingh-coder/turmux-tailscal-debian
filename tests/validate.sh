#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SETUP="$ROOT/bootstrap/setup.sh"

[ -f "$SETUP" ] || { echo "FAIL: setup.sh missing"; exit 1; }
[ -f "$ROOT/ARCHITECTURE.md" ] || { echo "FAIL: ARCHITECTURE.md missing"; exit 1; }
[ -f "$ROOT/SPECIFICATION.md" ] || { echo "FAIL: SPECIFICATION.md missing"; exit 1; }

grep -q 'TS_AUTHKEY' "$SETUP"
grep -q 'TERMUX_PASSWORD' "$SETUP"
grep -q 'DEBIAN_PASSWORD' "$SETUP"
grep -q '2222' "$SETUP"
grep -q '8022' "$SETUP"
grep -q 'proxychains4' "$SETUP"
grep -q 'apt-get update' "$SETUP"

if grep -RniE 'TS_AUTHKEY=.*(tskey-|tskey-auth-)' "$ROOT" >/dev/null 2>&1; then
  echo "FAIL: possible Tailscale auth key committed"
  exit 1
fi

echo "PASS: repository baseline validation"
