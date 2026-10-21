#!/data/data/com.termux/files/usr/bin/bash
# Alpha7A appliance provisioning.
#
# Every credential and every port is supplied by the Alpha7A app through the environment.
# This script never carries a default password and never embeds an authentication key.
set -Eeuo pipefail
umask 077

# ---------------------------------------------------------------- configuration
STAGE="${ALPHA7A_STAGE:-provision}"
TS_MODE="${ALPHA7A_TS_MODE:-official-app}"

DEBIAN_USER="${DEBIAN_USER:-admin}"
TERMUX_SSH_PORT="${TERMUX_SSH_PORT:-8022}"
DEBIAN_SSH_PORT="${DEBIAN_SSH_PORT:-2222}"
DEBIAN_MIN_VERSION="${DEBIAN_MIN_VERSION:-12}"
DEBIAN_CODENAME="${DEBIAN_CODENAME:-trixie}"
PROOT_DISTRO_VERSION="${PROOT_DISTRO_VERSION:-v4.29.0}"
TAILSCALE_SOCKS5_PORT="${TAILSCALE_SOCKS5_PORT:-1055}"
SHOW_LAB_CREDENTIALS="${SHOW_LAB_CREDENTIALS:-false}"

# Credentials come from the app. An empty value means "not supplied".
TS_AUTHKEY="${TS_AUTHKEY:-}"
TERMUX_PASSWORD="${TERMUX_PASSWORD:-}"
DEBIAN_PASSWORD="${DEBIAN_PASSWORD:-}"

# The Tailscale client is pinned. The version and the two digests belong together.
TAILSCALE_VERSION="${TAILSCALE_VERSION:-1.104.2}"
TAILSCALE_SHA256_ARM64="5776aaa901b5ef1916e66be5bb54b9f481cd399c9c28db123a8cab620efa237b"
TAILSCALE_SHA256_AMD64="512f613ed7d04fc7282f7dc5bd76aa105aae28168fbb9357eb749ebcbc29f0ae"

APP_HOME="$HOME/.appliance"
STATE="$APP_HOME/state"
LOGS="$APP_HOME/logs"
AUTH_FILE="$APP_HOME/tailscale-authkey"
ENV_FILE="$APP_HOME/appliance.env"
TS_DIR="$APP_HOME/tailscale"
TS_SOCKET="$TS_DIR/tailscaled.sock"
TS_STATE="$TS_DIR/state"

mkdir -p "$STATE" "$LOGS"
exec > >(tee -a "$LOGS/provisioning.log") 2>&1

log(){ printf '\n[+] %s\n' "$*"; }
warn(){ printf '\n[!] %s\n' "$*" >&2; }
die(){ printf '\n[ERROR] %s\n' "$*" >&2; exit 1; }
have(){ command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- stage markers
stage_needed(){ [ ! -f "$STATE/$1.done" ]; }
stage_done(){ printf '%s\n' "${2:-}" > "$STATE/$1.done"; }

# ---------------------------------------------------------------- credentials
require_debian_password(){
  [ -n "$DEBIAN_PASSWORD" ] || die "No Debian password was supplied. Set it in the Alpha7A SSH configuration and run provisioning again."
}

# The auth key may be supplied by the app, or staged by the user in Termux.
load_auth_key(){
  if [ -n "$TS_AUTHKEY" ]; then
    printf '%s' "$TS_AUTHKEY" > "$AUTH_FILE"
    chmod 600 "$AUTH_FILE"
    return 0
  fi
  if [ -f "$AUTH_FILE" ]; then
    TS_AUTHKEY="$(cat "$AUTH_FILE")"
    return 0
  fi
  # Only a real terminal can ask for a key. A background run must not consume its own input.
  if [ -t 0 ]; then
    printf '\n=== Tailscale first-run configuration ===\n'
    printf 'Enter the Tailscale auth key once. It is stored locally with mode 600 and deleted after authentication.\n'
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
    TS_AUTHKEY="$(cat "$AUTH_FILE")"
    return 0
  fi
  return 1
}

# ---------------------------------------------------------------- environment file
write_appliance_env(){
  umask 077
  cat > "$ENV_FILE" <<EOF
TERMUX_SSH_PORT=$TERMUX_SSH_PORT
DEBIAN_SSH_PORT=$DEBIAN_SSH_PORT
DEBIAN_USER=$DEBIAN_USER
TERMUX_SSH_USER=$(id -un 2>/dev/null || echo termux)
APPLIANCE_WEB_BIND=${APPLIANCE_WEB_BIND:-127.0.0.1}
APPLIANCE_WEB_PORT=8080
SHOW_LAB_CREDENTIALS=$SHOW_LAB_CREDENTIALS
EOF
  chmod 600 "$ENV_FILE"
}

# ---------------------------------------------------------------- Termux packages
ensure_termux_packages(){
  if stage_needed termux-packages; then
    log "Termux baseline"
    pkg update -y
    pkg install -y openssh proot proot-distro curl wget git git-lfs jq unzip zip rsync python tmux termux-services iproute2 net-tools qrencode proxychains-ng dnsutils nmap tcpdump netcat-openbsd
    stage_done termux-packages
  else
    log "Termux packages already installed"
  fi
}

# ---------------------------------------------------------------- Termux SSH
configure_termux_ssh(){
  if stage_needed termux-ssh; then
    log "Termux SSH on port $TERMUX_SSH_PORT"
    mkdir -p "$PREFIX/var/empty"; chmod 700 "$PREFIX/var/empty"
    ssh-keygen -A >/dev/null 2>&1 || true
    if [ -n "$TERMUX_PASSWORD" ]; then
      printf '%s\n%s\n' "$TERMUX_PASSWORD" "$TERMUX_PASSWORD" | passwd >/dev/null 2>&1 \
        || warn "Could not set the Termux account password."
    else
      warn "No Termux password was supplied, so the Termux account password was left unchanged."
    fi
    if grep -qE '^[#[:space:]]*Port[[:space:]]+' "$PREFIX/etc/ssh/sshd_config" 2>/dev/null; then
      sed -i -E "s/^[#[:space:]]*Port[[:space:]]+.*/Port $TERMUX_SSH_PORT/" "$PREFIX/etc/ssh/sshd_config"
    else
      printf '\nPort %s\n' "$TERMUX_SSH_PORT" >> "$PREFIX/etc/ssh/sshd_config"
    fi
    if have sv-enable && [ -d "$PREFIX/var/service/sshd" ]; then
      sv-enable sshd >/dev/null 2>&1 || warn "Could not enable the sshd service."
      sv up sshd >/dev/null 2>&1 || warn "Could not start sshd through termux-services."
    else
      warn "termux-services is not available for sshd; starting sshd directly."
      pkill sshd 2>/dev/null || true
      sshd || warn "Could not start the Termux sshd daemon."
    fi
    stage_done termux-ssh
  else
    log "Termux SSH already configured"
  fi
  sleep 1
  if have ss && ss -lnt 2>/dev/null | grep -qE ":$TERMUX_SSH_PORT\b"; then
    log "Termux SSH is listening on port $TERMUX_SSH_PORT."
  else
    die "Termux SSH is NOT listening on port $TERMUX_SSH_PORT. Run 'sv up sshd' inside Termux and check $PREFIX/var/service/sshd."
  fi
}

# ---------------------------------------------------------------- Tailscale
ts_arch(){
  case "$(uname -m)" in
    aarch64) printf 'arm64' ;;
    x86_64) printf 'amd64' ;;
    *) printf '' ;;
  esac
}

install_tailscale_cli(){
  local arch tarball url expected dir
  arch="$(ts_arch)"
  [ -n "$arch" ] || die "The Tailscale client is not published for this architecture ($(uname -m)). Use the official Tailscale app instead."
  if [ "$arch" = "arm64" ]; then expected="$TAILSCALE_SHA256_ARM64"; else expected="$TAILSCALE_SHA256_AMD64"; fi
  tarball="tailscale_${TAILSCALE_VERSION}_${arch}.tgz"
  url="https://pkgs.tailscale.com/stable/${tarball}"
  dir="$TS_DIR/${tarball%.tgz}"

  log "Installing the official Tailscale client $TAILSCALE_VERSION ($arch)"
  mkdir -p "$TS_DIR" "$PREFIX/bin"
  if [ ! -f "$TS_DIR/$tarball" ]; then
    curl -fsSL --retry 4 --retry-delay 3 -o "$TS_DIR/$tarball" "$url" \
      || die "Could not download $url"
  fi
  printf '%s  %s\n' "$expected" "$TS_DIR/$tarball" | sha256sum -c - >/dev/null 2>&1 \
    || die "The checksum of the Tailscale client does not match the pinned value. Refusing to install it."
  log "Checksum verified"
  rm -rf "$dir"
  tar xzf "$TS_DIR/$tarball" -C "$TS_DIR"
  install -m 0755 "$dir/tailscale" "$PREFIX/bin/tailscale"
  install -m 0755 "$dir/tailscaled" "$PREFIX/bin/tailscaled"
  stage_done tailscale-client
}

start_tailscaled(){
  local run_dir="$PREFIX/var/service/tailscaled"
  mkdir -p "$run_dir" "$TS_DIR"
  cat > "$run_dir/run" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
exec $PREFIX/bin/tailscaled --tun=userspace-networking --state=$TS_STATE --socket=$TS_SOCKET
EOF
  chmod 700 "$run_dir/run"
  if have sv-enable; then
    sv-enable tailscaled >/dev/null 2>&1 || warn "Could not enable the tailscaled service."
    sv up tailscaled >/dev/null 2>&1 || warn "Could not start tailscaled."
  else
    nohup "$PREFIX/bin/tailscaled" --tun=userspace-networking --state="$TS_STATE" --socket="$TS_SOCKET" >/dev/null 2>&1 &
  fi
  sleep 3
}

ts_cli(){ "$PREFIX/bin/tailscale" --socket="$TS_SOCKET" "$@"; }

ts_up(){
  local state
  state="$(ts_cli status --json 2>/dev/null | grep -o '"BackendState":"[^"]*"' | head -1)"
  if [ -n "$state" ]; then
    case "$state" in
      *Running*) return 0 ;;
      *) return 1 ;;
    esac
  fi
  ts_cli status 2>/dev/null | grep -qE '100\.[0-9]+\.[0-9]+\.[0-9]+'
}

ensure_tailscale(){
  if [ "$TS_MODE" != "termux-cli" ]; then
    log "Tailscale is provided by the official app; nothing to install or configure here."
    return 0
  fi
  if stage_needed tailscale-client; then
    install_tailscale_cli
  else
    log "Tailscale client already installed"
  fi
  if ! pgrep -x tailscaled >/dev/null 2>&1; then
    start_tailscaled
  fi
  if ! ts_up; then
    if ! load_auth_key; then
      die "No Tailscale auth key was supplied. Paste one in the Alpha7A app, or stage it at $AUTH_FILE inside Termux."
    fi
    log "Authenticating with the supplied Tailscale auth key"
    ts_cli up --auth-key="$TS_AUTHKEY" --hostname="$(cat "$STATE/hostname" 2>/dev/null || echo alpha7a)" || true
    unset TS_AUTHKEY
  fi
  if ts_up; then
    printf 'authenticated=true\n' > "$STATE/tailscale.env"
    rm -f "$AUTH_FILE"
    log "Tailscale is connected"
  else
    printf 'authenticated=false\n' > "$STATE/tailscale.env"
    die "Tailscale authentication failed. Check the auth key and ensure it is tagged AuthkeyNodes."
  fi
}

configure_proxy(){
  [ "$TS_MODE" = "termux-cli" ] || return 0
  # The official daemon exposes an unauthenticated SOCKS5 proxy, so it stays on loopback.
  cat > "$HOME/.appliance/proxy.env" <<EOF
export ALL_PROXY=socks5://127.0.0.1:$TAILSCALE_SOCKS5_PORT
export HTTP_PROXY=socks5://127.0.0.1:$TAILSCALE_SOCKS5_PORT
export HTTPS_PROXY=socks5://127.0.0.1:$TAILSCALE_SOCKS5_PORT
export all_proxy=socks5://127.0.0.1:$TAILSCALE_SOCKS5_PORT
export http_proxy=socks5://127.0.0.1:$TAILSCALE_SOCKS5_PORT
export https_proxy=socks5://127.0.0.1:$TAILSCALE_SOCKS5_PORT
export NO_PROXY=127.0.0.1,localhost,::1,100.64.0.0/10,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16
export no_proxy=127.0.0.1,localhost,::1,100.64.0.0/10,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16
EOF
  chmod 600 "$HOME/.appliance/proxy.env"
  grep -qxF '[ -f "$HOME/.appliance/proxy.env" ] && . "$HOME/.appliance/proxy.env"' "$HOME/.bashrc" 2>/dev/null \
    || printf '\n[ -f "$HOME/.appliance/proxy.env" ] && . "$HOME/.appliance/proxy.env"\n' >> "$HOME/.bashrc"
  cat > "$PREFIX/etc/proxychains.conf" <<EOF
strict_chain
proxy_dns
[ProxyList]
socks5 127.0.0.1 $TAILSCALE_SOCKS5_PORT
EOF
  chmod 644 "$PREFIX/etc/proxychains.conf"
  log "SOCKS5 proxy configured on 127.0.0.1:$TAILSCALE_SOCKS5_PORT"
}

# ---------------------------------------------------------------- Debian
debian_rootfs_url(){
  local arch
  case "$(uname -m)" in
    aarch64) arch=aarch64 ;;
    armv7l|armv8l|arm) arch=arm ;;
    x86_64) arch=x86_64 ;;
    i686|i386) arch=i686 ;;
    *) return 1 ;;
  esac
  printf 'https://github.com/termux/proot-distro/releases/download/%s/debian-%s-%s-pd-%s.tar.xz' \
    "$PROOT_DISTRO_VERSION" "$DEBIAN_CODENAME" "$arch" "$PROOT_DISTRO_VERSION"
}

ensure_debian(){
  if proot-distro login debian -- true >/dev/null 2>&1; then
    log "Debian is already installed"
    return 0
  fi
  local url
  url="$(debian_rootfs_url || true)"
  if [ -n "$url" ]; then
    log "Installing the pinned Termux Debian rootfs ($DEBIAN_CODENAME)"
    if proot-distro install "$url" --name debian >/dev/null 2>&1; then
      :
    else
      warn "The pinned rootfs could not be installed with this proot-distro, trying the distro name."
    fi
  fi
  if ! proot-distro login debian -- true >/dev/null 2>&1; then
    log "Installing Debian with proot-distro"
    proot-distro install debian </dev/null >/dev/null 2>&1 || true
  fi
  proot-distro login debian -- true >/dev/null 2>&1 \
    || die "Debian could not be installed. Check the network and the proot-distro version."
}

check_debian_version(){
  local version major
  version="$(proot-distro login debian -- cat /etc/debian_version 2>/dev/null | tr -d '[:space:]' || true)"
  major="${version%%.*}"
  case "$major" in
    ''|*[!0-9]*) warn "Could not read a numeric Debian version (got '$version'); skipping the minimum version check." ;;
    *)
      if [ "$major" -lt "$DEBIAN_MIN_VERSION" ]; then
        die "Debian $version is older than the required minimum Debian $DEBIAN_MIN_VERSION."
      fi
      log "Debian $version satisfies the minimum Debian $DEBIAN_MIN_VERSION requirement."
      ;;
  esac
}

configure_debian(){
  require_debian_password
  check_debian_version
  log "Debian packages (no apt upgrade)"
  proot-distro login debian -- env DEBIAN_USER="$DEBIAN_USER" DEBIAN_PASSWORD="$DEBIAN_PASSWORD" DEBIAN_SSH_PORT="$DEBIAN_SSH_PORT" bash -s <<'DEBIAN'
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
export DEBIAN_USER DEBIAN_PASSWORD DEBIAN_SSH_PORT
apt-get update
apt-get install -y build-essential git git-lfs openssh-client openssh-server curl wget nano vim tmux htop python3 python3-pip python3-venv python3-dev sudo net-tools iproute2 procps ca-certificates gnupg lsb-release pkg-config libssl-dev libffi-dev zlib1g-dev proxychains4 dnsutils nmap tcpdump netcat-openbsd jq rsync unzip zip file tree less man-db lsof psmisc strace openssl sqlite3 libsqlite3-dev

if ! id "$DEBIAN_USER" >/dev/null 2>&1; then useradd -m -s /bin/bash -G sudo "$DEBIAN_USER"; fi
printf '%s:%s\n' "$DEBIAN_USER" "$DEBIAN_PASSWORD" | chpasswd
printf '%s ALL=(ALL) NOPASSWD:ALL\n' "$DEBIAN_USER" > "/etc/sudoers.d/90-appliance-$DEBIAN_USER"
chmod 0440 "/etc/sudoers.d/90-appliance-$DEBIAN_USER"

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
mkdir -p /workspace
chown "$DEBIAN_USER:$DEBIAN_USER" /workspace
DEBIAN
  log "Debian account '$DEBIAN_USER' configured"
}

start_debian_ssh(){
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
  sleep 2
  if have ss && ss -lnt 2>/dev/null | grep -qE ":$DEBIAN_SSH_PORT\b"; then
    log "Debian SSH is listening on port $DEBIAN_SSH_PORT for user $DEBIAN_USER."
  else
    die "Debian SSH is NOT listening on port $DEBIAN_SSH_PORT. Run '$HOME/start-debian-ssh' inside Termux and check the debian-ssh tmux session."
  fi
}

# ---------------------------------------------------------------- operations page
configure_ops_page(){
  log "Local details/operations page"
  mkdir -p "$HOME/appliance-web"
  cat > "$HOME/appliance-web/server.py" <<'PY'
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os, subprocess, socket, html
HOME=os.path.expanduser("~")
PORT=int(os.environ.get("APPLIANCE_WEB_PORT","8080"))
BIND=os.environ.get("APPLIANCE_WEB_BIND","127.0.0.1")
TERMUX_PORT=os.environ.get("TERMUX_SSH_PORT","8022")
DEBIAN_PORT=os.environ.get("DEBIAN_SSH_PORT","2222")
TERMUX_USER=os.environ.get("TERMUX_SSH_USER","termux")
DEBIAN_USER=os.environ.get("DEBIAN_USER","admin")
SHOW=os.environ.get("SHOW_LAB_CREDENTIALS","false").lower()=="true"
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
        try: host=open(HOME+"/.appliance/state/hostname").read().strip()
        except: host="unknown"
        ts=sh("tailscale --socket=" + os.environ.get("TS_SOCKET","") + " status 2>/dev/null | head -20")
        ip=sh("tailscale --socket=" + os.environ.get("TS_SOCKET","") + " ip -4 2>/dev/null | head -1")
        lan=sh("ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*src \\([^ ]*\\).*/\\1/p'")
        creds=f"Termux: {TERMUX_USER} / {e.get('TERMUX_PASSWORD','-')}\\nDebian: {DEBIAN_USER} / {e.get('DEBIAN_PASSWORD','-')}" if SHOW else "Lab credentials hidden"
        body=f"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>Android Development Appliance</title>
<style>body{{font:16px system-ui;max-width:900px;margin:auto;padding:20px;background:#0b1020;color:#eef2ff}}section{{background:#151c33;border:1px solid #303a60;border-radius:14px;padding:18px;margin:14px 0}}pre{{white-space:pre-wrap;background:#080d18;border-radius:10px;padding:14px}}</style>
<h1>Android Development Appliance</h1><p>{html.escape(host)}</p>
<section><h2>Status</h2><pre>Tailscale IPv4: {html.escape(ip or '-')}
LAN IPv4: {html.escape(lan or '-')}
Termux SSH: {html.escape(TERMUX_PORT)}
Debian SSH: {html.escape(DEBIAN_PORT)}
Details: http://127.0.0.1:{PORT}</pre></section>
<section><h2>Tailscale</h2><pre>{html.escape(ts or '-')}</pre></section>
<section><h2>SSH</h2><pre>ssh -p {html.escape(TERMUX_PORT)} {html.escape(TERMUX_USER)}@{html.escape(host)}
ssh -p {html.escape(DEBIAN_PORT)} {html.escape(DEBIAN_USER)}@{html.escape(host)}</pre></section>
<section><h2>Proxy</h2><pre>SOCKS5 127.0.0.1:1055 (loopback only)
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
ThreadingHTTPServer((BIND,PORT),H).serve_forever()
PY

  mkdir -p "$PREFIX/var/service/appliance-web"
  cat > "$PREFIX/var/service/appliance-web/run" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
[ -f "$ENV_FILE" ] && . "$ENV_FILE"
[ -f "$TS_DIR/tailscaled.sock" ] && export TS_SOCKET="$TS_SOCKET"
exec python "$HOME/appliance-web/server.py" 2>&1
EOF
  chmod 700 "$PREFIX/var/service/appliance-web/run"
  if have sv-enable; then
    sv-enable appliance-web >/dev/null 2>&1 || warn "Could not enable the appliance-web service."
    sv up appliance-web >/dev/null 2>&1 || warn "Could not start the appliance-web service."
  else
    nohup python "$HOME/appliance-web/server.py" >/dev/null 2>&1 &
  fi
}

# ---------------------------------------------------------------- credentials file
write_credentials(){
  umask 077
  cat > "$APP_HOME/credentials.env" <<EOF
TERMUX_USER=$(id -un)
TERMUX_PASSWORD=$TERMUX_PASSWORD
DEBIAN_USER=$DEBIAN_USER
DEBIAN_PASSWORD=$DEBIAN_PASSWORD
EOF
  chmod 600 "$APP_HOME/credentials.env"
}

# ---------------------------------------------------------------- hostname
ensure_hostname(){
  local model
  model="$(getprop ro.product.model 2>/dev/null | tr ' ' '-' | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')"
  model="${model:-android}"
  if [ ! -f "$STATE/hostname" ]; then
    printf '%s-%s\n' "$model" "$(tr -dc 'a-z0-9' </dev/urandom | head -c 4)" > "$STATE/hostname"
  fi
}

# ---------------------------------------------------------------- verification
verify_all(){
  log "Verification"
  if have ss && ss -lnt 2>/dev/null | grep -qE ":$TERMUX_SSH_PORT\b"; then
    log "Termux SSH is listening on port $TERMUX_SSH_PORT."
  else
    warn "Termux SSH is NOT listening on port $TERMUX_SSH_PORT."
  fi
  if have ss && ss -lnt 2>/dev/null | grep -qE ":$DEBIAN_SSH_PORT\b"; then
    log "Debian SSH is listening on port $DEBIAN_SSH_PORT."
  else
    warn "Debian SSH is NOT listening on port $DEBIAN_SSH_PORT."
  fi
  if proot-distro login debian -- true >/dev/null 2>&1; then
    log "Debian starts."
  else
    warn "Debian does not start."
  fi
  if [ "$TS_MODE" = "termux-cli" ]; then
    if ts_up; then
      log "Tailscale is connected: $(ts_cli ip -4 2>/dev/null | head -1)"
    else
      warn "Tailscale is not connected."
    fi
  else
    warn "Tailscale state is owned by the official app and cannot be read from here."
  fi
  printf 'provisioned=true\n' > "$STATE/provisioned"
  log "Provisioning complete"
  printf 'Termux SSH: %s | Debian SSH: %s | SOCKS5: %s\n' "$TERMUX_SSH_PORT" "$DEBIAN_SSH_PORT" "$TAILSCALE_SOCKS5_PORT"
  printf 'Credentials are stored locally in %s with mode 0600.\n' "$APP_HOME/credentials.env"
}

# ---------------------------------------------------------------- stages
case "$STAGE" in
  provision)
    write_appliance_env
    ensure_termux_packages
    configure_termux_ssh
    ensure_hostname
    ensure_tailscale
    configure_proxy
    ensure_debian
    configure_debian
    write_credentials
    start_debian_ssh
    configure_ops_page
    verify_all
    ;;
  tailscale-cli)
    write_appliance_env
    ensure_hostname
    ensure_tailscale
    configure_proxy
    ;;
  status)
    verify_all
    ;;
  *)
    die "Unknown stage: $STAGE"
    ;;
esac
