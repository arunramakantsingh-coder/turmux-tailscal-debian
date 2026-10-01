#!/usr/bin/env bash
set -Eeuo pipefail

TERMUX_REF="v0.118.3"
BOOTSTRAP_REF="${BOOTSTRAP_REF}"
WORKDIR="$PWD/.termux-build"
SRC="$WORKDIR/termux-app"

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"

git clone --depth 1 --branch "$TERMUX_REF" --recurse-submodules https://github.com/termux/termux-app.git "$SRC"

export BOOTSTRAP_REF
python3 - "$SRC/app/src/main/java/com/termux/app/TermuxActivity.java" <<'PY'
import os, pathlib, sys

p = pathlib.Path(sys.argv[1])
s = p.read_text()

# Keep the upstream Termux session creation path completely intact.
# We only inject a delayed command AFTER addNewSession() has created and
# attached the normal terminal session. This avoids bypassing Termux's
# session/UI lifecycle and avoids requiring curl before packages exist.
needle = "mTermuxTerminalSessionClient.addNewSession(launchFailsafe, null);"
if needle not in s:
    raise SystemExit("Termux first-session hook changed upstream")

ref = os.environ["BOOTSTRAP_REF"]
url = "https://raw.githubusercontent.com/arunramakantsingh-coder/turmux-tailscal-debian/" + ref + "/bootstrap/install.sh"

replacement = """mTermuxTerminalSessionClient.addNewSession(launchFailsafe, null);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (mTermuxService == null) return;
                    com.termux.terminal.TerminalSession session =
                        mTermuxTerminalSessionClient.getCurrentStoredSessionOrLast();
                    if (session == null || !session.isRunning()) return;
                    session.write("echo '[APPLIANCE] Starting automatic provisioning...'; pkg update -y && pkg install -y curl && curl -fsSL --retry 4 --retry-delay 2 " + url + " | bash\\n");
                }, 1500);"""

s = s.replace(needle, replacement, 1)
p.write_text(s)
PY

python3 - "$SRC/app/src/main/res/values/strings.xml" <<'PY'
import pathlib, sys

p = pathlib.Path(sys.argv[1])
s = p.read_text()
s = s.replace(
    '<string name="application_name">&TERMUX_APP_NAME;</string>',
    '<string name="application_name">Android Development Appliance</string>',
    1,
)
s = s.replace(
    '<string name="shared_user_label">&TERMUX_APP_NAME; user</string>',
    '<string name="shared_user_label">Android Development Appliance user</string>',
    1,
)
p.write_text(s)
PY

cd "$SRC"
./gradlew :app:assembleDebug --no-daemon

APK="$(find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*universal*.apk' | head -1)"
[ -n "$APK" ] || APK="$(find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' | head -1)"
[ -n "$APK" ] || { echo "No APK produced"; exit 1; }

mkdir -p "$OLDPWD/site"
cp "$APK" "$OLDPWD/site/android-development-appliance-v0.5.0-alpha.6.apk"
