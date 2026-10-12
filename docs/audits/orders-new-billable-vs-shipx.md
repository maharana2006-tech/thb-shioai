# Billable batches (.Y) — ShipX vs shioai `/orders/new`

**Repo:** thb-shioai (dev @ 2026-09-29)
**Author:** Read-only gap analysis; no code changes.
**Scope:** the `.Y<batchId>` billable-batch flow on ShipX Quick-Ship / BackOrder vs shioai `/orders/new` (Manual Shipment) prefill + label + writeback.
**ShipX doc sources cited:** `part-B.md:263-278`, `deepdive/08c-billable.md`, `appendix-B.md:2135-2162` (spx_updatebillablestatus), `appendix-C.md:41-68` (NDS table map).

---

## 1. Overview — what is a "billable batch"?

ShipX definition (`part-B.md:265`, `deepdive/08c-billable.md:3`):
> A billable batch is a **.Y-prefixed barcode** that groups **several orders across ONE client** into shared physical boxes. NDS keeps the definition in `TB_BILLABLE_CONTAINERS`: one row per (batch, physical-box, order). Each physical box (`CONTAINER_NO`) may hold **multiple orders** — a single label bills freight against every order sharing that box.

The batch is scanned on Quick-Ship (single .Y) or BackOrder (comma list of `.X`/`.Y`); ShipX derives:
1. **Address** from the order in the box with the lowest `CONTAINER_NO` (`08c:16`). Sibling orders' addresses are ignored silently.
2. **One label with N pieces**, where N = distinct `CONTAINER_NO` in the batch (`08c:19-40`). Package weight = `TB_BILLABLE_CONTAINERS.WEIGHT` (pre-computed rated weight, NOT `OE_SHIP_CONTAINER.GROSS_WT`).
3. **Per-piece tracking** returned by the carrier is written back to **every order sharing that piece's box** (`08c:47-56`, `appendix-C:10` `SetTrackingQbillable`).

Key contrast with a regular order:
- A regular `.X` scan operates on ONE order with N of its own containers. Billable groups orders that don't otherwise share ANY key.
- Writeback fan-out is per-order-per-box for `.Y`; per-container-per-order for `.X`.
- Cancel of any single label in a billable batch cancels **all** labels sharing the `BillableBatch` (via `spx_updatebillablestatus`, `appendix-B:2135-2162`).

Freight is written once per package on ShipX (`FreightCost = whole shipment's net charge`; noted as double-counting risk in the doc `08c:63`).

---

## 2. ShipX behavior (canonical)

### 2.1 Lifecycle

| Step | Login | Table / SP | Purpose | Doc ref |
|------|-------|------------|---------|---------|
| Scan | prod | `TB_BILLABLE_CONTAINERS` | Resolve batch → `FF_SCHEMA` (client), pick lowest-`CONTAINER_NO` row for anchor | `part-B:275`, `08c:7-14` |
| BackOrder multi-scan | prod | `TB_BILLABLE_CONTAINERS` via `YTenantDetails` | `SELECT DISTINCT batch_id, ff_schema WHERE batch_id IN (…)` — **only bound-parameter query in the whole flow** | `08c:17`, DAL L2198 |
| Anchor read | client | `OEHEAD + OE_SEND_TO + TB_SHIP_CONTAINER + OE_SHIP_CONTAINER + SHIPVIA` | Ship-to, ship-via, notify, thpAccount, weight, invno | `part-B:276`, `08c:16` |
| Package fan-out | client | `TB_BILLABLE_CONTAINERS ⨝ TB_SHIP_CONTAINER` (`GetMultiContainerByOrders`) | One row per `(batch, container_no, order_suffix)`; container_ids & order_nos as XMLAGG CSV; WEIGHT from billable table | `08c:19-34` |
| Intl customs | client | `TB_CLIPPER_ITEM_DETL + OEHEAD + OE_SHIP_CONTAINER + OEDETL` | Commercial-invoice items scoped to the batch's order set | `08c:42-45` |
| Ship (carrier) | – | – | One shipment with **N packages** (sequenceNumber 1..N); carrier returns N trackings | `08c:38-40` |
| Writeback: CLIPPER | client | `CLIPPER` UPDATE via `SetTrackingQbillable` | Per package: `TRACK = tracking, FREIGHT = freight WHERE TENANT_ID + ORDER_NO IN (<all orders in this box>) + CONTAINER_NO + ORDER_SUFFIX` | `08c:47-53`, `appendix-C:10` |
| Writeback: SPX rows | – | `SPX_tblShipmentDetails` INSERT via `Shipmentd` | One `SPX_tblShipmentDetails` row PER package with `BillableBatch` set (the anchor for the cancel-all SP) | `08c:55` |
| Writeback: USPS no-container | client | `<client>.PROC_OE.INSERT_OE_TRACKING(SYSDATE, ORDER_NO, ORDER_SUFFIX, 'CLIPPER', '450', tracking)` | Only when no CLIPPER row exists; USPS-only, single-package | `part-B:278`, `08c:58` |
| Cancel (of ANY row in batch) | shipx-pg | **SP `spx_updatebillablestatus(shipmentId)`** — `UPDATE SPX_tblShipmentDetails SET Status=false, ErrorMessage='Cancelled', ZPLCommand='' WHERE BillableBatch = (subquery on inid)` | Bulk-cancels ALL sibling shipments sharing the `BillableBatch` | `appendix-B:2135-2162`, DAL `L1270, L1704` |
| Cancel writeback: CLIPPER | client | `SetTrackingQbillable` with empty tracking | Zeroes the tracking on every order in the batch's boxes | `08c:57` |

### 2.2 Rules that change what happens (from `part-B:236-247`, applied identically to `.Y`)

- **HLD ship-via** blocks with "Order is on Hold."
- **Blank ship-via** blocks with hint from the ship-via hint table.
- **Phone**: whitespace removed; 10–15 chars kept, else **`+1 616 772 3513`** (Thoroughbred fallback).
- **Special chars** stripped from ship name / company; addr1 keeps `A-Z 0-9 space - / . , #`.
- **Missing email** → `support@thbred.com`.
- **Client mismatch**: chosen client must own the container or be `THB`.
- **Third-party account refused**: prompt "proceed on default?"; on yes reverts to client account.
- **STD ship-via**: NOT replaced for `.Y` batches (`part-B:293` — "STD batch fails"). This is a legacy quirk `.Y` code never patched.
- **Saturday delivery**: refused always (`part-B:240`, doc calls out this as a bug).
- **Container weight of 0** → carrier sees `1 lb` (`part-B:288`).

### 2.3 Status transitions & bulk operations unique to `.Y`

- ShipX inserts one `SPX_tblShipmentDetails` row per package, all sharing `BillableBatch` = batch id.
- `spx_updatebillablestatus` is the ONE SP that flips `Status → false` across every row in a batch simultaneously. There is no per-shipment cancel path that leaves siblings intact.
- **Freight is written to `CLIPPER.FREIGHT` on every order in every package** — the doc calls out that `FreightCost` from the FedEx API is the WHOLE shipment total, so on a batch of 5 boxes × 3 orders each, the same total gets written to all 15 CLIPPER rows (`08c:63`). This is legacy behaviour; unclear whether NDS billing expects the split.

---

## 3. shioai counterpart at `/orders/new`

### 3.1 FE prefill — the good part

The **read side is close to complete parity** (PR #735, 2026-09-22):

- Scan parser accepts `.X<containerId>` and `.Y<batchId>` (case-insensitive, CR/LF/tab-tolerant): `NdsScanValueParser.java:39-73`.
- Batch owner lookup on PRODUCTION login: `NdsShipmentLookupRepository.findBatchOwner` (`NdsShipmentLookupRepository.java:281-319`). Reads `FF_SCHEMA` from `TB_BILLABLE_CONTAINERS`. If operator supplied `clientCode`, narrows the SQL — cross-tenant batch-id collision is guarded.
- Package fan-out on CLIENT login: `NdsShipmentLookupRepository.findBillableBatchPackages` (`:359-393`). Uses **the exact ShipX XMLAGG SQL** from `deepdive/08c-billable.md:19-34`, joined on `oe_ship_container` (real live table) rather than doc's `tb_ship_container` (see `:344-351` for the deliberate change with rationale). Weight from `TB_BILLABLE_CONTAINERS.WEIGHT`, matches ShipX.
- Anchor picking: `NdsShipmentLookupService.lookupBatch` (`NdsShipmentLookupService.java:163-204`) picks the first row after `ORDER BY b.Container_No ASC` — same lowest-container-no rule as ShipX (`08c:16`).
- Intl customs items from `OE_INTL_ITEMS` (per-order) at `:245-273`; ShipX uses `TB_CLIPPER_ITEM_DETL` (`08c:43-45`). **Different source table — see gap G-INTL.**
- Notify email fallback via tenant setting `nds.fallback_notify_email` (falls back to `support@thbred.com`): `:394-411`.
- Phone normalizer `NdsPhoneNormalizer.java:33-46`: 10–15 char rule matches ShipX; per-tenant `nds.fallback_phone` overrides.
- STD → real ship-via resolver via `StdShipMethodResolver` (`:295-319`) — actually **better than ShipX**, which fails the batch on STD. Doc-anchored to `ShipX_NDS_Orders_and_Tracking.docx §5+§6`. Deliberate improvement.
- HLD / hold detection driven by `client_shipvia_code_map.is_hold` OR the literal `HLD` (`:288-294`, `:464-472`).

FE UI (`NewShipmentPage.tsx`):
- Scan input at `:3032-3080` accepts commas or whitespace; each token defaults to `.X` if unprefixed.
- Multiple prefills queue into `ndsQueue` (chip strip), operator clicks to swap active (`:1446-1474`).
- Applied prefill (`applyNdsPrefill` at `:1302-1375`): puts recipient into `recipient` state, first package into main dims/weight, boxes 2..N into `extraPackages`.
- Round-trips `stdReplacementErpCode` on submit (`:2409`) — but NOT `batchId`, NOT `scannedValue`, NOT per-box `containerIds`/`orderNos`.

### 3.2 Backend label + writeback — where it diverges

Writeback shape (`WritebackPayload.java`) DOES have slots for `batchId`, `scannedValue`, and per-package `containerIds`/`orderNos`/`containerNo`/`orderSuffix` (`WritebackPackagePayload.java:21-30`). NDS writer (`NdsShipmentOracleWriter.writeShipment`) iterates `p.packages()`, and for each package walks `pkg.orderNos()`, running the CLIPPER UPDATE for every `(tenant, orderNo, container_no=pkg.sequence, order_suffix)` tuple (`:87-114`). **The plumbing is there — but nothing drives it.**

Flow trace of the divergence (top-to-bottom):

1. **FE prefill `.Y`** → `NdsShipmentPrefill.packages[]` correctly populated with `containerNo`, `containerIds`, `orderNos`, `orderSuffix` for every box (`NdsShipmentLookupService.buildBatchPackages:206-230`). Also carries `batchId` on the response envelope (`:453`).
2. **FE `applyNdsPrefill`** (`NewShipmentPage.tsx:1302-1375`) reads only `first.weight/length/width/height` from `packages[0]` and pushes `packages[1..]` into `extraPackages` as pure `weight/dims` state. **Container/order metadata is dropped on the floor.** No FE state stores `batchId` or per-box `orderNos[]`.
3. **FE submit** (`:2460-2485`) builds a `packages: [{sequenceNumber, weight, dims, packageType, declaredValue}]` payload. No `batchId`, no `orderNos[]`, no `containerNo` fields on the payload.
4. **BE `ManualShipmentRequest`** (`dto/ManualShipmentRequest.java`) has no `batchId` field, no `ndsPackages[]` field. Only `stdReplacementErpCode` (line 92) round-trips.
5. **BE `CarrierServiceImpl.generateManualLabel`** builds the `WritebackPayload` (`:2360-2386`) without ever calling `.batchId(...)`, `.scannedValue(...)`, or `.packages(...)`. Default `packages = List.of()` (`WritebackPayload.java:100`).
6. **BE `ExternalSystemWritebackDispatcher.dispatchOnGenerate`** (`ExternalSystemWritebackDispatcher.java:72-100`) resolves `nds-default` connection, redacts unflagged fields, calls `NdsOracleConnector.writeShipment(payload)`.
7. **BE `NdsShipmentOracleWriter.writeShipment`** (`:82-138`): outer `if (!isReturn)` is entered; the `for (WritebackPackagePayload pkg : p.packages())` loop body **never executes** (`p.packages()` is `List.of()`). CLIPPER update count = 0. USPS fallback (`:120-136`) is gated on `count == 0 && isUsps(carrier)` — fires only for USPS. For FedEx/UPS/DHL `.Y` batches, nothing happens.
8. **BE `TB_MANUAL_SHIPMENT` INSERT** (`:170-197`): ONE row with `orderNoText = p.orderNo` (the anchor order). The batch id has nowhere to land — schema has no `BATCH_ID` column referenced in the INSERT column list (`:274-283`).
9. **BE `OEHEAD.SHIPVIA_CD` update on STD** (`:199-230`): iterates `p.packages[*].orderNos[*]` — **never executes for `.Y`** for the same reason as #7. `stdReplacementErpCode` is set on the payload but the loop it drives is empty.

Compare with ShipX: `08c:47-56` writes CLIPPER per (package × orderNo) for every order in every box; inserts `SPX_tblShipmentDetails` per package with `BillableBatch` set for the bulk-cancel SP.

Corollary: **PR #735's memory doc says "Oracle IT" was included — the IT verifies the READ side against a live Oracle testcontainer. It does not exercise writeback. There is no test that would have caught the empty `packages()` fan-out** — grep for `WritebackPayload` in `backend/src/test/**` to confirm.

### 3.3 Cancel / void

`VoidServiceImpl.java:167-208` cancels by `shipment_batch` (an internal auto-split concept — the shipment split into carrier hops for USPS Direct, not the billable batch). Each `ShipmentBatch` row corresponds to ONE tracking; `.Y` billable shipments produce ONE tracking per package inside ONE `label_batch` row (verified by tracing `CarrierServiceImpl:845-895`, the manual-label MPS persistence path — one `label_batch` + N `label_package`). The batch id has no persistence surface: there is no `label_batch.billable_batch_id` column (`grep V*.sql` on migration dir — no such migration).

Consequence: `spx_updatebillablestatus` — the ShipX SP that cancels every sibling row in a billable batch (`appendix-B:2135-2162`) — has **no shioai equivalent**. Voiding one label leaves all sibling orders' `order_tracking.status = GENERATED`, sibling `CLIPPER.TRACK` populated (once G1 lands) with a tracking that no longer exists at the carrier, and sibling `label_batch.status = GENERATED`. Operators will re-print labels for orders that were already cancelled.

### 3.4 FE queue divergence from ShipX BackOrder

ShipX BackOrder (`part-B:280-292`) accepts comma-mixed `.X` + `.Y` and ships them all in one submit. shioai `NewShipmentPage.tsx:1387-1442` accepts the mixed comma list too, but:
- Fires `Promise.allSettled` on N `nds-lookup` calls in parallel.
- Applies only the FIRST unique result (`:1427`).
- Queues the rest as chips (`:1428`, `ndsQueue`).
- Operator must click through each chip and hit "Generate label" again.

For `.X` scans this is intentional (each `.X` is an independent order → one label per submit). For a MIXED `.X + .Y` submission it silently discards the batch-ness of the `.Y` — the operator sees "3 orders queued" without any hint that box 2 of the `.Y` batch is different from a separate `.X` order.

---

## 4. Gaps

### G1 (BLOCKER) — CLIPPER writeback never fires for `.Y`

**Symptom:** operator ships a billable batch, label prints, NDS `CLIPPER.TRACK` + `FREIGHT` stay NULL for every order in every box.
**Cause:** `CarrierServiceImpl.generateManualLabel` (`:2360-2386`) never populates `WritebackPayload.packages`. The `.Y` batch's per-box `containerIds/orderNos` (available on `NdsShipmentPrefill.Package.orderNos[]` at prefill time) is dropped on the FE, then not re-fetched.
**Fix path:**
1. FE: add `ndsBatchId` + `ndsPackages: [{ sequence, containerNo, containerIds, orderNos, orderSuffix }]` on the payload when `applyNdsPrefill` runs from a `.Y` scan. Thread into request state.
2. BE: add `batchId: String` + `ndsPackages: List<NdsPackagePayload>` to `ManualShipmentRequest`. Thread into `.batchId(...)` + `.packages(...)` on the WritebackPayload builder.
3. Existing `NdsShipmentOracleWriter.writeShipment` code path (`:83-137`) already handles the fan-out — no change downstream.

### G2 (BLOCKER) — no bulk-cancel across batch siblings

**Symptom:** operator voids one label; the 4 sibling orders that shared the same physical boxes retain their CLIPPER tracking + `label_batch.status=GENERATED` in shioai even though the physical parcel is dead.
**Cause:** no `.Y`-batch-aware cancel path. `VoidServiceImpl` walks `shipment_batch` (auto-split shape), not the billable batch.
**Fix path:**
1. Persist `label_batch.billable_batch_id` (new column) on generate — populated from `ManualShipmentRequest.batchId` (G1).
2. On void, if `label_batch.billable_batch_id` is set, void every `order_tracking` that shares the same `billable_batch_id`. `NdsShipmentOracleWriter.clearShipment` fan-out already works per-package once fed the batch's orderNos.
3. Mirror `spx_updatebillablestatus` semantically — but as a Java loop, no Oracle SP needed.

### G3 (HIGH) — sibling orders in the batch have no TB_MANUAL_SHIPMENT row

**Symptom:** NDS's Manual Ships list shows the label under the anchor order only. Ops team can't find the shipment when searching by any of the 2..N sibling orders.
**Cause:** writer INSERTs ONE `TB_MANUAL_SHIPMENT` row using `p.orderNo` (anchor only). ShipX inserts one `SPX_tblShipmentDetails` per package (`08c:55`), each carrying `BillableBatch`.
**Fix path:** in `NdsShipmentOracleWriter.writeShipment` `:170-197`, when `p.batchId != null`, loop `insertTbManualShipment` across every distinct order in `p.packages[*].orderNos`, tagging each row with `BATCH_ID` (needs NDS column addition + confirm with ops).

### G4 (HIGH) — freight double-counting risk NOT resolved

ShipX writes the SAME freight total to every CLIPPER row on every order in every box (`08c:63`). shioai will inherit this once G1 lands — same code path (`NdsShipmentOracleWriter.java:97-108` sets `FREIGHT_AMOUNT = :freight` unchanged per fan-out).
**Fix path (deliberate policy choice):** either
- (a) mirror ShipX literally and let NDS de-dupe upstream, or
- (b) divide `p.freightAmount()` by the number of `pkg.orderNos()` receiving the update (proportional), or
- (c) write freight only to the first order per box (marker row).
Needs ops sign-off before the writer changes. Recommend `(c)` with a `ponytail:` comment naming the policy ceiling.

### G5 (HIGH) — STD substitution silently applied to `.Y` batches

ShipX `part-B:293`: STD batches FAIL. shioai (`NdsShipmentLookupService.java:295-319`) silently resolves STD via `StdShipMethodResolver` for BOTH `.X` and `.Y`. On the shared-box case that means the batch's OTHER orders — never intended for the resolved carrier — also get their OEHEAD.SHIPVIA_CD updated (`NdsShipmentOracleWriter.java:199-230`) once G1 fan-out lands. Ops needs to sign off: is STD replacement legal across an entire billable batch, or only the anchor order?

### G6 (MED) — anchor order suffix defaults to `"0"` on `.Y`

`NdsShipmentLookupService.java:194-195`: when the anchor row has null `orderSuffix`, service falls back to `"0"`. `OEHEAD` lookup then queries `ORDER_SUFFIX = 0` — may miss the real header if NDS uses null/empty distinct from `0`. Doc doesn't confirm. Open question at the bottom.

### G7 (MED) — CI items sourced from `OE_INTL_ITEMS`, not `TB_CLIPPER_ITEM_DETL`

Prefill CI items pulled from `OE_INTL_ITEMS` (`NdsShipmentLookupRepository.java:245-273`). ShipX uses `TB_CLIPPER_ITEM_DETL` for billable batches (`08c:42-45`) with a fallback to `billableItemDetails1`. Deliberate divergence? For a batch spanning multiple orders, the shioai path fetches items for ONE order (the anchor); ShipX fetches items across ALL orders in the box. **International .Y batches lose per-box customs granularity.**

### G8 (LOW) — Saturday delivery bug port

Not ported (correctly). ShipX blocks all `FSD`/`USD` shipments even for US recipients (`part-B:240`, doc-called bug). shioai has no equivalent code — this is a **gap in the good direction**. No fix needed.

### G9 (LOW) — Third-party-account "proceed with default?" prompt

ShipX prompts operator to fall back to client's own account when the 3rd-party account is refused (`part-B:245`). shioai has no equivalent — connectors just fail with the carrier error. Non-blocker; carrier error surfaces the same info differently.

### G10 (LOW) — `SPX_tblShipmentDetails.BillableBatch` has no parallel

ShipX inserts one `SPX_tblShipmentDetails` row per package with `BillableBatch = <batchId>` set (`08c:55`). This is the anchor that `spx_updatebillablestatus` (`appendix-B:2148-2161`) uses to identify sibling rows. shioai has no `SPX_tblShipmentDetails` at all — that table is ShipX-side. The equivalent role for shioai is `label_batch` (our own tracking row); adding `billable_batch_id` there (G2) provides the fan-out anchor without needing a table equivalent.

### G11 (LOW) — Anchor row's `Container_ID` CSV order is unstable

`NdsShipmentLookupRepository.java:365-368` uses XMLAGG **with** `ORDER BY c.Container_ID` — an improvement over the ShipX-noted flaw (`08c:61`: "GetMultiContainerByOrders has no ORDER BY"). Tracking-to-box mapping should be stable now. Retained as a note: if ops sees mismatches, `ORDER BY` in the SUBQUERY is honoured but the outer `SELECT DISTINCT ... ORDER BY b.Container_No, a.Order_Suffix` (`:377`) is what governs the row order the writer sees. Both are ordered — no residual instability.

---

## 5. Hardcoded findings (business rules + reference data in code)

Ranked by whether the value SHOULD live in DB / FE-managed vs where it lives today.

| # | Finding | File:line | Severity | Fix |
|---|---------|-----------|----------|-----|
| H1 | **US territory ISO codes** `{PR, VI, GU, AS, MP, UM}` hardcoded as `Set.of(...)` → drives "is international" flag → drives customs-item queries + carrier intl block. Adding a new territory (or removing one) needs code deploy. | `NdsShipmentLookupService.java:53` | HIGH | promote to `country.is_us_territory` on existing country table; or `tenant_settings.nds.us_territory_codes` KV |
| H2 | **Address whitelist regex** `[A-Za-z0-9 .,\-#/&']` — literal Pattern. Any client shipping to JP/DE/BR where legit street strings include `ü` / `ō` / accented chars gets those stripped silently. | `NdsAddressSanitizer.java:24` | HIGH | move to `carrier_config.address_charset_whitelist` (per carrier) OR promote to a global setting; add a "preserve unicode" tenant toggle |
| H3 | **Fallback phone** `+1 616 772 3513` (Thoroughbred switchboard) as `DEFAULT_PHONE`. Already overridable via `nds.fallback_phone` tenant setting — this is the PLATFORM default. Ok for now, but the string is a THB-specific number: white-labelling to a new tenant requires code. | `NdsPhoneNormalizer.java:18` | MED | move platform default itself to `application.properties` (`multiship.nds.default-phone`) or global settings row so ops changes it without a build |
| H4 | **Fallback notify email** `support@thbred.com` — same shape as H3. Already tenant-overridable, but platform default is Thoroughbred-hardcoded. | `NdsShipmentLookupService.java:56` | MED | mirror H3 fix |
| H5 | **Default weight 1 lb** as `BigDecimal.ONE`. Already tenant-overridable via `nds.default_weight_lb`; platform default is a compile constant. | `NdsShipmentLookupService.java:59` | LOW | mirror H3 fix |
| H6 | **HLD literal** `"HLD".equalsIgnoreCase(shipvia)` as the last-resort hold detector even after `is_hold` mapping is checked. Retained for backwards compat per the code comment. A tenant whose ERP uses "HOLD" without also filling `is_hold` will never block. | `NdsShipmentLookupService.java:466` | LOW | acceptable as fallback; document the tenant onboarding step ("if your ERP uses HOLD/ONHOLD, mark those rows `is_hold=true`") |
| H7 | **Scan prefixes** `.X` / `.Y` as compile constants. If a new prefix (`.Z` for return, `.W` for warranty) is ever added the enum widens + parser widens. Fine for two, brittle for N. | `NdsScanValueParser.java:39-40` | LOW | promote to enum-of-enums when 3rd prefix arrives; not needed today |
| H8 | **NDS carrier display map** `{FEDEX → "FedEx", UPS → "UPS", USPS/USPS_DIRECT/STAMPS_COM → "USPS", DHL → "DHL"}` — hardcoded switch used to fill `TB_MANUAL_SHIPMENT.CARRIER` (NOT NULL column). Any new carrier requires code change AND may write raw code to NDS. | `CarrierServiceImpl.java:2398-2407` | MED | move to `carrier.nds_display_name` column on `carriers` table |
| H9 | **USPS variants for CLIPPER fallback** `{USPS, USPS_DIRECT, STAMPS_COM}` — the `isUsps()` guard for the "no CLIPPER row → PROC_OE.INSERT_OE_TRACKING" fallback. Adding a new USPS carrier variant requires code change. | `NdsShipmentOracleWriter.java:602-608` | MED | drive from `carrier.uses_usps_tracking_fallback` bool column on `carriers` |
| H10 | **`PROC_OE.INSERT_OE_TRACKING` param names** `P_ORDER_NO / P_ORDER_SUFFIX / P_SOURCE / P_CODE / P_TRACKING` — inferred from Oracle naming convention (`NdsShipmentOracleWriter.java:576-599`). Live procedure metadata was unreachable at write time. Wrong names silently fail. | `NdsShipmentOracleWriter.java:587-598` | HIGH | verify against live procedure on next PROD deploy; if wrong, ops-facing config value or code fix |
| H11 | **BATCHSHIP / CLIPPER source strings** for `PROC_OE.INSERT_OE_TRACKING` (`"BATCHSHIP"` for DTC, `"CLIPPER"` for USPS-fallback) — magic strings matching NDS's `TRACK_CD` enumeration. | `NdsShipmentOracleWriter.java:128, 505` | LOW | acceptable — these are external contract literals defined by NDS |
| H12 | **`nds-default` connection name** used across 5+ call sites as a compile constant, prevents "second NDS server in parallel" without code change. (Cross-ref with existing NDS-audit A4.) | `NdsTemplates.java:41` etc. | MED | route by tenant setting; already in existing NDS-audit backlog |

---

## 6. NDS touchpoints (billable-specific)

Every NDS-touching literal / config path for the `.Y` flow, with FE-management status:

| Concern | Location | Value | FE-manageable? |
|---------|----------|-------|----------------|
| Batch owner query (prod) | `NdsShipmentLookupRepository.java:293-319` | SQL literal | No; correct — no reason to expose SQL. Connection host/port/user is FE-managed via `/settings/external-systems` (S1 framework). |
| Package fan-out query (client) | `NdsShipmentLookupRepository.java:359-393` | SQL literal | No; correct — same rationale. |
| CI items table choice (`OE_INTL_ITEMS` vs `TB_CLIPPER_ITEM_DETL`) | `NdsShipmentLookupRepository.java:245-273` | Hardcoded to `OE_INTL_ITEMS` | **No — should be settable per-tenant** (G7). |
| CLIPPER update columns (TRACK, FREIGHT) | `NdsShipmentOracleWriter.java:97-100` | SQL literal | No; correct. |
| `TB_MANUAL_SHIPMENT` column list | `NdsShipmentOracleWriter.java:274-284` | 20-column literal | No; correct — reflects the NDS DDL. |
| CLIPPER writeback flag on connection | `V89__external_system_writeback_flags.sql` | Per-connection bool `writeback_tracking` etc. | **Yes** — `/settings/external-systems` writeback page. Good. |
| Source/channel routing (V90) | `V90__external_system_writeback_source_channel_flags.sql` | Per-connection bool per (source, channel) | **Yes** — same page. Good. |
| Bulk vs manual writeback (V93/V94) | `V94__nds_default_bulk_writeback_off.sql` | Seeded default: BULK writeback OFF | **Yes** — configurable in FE. Good. |
| `PROC_OE.INSERT_OE_TRACKING` param names | `NdsShipmentOracleWriter.java:587-598` | Compile-constant `P_ORDER_NO` etc. (H10) | No; SHOULD be verified against prod once, then acceptable to leave hardcoded. |
| Batch-cancel behavior | **absent** | – | **No — needs `label_batch.billable_batch_id` column + `VoidServiceImpl` fan-out (G2).** |
| Per-order-in-batch TB_MANUAL_SHIPMENT insert | **absent** | – | **No — G3.** |

**FE-management gap summary for `.Y` billable specifically:**

1. There is NO admin surface where an operator can see "batch `.Y12345` is in flight; 5 boxes, 12 orders, all shipped, none voided." Batch identity is dropped between prefill and writeback. Fix depends on G1.
2. Freight-split policy (G4) needs FE selector per-connection (`writeback.freight_split_policy` = `TOTAL_ON_EVERY | FIRST_ORDER_ONLY | PROPORTIONAL`).
3. STD-replacement-on-batch policy (G5) needs FE toggle: "allow STD substitution on billable batches" (default OFF to match ShipX).

---

## 7. Prioritized fix plan

| # | Item | Priority | LoC est | DB migrations | Risk | Rationale |
|---|------|----------|---------|---------------|------|-----------|
| 1 | **G1** — Thread `batchId` + `ndsPackages[]` through FE → `ManualShipmentRequest` → `WritebackPayload.builder().batchId().packages()` in `CarrierServiceImpl.generateManualLabel` | **P0** | ~120 (60 BE / 60 FE) | none (`batchId` optional on request; writer path already exists) | LOW — additive fields, existing writer code path exercised | Without this, `.Y` billable is FUNCTIONALLY BROKEN on the NDS side: CLIPPER never updates for any of the batch's orders. |
| 2 | **G2** — Persist `label_batch.billable_batch_id` (V102), fan cancel across sibling orders in `VoidServiceImpl` | **P0** | ~80 | 1 (`ALTER TABLE label_batch ADD COLUMN billable_batch_id VARCHAR(50)`, index) | MED — void semantics change; needs regression test on non-batch void | Without this, voiding one label in a batch leaves the batch's siblings stuck in `GENERATED` state with dead tracking numbers. |
| 3 | **G3** — Loop `insertTbManualShipment` across `p.packages[*].orderNos` when `p.batchId != null` | **P1** | ~40 | 0 (may need `TB_MANUAL_SHIPMENT.BATCH_ID` column addition — ops call) | LOW-MED — needs NDS column confirmation | Ops can't find the label in NDS's Manual Ships list by any of the sibling orders' order numbers. |
| 4 | **G4** — Freight-split policy on connection + writer | **P1** | ~60 | 1 (`ALTER TABLE external_system_connection ADD COLUMN writeback_freight_split_policy VARCHAR(20) DEFAULT 'TOTAL_ON_EVERY'`) | LOW — DB-driven policy, default preserves ShipX behavior | Prevents accidental 5× freight billing regressions if NDS-side dedupe isn't in place. |
| 5 | **G5** — STD substitution guard for `.Y` scans | **P1** | ~15 | 1 (`tenant_settings` key `nds.allow_std_replacement_on_batch` bool, default `false`) | LOW — defaults to ShipX-strict | Ships with ShipX-strict default so no live tenant regresses. |
| 6 | **H1** — Move US territory codes to DB (either `country.is_us_territory` column OR tenant setting) | **P2** | ~30 | 1 (add column + backfill) | LOW | Adds/removes a territory without a deploy. Rare event but cheap. |
| 7 | **H2** — Address whitelist per-carrier | **P2** | ~40 | 1 (add `carriers.address_charset_whitelist_regex TEXT`) | MED — regex misconfig can strip valid addresses | Carriers vary in what they accept; per-carrier is the right axis. |
| 8 | **H8** — NDS carrier display map to `carriers.nds_display_name` column | **P2** | ~20 | 1 (`ALTER carriers ADD nds_display_name VARCHAR(50)`, backfill) | LOW | Removes a compile-constant map. |
| 9 | **H9** — USPS-family guard via `carriers.uses_usps_tracking_fallback` column | **P2** | ~15 | 1 (add column + backfill) | LOW | Same rationale as H8. |
| 10 | **H10** — Verify `PROC_OE.INSERT_OE_TRACKING` param names in prod; document | **P2** | ~5 | 0 | HIGH if wrong — silent failure of the USPS-no-CLIPPER fallback and DTC-tracking write | One prod smoke-test; fix if names differ. Existing `ponytail:` comment names the ceiling. |
| 11 | **G7** — CI-items source per-tenant | **P3** | ~30 | 1 (`tenant_settings` key `nds.intl_items_source` = `OE_INTL_ITEMS` \| `TB_CLIPPER_ITEM_DETL`) | LOW | Only bites when tenants ship international `.Y` batches — narrow blast radius. |
| 12 | **H3/H4/H5** — Platform defaults from `application.properties` | **P3** | ~20 | 0 (config only) | LOW | Cosmetic; already tenant-overridable. |

---

## 8. Open questions

1. **G6 — order-suffix default `"0"`**: does NDS's `OEHEAD.ORDER_SUFFIX` treat `NULL` and `0` as distinct? If yes, the `NdsShipmentLookupService.java:194-195` fallback silently misses headers. Confirm with ops via a single query on a batch known to have null-suffix anchor rows.
2. **G3 — TB_MANUAL_SHIPMENT.BATCH_ID column**: does the real PRODUCTION schema have a batch-id column, or is the ShipX SP the only linkage? Check via `NdsDebugController /describe` on prod.
3. **G4 — Freight-write intent**: does the accounting team WANT freight on every order in the box (as ShipX writes) so NDS-side reports can pro-rate, or does it want the split? Answer picks between (a)/(b)/(c) in G4.
4. **G5 — STD on billable batches**: is ShipX's "STD batch fails" behavior a bug or a rule? shioai has been silently working around it since PR #735; policy call needed before locking in G5's default.
5. **CI-items granularity**: for an intl billable batch with 3 orders per box, is one order's CI items expected to represent the whole box, or should we UNION items across orders? Ops preference decides G7.
6. **`spx_updatebillablestatus` port**: ShipX's SP flips `SPX_tblShipmentDetails.Status = false` on batch-cancel. shioai's equivalent (G2) will need to walk `label_batch.status`, `order_tracking.status`, and clear `CLIPPER.TRACK` on every batch sibling. Confirm no downstream (billing exports, DTC sync) depends on the specific `Status=false, ErrorMessage='Cancelled', ZPLCommand=''` triple.
7. **BackOrder page**: ShipX's BackOrder (`part-B:280-292`) accepts comma-mixed `.X`+`.Y`. shioai's `/orders/new` scan input already accepts mixed comma lists (`NewShipmentPage.tsx:1387-1388`), BUT applies only the first prefill (`:1427`), queueing the rest. That's a UX divergence from ShipX (which ships all at once). Is a batch-submit path wanted, or does the queue-and-swap model suffice? Not a functional gap for a SINGLE `.Y` scan — flagged here for completeness.

---

## 9. File map (cross-reference)

**shioai — .Y billable code paths:**

- FE scan input + queue: `multiship-react/src/components/NewShipmentPage.tsx:279`, `:1302-1474`, `:1387-1442`, `:2404-2411`, `:3032-3080`.
- FE service client: `multiship-react/src/api/ndsShipmentService.ts:112-143` (`NdsShipmentPrefill` type + `lookup()`).
- BE controller: `backend/src/main/java/com/multiship/backend/controller/NdsShipmentLookupController.java:44-105`.
- BE scan parser: `backend/src/main/java/com/multiship/backend/service/ndsshipment/NdsScanValueParser.java:39-75`.
- BE prefill service: `backend/src/main/java/com/multiship/backend/service/ndsshipment/NdsShipmentLookupService.java`
  - `.Y` orchestrator: `:163-204`
  - `.Y` package builder: `:206-230`
  - CSV helpers: `:232-261`
  - Response assembly: `:265-454`
  - Hold / STD rules: `:288-329`, `:464-472`
  - International detection: `:538-543`
- BE prefill repository: `backend/src/main/java/com/multiship/backend/service/ndsshipment/NdsShipmentLookupRepository.java`
  - Batch owner: `:281-319`
  - Package fan-out (ShipX XMLAGG SQL): `:359-393`
  - Order header: `:101-153`
  - Intl items (from `OE_INTL_ITEMS` — G7): `:245-273`
- BE DTO: `backend/src/main/java/com/multiship/backend/dto/ManualShipmentRequest.java` (no `batchId` or `ndsPackages` — see G1).
- BE writeback payload + writer:
  - `backend/src/main/java/com/multiship/backend/service/externalsystems/writeback/WritebackPayload.java:31-138`
  - `backend/src/main/java/com/multiship/backend/service/externalsystems/writeback/WritebackPackagePayload.java:21-30`
  - `backend/src/main/java/com/multiship/backend/service/externalsystems/writeback/ExternalSystemWritebackDispatcher.java:72-100`
  - `backend/src/main/java/com/multiship/backend/service/ndsshipment/NdsShipmentOracleWriter.java`
    - Outbound: `:52-241`
    - CLIPPER loop (empty for .Y today — see G1): `:83-138`
    - `TB_MANUAL_SHIPMENT` INSERT: `:170-197`, `:247-284`
    - OEHEAD STD update: `:199-230`
    - Cancel: `:292-384`
    - USPS-family guard (H9): `:602-608`
    - `PROC_OE.INSERT_OE_TRACKING` param names (H10): `:587-598`
- BE writeback caller: `backend/src/main/java/com/multiship/backend/service/CarrierServiceImpl.java`
  - Manual writeback dispatch (missing `.batchId` + `.packages`): `:2352-2391`
  - Auto-path writeback (same bug): `:780-808`
  - NDS carrier display map (H8): `:2398-2407`
  - Ship-to builder for writeback: `:2410-2432`
- BE void: `backend/src/main/java/com/multiship/backend/service/VoidServiceImpl.java:161-266` (no `.Y` batch fan-out — see G2).
- BE FE-managed defaults: `multiship-react/src/components/settings/NdsFallbacksSection.tsx:1-80`.
- DB migrations related:
  - V78 seeds `nds-default`: `backend/src/main/resources/db/migration/V78__external_systems_seed_nds_default.sql`.
  - V89 writeback flags: `V89__external_system_writeback_flags.sql`.
  - V90 source/channel writeback: `V90__external_system_writeback_source_channel_flags.sql`.
  - V93/V94 bulk-writeback default OFF: `V93__external_system_writeback_dtc_wms_gates.sql`, `V94__nds_default_bulk_writeback_off.sql`.
  - V96 `label_batch.nds_resolved_shipvia_cd`: `V96__label_batch_nds_resolved_shipvia_cd.sql`.
  - **Missing (needed by fix plan):** `label_batch.billable_batch_id` (G2), `external_system_connection.writeback_freight_split_policy` (G4), `tenant_settings.nds.allow_std_replacement_on_batch` seed (G5).

**ShipX — reference (read-only from handover):**

- Narrative: `D:/GIT-REPOS/ShipX/docs/handover/source/part-B.md:263-278` (Billable batches (.Y)); `:280-292` (BackOrder Quick Ship).
- SQL deep-dive: `D:/GIT-REPOS/ShipX/docs/handover/source/deepdive/08c-billable.md` (whole file, 67 lines).
- Bulk-cancel SP: `D:/GIT-REPOS/ShipX/docs/handover/source/appendix-B.md:2135-2162`.
- NDS table map: `D:/GIT-REPOS/ShipX/docs/handover/source/appendix-C.md:10-68`.

---

*End of report — read-only, no code changes.*
