#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SETUP="$ROOT/bootstrap/setup.sh"
WORKFLOW="$ROOT/.github/workflows/pages.yml"

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

# The provisioning payload must not carry a build-time key marker any more.
if grep -q '__ALPHA7A_TAILSCALE_AUTHKEY__' "$SETUP"; then
  echo "FAIL: setup.sh still carries the build-time auth key marker"
  exit 1
fi
if grep -nE 'tskey-auth-[A-Za-z0-9]{8,}' "$SETUP" >/dev/null 2>&1; then
  echo "FAIL: a literal Tailscale auth key is committed in setup.sh"
  exit 1
fi

# The Debian account is configured by the app. The script must carry no default password.
grep -q 'DEBIAN_USER="${DEBIAN_USER:-admin}"' "$SETUP" \
  || { echo "FAIL: the Debian SSH user default is not admin"; exit 1; }
grep -q 'DEBIAN_PASSWORD="${DEBIAN_PASSWORD:-}"' "$SETUP" \
  || { echo "FAIL: the Debian password is not supplied by the app"; exit 1; }

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

# Debian detection must test the real precondition, not parse "proot-distro list",
# whose output format differs between proot-distro generations.
grep -q 'proot-distro login debian -- true' "$SETUP" \
  || { echo "FAIL: Debian detection does not test the real precondition"; exit 1; }
if grep -qE "proot-distro list.*grep.*debian" "$SETUP"; then
  echo "FAIL: Debian detection still parses proot-distro list"
  exit 1
fi

# The userspace Tailscale client must be installed from the official, pinned source.
if grep -q 'bropines' "$SETUP"; then
  echo "FAIL: the unpinned third-party Tailscale installer is still used"
  exit 1
fi
grep -q 'TAILSCALE_SHA256_ARM64="[0-9a-f]\{64\}"' "$SETUP" \
  || { echo "FAIL: the Tailscale client is not checksum pinned"; exit 1; }
grep -q 'TAILSCALE_SHA256_AMD64="[0-9a-f]\{64\}"' "$SETUP" \
  || { echo "FAIL: the Tailscale client is not checksum pinned for amd64"; exit 1; }
grep -q 'sha256sum -c' "$SETUP" \
  || { echo "FAIL: the Tailscale client checksum is never verified"; exit 1; }
grep -q 'userspace-networking' "$SETUP" \
  || { echo "FAIL: the Tailscale daemon does not use userspace networking"; exit 1; }
grep -q 'ALPHA7A_TS_MODE' "$SETUP" \
  || { echo "FAIL: the provisioning script does not know the Tailscale mode"; exit 1; }
grep -q 'BackendState' "$SETUP" \
  || { echo "FAIL: the Tailscale state is not read from the structured status"; exit 1; }

# The Debian rootfs must be pinned instead of relying on the distro name alone.
grep -q 'PROOT_DISTRO_VERSION' "$SETUP" \
  || { echo "FAIL: the Debian rootfs is not pinned"; exit 1; }
grep -q 'debian_rootfs_url' "$SETUP" \
  || { echo "FAIL: the pinned Debian rootfs URL is not used"; exit 1; }

# No reusable authentication key may be embedded in the build or in the payload.
if grep -q '__ALPHA7A_TAILSCALE_AUTHKEY__' "$SETUP"; then
  echo "FAIL: the provisioning payload still carries an auth key marker"
  exit 1
fi
if grep -q 'TAILSCALE_AUTHKEY' "$WORKFLOW"; then
  echo "FAIL: the build still injects a Tailscale auth key into the APK"
  exit 1
fi

# No default password may be baked into the script.
if grep -qE 'DEBIAN_PASSWORD="\$\{DEBIAN_PASSWORD:-[^}]' "$SETUP"; then
  echo "FAIL: a default Debian password is still baked into the script"
  exit 1
fi
if grep -qE 'TERMUX_PASSWORD="\$\{TERMUX_PASSWORD:-[^}]' "$SETUP"; then
  echo "FAIL: a default Termux password is still baked into the script"
  exit 1
fi

# The interactive key prompt must only run on a real terminal, never in a background run.
grep -q '\[ -t 0 \]' "$SETUP" \
  || { echo "FAIL: the interactive key prompt is not limited to a terminal"; exit 1; }

# The operations page must read the ports from the environment, not from literals.
if grep -qE 'Termux SSH: 8022|Debian SSH: 2222|ssh -p 8022|ssh -p 2222' "$SETUP"; then
  echo "FAIL: the operations page still hard codes the SSH ports"
  exit 1
fi

# A service must not be reported as running when the check only warns.
grep -q 'die "Termux SSH is NOT listening' "$SETUP" \
  || { echo "FAIL: a missing Termux SSH does not fail the run"; exit 1; }
grep -q 'die "Debian SSH is NOT listening' "$SETUP" \
  || { echo "FAIL: a missing Debian SSH does not fail the run"; exit 1; }

# The local operations page must not publish credentials on every interface.
if grep -q 'ThreadingHTTPServer(("0.0.0.0"' "$SETUP"; then
  echo "FAIL: the operations page still binds every interface"
  exit 1
fi
grep -q 'SHOW_LAB_CREDENTIALS:-false' "$SETUP" \
  || { echo "FAIL: the operations page still shows credentials by default"; exit 1; }

# The app must use Termux's documented result channel so it can report what happened.
ALPHA_RUNNER="$ROOT/alpha7a/app/src/main/java/com/arunramakantsingh/alpha7a/TermuxRunner.java"
ALPHA_RECEIVER="$ROOT/alpha7a/app/src/main/java/com/arunramakantsingh/alpha7a/CommandResultReceiver.java"
[ -f "$ALPHA_RUNNER" ] || { echo "FAIL: the Termux runner is missing"; exit 1; }
[ -f "$ALPHA_RECEIVER" ] || { echo "FAIL: the Termux result receiver is missing"; exit 1; }
grep -q 'com.termux.RUN_COMMAND_PENDING_INTENT' "$ALPHA_RUNNER" \
  || { echo "FAIL: the app does not request the Termux result callback"; exit 1; }
grep -q 'com.termux.RUN_COMMAND_BACKGROUND' "$ALPHA_RUNNER" \
  || { echo "FAIL: the app does not run Termux commands in the background"; exit 1; }
grep -q 'CommandResultReceiver' "$ALPHA_MANIFEST" \
  || { echo "FAIL: the manifest does not register the Termux result receiver"; exit 1; }

# The app must ask for the Termux command permission at run time. Without it the
# platform rejects the intent with "Not allowed to start service ... without
# permission com.termux.permission.RUN_COMMAND" before Termux ever sees it.
grep -q 'requestPermissions(new String\[\]{RUN_PERMISSION}' "$ALPHA_MAIN" \
  || { echo "FAIL: the app does not request the Termux command permission"; exit 1; }
grep -q 'onRequestPermissionsResult' "$ALPHA_MAIN" \
  || { echo "FAIL: the app does not handle the permission result"; exit 1; }
grep -q 'canRequestPackageInstalls' "$ALPHA_MAIN" \
  || { echo "FAIL: the app does not check the installer permission"; exit 1; }

# The app must offer both Tailscale modes and detect the official app.
grep -q 'TailscaleMode\.' "$ALPHA_MAIN" \
  || { echo "FAIL: the app has no Tailscale mode selector"; exit 1; }
grep -q '"com.tailscale.ipn"' "$ALPHA_MAIN" \
  || { echo "FAIL: the app does not detect the official Tailscale app"; exit 1; }
grep -q 'com.tailscale.ipn' "$ALPHA_MANIFEST" \
  || { echo "FAIL: the manifest does not declare package visibility for the Tailscale app"; exit 1; }

# The SSH configuration lives in the app, so no port may be hard coded there.
if grep -qE '8022|2222' "$ALPHA_MAIN"; then
  echo "FAIL: the app still hard codes an SSH port"
  exit 1
fi
grep -q 'KEY_TERMUX_PORT' "$ALPHA_MAIN" \
  || { echo "FAIL: the app does not store the Termux SSH port"; exit 1; }
grep -q 'KEY_DEBIAN_PORT' "$ALPHA_MAIN" \
  || { echo "FAIL: the app does not store the Debian SSH port"; exit 1; }
grep -q 'KEY_DEBIAN_USER' "$ALPHA_MAIN" \
  || { echo "FAIL: the app does not store the Debian user"; exit 1; }
grep -q 'KEY_DEBIAN_PASSWORD' "$ALPHA_MAIN" \
  || { echo "FAIL: the app does not store the Debian password"; exit 1; }

# The published release must be traceable to its source commit and workflow run.
grep -q '> site/release.json <<EOF' "$WORKFLOW" \
  || { echo "FAIL: the workflow does not publish release metadata"; exit 1; }
grep -q 'GITHUB_RUN_ID' "$WORKFLOW" \
  || { echo "FAIL: the published metadata does not record the workflow run"; exit 1; }
grep -q 'GITHUB_SHA' "$WORKFLOW" \
  || { echo "FAIL: the published metadata does not record the source commit"; exit 1; }
grep -q 'versionCode' "$WORKFLOW" \
  || { echo "FAIL: the published metadata does not record the version code"; exit 1; }

# The app must never carry a Tailscale auth key.
if grep -RniE 'tskey-auth-[A-Za-z0-9]{8,}' "$ROOT/alpha7a" >/dev/null 2>&1; then
  echo "FAIL: a Tailscale auth key is committed in the Android sources"
  exit 1
fi

# The release version must have a single source of truth and be shown on the page.
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

# The download page must render with every placeholder resolved.
if command -v python3 >/dev/null 2>&1; then
  python3 "$ROOT/tests/render_page_test.py" || exit 1
else
  echo "SKIP: python3 is not available, the page render test was not run"
fi

echo "PASS: repository baseline validation"
