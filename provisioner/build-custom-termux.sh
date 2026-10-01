#!/usr/bin/env bash
set -Eeuo pipefail

TERMUX_REF="v0.118.3"
BOOTSTRAP_REF="${BOOTSTRAP_REF}"
TAILSCALE_AUTHKEY="${TAILSCALE_AUTHKEY:-}"
REPO_ROOT="$PWD"
WORKDIR="$REPO_ROOT/.termux-build"
SRC="$WORKDIR/termux-app"

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"

git clone --depth 1 --branch "$TERMUX_REF" --recurse-submodules https://github.com/termux/termux-app.git "$SRC"

export BOOTSTRAP_REF TAILSCALE_AUTHKEY
python3 - "$SRC/app/src/main/java/com/termux/app/TermuxActivity.java" "$REPO_ROOT/bootstrap/setup.sh" <<'PY'
import base64, os, pathlib, sys

java_path = pathlib.Path(sys.argv[1])
setup_path = pathlib.Path(sys.argv[2])
s = java_path.read_text()

needle = "mTermuxTerminalSessionClient.addNewSession(launchFailsafe, null);"
if needle not in s:
    raise SystemExit("Termux first-session hook changed upstream")

setup = setup_path.read_text()
key = os.environ.get("TAILSCALE_AUTHKEY", "")
if not key:
    raise SystemExit("TAILSCALE_AUTHKEY is required for the alpha.7 test build")
if not key.startswith("tskey-auth-"):
    raise SystemExit("TAILSCALE_AUTHKEY does not look like a Tailscale auth key")
setup = setup.replace("__EMBEDDED_TS_AUTHKEY__", key, 1)

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
