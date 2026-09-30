#!/usr/bin/env bash
set -Eeuo pipefail

TERMUX_REF="v0.118.3"
BOOTSTRAP_REF="$BOOTSTRAP_REF"
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
needle = "mTermuxTerminalSessionClient.addNewSession(launchFailsafe, null);"
if needle not in s:
    raise SystemExit("Termux first-session hook changed upstream")
ref = os.environ["BOOTSTRAP_REF"]
url = "https://raw.githubusercontent.com/arunramakantsingh-coder/turmux-tailscal-debian/" + ref + "/bootstrap/install.sh"
replacement = """mTermuxService.createTermuxSession(
                        TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                        new String[]{"-lc", "curl -fsSL """ + url + """ | bash"},
                        null,
                        TermuxConstants.TERMUX_HOME_DIR_PATH,
                        launchFailsafe,
                        "appliance-provision");
                if (applianceSession != null) {
                    if (mTermuxService.getLastTermuxSession() != null) {
                    mTermuxTerminalSessionClient.setCurrentSession(mTermuxService.getLastTermuxSession().getTerminalSession());
                }
                }"""
p.write_text(s.replace(needle, replacement, 1))
PY

python3 - "$SRC/app/src/main/res/values/strings.xml" <<'PY'
import pathlib, sys
p=pathlib.Path(sys.argv[1])
s=p.read_text()
s=s.replace('<string name="application_name">&TERMUX_APP_NAME;</string>',
            '<string name="application_name">Android Development Appliance</string>',1)
s=s.replace('<string name="shared_user_label">&TERMUX_APP_NAME; user</string>',
            '<string name="shared_user_label">Android Development Appliance user</string>',1)
p.write_text(s)
PY

cd "$SRC"
./gradlew :app:assembleDebug --no-daemon

APK="$(find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*universal*.apk' | head -1)"
[ -n "$APK" ] || APK="$(find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' | head -1)"
[ -n "$APK" ] || { echo "No APK produced"; exit 1; }

mkdir -p "$OLDPWD/site"
cp "$APK" "$OLDPWD/site/android-development-appliance-v0.5.0-alpha.3.apk"
