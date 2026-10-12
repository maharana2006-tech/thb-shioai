#!/usr/bin/env bash
## Idempotent redeploy — runs on every push to the 'staging' branch (via the
## GitHub Actions workflow at .github/workflows/deploy-staging.yml) OR by hand
## on the server. Pulls, rebuilds, rolls over.

set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/shipai}"
COMPOSE_FILE="docker-compose.staging.yml"

cd "$INSTALL_DIR"

echo "==> git pull (staging)"
git fetch origin
git checkout staging
git pull --ff-only origin staging

echo "==> docker compose build (rebuild changed layers only)"
docker compose -f "$COMPOSE_FILE" build

echo "==> docker compose up -d (rolling)"
docker compose -f "$COMPOSE_FILE" up -d

echo "==> waiting for backend health (max 2 min)"
for i in $(seq 1 24); do
  if curl -fsS http://localhost:${STAGING_PORT:-8080}/actuator/health/liveness >/dev/null 2>&1; then
    echo "==> backend healthy after ${i}0s"
    exit 0
  fi
  sleep 5
done

echo "==> backend did NOT come up healthy in 2 min — check logs:"
docker compose -f "$COMPOSE_FILE" logs --tail 100 backend
exit 1
