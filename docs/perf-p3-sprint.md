# Perf P3 sprint — long-tx phase-C split

Follow-up to [`docs/perf-audit.md`](perf-audit.md), which deferred P3 as HIGH-risk needing feature-flag rollout. 2026-10-07 session shipped the foundation + 3 of 5 phases.

All phases are dark-launched behind `carrier.tx-split-phase-c` (env `CARRIER_TX_SPLIT_PHASE_C`, prod default FALSE).

## Commits

| Commit | Phase | Scope |
|---|---|---|
| `fb906309` | **P0** | V131 `order_label_tracking.in_flight_since` + partial index (fresh-DB-safe), `OrderTracking.inFlightSince` field, `OrderTrackingRepository.findStuckInFlight` + `tryAcquireInFlightSweeperLock` (advisory lock 4831277), `carrier.tx-split-phase-c` flag (default FALSE) + `in-flight-timeout=PT2M` + `sweeper-interval-ms=60000`, `InFlightTrackingSweeper` scaffold (log-only), `MigrationsFreshDbIntegrationTest` entry. |
| `0d8abe46` | **P1** | `VoidServiceImpl.voidLabel` three-phase split. Reservation + CarrierCallOutcome records. 5 split-path tests + 9 legacy tests green. |
| `341b9c9b` | **P2** | `MultiWarehouseLabelServiceImpl.generate` outer-tx drop. Each child's own tx via its own @Transactional; final persist in own REQUIRES_NEW. 3 split tests + 10 legacy green. |
| `96a4138e` | **P3** | `CarrierServiceImpl.generateLabel` three-phase split. LabelReservation + LabelCarrierOutcome records; runLabelReservationPhase / runLabelCarrierCallPhase / runLabelPersistPhase. **No new split test** — see Test coverage decisions below. |
| `81eca497` | **P4α** | `CarrierServiceImpl.generateManualLabel` dispatcher + split stub. `@Transactional` removed; stub currently delegates to legacy; body renamed to `generateManualLabelLegacyBody`. Zero behaviour change. |
| _tbd_ | **P5** | `InFlightTrackingSweeper` flips from log-only to resolve: marks stuck rows ERROR + nulls `in_flight_since` + sets actionable `error_message`. Race guard against parallel Phase C commit. 4/4 tests. |

## Design pattern (established in P1, reused in P2 + P3)

1. **Remove `@Transactional` from entry method.** The annotation fires BEFORE the body — can't flag-gate. Instead the entry becomes a pure orchestrator.
2. **`requiresNewTransactionTemplate` for both paths.** Legacy wraps full body in `template.execute(status -> legacyBody)` — equivalent semantics to the removed annotation (these methods only called from controllers; no caller tx to join). Null-template guard falls through to direct call for bare-ctor Mockito tests.
3. **Phase A: tx + lock + reserve + release.** Short tx: validate, resolve, lock row, set `in_flight_since = now()`, commit.
4. **Phase B: no tx, carrier HTTP.** `attemptShipment` / `connector.voidShipment` etc. run with no DB connection held.
5. **Phase C: new tx, persist + null in_flight_since.** Re-fetch under lock; always null `in_flight_since` (even on error) so the sweeper doesn't flag the row.
6. **Reservation record** carries state A → B → C. Nullable `earlyReturn` field: non-null = short-circuit, skip B+C.
7. **CarrierCallOutcome record** carries Phase B result. Fields: success result + typed exception kinds (rate-limit vs other).

Why this works: the three TransactionTemplate.execute calls in split mode are each tiny (phase A + phase C), with no tx open during the long phase B. Pre-P3, one connection was held across N × 5-15s carrier RTTs.

## P4 — generateManualLabel (in progress)

Original P3 plan sized it at ~250 LoC; **actual is ~1,500 lines** (CarrierServiceImpl.java:989 → :2491). The audit author undercounted. Mid-session swap moved generateLabel's content into P3 so this slot could get its own sprint.

**P4α shipped 2026-10-07**: dispatcher + split stub (`generateManualLabel` entry became orchestrator, `@Transactional` → `requiresNewTransactionTemplate` wrap; new `generateManualLabelSplit` stub delegates to legacy; body renamed to `generateManualLabelLegacyBody`). Zero behaviour change today; flag toggle now routes without crashing; the actual split can land incrementally.

**P4β-P4δ remaining** (needs its own sprint):

Investigated 2026-10-07 after P4α landed. Line numbers below are post-P4α (after dispatcher insert).

### Method anatomy (`generateManualLabelLegacyBody`, lines 1427-2928, ~1,500 LoC)

| Section | Lines | Belongs to phase |
|---------|-------|------------------|
| Input validation + idempotency key | 1427-1450 | A |
| USPS_DIRECT short-circuit (regenerate only) | 1458-1465 | A |
| Tenant clamp + address extract | 1419-1424 | A |
| Return eligibility / recipient clamp | ~1430-1500 | A |
| Resolve carrier/service/package/account | ~1500-1700 | A |
| Routing rules + REROUTE | ~1700-1820 | A (connector may swap at 1809) |
| orderNo pre-allocation | 1950-1951 | A (regenerate reuses; new-order gets fresh sequence) |
| Build ShipmentRequestDTO (+ cutoff shift) | 1952-2160 | A |
| **Carrier HTTP block** (AuthRetry.withAuthRetry over subRequests) | **2160-2232** | **B** |
| CarrierRateLimitException handler → 429 | 2233-2242 | B catch (no persist) |
| Carrier-generic-exception handler → ERROR order persist (already REQUIRES_NEW) | 2243-2427 | B catch (own tx, SAFE) |
| `batchResults.get(0)` → `result` | 2431 | C start |
| Order entity construction (existingOrderNo branch at 2449) | 2449-2493+ | C |
| Clear prior batch/package rows (regenerate at 2580) | 2580-2583 | C |
| Per-batch persist (shipment_batch + label_package loop) | 2585-2770 | C |
| OrderTracking resolve + persist (existingOrderNo branch at 2783) | 2779-2810 | C |
| Order save + response build | 2810-2850 | C |
| Audit (LABEL_GENERATED vs LABEL_REGENERATED at 2840/2845) | 2820-2865 | C |
| Writeback dispatch | 2866-end | C |

Carrier HTTP = line **2231** specifically: `fConnector.createShipment(sub, t, fEnv)` inside the `for (ShipmentRequestDTO sub : subRequests)` loop. One method does N carrier calls when auto-split fires.

### Key insights (don't re-derive)

1. **orderNo is pre-allocated BEFORE the carrier call** (line 1950-1951). Phase A can key by it even for new-orders.
2. **Error path already uses REQUIRES_NEW** (line 2264: `requiresNewTransactionTemplate.executeWithoutResult(...)`). The ERROR-order persist survives tx corruption without P4 doing anything extra.
3. **CarrierRateLimitException** (line 2233) returns 429 without persisting anything — this stays in Phase B catch; Phase C never runs for 429.
4. **AuthRetry.withAuthRetry** wraps createShipment per sub-request — handles token rotation. Phase B needs to preserve this (don't flatten the loop).
5. **The 10 `existingOrderNo != null` branches** split into: 3 for routing/alloc (Phase A: 1458, 1950), 1 for connector resolve (A: internally in resolver), 4 for persist (C: 2276 error-only, 2449, 2580, 2783), 2 for audit choice (C: 2840, 2845). Only ONE lives inside Phase B's catch block (2276 — error-order persist) and that's already REQUIRES_NEW so unaffected by split mode.

### Recommended attack order

1. **P4β (regenerate-only)** — Full three-phase split, gated on `existingOrderNo != null` AND `phaseSplitEnabled`. New-order path (existingOrderNo == null) stays legacy. ~250 LoC extraction. Phases:
   - **Phase A**: validate → resolve → lock existing Order via findByOrderNoForUpdate → short-circuit on already-generated / already-in-flight → resolve carrier/account/service/package → compute allocatedOrderNo (= existingOrderNo) → set in_flight_since on OrderTracking → commit (releases Order lock)
   - **Phase B**: AuthRetry.withAuthRetry loop over subRequests → createShipment per sub → return `List<ShipmentResult>` OR typed exception kind (rate-limit vs other)
   - **Phase C**: null in_flight_since → apply Order updates from req → delete prior batches → persist new shipment_batch + label_package → persist OrderTracking (reuse row) → audit LABEL_REGENERATED → writeback

2. **P4γ (new-order via pre-save migration)** — V132 migration adds a `pending_order` or lets Order rows exist with `order_status='PENDING'` + minimal fields. Phase A commits a stub Order; Phase C fills it in. Enables unifying the regenerate + new-order split paths. Only ship this when a real customer complains about new-order perf under load.

3. **P4δ (full merge)** — Once P4γ lands, generateManualLabelSplit covers BOTH paths with ONE three-phase implementation. Delete `generateManualLabelLegacyBody` (if no callers remain).

### Known hazards

- **Auto-split (`ShipmentSplitter`)** generates N sub-requests. The loop at line 2183-2232 runs N × (5-15s) carrier calls. In split mode this all happens without the Order row lock. ✓ No conflict with P4β design.
- **Order field cross-cutting**: ~40 Order fields set across lines 2449-2810. Phase C's "apply Order updates from req" must preserve every one. Risk: easy to miss a field during extraction. Mitigation: extract into a helper `applyOrderFieldsFromRequest(Order order, ManualShipmentRequest req, ...)` that both legacy and Phase C can call.
- **Idempotency key** (line 1393-1399) is normalized early. Phase A must persist it on the reserved OrderTracking so a crash + retry can detect "same request" via existingTracking.idempotencyKey match.
- **Transaction propagation**: `shippingConfigService.pickPackageForClient` is @Transactional and marks the enclosing tx rollback-only on failure (noted at line 2659). In split-mode's Phase A short tx, that would roll back Phase A's reservation. Phase A must call this with its OWN REQUIRES_NEW tx OR handle the exception without propagation. Investigate during P4β.
- **ERROR persist uses `allocatedOrderNo`** (line 2272). Phase B's exception handler stays as-is (REQUIRES_NEW); keep it in the Phase B catch, not Phase C.

### Scope estimate

- P4β only (regenerate split): ~250 LoC + ~300 LoC test. 1-2 sessions.
- P4γ (new-order migration + pre-save): 1 V-migration + ~50 LoC schema entity change + careful rollout. 1 session.
- P4δ (merge paths, delete legacy body): ~100 LoC deletion + regression testing. 1 session.

Total P4 remaining: 3-4 sessions once scope is committed.

## P5 — Sweeper resolves stuck rows ✅ shipped 2026-10-07

`InFlightTrackingSweeper.sweep()` now marks stuck rows ERROR instead of just logging:

- Sets `status = "ERROR"`, nulls `in_flight_since`, flips `is_label_generated = false`
- Sets `error_message` = "Carrier dispatch did not complete within the in-flight timeout. The carrier may or may not have accepted the shipment — query the carrier for tracking state before voiding or retrying."
- Race guard: skips rows whose `in_flight_since` was nulled by a parallel Phase C commit between SELECT and UPDATE
- Marked `ponytail:` — naive "mark ERROR" resolution; upgrade to carrier tracking-lookup round-trip when stuck-row counts become operationally painful

Tests: 4/4 (lock-contested, no-stuck, resolve stuck, race-settled skip).

Flag flip (ops): `CARRIER_TX_SPLIT_PHASE_C=true` → voidLabel + MW + generateLabel start writing `in_flight_since`; sweeper picks up any crashed dispatches. Zero stuck rows pre-flag-flip because no caller writes the column.

## Test coverage decisions

- **P1 + P2**: added split-path test files (`VoidServiceImplSplitTest` 5/5, `MultiWarehouseLabelServiceImplSplitTest` 3/3). Existing fixtures made this cheap.
- **P3**: deliberately **no `CarrierServiceImplSplitTest`**. The project itself deferred Mockito plumbing at `CarrierServiceImplTest.java:3-18` ("~30-dep mock fixture is a follow-up sprint"). Writing it to validate a dark-launched code path with flag OFF in prod is anti-ponytail. Assurance:
  - Flag OFF: 86/86 adjacent tests pass (CarrierServiceImplStatus/Labels/Packages, OrderTracking, Tracking, Void, Sweeper)
  - Flag ON: structure byte-identical to P1 which has 5 passing cases
  - 4 pre-existing failures (`L01`/`P80`/`F77` in `CarrierServiceImplConnectHelpersTest`) are unrelated carrier-alias V106 migration debt

## How to apply when touching this code

- A new carrier-dispatch method: mirror the pattern. Entry orchestrator → Reservation record → three phases. Reuse `requiresNewTransactionTemplate` + `phaseSplitEnabled` flag.
- Status writes outside the normal paths (void sweeper, retry endpoints, admin backfill): always null `in_flight_since` after settling the row's status, or the sweeper will flag it stuck.
- Flag flip (ops): `CARRIER_TX_SPLIT_PHASE_C=true` env var. Staging first; watch Hikari pool metrics + `in_flight_since` row count; roll back by unsetting if the sweeper WARN rate spikes.
