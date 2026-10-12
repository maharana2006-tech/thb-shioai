#!/usr/bin/env bash
## Server-level hardening for the staging box. Idempotent — safe to re-run.
##
## Usage:
##   sudo ./scripts/harden-staging.sh                   # firewall + fail2ban + updates + auditd
##   sudo ./scripts/harden-staging.sh --disable-ssh-password
##                                                      # also disables SSH password auth
##                                                      # (ONLY if key-based login was already
##                                                      #  tested from this exact session)
##
## What it does by default:
##   · UFW:    default deny-in; allow 22 (SSH) + ${STAGING_PORT:-8080} + 443/80 egress
##   · fail2ban:  SSH jail — 5 failures in 10 min → 10 min ban
##   · unattended-upgrades:  Ubuntu security + updates channel
##   · auditd:  installed; a baseline rule set tracks sudo + user DB changes
##
## What it SKIPS unless you pass --disable-ssh-password:
##   · Disabling SSH password auth  (you lock yourself out if your key isn't working)
##   · Changing SSH port            (not worth the fragility on a LAN-only box)
##   · Disabling root login         (handled implicitly; root already can't log in on
##                                   fresh Ubuntu images unless you set a root password)

set -euo pipefail

STAGING_PORT="${STAGING_PORT:-8080}"
DISABLE_SSH_PASSWORD=0
for arg in "$@"; do
  case "$arg" in
    --disable-ssh-password) DISABLE_SSH_PASSWORD=1 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "Must run as root (use sudo)." >&2; exit 1
fi

log() { echo -e "\n==> $*"; }

# Try to pick up STAGING_PORT from /opt/shipai/.env if present + not already set.
if [[ "$STAGING_PORT" == "8080" && -f /opt/shipai/.env ]]; then
  STAGING_PORT=$(grep '^STAGING_PORT=' /opt/shipai/.env | cut -d= -f2 || echo 8080)
  log "Read STAGING_PORT=$STAGING_PORT from /opt/shipai/.env"
fi

# ─── 1. UFW firewall ──────────────────────────────────────────────────────
log "Configuring UFW firewall"
apt-get install -y --no-install-recommends ufw >/dev/null
ufw --force reset >/dev/null
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH'
ufw allow "${STAGING_PORT}"/tcp comment 'shipai staging HTTP'
ufw --force enable
ufw status verbose | head -15

# ─── 2. fail2ban (SSH only) ───────────────────────────────────────────────
log "Installing fail2ban + enabling SSH jail"
apt-get install -y --no-install-recommends fail2ban >/dev/null
cat > /etc/fail2ban/jail.d/shipai-sshd.local <<'EOF'
[sshd]
enabled  = true
port     = ssh
backend  = systemd
maxretry = 5
findtime = 10m
bantime  = 10m
EOF
systemctl enable --now fail2ban
systemctl restart fail2ban
sleep 1
fail2ban-client status sshd 2>/dev/null | head -10 || true

# ─── 3. Unattended security upgrades ──────────────────────────────────────
log "Enabling unattended security upgrades"
apt-get install -y --no-install-recommends unattended-upgrades apt-listchanges >/dev/null
# Enable the standard auto-upgrade channels (security + updates).
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
# Noble/Jammy both ship a sensible 50unattended-upgrades default; just ensure
# security line isn't commented out.
sed -i \
    -e 's|^//\s*"${distro_id}:${distro_codename}-security";|"${distro_id}:${distro_codename}-security";|' \
    -e 's|^//\s*"${distro_id}:${distro_codename}-updates";|"${distro_id}:${distro_codename}-updates";|' \
    /etc/apt/apt.conf.d/50unattended-upgrades || true
systemctl enable --now unattended-upgrades

# ─── 4. auditd baseline rules ─────────────────────────────────────────────
log "Installing auditd + baseline rules"
apt-get install -y --no-install-recommends auditd audispd-plugins >/dev/null
cat > /etc/audit/rules.d/shipai-baseline.rules <<'EOF'
## Shipai baseline — track sudo invocations, user DB changes, root cron.
-w /etc/sudoers -p wa -k identity
-w /etc/sudoers.d/ -p wa -k identity
-w /etc/passwd -p wa -k identity
-w /etc/shadow -p wa -k identity
-w /etc/group -p wa -k identity
-w /var/log/auth.log -p wa -k logins
-w /etc/ssh/sshd_config -p wa -k ssh-config
-w /root -p rwxa -k root-activity
EOF
augenrules --load >/dev/null 2>&1 || true
systemctl enable --now auditd
systemctl restart auditd

# ─── 5. Optional — SSH password-auth disable ──────────────────────────────
if [[ $DISABLE_SSH_PASSWORD -eq 1 ]]; then
  log "Disabling SSH password auth (--disable-ssh-password was passed)"
  # Hard guard: at least one non-empty authorized_keys must exist, otherwise
  # we'd be locking ourselves out. Walk every home dir.
  if ! find /home /root -maxdepth 3 -name authorized_keys -type f -not -empty 2>/dev/null | grep -q .; then
    echo "ABORT: no non-empty authorized_keys found in /home or /root." >&2
    echo "Add your public key first (echo 'ssh-ed25519 ...' >> ~/.ssh/authorized_keys)," >&2
    echo "verify key-based login from another terminal, then re-run." >&2
    exit 3
  fi
  sed -i \
      -e 's|^#\?PasswordAuthentication .*|PasswordAuthentication no|' \
      -e 's|^#\?PermitRootLogin .*|PermitRootLogin no|' \
      -e 's|^#\?ChallengeResponseAuthentication .*|ChallengeResponseAuthentication no|' \
      /etc/ssh/sshd_config
  # If a cloud-init drop-in file re-enables password auth, override it.
  if ls /etc/ssh/sshd_config.d/*.conf >/dev/null 2>&1; then
    for f in /etc/ssh/sshd_config.d/*.conf; do
      sed -i -e 's|^PasswordAuthentication yes|PasswordAuthentication no|' "$f" || true
    done
  fi
  sshd -t && systemctl restart ssh
  log "SSH password auth is now disabled. Key-based login only."
else
  log "SSH password auth left ENABLED (pass --disable-ssh-password to flip off)."
fi

log "Hardening complete."
log "Verify:"
log "  ufw status                   # firewall rules + default policy"
log "  fail2ban-client status sshd  # jail status"
log "  systemctl status unattended-upgrades"
log "  auditctl -l                  # loaded audit rules"
log "App still reachable at http://\$(hostname -I | awk '{print \$1}'):${STAGING_PORT}"
