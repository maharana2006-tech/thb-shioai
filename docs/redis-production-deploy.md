# Redis Production Deploy — Hardening Checklist

## Overview

Redis is required in production. It currently backs the Idempotency-Key
replay store on the public v2 API (`POST /v2/shipments`), and the
Sprint 50 roadmap adds a per-tenant rate limiter and — potentially — a
webhook processing queue on the same instance. Losing Redis means
duplicate POSTs to `/shipments` create duplicate shipments; treat it
as a tier-1 dependency of the write path.

## Prerequisites

- Redis **7.0+** (matches the `redis:7-alpine` image used in
  `docker-compose.yml`).
- TLS certificates issued for the Redis endpoint if traffic will cross
  any untrusted network (public internet, cross-VPC peering, etc.).
- Access to whatever secret store the platform uses (Vault, AWS Secrets
  Manager, GCP Secret Manager, k8s Secret) so `REDIS_PASSWORD` is not
  committed to Git.

## Hardening Checklist

- [ ] **`requirepass`** set to a random string of **>= 32 characters**.
      Generate with `openssl rand -base64 48` (trim to your desired
      length). Pass to Redis via `--requirepass "$REDIS_PASSWORD"` or the
      matching `redis.conf` directive.
- [ ] **`bind`** restricted to the private interface(s) the app pod /
      VM reaches Redis on. Never bind to `0.0.0.0` in prod.
- [ ] **`protected-mode yes`** — belt-and-braces guard against the
      "bound to 0.0.0.0 with no password" footgun. Redis 7 ships this
      on by default; leave it on.
- [ ] **`maxmemory`** set to a value the host can safely give Redis
      (usually 70-80 % of the pod / VM memory) and
      **`maxmemory-policy allkeys-lru`**. The idempotency store already
      writes keys with a TTL, but LRU on top of that gives a graceful
      degradation path if a spike blows through the memory budget.
- [ ] **TLS** if any traffic crosses an untrusted network. See the
      Redis docs on
      [`tls-port` and `tls-cert-file`](https://redis.io/docs/latest/operate/oss_and_stack/management/security/encryption/).
      When TLS is on, set `spring.data.redis.ssl.enabled=true` on the
      Spring side (add a `REDIS_SSL_ENABLED` env var override).
- [ ] **Persistence:** `appendonly yes` for the idempotency store —
      losing the AOF means the replay window is gone and duplicate POSTs
      will slip through until the app rewrites the key. If Redis is
      **only** hosting the rate limiter (no idempotency), snapshot-only
      (`save`) is acceptable — the worst case is a rate-limit reset,
      not a duplicate write.
- [ ] **Backups:** whatever your platform's default is for the AOF /
      RDB files. Nightly is usually enough; the idempotency store's
      TTL is short so restore from a >24h old backup is a no-op.

## Env Vars the App Expects

| Env var                        | Default          | Effect if unset                                |
|--------------------------------|------------------|-----------------------------------------------|
| `REDIS_HOST`                   | *(blank)*        | Redis disabled — idempotency no-ops. Boot emits a **WARN** so the misconfig is loud (`REDIS DISABLED —` ...). |
| `REDIS_PORT`                   | `6379`           | Standard Redis port.                          |
| `REDIS_TIMEOUT`                | `2s`             | Spring Data Redis command timeout.            |
| `REDIS_PASSWORD`               | *(unused today)* | Reserved for prod — see docker-compose.yml commented-out `--requirepass` line. |
| `REDIS_AUTOCONFIGURE_EXCLUDE`  | Redis auto-configs (see below) | Spring Boot's `spring.autoconfigure.exclude`. Defaults to the Redis Lettuce + Repositories auto-configs so an unset `REDIS_HOST` cannot fail the app-context at bean-creation time. **To enable Redis, set this to `` (empty) alongside `REDIS_HOST`.** |

Note the "safe-by-loud" behavior: an empty `REDIS_HOST` no longer
silently falls back to `localhost`. `RedisPresenceLogger` fires on
`ApplicationReadyEvent` and prints a WARN with the exact operational
consequence, so a missing env var is one grep away in the boot log.

Because Boot 4's Lettuce factory validates the host at bean-creation
time (an empty host throws `'host' must not be empty` and wedges the
whole context), the default `spring.autoconfigure.exclude` in
`application.properties` also excludes:

```
org.springframework.boot.data.redis.autoconfigure.RedisAutoConfiguration
org.springframework.boot.data.redis.autoconfigure.RedisRepositoriesAutoConfiguration
```

To turn Redis on in prod you set two env vars:

```
REDIS_HOST=redis.svc.cluster.local
REDIS_AUTOCONFIGURE_EXCLUDE=
```

(The empty string on the second one clears the default exclusion. It
is intentional that this is explicit — enabling Redis in a live
environment is a decision that should be made once, on purpose, per
env.)

## Health Check

From a pod / host on the same private network:

```
redis-cli -h <REDIS_HOST> -p <REDIS_PORT> -a "$REDIS_PASSWORD" ping
```

Expected: `PONG`. For TLS, add `--tls` and `--cacert`.

The Spring app itself hits Redis via the idempotency filter on the
first `POST /v2/shipments` after boot; a connection failure surfaces
in the app logs as a warning from the idempotency store, and the
request is served as if Redis were absent (duplicate protection off
for that call). Alert on that log line at any non-trivial rate.

## Observability Follow-ups

Not blocking for the first prod deploy, but the natural next steps:

- **Micrometer Redis metrics** — Spring Boot exposes Lettuce metrics
  when you add `management.metrics.enable.lettuce=true` (or via
  `LettuceClientConfigurationBuilderCustomizer`). Feeds command
  latency and connection-pool gauges to whatever scrape target is
  wired up.
- **Prometheus scrape** — either the Micrometer registry above, or a
  Redis-side `redis_exporter` if you want per-key-pattern stats.
- **Key-expiry rate** — the idempotency store writes keys with a TTL;
  a sudden change in the expiry rate is the earliest signal that
  either write volume shifted or the TTL config drifted.
- **Memory alert** — page at 80 % of `maxmemory` so the LRU eviction
  path is investigated before it starts silently dropping keys the
  app still expects.
