# USPS_DIRECT integration — cross-flow audit (2026-09-16)

Status: FINDINGS — design doc for the follow-up fix track. No code yet.
Author: 2026-09-16 session, 3 parallel audit agents on manual / bulk-import / bulk-background paths.

## TL;DR

The USPS_DIRECT integration is **production-broken on 3 of the 4 label-generating paths**. Only `BulkLabelServiceImpl.processOneOrder` (the operator-triggered bulk-label modal) correctly routes USPS_DIRECT calls through the 55/hr `UspsLabelQueueService`. Every other path — manual single-order, bulk-import operator, bulk-import background worker — calls `CarrierServiceImpl.generateManualLabel` (or `generateLabel`) which dispatches directly to `UspsDirectConnector.createShipment` without touching the queue.

**Operational impact:** any tenant issuing >55 USPS labels/hour via any path other than the bulk-label modal exhausts the platform's shared 60 req/hr USPS Direct OAuth quota, cascading 429s to every other tenant using the queue-based path. A single 500-order background import job triggered by any tenant blows the quota for the entire platform within ~10 minutes.

**Root cause:** `maybeEnqueueUspsDirect(long orderNo)` (PR-F1) was only wired into `BulkLabelServiceImpl.processOneOrder`. Its javadoc explicitly documents "the BULK path" — meaning the operator-triggered bulk-label modal — but three other paths were never modified to call it.

## Cross-flow findings summary

### BLOCKERs (all 3 flows share the same root pattern)

| ID | Flow | File:line | Symptom |
|---|---|---|---|
| **B-M1** | Manual | `CarrierServiceImpl.java:855` (`generateManualLabel`) → `:1513` / `:2282` (`connector.createShipment`) | Manual /orders/manual-label bypasses queue. Live wire call per operator click. Two operators clicking Generate ~30×/min exhaust platform quota. |
| **B-M2** | Manual | `OrderController.java:1506-1521` → `CarrierServiceImpl.java:410` / `:421` → `attemptShipment` → `:2282` | List-view Generate button, /orders/{n}/label endpoint — same bypass. |
| **B-M3** | Manual | `UspsDirectConnector.java:649-692` (`createShipment`) called with multi-package DTO | Manual MPS under USPS_DIRECT sends ONE batched request. USPS v3 has no batch endpoint — will fail with opaque error. |
| **B-I1** | Bulk import | `OrderImportServiceImpl.java:4650-4651` (`processGroup` → `carrierService.generateManualLabel(req, null, existingOrderNo)`) fan-out via 24-thread pool | Bulk-import Generate-all bypasses queue. 100-order USPS_DIRECT import → 24 concurrent connector calls → quota blown in ~2.5 min. |
| **B-I2** | Bulk import | Same as B-I1 | MPS orders in import never split via `UspsMpsSplitterService`. Each becomes one batched call. |
| **B-B1** | Bulk background | `ImportGenerationWorker.java:71` (`@Scheduled(fixedDelayString=…)`) → `executeGenerationJob` → `commit` → `processGroup` → same site as B-I1 | Background worker fires labels WITHOUT operator. 1000-row job blows quota in minutes. **Worst blast radius — no operator to notice the burst.** |
| **B-B2** | Bulk background | Same as B-B1 | MPS in background import bypasses splitter — same as B-I2 but silent. |
| **B-B3** | Bulk background | `OrderImportServiceImpl.java:1864-1896` | Retry-of-rate-limited-groups loop AMPLIFIES the burst — re-fires the same connector calls after waiting for a per-carrier pause, doubling quota burn. |

### MAJORs (functional / UX / consistency issues that will bite ops within a day of production use)

| ID | Flow | Description |
|---|---|---|
| **M-M1** | Manual | Intl-MPS guard latent gap (audit-agent-1 flagged; task #32 addresses this in `UspsMpsSplitterService`). |
| **M-M2** | Manual | Manual idempotency key (`user:{name} + Idempotency-Key` header) and queue key (`usps-queue-{itemId}`) live in different namespaces — retry-inside-idempotency-window races the queue. |
| **M-I1** | Bulk import | Retry-with-`onlyFailed=true` throws `IllegalStateException` on existing live queue rows (unique `shipment_id`). Import row goes FAILED even though queue is doing its job correctly. |
| **M-I2** | Bulk import | Import path has its own `carrierPauseUntil` map reacting to `CARRIER_RATE_LIMITED` — but is unaware of queue. Bursts to platform cap; queue-based paths see phantom exhaustion. |
| **M-B1** | Bulk background | `FairTenantExecutor` is JVM-level, NOT USPS 60/hr cap-level. Two concurrent tenant import jobs starve each other under USPS_DIRECT because both bypass `UspsLabelQueueFairScheduler`. |
| **M-B2** | Bulk background | On restart, `requeueStale` re-picks jobs; the resumed run's un-labelled rows still land in sync `generateManualLabel` path. |
| **M-B3** | Bulk background | Silent fall-back to sync in `maybeEnqueueUspsDirect` is DANGEROUS for background contexts — no operator watching means the fallback IS the blocker. Must alert loud. |
| **M-B4** | Bulk background | Cancellation of a background import batch doesn't cascade to `uspsLabelQueueService.cancel(queueItemId)`. Cancelled batch keeps generating labels via the queue. |

### Audit-log + design consistency drift

| ID | Description |
|---|---|
| **D1** | Idempotency-key format diverges across paths: `usps-queue-{itemId}` (queue wiring) vs `user:{name}+header` (manual controller) vs `null` (import controller passes null). Once fixes cross paths, same order can generate under different keys. |
| **D2** | Audit-log event type diverges: queue-serviced labels log `LABEL_GENERATED`; import-serviced labels log aggregate `IMPORT_GENERATED`. Analytics can't join. |
| **D3** | Tenant-code derivation differs: queue uses `order.getTenantId()` → `order.getCustNo()` → `"unknown"`; import uses `job.getRequestedScope()` (auth-time truth — probably the correct source). |

### Test coverage gaps (across all 3 flows)

- **T-M1** — No test for `generateManualLabel` routing through queue under `USPS_DIRECT`. Only `BulkLabelServiceImplUspsQueueTest` covers PR-F1's integration.
- **T-M2** — No test for queue-processor recursion guard (username = `usps-queue-processor` skips re-enqueue).
- **T-M3** — No test for manual MPS under USPS_DIRECT fanning to splitter.
- **T-M4** — No test for manual intl-MPS rejection.
- **T-M5** — FE: `NewShipmentPage.test.tsx` has zero USPS_DIRECT branch coverage.
- **T-I1** — Zero USPS_DIRECT tests in `OrderImportServiceImpl*Test` — 4 existing files only cover UPS/FedEx branches (grep `USPS` in `OrderImportServiceImplTest` returns nothing).
- **T-B1** — `ImportGenerationJobTest` has ZERO USPS_DIRECT branches — `carrierSucceeds` mocks `generateManualLabel` blindly.
- **T-B2** — No integration test for "background worker enqueues → queue processor drains → import job finishes as COMPLETE."
- **T-B3** — No test asserts `requeueStale` doesn't re-enqueue rows with live queue items.
- **T-B4** — No test for MPS-in-import.

### UX gaps

- **U1** — After manual routes to queue, FE sees `ACCEPTED/queueItemId` but `NewShipmentPage.tsx:2354/2554` treats any non-error response as label-ready → operator sees fake success.
- **U2** — `BulkLabelQueueBadge` NOT mounted in `OrderImportModal.tsx` or `DataHistoryPage.tsx` (only in `BulkLabelModal.tsx:350` + `UspsDirectDashboardPage.tsx`). 500-order USPS_DIRECT import shows per-row spinner with no queue-depth signal.
- **U3** — `MpsProgressCard` NOT mounted in Data History rows — MPS import parents look "stuck" with no per-piece feedback.
- **U4** — No "USPS is rate-limited" warning banner in `OrderImportModal` before commit.
- **U5** — Retry-exhausted queue FAILED items don't bridge back to import row's `generatedStatus`. Operators see row stuck at "QUEUED" even after queue gives up.
- **U6** — Admin dashboard (PR-F4) aggregates queue depth without a `source` dimension — can't answer "is this spike from a background job?"
- **U7** — Import batch progress endpoint reports `progressDone` when row is enqueued (misleading) — needs distinct `QUEUED` state visible to FE poller.
- **U8** — Enqueue-failure surfacing missing — background job silently fell back to sync (see M-B3); operator has no alert.
- **U9** — No admin "kill switch" for per-source pause (import vs manual vs bulk).

## Proposed fix track — 4 stacked PRs (~2500 LoC total)

The 3 flow audits converged on a single central refactor: **extract `maybeEnqueueUspsDirect` into a shared `UspsDirectRoutingService`** that all 4 label-generating call sites invoke. Everything else stacks on that.

### PR-G1 — Shared `UspsDirectRoutingService` + manual-path routing (~450 LoC, HIGH risk)
**Fixes:** B-M1, B-M2, B-M3, M-M2, T-M1, T-M2, T-M3, D1 (partial).

**Scope:**
- Extract `BulkLabelServiceImpl.maybeEnqueueUspsDirect(long orderNo)` into a new `UspsDirectRoutingService` service. Signature preserved; both `BulkLabelServiceImpl` and `CarrierServiceImpl` now call the shared service.
- Wire into `CarrierServiceImpl.generateManualLabel` (entry from `OrderController.java:1533` + `:1566`) and `CarrierServiceImpl.generateLabel(Long, UserDetails, String, Long)` (entry from `OrderController.java:1506`).
- Add re-entrancy guard: skip enqueue when `userDetails.getUsername().equals(UspsLabelQueueWiring.QUEUE_SYSTEM_USER)` — otherwise queue processor call loops back and re-enqueues itself.
- MPS branch (`packageCount >= 2`) → `UspsMpsSplitterService.splitAndEnqueueForOrder`.
- Extend `LabelGenerationResponse` with `status="QUEUED"` + `queueItemId` + `mpsPieceCount` fields.
- Add `ErrorCode.INTL_MPS_UNSUPPORTED`.
- Idempotency-key normalization: `usps-queue-{itemId}` used by wiring; caller-provided keys stored as `originIdempotencyKey` so cross-path dedupe works.
- Tests: 5 new (T-M1 through T-M4) — assert manual routes to queue, MPS fans out, recursion guard fires, intl-MPS rejected.

**Risk:** HIGH — refactor of the hottest label-generation code path. Regression test glob mandatory: `*CarrierService*,*BulkLabel*,*UspsDirect*,*UspsLabelQueue*,*UspsMps*`.

### PR-G2 — Bulk-import routing + retry idempotency (~450 LoC, HIGH risk)
**Fixes:** B-I1, B-I2, M-I1, M-I2, D3, T-I1, U7.

**Scope:**
- Route `OrderImportServiceImpl.processGroup` (~`:4640`) through `UspsDirectRoutingService` before calling `generateManualLabel`.
- Row transitions: `PENDING` → `QUEUED_USPS` (new `generatedStatus` value) → `GENERATED` (via queue callback bridge in PR-G3) or `FAILED`.
- Idempotency key: `import-{importBatchId}-{orderNo}` — stable across retry-with-`onlyFailed=true`.
- Handle `DataIntegrityViolationException` on existing queue row: look up the existing item, treat as "already queued, wait" — do NOT throw `IllegalStateException` or fall back to sync.
- Extend import batch progress endpoint with distinct `progressQueued` count.
- Tests: `OrderImportServiceImplUspsDirectRoutingTest` — 5 cases (STAMPS_COM/USPS+sync, USPS_DIRECT/USPS+enqueue, USPS_DIRECT/FEDEX+sync, retry-with-still-queued, MPS-in-import).

**Risk:** HIGH — touches the import commit hot path. Must not regress non-USPS or STAMPS_COM paths.

### PR-G3 — Background worker routing + queue→import bridge + alerting (~700 LoC, HIGH risk)
**Fixes:** B-B1, B-B2, B-B3, M-B1, M-B2, M-B3, M-B4, U8, U5, D2, T-B1, T-B2, T-B3, T-B4.

**Scope:**
- `ImportGenerationWorker` → `executeGenerationJob` → `commit` → `processGroup` uses PR-G2's routing. No new call site needed — same import service.
- Fair-scheduler alignment: replace `FairTenantExecutor` usage for USPS_DIRECT rows with delegation to `UspsLabelQueueFairScheduler` — one authoritative scheduler across paths.
- V63 migration: add `usps_label_queue.import_batch_id BIGINT` FK, `usps_label_queue.source_type VARCHAR(24)` (values: `BULK_OPERATOR | IMPORT_OPERATOR | IMPORT_BACKGROUND | MPS_PIECE | MANUAL`).
- Queue→import bridge: new `UspsQueueImportRowReconciler` — subscribes to queue completion events (or scheduled scan of DONE/FAILED items with `import_batch_id != NULL`) and stamps the import row's `generatedStatus` + emits audit log entry.
- Cancel cascade: `OrderImportService.cancelJob` → also calls `uspsLabelQueueService.cancelPending(importBatchId)`.
- Alerting: WARN-level admin dashboard banner when a background enqueue throws (M-B3). Optional webhook hook for future.
- Actor stamping: `system:import-worker/{workerId}` when the label call originates from `ImportGenerationWorker`.
- Restart safety: `requeueStale` checks queue for existing rows before re-enqueueing (M-B2).
- Tests: `ImportGenerationJobUspsDirectTest` — 8 cases + `ImportGenerationWorkerCrashRecoveryIT` + broader-glob discipline.

**Risk:** HIGH — background jobs + shared queue + scheduled reconciler. Must include integration test for the full round-trip.

### PR-G4 — FE UX surface + admin dashboard by-source dimension (~600 LoC, LOW risk)
**Fixes:** U1, U2, U3, U4, U6, U9, T-M5.

**Scope:**
- `NewShipmentPage.tsx`: interpret `status="QUEUED"` — mount `MpsProgressCard` above results, "Queued for USPS Direct — ~N min to first label" banner.
- `OrderImportModal.tsx`: `<BulkLabelQueueBadge>` after upload results; "USPS is rate-limited platform-wide (55/hr) — this import may take X hours" banner when `saveBatchSize × queueDepth > threshold` at commit time.
- `DataHistoryPage.tsx`: `<BulkLabelQueueBadge>` at top; `<MpsProgressCard orderNo={row.generatedOrderNo}>` for MPS parent rows.
- `UspsDirectDashboardPage.tsx`: by-source filter (BULK_OPERATOR / IMPORT_OPERATOR / IMPORT_BACKGROUND / MPS_PIECE / MANUAL) using PR-G3's new `source_type` column.
- Per-source pause admin control (U9) — new `system_setting.USPS_PROVIDER_SOURCE_PAUSE` (JSON array of paused sources) with FE toggle.
- Tests: 4 new FE tests (`NewShipmentPage.uspsDirectQueue.test.tsx`, `OrderImportModal.uspsDirect.test.tsx`, `DataHistoryPage.mpsProgress.test.tsx`, dashboard by-source filter).

**Risk:** LOW — pure additive UI branches. Fixes user-visible symptoms once G1-G3 land.

## Dispatch strategy

- **PR-G1 first, alone.** Highest risk (touches hottest code); gates every subsequent PR because they all depend on `UspsDirectRoutingService`.
- **PR-G2 + PR-G4 in parallel after G1 lands.** G2 backend on import; G4 FE surfaces don't collide.
- **PR-G3 last** — depends on G2's routing being in place; adds background-specific alerting on top.

Total: ~2200 LoC across 4 PRs. Est. 3-5 days elapsed with parallel-agent execution.

## Non-goals for this track

- **Stamps.com partner-integration migration** (Stage 2, per project memory) — not blocking any of these gaps.
- **PR-G4's per-source pause admin toggle** could be split off as `PR-G5` if we want to keep the FE PR pure-additive.
- **Idempotency-key namespace unification across ALL carriers** — this PR only harmonizes USPS_DIRECT-adjacent keys; broader work is out of scope.
- **Restructuring `OrderImportServiceImpl`** — it's a hot 5000-line file; we're threading queue routing through its existing structure, not refactoring.
