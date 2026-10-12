# Bulk Mailer — ShipX vs. shioai gap analysis

Read-only audit, 2026-09-30. Author: subagent (fullstack SME) on `dev` @
`093d7731`. All ShipX citations are `file:line[-range]`; all shioai
citations are absolute `file:line`. Sources listed in Overview.

---

## 1. Overview — what "Bulk Mailer" means

### ShipX (`.NET` monolith)
Menu **Bulk Mailer → Upload / History**. Purpose: **mail-out of many
parcels from a spreadsheet**, e.g. sample mailings. One 25-column CSV
per batch (`wwwroot/shipx_bulkshipformat.csv`), one client per file, one
row → one single-package shipment, no NDS writeback. FedEx / UPS / USPS
supported per row via `SHIPVIA_CD`. All state lives in Postgres tables
`SPX_tblbulkmailerBatch` + `SPX_tblexcelupload`. See
`ShipX/docs/handover/source/deepdive/13-bulk-mailer.md` (entire file,
262 lines) and `combined.md:4169-4521`.

### shioai (Spring Boot + React)
**"Bulk Mailer" is the marketing name for the entire CSV/XLSX order
import + label pipeline.** It is spread across three surfaces that share
the wire tag but are three different subsystems:

| Surface | What it does | Backing entities |
|---|---|---|
| `/bulk/imports` | List + open CSV/XLSX **imports** (batches) | `import_batch`, `import_batch_row` |
| `/bulk/import` | Upload + validate + save one CSV/XLSX file | `import_staging_*` → `import_batch` |
| `POST /api/v1/bulk-labels` | Fan-out N orders through the label pipeline into a ZIP | `bulk_label_jobs` |

The FE nav (`multiship-react/src/routes/workspaceRoutes.ts:7,14,98`),
the controller Swagger tag (`BulkBatchController.java:29`), and the JPA
`ImportBatch` javadoc (`ImportBatch.java:179`) all call this "Bulk
Mailer" — copying ShipX's brand — but the mechanics are different from
ShipX.

---

## 2. Terminology check

| ShipX term | shioai equivalent | Same concept? |
|---|---|---|
| Bulk Mailer (menu section) | `/bulk/*` — imports + labels & invoices | Umbrella name only. Different scope. |
| Bulk Mailer CSV upload (25 col fixed) | `OrderImportServiceImpl` CSV/XLSX importer | **Different.** Free-form 20+ columns, per-client warehouse dropdowns, no THP_* columns, no `ONE_RATE`/`SHIP_DATE` phantoms. |
| `SPX_tblbulkmailerBatch` | `import_batch` (`ImportBatch.java`) | Similar role (batch header) but shioai stores a slug, tenant scope, deleted_at, cancel_requested_at, per-run generation timestamps, and label counters. |
| `SPX_tblexcelupload` (one row per CSV line, becomes 1 shipment) | `import_batch_row` OR `import_batch.rows_json` OR `Order` (rows generate real `orders`) | **Different lifecycle.** shioai's rows become real `Order` entities on Generate; ShipX rewrites the same excelupload row in place. |
| Bulk mailer address validation | shioai `AddressValidationService` (per-carrier) | Different. ShipX hard-codes FedEx carrier 28 for validation of every row regardless of ship carrier. shioai uses the row's own carrier. |
| Bulk mailer "one row = one label" | `BulkLabelServiceImpl` + `CarrierService.generateLabel` per order | **Overlap.** shioai's `BulkLabelServiceImpl` (`bulk_label_jobs`) is closest to ShipX's step 4/5 loop — but the input is an order-number list, not a spreadsheet. |
| Bulk mailer CSV template | shioai `.xlsx` template built dynamically in `OrderImportServiceImpl` (Sprint 48) | shioai's is dynamic + per-client (dropdowns for carrier accounts / warehouses / services). ShipX ships a static CSV header on disk. |
| Bulk mailer history/void/delete | `BulkBatchQueryService` (list) + `voidService` + `soft-delete` | Roughly equivalent, plus richer (label counts, live void tracking, Trash view). |
| "Bulk Mailer parcels never write to NDS" | `V94__nds_default_bulk_writeback_off.sql` + writeback framework | **Same operator contract, better plumbing.** ShipX hard-coded silence; shioai gates it via a per-connection `writeback_source_bulk` flag defaulting FALSE on the seeded nds-default row (togglable in UI). |

**Bottom line.** ShipX Bulk Mailer is a **narrow CSV → shipment tool**
with a fixed schema, one hardcoded FedEx validation account, one
hardcoded "Thoroughbred" phone fallback, and one hardcoded shipper
(THB). shioai has **no CSV-driven mail-mailout tool** — it has a
general-purpose order import that yields real `orders`, then a
`bulk-labels` fan-out over any pre-existing orders. The "Bulk Mailer"
label was reused as the FE section name, but the ShipX-style single-use
mail-out isn't a first-class flow.

---

## 3. ShipX §13 behavior (what to compare against)

Source: `ShipX/docs/handover/source/deepdive/13-bulk-mailer.md`.

**Spreadsheet contract** (deepdive:10-32; combined.md:4181-4210):
- CSV only, `.csv` extension, 25 header columns case-sensitive.
- One CLIENT_ID per file; blank phone becomes `+16167723513` (Thoroughbred's number, hardcoded in `BALShiplist.cs:L7440`).
- Non-US rows require `ITEM_NUMBERS`, `QUANTITY`, `HTSCode`.
- THP_* headers required at header check even if values blank.

**Flow** (deepdive:34-192):
1. `Checkduplicatefile` — validators + `SELECT COUNT` against `SPX_tblClient` (first row's client, active check) + `SPX_tblCarrierService` (every code must map to one of the client's carriers) + same-day filename dedup.
2. `spx_probulkmailerbatch` — inserts `SPX_tblbulkmailerBatch`, returns BatchId.
3. Each row → `spx_prospxexcelupload` — one `SPX_tblexcelupload` row (Status=false, ErrorMessage='', IsDelete=false).
4. `Shipmentsvalidation` — every row's address goes to FedEx validation (**carrier 28 hardcoded**, THB account) → `spx_probulkmailervalidation` writes `ErrorMessage='OK'` or the error text.
5. Print — `BulkmailerLabel` reads rows where `Status=false AND ErrorMessage='OK'`, orders `ShipmentId DESC` (**newest first, reverse CSV order**), calls carrier per row via `BALShiplist.ExcelUpload`. `ShipmentMethod` SP picks the smallest packing with `MaxWeight > weight`. On success `spx_updatebulkmailer` writes tracking, ZPL, from-address, sets `DeteteStatus=false` (deletion blocker).
6. **No NDS writes.** Reads only: `SHIPX_DEV_SPECIAL_ACCOUNTS` (MKL246 on FSM), `SHIPX_SPECIAL_ACCOUNTS` (ARC805 on U30), `TB_SHIPX_WORLDSHIP_VW` (MHR210), and one broken `TB_CLIPPER_ITEM_DETL` query for USPS intl rows.
7. History + void + delete: `SPX_tblexcelupload` per-row cancel; batch-level "Delete" hard-blocks any row that ever printed.

**Documented bugs** (deepdive:200-262 "Things to watch") — all still
present in ShipX code as of the handover: success message is a lie
(returns "Successful" whenever no exception thrown, last-row only); UPS
customs line reads single character from `BulkItems[0]`; customs value
always 1 USD/unit; USPS international is silently broken; SQL built
from string concat everywhere except two pre-check counts.

**Stored procedures** (`appendix-B.md`):
- B.4 `spx_probulkmaileraddressupdate` (line 320)
- B.5 `spx_probulkmailerbatch` (line 350)
- B.6 `spx_probulkmailervalidation` (line 379)
- B.34 `spx_prospxexcelupload` (line 1908)
- B.40 `spx_updatebulkmailer` (line 2164)
- `spx_updatestatus` handles `CancelBULKShipment` and `Deletebulkmailer` action types (line 2494)

---

## 4. shioai counterpart — three-part stack

### 4a. Import batch pipeline (closest to Bulk Mailer step 1-3)
- `backend/src/main/java/com/multiship/backend/service/OrderImportServiceImpl.java` (3,500+ LOC — 955 mentions of `bulk`/`import`)
- `backend/src/main/java/com/multiship/backend/model/ImportBatch.java:1-200`
- `backend/src/main/java/com/multiship/backend/controller/BulkBatchController.java:1-95` — list + summary + one-batch
- `multiship-react/src/components/BulkImportPage.tsx:13` — upload page
- `multiship-react/src/components/modals/OrderImportModal.tsx` — the actual importer
- `multiship-react/src/api/bulkService.ts` — list, summary, one-batch client
- `multiship-react/src/api/orderImportService.ts` — upload, validate, commit
- Schema: `V79` (client_code), `V80` (document_print_event), `V81` (label counters), `V86` (slug), `V87` (import_batch_row separate table)

### 4b. Bulk label fan-out (closest to Bulk Mailer step 4-5)
- `backend/src/main/java/com/multiship/backend/service/BulkLabelServiceImpl.java:75-1465` — job kick-off, fan-out executor, per-order dispatch, ZIP assembly, USPS_DIRECT queue routing, MPS splitter
- `backend/src/main/java/com/multiship/backend/service/BulkLabelService.java` — 4-method interface (submit / status / findRaw / cancel)
- `backend/src/main/java/com/multiship/backend/controller/BulkLabelController.java:47-111` — 4 endpoints (submit, status, cancel, download)
- `backend/src/main/java/com/multiship/backend/model/BulkLabelJob.java` — job entity (order_numbers, status, counts, result_zip_base64, failure_details_json)
- `backend/src/main/java/com/multiship/backend/dto/BulkLabelRequestDTO.java` — orderNumbers OR identifiers (F1 polymorphic {type,value})
- `backend/src/main/java/com/multiship/backend/dto/BulkLabelIdentifierDTO.java` — orderNo | orderRef (wmsExternalId lookup)
- `multiship-react/src/components/modals/BulkLabelModal.tsx` — poll loop, download link, MPS card, USPS queue badge
- Schema: `bulk_label_jobs` (V37-era) + `V51__bulk_label_jobs_failure_details.sql`

### 4c. NDS gating (closest to Bulk Mailer step 6, "no NDS")
- `backend/src/main/resources/db/migration/V93__external_system_writeback_dtc_wms_gates.sql` — adds `writeback_source_bulk|dtc|wms` columns on `external_system_connection`
- `backend/src/main/resources/db/migration/V94__nds_default_bulk_writeback_off.sql` — seeds `nds-default.writeback_source_bulk = FALSE`
- FE toggle on `/settings/external-systems` (Writeback tab)

---

## 5. Gaps — feature-by-feature

| # | ShipX Bulk Mailer feature | shioai state | Gap severity |
|---|---|---|---|
| G1 | **Fixed 25-column CSV mail-out schema** (CLIENT_ID, ATTENTION, COMPANY, ADDRESS1..2, CITY, STATE, ZIP, COUNTRY, WEIGHT, PHONE, EMAIL, SHIPVIA_CD, THIRD_PARTY_ACC, GROUP_ID, ITEM_NUMBERS, QTY, HTSCode, THP_* x5, plus phantom ONE_RATE/SHIP_DATE) | Different schema in `OrderImportServiceImpl` (recipientName, addressLine1/2, weight, carrierCode, etc). No THP_* customs line, no CLIENT_ID-per-file enforcement, no GROUP_ID → container-id concept, no ONE_RATE/SHIP_DATE phantom cols. | **Missing** if a customer relies on the exact ShipX template; **not a gap** if the goal is a modern importer. Confirm with product. |
| G2 | **Third-party billing address per row** (THP_ACCOUNT + THP_ADDRESS/CITY/STATE/POSTAL/COUNTRY) | shioai's `Order` model supports third-party account (`ThirdpartyAc` equivalent — see `orderImportService.ts` schema) but the per-order 3rd-party **billing address** on the shipment DTO is not exposed via the CSV importer. Manual shipment path has it (Sprint 51 email+company track). | Medium — the ShipX schema exposes it, shioai importer doesn't. |
| G3 | **"Weight → packing" auto-pick** (`ShipmentMethod` SP: smallest packing where `MaxWeight > weight`) | shioai relies on operator-selected `packageType` per row OR client default via `PackagePreset`. No weight-driven "pick smallest packing" resolver. | Low — customers moved away from this heuristic. |
| G4 | **CSV row → single-package shipment** (one row = one package always) | shioai supports MPS: `Order.packageCount >= 2` routes via `UspsMpsSplitterService`. Import row can carry multiple packages. | **shioai is a strict superset.** |
| G5 | **Address validation with a single hardcoded FedEx account** (all rows, all carriers) | Per-carrier via `AddressValidationService` (`OrderImportServiceImpl.java:107`). | **shioai is correct.** |
| G6 | **Duplicate-filename same-day check** (`Checkduplicatefile`) | Content-hash (`ImportBatch.contentHash` — SHA-256 of row data) rejects re-uploads of identical content. Filename is not part of the check. | Better in shioai (hash beats filename). |
| G7 | **`BulkFileName` + `IPaddress` header** | `ImportBatch.fileName` recorded; no client IP column. | Low — SecurityAudit table probably has it. |
| G8 | **Batch lifecycle** (Status flag on rows; `IsDelete`; `DeteteStatus` deletion blocker) | Richer: `INITIATE / DRAFT / IN_PROGRESS / PARTIAL_COMPLETE / COMPLETE / FAILED / CANCELLED`; `deletedAt / deletedBy` soft delete + Trash view; `cancel_requested_at` durable cancel signal; label counters (V81); label_orders JSON for live void counting. | **shioai far ahead.** |
| G9 | **CSV-native mail-mailout without an Order entity** (rows go straight to carrier, no `Order` created) | shioai's importer creates real `Order` entities on Generate; there is no "1-off row → label without an order row" path. | **Missing.** A mail-mailout of 500 samples in shioai leaves 500 `orders` in the DB; ShipX left 500 `SPX_tblexcelupload` rows. Confirm with product whether this is desirable. |
| G10 | **THP_ACCOUNT third-party billing** (per-row bill-to) | Available in `ManualShipmentRequest`. Not on the CSV importer schema. | Medium — see G2. |
| G11 | **CSV → international customs commodity** (`ITEM_NUMBERS`/`QUANTITY`/`HTSCode` → one commodity line) | shioai's `IntlShipmentBlockDTO` + `OrderCustomsUpsertRequest` (see `intl-dto-wire-and-persist-paths` memory doc) support this on the DTO; the CSV importer maps `goodsDescription` + declared value, but does NOT accept HTS codes or itemised quantities. | **Missing** on the CSV importer, present elsewhere. Reuse existing DTO fields — add three CSV columns. |
| G12 | **Batch history filter by date + tracking number** | Richer: full toolbar (`BulkBatchController.batches` — `q, from, to, createdBy, labelBatch, minSaved, status`, sort + paginate). Tracking-id filter lives on other pages (Labels & Invoices, Orders). | shioai ahead. |
| G13 | **Cancel one shipment / cancel batch** | `voidService` (per-order) + `OrderImportServiceImpl.cancelGeneration` (batch-level durable cancel via `cancel_requested_at`). Cross-instance safe. | **shioai ahead.** |
| G14 | **Retry a failed row from its edit page** | Retry-only-failed on batch (`generateLabelsForBatch(id, requestedBy, onlyFailed=true, ...)` — `OrderImportServiceImpl:3536`). | shioai ahead. |
| G15 | **"After print, no deletion" (`DeteteStatus`)** | `document_print_event` (V80) records prints; delete/void rules in `OrderImportServiceImpl:3311+` and `BulkBatchLabelRulesTest`. | shioai equivalent + richer. |
| G16 | **NDS writes: NONE from bulk mailer** | `V94` enforces the same contract via the writeback framework; togglable per connection. | **shioai enforces the contract via infra, ShipX only via omission.** |
| G17 | **Named-account NDS reads for special clients** (MKL246 on FSM, ARC805 on U30, MHR210 WorldShip view) | Not implemented in shioai. `SHIPX_DEV_SPECIAL_ACCOUNTS` / `SHIPX_SPECIAL_ACCOUNTS` never appear in the codebase (`grep` returned no matches). | **Missing** — verify with product whether these named-account overrides still apply. |
| G18 | **CSV template download link** | Dynamic per-tenant XLSX template (Sprint 48) with client / carrier account / service / packageType / warehouse dropdowns. | **shioai far ahead.** |
| G19 | **Order matters: rows printed `ShipmentId DESC` (newest first)** | shioai processes rows in input order (fan-out completion loop reads futures in order). `BulkLabelServiceImpl:658-660`. | Behavior differs — shioai is more intuitive. |
| G20 | **`GROUP_ID` → `ContainerId` → sticker/master-tote grouping** | No equivalent. shioai has `label_batch_id` (per-batch, not per-group). | **Missing** — if operators used GROUP_ID to bundle samples per pallet/tote, no substitute exists. |

---

## 6. Hardcoded findings

Read of `BulkLabelServiceImpl.java`, `BulkBatchQueryService.java`,
`BulkBatchController.java`, `BulkLabelController.java`, `ImportBatch.java`
and grep across `backend/src` for THB / Thoroughbred / carrierId=28 /
+16167 / hardcoded phone found:

| # | File:line | Constant | ShipX parallel | Fix |
|---|---|---|---|---|
| H1 | `BulkLabelServiceImpl.java:379` | `MAX_BULK_ORDERS = 500` | ShipX unlimited (any CSV size) | Already externalised via `@Value` for concurrency (`bulk.worker-concurrency`, `bulk.max-per-tenant`) but the 500 cap itself is compile-time. Externalise as `bulk.max-orders-per-submit` for parity with the tenant/concurrency knobs. **Priority: LOW** (500 is a safe default). |
| H2 | `BulkLabelServiceImpl.java:109` | `bulk.stale-running-cutoff-minutes:60` | Not applicable in ShipX (no crash-reap) | Already property-driven. Ship a shorter dev-mode profile to fix quicker in QA. **Priority: NONE — well-done.** |
| H3 | `BulkLabelServiceImpl.java:112` | `LABEL_FETCH_TIMEOUT = Duration.ofSeconds(15)` | ShipX N/A (direct carrier calls) | Move to `bulk.label-fetch-timeout-sec` — the 15s hardcode has bitten slow CDNs before. **Priority: LOW.** |
| H4 | `BulkLabelServiceImpl.java:536-537` | `PROGRESS_FLUSH_EVERY_N = 25` + `PROGRESS_FLUSH_EVERY_MS = 2000` | N/A | Compile-time; consider `@Value`. **Priority: LOW.** |
| H5 | `BulkBatchQueryService.java:69` | `MAX_PAGE_SIZE = 100` | N/A | Trivial. **Priority: LOW.** |
| H6 | `BulkBatchQueryService.java:67-68` | `STATUSES = List.of("DRAFT","INITIATE","IN_PROGRESS",...)` | ShipX equivalent status set | These are the canonical `import_batch.status` values, closed enum. Not tunable — correctly hardcoded. **Priority: NONE.** |
| H7 | *(not found in shioai)* | `+16167723513` fallback phone | `BALShiplist.cs:L7440` | **shioai does NOT carry ShipX's Thoroughbred phone-number fallback.** Grep across `backend/src` returned zero matches. If product wants ShipX parity, this needs a per-tenant `bulk_fallback_phone` setting on `tenant_settings` — NOT a static constant. **Priority: MEDIUM if product wants the fallback; LOW/NONE otherwise.** |
| H8 | *(not found in shioai)* | Carrier 28 hardcoded FedEx validator | `DALBulkUpload.cs:L91` | shioai uses per-carrier validation. **shioai is correct — no fix needed.** |
| H9 | *(not found in shioai)* | THB client hardcoded for validation account | `DALBulkUpload.cs:L155-L157` | shioai uses the row's own carrier account. **Correct.** |
| H10 | *(not found in shioai)* | MKL246/ARC805/MHR210 NDS overrides | `BALFdExShipApi.cs:L1805` etc | Missing. See G17. **Verify with product before adding — customer may have retired these.** |

**Biggest hardcoded finding.** By absence: **H10 — the three
customer-name-coded NDS overrides (MKL246, ARC805, MHR210) that ShipX
Bulk Mailer applies at label-time do not exist in shioai.** If any of
these customers still ships bulk with the old override rules, their
carrier account will resolve differently in shioai than in ShipX and
they will get the wrong shipper account on the label. **Confirm with
customer support before writing any code** — the alternative
interpretation is that these overrides were fossils that the customer
support team already retired.

Runner-up hardcoded item (present, minor): **H1 (`MAX_BULK_ORDERS = 500`
compile-time)**. Every other bulk knob is `@Value`-driven; the cap
itself is inconsistent. 10-line fix.

---

## 7. NDS touchpoints in the bulk-mailer flow

### ShipX
Per deepdive:79-95:
- **No NDS writes.** No `SetTrackingQ`, no `SetManualTrackingQ`, no `OE_TRACKING`, no `CLIPPER`.
- Reads only:
  - `production.SHIPX_DEV_SPECIAL_ACCOUNTS` for FedEx account override on client MKL246 (FSM path).
  - `production.SHIPX_SPECIAL_ACCOUNTS` for UPS account override on client ARC805 (U30 path).
  - `TB_SHIPX_WORLDSHIP_VW` for client MHR210 with a blank order (returns nothing meaningful).
  - `TB_CLIPPER_ITEM_DETL` for USPS international rows — **broken SQL** (missing order_no leaves `order_no =  and order_suffix = ''` — Oracle rejects with ORA-00936).
- Cited in `appendix-C.md:73-74, 51`.

### shioai
- **Explicit contract via `writeback_source_bulk` gate**, defaulting FALSE for the seeded nds-default row (`V94__nds_default_bulk_writeback_off.sql:16-23`). Togglable per connection in `/settings/external-systems` Writeback tab.
- No NDS reads at bulk-label-generate time. The three ShipX customer-name overrides are absent (see G17/H10).
- **NDS is written on other flows**, not bulk (per `writeback_source_bulk`, `writeback_source_dtc`, `writeback_source_wms` triplet in V93 — see `docs/handover/source/nds-audit-and-fe-spec.md`).
- Verified: `grep -i "nds|oracle" BulkLabelServiceImpl.java` → 0 matches (only `TimeUnit.SECONDS` and `Duration.ofSeconds` noise).

**Verdict.** shioai's operator contract on NDS matches ShipX §13, and
it is enforced infrastructurally rather than by omission — safer.

---

## 8. Prioritized fix-plan

Ordered by (product value × ROI) with the ladder applied — most items
turn out to be either "already done" or "need product confirmation
before writing anything".

| # | Priority | Item | LoC | Migrations | Risk | Rationale |
|---|---|---|---|---|---|---|
| P1 | **HIGH** | **Product decision needed: is a ShipX-shape CSV mail-mailout tool still required?** shioai has no equivalent to G1/G9. If yes → build one. If no → document the intentional gap. | 0 (decision) | 0 | ZERO if decision-only | This blocks P4/P5/P6. Do not build first. |
| P2 | **HIGH** | Verify H10 / G17 (MKL246/ARC805/MHR210 NDS overrides) with customer support. If still active, migrate to a `client_carrier_override` table. | 0 (decision), then ~80 | 1 (`V-XX__client_carrier_overrides`) | Medium (wrong account = wrong bill) | Silent divergence between ShipX and shioai for named customers. |
| P3 | **MEDIUM** | Add HTS + itemised quantity columns (G11) to the CSV/XLSX importer, wire to existing `OrderCustomsUpsertRequest` (per `feedback_intl_dto_wire_and_persist_paths.md`). | ~120 | 0 (DTO fields exist) | Low | Small, high-value; reuses ShipX-proven schema fragment. |
| P4 | **MEDIUM (if P1=yes)** | THP_* per-row 3rd-party billing address on CSV importer (G2/G10). Persist to `Order.thirdPartyBillingAddress` (new field via V-XX). | ~180 | 1 | Low-medium | Reuses existing ShipmentRequestDTO plumbing. |
| P5 | **LOW (if P1=yes)** | `GROUP_ID` → `container_id` for master-tote bundling (G20). New `order.container_id` + FE grouping. | ~100 | 1 | Low | Only if a customer still relies on GROUP_ID. |
| P6 | **LOW (if P1=yes)** | Static ShipX-compatible 25-col CSV template file at `wwwroot/shipx_bulkshipformat.csv` equivalent. | ~50 | 0 | ZERO | Migration path for customers with hand-built ShipX CSVs. |
| P7 | **LOW** | Externalise `MAX_BULK_ORDERS = 500` as `bulk.max-orders-per-submit` @Value (H1). | ~10 | 0 | ZERO | Consistency with other bulk knobs. |
| P8 | **LOW** | Externalise `LABEL_FETCH_TIMEOUT = 15s` (H3) and `PROGRESS_FLUSH_EVERY_N/MS` (H4). | ~15 | 0 | ZERO | Ops tunability. |
| P9 | **NONE — already handled** | NDS write contract (G16 / §7). | 0 | 0 | 0 | V93 + V94 done. Verify FE Writeback tab shows the flag. |
| P10 | **NONE — shioai is strictly better** | Address validation (G5), duplicate-file check (G6), batch lifecycle (G8), retry-only-failed (G14), print history (G15), template (G18), cancel (G13), filter/search/paging (G12). | 0 | 0 | 0 | Do not regress toward ShipX. |

### Recommended sequencing

1. Get P1 answered (1 day, product).
2. In parallel: P2 verification (1-2 days, customer support).
3. If P1=yes: ship P3 first (single PR, small blast radius). Then P4/P5/P6 as separate PRs.
4. Batch P7+P8 into one 30-min ops-hardening PR — no risk.

**Estimated total build (if P1=yes and P2 confirms overrides):**
~550 LoC + 3 migrations across 5 PRs. **~2 dev-days**.

**If P1=no:** ~25 LoC (P7+P8) + one runbook doc — half a day.

---

## 9. Open questions

1. **P1 blocker.** Does any customer still upload a ShipX 25-column
   Bulk Mailer CSV? If yes, they need G1/G9 rebuilt or migrated. If no,
   record the intentional divergence and close.
2. **P2 blocker.** Are MKL246 / ARC805 / MHR210 special account
   overrides (H10) still in effect for those customers, or were they
   retired in the ShipX → shioai cutover?
3. Is the `+16167723513` Thoroughbred phone-number fallback (H7)
   customer-visible on any real shipment today? If yes, it needs
   per-tenant configuration. If no, the omission from shioai is
   correct and no work is required.
4. Should `bulk_label_jobs.order_numbers` (`BulkLabelJob.java:24-25`)
   move to a proper `bulk_label_job_order` join table? The current
   comma-separated `text` column is fine at N < 500 but out of step
   with how shioai models every other 1:N relationship. **NOT** in this
   report's scope — flag only.
5. `BulkBatchController.java:29` Swagger tag is `"Bulk Mailer"` but the
   controller is only the *list*; `BulkLabelController.java:43` tag is
   `"Bulk labels"`. Two Swagger sections that both look like ShipX
   Bulk Mailer. Confirm with the FE team whether renaming to
   `Bulk imports (list)` and `Bulk labels (fan-out)` would clarify.
6. `SPX_tblexcelupload` still shows up in `appendix-B.md` as a view
   (`spx_shipmentdetailslist_vw`, appendix-B.md:2710-2731) that
   UNION-ALLs bulk mailer rows into a unified shipment list. shioai
   has no equivalent unified view — is one needed for reporting?
