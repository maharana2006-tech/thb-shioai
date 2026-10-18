# Print & cancel/reprint/reship — shioai vs ShipX §17 + §18

Read-only gap-analysis of ShipX handover §17 (Printing, invoices, paperwork)
and §18 (Cancelling, reprinting, re-shipping) against the current shioai
codebase. Sandbox probe at `docs/audits/probe-print-cancel.txt` passed
(Write allowed).

Sources compared:

- ShipX: `D:/GIT-REPOS/ShipX/docs/handover/source/combined.md` §17 (5577-5922)
  + §18 (5923-6270); deepdives `17-printing-invoices.md`,
  `18-cancel-reprint.md`; `appendix-B.md` cancel SPs; `appendix-C.md` NDS
  refs.
- shioai: `backend/src/main/**`, `multiship-react/src/**`, migrations
  `V1..V101+`.

## 1. Overview

ShipX §17 is a server-side Windows GDI+ pipeline that prints labels
through the print spooler and either prints or PDF-drops commercial
invoices to fixed `C:\...` folders. Invoices are drawn by C# code from
NDS item lines (no templates). §18 is a per-shipment-type cancel switch
(Quick / Billable / Manual / DTC / Bulk / Return) that first asks the
carrier, then flips a Postgres row, then (Mode=Live only) undoes the NDS
writes. Reprint re-sends the saved ZPL from `SPX_tblShipmentDetails`.
Re-ship exists only for Shipment History (`ProcessReShipments`) and DTC
"void and re-ship".

shioai is broadly ahead of ShipX on: label preview (14 PRs), printer
infrastructure (25 PRs), multi-batch void, per-tenant NDS writeback
(V89/V90/V93), Idempotency + PESSIMISTIC_WRITE on void, and PDFBox 3.x
commercial-invoice + packing-slip rendering. It is behind or missing on:
document-type registry, packing-slip printer routing, per-client invoice
copies UX exposure, reprint UI for the ops audit trail, and a
symmetric "re-ship" surface distinct from "regenerate a failed order".

## 2. Prior work already covered (do not re-audit)

- **Label preview / rendering** — 14 PRs #535-#552 shipped
  `PdfMerger` (PDFBox 3.x), unified per-package PDF picker, zebrash-cli.
  `PdfMerger.java`, `LabelImagePdfService.java`, `ZebrashPdfService.java`.
- **Printer infra** — 9 PRs #687-#695 (auto-detect + scanner) + 16 PRs
  #699-#714 (page redesign, tags, R11 queue depth, SSE stream,
  bulk ops, click-cell assignment, R7a/b/c invoice-copies matrix).
- **Label PO+DEPT** — PR #543 populates carrier reference slots
  (`MAN{orderNo}` / `wmsExternalId`).
- **NDS writeback** — V89/V90/V93/V94 external-system framework, dispatched
  from `VoidServiceImpl.java:277` and `CarrierServiceImpl.java:789`.

## 3. ShipX §17 behavior

**Printing pipeline** (§17, combined.md:5577-5622):

- Labels: JSON envelope + share name → `\\<PntIP>\<PntIdentifier>` via
  Windows spooler as RAW data (not port 9100). `Station` mode (TCP 8082)
  defined but unused.
- Invoices: GDI+ page-by-page → office printer OR Microsoft Print to PDF
  → saved to fixed `C:\` folders (`C:\ShipxAutoCommInv\<client>\<MM-dd-yyyy>\`,
  `C:\UPSCommercialInvoice\`, `C:\ShipxETD\`, `C:\SteelCaseEmails\`,
  `C:\SCANdocs\`, `C:\batch\`).
- Print failures silent (`RawPrinterHelper.cs` always returns success).
- Printer table `SPX_tblPrinter` has fixed `PntType ∈ {LABEL, PACK_INV}`
  (17 LABEL + 8 PACK_INV in backup, combined.md:5584).

**Document types**: fixed list, all in code:

| Document | Where built | Template? |
|---|---|---|
| Carrier ZPL label | Carrier response | No |
| Commercial invoice (shipping screen) | `BALShiplist.commercialinvoice` | No — GDI+ hardcoded |
| DTC commercial invoice | `BALShiplist.DTCcommercialinvoice` | No |
| Steelcase CA/MX invoice | `BALShiplist.SteelCasecominvCanada` | No (kit components not printed) |
| Bulk Mailer invoice | Single fixed line | No |
| Manual commercial invoice screen | `CommInvoiceController` (ShipX only) | No |
| USPS SCAN form | `Stamps/BAL/BALStamps.cs` | No |
| FSM 049 ETD | Filesystem drop | No |

No packing slip — shipping label + commercial invoice only.

**Invoice generation** (combined.md:5648-5750):

- Data pulled from NDS `tb_clipper_item_detl` + `oehead` + `oedetl` +
  `tb_ship_container` (Oracle client login).
- Lines saved in Postgres `SPX_tblCommercialInvoice`; DTC in
  `SPX_tblDTCCommercialInvoice`.
- Importer block from `SPX_tblimporter` (per-client).
- Copies: `SPX_tblClient.ComInvCopy` (per-client int on the client row).
- **International-only** and only when `ContainerNo == "1"`
  (combined.md:5709). Other containers of a multi-container order get
  no invoice.
- Steelcase/STL380: kit components saved but not printed
  (combined.md:5720).
- `letter_of_credit` from character 5 shown in MARK_NBRS column
  (combined.md:5698).
- Under-$1 shipment forces total to $1.00 (combined.md:5731).

## 4. shioai print counterpart

**Print stack**: `PrinterService.java` (`backend/.../service/printing/`)
+ `PrinterTransport` (RAW_9100 + IPP + Get-Jobs) + `PclRasterizer`
(PDFBox 3.x → PCL for office lasers).

- Connections: `Set.of("RAW_9100", "IPP")` — `PrinterService.java:48`.
- Formats: `Set.of("ZPL", "PDF", "PCL")` — `PrinterService.java:51`.
- Paper: `Set.of("LABEL_4X6", "A4", "LETTER")` — `PrinterService.java:52`.
- **Doc types: `Set.of("LABEL", "COMMERCIAL_INVOICE")`** —
  `PrinterService.java:53`. **Hardcoded**, in the same file that
  validates every assignment (`.java:191`).
- Assignments: `printer_assignments` table, `(clientCode, docType) →
  printerId`; blank clientCode = default. `PrinterService.resolve()`
  falls back client → default.
- In-flight counter + IPP Get-Jobs for queue depth (R11).
- Print-address guard against SSRF (`PrinterAddressGuard.java`).

**Commercial invoice renderer**: `CommercialInvoiceServiceImpl.java`
(883 lines, PDFBox 3.x). Renders from `order_customs` +
`order_customs_item` + `Client` + `ClientCustomsProfile`. Espresso/cream
palette, A4 default with LETTER override, dry-measure pagination.
**Not template-driven** — layout hardcoded, only the client's own tax
IDs + importer/broker are per-client via `ClientCustomsProfile`.

**Packing slip renderer**: `PackingSlipServiceImpl.java` (485 lines,
PDFBox 3.x). **Template-driven** — reads `LabelTemplate` resolved by
`(tenantId, "PACKING_SLIP")` from the `label_templates` table
(`LabelTemplate.java`). Custom fields (G5) included. Falls back to
platform defaults when tenant hasn't configured a template.

**Copies**: `InvoiceCopies` model (V70), `invoice_copies` table with
`(clientCode, carrierCode) → copies (1..20)`, `NULL` clientCode = tenant
default. `InvoiceCopiesService.resolveCopies()` cascade: exact → tenant
default → 1. Wired at bulk print in `OrderController.java:1725-1728`.

**Endpoints on `OrderController`**:

- `GET /orders/{orderNo}/packing-slip` — inline PDF, `L1417`.
- `GET /orders/{orderNo}/shipment-documents` — merged invoice + labels,
  `L1451`.
- `POST /orders/documents/print` — bulk print, `L1514`. Only accepts
  `docType ∈ {LABEL, COMMERCIAL_INVOICE}` (`L1521`).
- `POST /orders/documents/send-to-printer` — send to network printer,
  `L1613`. Same two-value docType clamp (`L1624`).

**FE `LabelTemplate` types** (`LabelTemplatesListPage.tsx:36`):
`['SHIPPING_LABEL', 'PACKING_SLIP', 'COMMERCIAL_INVOICE', 'RETURN_COVER']`
— **four** types offered in the UI, but backend `LabelTemplate.java:38-46`
javadoc lists PACKING_SLIP + COMMERCIAL_INVOICE + RETURN_COVER (no
SHIPPING_LABEL), and `PrinterService.DOC_TYPES` accepts only two.
Inconsistent surface.

## 5. Gaps (§17) — feature-by-feature

Ordered by user impact. Label rendering, printer registry, and PdfMerger
are prior-work-covered; this section skips them.

### 5.1 — PACKING_SLIP not a routable document type
- ShipX has no packing slip. shioai has one (`PackingSlipServiceImpl`),
  but `PrinterService.DOC_TYPES` refuses assignments for it
  (`PrinterService.java:53,191`).
- Consequence: the branded packing slip exists as a downloadable PDF
  endpoint (`GET /orders/{orderNo}/packing-slip`) but **cannot be routed
  to a printer** through the same infrastructure as invoices/labels. The
  bulk `POST /orders/documents/print` refuses `docType=PACKING_SLIP`
  (`OrderController.java:1521`).
- Fix: add `"PACKING_SLIP"` (and `"RETURN_COVER"` for parity with
  `LabelTemplate` javadoc) to the `DOC_TYPES` set and extend the two
  controller docType clamps.

### 5.2 — Commercial invoice not template-driven
- ShipX: hardcoded GDI+ layout. shioai improves on ShipX by rendering
  from PDFBox, but the layout itself is still hardcoded in
  `CommercialInvoiceServiceImpl.java`. The `LabelTemplate.templateType =
  "COMMERCIAL_INVOICE"` slot exists (model javadoc L40-L44) but the
  renderer ignores it.
- Consequence: no per-client custom invoice branding / layout / footer.
  Only the ClientCustomsProfile (tax IDs, importer, broker) is
  per-client, which matches ShipX's `SPX_tblimporter`.
- Fix (large): plumb `LabelTemplate.resolve(tenantId, "COMMERCIAL_INVOICE")`
  into `CommercialInvoiceServiceImpl` the same way `PackingSlipServiceImpl`
  does (`L113-L115` there). Layout knobs already exist:
  `primaryColor`, `headerText`, `footerText`, `logoBase64`, `layoutJson`.

### 5.3 — Invoice-copies UI missing / underexposed
- ShipX: `SPX_tblClient.ComInvCopy` (single int per client).
- shioai: superior model (V70 `invoice_copies` table; per (client, carrier)
  int, tenant-default row). Backend + service + REST all live
  (`InvoiceCopiesController.java`, `InvoiceCopiesService.java`).
- Gap: no dedicated FE surface listed in the search; `LabelTemplatesListPage`
  handles templates, not copies. The R7b "Copies-tab matrix" referenced in
  `InvoiceCopiesController.java:26-28` and per-memory `project_printer_page_redesign`
  is shipped as part of the PrintersPage redesign but should be confirmed
  discoverable from `/settings/printers` alongside per-carrier defaults.
- Fix: FE check-and-link only; small.

### 5.4 — No "invoice only for container 1" defect port
- ShipX: `ContainerNo == "1"` gate misses invoices for multi-container
  orders shipped separately (combined.md:5709). This is a real ShipX bug.
- shioai `CommercialInvoiceServiceImpl.render(orderNo)` is per-order,
  reads `order_customs` once per order — this defect **does not port**.
  Good.

### 5.5 — Manual commercial-invoice-typing screen absent
- ShipX has `CommInvoiceController` for hand-typed invoices
  (combined.md:5768, retired in ShipXSync). shioai has no equivalent.
- Verdict: **do not build** — ShipXSync retired it; regeneration via
  order/customs edit + `POST /orders/{n}/regenerate` is the modern path.

### 5.6 — Filesystem drop folders / ETD paths not needed
- ShipX writes to fixed `C:\ShipxAutoCommInv\`, `C:\ShipxETD\`,
  `C:\SCANdocs\` etc. shioai has zero `C:\`-style hardcoded paths in
  main sources (`grep C:\\` on `backend/src/main` returns only spurious
  hits in FedEx/DHL sample JSON and DTC docs). ShipX's DtcInvoiceRegenSweeper
  (feature flag `DtcInvoiceRegenSweeper`) has no equivalent because
  shioai does not persist invoice PDFs to disk — the endpoint renders on
  demand. Good.

### 5.7 — Steelcase kit-component invoice logic absent
- ShipX has bespoke Steelcase parent/child logic and MHR210-to-China
  `unit_cost` rule (combined.md:5720-5731). shioai does not
  replicate — the customs items table is straight and unbranched.
- Verdict: correct simplification unless a Steelcase-specific customer
  onboards; leave.

## 6. Hardcoded findings (§17)

| # | Symptom | Location | Fix |
|---|---|---|---|
| H17-1 | `DOC_TYPES` closed set of 2 values blocks routing the packing slip and any future document type through the printer stack. | `backend/src/main/java/com/multiship/backend/service/printing/PrinterService.java:53` (referenced at :191). Also gated at `OrderController.java:1521` and `:1624`. | Add `"PACKING_SLIP"`, `"RETURN_COVER"` to the set + the two controller checks. Consider promoting to `label_templates.template_type` DISTINCT + `printer_assignments.doc_type` FK (dedicated registry). ~15 LoC. |
| H17-2 | Commercial-invoice layout hardcoded — `LabelTemplate` for `COMMERCIAL_INVOICE` type is documented but ignored. | `CommercialInvoiceServiceImpl.java:167-183` (`render`) never calls `labelTemplateService.resolve`. Contrast `PackingSlipServiceImpl.java:113-115`. | Plumb template resolution + honour `primaryColor`, `headerText`, `footerText`, `logoBase64`. ~40 LoC, no migration. |
| H17-3 | Invoice-copies default fallback of 1 lives in Java (not per-tenant setting). | `InvoiceCopiesService.java:37` `DEFAULT_COPIES = 1`. | Acceptable — matches ShipX's historical behaviour. If per-tenant default wanted, add `tenant_settings` key. No change recommended. |
| H17-4 | `RestExternalConnector.writebackClearPath = "/shipments/{tracking}"` hardcoded default. | `RestExternalConnector.java` config `RestExternalConfig.java:79`. | Already configurable via `RestExternalConfig` DB row per connection — not a defect, just note that the default is fine. |
| H17-5 | FE template type list includes `SHIPPING_LABEL` which backend does not render / accept. | `LabelTemplatesListPage.tsx:36`, `LabelTemplateEditorPage.tsx:34`. Backend `LabelTemplate.java` javadoc omits it. | Either drop `SHIPPING_LABEL` from the FE list (carrier labels are non-customisable per javadoc) or add renderer + backend enum. Prefer drop — 2 LoC. |

## 7. ShipX §18 behavior

**Cancel switch by shipment type** (combined.md:5960-5977):

| Type | Postgres SP | NDS writeback (Mode=Live only) | Login |
|---|---|---|---|
| Quick / BackOrder / Tax | `spx_updatestatus(id,'CancelShipment')` | `SetTrackingQ` blank | Client |
| Billable | `spx_updatebillablestatus(id)` (all rows same batch) | `SetTrackingQbillable` blank (one box only!) | Client |
| Manual Ship / any note | `spx_updatestatus(id,'CancelManualShipment')` | `UpdateManualTrackingQ` (VOID_YN='Y') | Production |
| DTC | `spx_updatedtcshipment(...,'ShipCancel')` then `spx_updatestatus(...,'CancelDTCShipment')` | `removeTrackingOE_TRACK` + `SetTrackingOE_SHIP` blank | Client |
| Bulk Mailer | `spx_updatestatus(...,'CancelBULKShipment')` | None | — |
| Return | `spx_updatestatus(...,'Cancelreturn')` | `UpdateManualTrackingQ` when note exists, **without Mode check**; USPS also `removeTrackingOE_TRACK` before void result known | Prod/Client |

Rules (combined.md:5989-6005):

- Nothing changes in Postgres or NDS if the carrier refuses.
- UPS multi-package: only one package voided in ShipX; ShipXSync voids
  the whole DTC shipment.
- USPS refunds take weeks and are untracked.
- Bulk void = loop of single-cancel.

**Reprint** (combined.md:6010-6040):

- Sends the saved ZPL (`SPX_tblShipmentDetails.ZPLCommand`) to the printer.
- Never touches NDS; DTC-only side effect is `SPX_tblDtcBatch.IsPrint=true`.
- **Only carrier 30 (inactive)** re-fetches from Stamps — real USPS 31
  uses saved ZPL. 473/5338 DTC USPS rows have no saved ZPL → cannot reprint.
- No retention window — everything with saved ZPL is always reprintable.

**Re-ship** (combined.md:6046-6120):

- **Shipment History re-ship** (ShipX only, disabled in ShipXSync):
  re-reads containers from NDS, calls the carrier, updates the same row
  or adds MP rows, writes new tracking to CLIPPER (Mode=Live), prints.
  **Fire-and-forget** — FedEx re-ship not awaited. Invoice goes to
  label printer (known bug).
- **DTC "void and re-ship"** (both builds): fresh label for same tote.
  ShipX does NOT void old label first → double-charge risk. ShipXSync
  voids first (`ShipXSync BALShiplist.cs:L3973`).
- **DtcErroredSweeper** (ShipXSync feature flag) retries errored totes
  every 15 min.

## 8. shioai cancel/reprint/reship counterpart

**Cancel**: `VoidServiceImpl.java:82-296` (called from
`POST /api/v1/orders/{orderNo}/void`, `VoidController.java:39`):

1. `@Transactional` + `findByOrderNoForUpdate` PESSIMISTIC_WRITE lock
   (Sprint 51 R1, `VoidServiceImpl.java:95`). Idempotent short-circuit
   on `status == VOIDED` (`L103`).
2. Tenant guard (Sprint 50 Tier 0.5 PR E, `L91-93`).
3. Resolves canonical carrier (with account-based fallback when
   ship-via names a service not a carrier, `L108-118`).
4. Multi-batch void: reads `ShipmentBatch` list, calls
   `connector.voidShipment` per master tracking (`L167-217`). All-or-nothing
   flag flip.
5. On refusal: appends `VOID_REFUSED` to `LabelHistory`, logs
   `LABEL_VOID_REFUSED` (`L222-236`). **No DB flag flip**.
6. On success: `status=VOIDED`, `isLabelGenerated=false`, appends
   `VOIDED` to label history (`L240-248`), logs `LABEL_VOIDED` with
   reversed carrier + billable amounts (`L254-265`).
7. **NDS writeback**: `writebackDispatcher.dispatchOnClear` (`L277`) —
   async, per-tenant flag matrix, DTC path deletes OE_TRACKING+nulls
   OE_SHIP_CONTAINER (`NdsShipmentOracleWriter.java:434`), CLIPPER +
   OE_TRACKING + TB_MANUAL_SHIPMENT VOID_YN='Y' (`.java:361-368`). Full
   V89/V90 flag gating; per-source (MANUAL/BULK/API/WMS/DTC) + per-channel
   (D2C/B2B).
8. **Distinct account-gone message**: if the billed account is gone,
   the operator gets an actionable re-add prompt (`L138-144`).
9. Sprint 49 Tier 2 fix: real accountNumber threaded through, not "ACCOUNT"
   placeholder (`L181-188`).

**USPS_DIRECT G-audit** shipped V60 `usps_void_pending_state` +
`UspsDirectVoidReconciliationService.java` — reconciles Stamps-side
refunds; per-memory `project_usps_direct_integration`.

**STAMPS_COM S-audit** shipped V64 `import_batch_cancel_requested_at`
durable cancel signal — per-memory `project_stamps_com_audit`.

**Reprint**: `CarrierConnector.reprintLabel` default returns
`NOT_SUPPORTED` (`CarrierConnector.java:593`). **Only StampsConnector
SERA flavor implements it** (`StampsConnector.java:2035-2109` — GET
`/sera/v1/labels/{label_id}` with base64 output). SWSIM flavor + FedEx +
UPS + DHL + USPS_DIRECT all inherit the NOT_SUPPORTED default —
operators reprint from the persisted `label_batch.master_label_url` /
`label_package.label_file_path` bytes (which never expire — see PR #550).

No user-facing reprint endpoint on `OrderController` — bulk print
(`/documents/print`) re-serves the persisted labels which is functionally
"reprint" already. Distinct "Reprint" action / audit event does not exist.

**Reship / regenerate**: `POST /api/v1/orders/{orderNo}/regenerate`
(`OrderController.java:1962`). Only for **failed / pending** orders —
409 refuses when `isGenerated=true && status=GENERATED` (`.java:1972-1979`).
Reuses same order number so the URL is stable. Idempotency via
`executeOrReplay` (`.java:1981`). **No** re-shipping of an
already-generated order after void — the flow is void → regenerate
(effectively two calls, two audit events). No fire-and-forget hazard
(unlike ShipX).

## 9. Gaps (§18)

### 9.1 — Reprint endpoint / audit event
- ShipX has a first-class Reprint button that mutates
  `SPX_tblDtcBatch.IsPrint=true` and produces a separate LOG entry.
- shioai currently: bulk `documents/print` re-serves persisted bytes but
  emits `LABEL_GENERATED` (or nothing) — no distinct `LABEL_REPRINTED`
  audit event, so "how many times has this label been printed?" needs
  a `document_print_event` log scan (V80 shipped this — good, but no
  UI for it yet).
- Fix: emit `LABEL_REPRINTED` audit event and one operator-facing
  reprint count on the order details drawer. ~20 LoC + FE surface.

### 9.2 — Reprint from carrier (fallback when local bytes gone)
- ShipX has a carrier-30 stamp-refetch path (broken). shioai has SERA
  reprint (`StampsConnector.java:2035`) for USPS/SERA-flavor only.
- Gap: FedEx / UPS / DHL / USPS_DIRECT do not implement `reprintLabel`
  → the default `NOT_SUPPORTED` returns. Real defense-in-depth for lost
  local bytes.
- Fix: implement per-carrier reprint (FedEx `label/reprintOnly`, UPS
  `label recovery`, DHL none, USPS_DIRECT via SERA already). Bigger
  effort; only if the platform ever loses persisted label bytes.

### 9.3 — Post-generation re-ship flow (void → new label same order)
- ShipX Shipment History has `Reship` for a failed shipment (buggy but
  it exists). shioai has `/regenerate` for failed only, guarded 409 on
  generated (`OrderController.java:1972`).
- Missing: "cancel this good shipment and issue a new label on the same
  order" as one atomic UX. Operators today do POST /void → POST
  /regenerate (two calls), which does work but shows two events instead
  of one "reship" event.
- Fix: add `POST /orders/{orderNo}/reship` that internally calls void
  then regenerate, emitting one `LABEL_RESHIPPED` audit. ~40 LoC,
  reuses existing services. Feature-flag off by default.

### 9.4 — Bulk void
- ShipX DTC batch cancel loops the single cancel. shioai has no
  `POST /orders/void-batch` — the FE would call N × single void.
- Fix: batch endpoint with the same per-order guards as single void.
  ~30 LoC. Only add if UI needs it.

### 9.5 — DTC-order-wide clear defect port check
- ShipX DTC cancel deletes ALL BATCHSHIP rows for the order + suffix
  and blanks CONTAINER_ID on ALL OE_SHIP_CONTAINER rows for the order
  (`removeTrackingOE_TRACK` + `SetTrackingOE_SHIP`, combined.md:6203-6220).
  If the order has multiple totes shipped, cancelling one wipes NDS
  tracking for all.
- shioai `NdsShipmentOracleWriter.dtcClearShipment` (`.java:434-483`)
  loops per orderNo, deletes `WHERE TENANT_ID + ORDER_NO` (no tote key,
  no ORDER_SUFFIX filter on DELETE) and updates OE_SHIP_CONTAINER
  `WHERE TENANT_ID + ORDER_NO` (no suffix). **Same defect present**.
- Fix (only if orders can have multiple DTC totes per suffix): add
  `ORDER_SUFFIX` + tracking-scoped predicate to the DELETE + UPDATE.
  Migration: none (query change only). Risk: medium — need to confirm
  the DTC-per-suffix multi-tote scenario is real on this platform
  before touching a shared query.

### 9.6 — Billable-batch cancel partial-clear
- ShipX billable cancel: `spx_updatebillablestatus` cancels the whole
  batch in Postgres, but `SetTrackingQbillable` clears CLIPPER only for
  the clicked box (combined.md:6229-6232). Other boxes keep tracking.
  ShipX bug.
- shioai has no billable-batch model equivalent (batches map cleanly to
  `ShipmentBatch` rows, and `VoidServiceImpl` iterates all of them
  `L167-217`). Confirmed **defect does not port**.

### 9.7 — Return cancel ignores Mode
- ShipX `cancelReturn` writes VOID_YN='Y' from a test system too
  (combined.md:5981). shioai does not have a distinct "Return" model
  today; return cancels flow through `VoidServiceImpl` and are gated by
  the connection's Mode flag on the writeback. **Defect does not port**.

### 9.8 — Reship "fire and forget" defect port check
- ShipX `ProcessReShipments` not awaited (combined.md:6111-6113 —
  screen answers "1" before the label exists). shioai `/regenerate` is
  fully synchronous via `carrierService.generateManualLabel` — the
  response body is the label result. **Defect does not port**.

### 9.9 — DTC void-and-reship double-charge
- ShipX does not void old label before re-shipping DTC (combined.md:6116-6119).
  ShipXSync fixed it in `L3973`.
- shioai: no automatic void-and-reship path exists; `/regenerate` is
  failed-only (409 on generated). Operator must void first. **Not
  applicable** — the way we wire it prevents the class of bug.

### 9.10 — Errored-order sweeper
- ShipXSync `DtcErroredSweeper` retries every 15 min behind
  `DtcErroredSweeper` flag. shioai does not have an equivalent for
  order-import failures beyond the USPS_DIRECT queue's own retry.
- Verdict: optional. Only if we see chronic transient carrier failures
  ops complains about.

## 10. Hardcoded findings (§18)

| # | Symptom | Location | Fix |
|---|---|---|---|
| H18-1 | Reprint capability per-carrier is closed at the interface default; adding a new carrier reprint requires editing `CarrierConnector` + each `Impl`. | `CarrierConnector.java:593` (default `NOT_SUPPORTED`); only `StampsConnector.java:2035-2109` overrides. | Fine as an SPI — no fix. Track in per-carrier PRs. |
| H18-2 | DTC clear query scope hardcoded to `TENANT_ID + ORDER_NO` (missing `ORDER_SUFFIX`, no tote key). | `NdsShipmentOracleWriter.java:447-454` (DELETE) and `:460-466` (UPDATE). | Add `ORDER_SUFFIX` filter (already on the write path L515). If per-tote scope required, thread tracking through `WritebackClearRequest`. ~10 LoC + tests. |
| H18-3 | Void tri-state "IN_FLIGHT" not implemented — carrier HTTP held inside DB tx (see javadoc `VoidServiceImpl.java:75-81`). | `VoidServiceImpl.java:82-296`. | Deliberate ponytail deferral per javadoc. Only revisit when carrier calls are slow enough to cause DB pool starvation. No change now. |
| H18-4 | External-system writeback `DEFAULT_CONNECTION_NAME = "nds-default"` string literal. | `ExternalSystemWritebackDispatcher.java:58`. | Fine as a well-known seed. V78 seeds the row. Do not extract. |
| H18-5 | Reprint doesn't emit distinct audit event; bulk `documents/print` records via `DocumentPrintLog` but single-order print does not. | `OrderController.java:1583-1585` (record path) vs the `/orders/{n}/packing-slip`, `/shipment-documents` handlers `:1421,:1456` which do not. | Call `documentPrintLog.record(...)` in the two single-order handlers. ~4 LoC each. |
| H18-6 | Regenerate 409 message hardcoded; no i18n needed but the "regenerate is only for failed/pending orders" text is duplicated in FE. | `OrderController.java:1974-1978`. | Leave — matches error-message pattern across the codebase. |

## 11. Prioritized fix-plan

Ranked by ratio of user impact / fix size.

| Pri | Item | LoC | Migrations | Risk | Rationale |
|---|---|---|---|---|---|
| **P0** | H17-1: register `PACKING_SLIP` (and `RETURN_COVER`) in `DOC_TYPES` + two controller clamps so branded packing slips can be routed to a printer through the assignment matrix. | ~15 | None | Low | Immediate unblock: packing slip PDF exists, endpoint exists, printer stack exists — one Set + two contains checks are all that stand between them. |
| **P0** | H18-5: emit `LABEL_REPRINTED` (via existing `DocumentPrintLog.record`) on the single-order document endpoints so ops can answer "did we print this?". | ~10 | None | Low | Matches ShipX §18 audit granularity; V80 already gave us the table. |
| **P1** | 9.3: `POST /orders/{orderNo}/reship` = void + regenerate as one call + one audit event. | ~40 | None | Low | Two-call today works, one-call is the natural UX; reuses existing services. |
| **P1** | H17-2: honour `LabelTemplate` on `COMMERCIAL_INVOICE` — plumb `resolve(tenant, "COMMERCIAL_INVOICE")` into `CommercialInvoiceServiceImpl` (colour, header, footer, logo). | ~40 | None | Low | Model + FE already expect this; renderer is the last mile. |
| **P1** | H17-5: drop `SHIPPING_LABEL` from FE `TEMPLATE_TYPES` (carrier labels are non-customisable per `LabelTemplate.java:11-13`). | 2 | None | Low | Removes a UI dead-end that would only ever save a template no renderer reads. |
| **P2** | 9.1: distinct "Reprint" action on the order drawer, reading `document_print_event` (V80) for the count. | ~30 BE + ~40 FE | None | Low | UX polish — the data is already logged. |
| **P2** | 9.5: DTC clear NDS scope — add `ORDER_SUFFIX` filter (mirrors write path). | ~10 | None | Med | Only touch after confirming multi-tote-per-suffix DTC orders exist on this tenant; shared query. |
| **P3** | 9.4: bulk void endpoint. | ~30 | None | Low | Only if FE wants a Cancel-many surface — otherwise loop-in-FE is fine at low volume. |
| **P3** | 9.2: per-carrier `reprintLabel` implementations (FedEx / UPS / DHL). | Large | None | Med | Defense-in-depth for lost persisted bytes; not needed today (never expire per PR #550). |

## 12. Open questions

1. **Multi-tote DTC**: does this platform have orders where the same
   (`ORDER_NO`, `ORDER_SUFFIX`) has multiple totes shipped, such that
   cancelling one should not wipe the others' BATCHSHIP tracking? If no,
   H18-2 fix is a no-op and can be dropped. If yes, the write path
   (`NdsShipmentOracleWriter.java:499-527`) already loops per package
   with a suffix — the DELETE/UPDATE needs the same key.
2. **`SHIPPING_LABEL` template type**: was this added to the FE list on
   purpose (planned carrier-agnostic label overlay) or by mistake? If
   the former, backend model + renderer are missing. If the latter,
   drop the FE entry.
3. **Reprint audit**: is `document_print_event` (V80) already surfaced
   anywhere on the FE, or is it write-only? If the former, H18-5 is
   done. If the latter, add reading path + a reprint-count badge.
4. **"Reship" naming**: `/regenerate` today rebills a failed order (same
   number). If we add a distinct "cancel + issue a new label on the
   same order" (§9.3), do we want it as `/reship` or as an option flag
   on `/regenerate`? The former is clearer.
5. **Bulk void demand**: does the operator UI plan to expose a
   "Cancel N selected" action for the orders workspace? If yes,
   §9.4 becomes P1.
6. **Commercial-invoice template Phase 2 (layout_json)**: `LabelTemplate`
   already has a `layoutJson` slot (model `L84-L96`) — is the intent to
   drive the CI renderer from the same layout tree the packing-slip
   editor produces, or keep CI on a fixed regulatory-safe layout with
   only branding knobs? Regulatory-safe suggests only branding knobs
   (H17-2 fix) and leaving `layoutJson` for packing-slip / return cover.

## Appendix — files referenced

Backend:

- `backend/src/main/java/com/multiship/backend/service/printing/PrinterService.java`
- `backend/src/main/java/com/multiship/backend/service/CommercialInvoiceServiceImpl.java`
- `backend/src/main/java/com/multiship/backend/service/PackingSlipServiceImpl.java`
- `backend/src/main/java/com/multiship/backend/service/VoidServiceImpl.java`
- `backend/src/main/java/com/multiship/backend/service/InvoiceCopiesService.java`
- `backend/src/main/java/com/multiship/backend/service/ndsshipment/NdsShipmentOracleWriter.java`
- `backend/src/main/java/com/multiship/backend/service/externalsystems/writeback/ExternalSystemWritebackDispatcher.java`
- `backend/src/main/java/com/multiship/backend/service/carriers/CarrierConnector.java`
- `backend/src/main/java/com/multiship/backend/service/carriers/StampsConnector.java`
- `backend/src/main/java/com/multiship/backend/controller/OrderController.java`
- `backend/src/main/java/com/multiship/backend/controller/VoidController.java`
- `backend/src/main/java/com/multiship/backend/controller/InvoiceCopiesController.java`
- `backend/src/main/java/com/multiship/backend/model/InvoiceCopies.java`
- `backend/src/main/java/com/multiship/backend/model/LabelTemplate.java`
- Migrations: V65 (printer_registry), V70 (invoice_copies), V77-V78
  (external_systems + nds seed), V80 (document_print_event), V89-V94
  (writeback flags).

Frontend:

- `multiship-react/src/components/LabelTemplatesListPage.tsx`
- `multiship-react/src/components/LabelTemplateEditorPage.tsx`
- `multiship-react/src/components/PrintersPage.tsx`
- `multiship-react/src/components/AssignmentMatrix.tsx`
- `multiship-react/src/components/LabelDocumentPage.tsx`

ShipX docs:

- `combined.md` §17 (5577-5922), §18 (5923-6270)
- `deepdive/17-printing-invoices.md`, `deepdive/18-cancel-reprint.md`
- `appendix-B.md` (cancel/void SPs at L48, L50, L54, L2137-L2626)
- `appendix-C.md` (NDS refs at L32-L38)
