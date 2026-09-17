# Backend performance audit (2026-09-17)

Static analysis across four surfaces — N+1 loops, slow queries + missing indexes, Hikari pool + transaction duration, async / thread-pool pressure + unbounded in-memory state. Same section shape as `docs/stamps-com-cross-flow-audit.md` so triage is comparable.

**Baseline before audit**: Sprint 49 Tier 2 (Sept 2026) noted the "carrier RTT inside @Transactional" pattern as a known follow-up; Sprint 51 added Caffeine caches on 3 hot paths. Everything below either updates that context or points at a new gap.

## TL;DR

**Real risk today is small** — Stamps + USPS_DIRECT quotas keep peak DB pressure below ~1/2 of pool capacity, most N+1 hotspots hit low-cardinality lists (typically < 50 rows), and Caffeine already caps the token / rate caches on the hot path. But the paths that WILL bite as tenancy grows are all uniform: (a) unbounded in-memory `Set<Long>` growing forever, (b) 4 carrier connector token caches without eviction, (c) fanOut executor queues without bounds, and (d) one MPS-adjacent long-@Transactional path that holds a row lock across the carrier HTTP call.

**Highest-signal findings**:
- **PERF-B1 (BLOCKER-latent)** — `CarrierServiceImpl.generateLabel/generateManualLabel` hold a pessimistic row-lock across a 5–15s carrier HTTP call. Documented as Sprint 49 Tier 2 follow-up but never split. 24-worker bulk × 15s RTT = pool exhaustion once concurrency crosses ~pool/2.
- **PERF-B2 (BLOCKER-latent)** — `BulkLabelServiceImpl.fanOutExecutor` and `OrderImportServiceImpl.fanOutExecutor` use `newFixedThreadPool(24)` with **unbounded** `LinkedBlockingQueue`. A stalled carrier + burst import piles 1000+ tasks in memory → OOM path.
- **PERF-M1** — 4 carrier connector token caches (USPS/UPS/FedEx/DHL) grow forever. Real-world cardinality is < 100 accounts, so today it's negligible, but no upper bound and no TTL — the day a tenant rotates creds daily is the day it starts leaking.

## Findings summary

### BLOCKER-latent (real but bounded by current load)

| ID | Surface | File:line | Description |
|---|---|---|---|
| **PERF-B1** | Tx duration | `CarrierServiceImpl.java:427, 891` | `generateLabel` / `generateManualLabel` hold pessimistic row-lock during 5–15s carrier HTTP RTT. Documented Sprint 49 Tier 2 follow-up. Pool starvation risk once bulk concurrency crosses pool/2. |
| **PERF-B2** | Async | `BulkLabelServiceImpl.java:321-327`, `OrderImportServiceImpl.java:421-427` | `newFixedThreadPool(24)` with unbounded LinkedBlockingQueue. Burst + stalled carrier → OOM path. |

### MAJORs

| ID | Surface | Description |
|---|---|---|
| **PERF-M1** | State | 4 connector token caches (`UspsOAuthTokenCache`, `UpsConnector`, `FedExConnector`, `DhlConnector`) use raw `ConcurrentHashMap` — no TTL, no size cap. Wrap in Caffeine `expireAfterWrite(8h) + maximumSize(1000)`. |
| **PERF-M2** | State | `OrderImportServiceImpl.cancelledBatchIds` + `BulkLabelServiceImpl.cancelledJobIds` — `ConcurrentHashMap.newKeySet`. Cleared in `finally` blocks; verify all terminal paths clear. Belt-and-braces: add Caffeine TTL wrapper. |
| **PERF-M3** | State | `SseController.live` — SSE emitter map, no cleanup for hard-closed tabs. Add scheduled ping-and-evict every 5 min. |
| **PERF-M4** | State | `CarrierOutboundRateLimiter.recent429Ms` — per-carrier deque of 429 timestamps, pruned on recompute but no hard cap. Cap at `RATE_LIMIT_TRIGGER_COUNT + 1`. |
| **PERF-M5** | State | `ClientErrorReportController.reportsByIp` — clears the ENTIRE map on overflow (line 88). Attacker-controlled `X-Forwarded-For` can trigger 10k full clears/s. Replace with Caffeine `expireAfterAccess(10 min)`. |
| **PERF-M6** | Async | `RateShopServiceImpl.executor` — hardcoded `newFixedThreadPool(8)`, unbounded queue, no PreDestroy. Externalize config + bound queue. |
| **PERF-M7** | Tx duration | `MultiWarehouseLabelServiceImpl:54-106` — nested @Transactional loop, each child does carrier HTTP inside the tx. 3 warehouses × 10s = 30s outer tx holding 1 connection. Split each child to `REQUIRES_NEW`. |
| **PERF-M8** | Tx duration | `VoidServiceImpl:79-150` — @Transactional wraps token acquisition + carrier void call. Double-click void → 30s carrier-timeout while holding the lock. |
| **PERF-M9** | N+1 | `ClientServiceImpl.exportClientsCsv` + `listClients` (lines 84, 149) — filters return N client codes, then re-fetches each via `findByClientCodeIgnoreCase` individually. Add `findByClientCodeInIgnoreCase(List)` batch method. |
| **PERF-M10** | N+1 | `ClientServiceImpl.toDTO(...)` (line 567) — every DTO conversion fires `carrierAccountRefRepository.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(clientCode)`. Amplifies M9. Pre-load all accounts for the page. |
| **PERF-M11** | Slow query | `AuditLogRepositoryCustomImpl:79-80` — WHERE `client_code = :scope AND createdAt BETWEEN :from AND :to`. Composite index `(client_code, created_at DESC)` missing; scope alone is indexed. Add via V65. |
| **PERF-M12** | Slow query | `ClientRepository.filterCodes:79-80` — ORDER BY includes correlated `SELECT COUNT(*)` subquery. 1k clients → 1k COUNTs per page load. Split: fetch codes, then materialize counts in application. |
| **PERF-M13** | Slow query | `UspsLabelQueueRepository.findByStatusOrderByPriorityAscEnqueuedAtAsc:75` — no LIMIT / Pageable. Processor tick materializes ALL queued items every tick. Add `Pageable`, cap at batchSize (default 1). |
| **PERF-M14** | Slow query | `OrderTrackingRepository.findByStatus:50` + `findByIsLabelGeneratedFalse:52` — unbounded return. Table grows unbounded per label. Callers should already paginate; audit + add `Pageable` overloads. |

### Downgraded / cleared during grading

| ID | Reason |
|---|---|
| **N+1-5** (OrderCustoms.items) | Already `FetchType.EAGER` per Sprint 48 audit fix. |
| **SLOW-1** (order_label_tracking.status) | Indexed at startup via `IndexInitializer.java:78` — false positive. |
| **SLOW-4** (LOWER(shipto_city)) | Trigram index exists via `IndexInitializer.java:83` (`idx_label_batch_shipto_city_trgm`) — false positive for the specific LIKE pattern. |
| **SLOW-7** (order_label_tracking.order_no join) | Indexed at startup via `IndexInitializer.java:74` — false positive. |
| **POOL-5** (OrderImport commit()) | Workers already execute outside @Transactional — verified during grading. |

**Grading lesson**: `IndexInitializer.java` creates 6+ indexes at startup that DON'T appear in `db/migration/V*.sql`. Static SQL grep alone misses them. For future audits: grep both `db/migration/**.sql` AND `**/IndexInitializer.java` for `CREATE INDEX`.

## Proposed fix track — 4 stacked PRs (~1200 LoC total)

Uses letter **P** (Performance).

### PR-P1 — Bound the fanOut executor queues + Caffeine-wrap token caches (~400 LoC, MEDIUM risk)
**Fixes:** PERF-B2, PERF-M1, PERF-M6.
- Replace `newFixedThreadPool(24)` + LinkedBlockingQueue in `BulkLabelServiceImpl` + `OrderImportServiceImpl` + `RateShopServiceImpl` with `ThreadPoolTaskExecutor` (Spring bean): bounded queue (200), `CallerRunsPolicy`, PreDestroy shutdown. FairTenantExecutor already gates per-tenant so back-pressure is acceptable.
- Wrap all 4 carrier connector token caches (`UspsOAuthTokenCache`, `UpsConnector.tokenCache`, `FedExConnector.tokenCache`, `DhlConnector.tokenCache`) in Caffeine with `expireAfterWrite(8h) + maximumSize(1000)`. Retain the existing `CachedToken` shape as the value; only the map wrapper changes.

### PR-P2 — Missing indexes + query pagination (~200 LoC + V65 migration, LOW risk)
**Fixes:** PERF-M11, PERF-M13, PERF-M14.
- V65: `idx_audit_log_client_code_created ON audit_log (client_code, created_at DESC)`.
- Add `Pageable` overloads to `UspsLabelQueueRepository.findByStatus...` + `OrderTrackingRepository.findByStatus` / `findByIsLabelGeneratedFalse`. Update callers to pass batchSize / page.
- Delete unbounded overloads once callers migrate (or `@Deprecated` first, remove in a follow-up).

### PR-P3 — Long-tx split for CarrierServiceImpl (~500 LoC, HIGH risk)
**Fixes:** PERF-B1, PERF-M7, PERF-M8.
- Split `generateLabel` / `generateManualLabel` into three phases: (a) `@Transactional` validate + acquire row lock + release; (b) no-tx carrier HTTP RTT; (c) `@Transactional REQUIRES_NEW` persist result. Requires a new `IN_FLIGHT` status on `order_label_tracking` so retries can distinguish "carrier in-flight" from "never sent".
- Same split for `VoidServiceImpl.voidLabel` + `MultiWarehouseLabelServiceImpl` child-shipment loop.
- Risk: touches the single most-tested code path in the service layer. Ship behind a feature flag (`carrier.tx-split-phase-c`) and dark-launch.

### PR-P4 — In-memory state hygiene + SSE eviction (~100 LoC, LOW risk)
**Fixes:** PERF-M2, PERF-M3, PERF-M4, PERF-M5.
- Wrap `cancelledBatchIds` + `cancelledJobIds` in Caffeine `expireAfterAccess(24h)` (defense in depth on top of the existing `finally` clears).
- `SseController`: `@Scheduled(fixedDelay=5min)` ping-and-evict.
- `CarrierOutboundRateLimiter.recent429Ms`: cap deque at 4.
- `ClientErrorReportController.reportsByIp`: swap to Caffeine `expireAfterAccess(10 min)`.

### PR-P5 (deferred) — N+1 fixes in ClientServiceImpl
**Fixes:** PERF-M9, PERF-M10.
- Batch `findByClientCodeInIgnoreCase(List)` + pre-load carrier accounts for a page of clients.
- Deferred because blast radius on today's client-list page is < 50 rows / < 100 amplified lookups. Ships when a customer with 500+ clients complains.

## Dispatch strategy

Mirror the G/S-track playbook:

1. **P1 alone** — the two BLOCKER-latents (fanOut bound + token caches) close the two "unbounded state" categories cheaply; low review risk.
2. **P2 + P4 in parallel** — independent (indexes vs in-memory), both LOW risk.
3. **P3** alone last — needs its own review cycle. Feature-flagged.

Total: 4 PRs / ~1200 LoC / ~2-3 days.

## Non-goals for this track

- **Full APM instrumentation** — Micrometer + Prometheus exposition. Separate infra track.
- **PgHero / pg_stat_statements dashboard rollout** — DBA-adjacent, not code.
- **N+1 fixes in ClientServiceImpl** (P5) — deferred, blast radius is small today.
- **BULK_WORKER_CONCURRENCY tuning** — application.properties formula already gates safely (`min(pool/2, 32)`); leave alone.
- **CarrierServiceImpl full refactor** — the audit only touches the carrier-HTTP-inside-tx pattern via PR-P3's phase-C split. The wider ~5k-line file is a separate cleanup track.

## Profiler run — 2026-09-17 (findings amendment)

Ran the freshly-built backend against a dev Postgres + Redis, logged in via `/api/v1/auth/login`, scraped `/actuator/prometheus` at idle. **No synthetic load** — numbers below are steady-state after boot + one login round-trip. Concrete stress numbers still deferred to a proper `k6` / `JMeter` run.

### Runtime confirmations (static findings validated)

| Metric | Reading | Confirms |
|---|---|---|
| `hikaricp_connections_max` | **50.0** | PERF-B1 baseline; matches `application.properties` |
| `hikaricp_connections_idle` | 10.0 | matches `minimum-idle=10` |
| `hikaricp_connections_active` | 0.0 (idle) | as expected |
| `hikaricp_connections_pending` | 0.0 | no waits at idle |
| `hikaricp_connections_timeout_total` | 0.0 | no timeouts to date |
| `hikaricp_connections_usage_seconds_max` | **0.263s** | one long-hold on the login round-trip — meaningful but bounded |
| `jvm_threads_live_threads` | 31 | healthy at rest |
| `jvm_threads_states_threads{blocked}` | 0 | no lock contention at rest |
| `executor{name="taskExecutor"}` | core=4 / max=32 / queue-remaining=200 | matches `AsyncConfig` static finding |
| `executor{name="webhookExecutor"}` | core=8 / max=8 / queue-remaining=500 | matches `WebhookAsyncConfig` static finding |

### New findings uncovered by the profiler

| ID | Severity | Description |
|---|---|---|
| **PERF-M15** | MAJOR (new) | `executor{name="taskScheduler"}` reports `max_threads=2.147483647E9` (Integer.MAX_VALUE) with UNBOUNDED queue (2.147e9 remaining). That's the default Spring `ThreadPoolTaskScheduler` — no cap. Any `@Scheduled` method that stalls will spawn a fresh thread on every tick until OOM. **Fix in P1**: configure `TaskSchedulerBuilder` bean with explicit `poolSize` (e.g. 4) + `awaitTermination(30s)`. |
| **PERF-M16** | MAJOR (observability gap) | `BulkLabelServiceImpl.fanOutExecutor` + `OrderImportServiceImpl.fanOutExecutor` are `Executors.newFixedThreadPool(24)` — raw JDK executors NOT registered with Spring, so **Micrometer never sees them**. `/actuator/prometheus` shows only `taskExecutor` / `taskScheduler` / `webhookExecutor`. Ops has zero visibility into the two largest work queues on the platform. **Fix in P1**: swap to `ThreadPoolTaskExecutor` Spring beans (already planned for PERF-B2); confirm `@Bean` names surface as `executor{name="..."}` labels. |
| **PERF-N1** | NOTE | 3 `401 UNKNOWN` requests hit `/actuator/*` before the login round-trip. Actuator paths aren't `permitAll` in `SecurityConfig`. If the K8s liveness / readiness probes are wired to `/actuator/health/liveness` (as the endpoint config suggests), the probes will 401 unless the K8s deployment passes a bearer token / basic auth header. **Fix path**: either add `.requestMatchers("/actuator/health/**").permitAll()` to `SecurityConfig` or document the probe-header requirement in the deployment runbook. Out of P-track scope; log as an ops follow-up. |

### What the profiler could NOT confirm

- **PERF-B1** (long tx holding row-lock during carrier HTTP) — requires synthetic load with a mocked slow carrier. Idle metrics only show `usage_seconds_max=0.263s` from the login flow, which is fine at rest.
- **PERF-M2 / M3 / M4** (unbounded `Set<Long>` / SSE emitters / 429 deques) — no state buildup at idle. Would need a hot-path test to force accumulation.
- **PERF-M11 / M12** (missing indexes / slow queries) — no query-level metrics exposed. Would need `pg_stat_statements` + PgHero.

### Recommendation

Roll **PERF-M15** (taskScheduler unbounded) + **PERF-M16** (fanOut invisible to Micrometer) into P1's scope — both are natural extensions of the "convert raw JDK executors to Spring beans" work. **PERF-N1** goes on the ops follow-up list.

## Related memory

- [[usps-direct-integration]] — reference for Sprint 50 Tier 2 tx-timeout mitigation.
- [[stamps-com-audit]] — most recent audit track; same dispatch pattern.
- [[component-ctor-overload-needs-autowired]] — watch during P1's new `ThreadPoolTaskExecutor` bean wiring.
