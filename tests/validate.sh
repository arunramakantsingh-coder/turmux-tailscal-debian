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

if grep -RniE --exclude-dir=.git --exclude-dir=tests 'TS_AUTHKEY=.*(tskey-|tskey-auth-)' "$ROOT" >/dev/null 2>&1; then
  echo "FAIL: possible Tailscale auth key committed"
  exit 1
fi

ALPHA_MAIN="$ROOT/alpha7a/app/src/main/java/com/arunramakantsingh/alpha7a/MainActivity.java"
ALPHA_MANIFEST="$ROOT/alpha7a/app/src/main/AndroidManifest.xml"

[ -f "$ALPHA_MAIN" ] || { echo "FAIL: Alpha7A MainActivity missing"; exit 1; }
[ -f "$ALPHA_MANIFEST" ] || { echo "FAIL: Alpha7A manifest missing"; exit 1; }

# The Termux install button must fetch a release asset, not the releases web page.
grep -q 'https://github.com/termux/termux-app/releases/download/' "$ALPHA_MAIN" \
  || { echo "FAIL: Alpha7A does not download a Termux release asset"; exit 1; }
grep -qE 'termux-app_v[0-9][^"]*_universal\.apk' "$ALPHA_MAIN" \
  || { echo "FAIL: Alpha7A does not use the universal Termux APK"; exit 1; }
if grep -qE 'releases/download/v[^"/]*(beta|alpha|rc)' "$ALPHA_MAIN"; then
  echo "FAIL: Alpha7A pins a pre-release Termux build"
  exit 1
fi

# Every URL in the Android sources must point at the official Termux project.
if grep -oE 'https?://[^"[:space:])]+' "$ALPHA_MAIN" | grep -v '^https://github\.com/termux/termux-app/' | grep -q .; then
  echo "FAIL: Alpha7A references a URL outside the official Termux project"
  exit 1
fi

# The download must use Android's own download service and the system package installer.
grep -q 'DownloadManager' "$ALPHA_MAIN" \
  || { echo "FAIL: Alpha7A does not use DownloadManager"; exit 1; }
grep -q 'ACTION_INSTALL_PACKAGE' "$ALPHA_MAIN" \
  || { echo "FAIL: Alpha7A does not hand the APK to the Android package installer"; exit 1; }

grep -q 'android.permission.INTERNET' "$ALPHA_MANIFEST" \
  || { echo "FAIL: Alpha7A manifest lacks INTERNET"; exit 1; }
grep -q 'android.permission.REQUEST_INSTALL_PACKAGES' "$ALPHA_MANIFEST" \
  || { echo "FAIL: Alpha7A manifest lacks REQUEST_INSTALL_PACKAGES"; exit 1; }

# The Android sources must never carry a Tailscale key. The pattern needs a real key
# body, so that format checks such as key.startswith("tskey-auth-") are not flagged.
if grep -RniE 'tskey-(auth-)?[A-Za-z0-9]{8,}' "$ROOT/alpha7a" "$ROOT/provisioner" >/dev/null 2>&1; then
  echo "FAIL: possible Tailscale auth key in the Android sources"
  exit 1
fi

# The provisioning payload must only carry the build-time marker, never a real key.
grep -q '__ALPHA7A_TAILSCALE_AUTHKEY__' "$SETUP" \
  || { echo "FAIL: setup.sh lost the Tailscale auth key marker"; exit 1; }
if grep -nE 'tskey-auth-[A-Za-z0-9]{8,}' "$SETUP" >/dev/null 2>&1; then
  echo "FAIL: a literal Tailscale auth key is committed in setup.sh"
  exit 1
fi

# Debian SSH must use the documented appliance credentials.
grep -q 'DEBIAN_USER="${DEBIAN_USER:-Alpha7}"' "$SETUP" \
  || { echo "FAIL: Debian SSH user is not Alpha7"; exit 1; }
grep -q 'DEBIAN_PASSWORD="${DEBIAN_PASSWORD:-Alpha7}"' "$SETUP" \
  || { echo "FAIL: Debian SSH password is not Alpha7"; exit 1; }

# Termux SSH must be enabled and verified, not just attempted silently.
grep -q 'sv-enable sshd' "$SETUP" \
  || { echo "FAIL: Termux SSH is not enabled through termux-services"; exit 1; }
grep -q 'Termux SSH is NOT listening' "$SETUP" \
  || { echo "FAIL: Termux SSH listening state is not verified"; exit 1; }
grep -q 'Debian SSH is NOT listening' "$SETUP" \
  || { echo "FAIL: Debian SSH listening state is not verified"; exit 1; }

# A minimum Debian version must be enforced.
grep -q 'DEBIAN_MIN_VERSION="${DEBIAN_MIN_VERSION:-12}"' "$SETUP" \
  || { echo "FAIL: no minimum Debian version is enforced"; exit 1; }
grep -q 'older than the required minimum Debian' "$SETUP" \
  || { echo "FAIL: the minimum Debian version is not enforced"; exit 1; }

# The release version must have a single source of truth and be shown on the page.
WORKFLOW="$ROOT/.github/workflows/pages.yml"
[ -f "$WORKFLOW" ] || { echo "FAIL: publish workflow missing"; exit 1; }
grep -q "versionName '0.5.0'" "$ROOT/alpha7a/app/build.gradle" \
  || { echo "FAIL: Alpha7A versionName is not 0.5.0"; exit 1; }
grep -q 'grep -E "versionName" alpha7a/app/build.gradle' "$WORKFLOW" \
  || { echo "FAIL: the publish workflow does not read versionName"; exit 1; }
grep -q 'APK_NAME="android-development-appliance-v${VERSION}.apk"' "$WORKFLOW" \
  || { echo "FAIL: the APK file name is not derived from the version"; exit 1; }
grep -q '__VERSION__' "$WORKFLOW" \
  || { echo "FAIL: the distribution page does not show the version"; exit 1; }
grep -q '__BUILD_SHA__' "$WORKFLOW" \
  || { echo "FAIL: the distribution page does not show the build id"; exit 1; }
if grep -q '0\.5\.0-alpha\.7a' "$WORKFLOW" "$ROOT/alpha7a/app/build.gradle"; then
  echo "FAIL: the old alpha.7a version string is still hardcoded"
  exit 1
fi

# The checksum must record a bare file name so that it verifies next to the APK.
if grep -qE 'sha256sum site/' "$WORKFLOW"; then
  echo "FAIL: the checksum still records the site/ path prefix"
  exit 1
fi

echo "PASS: repository baseline validation"
