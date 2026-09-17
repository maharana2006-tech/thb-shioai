# STAMPS_COM cross-flow audit (2026-09-17)

Audit of the STAMPS_COM (`StampsConnector`) code paths for the three flows the USPS_DIRECT G-track just closed on (manual / bulk-import / bulk-background). Same section shape as `docs/usps-direct-integration-audit.md` so review + triage is directly comparable.

## TL;DR

STAMPS_COM never got the same cross-flow review USPS_DIRECT did over sprints F/G. The connector itself is stable (20+ tests) but the **glue layers around it** — how manual / import / background reach the connector — carry patterns that USPS_DIRECT explicitly rejected (silent OAuth fallback, no cancel cascade, no per-row idempotency key, no background actor stamping).

**Real risk today** is small — Stamps runs at operator click rate (~1/sec peak) so most of the theoretical burst / race conditions never fire in practice. But the paths **have grown apart from the USPS_DIRECT ones**, so a fix that lands on USPS_DIRECT tomorrow won't automatically apply to Stamps. The purpose of this track is to bring them back to parity.

**Highest-signal findings:**
- **S-B1** — background workers can't authenticate SERA (thread-local refresh_token is never populated in the worker path). Real BLOCKER — a SERA-only tenant's background import fails silently today.
- **S-B3** — cancel-import doesn't stop in-flight Stamps calls (Stamps has no queue for `cancelPending` to flip). Real MAJOR — the operator cancel is honoured for USPS_DIRECT but ignored for Stamps.
- **S-I2 / S-D1** — Stamps import path passes null idempotency keys through. Retry-after-crash can double-charge a label the crash happened after. G5 fixed this for USPS_DIRECT via `IdempotencyKeys.forUspsOrder`; Stamps needs the equivalent.

## Cross-flow findings summary

### BLOCKERs (silent-fail / data-loss)

| ID | Flow | Description | Verified? |
|---|---|---|---|
| **S-B1** | Bulk background | `StampsConnector.SERA_REFRESH_TOKEN` is a ThreadLocal populated by `pushSeraRefreshToken(...)`. `OrderImportServiceImpl` (background path `executeGenerationJob`) never calls it and `BulkLabelServiceImpl` doesn't either. A SERA-only tenant's background label fails at auth; the worker catches the exception and marks the row FAILED. No operator watching means the failure IS the outage. | **Confirmed** (`StampsConnector.java:128, 145-152, 415-416`; grep of `OrderImportServiceImpl.java` + `BulkLabelServiceImpl.java` returns zero hits) |
| **S-B3** | Bulk background | `OrderImportServiceImpl.cancelGeneration` cascades to `uspsLabelQueueService.cancelPending(id)` (G3b PR-M-B4) but STAMPS_COM has no persistent queue — a background 1000-order Stamps import will happily finish all 1000 after the operator cancelled. Billing leak + orphaned labels. | **Confirmed** (audit doc F-B3 file:line matches G3b's cascade block) |
| **S-M3** | Manual | Concurrent operator clicks on the same shared Stamps account can race `StampsSeraOAuthService.tokenCache` refresh path. Blast radius: two operators on one shared credential minted at ~the same second. Rare in practice; still a silent stale-token risk. | **Verify** — needs a concurrency test to reproduce |

### MAJORs (functional / UX / consistency)

| ID | Flow | Description |
|---|---|---|
| **S-I1** | Bulk import | No `StampsRoutingService` analogous to `UspsDirectRoutingService`. Stamps import calls go direct to `carrierService.generateManualLabel(...)`. Not a bug — Stamps has no 55/hr platform quota to gate — but it means future rate-limit / provenance / cancel work would need a new routing seam. |
| **S-I2** | Bulk import | Import passes `internalIdempotencyKey=null` to Stamps (only wired for USPS_DIRECT in G5). A JVM crash between "label bought" and "row.generatedOrderNo persisted" causes a resubmit that double-charges Stamps (SWSIM has no server-side dedup on `ReferenceNumber + ShipperAccount`). |
| **S-I4** | Bulk import | SERA thread-local pollution: `SERA_REFRESH_TOKEN` set on worker thread by row N of group A can leak to row 1 of group B on the same thread if the token wasn't consumed (`one-shot; caller re-pushes` comment on line 416 exists but no push means silent stale). |
| **S-I5** | Bulk import | Partial-batch failure — no persisted `labelId` / connector-side idempotency reference. On restart, `syncRowsWithLiveOrders()` only recognises a label via `generatedOrderNo`; crash before that setter = resubmit. Fixed for USPS_DIRECT via the queue's DB row survival; not for Stamps. |
| **S-I8** | Bulk import | Background `ImportGenerationWorker.executeGenerationJob` doesn't attempt the batch CAS gate that manual `generateLabelsForBatch` uses; a stale-job requeue running concurrent with an operator Retry can both dispatch the same batch. |
| **S-B2** | Bulk background | Silent SERA→SWSIM fallback on token failure. Background worker's generic `RuntimeException` catch swallows Stamps auth errors without a WARN audit event; row stays PENDING forever. USPS_DIRECT G3a added the alert channel + WARN log; Stamps needs the equivalent. |
| **S-B4** | Bulk background | Restart loses account context — `import_generation_job` has no `carrier_account_ref_id` FK. On resume, the worker can't re-push the SERA refresh_token. Compounds S-B1. |
| **S-B5** | Bulk background | No actor stamping for background Stamps labels. USPS_DIRECT gets `system:import-worker/{workerId}` via `ProvenanceHint.importBackgroundScoped`; Stamps calls audit-log with `actor=null`. Billing attribution + spike-diagnosis both blocked. |
| **S-M5** | Manual regenerate | Idempotency-Key on `POST /orders/{n}/regenerate-label` is caller-set; retries with **different** headers on the same order 409 (via tracking-row check), which is correct — but no test pins the contract on the Stamps side. |
| **S-M6** | Void / refund | No caller-side idempotency wrap on `voidShipment`. A retried void call fires Stamps twice; server refunds once but audit log shows two void events. Cross-flow consistency issue only (label already voided). |

### Audit-log + design consistency drift

| ID | Description |
|---|---|
| **S-D1** | Idempotency-key namespace: USPS_DIRECT now uniformly writes `usps-order-{orderNo}` (G5); Stamps has no equivalent stable key. Cross-flow retries fall through the tracking-row check because the stored key is null. |
| **S-D2** | Tenant-code derivation: USPS_DIRECT uses `ProvenanceHint.tenantCodeHint` (G5 D3) so queue rows are scoped to the caller's auth-time truth. Stamps path uses whatever the loaded `Order.tenantId/custNo` carries — legacy drift possible. |
| **S-D3** | Audit event type: `LABEL_GENERATED` is emitted for both paths (parity confirmed by G5 D2's addition to `generateLabel`), but Stamps import path aggregates only `IMPORT_GENERATED` per batch (per-row events already flow via the delegated `generateManualLabel` call — so this is closed transitively, same as USPS_DIRECT D2's actual state was pre-G5). |

### Test coverage gaps

| ID | Description |
|---|---|
| **S-T1** | Zero manual-flow round-trip test: `POST /orders/manual-label` → `StampsConnector.createShipment` → persist tracking → emit audit. Existing 20 Stamps test files cover payload building + auth path in isolation; no end-to-end integration. |
| **S-T2** | Zero regenerate-with-Stamps test (`generateManualLabel(req, user, existingOrderNo)` when carrier=Stamps). |
| **S-T3** | Zero concurrent-refreshToken test on `StampsSeraOAuthService.tokenCache`. |
| **S-T4** | Zero import-with-Stamps test. `OrderImportServiceImplTest` has UPS + FedEx fixtures; no `STAMPS`, no `SWSIM`, no `SERA`. |
| **S-T5** | Zero background-with-Stamps test. `ImportGenerationJobTest` + `ImportGenerationWorkerRequeueStaleTest` cover the generic path; no Stamps-specific case for SERA token loading or requeue-preserves-context. |

### UX gaps

| ID | Description |
|---|---|
| **S-U1** | SERA OAuth refresh failure surfaces as a generic label-generation timeout. No user-facing "re-verify the Stamps account" banner. |
| **S-U2** | Rate-limit hint asymmetry: USPS_DIRECT shows "Queued for USPS Direct (55/hr)" and a queue-depth badge; Stamps shows instant `Generated` OR silent failure. Operators may misread the timing difference as a bug. |
| **S-U3** | No pre-commit rate-hint on Stamps imports. USPS_DIRECT import shows "estimated 20 minutes for 1000 orders at 55/hr"; Stamps shows nothing (partly because Stamps' rate limit is per-refresh-token, not per-platform). |

## Proposed fix track — 5 stacked PRs (~1500 LoC total)

Same shape as the G-track. Uses letter **S** (STAMPS_COM) instead of G (USPS_DIRECT_G).

### PR-S1 — Background SERA authentication (~250 LoC, HIGH risk)
**Fixes:** S-B1, S-B4 partial.
- New `StampsBackgroundAuthContext` service: given an `Order` (or account id), loads `carrier_account_ref.stamps_refresh_token`, decrypts, calls `StampsConnector.pushSeraRefreshToken(...)`, and clears the ThreadLocal in a try-finally.
- Wire from `OrderImportServiceImpl.processGroup` and `BulkLabelServiceImpl.processOneOrder` before every Stamps carrier call.
- Add `carrier_account_ref_id` to `import_generation_job` (V64) so `requeueStale` can reload the account without ambiguity.

### PR-S2 — Stamps cancel cascade (~150 LoC, MEDIUM risk)
**Fixes:** S-B3, S-I8 partial.
- `import_batch.cancel_requested_at` timestamp column (V65) so background workers can polling-check per row.
- `BulkLabelServiceImpl.processOneOrder` + `OrderImportServiceImpl.processGroup` check the flag before each Stamps carrier call; break loop on cancel.
- No queue-flip needed (Stamps has no queue) — this is a cooperative-cancel pattern instead of the G3b cancel-cascade pattern.

### PR-S3 — Stamps idempotency key + audit provenance (~350 LoC, MEDIUM risk)
**Fixes:** S-I2, S-I5, S-B5, S-D1, S-D2.
- Extend `IdempotencyKeys` with `forStampsOrder(long orderNo)` → `stamps-order-{orderNo}`. Reuse the G5 DTO-field pattern: `ManualShipmentRequest.internalIdempotencyKey` is already `@JsonIgnore`, just stamp it from the Stamps import path too.
- `CarrierServiceImpl.generateManualLabel` writes the key onto the tracking row (already done in G5; verifies Stamps path also picks it up).
- New `StampsProvenanceHint` (or reuse `UspsDirectRoutingService.ProvenanceHint` if the audit layer accepts an abstract source) so Stamps background calls get `system:import-worker/{workerId}` actor stamping.
- Persist `carrier_side_idempotency_ref` (Stamps `IntegratorTxID` if SWSIM, `X-Request-Id` for SERA) on `order_tracking` (V66) so restart-detect can recognise a label via connector-side reference, not just `generatedOrderNo`.

### PR-S4 — Silent-fallback alerts + operator visibility (~300 LoC, LOW risk)
**Fixes:** S-B2, S-U1, S-U2, S-U3.
- Extend `UspsFallbackAlertService` to also record Stamps SERA→SWSIM fallback events (or refactor to `CarrierFallbackAlertService`).
- `/admin/carrier-fallback-alerts` endpoint (extends G3b's `/dashboard/fallback-alerts`).
- FE: banner on `/orders/manual-label` when caller's account has recent auth failures; caret on `/imports/new` estimating time based on account SLA (or a "processed as-fast-as-carrier-allows" fallback message).

### PR-S5 — Tests (~500 LoC, LOW risk)
**Fixes:** S-T1 through S-T5.
- 5 new test classes:
  - `StampsManualLabelGenerationTest` — round-trip
  - `StampsRegenerateManualTest` — regenerate path
  - `StampsSeraTokenCacheConcurrencyTest` — pins S-M3 contract
  - `OrderImportServiceImplStampsTest` — import + retry
  - `ImportGenerationWorkerStampsTest` — background + requeue
- Mirror the coverage matrix of `OrderImportServiceImplUspsDirectRoutingTest` / `CarrierServiceUspsManualQueueTest`.

## Dispatch strategy

Same pattern as G-track:

1. **S1 first alone** — high-risk backend auth wiring; needs clean CI before anything else lands.
2. **S2 + S3 in parallel** — independent (cancel-cascade vs idempotency); both depend on S1's account-context lookup.
3. **S4** after S3 — fallback-alerts endpoint shape depends on the audit-provenance shape from S3.
4. **S5** last — tests reference the final APIs from S1-S4.

Total: 5 PRs / ~1500 LoC / 3-4 days if agent-parallelised, 1.5-2 weeks solo.

## Non-goals for this track

- **New routing service for Stamps** (S-I1) — deliberate. Stamps has no platform-level quota that would justify a queue. Only pull this in if a future SLA change makes it necessary.
- **SWSIM sunset planning** — Stamps.com's SOAP API has been "legacy" for years; a migration to their v3 REST is out of scope. Track separately.
- **Stamps.com partner-integration migration** (Stage 2 in the USPS_DIRECT integration doc) — platform-owned partner creds instead of per-tenant. Deferred, not blocked by this audit.
- **Void-side idempotency wrapper** (S-M6) — belongs in a void-lifecycle audit, not label-generation.
- **F-M2 / U2** — the "USPS_DIRECT queues, Stamps runs sync" difference is intended and correct; documenting it beats papering over it.

## Related memory

- [[usps-direct-integration]] — G-track structure this audit mirrors
- [[widen-via-dto-field-not-new-arg]] — pattern for the S3 idempotency-key wiring
- [[component-ctor-overload-needs-autowired]] — watch out for during S1's new service wiring
- [[feedback_bulk_labels_status_race]] — relevant for S5 tests around BulkLabelServiceImpl
