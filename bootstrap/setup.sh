#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

# Android Development Appliance
# Termux + Tailscale userspace + Debian proot
#
# Runtime secrets:
#   TS_AUTHKEY
#   TERMUX_PASSWORD
#   DEBIAN_PASSWORD

DEBIAN_USER="${DEBIAN_USER:-admin}"
TERMUX_SSH_PORT="${TERMUX_SSH_PORT:-8022}"
DEBIAN_SSH_PORT="${DEBIAN_SSH_PORT:-2222}"
TAILSCALE_SOCKS5_PORT="${TAILSCALE_SOCKS5_PORT:-1055}"

log(){ printf '\n[+] %s\n' "$*"; }
warn(){ printf '\n[!] %s\n' "$*" >&2; }
die(){ printf '\n[ERROR] %s\n' "$*" >&2; exit 1; }

command -v pkg >/dev/null 2>&1 || die "Run this from Termux."

log "Installing Termux prerequisites"
pkg update -y
pkg install -y openssh proot-distro curl wget tmux iproute2 net-tools termux-services qrencode proxychains-ng dnsutils nmap tcpdump netcat-openbsd

log "Configuring Termux SSH"
mkdir -p "$PREFIX/var/empty"
chmod 700 "$PREFIX/var/empty"
ssh-keygen -A >/dev/null 2>&1 || true

if [ -n "${TERMUX_PASSWORD:-}" ]; then
  printf '%s\n%s\n' "$TERMUX_PASSWORD" "$TERMUX_PASSWORD" | passwd >/dev/null 2>&1 ||     warn "Could not change Termux password automatically."
fi

if grep -qE '^[#[:space:]]*Port[[:space:]]+' "$PREFIX/etc/ssh/sshd_config" 2>/dev/null; then
  sed -i -E "s/^[#[:space:]]*Port[[:space:]]+.*/Port $TERMUX_SSH_PORT/" "$PREFIX/etc/ssh/sshd_config"
else
  printf '\nPort %s\n' "$TERMUX_SSH_PORT" >> "$PREFIX/etc/ssh/sshd_config"
fi

pkill sshd 2>/dev/null || true
sshd

log "Installing Tailscale Termux CLI"
if ! command -v tailscale-cli >/dev/null 2>&1; then
  curl -fsSL https://raw.githubusercontent.com/bropines/tailscale-termux-cli/main/remote-install.sh | bash
fi

MODEL="$(getprop ro.product.model 2>/dev/null | tr ' ' '-' | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')"
MODEL="${MODEL:-android}"
SUFFIX="$(tr -dc 'a-z0-9' </dev/urandom | head -c 4 || true)"
AUTO_HOSTNAME="${MODEL}-${SUFFIX:-0000}"

mkdir -p "$PREFIX/var/service/tailscaled" "$PREFIX/var/log"

if command -v sv >/dev/null 2>&1; then
  sv-enable tailscaled 2>/dev/null || true
  sv up tailscaled 2>/dev/null || true
fi

sleep 2

log "Authenticating Tailscale"
if [ -n "${TS_AUTHKEY:-}" ]; then
  if ! tailscale-cli up --auth-key="$TS_AUTHKEY" --hostname="$AUTO_HOSTNAME"; then
    warn "Auth key failed; attempting login/QR fallback."
    LOGIN_OUTPUT="$(tailscale-cli up --hostname="$AUTO_HOSTNAME" 2>&1 || true)"
    printf '%s\n' "$LOGIN_OUTPUT"
    AUTH_URL="$(printf '%s\n' "$LOGIN_OUTPUT" | grep -oE 'https://login.tailscale.com/[^[:space:]]+' | head -1 || true)"
    [ -z "$AUTH_URL" ] || qrencode -t UTF8 "$AUTH_URL" || true
  fi
else
  warn "TS_AUTHKEY not supplied; attempting interactive login."
  LOGIN_OUTPUT="$(tailscale-cli up --hostname="$AUTO_HOSTNAME" 2>&1 || true)"
  printf '%s\n' "$LOGIN_OUTPUT"
  AUTH_URL="$(printf '%s\n' "$LOGIN_OUTPUT" | grep -oE 'https://login.tailscale.com/[^[:space:]]+' | head -1 || true)"
  [ -z "$AUTH_URL" ] || qrencode -t UTF8 "$AUTH_URL" || true
fi

log "Installing Debian"
if ! proot-distro list 2>/dev/null | grep -q '^debian'; then
  proot-distro install debian
fi

DEBIAN_ROOT="$PREFIX/var/lib/proot-distro/installed-rootfs/debian"
[ -d "$DEBIAN_ROOT" ] || die "Debian rootfs not found."

cat > "$DEBIAN_ROOT/root/appliance-setup.sh" <<'DEBIAN_SETUP'
#!/bin/bash
set -Eeuo pipefail

DEBIAN_USER="${DEBIAN_USER:-admin}"
DEBIAN_SSH_PORT="${DEBIAN_SSH_PORT:-2222}"
DEBIAN_PASSWORD="${DEBIAN_PASSWORD:-}"

export DEBIAN_FRONTEND=noninteractive

# Deliberately update package indexes but do not perform apt upgrade.
apt-get update

apt-get install -y   build-essential git curl wget nano vim tmux htop   python3 python3-pip python3-venv python3-dev   sudo openssh-server net-tools iproute2 procps   ca-certificates gnupg lsb-release pkg-config   libssl-dev libffi-dev zlib1g-dev   proxychains4 dnsutils nmap tcpdump netcat-openbsd

if ! id "$DEBIAN_USER" >/dev/null 2>&1; then
  useradd -m -s /bin/bash -G sudo "$DEBIAN_USER"
fi

if [ -n "$DEBIAN_PASSWORD" ]; then
  printf '%s:%s\n' "$DEBIAN_USER" "$DEBIAN_PASSWORD" | chpasswd
fi

install -d -m 0755 /run/sshd
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

cat > /etc/proxychains4.conf <<'PROXY'
strict_chain
proxy_dns
[ProxyList]
socks5 127.0.0.1 1055
PROXY

rm -f /root/appliance-setup.sh
DEBIAN_SETUP

chmod +x "$DEBIAN_ROOT/root/appliance-setup.sh"

log "Configuring Debian"
proot-distro login debian -- env   DEBIAN_PASSWORD="${DEBIAN_PASSWORD:-}"   DEBIAN_USER="$DEBIAN_USER"   DEBIAN_SSH_PORT="$DEBIAN_SSH_PORT"   bash /root/appliance-setup.sh

cat > "$HOME/start-debian-ssh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock 2>/dev/null || true
tmux kill-session -t debian-ssh 2>/dev/null || true
tmux new-session -d -s debian-ssh "proot-distro login debian -- bash -c 'mkdir -p /run/sshd; exec /usr/sbin/sshd -D -e'"
EOF
chmod +x "$HOME/start-debian-ssh"

cat > "$HOME/stop-debian-ssh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
tmux kill-session -t debian-ssh 2>/dev/null || true
EOF
chmod +x "$HOME/stop-debian-ssh"

"$HOME/start-debian-ssh" || warn "Debian SSH did not start automatically."

log "Provisioning complete"
printf 'Initial Tailscale hostname: %s\n' "$AUTO_HOSTNAME"
printf 'Termux SSH port: %s\n' "$TERMUX_SSH_PORT"
printf 'Debian SSH port: %s\n' "$DEBIAN_SSH_PORT"
printf 'Debian user: %s\n' "$DEBIAN_USER"
printf 'Tailscale SOCKS5 endpoint: 127.0.0.1:%s\n' "$TAILSCALE_SOCKS5_PORT"
printf 'Secrets were not printed.\n'
