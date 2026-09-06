# Commodity Auto-Split — Implementation Plan

**Status:** Planned 2026-09-06. Not started.
**Owner:** TBD
**Estimated effort:** 2 days focused, ~4 PRs.
**Blocker context:** operator hit 190-commodity UK shipment; PR #600 HS-consolidation not applied (Sprint 52 preflight throws first — see "Root cause" below); user wants real auto-split without consolidation.

## Root cause of today's block (already understood)

Two independent limits that don't know about each other:

1. **`ShipmentSplitter.assertCommoditiesFit` (Sprint 52, `CarrierServiceImpl.java:1267`)** —
   throws `CommoditiesLimitExceededException` when raw `intl.getCommodities().size() > carrier cap`.
   This is the preflight guard.

2. **`UpsConnector.buildInternationalForms` (PR #600, 2026-09-06)** —
   consolidates commodities by (HS code + description + origin) BEFORE emitting the wire,
   throws if consolidated count still > 50.

The preflight fires first with the raw count, so the consolidation never runs. **Real auto-split**
supersedes both — no consolidation, but split the shipment into N sub-shipments each
under the carrier cap.

## Goal

For any international shipment where `commodities.size() > carrier cap`:

1. Split into `⌈size / cap⌉` sub-shipments.
2. Each sub-shipment gets its own tracking number, its own label, its own paperless invoice
   (with a slice of the commodities).
3. Operator picks how physical packages are distributed across sub-shipments
   (three strategies — see "Split strategies" below).
4. FE renders as a master shipment with N sub-shipments (existing MPS UI pattern).
5. UPS first. FedEx / DHL / USPS ported after real usage.

## Non-goals (out of scope)

- Consolidating commodities by HS code (user rejected; PR #600 is superseded).
- Auto-picking strategy for the operator (they always pick).
- Cross-carrier scope on day 1 — UPS-only.
- Backfilling stuck orders that already hit the pre-#600 error.

## Split strategies

Presented to the operator via a modal when their submit response returns `SPLIT_REQUIRED`:

**Strategy A — `SAME_PACKAGES`**
Each sub-shipment carries **all** physical packages, but each has a different slice of commodities.
E.g. 3 packages × 4 splits = 12 tracking numbers for 3 physical boxes.
Wasteful (carrier bills 4× shipping for 3 boxes) but customs-simple.
Each label lists the full physical dims/weights of each box.

**Strategy B — `PROPORTIONAL_PACKAGES`**
Distribute physical packages across sub-shipments proportional to commodity count.
190 commodities across 4 splits = ~48 per split; 3 packages assigned 1+1+1 to 3 splits.
Split 4 has fewer packages (may need repack).
Customs paperwork per split names only the commodities in that split's boxes.
Requires operator to accept package redistribution.

**Strategy C — `ONE_PACKAGE_PER_SPLIT`**
For an N-package order with M > cap commodities: N splits each with 1 package and its own
commodity slice — (M/N) commodities per split. Requires `boxSeq` metadata (Sprint 27+) to
attribute commodities to packages; if missing, distribute proportionally.
Best customs fidelity but requires operator to have used per-package item breakdown.

## Data model changes

### Backend

- **`ShipmentBatch`** already supports multiple batches per order (`shipment_batch.batch_seq`).
  No schema change needed. Sprint 47 established the multi-batch pattern.

- **`Order` / `label_batch`** unchanged. One order continues to have one order_no; the split is
  modeled through multiple `ShipmentBatch` rows under it.

- **New enum `SplitStrategy`** in `com.multiship.backend.dto`:
  `SAME_PACKAGES | PROPORTIONAL_PACKAGES | ONE_PACKAGE_PER_SPLIT`

- **`ManualShipmentRequest`** — new nullable field `splitStrategy: SplitStrategy`. Non-null on
  the second submit after the operator picks in the modal.

### FE

- **`ManualShipmentPayload`** (`orderService.ts`) — new nullable `splitStrategy` field.

- No IndexedDB / localStorage changes.

## API changes

### First submit (no strategy)

`POST /orders/manual-shipment` with `splitStrategy: null`, `commodities.length > cap`.

**Response**: `422 SPLIT_REQUIRED` with body:

```json
{
  "status": "ERROR",
  "code": 422,
  "errorCode": "SPLIT_REQUIRED",
  "message": "UPS caps at 50 commodity lines per shipment; this shipment has 190.",
  "data": {
    "carrier": "UPS",
    "actualCommodityCount": 190,
    "carrierCap": 50,
    "requiredSplitCount": 4,
    "packageCount": 3,
    "strategies": [
      {
        "code": "SAME_PACKAGES",
        "label": "Duplicate packages, split invoice",
        "trackingCount": 12,
        "note": "3 boxes × 4 shipments = 12 tracking numbers. UPS bills 4× shipping for 3 boxes."
      },
      {
        "code": "PROPORTIONAL_PACKAGES",
        "label": "Distribute packages proportionally",
        "trackingCount": 3,
        "note": "3 boxes across 4 shipments (1 shipment has no box — needs repack or merge)."
      },
      {
        "code": "ONE_PACKAGE_PER_SPLIT",
        "label": "One package per shipment",
        "trackingCount": 3,
        "note": "3 shipments each with 1 box. Uses per-package item breakdown if provided."
      }
    ]
  }
}
```

### Second submit (with strategy)

Same endpoint, same body plus `splitStrategy: "SAME_PACKAGES"`.
Backend splits, calls UPS N times, returns array shape mirroring the existing
`LabelGenerationResponse` but wrapped in an array:

```json
{
  "status": "SUCCESS",
  "data": {
    "orderNo": 900054,
    "batches": [
      { "batchSeq": 1, "trackingNumber": "1Z...A", "labelUrl": "...", "shippingCost": 45.50 },
      { "batchSeq": 2, "trackingNumber": "1Z...B", "labelUrl": "...", "shippingCost": 45.50 },
      ...
    ],
    "totalCost": 182.00,
    "carrierCode": "UPS"
  }
}
```

## Backend changes

### `ShipmentSplitter`

New method:

```java
List<ShipmentRequestDTO> splitByCommodityStrategy(
    ShipmentRequestDTO request,
    CarrierShippingLimit limit,
    SplitStrategy strategy);
```

Implementation:

1. Take the full commodities list, chunk into slices of `carrierCap`.
2. For each slice, build a `ShipmentRequestDTO` derived from the parent:
   - `SAME_PACKAGES`: copy all packages verbatim.
   - `PROPORTIONAL_PACKAGES`: distribute packages round-robin across splits.
   - `ONE_PACKAGE_PER_SPLIT`: use `commodity.boxSeq` mapping (fallback to proportional).
3. Recompute per-split `declaredValue = sum(qty × unitValue)` for that slice.
4. Return the list of sub-DTOs.

Also: modify `assertCommoditiesFit` to accept a `throwsIfOver: boolean` param. Set to false
when caller intends to split; true when caller wants the fast-fail (existing behavior).

### `CarrierServiceImpl.generateManualLabel`

Refactor to loop the sub-requests. Pseudo-code:

```java
List<ShipmentRequestDTO> subRequests =
    req.getSplitStrategy() != null
        ? splitter.splitByCommodityStrategy(shipmentRequest, limit, req.getSplitStrategy())
        : List.of(shipmentRequest);

// If commodity count still over cap and no strategy → return SPLIT_REQUIRED response
if (subRequests.size() == 1
        && commodities.size() > carrierCap) {
    return splitRequiredResponse(carrier, commodities.size(), carrierCap, packageCount);
}

List<LabelGenerationResponse> batchResults = new ArrayList<>();
for (int i = 0; i < subRequests.size(); i++) {
    ShipmentResult result = connector.createShipment(subRequests.get(i), token, env);
    // Persist as ShipmentBatch batch_seq = i+1 under the same order
    batchResults.add(persist(order, result, i + 1));
}
return aggregate(batchResults);
```

### `CommoditiesLimitExceededException`

Deprecate. Replace with `SplitRequiredResponse` DTO carrying the strategies.

### Persistence

Reuse Sprint 47 `ShipmentBatch` shape:
- `batch_seq` = 1..N for split index
- `master_tracking_number` = first batch's tracking (the FE displays this as "primary")
- Each batch has its own `label_url`, `label_pdf`

New order_label_tracking row per split? Or single row referencing the first batch?
**Recommendation**: single `order_label_tracking` with `trackingNumber` = master; per-split
labels persisted only in `label_package` (existing per-piece pattern).

### Idempotency

Sub-request idempotency keys derived from parent + batch_seq: `parentKey || "-split-" || seq`.
Same-key retry safe.

## FE changes

### 1. Modal — `SplitShipmentModal.tsx` (new)

Rendered when POST returns `errorCode: SPLIT_REQUIRED`. Props:

```ts
{
  strategies: SplitStrategyOption[]
  onPick: (strategy: string) => void
  onCancel: () => void
  originalRequest: ManualShipmentPayload
}
```

Shows 3 cards (strategy label + note + trackingCount pill). Pick calls parent's re-submit.

### 2. `NewShipmentPage.tsx` submit handler

Wrap the existing manual-shipment submit call. On `errorCode: SPLIT_REQUIRED`, open the modal
instead of showing a generic error toast. On strategy pick, re-submit with `splitStrategy` field.

### 3. Order detail page

Reuse existing MPS accordion (Sprint 47). When an order has multiple ShipmentBatches:

```
Order 900054                              4 shipments · $182.00
  ▼ Batch 1  1Z...A                       50 items · $45.50
    Package 1                             1Z...A
    Package 2                             1Z...C
    Package 3                             1Z...D
  ▼ Batch 2  1Z...B                       50 items · $45.50
    Package 1                             1Z...E
    ...
```

### 4. Label PDF download

Existing `/label/pdf` returns merged PDF via `PdfMerger`. Extend to accept `?batch=all|N`.
Default `all` = all batches × all packages, merged into one PDF.

### 5. API service (`orderService.ts`)

New function `submitManualShipmentWithSplit(payload, strategy)` — wrapper around
`submitManualShipment` that appends `splitStrategy` field.

## Rollout / carrier scope

**Phase 1 (this plan):** UPS only.

**Phase 2 (follow-up):** Port to FedEx / DHL / USPS. FedEx cap is much higher (500+),
DHL is 25 per shipment, USPS varies by service. Each connector reports its cap via existing
`CarrierShippingLimit.maxCommodities`.

## PR breakdown

**PR 1 — Backend DTOs + splitter:**
- `SplitStrategy` enum
- `ShipmentSplitter.splitByCommodityStrategy` + tests
- `SplitRequiredResponse` DTO
- Deprecate `CommoditiesLimitExceededException`

**PR 2 — Backend orchestration:**
- `CarrierServiceImpl.generateManualLabel` refactor to loop
- Persistence for N-batch orders
- Idempotency key derivation
- Integration test with UPS sandbox

**PR 3 — FE modal + submit:**
- `SplitShipmentModal.tsx`
- `NewShipmentPage.tsx` submit-error handler
- `orderService.ts` new methods

**PR 4 — Order-detail rendering + PDF download:**
- Multi-batch accordion (leverage MPS)
- `/label/pdf?batch=all` extension
- E2E test

## Testing plan

**Backend:**
- Unit: `splitByCommodityStrategy` for each strategy on 100/190/500 commodity fixtures
- Integration: UPS sandbox — 190-commodity shipment, verify 4 tracking numbers, 4 labels persisted
- Integration: partial failure — split 4 → UPS accepts 3, rejects 4th → transaction rollback

**FE:**
- Modal renders 3 strategies correctly
- Strategy pick triggers re-submit with correct field
- Order detail shows N batches

## Risks / open questions

1. **Partial failure atomicity** — if UPS accepts split 1-3 but rejects split 4, do we void 1-3?
   Recommend YES (atomic all-or-nothing) — otherwise operator has 3 stuck labels.
   Cost: extra UPS voidShipment calls on failure.

2. **Duplicate physical shipment cost** — SAME_PACKAGES strategy costs 4× shipping.
   Operator must accept this explicitly in the modal. Consider showing estimated cost
   uplift on each strategy card.

3. **Carrier voidShipment reliability** — if void fails during atomic rollback, operator has
   dangling labels. Log + alert; manual reconciliation.

4. **UPS 1ZXXXX sandbox tracking** — all sandbox splits return the same placeholder
   `1ZXXXXXXXXXXXXXXXX`. FE will show 4 identical trackings — expected in sandbox, real prod
   returns distinct 1Zs. Add a sandbox banner explaining.

5. **Commodity → package attribution** — Strategy C needs `boxSeq` field which not all
   operators populate. Fallback path: if any commodity lacks `boxSeq`, degrade to PROPORTIONAL.

## Interim workaround (today, not part of this plan)

Until this feature ships, the operator can:
1. Split their order manually in the FE — create N orders each with ≤50 items.
2. Use the "Custom package" option to bypass branded-packaging restrictions.

Reject the shipment quickly with the current error message + this workaround note.
