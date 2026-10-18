# Return Ship gap analysis — ShipX vs shioai

Read-only audit. Date: 2026-09-29. Author: subagent.

Scope: compare legacy ShipX **Return Ship** (Shipment → Return Ship + Return
History) against the current shioai return-label path (in-line inside
`/orders/new`). Cite file:line for shioai, doc line-range for ShipX.

## 1. Overview

**ShipX** ships returns from a **separate screen** ("Manual Ship" +
"Return Ship" are siblings under **Shipment**). The operator types the
customer's address (parcel origin), the client's address is hard-wired
as destination, country is hard-fixed to **US**, and only **FedEx** or
**UPS** are offered (USPS returns are silently dropped). Returns are
saved in a **dedicated table** `SPX_tblReturnDetails` and given order
number **`REN-<id>`**. A Live carrier writes a row to NDS
`TB_MANUAL_SHIPMENT` with `ERROR_MODE='R'`, `ORDER_NO='REN -<id>'` (with
a space). Return History is a **separate grid**; retry ("Returnship") and
cancel-with-carrier are supported. No RMA number, no return-reason
picklist, no refund tie-in, no email-return-label option — PRINT only.

**shioai** has **no dedicated Returns page and no dedicated Returns
route**. Return-label creation is an **in-line mode toggle on
`/orders/new`** (`SHIPMENT` / `RETURN`) at
`multiship-react/src/components/NewShipmentPage.tsx:225-226`. There is
no `SPX_tblReturnDetails` equivalent: returns share the same `orders`
row with `Order.is_return = 'Y'` and the same `MAN{orderNo}` order
number (there is no `REN-` prefix anywhere). The return-label pipeline
is **substantially more capable than ShipX in three dimensions**
(carriers, geography, delivery type) but is **structurally hidden**
(no menu entry, no history filter, no retry-return button) and **missing
several first-class return-only fields** (RMA, return reason, original
outbound order tie-in).

## 2. ShipX behavior (extracted from docs)

Source: `docs/handover/source/part-C.md:328-368`,
`docs/handover/source/deepdive/12b-return-ship.md:1-93`,
`docs/handover/source/appendix-B.md:1819-1864` (spx_proshipmentreturn),
`appendix-B.md:2240-2271` (spx_updatereturn),
`combined.md:6466-6477` (Return History query).

### Screen: `ShipmentController.ManualReturn`

- Loads clients + printers from Postgres (part-C.md:308-311; deepdive:8).
- Hard-codes `isreturn=true` and `RecptCountry='US'`
  (`Views/Shipment/ManualReturn.cshtml:L349, L374`; deepdive:9).
- Only FedEx / UPS carrier options. USPS returns are dropped by
  `BALShiplist.ReturnShipments` → `return 0` (deepdive:21).
- No return-reason picklist. No RMA number field. No paired-with-outbound
  linkage (no "return of order X" lookup). No refund hook.

### Carrier call

| Carrier | Wire | Retry |
|---|---|---|
| FedEx | `BALFdExShipApi.ReturnShipments`. Reads `FedExFromAddrss` + MKL246 SP for FSM. | If Status=false, retry once with third-party blanked (`BALShiplist.cs:L2279-L2284`; deepdive:19). |
| UPS | `UPSAPIBAL.ReturnShipment`. Reads `UPSFromAddrss`. | None (deepdive:20). |
| USPS | *none*. `return 0`. Nothing saved (deepdive:21). |

Return-label delivery: **PRINT only** — carrier returns a PDF/ZPL to
ShipX; there is no "email the label to the customer" option.

### Save path

`spx_proshipmentreturn` (appendix-B B.32, `appendix-B.md:1819-1864`):
55-arg proc that INSERTs one row into `SPX_tblReturnDetails` with
`OrderNo = <blank>`, returns `ShipmentId`, COMMITs. The DAL then
renames on the same connection to `OrderNo = 'REN-<id>'`
(`DALShiplist.cs:L1198`; deepdive:38-40).

Retry: `spx_updatereturn` (B.42, `appendix-B.md:2240-2271`) — updates
carrier/service/packing/weight/tracking/ZPL/status/error on the row.

Cancel: `spx_UpdateStatus(<id>, 'Cancelreturn')` — sets
`Status=False, ErrorMessage='Cancelled'`, clears ZPL + TrackingID.

### NDS write-back

`SetManualTrackingQ` (`DALShiplist.cs:L2587-L2628`; deepdive:42-58):

```sql
INSERT INTO tb_manual_shipment (TENANT_ID, CARRIER, SHIP_SERVICE, THIRD_PARTY,
  SHIP_ATTN, COMPANY, ADDR1, ADDR2, CITY, STATE, COUNTRY, ZIP_CODE, SHIP_DATE,
  TRACKING, EMAIL, ORDER_NO, VOID_YN, FREIGHT, NOTE, ERROR_MODE)
VALUES (..., 'REN -<id>', 'N', ..., 'R')   -- note: space in 'REN -<id>'
```

- **UPS**: NDS row always written on Live.
- **FedEx**: NDS row written on Live **only when a note was typed**.
- **Retry (ReReturn)**: ignores the note check — writes 'R' for FedEx
  anyway (deepdive:74, quirk).
- **Cancel**: `update tb_manual_shipment set VOID_YN='Y'` only when note
  is non-blank; does not check carrier Mode (deepdive:80, quirk).

### Return History

Separate page `Views/ShipmentHistory/ReturnHistory.cshtml`. Query:
`SPX_tblReturnDetails WHERE CAST(TransactionDate AS TEXT) like ...`
(combined.md:6469-6477). Actions: Reprint, Returnship (retry a failed
return via `ProcessReReturn` at `BALShiplist.cs:L6707-L6894`; deepdive:74).
Void column **hidden** on the return grid (deepdive:92 "UNVERIFIED"),
though the underlying `Cancelreturn` action is wired.

### Known ShipX defects (from the deep dive)

- FedEx Live return without a note → **no NDS record** (deepdive:84).
- Retry overrides FedEx's note-check → **empty-NOTE 'R' rows in NDS**
  (deepdive:86).
- `ORDER_NO` differs between first save (`'REN -<id>'` with space) and
  retry (`'REN-<id>'` no space) — same return appears under two
  spellings (deepdive:85).
- Container ID is taken from `SPX_tblShipmentDetails` (last shipment id),
  not from `SPX_tblReturnDetails` (deepdive:13, 90).
- USPS returns silently do nothing.
- Cancel writes VOID_YN='Y' regardless of carrier Mode (Test carrier
  leaks into Prod NDS on cancel).
- SQL strings built by concatenation — apostrophe in customer name
  breaks the NDS insert (deepdive:89).

## 3. shioai counterpart

### 3.1 Frontend

**No dedicated Returns page.** Returns are a **mode toggle inside
`/orders/new`**:

- Mode state: `NewShipmentPage.tsx:225-226`
  `const [mode, setMode] = useState<'SHIPMENT' | 'RETURN'>('SHIPMENT')`
- Toggle UI: `NewShipmentPage.tsx:2891` — segmented radio.
- Address block flip: sender/recipient titles swap to
  "Return from · customer" / "Return to · your address"
  (`NewShipmentPage.tsx:3402, 3433`).
- Return delivery type: `PRINT` / `EMAIL` state at line 236, UI at 2905
  (only meaningful when isReturn=true).
- Auto-fill: when mode switches to RETURN, `switchMode()` at
  `NewShipmentPage.tsx:1477-1484` sets `reasonForExport='RETURN'` and
  swaps address prefill from `client.shipFrom` to
  `client.returnAddress ?? client.shipFrom` (line 1235).
- Client-config surface: `ClientEditorSteps/ReturnStep.tsx` — wizard
  step to configure per-client return address (with "same as ship-from"
  checkbox).
- Multi-package return note: UPS-specific banner at line 4139 informs
  the operator about connector-level MPS return split.
- Email guard: `returnEmailMissing = isReturn && !sender.email?.trim()`
  (line 245) — blocks generate button.

**No Return History route.** `DataHistoryPage.tsx` has no `isReturn`
filter chip; `DataHistoryFilterToolbar.tsx` does not surface return-only
rows. Search "Return" hits only generic `ReturnType<typeof ...>` TypeScript
utility patterns in that file.

**No "retry a failed return" button.** The generic "Fix & regenerate"
flow (via `fixOrderNo` query param on `/orders/new`) works for returns
because Order.is_return survives the fixup path.

### 3.2 Backend DTOs

- `ManualShipmentRequest.isReturn: Boolean` — `dto/ManualShipmentRequest.java:27`.
- `ManualShipmentRequest.returnType: String` (`PRINT`/`EMAIL`) — line 30.
- `ShipmentRequestDTO.isReturn: Boolean` — `dto/ShipmentRequestDTO.java:222`.
- `ShipmentRequestDTO.returnType: String` — line 241.
- `ExternalShipmentRequest.isReturn: Boolean` — `dto/external/ExternalShipmentRequest.java:76`
  (external v2 API exposes the flag).
- `Order.is_return: String` — `model/Order.java:179-180` (legacy `'Y'`/`'N'` column,
  not a boolean — pre-existing baseline, comment at `CarrierServiceImpl:3949`).

### 3.3 Backend controller / service

- No `/returns` route. Manual-label endpoint =
  `POST /api/orders/manual` → `CarrierServiceImpl.generateManualLabel`.
  Return handling is a **branch** on `req.getIsReturn()` at
  `CarrierServiceImpl.java:1017` (return_scope gate + client-return-
  address clamp) and 1943 (`order.setIsReturn(...)`).
- No `REN-<id>` prefix. Return orders get the same
  `MAN{orderNo}` label ref as manual outbound (`CarrierServiceImpl:2062`
  vicinity; label PO doc in memory).
- Splitter threads `isReturn` through to sub-requests
  (`ShipmentSplitter.java:366`) so multi-package + return interact
  correctly.
- `MaybeUpsUsPrReturnService` synthetic service at `CarrierServiceImpl:1613`
  swaps to `RETURN_US_PR` for the US↔PR corridor to hit the 1-pkg cap
  seed row.

### 3.4 Carrier connector coverage

| Carrier | Return-label support | File:line |
|---|---|---|
| UPS | Full. Code 8 (PRINT) + Code 9 (EMAIL) with `LabelDelivery.EMail` block, `ShipFrom` reversed, retailer-email fallback via tenant_settings. **Multi-pkg split** via `createSplitReturnShipment` (UPS Ship API caps returns at 1 pkg per call). | `UpsConnector.java:2107-2296` |
| FedEx | Full. `pickupType=CONTACT_FEDEX_TO_SCHEDULE` forced for returns (`FedExConnector.java:2234` comment). `returnedShipmentDetail.returnType=PRINT_RETURN_LABEL`. | `FedExConnector.java` (search `isReturn`) |
| DHL Express | Basic. `pickup.isRequested = isReturn` (`DhlConnector.java:1214-1223`) — DHL treats returns as normal shipments with reversed parties. No explicit Global Return product code plumbing. |
| USPS/Stamps SWSIM | Full. `<IsReturnLabel>true</IsReturnLabel>` in XML (`StampsConnector.java:3105-3111`). |
| USPS/Stamps SERA | Full. `is_return_label: true` (`StampsConnector.java:1129`, docs at 890). |
| USPS_DIRECT | *No explicit return-label support found.* Grep for `isReturn` in `UspsDirectConnector.java` returns 0 matches. Silent no-op behaviour (like ShipX's USPS branch) — a return submitted via USPS_DIRECT will produce a normal outbound label, wrong direction. |

### 3.5 NDS write-back

`NdsShipmentOracleWriter.java:63-197` implements the ShipX
TB_MANUAL_SHIPMENT `'R'` row semantics with **partial parity**:

- `shipmentMode='RETURN'` → `errorMode='R'` (line 171).
- `orderNo` → `'REN -' + orderNo` with a space (line 172-174) — matches
  the ShipX first-write spelling, but note: shioai has no `REN-<id>`
  concept internally so `p.orderNo()` is the shioai `MAN{orderNo}` value.
  **Format mismatch with ShipX**: ShipX writes `REN -1234`, shioai writes
  `REN -{orderNo}` where orderNo is the shioai order number, not the
  return's own id.
- FedEx-no-note skip logic replicated at line 177-180 (`skipRRow` gate).
- CLIPPER + OE_TRACKING correctly skipped for returns (line 83, 202).

### 3.6 Database

- `V95__carrier_account_ref_return_scope.sql` — per-account
  `DOMESTIC_ONLY` / `DOMESTIC_AND_INTERNATIONAL` / `DISABLED`.
  Enforcement at `CarrierServiceImpl.java:1017-1036`. This is a
  **data-driven upgrade over ShipX** which hardcoded FedEx+UPS+US-only.
- `Client.returnAddress` — configured via `ReturnStep` wizard tab,
  used as the RETURN-mode recipient (auto-clamped at
  `CarrierServiceImpl.java:1042-1058`).
- **No return-only fields**: `orders` table has no `rma_number`,
  `return_reason`, `original_order_no`, `return_label_expires_at`
  columns. No `return_history` view / grid.

## 4. Gaps (feature-by-feature)

Legend: MISSING = not present; PARTIAL = present but limited;
FULL = at parity or better.

| # | Feature | ShipX | shioai | Verdict |
|---|---|---|---|---|
| 1 | Dedicated Returns page in menu | Yes (Shipment → Return Ship) | No (mode toggle inside `/orders/new`) | **MISSING as separate surface** — deliberate design? Question below. |
| 2 | Return History grid | Yes (Shipment History → Return History) | No | **MISSING**. DataHistoryPage has no `isReturn` filter. |
| 3 | Retry-a-failed-return action | Yes (Returnship button) | Uses generic `/orders/new?fixOrderNo=X` fix-flow | **PARTIAL** — works but no return-specific button. |
| 4 | Cancel-return + carrier void | Yes (Cancelreturn) | Generic void flow (`useVoidFailedToast`, batch cancel) | **PARTIAL** — verify NDS void writes `'R'` row's VOID_YN correctly. Likely works via `NdsShipmentOracleWriter` void path but not audited here. |
| 5 | Return-reason picklist | No (ShipX doesn't have it either) | No (`reasonForExport` includes RETURN as a value but that's the customs reason) | **BOTH MISSING** — this is a common feature request in returns workflows (Wrong Item, Defective, No Longer Needed, etc.). Not a regression, but a modern-parity gap. |
| 6 | RMA number field | No | No | **BOTH MISSING**. `PackageDetailDTO.java:71` mentions "operators can bind box → RMA" — the field for it doesn't exist. |
| 7 | Original outbound order linkage (paired return) | No | No | **BOTH MISSING**. No `Order.original_order_no` FK. |
| 8 | Refund tie-in | No | No | **BOTH MISSING**. Not in scope for ShipX or shioai. |
| 9 | Return-label geography | US-only (hardcoded) | **DOMESTIC_ONLY / DOMESTIC_AND_INTL / DISABLED per-account** (V95) | **FULL, better than ShipX** |
| 10 | Carriers supported | FedEx + UPS only | UPS full, FedEx full, DHL basic, Stamps SWSIM/SERA full, USPS_DIRECT MISSING | **PARTIAL** — USPS_DIRECT return-label branch not implemented. |
| 11 | PRINT return label | Yes | Yes | FULL |
| 12 | EMAIL return label (carrier emails customer) | No (ShipX PRINT-only) | Yes (UPS code 9; FedEx equivalent) | **FULL, better than ShipX** |
| 13 | Multi-package returns | N/A (single package per ShipX return) | Yes; UPS uses connector-level split | **FULL, better than ShipX** |
| 14 | Return-label delivery via retailer email fallback | No (support@thbred.com hardcoded) | Yes (`tenant_settings.nds.fallback_notify_email` → platform default) | **FULL, better than ShipX** |
| 15 | NDS `TB_MANUAL_SHIPMENT` `'R'` row | Yes; FedEx-no-note skip; retry ignores skip | Yes (`NdsShipmentOracleWriter:171-180`); FedEx-no-note skip **replicated correctly** | FULL — matches doc'd ShipX behavior including the note gate. |
| 16 | ORDER_NO format in NDS 'R' row | `'REN -<returnId>'` (with space) | `'REN -<shioaiOrderNo>'` (with space) — but shioai orderNo is a MAN sequence, not a separate return id | **PARTIAL** — spelling matches but the id space differs. Downstream NDS consumers keyed off `REN-<returnId>` might not match. |
| 17 | Void return in NDS | `update tb_manual_shipment set VOID_YN='Y'` regardless of Mode | Generic void writeback path; not audited here | UNVERIFIED |
| 18 | Return-label expiry / TTL | No | No | Both missing — carrier default (typically 30-60 days). |
| 19 | Per-client return address config | Yes (client.ShipFrom is the destination) | Yes (`Client.returnAddress` + `returnSameAsShipFrom` flag) | FULL |
| 20 | Menu discoverability | Yes (top-level menu item) | **No** — hidden under `/orders/new` mode toggle | **MISSING** — biggest UX regression. |

## 5. Hardcoded findings

### Backend

| # | File:line | Finding | Fix (which DB table extends?) |
|---|---|---|---|
| H1 | `NdsShipmentOracleWriter.java:173` | `orderNoText = "REN -" + p.orderNo()` — hardcoded prefix + space. | Externalise to `tenant_settings` key `nds.return_order_no_prefix` (default `"REN -"`) or a per-external-system column on `external_system_connection`. |
| H2 | `NdsShipmentOracleWriter.java:178` | Hardcoded `"FEDEX".equalsIgnoreCase(...)` for the note-gated skip. | Move to `carrier_account_ref.return_nds_gate` enum (`ALWAYS`, `ONLY_WITH_NOTE`) so DHL / USPS behavior is configurable if this ever expands to more carriers. |
| H3 | `UpsConnector.java:2232` | `String returnCode = emailFlavour ? "9" : "8"` — hardcoded UPS return codes. | Fine as-is (UPS wire enum, not policy). Keep. |
| H4 | `UpsConnector.java:2290` | `SubjectCode "01"` (`"Return Notification"`) hardcoded. | Fine — carrier constant. |
| H5 | `UpsConnector.java:2288` | Return notification memo string: `"Your return shipping label from " + fromName + "."` and Subject `"Your return label"`. | Move to `label_template` or a `tenant_settings.ups.return_notification_subject` if branding-per-tenant needed. Ponytail: leave until a customer asks. |
| H6 | `CarrierServiceImpl.java:1717-1719` | Return-email guard message hardcoded English. | Fine (matches all other operator error copy in the file). |
| H7 | `CarrierServiceImpl.java:1608` | `directionForLimit = isReturn ? "RETURN" : "FORWARD"` — string literal. | Already a soft-enum; convert to `ShipmentDirection` enum for type safety (small refactor). |
| H8 | `FedExConnector.java:2234` (comment references L1657) | FedEx `pickupType` forced to `CONTACT_FEDEX_TO_SCHEDULE` for returns. | Data-drive via `carrier_account_ref.return_pickup_type` if operators need drop-off returns. |
| H9 | `DhlConnector.java:1223` | `payload.put("pickup", Map.of("isRequested", isReturn))` — auto-schedules DHL Global Return pickup. | Should be operator-controllable (some returns are drop-off at ServicePoint). Add `ManualShipmentRequest.returnPickupRequested: Boolean`, default true. |
| H10 | `UpsConnector.java:2278` | `NdsShipmentLookupService.DEFAULT_NOTIFY_EMAIL` — platform default for the LabelDelivery "from" address. | Already tenant-overridable via `tenant_settings.nds.fallback_notify_email`; document this fact in the runbook. |
| H11 | `UspsDirectConnector.java` | **No `isReturn` branch at all** — a USPS_DIRECT return silently ships as outbound. | Add `isReturn` handling: USPS Ship API accepts `mailClass=PARCEL_SELECT_RETURN_SERVICE` or `PRIORITY_MAIL_RETURN_SERVICE`. Add `usps_direct.return_mail_class` column on `carrier_account_ref` or hardcode per service. |

### Frontend

| # | File:line | Finding | Fix |
|---|---|---|---|
| H12 | `NewShipmentPage.tsx:225` | Mode toggle inside NewShipmentPage — no dedicated route. | Add `/orders/new/return` route that pre-sets mode=RETURN. Keeps the code path but restores menu discoverability. |
| H13 | `NewShipmentPage.tsx:1484` | `setReasonForExport('RETURN')` hardcoded when mode flips. | Fine (customs reason enum). Keep. |
| H14 | `NewShipmentPage.tsx:236` | `returnType` hardcoded `'PRINT' \| 'EMAIL'` union. | Extend to `'PRINT' \| 'EMAIL' \| 'CUSTOMER_UPLOAD_QR'` when FedEx QR-code return is added (Sprint 60+? tracked elsewhere). |
| H15 | `DataHistoryPage.tsx` | No `isReturn` filter chip. | Add filter chip; server-side query already supports `Order.isReturn` on the returned DTO (`OrderController.java:633`). |

## 6. NDS touchpoints

Fully wired in `NdsShipmentOracleWriter.java`:

- `errorMode='R'` on TB_MANUAL_SHIPMENT for return shipments (line 171).
- `orderNoText='REN -<orderNo>'` with the ShipX space (line 172-174).
- FedEx-no-note skip gate replicated (line 177-180) — matches
  `BALShiplist.cs:L2301` behavior documented in
  `deepdive/12b-return-ship.md:47`.
- CLIPPER + OEHEAD skipped correctly for returns (line 83, 202).
- OE_TRACKING still writes (line 141) — verify this matches
  `TB_MANUAL_SHIPMENT`'s expectation; ShipX **does not** write
  OE_TRACKING for returns (deepdive:3 "There are no CLIPPER,
  OE_TRACKING or OE_SHIP writes"). **Potential over-write vs ShipX
  parity** — see gap G-NDS-1 below.

**FE-management gap**: no UI shows *which* rows `NdsShipmentOracleWriter`
attempted / succeeded / failed for returns specifically. The Writeback
Probe endpoint (`docs/nds-writeback-dev-suppression.md` referenced in
memory) works but isn't return-aware.

Hardcoded config in NDS path:

- `NdsShipmentOracleWriter:173` — `"REN -"` literal (H1 above).
- `NdsShipmentOracleWriter:178` — `"FEDEX"` literal for note-gate (H2 above).

## 7. Prioritized fix-plan

Priorities: **P0** = ship blocker / correctness regression; **P1** = closes
functional gap with ShipX; **P2** = polish/parity; **P3** = greenfield
enhancement.

| # | Prio | Item | LoC | DB migrations | Risk | Rationale |
|---|---|---|---|---|---|---|
| F1 | **P0** | USPS_DIRECT return-label branch — reject or implement. Right now it silently ships outbound. | 40 (reject) / 200 (implement) | 1 col on `carrier_account_ref` (return_mail_class) if implementing | LOW (reject) / MED (implement) | Correctness. Operator picks USPS_DIRECT + RETURN → gets a wrong-direction label. Reject with a clear error until implemented. |
| F2 | **P0** | Verify `NdsShipmentOracleWriter` void path writes `VOID_YN='Y'` on the 'R' row when a return is cancelled. Currently unverified. | 20-50 | 0 | LOW (audit + guard test) | ShipX-parity check. Void of a return should not leave the NDS row live. |
| F3 | **P1** | Add `DataHistoryPage` `isReturn` filter chip + backend `?isReturn=true` query param on the orders list. | 60 FE + 20 BE | 0 (index exists via V95 & V49) | LOW | Restores Return History discoverability. Reuses existing grid. |
| F4 | **P1** | Add `/orders/new/return` route that pre-selects mode=RETURN + adds a "Returns" menu entry under Shipments in `Sidebar.tsx`. | 30 FE | 0 | LOW | Fixes the biggest UX regression vs ShipX (returns are invisible in the menu). Zero backend work. |
| F5 | **P1** | Fix NDS OE_TRACKING skip for returns to match ShipX (deepdive:3 explicitly says no OE_TRACKING writes for returns). | 10 | 0 | LOW | Currently line 141 writes OE_TRACKING for both — divergence from ShipX behavior downstream NDS may key off. |
| F6 | **P2** | Data-drive the `NdsShipmentOracleWriter:178` FedEx-no-note gate via `carrier_account_ref` column so DHL/USPS behavior stays configurable if it ever expands. | 40 + 1 mig | 1 col (`return_nds_gate` enum) | LOW | Removes H2 hardcode. |
| F7 | **P2** | Data-drive `"REN -"` prefix into `tenant_settings` (`nds.return_order_no_prefix`). | 30 | 0 (uses existing tenant_settings) | LOW | Removes H1 hardcode; enables tenants running non-ShipX NDS variants. |
| F8 | **P2** | DHL Global Return pickup: add operator toggle for `pickup.isRequested` instead of forcing true on all returns. | 40 (FE + connector + DTO) | 0 | LOW | Some returns are drop-off; H9. |
| F9 | **P2** | Return-specific "Retry return" button on the orders row (currently uses generic Fix flow). | 50 FE | 0 | LOW | Marginal UX polish; the Fix flow already works. Only worth doing if F3 lands and users start filtering by returns. |
| F10 | **P3** | Add `Order.rma_number` (VARCHAR 60) + FE field on return mode; print on label reference slot 2. | 80 + 1 mig | 1 col | LOW | Common modern-returns feature; not in ShipX. |
| F11 | **P3** | Add `Order.return_reason` (VARCHAR 40) + picklist (`WRONG_ITEM`, `DEFECTIVE`, `NO_LONGER_NEEDED`, `SIZE`, `OTHER`) — surface in return mode, roll up in analytics. | 60 + 1 mig | 1 col + optional lookup table | LOW | Not in ShipX; modern parity. |
| F12 | **P3** | Add `Order.original_order_no` FK so returns pair with outbound orders — allows "return of X" lookup + reprint of original CI. | 100 + 1 mig | 1 col + FK | MED | Not in ShipX; would enable refund-tie-in later. |

**Total P0 + P1 estimate: ~200 LoC, 0-1 migrations, small blast radius.**

## Open questions

1. **Intentional collapse or oversight?** The mode-toggle-inside-`/orders/new`
   design collapses the ShipX "Manual Ship" + "Return Ship" split into
   one page. Was this deliberate (fewer surfaces to maintain) or
   accidental (return-history / retry buttons never got built)? The
   `ReturnStep.tsx` wizard tab suggests deliberate, but the missing
   Return History filter suggests accidental.
2. **USPS_DIRECT returns**: is USPS_DIRECT expected to support returns
   at all? USPS Ship API v3 offers `PARCEL_SELECT_RETURN_SERVICE` +
   `PRIORITY_MAIL_RETURN_SERVICE` mailClass values. Operator direction
   needed on F1 (reject vs implement).
3. **REN- prefix in NDS**: does downstream NDS (Oracle side) key off
   `REN-<returnId>` or `REN-<manualOrderNo>`? The shioai writes
   `REN -{shioaiOrderNo}` which uses the wrong id space vs a canonical
   ShipX record. If NDS side has a JOIN somewhere,
   `NdsShipmentOracleWriter:173` needs a dedicated return-sequence.
4. **OE_TRACKING for returns**: shioai writes it (line 141) but the
   ShipX deep dive at deepdive:3 explicitly says "no CLIPPER, OE_TRACKING
   or OE_SHIP writes" for returns. Is the shioai behavior a deliberate
   upgrade (returns should be tracked in NDS OE_TRACKING) or a bug?
   Needs ops sign-off before F5.
5. **DHL return-service product code**: `DhlConnector.java:1216` uses
   `firstNonBlank(request.getServiceType(), "P")` — same product code
   as outbound. DHL Express has a dedicated Global Return
   `productCode=8` (docs). Is service-code selection expected to be
   operator-driven from `/settings/shipping-services`, or should returns
   force the return product code? Not audited today.
6. **Return-history retention**: ShipX keeps returns in
   `SPX_tblReturnDetails` forever. shioai's `orders` retention policy
   currently governs this. Any return-specific retention rule?
7. **Void-a-return in NDS**: is `VOID_YN='Y'` on the `'R'` row exercised
   anywhere in the shioai void path? Not found in this audit; a
   confirmatory grep + IT probably closes it in an hour.

---

**Files cited (shioai, absolute)**:
- `D:/GIT-REPOS/thb-shioai/multiship-react/src/components/NewShipmentPage.tsx`
- `D:/GIT-REPOS/thb-shioai/multiship-react/src/components/ClientEditorSteps/ReturnStep.tsx`
- `D:/GIT-REPOS/thb-shioai/multiship-react/src/components/DataHistoryPage.tsx`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/dto/ManualShipmentRequest.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/dto/ShipmentRequestDTO.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/dto/external/ExternalShipmentRequest.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/model/Order.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/model/CarrierAccountRef.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/CarrierServiceImpl.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/ShipmentSplitter.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/carriers/UpsConnector.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/carriers/FedExConnector.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/carriers/StampsConnector.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/carriers/DhlConnector.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/carriers/UspsDirectConnector.java` (absence of isReturn)
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/ndsshipment/NdsShipmentOracleWriter.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/resources/db/migration/V95__carrier_account_ref_return_scope.sql`

**ShipX docs cited**:
- `D:/GIT-REPOS/ShipX/docs/handover/source/part-C.md:296-368` (§12 Manual + Return Ship)
- `D:/GIT-REPOS/ShipX/docs/handover/source/deepdive/12b-return-ship.md:1-93`
- `D:/GIT-REPOS/ShipX/docs/handover/source/appendix-B.md:1819-1864` (B.32 spx_proshipmentreturn)
- `D:/GIT-REPOS/ShipX/docs/handover/source/appendix-B.md:2240-2271` (B.42 spx_updatereturn)
- `D:/GIT-REPOS/ShipX/docs/handover/source/combined.md:6366-6488` (Query deep dive: Shipment History and Return History)
