# /orders/new vs ShipX Quick Ship — gap-analysis & fix-plan

Read-only investigation. Compares ShipX's **Quick Ship / Quick Ship With Tax** flow (legacy .NET, doc: `docs/handover/source/part-B.md §8`, `deepdive/08a-quickship.md`, `deepdive/08b-quickship-tax.md`) to the shioai `/orders/new` (Manual Shipment) flow.

Every code claim carries `file:line`. Every ShipX claim carries `doc-file:line`.

---

## 1. Overview

Quick Ship is ShipX's "scan a packed box, get a label" flow. Five things happen per scan:

- Operator scans `.X<containerId>` (single container) or `.Y<batchId>` (billable batch).
- ShipX asks NDS which client owns the container/batch, then reads the order from that client's schema (recipient, ship method, package dims, notify emails, invoice items).
- Screen prefills carrier/service/box from the NDS ship method + the client's carrier grid.
- Operator clicks **Create Shipment**; label is purchased.
- If carrier Mode = Live, ShipX writes **CLIPPER.TRACK/FREIGHT**, an optional `TB_MANUAL_SHIPMENT` "Q" note row, and (for USPS with no CLIPPER row) OE_TRACKING via `PROC_OE.INSERT_OE_TRACKING`.

Quick Ship With Tax adds two fields — sender/recipient tax IDs — that are threaded into the FedEx/UPS request only (USPS ignores; FedEx multi-piece + FSM drop them; `.Y` never carries them).

---

## 2. ShipX behavior — extracted facts

### 2.1 Entry points
- **Quick Ship**: `ShipmentController.FedExShipment` (`ShipmentController.cs:L736-L904`); Saturday check at `:L740`, HLD at `:L760` (`part-B.md:238-247`).
- **Quick Ship With Tax**: `ShipmentController.FedExTaxShipment` (`ShipmentController.cs:L1043-L1212`) — copy with `FromTaxId`/`ToTaxId` added (`08b-quickship-tax.md:10`).
- **BackOrder** (bulk): `BackOrderShipment` (`ShipmentController.cs:L569`) — comma-separated .X + .Y; NO HLD / Saturday checks (`part-B.md:294-295`).

### 2.2 NDS reads (per scan)

| Step | Login | Table / view | Filter | Source |
|---|---|---|---|---|
| 1. Container → owner | PRODUCTION | `TB_SHIP_CONTAINER` | `CONTAINER_ID` | `08a-quickship.md:7-13`, `DALShiplist.cs:L2235-L2253` |
| 2. Order header + recipient + ship method + first container | CLIENT | `OEHEAD` ⋈ `OE_SEND_TO` ⋈ `TB_SHIP_CONTAINER` ⋈ `OE_SHIP_CONTAINER` ⋈ `SHIPVIA` | `TENANT_ID, CONTAINER_ID` | `08a-quickship.md:19-41`, `DALShiplist.cs:L2178` |
| 3. Multi-container fan-out (FedEx/UPS) | CLIENT | `TB_SHIP_CONTAINER` | `Order_NO, Order_Suffix, TENANT_ID` | `08a-quickship.md:66-70`, `DALShiplist.cs:L2096` |
| 4. International CI items | CLIENT | `TB_CLIPPER_ITEM_DETL` ⋈ `TB_SHIP_CONTAINER` | `container_id, cont.order_suffix, tenant` | `08a-quickship.md:126-139`, `DALFeddExShipment.cs:L965` |
| 5. USPS/UPS CI items | **PRODUCTION** | `TB_CLIPPER_ITEM_DETL` | `order_no, order_suffix, tenant_id` | `08a-quickship.md:141-152`, `DALUPSShipment.cs:L168-L184` |
| 6. Package count + total weight (invoice) | CLIENT | `TB_SHIP_CONTAINER` | `tenant, order_no, order_suffix` | `08a-quickship.md:174-177`, `DALShiplist.cs:L2517` |
| 7. Batch resolution (.Y) | PRODUCTION | `TB_BILLABLE_CONTAINERS` | `BATCH_ID [+FF_SCHEMA]` | `part-B.md:275`, `DALShiplist.cs:L2114/L2146` |

### 2.3 NDS writes (Mode=Live only)

| Op | Login | Table | Columns | Source |
|---|---|---|---|---|
| CLIPPER update, per container | CLIENT | `CLIPPER` | `TRACK`, `FREIGHT` | `08a-quickship.md:186-192`, `DALShiplist.cs:L2735-L2793` |
| .Y batch CLIPPER update | CLIENT | `CLIPPER` | `TRACK`, `FREIGHT` (WHERE `ORDER_NO IN (…)`) | `08a-quickship.md:194`, `DALShiplist.cs:L2673-L2731` |
| Note "Q" row (only when typed) | **PRODUCTION** | `TB_MANUAL_SHIPMENT` | 20 cols incl. `ERROR_MODE='Q'` | `08a-quickship.md:198-205`, `DALShiplist.cs:L2587-L2628` |
| USPS-no-CLIPPER-row fallback | CLIENT | via `<client>.PROC_OE.INSERT_OE_TRACKING` | `SYSDATE, 'CLIPPER', '450', tracking` | `08a-quickship.md:209-211`, `DALShiplist.cs:L2897-L2937` |
| STD replacement (BackOrder .X only) | CLIENT | `OEHEAD.SHIPVIA_CD` | actual method | `part-B.md:293`, `DALShiplist.cs:L2942` |

### 2.4 Business rules (ShipX Quick Ship)

| Rule | Behavior | Source |
|---|---|---|
| HLD ship method → block | Stop with "Order is on Hold." | `part-B.md:238` |
| Saturday delivery bug | FSD/USD always refused (dead check) | `part-B.md:240` |
| Phone normalize | Strip spaces; if !=10-15 chars → `+1 616 772 3513` | `part-B.md:241` |
| Special chars | Strip from SHIP_NAME / SHIP_ATTN; ADDR1 keeps `a-zA-Z0-9 -/.,#` | `part-B.md:242` |
| Missing email | Substitute `support@thbred.com` | `part-B.md:243` |
| Wrong client | Container must belong to picked client OR THB | `part-B.md:244` |
| Third-party account refused | Retry on default account after operator confirm | `part-B.md:245` |
| Label prints | Employee number, order number, container number on FedEx/UPS labels | `part-B.md:246` |
| STD ship method (BackOrder .X) | Replace with client's standard on OEHEAD after ship | `part-B.md:293`, `08a-quickship.md:307` |
| FedEx/UPS multi-container fan-out | Scanning one box ships ALL containers on the order as MPS | `08a-quickship.md:64-73`, `232` |
| USPS freight lost | `SetTrackingQ` called without freight → CLIPPER.FREIGHT='0' | `08a-quickship.md:218, 237` |
| Tax IDs (QS With Tax) | FedEx: only single-label REST + non-US. UPS: all non-US. USPS: never. FSM: never. .Y: never. | `08b-quickship-tax.md:16-20, 25-28` |

### 2.5 Postgres writes (ShipX)
- `SPX_tblShipmentDetails` via `spx_proshipment` — one row per package, incl. `ToTaxId/FromTaxId` (`appendix-B.md:35, L1449`; `08a-quickship.md:220-227`).
- `SPX_tblCommercialInvoice` — direct INSERT per line (`08a-quickship.md:180`).
- `SPX_Shipment_API_Log` — carrier request/response JSON via `spx_upsert_shipment_api_log`.

---

## 3. shioai counterpart at /orders/new

### 3.1 FE component structure
- Root page: `multiship-react/src/components/NewShipmentPage.tsx:1` (4860 LoC).
- Scan input UI: `NewShipmentPage.tsx:3032-3056`; token parse at `1382-1402`.
- API client: `multiship-react/src/api/ndsShipmentService.ts:135-143` — `GET /api/v1/manual-shipment/nds-lookup?scan=…&clientCode=…`.
- Prefill applier: `NewShipmentPage.tsx:1302-1375` (client → recipient → carrier/service → packages → CI items → notify).
- Multi-scan queue: `NewShipmentPage.tsx:1383-1443` — comma/space split, dedup by orderNo, first prefill applied, remainder as click-chips.
- Sender-fallback effect (uses shipper block when NDS phone/email defaulted): stashed via `senderFallbackPendingRef` at `1368-1371`.

### 3.2 Backend controllers
- Prefill lookup: `NdsShipmentLookupController.java:49` on `POST /api/v1/manual-shipment/nds-lookup`. Status contract: 200 OK/WARNING/BLOCKED, 404 not-found, 422 unparseable, 503 NDS down.
- Manual label buy: `OrderController.java:1930` on `POST /orders/manual-label` — receives `ManualShipmentRequest` (`dto/ManualShipmentRequest.java:18`). Idempotency via `Idempotency-Key` header.
- Fix-and-regenerate: `OrderController.java:1962` on `POST /orders/{orderNo}/regenerate`.
- Admin NDS schema snapshot: `NdsDebugController.java:47` on `POST /api/v1/admin/nds-debug/describe` (ADMIN only, hardcoded table list).

### 3.3 Backend services
- Prefill orchestrator: `NdsShipmentLookupService.java:50` — hold gate (line 464-472), STD resolver hookup (line 295-319), sanitizer/normalizer/international detection (line 348-442).
- Repository (Oracle reads): `NdsShipmentLookupRepository.java:42` — two-login pattern via `NdsTemplates.production()` / `.forClient()`.
- Templates: `NdsTemplates.java:38` — thin JDBC dispensor from S1 external-systems registry, well-known connection name `nds-default`.
- Scan parser: `NdsScanValueParser.java:35` — `.X` / `.Y` prefix; rejects everything else with 422.
- Sanitizer: `NdsAddressSanitizer.java:24` — regex allow-list `[^A-Za-z0-9 .,\-#/&']` (NOTE: doc comment at line 17-19 admits the exact ShipX whitelist is unknown, so this is a guess).
- Phone normalizer: `NdsPhoneNormalizer.java:33` — 10-15 char check; fallback `+1 616 772 3513` (line 18) unless tenant override key `nds.fallback_phone` set.
- STD resolver: `StdShipMethodResolver.java:43` — client's `shipvia_cd='STD'` row from `ship_via_mapping` picks the target service + reverse-lookup ERP code for writeback.
- Post-label writeback: `ExternalSystemWritebackDispatcher.java:52` — async fire-and-forget; per-tenant setting `writebackConnection` → else `nds-default` (line 178-189).
- Oracle writer: `NdsShipmentOracleWriter.java:48` — 4-table update (CLIPPER, OE_TRACKING, TB_MANUAL_SHIPMENT, OEHEAD).

### 3.4 Connector routing (label buy)
- Dispatch: `CarrierServiceImpl.generateManualLabel` (`CarrierServiceImpl.java:962`).
- Carrier connectors: `service/carriers/FedExConnector.java`, `UpsConnector.java`, `StampsConnector.java`, `UspsDirectConnector.java`, `DhlConnector.java`.
- USPS_DIRECT queue routing: `CarrierServiceImpl.java:975-999` — routes to 55/hr queue when `USPS_PROVIDER=USPS_DIRECT` AND order routes to USPS.
- Multi-package split: builder at `CarrierServiceImpl.buildShipmentRequest` (`:3495`, `:3639`) — one `ShipmentRequestDTO` per multi-box request; splitter in `ShipmentSplitter`.

---

## 4. Gaps — features present in ShipX, missing / different in shioai

Legend: **[G]** = gap (ShipX feature missing), **[D]** = different behavior, **[U]** = shioai upgrade over ShipX.

### 4.1 NDS read path

| # | Item | Status | ShipX | shioai | Location |
|---|---|---|---|---|---|
| G01 | International CI items query | **[G]** | Reads `TB_CLIPPER_ITEM_DETL` (real table) with client login | Reads `OE_INTL_ITEMS` — **table does not exist in real NDS**, only in test fixture `nds-oracle-init.sql:82-94` | `NdsShipmentLookupRepository.java:245-273`; ShipX `08a-quickship.md:126-139` |
| G02 | Order-header extra fields (SHIPPED_FLAG, HOLD_FLAG, CUST_PO, DEPARTMENT, INCOTERMS, CURRENCY_CD) | **[G]** | Read where present; used | Explicitly set to `null` in projection with TBD comments; block gates fire on `null` value only | `NdsShipmentLookupRepository.java:142-149` |
| G03 | Multi-container fan-out from single scan | **[G]** | Scanning one `.X` returns the order + auto-loads all sibling containers for FedEx/UPS MPS | Loads sibling containers into `packages[]` prefill on FE but does NOT auto-add to submitted request; operator sees them, can remove | `NdsShipmentLookupService.buildDirectPackages` `:134`; FE prefill `NewShipmentPage.tsx:1331-1347` |
| G04 | `TB_SHIP_CONTAINER` billable weight (real ShipX column) | **[D]** | Reads `TB_SHIP_CONTAINER.WEIGHT` (billable weight) | Reads `OE_SHIP_CONTAINER.GROSS_WT`, marked as such in `weightSource` | `NdsShipmentLookupRepository.java:169-197`; ShipX `08a-quickship.md:106-109` |
| G05 | .Y batch anchor-address rule | **[U]** | Uses the batch's first row silently, no cross-order address check | Uses lowest CONTAINER_NO's first order; documented at `NdsShipmentLookupService.java:41-45` — silently mirrors ShipX (does NOT diff addresses) | `NdsShipmentLookupService.java:186-204` |
| G06 | Third-party account refused → default-account retry prompt | **[G]** | Refused → prompts "Do you want to proceed with default account?" → retries | No prompt/retry flow — request rejects | ShipX `part-B.md:245`; shioai `CarrierServiceImpl.java:1143-1159` |
| G07 | Wrong-client THB override | **[G]** | Chosen client must own container OR be THB (bypass for master account) | Strict match only via `assertClientCodeMatches` at `NdsShipmentLookupService.java:510-519` | ShipX `part-B.md:244` |
| G08 | OE_SEND_TO copy-to email | **[G]** | Reads `SEND_TO, COPY_TO` | Only reads `SEND_TO` (no COPY_TO column selected) | `NdsShipmentLookupRepository.java:222`; DTO carries `copyTo` but always `null` (`NdsShipmentPrefill.java:107`) |
| G09 | OEHEAD.LETTER_OF_CREDIT | **[G]** | Read for CI export | Stubbed as `null` (`NdsShipmentPrefill.International.letterOfCredit` never set) | `NdsShipmentLookupService.java:441`; ShipX `08a-quickship.md:162` |
| G10 | Unit cost + customs_decl_value min-1 clamp | **[D]** | ShipX clamps `< 1 or NULL` → `1` (avoids carrier rejection on $0 items) | shioai does NOT clamp; passes NDS value through raw | `NdsShipmentLookupService.java:427-428`; ShipX `08a-quickship.md:130-134` |

### 4.2 NDS write path

| # | Item | Status | ShipX | shioai | Location |
|---|---|---|---|---|---|
| W01 | CLIPPER column names | **[D] — likely broken** | `UPDATE CLIPPER SET clip.track=…, clip.freight=…` | `UPDATE CLIPPER SET TRACKING_NUMBER=…, FREIGHT_AMOUNT=…` — different column names, no confirmation these exist | `NdsShipmentOracleWriter.java:97-101`; ShipX `08a-quickship.md:186-192` |
| W02 | CLIPPER composite-key CONTAINER_NO | **[D]** | Uses NDS's own row-level CONTAINER_NO from `GetMultiContainerByOrder` result | Uses FE `pkg.sequence` (1..N) as CONTAINER_NO. Comment at line 91-93 flags this: `ponytail: package.sequence used as CONTAINER_NO; if NDS keys off the raw container_no from prefill, promote that field onto WritebackPackagePayload.` | `NdsShipmentOracleWriter.java:107` |
| W03 | OE_TRACKING columns | **[D]** | ShipX calls `PROC_OE.INSERT_OE_TRACKING` only for USPS-no-CLIPPER-row fallback | shioai ALSO does a direct UPDATE against OE_TRACKING columns (`tracking_no`, `ship_date`, `ship_status`, `carrier_cd`, `service_cd`, `freight_amt`) as its primary write. **These columns are unconfirmed** — file comment at `:396` says "unchanged from V89; pending DDL confirm" | `NdsShipmentOracleWriter.java:396-406, 140-158` |
| W04 | Duplicate CLIPPER writes for UPS (scanned container written twice) | **[U]** | Bug in ShipX (`08a-quickship.md:217`) — scanned container gets tracking twice | shioai does per-package single write; no double-write | `NdsShipmentOracleWriter.java:87-114` |
| W05 | Freight duplicated across MPS packages | **[D]** | Whole-shipment freight written to every package (`08a-quickship.md:234`) | shioai writes single `p.freightAmount` to every package too — same behavior | `NdsShipmentOracleWriter.java:97-108` |
| W06 | ERROR_MODE=Q note row | **[U]** | Only inserted when note typed; unescaped concatenation bug (`08a-quickship.md:238`) | Parameterized INSERT; still gated on `note != blank` | `NdsShipmentOracleWriter.java:189-196, 244-284` |
| W07 | ERROR_MODE=M row (writeback marker) | **[D]** | Not in ShipX Quick Ship — only 'Q' fires | shioai fires 'M' (or 'R' for returns) on every writeback (`:171-186`) | `NdsShipmentOracleWriter.java:171-186` |
| W08 | STD replacement writeback (BackOrder path) | **[U]** | ShipX writes `OEHEAD.SHIPVIA_CD` only on BackOrder .X, not on Quick Ship (`part-B.md:293`) | shioai writes on Quick Ship equivalent too (once STD resolver fired). Read-before-write to skip no-op (`:209-217`) | `NdsShipmentOracleWriter.java:199-230` |
| W09 | USPS-no-CLIPPER fallback | **[U]** | ShipX: `SetTrackingOE_TRACK` only when `ContainerId == null` (dead code — always non-null; see `08a-quickship.md:236`) | shioai: fires when CLIPPER UPDATE hits zero rows AND carrier is USPS variant (`:120-136`) | `NdsShipmentOracleWriter.java:120-136` |
| W10 | PROC_OE.INSERT_OE_TRACKING param names | **[D]** | ShipX uses positional-bind (works in Oracle) | shioai uses named `P_ORDER_NO/P_ORDER_SUFFIX/P_SOURCE/P_CODE/P_TRACKING` — comment at `:575-577` admits: "parameter names inferred (P_ORDER_NO etc.) from Oracle convention because we can't reach the live procedure metadata from dev. If the first prod call fails … ops updates the names here." | `NdsShipmentOracleWriter.java:579-599` |
| W11 | Async writeback | **[U]** | ShipX writes inline before printing; if TB_MANUAL_SHIPMENT insert fails, label never prints | shioai writes @Async fire-and-forget after label — label always ships even if NDS is down | `ExternalSystemWritebackDispatcher.java:72-114` |
| W12 | Per-connection writeback flags (tracking/shipdate/status/carrier/service/freight) | **[U]** | Not in ShipX | shioai has 6-way FE-managed toggle per connection | V90/V93; `ExternalSystemWritebackDispatcher.java:191-243` |
| W13 | Per-source gates (MANUAL/BULK/API/WMS/DTC) | **[U]** | Not in ShipX | FE-managed per-connection booleans | V90/V93; `:214-224` |

### 4.3 UX / business rules

| # | Item | Status | Location |
|---|---|---|---|
| B01 | HLD block | **[U]** — data-driven | shioai: `client_shipvia_code_map.is_hold` + legacy 'HLD' literal fallback (`NdsShipmentLookupService.java:464-472`, V100). ShipX: hardcoded 'HLD' literal (`part-B.md:238`). |
| B02 | Saturday-delivery block (FSD/USD) | **[G]** — deliberate | ShipX had a broken always-refused check (`part-B.md:240`); shioai has no equivalent gate. |
| B03 | Multi-scan queue (comma/space list) | **[U]** | ShipX: BackOrder screen only; shioai: same screen (`NewShipmentPage.tsx:1382-1443`) via chip picker. |
| B04 | Client-code operator gate | **[U]** | shioai: `assertClientCodeMatches` throws 422 with both codes named (`NdsShipmentLookupService.java:510-519`). ShipX: just refuses. |
| B05 | Already-shipped block | **[G] — currently non-functional** | Code exists (`NdsShipmentLookupService.java:332-337`) but `h.shippedFlag()` is hardcoded `null` in projection (`NdsShipmentLookupRepository.java:144`). Gate never fires. |
| B06 | Ops fallback for missing weight (0/null) | **[U]** | shioai: `nds.default_weight_lb` + `nds.on_missing_weight=BLOCK|WARN` (`:485-502`). ShipX had no equivalent — 0 weight silently shipped. |
| B07 | Sanitizer whitelist | **[D] — unverified** | shioai regex `[^A-Za-z0-9 .,\-#/&']` (`NdsAddressSanitizer.java:24`) — self-admits at line 17-19 "ShipX's exact whitelist is unknown; adjust if carrier rejects". |
| B08 | Manual invoice printer | **[G]** | ShipX Quick Ship page has label + invoice printer pickers. shioai's Manual Shipment page: no separate CI printer picker on this page (invoice PDF is a download). |
| B09 | Container ID printed on label as reference | **[D]** | ShipX prints EmpID + orderNo + containerId on labels (`part-B.md:246`). shioai prints `MAN{orderNo}`/`wmsExternalId` PO ref via `LabelPO/DEPT` track (per memory doc; not containerId). |
| B10 | Third-party account refused retry prompt | **[G]** | See G06 above. Missing recovery UX. |

---

## 5. Hardcoded findings — business rules / reference data in code

Rule: only rules that SHOULD be data / FE-managed. Retry counts, buffer sizes, etc. excluded.

| # | File:line | Constant | What it should be | Fix (one line) |
|---|---|---|---|---|
| H01 | `NdsPhoneNormalizer.java:18` | `DEFAULT_PHONE = "+1 616 772 3513"` (Thoroughbred's number) | Per-tenant fallback (partially wired via `nds.fallback_phone` at `:33-35`) but hardcoded compile-time default remains | Delete platform default; require tenant to set `nds.fallback_phone`; block prefill if missing rather than substituting THB's phone |
| H02 | `NdsShipmentLookupService.java:56` | `DEFAULT_NOTIFY_EMAIL = "support@thbred.com"` | Per-tenant (`nds.fallback_notify_email` wired at `:406-411`) but hardcoded default remains | Same treatment as H01 — remove platform default, force per-tenant |
| H03 | `NdsShipmentLookupService.java:53` | `US_TERRITORY_CODES = Set.of("PR","VI","GU","AS","MP","UM")` | Country-config table (per-tenant / global) | Move to `country` table with `is_us_territory` flag |
| H04 | `NdsAddressSanitizer.java:24` | Regex `[^A-Za-z0-9 .,\-#/&']` — chars allowed in name/attn/addr1 | Per-tenant carrier-profile setting (some carriers accept more) | Extract to `tenant_settings` key `nds.sanitizer_allowlist_regex` |
| H05 | `NdsPhoneNormalizer.java:42` | `10 ≤ len ≤ 15` — E.164 range | Country-config (US wants 10, EU wants up to 15) | Table `country.min_phone_digits` / `max_phone_digits` |
| H06 | `NdsShipmentOracleWriter.java:604-607` | `switch("USPS","USPS_DIRECT","STAMPS_COM") → true` for USPS-family detection (G2 fallback trigger) | `carrier` table `carrier_family` column | Add `carrier.family = 'USPS'\|'FEDEX'\|'UPS'\|'DHL'` |
| H07 | `NdsShipmentOracleWriter.java:127, 452, 504` | `"CLIPPER"`, `"BATCHSHIP"`, `"450"` — hardcoded PROC_OE.INSERT_OE_TRACKING source/code markers | Per-connection config (each NDS install could label its tracking source differently) | `external_system_connection.nds_tracking_source_manual` / `.nds_tracking_source_dtc` / `.nds_tracking_status_code` columns |
| H08 | `NdsShipmentOracleWriter.java:171-179` | `errorMode = isReturn ? "R" : "M"`; FedEx-returns note gate | Per-connection ERROR_MODE mapping | `external_system_connection.nds_error_mode_shipment` / `.nds_error_mode_return` |
| H09 | `NdsShipmentOracleWriter.java:452` | `TRACK_CD = "BATCHSHIP"` — DTC clear source | Per-connection config | As per H07 |
| H10 | `NdsShipmentOracleWriter.java:258-273` | 20 x `clamp(value, 50)` and `clamp(note, 255)` — TB_MANUAL_SHIPMENT column widths | Reflect Oracle column widths from `USER_TAB_COLUMNS` at boot; cache | Discover on connection health-check; per-connection column-width map (Oracle can differ per install) |
| H11 | `NdsShipmentOracleWriter.java:243-283` | Full column list of `TB_MANUAL_SHIPMENT INSERT` and `TB_MANUAL_SHIPMENT VOID_YN='Y'` | Same schema-drift risk as H10 | Discover columns at boot; fail-fast + WARN on mismatch |
| H12 | `NdsShipmentLookupRepository.java:53-60` | SQL text `SELECT TENANT_ID, ORDER_NO, ORDER_SUFFIX FROM TB_SHIP_CONTAINER WHERE CONTAINER_ID = :containerId` | Query catalog per connection type | Not high-value alone, but grouping H12-H17 into a query-catalog resource per connection lets a customer with different NDS forks override |
| H13 | `NdsShipmentLookupRepository.java:101-153` | Full OEHEAD projection SQL | Per-connection query template | See H12 |
| H14 | `NdsShipmentLookupRepository.java:169-197` | OE_SHIP_CONTAINER SQL — column names `GROSS_WT`, `LENGTH`, `WIDTH`, `HEIGHT`, `CONTAINER_TYPE` | Per-connection | See H12 |
| H15 | `NdsShipmentLookupRepository.java:245-273` | `OE_INTL_ITEMS` — invented table (see G01) | Change to `TB_CLIPPER_ITEM_DETL` per ShipX contract | Rewrite query to match real NDS; column names to per-connection template |
| H16 | `NdsShipmentLookupRepository.java:359-393` | `oe_ship_container` ⋈ `tb_billable_containers` billable-batch join with XMLAGG for CSV | Per-connection | See H12; XMLAGG is Oracle-version-specific too |
| H17 | `NdsShipmentOracleWriter.java:96-101, 213-217, 447-471, 511-520` | `UPDATE CLIPPER SET TRACKING_NUMBER … FREIGHT_AMOUNT …`, `UPDATE OEHEAD SET SHIPVIA_CD`, `DELETE FROM OE_TRACKING WHERE TRACK_CD='BATCHSHIP'`, `UPDATE OE_SHIP_CONTAINER SET CONTAINER_ID = NULL` | Column names in H17 differ from what the ShipX doc says (see W01). Per-connection query catalog | Rewrite queries; move to `external_system_query_template` table keyed by (connection_id, op_name) |
| H18 | `StdShipMethodResolver.java:45` | `STD = "STD"` — the literal that triggers STD replacement | Per-connection erp-code | `external_system_connection.std_shipvia_marker` — an NDS fork could use different literal |
| H19 | `NdsShipmentLookupService.java:466` | `"HLD"` last-resort hold check | Per-connection erp-code (data-driven path via `client_shipvia_code_map.is_hold` already exists) | Delete the literal; require operators to add HLD to the map at first-use (already partially done in V100 seed) |
| H20 | `NdsShipmentLookupService.java:389` | Weight-defaulted-BLOCK derived from tenant setting `nds.on_missing_weight` (BLOCK vs implicit WARN) | Already data-driven — no change | none — noted as good pattern |
| H21 | `NewShipmentPage.tsx:1388` | `.map((t) => (/^\.[XY]/i.test(t) ? t : \`.X${t}\`))` — bare tokens default to `.X` | ShipX behavior is scanned barcodes always have the prefix; the `.X` default is a shioai UX layer. Fine but should be a settings toggle | `tenant_settings.nds.scan_default_scope = 'X'\|'Y'\|null` |
| H22 | `NdsShipmentLookupController.java:46` | `/api/v1/manual-shipment/nds-lookup` path is hardcoded, endpoint always active | Feature-flag / per-tenant enablement (some tenants have no NDS wired) | Return 404 when no NDS connection resolves for the caller's tenant (rather than 503 after DB call) |

### Notable data-driven wins already shipped (not gaps)
- `client_shipvia_code_map.is_hold` (V100) — replaces hardcoded HLD (partially).
- `tenant_settings` KV store — used for `nds.fallback_phone`, `nds.fallback_notify_email`, `nds.default_weight_lb`, `nds.on_missing_weight` (`NdsShipmentLookupService.java:62-65`).
- `external_system_connection` writeback flag matrix (V89/V90/V93) — 6 column flags + 5 source flags + 2 channel flags — all FE-managed.
- `PROD/DEV` env split (V91 per memory doc) — per-connection env.
- `ship_via_mapping` + `client_shipvia_code_map` — STD replacement + ship method routing.

---

## 6. NDS touchpoints

### 6.1 What Quick Ship reads/writes to NDS

- **Read**: 7 queries described in section 2.2. All parameterised via `NamedParameterJdbcTemplate` (shioai) vs ShipX's string concatenation (`08a-quickship.md:244`).
- **Write** (V89 real writer): CLIPPER (per-container UPDATE), OE_TRACKING (per-order UPDATE — column set unconfirmed), TB_MANUAL_SHIPMENT (M/R/Q rows), OEHEAD (STD replacement), OE_SHIP_CONTAINER (DTC only).
- **Async**: shioai writeback fires @Async post-label (`ExternalSystemWritebackDispatcher.java:72`); ShipX writes inline (`08a-quickship.md:183`).

### 6.2 How shioai handles it today
- Two-login pattern (PRODUCTION for cross-client lookup, CLIENT per tenant) via `NdsTemplates` + `NdsOracleConnector`.
- Pool-per-connection + pool-per-client cached in `NdsOracleConnector:80-86`; idle-pool sweep at `:221-245`.
- Client-code regex validation before pool build (`:325-343`) — defense against poisoned scan input.
- Per-connection secrets in `external_system_secret` (V77) via `ConnectorSecretAccess`.

### 6.3 Hardcoded config leaks in NDS Oracle connector (PR #731)

| Ref | File:line | Issue |
|---|---|---|
| N01 | `V78__external_systems_seed_nds_default.sql:38` | Seed values `host=192.168.3.8`, `port=1521`, `serviceName=tb10g`, `productionUsername=production`, `serverMode=DEDICATED`, `clientCodePattern=^[A-Z0-9-]{3,12}$`. Row `active=FALSE` so admin must configure via FE. **Good pattern** — only issue is the seeded host is a Thoroughbred-internal IP. On a fresh install for a non-THB customer, the admin sees THB's IP as the "default" placeholder. |
| N02 | `NdsOracleConnector.java:304` | Fallback client login rule: `username = clientCode`, `password = clientCode`. Overridable via `external_system_client_login_override` table (per row), but the *rule itself* is compiled in. A fork of NDS with `password = clientCode + '_pw'` needs a code change. → Extract to `external_system_connection.client_login_rule` (JSONPath or template string). |
| N03 | `NdsOracleConnector.java:347` | `driverClassName = "oracle.jdbc.driver.OracleDriver"` — connector systemType is `NDS_ORACLE` so this is fine, but adds coupling for a `NDS_MSSQL` sibling later. Not high-value to fix. |
| N04 | `NdsOracleConnector.java:356` | `connectionTestQuery = "SELECT 1 FROM DUAL"` — Oracle-specific. Fine while connector systemType is NDS_ORACLE. |
| N05 | `NdsTemplates.java:41` | `NDS_CONNECTION = "nds-default"` — hardcoded well-known name. Callers cannot pick a specific NDS connection when a tenant has two (rare but possible). → Add `NdsTemplates.forConnection(name)` for future multi-NDS. |
| N06 | `ExternalSystemWritebackDispatcher.java:58` | `DEFAULT_CONNECTION_NAME = "nds-default"` — see N05. Same concern. |
| N07 | `NdsDebugController.java:53-58` | Hardcoded CLIENT_TABLES / PRODUCTION_TABLES lists. Not FE-managed — admin cannot describe an unknown table without code change. Low-value; this is a diagnostic tool with deletion criteria at `:37-40`. |
| N08 | `NdsShipmentOracleWriter.java:583-585` | Hardcoded `withCatalogName("PROC_OE").withProcedureName("INSERT_OE_TRACKING")` — per-connection procedure catalog + name would let a fork ship. → `external_system_connection.nds_tracking_proc_catalog` / `.nds_tracking_proc_name`. |

### 6.4 What should be FE-managed

Add a **Settings → External Systems → NDS Query Templates** tab exposing:
- Per-connection SQL templates for each read/write op (grouped by op name, editable, versioned).
- Per-connection column-name overrides for TB_MANUAL_SHIPMENT / CLIPPER / OE_TRACKING (already possible in principle via query template, but a lighter "just override the column names" UI would suit ops).
- Per-connection tracking-marker constants: `nds_tracking_source_manual` (default `CLIPPER`), `_dtc` (default `BATCHSHIP`), `nds_tracking_status_code` (default `450`), `std_shipvia_marker` (default `STD`).
- Per-connection PROC_OE catalog+name.

---

## 7. Prioritized fix-plan

| P | # | Item | LoC est | DB migrations | Risk | Rationale |
|---|---|---|---|---|---|---|
| **P0** | F01 | Fix CLIPPER column names to match real NDS (`TRACK`, `FREIGHT`, not `TRACKING_NUMBER`, `FREIGHT_AMOUNT`) — see W01/H17 | 40 | none (schema is Oracle, not ours) | high | Silent-fail today: every prod write goes to non-existent columns; writeback ACK reports OK because per-package try/catch swallows the error. Nothing is landing in CLIPPER. |
| **P0** | F02 | Fix international-items query to read `TB_CLIPPER_ITEM_DETL` (real ShipX contract) instead of invented `OE_INTL_ITEMS` — see G01/H15 | 60 | 0 (delete OE_INTL_ITEMS from test init) | high | International CI on prefill silently returns 0 rows on real NDS. FedEx/UPS int'l labels ship without CI, get customs holds. |
| **P0** | F03 | Confirm/fix OE_TRACKING column names — see W03. Either drop the direct UPDATE and rely purely on PROC_OE.INSERT_OE_TRACKING (safer), or discover columns via `USER_TAB_COLUMNS` at boot | 80 | 0 | high | Comment at line 396 admits "unchanged from V89; pending DDL confirm" — likely never worked. |
| **P0** | F04 | Populate SHIPPED_FLAG + HOLD_FLAG in the OEHEAD projection — see B05/G02 | 30 | 0 | med | Block gates for already-shipped and OEHEAD.HOLD_FLAG exist but the data source is `null`. Gates never fire. Enables re-shipping the same container into a MPS mess. |
| **P0** | F05 | Verify PROC_OE.INSERT_OE_TRACKING parameter names via a first-call dry-run + WARN on failure — see W10/H08 | 20 | 0 | med | Comment at line 575-577 admits param names are guessed. USPS-no-CLIPPER fallback will 500-loop until ops corrects. |
| **P1** | F06 | Wire real `containerNo` (from NDS) through `WritebackPackagePayload` instead of using FE sequence — see W02 | 50 | 0 | med | Multi-container fan-out writeback keys on the wrong CONTAINER_NO if operator reordered / removed rows on FE. |
| **P1** | F07 | Extract per-connection tracking-marker constants (`CLIPPER`, `BATCHSHIP`, `450`, `STD`, `M`/`R`/`Q`) into `external_system_connection` columns — see H07-H09, H18 | 200 | 1 (add columns + backfill defaults) | low | Data-driven so NDS forks don't need a rebuild. |
| **P1** | F08 | Delete platform-default `+1 616 772 3513` and `support@thbred.com`; require tenant to set fallback or fail prefill with actionable error — see H01/H02 | 30 | 0 | low | Prevents THB-branded emails/phones going out on labels for other tenants. |
| **P1** | F09 | Third-party account refused → operator-confirm retry on default account — see G06/B10 | 150 (BE + FE) | 0 | med | Operator UX parity with ShipX; currently a hard 422 stops the ship. |
| **P1** | F10 | Per-connection query templates for CLIPPER/OEHEAD/OE_SHIP_CONTAINER/OE_SEND_TO/TB_CLIPPER_ITEM_DETL/TB_BILLABLE_CONTAINERS — see H12-H17 | 400 | 1 (`external_system_query_template` table) | med | Enables NDS forks with different column names to run without a code change. Combines with F01/F02/F03. |
| **P1** | F11 | OE_SEND_TO copy-to email projection — see G08 | 20 | 0 | low | Ops-reported gap; the DTO field exists (`copyTo`) but is always null. |
| **P1** | F12 | Missing letter-of-credit projection — see G09 | 20 | 0 | low | Same as F11 — DTO field exists, field `null`. |
| **P1** | F13 | Unit-price / customs-decl-value min-1 clamp — see G10 | 15 | 0 | low | Carriers reject $0 items on customs; ShipX carries the clamp for a reason. |
| **P2** | F14 | Extract sanitizer whitelist to tenant setting — see B07/H04 | 40 | 0 | low | Per-tenant carrier-profile tuning. Not urgent — current regex is reasonable default. |
| **P2** | F15 | Extract US-territory list to `country.is_us_territory` — see H03 | 30 | 1 (add column + backfill) | low | Cleanup; not currently misfiring. |
| **P2** | F16 | Extract carrier-family (USPS/FedEx/UPS/DHL) to `carrier.family` — see H06 | 40 | 1 | low | Cleanup; enables USPS-family detection in other places (rate limiter etc.). |
| **P2** | F17 | TB_MANUAL_SHIPMENT column-width discovery on health-check — see H10 | 60 | 0 | low | Currently `clamp(v, 50)` everywhere; different NDS installs may have different widths. Preferable to fail-fast on mismatch than to silently truncate. |
| **P2** | F18 | THB-override for wrong-client scan (parity with ShipX) — see G07 | 40 | 1 (`client.is_master_account`) | low | Only THB uses this; low priority for other tenants. |
| **P2** | F19 | Multi-NDS-connection support via `NdsTemplates.forConnection(name)` — see N05/N06 | 100 | 0 | low | No customer asked for two NDSes yet. |
| **P2** | F20 | `.X` default-prefix toggle in tenant settings — see H21 | 15 | 0 | low | Cosmetic. |
| **P2** | F21 | Feature-gate `/api/v1/manual-shipment/nds-lookup` per-tenant — see H22 | 30 | 0 | low | Cleaner 404 than 503 when no NDS wired. |

### Summary
- **Total P0**: 5 items, ~230 LoC, 0 migrations, blocks Quick Ship parity. **Ship first.**
- **Total P1**: 8 items, ~900 LoC, 2 migrations, closes remaining functional gaps.
- **Total P2**: 8 items, ~355 LoC, 3 migrations, cleanup + data-driven-config wins.

---

## 8. Open questions

1. **Is CLIPPER's real column `TRACK` or `TRACKING_NUMBER`?** ShipX doc (`08a-quickship.md:186-192`) explicitly says `clip.track = '<tracking>', clip.freight = '<freight>'`. shioai writer at `NdsShipmentOracleWriter.java:97` writes `TRACKING_NUMBER = :tracking, FREIGHT_AMOUNT = :freight`. Need ops to run `SELECT column_name FROM ALL_TAB_COLUMNS WHERE table_name='CLIPPER'` — `NdsDebugController` already exposes this endpoint but was scoped for scan-source tables, not CLIPPER. Recommend running the describe against CLIPPER before F01.
2. **Does OE_TRACKING have `tracking_no/ship_date/ship_status/carrier_cd/service_cd/freight_amt` columns?** shioai treats these as direct UPDATE targets (`NdsShipmentOracleWriter.java:397-406`) but the file comment admits they're unverified. If OE_TRACKING is ONLY writable via PROC_OE.INSERT_OE_TRACKING (as ShipX suggests) then F03 should delete the direct UPDATE entirely.
3. **Does `TB_SHIP_CONTAINER` have a `WEIGHT` column separate from `OE_SHIP_CONTAINER.GROSS_WT`?** ShipX reads `cont.WEIGHT` from `TB_SHIP_CONTAINER` (`08a-quickship.md:29`). shioai reads `GROSS_WT` from `OE_SHIP_CONTAINER` (`NdsShipmentLookupRepository.java:174`). Are these the same thing under different names, or two different weights (billable vs gross)?
4. **What ShipX character-whitelist actually gets applied?** ShipX doc says "Symbols are removed from name and company; ADDR1 keeps a-zA-Z0-9 space -/.,#" — three fields, two whitelists. shioai has one whitelist applied to all three (`NdsAddressSanitizer.java:24`). Need to confirm ShipX's exact behavior — the sanitizer's own doc comment admits this.
5. **Does ShipX QuickShip actually update `OEHEAD.SHIPVIA_CD` on STD, or only BackOrder does?** ShipX doc (`part-B.md:293`) is explicit: only .X on BackOrder replaces STD, and it's the write-back step that mirrors it to OEHEAD. shioai writer at `NdsShipmentOracleWriter.java:199-230` fires the update on ALL outbound (`!isReturn` gate). This may be a shioai upgrade, or a bug. Confirm intended behavior with ops.
6. **Third-party account "default retry" prompt** — is this still desired UX, or has business decided the strict-fail is preferred? ShipX doc `part-B.md:245` documents the prompt but doesn't say whether it's still wanted.
7. **Is the "scanning one box ships whole order" ShipX behavior (G03) desired or a bug?** shioai deliberately does NOT auto-add sibling containers on submit; it prefills them to the FE and lets the operator decide. If ops wants full auto-MPS, that's a UX change to `applyNdsPrefill` (`NewShipmentPage.tsx:1331-1347`) — not a defect.

---

## 9. Files consulted (canonical list)

### ShipX docs
- `D:/GIT-REPOS/ShipX/docs/handover/source/part-B.md` §7-8 (lines 205-335)
- `D:/GIT-REPOS/ShipX/docs/handover/source/deepdive/08a-quickship.md` (all)
- `D:/GIT-REPOS/ShipX/docs/handover/source/deepdive/08b-quickship-tax.md` (all)
- `D:/GIT-REPOS/ShipX/docs/handover/source/appendix-B.md:35-40, 1449-1500` (spx_proshipment family)
- `D:/GIT-REPOS/ShipX/docs/handover/source/appendix-C.md:10-72` (NDS tables under Quick Ship)

### shioai code
- `D:/GIT-REPOS/thb-shioai/multiship-react/src/components/NewShipmentPage.tsx` (scan + prefill + queue)
- `D:/GIT-REPOS/thb-shioai/multiship-react/src/api/ndsShipmentService.ts`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/controller/NdsShipmentLookupController.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/controller/OrderController.java` (manual-label + regenerate)
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/controller/NdsDebugController.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/ndsshipment/` (all 11 files)
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/CarrierServiceImpl.java` (generateManualLabel, writeback dispatch)
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/externalsystems/writeback/ExternalSystemWritebackDispatcher.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/externalsystems/connectors/NdsOracleConnector.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/StdShipMethodResolver.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/dto/ManualShipmentRequest.java`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/resources/db/migration/V78__external_systems_seed_nds_default.sql`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/resources/db/migration/V90__external_system_writeback_source_channel_flags.sql`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/resources/db/migration/V93__external_system_writeback_dtc_wms_gates.sql`
- `D:/GIT-REPOS/thb-shioai/backend/src/main/resources/db/migration/V100__client_shipvia_code_map_is_hold.sql`
