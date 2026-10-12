# Staging deployment runbook

One Linux box, Docker Compose, auto-redeploy on push to the `staging` branch.

## What's in this branch

| File | Role |
|---|---|
| `docker-compose.staging.yml` | Full stack: Postgres + Redis + backend + frontend + reverse-proxy nginx |
| `backend/Dockerfile.staging` | Multi-stage build (Maven → JRE 21) |
| `multiship-react/Dockerfile.staging` | Vite build → inner nginx static serve |
| `nginx/staging.conf` | Outer nginx — proxies `/api/v1`, `/actuator` to backend, everything else to the SPA |
| `backend/src/main/resources/application-staging.properties` | Spring profile overrides (Oracle off, cookie policy, Redis host) |
| `.env.staging.example` | Env-var template (secrets) |
| `scripts/bootstrap-staging.sh` | One-shot fresh-server setup |
| `scripts/deploy-staging.sh` | Idempotent redeploy |
| `.github/workflows/deploy-staging.yml` | Auto-deploy on push |

## First-time setup (one-time, ~15 min)

### 1. SSH key for CI → server

On your workstation:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/shipai-staging-deploy -C "shipai-staging-ci" -N ""
cat ~/.ssh/shipai-staging-deploy.pub
```

Copy the public key — you'll paste it into `~/.ssh/authorized_keys` on the staging box in step 3.

### 2. Add the three GitHub Secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Name | Value |
|---|---|
| `STAGING_HOST` | Hostname or IP of the staging box |
| `STAGING_SSH_USER` | Linux user that will own the deploy (e.g. `ubuntu`, `deploy`) |
| `STAGING_SSH_KEY` | Paste the entire contents of `~/.ssh/shipai-staging-deploy` (the PRIVATE key) |
| `STAGING_SSH_PORT` | Optional. Default 22 |

### 3. Bootstrap the Linux box

SSH into the fresh box as a sudo-capable user, then:

```bash
# Append the CI public key to authorized_keys (so GitHub Actions can SSH)
echo 'PASTE-THE-PUBLIC-KEY-FROM-STEP-1' >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys

# One-shot bootstrap — installs Docker + clones repo + generates .env
curl -fsSL https://raw.githubusercontent.com/<org>/<repo>/staging/scripts/bootstrap-staging.sh \
  | sudo bash -s -- https://github.com/<org>/<repo>.git
```

Replace `<org>/<repo>` with your GitHub path twice.

Log out, log back in (so your user picks up docker-group membership).

### 4. First deploy

```bash
cd /opt/shipai
docker compose -f docker-compose.staging.yml up -d --build
```

Takes ~3 min first time (Maven cache + npm install). Verify:

```bash
docker compose -f docker-compose.staging.yml ps
curl http://localhost:$(grep STAGING_PORT .env | cut -d= -f2)/actuator/health
```

Backend's `/actuator/health` returns `{"status":"UP"}` on green (or `503` with details on partial — e.g. the stub oracle datasource is intentionally absent and does not report down because Oracle module is disabled entirely via `multiship.oracle.enabled=false`).

### 5. Create the admin user

The default seed in `AdminUserSeeder` creates `admin` on first boot (password is auto-generated and logged — grep `docker compose logs backend | grep -i "default admin"`). Change it immediately via `/profile`.

### 6. Verify end-to-end

- Open `http://<staging-host>:<STAGING_PORT>` in a browser
- Log in as admin
- `/settings/carriers` → Add a sandbox carrier (UPS/FedEx/Stamps.com/DHL)
- Create a test shipment → verify label

## Ongoing deploys

Push to the `staging` branch → GitHub Actions SSHes in and runs `scripts/deploy-staging.sh` → pull + rebuild + rolling restart. Health check waits up to 2 min for the backend to come back `UP`, otherwise dumps the last 100 log lines and fails the workflow.

Manual redeploy on the box:

```bash
/opt/shipai/scripts/deploy-staging.sh
```

## Rollback

```bash
cd /opt/shipai
git log --oneline -10   # pick a prior commit
git checkout <sha>
docker compose -f docker-compose.staging.yml up -d --build
```

## Common surfaces

| Need | Where |
|---|---|
| Backend logs | `docker compose -f docker-compose.staging.yml logs -f backend` |
| Postgres shell | `docker compose exec postgres psql -U postgres multiship_db` |
| Reset the DB (destructive) | `docker compose down -v && docker compose up -d --build` |
| Change port | Edit `STAGING_PORT` in `.env`, then `docker compose up -d` |
| Rotate `JWT_SECRET` | Edit `.env`, `docker compose restart backend` — every session invalidates |
| Enable Oracle NDS | Set `MULTISHIP_ORACLE_ENABLED=true` in `.env`, restart backend, configure `nds-default` credentials via `/settings/external-systems` |

## Server-level hardening (one-shot after first-deploy green)

Once the stack is reachable + login works, apply host-level security:

```bash
sudo /opt/shipai/scripts/harden-staging.sh
```

Idempotent — safe to re-run. By default it:

| Layer | What |
|---|---|
| **UFW** | Default deny-in, allow 22 (SSH) + `STAGING_PORT`, allow all egress |
| **fail2ban** | SSH jail — 5 fails in 10 min → 10 min ban |
| **unattended-upgrades** | Ubuntu security + updates channels auto-apply |
| **auditd** | Tracks sudo, user DB changes, SSH config edits, root activity |

**Opt-in SSH password-auth disable** (only after confirming key-based login works from a second terminal):

```bash
sudo /opt/shipai/scripts/harden-staging.sh --disable-ssh-password
```

Script hard-aborts if no non-empty `authorized_keys` is found anywhere — won't lock you out.

Container-side hardening already baked into `docker-compose.staging.yml`:
- `no-new-privileges` on backend/redis/nginx
- `cap_drop: ALL` + minimal `cap_add` per service
- Backend runs as non-root uid 10001

Verify after:

```bash
ufw status
fail2ban-client status sshd
auditctl -l
docker inspect shipai-backend-1 --format '{{.HostConfig.CapDrop}} / {{.HostConfig.SecurityOpt}} / {{.Config.User}}'
```

## Caveats this setup accepts

- **Plain HTTP** per the deployment choice. The httpOnly JWT cookie uses `SameSite=Lax` + `Secure=false` so it survives HTTP same-origin. Browsers on a non-localhost HTTP origin will refuse `SameSite=None` cookies — the stack explicitly avoids them.
- **Build on the server** instead of pulling pre-built images. ~3 min per deploy. Simpler than GHCR + no PAT scope to manage.
- **No TLS termination** at nginx — switch to Caddy or add certbot once you have a domain.
- **Oracle NDS off**. Pre-boot DataSource probe is skipped entirely via `@ConditionalOnProperty`. Flip when staging needs DTC-sync QA.
- **No backups**. Postgres volume is Docker-managed; `docker compose down -v` wipes it. Add `pg_dump` cron when staging stops being disposable.
