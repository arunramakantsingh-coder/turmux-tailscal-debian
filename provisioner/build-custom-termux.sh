#!/usr/bin/env bash
set -Eeuo pipefail

TERMUX_REF="v0.118.3"
BOOTSTRAP_REF="${BOOTSTRAP_REF}"
REPO_ROOT="$PWD"
WORKDIR="$REPO_ROOT/.termux-build"
SRC="$WORKDIR/termux-app"

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"

git clone --depth 1 --branch "$TERMUX_REF" --recurse-submodules https://github.com/termux/termux-app.git "$SRC"

export BOOTSTRAP_REF
python3 - "$SRC/app/src/main/java/com/termux/app/TermuxActivity.java" "$REPO_ROOT/bootstrap/setup.sh" <<'PY'
import base64, os, pathlib, re, sys

java_path = pathlib.Path(sys.argv[1])
setup_path = pathlib.Path(sys.argv[2])
s = java_path.read_text()

needle = "mTermuxTerminalSessionClient.addNewSession(launchFailsafe, null);"
if needle not in s:
    raise SystemExit("Termux first-session hook changed upstream")

setup = setup_path.read_text()

# SECURITY: the Tailscale auth key is NEVER embedded in the APK.
#
# This build previously substituted a live, reusable tailnet credential
# into the bootstrap and compiled it into the APK, which the publish step
# then copied into the PUBLIC distribution repository. Because the APK is
# distributed publicly, an embedded key is disclosed to every downloader.
#
# The key is now supplied once on first run inside Termux, stored mode 600
# and deleted after a successful `tailscale up`. `setup.sh` keeps the
# __EMBEDDED_TS_AUTHKEY__ placeholder literal, which is its sentinel for
# "ask the user".
#
# Fail closed: refuse to build if any credential-shaped string is present,
# so this can never regress silently.
injected = os.environ.get("TAILSCALE_AUTHKEY", "").strip()
if injected:
    raise SystemExit(
        "Refusing to build: TAILSCALE_AUTHKEY is set in this environment. "
        "Credentials must not be embedded in a distributed APK. "
        "Unset it and let first-run provisioning prompt for the key."
    )

for pattern, what in (
    (r"tskey-[a-z]+-[A-Za-z0-9]+", "a Tailscale auth key"),
    (r"gh[pousr]_[A-Za-z0-9]{20,}", "a GitHub token"),
    (r"github_pat_[A-Za-z0-9_]{20,}", "a GitHub fine-grained PAT"),
):
    if re.search(pattern, setup):
        raise SystemExit(f"Refusing to build: bootstrap/setup.sh contains {what}.")

payload = base64.b64encode(setup.encode()).decode()

replacement = """mTermuxTerminalSessionClient.addNewSession(launchFailsafe, null);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (mTermuxService == null) return;
                    com.termux.terminal.TerminalSession session =
                        mTermuxTerminalSessionClient.getCurrentStoredSessionOrLast();
                    if (session == null || !session.isRunning()) return;
                    String payload = "%s";
                    session.write("echo '[APPLIANCE] Starting automatic provisioning...'; echo " + payload + " | base64 -d > $PREFIX/tmp/appliance-setup.sh; chmod 700 $PREFIX/tmp/appliance-setup.sh; $PREFIX/tmp/appliance-setup.sh; rm -f $PREFIX/tmp/appliance-setup.sh\\n");
                }, 1500);""" % payload

s = s.replace(needle, replacement, 1)
java_path.write_text(s)
PY

python3 - "$SRC/app/src/main/res/values/strings.xml" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text()
s = s.replace('<string name="application_name">&TERMUX_APP_NAME;</string>','<string name="application_name">Android Development Appliance</string>',1)
s = s.replace('<string name="shared_user_label">&TERMUX_APP_NAME; user</string>','<string name="shared_user_label">Android Development Appliance user</string>',1)
p.write_text(s)
PY

cd "$SRC"
./gradlew :app:assembleDebug --no-daemon

APK="$(find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*universal*.apk' | head -1)"
[ -n "$APK" ] || APK="$(find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' | head -1)"
[ -n "$APK" ] || { echo "No APK produced"; exit 1; }

mkdir -p "$REPO_ROOT/site"
cp "$APK" "$REPO_ROOT/site/android-development-appliance-v0.5.0-alpha.7.apk"

# ── fail closed: prove the shipped artifact carries no credentials ──
# Scans every entry inside the built APK for credential shapes. This is
# the last line of defence before the publish step copies the APK into the
# public distribution repository.
python3 - "$APK" "$REPO_ROOT/bootstrap/setup.sh" <<'PY'
import re, sys, zipfile

apk, setup_path = sys.argv[1], sys.argv[2]

patterns = [
    (r"tskey-[a-z]+-[A-Za-z0-9]{6,}", "Tailscale auth key"),
    (r"gh[pousr]_[A-Za-z0-9]{20,}", "GitHub token"),
    (r"github_pat_[A-Za-z0-9_]{20,}", "GitHub fine-grained PAT"),
    (r"BEGIN [A-Z ]*PRIVATE KEY", "PEM private key"),
    (r"AKIA[0-9A-Z]{16}", "AWS access key id"),
]

findings = []

# 1. the APK itself
with zipfile.ZipFile(apk) as z:
    for info in z.infolist():
        try:
            data = z.read(info.filename)
        except Exception:
            continue
        for pattern, what in patterns:
            if re.search(pattern.encode(), data):
                findings.append(f"{what} in APK entry {info.filename}")

# 2. the bootstrap source that gets base64-embedded into the DEX
try:
    setup = open(setup_path, "rb").read()
    for pattern, what in patterns:
        if re.search(pattern.encode(), setup):
            findings.append(f"{what} in {setup_path}")
except OSError:
    pass

if findings:
    print("SECRET SCAN FAILED — refusing to publish:", file=sys.stderr)
    for f in sorted(set(findings)):
        print(f"  - {f}", file=sys.stderr)
    raise SystemExit(1)

print("secret scan: PASS (no credential shapes found in APK or bootstrap)")
PY

echo "[build] APK ready: $REPO_ROOT/site/android-development-appliance-v0.5.0-alpha.7.apk"

