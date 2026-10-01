#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
umask 077

DEBIAN_USER="${DEBIAN_USER:-admin}"
TERMUX_SSH_PORT="${TERMUX_SSH_PORT:-8022}"
DEBIAN_SSH_PORT="${DEBIAN_SSH_PORT:-2222}"
TAILSCALE_SOCKS5_PORT="${TAILSCALE_SOCKS5_PORT:-1055}"
SHOW_LAB_CREDENTIALS="${SHOW_LAB_CREDENTIALS:-true}"
# The Tailscale auth key is supplied on first run and is NEVER embedded in
# the distributed APK. The literal below is a sentinel meaning "prompt for
# the key". provisioner/build-custom-termux.sh refuses to build if a
# credential is present in this file or in the produced APK.
TS_AUTHKEY="${TS_AUTHKEY:-__EMBEDDED_TS_AUTHKEY__}"
APP_HOME="${HOME}/.appliance"
STATE="$APP_HOME/state"
LOGS="$APP_HOME/logs"

mkdir -p "$STATE" "$LOGS"
AUTH_FILE="$APP_HOME/tailscale-authkey"
if [ ! -f "$AUTH_FILE" ] && [ "$TS_AUTHKEY" = "__EMBEDDED_TS_AUTHKEY__" ]; then
  printf '\n=== Tailscale first-run configuration ===\n'
  printf 'Enter the Tailscale auth key once. It will be stored locally with mode 600 and deleted after successful authentication.\n'
  printf 'Tag expected on the key: AuthkeyNodes\n'
  while :; do
    read -r -s -p 'Tailscale Auth Key: ' TS_INPUT
    printf '\n'
    if [[ "$TS_INPUT" == tskey-auth-* ]]; then
      printf '%s' "$TS_INPUT" > "$AUTH_FILE"
      chmod 600 "$AUTH_FILE"
      unset TS_INPUT
      break
    fi
    printf '[ERROR] That does not look like a Tailscale auth key. Please try again.\n'
  done
fi
exec > >(tee -a "$LOGS/provisioning.log") 2>&1

log(){ printf '\n[+] %s\n' "$*"; }
warn(){ printf '\n[!] %s\n' "$*" >&2; }
die(){ printf '\n[ERROR] %s\n' "$*" >&2; exit 1; }
have(){ command -v "$1" >/dev/null 2>&1; }

random_secret(){ tr -dc 'A-Za-z0-9' </dev/urandom | head -c 20; }

have pkg || die "Run this from the appliance terminal."

if [ ! -f "$APP_HOME/credentials.env" ]; then
  TERMUX_PASSWORD="${TERMUX_PASSWORD:-$(random_secret)}"
  DEBIAN_PASSWORD="${DEBIAN_PASSWORD:-$(random_secret)}"
  cat > "$APP_HOME/credentials.env" <<EOF
TERMUX_USER=$(id -un)
TERMUX_PASSWORD=$TERMUX_PASSWORD
DEBIAN_USER=$DEBIAN_USER
DEBIAN_PASSWORD=$DEBIAN_PASSWORD
EOF
  chmod 600 "$APP_HOME/credentials.env"
else
  . "$APP_HOME/credentials.env"
fi

log "Termux baseline"
pkg update -y
pkg install -y openssh proot-distro curl wget git git-lfs jq unzip zip rsync python tmux termux-services iproute2 net-tools qrencode proxychains-ng dnsutils nmap tcpdump netcat-openbsd

log "Termux SSH :$TERMUX_SSH_PORT"
mkdir -p "$PREFIX/var/empty"; chmod 700 "$PREFIX/var/empty"
ssh-keygen -A >/dev/null 2>&1 || true
printf '%s\n%s\n' "$TERMUX_PASSWORD" "$TERMUX_PASSWORD" | passwd >/dev/null 2>&1 || true
if grep -qE '^[#[:space:]]*Port[[:space:]]+' "$PREFIX/etc/ssh/sshd_config" 2>/dev/null; then
  sed -i -E "s/^[#[:space:]]*Port[[:space:]]+.*/Port $TERMUX_SSH_PORT/" "$PREFIX/etc/ssh/sshd_config"
else
  printf '\nPort %s\n' "$TERMUX_SSH_PORT" >> "$PREFIX/etc/ssh/sshd_config"
fi
if have sv-enable && [ -d "$PREFIX/var/service/sshd" ]; then
  sv-enable sshd >/dev/null 2>&1 || true
  sv up sshd >/dev/null 2>&1 || true
else
  pkill sshd 2>/dev/null || true
  sshd || true
fi

log "Tailscale userspace"
if ! have tailscale-cli; then
  curl -fsSL --retry 4 --retry-delay 2 https://raw.githubusercontent.com/bropines/tailscale-termux-cli/main/remote-install.sh | bash
fi

MODEL="$(getprop ro.product.model 2>/dev/null | tr ' ' '-' | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')"
MODEL="${MODEL:-android}"
if [ ! -f "$STATE/hostname" ]; then
  printf '%s-%s\n' "$MODEL" "$(tr -dc 'a-z0-9' </dev/urandom | head -c 4)" > "$STATE/hostname"
fi
HOSTNAME="$(cat "$STATE/hostname")"

mkdir -p "$HOME/.tailscale"
printf 'TS_SOCKS5_PORT=%s\n' "$TAILSCALE_SOCKS5_PORT" > "$HOME/.tailscale/.env"
chmod 600 "$HOME/.tailscale/.env"

if have tailscaled-start; then tailscaled-start --service=on >/dev/null 2>&1 || true; fi
sleep 3

ts_up(){
  tailscale-cli status 2>/dev/null | grep -qE '100\.[0-9]+\.[0-9]+\.[0-9]+'
}

if ! ts_up; then
  if [ -n "${TS_AUTHKEY:-}" ]; then
    printf '%s' "$TS_AUTHKEY" > "$AUTH_FILE"
    chmod 600 "$AUTH_FILE"
  fi
  if [ -f "$AUTH_FILE" ]; then
    log "Using locally supplied Tailscale auth key"
    TS_AUTHKEY="$(cat "$AUTH_FILE")"
    tailscale-cli up --auth-key="$TS_AUTHKEY" --hostname="$HOSTNAME"
    unset TS_AUTHKEY
  fi
fi

if ! ts_up; then
  die "Tailscale authentication failed. Check the auth key and ensure it is tagged AuthkeyNodes."
fi

rm -f "$AUTH_FILE"


if ts_up; then
  printf 'authenticated=true\n' > "$STATE/tailscale.env"
else
  printf 'authenticated=false\n' > "$STATE/tailscale.env"
  warn "Tailscale authentication still pending."
fi

SOCKS_USER=""
SOCKS_PASS=""
configure_proxy(){
  local info
  info="$(tailscale-socks5 2>/dev/null || true)"
  SOCKS_USER="$(printf '%s\n' "$info" | sed -n 's/^[[:space:]]*Username[[:space:]]*:[[:space:]]*//p' | head -1)"
  SOCKS_PASS="$(printf '%s\n' "$info" | sed -n 's/^[[:space:]]*Password[[:space:]]*:[[:space:]]*//p' | head -1)"
  if [ -n "$SOCKS_USER" ] && [ -n "$SOCKS_PASS" ]; then
    PROXY_URL="socks5://$SOCKS_USER:$SOCKS_PASS@127.0.0.1:$TAILSCALE_SOCKS5_PORT"
    cat > "$HOME/.appliance/proxy.env" <<EOF
export ALL_PROXY="$PROXY_URL"
export HTTP_PROXY="$PROXY_URL"
export HTTPS_PROXY="$PROXY_URL"
export all_proxy="$PROXY_URL"
export http_proxy="$PROXY_URL"
export https_proxy="$PROXY_URL"
export NO_PROXY="127.0.0.1,localhost,::1,100.64.0.0/10,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
export no_proxy="$NO_PROXY"
EOF
    chmod 600 "$HOME/.appliance/proxy.env"
    grep -qxF '[ -f "$HOME/.appliance/proxy.env" ] && . "$HOME/.appliance/proxy.env"' "$HOME/.bashrc" 2>/dev/null || printf '\n[ -f "$HOME/.appliance/proxy.env" ] && . "$HOME/.appliance/proxy.env"\n' >> "$HOME/.bashrc"
    cat > "$PREFIX/etc/proxychains.conf" <<EOF
strict_chain
proxy_dns
[ProxyList]
socks5 127.0.0.1 $TAILSCALE_SOCKS5_PORT $SOCKS_USER $SOCKS_PASS
EOF
    chmod 600 "$PREFIX/etc/proxychains.conf"
  fi
}
ts_up && configure_proxy || true

log "Debian via current proot-distro"
if ! proot-distro list 2>/dev/null | grep -qE '^debian([[:space:]]|$)'; then
  proot-distro install debian
fi

log "Debian packages (no apt upgrade)"
proot-distro login debian -- env DEBIAN_USER="$DEBIAN_USER" DEBIAN_PASSWORD="$DEBIAN_PASSWORD" DEBIAN_SSH_PORT="$DEBIAN_SSH_PORT" SOCKS_PORT="$TAILSCALE_SOCKS5_PORT" SOCKS_USER="$SOCKS_USER" SOCKS_PASS="$SOCKS_PASS" bash -s <<'DEBIAN'
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y build-essential git git-lfs openssh-client openssh-server curl wget nano vim tmux htop python3 python3-pip python3-venv python3-dev sudo net-tools iproute2 procps ca-certificates gnupg lsb-release pkg-config libssl-dev libffi-dev zlib1g-dev proxychains4 dnsutils nmap tcpdump netcat-openbsd jq rsync unzip zip file tree less man-db lsof psmisc strace openssl sqlite3 libsqlite3-dev

if ! id "$DEBIAN_USER" >/dev/null 2>&1; then useradd -m -s /bin/bash -G sudo "$DEBIAN_USER"; fi
printf '%s:%s\n' "$DEBIAN_USER" "$DEBIAN_PASSWORD" | chpasswd
printf '%s ALL=(ALL) NOPASSWD:ALL\n' "$DEBIAN_USER" > /etc/sudoers.d/90-appliance-admin
chmod 0440 /etc/sudoers.d/90-appliance-admin

mkdir -p /run/sshd
ssh-keygen -A
if grep -qE '^[#[:space:]]*Port[[:space:]]+' /etc/ssh/sshd_config; then
  sed -i -E "s/^[#[:space:]]*Port[[:space:]]+.*/Port $DEBIAN_SSH_PORT/" /etc/ssh/sshd_config
else
  printf '\nPort %s\n' "$DEBIAN_SSH_PORT" >> /etc/ssh/sshd_config
fi
if grep -qE '^[#[:space:]]*PasswordAuthentication[[:space:]]+' /etc/ssh/sshd_config; then
  sed -i -E 's/^[#[:space:]]*PasswordAuthentication[[:space:]]+.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
else
  printf '\nPasswordAuthentication yes\n' >> /etc/ssh/sshd_config
fi
cat > /etc/proxychains4.conf <<PROXY
strict_chain
proxy_dns
[ProxyList]
socks5 127.0.0.1 $SOCKS_PORT $SOCKS_USER $SOCKS_PASS
PROXY
mkdir -p /workspace
chown "$DEBIAN_USER:$DEBIAN_USER" /workspace
cat > /etc/profile.d/appliance-proxy.sh <<PROXYENV
export ALL_PROXY="socks5://$SOCKS_USER:$SOCKS_PASS@127.0.0.1:$SOCKS_PORT"
export HTTP_PROXY="$ALL_PROXY"
export HTTPS_PROXY="$ALL_PROXY"
export all_proxy="$ALL_PROXY"
export http_proxy="$ALL_PROXY"
export https_proxy="$ALL_PROXY"
export NO_PROXY="127.0.0.1,localhost,::1,100.64.0.0/10,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
export no_proxy="$NO_PROXY"
PROXYENV
chmod 644 /etc/profile.d/appliance-proxy.sh
DEBIAN

cat > "$HOME/appliance-complete" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
HOSTNAME="$(cat "$HOME/.appliance/state/hostname")"
tailscale-cli up --hostname="$HOSTNAME"
SOCKS_INFO="$(tailscale-socks5 2>/dev/null || true)"
SOCKS_USER="$(printf '%s\n' "$SOCKS_INFO" | sed -n 's/^[[:space:]]*Username[[:space:]]*:[[:space:]]*//p' | head -1)"
SOCKS_PASS="$(printf '%s\n' "$SOCKS_INFO" | sed -n 's/^[[:space:]]*Password[[:space:]]*:[[:space:]]*//p' | head -1)"
cat > "$PREFIX/etc/proxychains.conf" <<PROXY
strict_chain
proxy_dns
[ProxyList]
socks5 127.0.0.1 1055 $SOCKS_USER $SOCKS_PASS
PROXY
chmod 600 "$PREFIX/etc/proxychains.conf"
echo "Tailscale/proxy completion updated."
EOF
chmod +x "$HOME/appliance-complete"

log "Debian SSH helper"
cat > "$HOME/start-debian-ssh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
termux-wake-lock 2>/dev/null || true
tmux kill-session -t debian-ssh 2>/dev/null || true
tmux new-session -d -s debian-ssh "proot-distro login debian -- bash -lc 'mkdir -p /run/sshd; exec /usr/sbin/sshd -D -e'"
EOF
chmod +x "$HOME/start-debian-ssh"
cat > "$HOME/stop-debian-ssh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
tmux kill-session -t debian-ssh 2>/dev/null || true
EOF
chmod +x "$HOME/stop-debian-ssh"
"$HOME/start-debian-ssh" || warn "Debian SSH start failed"

log "Local details/operations page"
mkdir -p "$HOME/appliance-web"
cat > "$HOME/appliance-web/server.py" <<'PY'
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os, subprocess, socket, html
HOME=os.path.expanduser("~")
PORT=int(os.environ.get("APPLIANCE_WEB_PORT","8080"))
SHOW=os.environ.get("SHOW_LAB_CREDENTIALS","true").lower()=="true"
def sh(c):
    try: return subprocess.check_output(c,shell=True,text=True,stderr=subprocess.DEVNULL).strip()
    except: return ""
def envfile():
    d={}
    try:
        for line in open(HOME+"/.appliance/credentials.env"):
            if "=" in line:
                k,v=line.rstrip().split("=",1); d[k]=v
    except: pass
    return d
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        e=envfile() if SHOW else {}
        host=open(HOME+"/.appliance/state/hostname").read().strip()
        ts=sh("tailscale-cli status 2>/dev/null | head -20")
        ip=sh("tailscale-cli ip -4 2>/dev/null | head -1")
        lan=sh("ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*src \\([^ ]*\\).*/\\1/p'")
        creds=f"Termux: {e.get('TERMUX_USER','-')} / {e.get('TERMUX_PASSWORD','-')}\\nDebian: {e.get('DEBIAN_USER','admin')} / {e.get('DEBIAN_PASSWORD','-')}" if SHOW else "Lab credentials hidden"
        body=f"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>Android Development Appliance</title>
<style>body{{font:16px system-ui;max-width:900px;margin:auto;padding:20px;background:#0b1020;color:#eef2ff}}section{{background:#151c33;border:1px solid #303a60;border-radius:14px;padding:18px;margin:14px 0}}pre{{white-space:pre-wrap;background:#080d18;padding:14px;border-radius:10px}}</style>
<h1>Android Development Appliance</h1><p>{html.escape(host)}</p>
<section><h2>Status</h2><pre>Tailscale IPv4: {html.escape(ip or '-')}
LAN IPv4: {html.escape(lan or '-')}
Termux SSH: 8022
Debian SSH: 2222
Details: http://127.0.0.1:{PORT}</pre></section>
<section><h2>Tailscale</h2><pre>{html.escape(ts or '-')}</pre></section>
<section><h2>SSH</h2><pre>ssh -p 8022 {html.escape(e.get('TERMUX_USER','&lt;termux-user&gt;'))}@{html.escape(host)}
ssh -p 2222 admin@{html.escape(host)}</pre></section>
<section><h2>Proxy</h2><pre>SOCKS5 127.0.0.1:1055
Level 1: ALL_PROXY / HTTP_PROXY / HTTPS_PROXY
Level 2: proxychains4 &lt;command&gt;</pre></section>
<section><h2>Credentials</h2><pre>{html.escape(creds)}</pre></section>
<section><h2>Development</h2><pre>Git + Git LFS
Python + pip + venv
Build toolchain + OpenSSL + SQLite
jq + rsync + zip/unzip + diagnostics
nmap + tcpdump + DNS + netcat</pre></section>"""
        b=body.encode(); self.send_response(200); self.send_header("Content-Type","text/html; charset=utf-8"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self,*a): pass
ThreadingHTTPServer(("0.0.0.0",PORT),H).serve_forever()
PY

mkdir -p "$PREFIX/var/service/appliance-web"
cat > "$PREFIX/var/service/appliance-web/run" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
exec python "$HOME/appliance-web/server.py" 2>&1
EOF
chmod +x "$PREFIX/var/service/appliance-web/run"
if have sv-enable; then sv-enable appliance-web >/dev/null 2>&1 || true; sv up appliance-web >/dev/null 2>&1 || true; else nohup python "$HOME/appliance-web/server.py" >/dev/null 2>&1 & fi

cat > "$HOME/appliance-status" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
echo "=== Android Development Appliance ==="
echo "Hostname: $(cat "$HOME/.appliance/state/hostname" 2>/dev/null || hostname)"
echo "Tailscale:"; tailscale-cli status 2>/dev/null || true
echo "Listening:"; ss -lnt 2>/dev/null | grep -E ':(8022|2222|8080)\b' || true
echo "Details: http://127.0.0.1:8080"
EOF
chmod +x "$HOME/appliance-status"

printf 'provisioned=true\n' > "$STATE/provisioned"
printf 'show_lab_credentials=%s\n' "$SHOW_LAB_CREDENTIALS" > "$STATE/config"
printf 'hostname=%s\n' "$HOSTNAME" >> "$STATE/provisioned"
log "Provisioning complete"
printf 'Details page: http://127.0.0.1:8080\n'
printf 'Termux SSH: %s | Debian SSH: %s | SOCKS5: %s\n' "$TERMUX_SSH_PORT" "$DEBIAN_SSH_PORT" "$TAILSCALE_SOCKS5_PORT"
printf 'Credentials are stored locally in %s with mode 0600.\n' "$APP_HOME/credentials.env"
