#!/usr/bin/env bash
## One-shot bootstrap for a fresh Ubuntu 22.04 / 24.04 box.
## Installs docker + docker-compose-plugin + git, clones the repo at the
## staging branch, drops the operator at a .env template to fill in.
##
## Usage (as a sudo-capable user on the fresh box):
##   curl -fsSL https://raw.githubusercontent.com/<org>/<repo>/staging/scripts/bootstrap-staging.sh | sudo bash -s -- <https-repo-url>
## OR locally after SCP'ing the file:
##   sudo ./bootstrap-staging.sh <https-repo-url>

set -euo pipefail

REPO_URL="${1:-}"
if [[ -z "$REPO_URL" ]]; then
  echo "Usage: $0 <https-repo-url>" >&2
  echo "Example: $0 https://github.com/myorg/thb-shioai.git" >&2
  exit 2
fi

INSTALL_DIR="/opt/shipai"
DEPLOY_USER="${SUDO_USER:-$USER}"

log() { echo -e "\n==> $*"; }

# ─── 1. OS detection + package install ────────────────────────────────────
log "Detecting OS"
. /etc/os-release
case "$VERSION_ID" in
  22.04|24.04) log "Ubuntu $VERSION_ID — supported" ;;
  *) echo "WARN: Only tested on Ubuntu 22.04 / 24.04. Found: $PRETTY_NAME. Proceeding anyway."; ;;
esac

log "Installing prerequisites (git, curl, ca-certificates)"
apt-get update -y
apt-get install -y --no-install-recommends git curl ca-certificates gnupg

# ─── 2. Docker (official repo, not snap) ──────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
  log "Installing Docker Engine + Compose plugin"
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
  usermod -aG docker "$DEPLOY_USER"
  log "Added $DEPLOY_USER to docker group — log out + back in for group membership to take effect"
else
  log "Docker already installed — skipping"
fi

# ─── 3. Clone repo ────────────────────────────────────────────────────────
log "Cloning $REPO_URL to $INSTALL_DIR (staging branch)"
if [[ -d "$INSTALL_DIR/.git" ]]; then
  log "$INSTALL_DIR already a repo — pulling latest staging"
  git -C "$INSTALL_DIR" fetch origin
  git -C "$INSTALL_DIR" checkout staging
  git -C "$INSTALL_DIR" pull --ff-only origin staging
else
  mkdir -p "$INSTALL_DIR"
  git clone --branch staging "$REPO_URL" "$INSTALL_DIR"
fi
chown -R "$DEPLOY_USER:$DEPLOY_USER" "$INSTALL_DIR"

# ─── 4. .env scaffold ─────────────────────────────────────────────────────
if [[ ! -f "$INSTALL_DIR/.env" ]]; then
  log "Creating .env with freshly generated secrets"
  cp "$INSTALL_DIR/.env.staging.example" "$INSTALL_DIR/.env"
  POSTGRES_PASSWORD=$(openssl rand -base64 24 | tr -d '\n')
  JWT_SECRET=$(openssl rand -base64 60 | tr -d '\n')
  APP_ENCRYPTION_KEY=$(openssl rand -base64 32 | tr -d '\n')
  sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$POSTGRES_PASSWORD|" "$INSTALL_DIR/.env"
  sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$JWT_SECRET|" "$INSTALL_DIR/.env"
  sed -i "s|^APP_ENCRYPTION_KEY=.*|APP_ENCRYPTION_KEY=$APP_ENCRYPTION_KEY|" "$INSTALL_DIR/.env"
  chmod 600 "$INSTALL_DIR/.env"
  chown "$DEPLOY_USER:$DEPLOY_USER" "$INSTALL_DIR/.env"
  log "Generated .env with random secrets. Review and adjust STAGING_PORT if needed."
else
  log ".env already present — leaving untouched"
fi

log "Bootstrap complete."
log "Next steps:"
log "  1. Log out and back in (for docker group membership)."
log "  2. cd $INSTALL_DIR"
log "  3. Review .env (STAGING_PORT, etc)."
log "  4. docker compose -f docker-compose.staging.yml up -d --build"
log "  5. Hit http://$(hostname -I | awk '{print $1}'):\$STAGING_PORT"
