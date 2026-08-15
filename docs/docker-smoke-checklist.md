# Docker image smoke-verification checklist

Sprint 52 verification hardening — `backend/Dockerfile` was added in
Sprint 51 BP-M3 (part of PR #169) and has never been built or run
outside a developer laptop. This checklist is what ops runs before the
first prod deploy that uses the image, and any time the Dockerfile,
`pom.xml`, or Spring Boot version is bumped.

The `dockerfile-lint` GitHub Actions workflow catches static issues on
every PR. This checklist covers the runtime side: does the image
actually build, start, and answer the health probe?

---

## Prerequisites

- Docker Desktop / Docker Engine 24+ with at least 6 GB free disk and
  4 GB RAM available to the daemon (the multi-stage build downloads
  ~350 MB of Maven deps into the intermediate layer).
- A checkout of `main` (or the branch under test) at the repo root.
- No local Postgres bound to `:5432` if you plan to run the container
  against a docker-compose'd DB (see optional §5 below); the container
  only needs an outbound connection to whichever DB the env vars point
  at.

## 1. Build the image

```bash
cd backend
docker build -t multiship-backend:smoke .
```

**Expected**
- Two-stage build completes in 3–8 min on a warm Maven cache (10–15 min
  cold). The build stage line ends with `mv target/*.jar target/app.jar`.
- Final `docker images multiship-backend:smoke` shows a size in the
  **~260-320 MB** range (JRE alpine + app jar + curl). If it's over
  500 MB, something is being copied that shouldn't be — recheck
  `.dockerignore`.

**Common failures**
- `mvn package` fails mid-build: usually a transient Maven Central
  outage. Rerun with `--no-cache` after 5 min.
- `apk add curl` fails: alpine repo mirror hiccup. Rerun.
- Build stage OOMs (`Killed` mid-`mvn package`): raise Docker Desktop
  memory to 4 GB+.

## 2. Inspect the image

```bash
docker inspect multiship-backend:smoke --format '{{json .Config}}' | jq .
```

**Verify**
- `.User` is `"65532:65532"` (non-root, matches K8s
  `runAsNonRoot: true`).
- `.ExposedPorts` contains `"8080/tcp"`.
- `.Healthcheck.Test` starts with `["CMD-SHELL", "curl -fsS ...`.
- `.Entrypoint` is `["java", "-jar", "/app/app.jar"]`.

## 3. Run the container against an existing DB

Point at whichever Postgres you have on hand (local dev DB, throwaway
docker-compose'd DB, or a shared staging cluster):

```bash
docker run --rm --name multiship-smoke -p 8080:8080 \
  -e DB_URL="jdbc:postgresql://host.docker.internal:5432/multiship_db" \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e JWT_SECRET="smoke-test-secret-do-not-use-in-production-32b" \
  -e SECRETS_ENCRYPTION_KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
  multiship-backend:smoke
```

**Expected startup log tail (within ~15-25 s)**
```
INFO ... Started BackendApplication in NN.NNN seconds
```
No `ERROR` lines from Flyway (migrations should apply cleanly on a
fresh DB, or be no-ops on an already-migrated DB — the fresh-DB guard
pattern in each V-migration keeps `IF EXISTS` on the drops).

**Common failures**
- `Connection refused` from Flyway on startup: `host.docker.internal`
  isn't reachable on Linux — use `--add-host=host.docker.internal:host-gateway`
  or `--network=host`.
- `JwtService` rejects `JWT_SECRET`: the value must be at least 32
  bytes (Sprint 49 Tier 0 hardening).
- Immediate exit with `SECRETS_ENCRYPTION_KEY is required`: env var
  missing; every user-secret write path hits the encryptor at boot.

## 4. Health probe checks

In a second terminal:

```bash
# Liveness — what the container HEALTHCHECK hits
curl -fsS http://localhost:8080/actuator/health/liveness
# Expected: {"status":"UP"}

# Readiness — what k8s should route traffic on
curl -fsS http://localhost:8080/actuator/health/readiness
# Expected: {"status":"UP"}

# Full health (includes DB check)
curl -fsS http://localhost:8080/actuator/health | jq .
# Expected: .status == "UP"; .components.db.status == "UP"
```

Wait 30 s and confirm the Docker healthcheck flips to `healthy`:

```bash
docker inspect multiship-smoke --format '{{.State.Health.Status}}'
# Expected: healthy   (starting -> healthy transition within ~60s)
```

**Common failures**
- Liveness `UP` but readiness `OUT_OF_SERVICE`: normal during the
  first ~10 s while the app is still warming pools. If it stays that
  way, the DB check failed — inspect `curl /actuator/health | jq` for
  the component that's down.
- Docker healthcheck stuck at `starting` for > 60 s: `curl` inside the
  container can't hit `localhost:8080` — usually means the JVM died
  silently. `docker logs multiship-smoke` should show the stack.

## 5. Sanity-hit one real endpoint

Prove the app is actually serving requests, not just the actuator:

```bash
curl -s http://localhost:8080/api/v1/carriers/health
# Expected: any 2xx JSON. If unauthenticated 401, that's ALSO fine —
# it proves the SecurityFilterChain is wired.
```

## 6. Clean up

```bash
docker stop multiship-smoke   # if not already exited
docker rmi multiship-backend:smoke
docker image prune -f          # clear intermediate build layers
```

---

## What the CI hadolint job does NOT catch

Static analysis can't verify:
- The `mvn package` step actually produces a runnable fat jar with all
  required Spring Boot autoconfigurers on the classpath.
- The runtime image can start with production env vars (Redis, Flyway,
  encryption key format).
- `HEALTHCHECK` actually returns 0 (curl might be missing at runtime
  if the alpine `apk add` line regresses).
- Startup time and memory footprint under a realistic heap size
  (`-Xmx` from container limits).

Those require an actual `docker build` + `docker run` — this checklist.

---

## Agent-side verification status (Sprint 52)

- **Static audit of `backend/Dockerfile`**: completed. Findings:
  no critical issues; base-image digest pinning and curl version
  pinning are the only hadolint-visible gaps and are demoted in
  `.hadolint.yaml` as consciously-accepted tech debt.
- **Local `docker build`**: not attempted from the agent sandbox
  (out of scope per the verification-hardening instructions —
  scope was "add static analysis to CI + document runtime steps
  for ops", not "run the build ourselves"). Ops should walk this
  checklist once before the next prod push.
- **CI wiring**: `.github/workflows/dockerfile-lint.yml` now runs
  hadolint on every PR touching the Dockerfile.
