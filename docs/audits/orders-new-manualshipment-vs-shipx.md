# /orders/new (Manual Shipment) vs ShipX Manual Ship — Gap Analysis + Fix Plan

Read-only audit. shioai FE + BE at `dev` HEAD, ShipX docs from
`D:/GIT-REPOS/ShipX/docs/handover/source/`. Cited FE lines are against
`multiship-react/src/components/NewShipmentPage.tsx` (4,860 LoC).

---

## 1. Overview

**Manual Ship in ShipX**: one-off shipments with no NDS order — samples,
replacements, ad-hoc parcels, and Return Ship labels. All fields typed by
the operator; ship-from = the client's ShipX address; carrier is asked for
one label (**one package** — no MPS on the manual path in ShipX);
`SPX_tblShipmentDetails.Ismanual=1` on save; a `MAN<n>` order number is
generated in the SP; NDS gets **one** row per shipment via
`INSERT INTO TB_MANUAL_SHIPMENT` with `ERROR_MODE ∈ {M, R, Q}`.
Ref: `part-C.md:296-368`, `deepdive/12a-manual-ship.md`.

**shioai `/orders/new`**: canonical shipment-creation surface, superset of
ShipX Manual Ship + Return Ship + parts of Quick Ship + rate shopping +
customs wizard + DG + bulk MPS + auto-split + save-a-failed-order. Same
page also acts as **`fixOrder`** entry (`NewShipmentPage.tsx:159-169`,
regenerate flow POST `/orders/{n}/regenerate`,
`OrderController.java:1962-1988`). NDS-scan prefill (`.X<containerId>` /
`.Y<batchId>`) mimics the "start from an NDS order" experience without
requiring a Manual page to look up NDS at all — a functional overlap with
ShipX Quick Ship rather than Manual Ship, but landed in the manual UX to
keep one entry surface.

**Divergence vs Quick Ship**: neither system's "manual" surface treats the
NDS order as authoritative; both create a *new* label + order in the local
system with the option to notify NDS. shioai has no "Quick Ship" —
`/orders/new` covers both flows because NDS reads happen only on demand
(scan-triggered) and default to blank.

---

## 2. ShipX behavior — extracted facts

### 2.1 DB tables

| Table | DB | Role | Ref |
|---|---|---|---|
| `SPX_tblShipmentDetails` (`Ismanual=1`) | ShipX Postgres | Master row per manual label; carries `OrderNo="MAN<n>"`, tracking, ZPL, status, error | `12a-manual-ship.md` step 5 |
| `SPX_tblCommercialInvoice` | ShipX Postgres | Intl item lines (JSON insert via SP) | `12a-manual-ship.md` step 5 |
| `SPX_tblReturnDetails` | ShipX Postgres | Return labels, `REN-<id>` numbering | `part-C.md:335` |
| `TB_MANUAL_SHIPMENT` | NDS Oracle Production | One row per manual/return/Q-note shipment | `12c-nds-manual-list.md`, `appendix-A.md:6877-6898` |

### 2.2 Stored procedures + NDS DML

| SP / SQL | What it does | Ref |
|---|---|---|
| `spx_proshipmentmanual` | INSERT `SPX_tblShipmentDetails` + allocate `MAN<n>` via `MAX(substring(...))+1` | `appendix-B.md:1750-1768` (B.31) |
| `spx_proshipment_international` | Same as above + iterate JSON items → `SPX_tblCommercialInvoice` INSERTs; **no COMMIT** | `appendix-B.md` B.27 |
| `spx_proshipmentReturn` | INSERT `SPX_tblReturnDetails` + allocate `REN-<n>` | `part-C.md:368` |
| `DALShiplist.SetManualTrackingQ` (A-255) | String-built `INSERT INTO tb_manual_shipment(...)`; injection-risky | `appendix-A.md:6877-6898` |
| `DALShiplist.UpdateManualTrackingQ` (A-256) | String-built `UPDATE tb_manual_shipment SET VOID_YN='Y' WHERE TENANT_ID=<x> AND TRACKING=<t>` | `appendix-A.md:6900-6916` |

### 2.3 Business rules

- Domestic requires country=US; international requires ≠US + ≥1 item, total ≥1.
- FedEx retries once with blank third-party account on refusal.
- UPS retries with blank third-party only when carrier msg starts `120413` / `120412`.
- USPS retries without delivery confirmation on refusal (`part-C.md:325`; `12a-manual-ship.md` step 4).
- Missing email → `support@thbred.com` (FedEx + UPS only; USPS branch has no fallback).
- Missing phone → `+16167723513`.
- Void: cancel with carrier, `spx_UpdateStatus` sets `Status=false, ErrorMessage='Cancelled'`, clears ZPL/TrackingID; if Mode=Live then run `UpdateManualTrackingQ` (VOID_YN='Y').
- FedEx returns write NDS `R` row **only if a note was typed**; UPS returns always write it; USPS returns unsupported (`12c-nds-manual-list.md` step 1 table).
- Return Ship supports FedEx + UPS only; USPS not supported.
- Every value on the NDS `INSERT` is string-interpolated: an apostrophe in a name kills the write.

### 2.4 NDS manual shipments list — lifecycle

- No SELECT anywhere in ShipX/ShipXSync on `TB_MANUAL_SHIPMENT`. The
  "list" everyone means lives inside Thoroughbred NDS itself — outside
  the ShipX codebase (`12c-nds-manual-list.md:1-10`).
- ShipX side stand-in: **Shipment History** with `Ship Type = Manual`
  filter (`SPX_tblShipmentDetails.Ismanual='1'` + date range,
  `DALShipHistory.cs:L1949-L2010`). Void, Re-Print, Retry (`ReMship`)
  live here. Void is HIDDEN on returns (see `Getreturnistory1`).
- Row taxonomy:

  | ERROR_MODE | Written by | ORDER_NO |
  |---|---|---|
  | M | Manual Ship domestic + international + retries | MAN<n> |
  | R | Return Ship (FedEx w/note; UPS always) | REN -<id> |
  | Q | Quick Ship / BackOrder / TaxShipments (**only when note typed**) | NDS order number |

- Void gotchas: `UPDATE` matches on `TENANT_ID + TRACKING` only — no
  `ERROR_MODE` / `ORDER_NO` guard. `cancelReturn` skips the Live-mode
  check; `USPS refusal is not saved to history` because the branch has
  no `else` for `Status=false` (`12a-manual-ship.md` step 8).
- `MAN` numbers race (`MAX()+1` without lock); `ContainerId` collides
  under parallel submit and doubles as the FSM `.in` filename.

---

## 3. shioai counterpart at `/orders/new` — by feature area

Backend entry point: `POST /orders/manual-label` (net-new) /
`POST /orders/{n}/regenerate` (fix-in-place), both delegate to
`CarrierServiceImpl.generateManualLabel(ManualShipmentRequest, UserDetails, Integer)`
(`OrderController.java:1930-1988`, `CarrierServiceImpl.java:946-2400`).

### 3.1 FE section layout (`NewShipmentPage.tsx`)

Not a tab UI — one long vertical form with anchor-linked `SectionCard`s
(`id="sec-*"`):

| Section id | Title | Line | Covers |
|---|---|---|---|
| `sec-shipment` | Shipment | 3025 | NDS scan input (`.X`/`.Y`), client picker, warehouse picker, mode toggle (SHIPMENT/RETURN), currency, label-image + stock (FedEx) / label-image-format (UPS/DHL/USPS), FedEx pickup type |
| `sec-addresses` | Ship from · sender / Return from · customer | 3401 | Sender `AddressBlock`, internal note (V76, 500-char) |
| _(no id)_ | Ship to · recipient / Return to · your address | 3431 | Recipient `AddressBlock`, importer-broker banner + validate |
| `sec-service` | Account & service | 3617 | Carrier account picker, service dropdown, rate-picker modal, incoterms, clearanceOption, dutiesAccount, EEI (FTR/AES/exportRef), reasonForExport, signature option, insured value |
| `sec-packages` | Package & weight | 3832 | Package preset picker, custom L/W/H, weight/unit, MPS `extraPackages[]` + bulk-entry (duplicate, add-N, apply-box-1) |
| `sec-customs` | Importer of record & customs broker | 4167 | Per-shipment importer/broker override (opens `ShipmentPartiesOverrideModal`) |
| `sec-items` | Items · commercial invoice | 4277 | Line items, `HsCodeCombobox`, `PasteItemsModal`, per-item `boxSeq` assignment, `CustomsWizard`, `LandedCostModal`, `DangerousGoodsWizard` |
| _(no id)_ | Custom fields | — | `CustomFieldsSection` reads tenant-configured fields |
| _(no id)_ | Validation | — | `ValidationChecklist`, carrier-side `Validate shipment` |

### 3.2 Backend service flow (`CarrierServiceImpl.generateManualLabel`)

Order of operations (`CarrierServiceImpl.java:946-2400`):

1. **Null-guard** recipient (line 967).
2. **`internalIdempotencyKey`** normalize (974).
3. **USPS_DIRECT routing** on regenerate only (993-999) — enqueues on 55/hr queue when routed.
4. **`clientCode` tenant clamp** (1003).
5. **Return-mode gates** (1017-1059): resolves `CarrierAccountRef.returnScope` (DOMESTIC_ONLY / DOMESTIC_AND_INTERNATIONAL / DISABLED), then **clamps recipient block to `Client.effectiveReturnAddress()`** — operator-typed recipient is ignored on returns.
6. **Recipient completeness gate** (1061-1066), **weight gate** (1067-1070), **channel enum gate** (1076-1084).
7. **Bill-to-account resolution** (1085-1192): `accountId` → `accountNumber+carrier` → error; ownership check; active check; `billToNumber` is the typed number wins-over stored.
8. **Ship-to allowlist gate** (1099).
9. **Warehouse resolution** (1109-1120) — merges warehouse address into `from` block.
10. **Carrier resolution** (1122-1136), canonicalise (`resolveCanonicalCarrierCode`).
11. **Service + preset lookup** (1197-1200) via `shippingConfigService`.
12. **Packaging validator** (1210-1252) — data-driven from preset rows (max weight, L/W/H, girth, dim weight); parcel-limit check via `carrierLimitService.resolveLimit`.
13. **Routing rules** (1259-1372) — `routingRuleService.evaluate` may BLOCK or REROUTE (service, warehouse, or both); may swap carrier + connector + account.
14. **Allowlist gates** on the resolved service + preset (1376-1388).
15. **`PackagingCompatibilityGuard.assertCompatible`** (1396) — enforces `service_package` M2M link.
16. **US-territory service allowlist** (1412-1437) — pre-flight lane check.
17. **Order number allocation** (1466-1467): `orderRepository.nextManualOrderNo()` → `label_batch_order_no_seq` (V53). Reused as `MAN{n}` on the label wire.
18. **Cutoff shift** (1471-1494) — `CutoffShiftService.resolveShipDate` sets `shipmentRequest.shipDateOverride`.
19. **DTO build** — `buildManualShipmentRequestDto` (1489-1494), stamps PO=`MAN{orderNo}`, DEPT=`custNo`.
20. **Cross-border customs gate** (1512-1589): recognises US↔PR/VI/GU/… as cross-border; requires importer taxId on BUSINESS profiles; force-picks incoterms/reasonForExport from `ClientCustomsProfile` or `DAP`/`SALE`; requires `dutiesAccount` when `THIRD_PARTY`; builds `IntlShipmentBlockDTO`.
21. **MPS + commodity split** (1591-1656) — `SplitStrategy` prompt when over-cap; splits into N sub-requests; loops.
22. **Connector loop** (1662-1734) — `AuthRetry.withAuthRetry` per sub, `StampsSeraAuthContext` for Stamps, `ResidentialRequiredServices` gate, return-email gate.
23. **Rate-limit `CarrierRateLimitException`** → 429 with `Retry-After` (1735-1744).
24. **Carrier-exception path** (1745-1926) — persists `Order` + `OrderTracking` as `ERROR` in REQUIRES_NEW tx; saves `order_customs` on intl; audits `CARRIER_REJECTED`; returns 502.
25. **Success persist** (1930-2400): `Order` (`isManual='Y'`), `OrderTracking`, `ShipmentBatch`+`LabelPackage` per sub-request, `order_customs` (via `CustomsServiceImpl.upsertCustoms`), applies markup via `resolutionService.applyMarkup` in REQUIRES_NEW, computes cutoff-next-business-day, persists label (base64) via `persistLabelPath`, audits `LABEL_GENERATED`/`LABEL_REGENERATED`.
26. **NDS writeback** — kicks off via `NdsOracleConnector.writeShipment` → `NdsShipmentOracleWriter.writeShipment` (called by external-systems framework, not inline in this method — see § 6).

### 3.3 Connectors

`service/carriers/{FedExConnector, UpsConnector, DhlConnector, StampsConnector, UspsDirectConnector}.java`. All implement `CarrierConnector` (line 987 shows `configuration()` with `defaultServiceType`, `defaultPackageType`).

### 3.4 NDS prefill (`.X`/`.Y` scan)

- FE: `NewShipmentPage.tsx:282-299` — `ndsScan`, `ndsLoading`, `ndsBanner`, `ndsQueue`, `ndsActiveIdx`, `senderFallbackPendingRef`, `ndsStdReplacementErpCode`.
- Multi-scan: `NewShipmentPage.tsx:2865+` — comma/space-separated tokens, each looked up, deduped by orderNo, first loaded, rest queued as chips.
- API: `multiship-react/src/api/ndsShipmentService.ts:135-143` → `GET /manual-shipment/nds-lookup?scan=&clientCode=`.
- BE: `NdsShipmentLookupController.java:44-83` → `NdsShipmentLookupService` → external-systems framework `NdsOracleConnector`.
- Sticky default sender: `NewShipmentPage.tsx:137-141` (`MultiShip Fulfillment / 350 5th Ave New York NY 10118 US`) — **hardcoded**.

---

## 4. Gaps

### 4.1 Functional deltas

| # | ShipX behavior | shioai behavior | Gap | Severity |
|---|---|---|---|---|
| G-01 | `TB_MANUAL_SHIPMENT` is the NDS-side list surface | shioai writes rows (`NdsShipmentOracleWriter:275-283`) but **never SELECTs** — no read/refresh/view surface exists on our side either | Parity: same read gap. **NOT a gap, but preserving as a doc note.** | INFO |
| G-02 | Return Ship = FedEx + UPS only | Any carrier account with `returnScope ≠ DISABLED` allowed (`CarrierServiceImpl:1017-1036`, V95) | **Better than ShipX** — accept, no gap | INFO |
| G-03 | Return Ship USPS unsupported | USPS return allowed if `return_scope` set | Confirm carrier actually supports (`StampsConnector`/`UspsDirectConnector`) — audit-worthy | LOW |
| G-04 | Return email substitute (`support@thbred.com`) | Return-email is a **hard error** (`CarrierServiceImpl:1714-1720`) | Stricter — safer default. Confirm operations sign-off | LOW |
| G-05 | Failed manual shipment shows in Shipment History → Retry via `ReMship`/`Remanual` | Failed shipments saved as `Order status='ERROR'` in REQUIRES_NEW tx (`CarrierServiceImpl:1745-1857`); Retry = `/orders/new?fixOrder={n}` → `regenerate` (fix-in-place, same order number) | Parity via different mechanism | INFO |
| G-06 | Void from history → carrier cancel + `spx_UpdateStatus` + `UpdateManualTrackingQ` (VOID_YN='Y') | Void via `VoidController` (grep) + `NdsShipmentOracleWriter.clearShipment` → `UPDATE TB_MANUAL_SHIPMENT SET VOID_YN='Y' WHERE TRACKING=:tracking` (line 361-372) | Parity | INFO |
| G-07 | `Q` row is written on Quick Ship/BackOrder when a note is typed | shioai writes `Q` row when `note` is non-blank in `NdsShipmentOracleWriter:189-196` — but every /orders/new is treated as manual, so the M row already covers it, and Q duplicates on outbound with note | **Bug candidate**: outbound with note writes both `M` and `Q` rows; ShipX only wrote `Q` on Quick Ship/BackOrder, not on Manual Ship. | HIGH |
| G-08 | Manual Ship = 1 package only | Manual = full MPS + commodity split (`CarrierServiceImpl:1591-1656`) | Superset | INFO |
| G-09 | `MAN<n>` allocated by SP `MAX()+1` (race-prone) | `label_batch_order_no_seq` (`OrderRepository:66-74`) — race-free | Superset | INFO |
| G-10 | International commercial invoice items via `SPX_tblCommercialInvoice` JSON insert | `order_customs` + `order_customs_item` via `CustomsServiceImpl.upsertCustoms` in same tx (`CarrierServiceImpl:2210-2211`) | Parity | INFO |
| G-11 | Domestic requires country=US strictly | shioai is intl-any-non-matching-origin (`CarrierServiceImpl:1516-1517`); origin-country of platform default `IN` from `application.properties:571` triggers false-positive cross-border | Config-fragile; see § 5 H-01 | MED |
| G-12 | FedEx retry once with 3rd-party blank | Not implemented on manual path (`CarrierServiceImpl` grep — no 3rd-party retry). Only `AuthRetry` on 401 | **Regression**: FedEx-refuses-3rd-party retry not ported | MED |
| G-13 | UPS `120412/120413` retry with 3rd-party blank | Not implemented | Regression | MED |
| G-14 | USPS retry without delivery confirmation | Not implemented | Regression (may be non-issue on USPS_DIRECT / SWSIM) | LOW |
| G-15 | Multi-container `.Y<batchId>` batch pick-list on manual page | shioai FE parses `.Y` scan and populates `ndsQueue` chips (`NewShipmentPage.tsx:2865+`), each queued shipment click-to-swap. | Parity via UX chip strip | INFO |
| G-16 | **NDS manual list picker on the FE** — a "queue of un-shipped manual orders / TB_MANUAL_SHIPMENT rows" | **No dedicated picker exists**. The scan input is the entry; queued chips only from ScanValueParser output. A "browse pending manual shipments" screen does not exist. | **Gap by design or omission?** If NDS operators expect a picker of pending `TB_MANUAL_SHIPMENT` rows to work off, that is not built. Open question. | HIGH? |
| G-17 | Void matches on TRACKING only (dupes possible) | Same behaviour on our writeback (`NdsShipmentOracleWriter:365-368`) — but our tracking numbers are unique per tenant | Parity + no known duplicate risk | INFO |
| G-18 | Live-mode gate from browser hidden field | Our `environment` comes from `CarrierAccountRef.environment` server-side, not browser | **Better than ShipX** (fixes ShipX security issue) | INFO |
| G-19 | Fix-in-place regenerate | Regenerate flips `ERROR → GENERATED` on same order number (`CarrierServiceImpl:1948-1950`); pre-existing `shipment_batch`+`label_package` cleared (2057-2060) | Superset | INFO |
| G-20 | ShipX Return field: `REN-<id>` numbering | Not surfaced — our order number stays numeric; returns just carry `is_return='Y'`. `TB_MANUAL_SHIPMENT.ORDER_NO` writes `REN -{orderNo}` (`NdsShipmentOracleWriter:172-174`) matching ShipX. | Parity on NDS wire; internally `orderNo` still numeric | INFO |
| G-21 | Cancellations from ShipX History; DTC skips manual-void gate | Void is on the order detail view, not the /orders/new page — parity elsewhere | INFO |

### 4.2 Status transitions

ShipX (manual): (blank) → OK → Voided | Error (`part-C.md:413-418`). shioai:
`PENDING` → `GENERATED` | `ERROR` → `VOIDED` (per `Order.orderStatus`
+ `OrderTracking.status`). No known transition gaps.

### 4.3 Writeback expectations

- **CLIPPER writes**: NDS Manual Ship in ShipX did **NOT** write CLIPPER. shioai's `NdsShipmentOracleWriter.writeShipment` writes CLIPPER on outbound whenever `pkg.orderNos` non-empty (line 83-138). For a **true manual-only shipment (no NDS order)** the writeback payload should have zero `orderNos` and the CLIPPER branch is a no-op. Confirm the `NoopNdsShipmentWriteback` vs Oracle-primed variant is routed correctly on genuinely manual (non-scanned) shipments.
- **OEHEAD SHIPVIA_CD** (line 199-230): only fires when `stdReplacementErpCode` is set — i.e. only for shipments that came from an NDS scan with `shipvia=STD`. Pure manual doesn't touch OEHEAD.
- **G-07 duplicate Q row** on outbound with note is the biggest divergence.

---

## 5. Hardcoded findings

Priority marker: **P0** = ships wrong data / silently defaults per-tenant.
**P1** = admin-visible but shouldn't be code. **P2** = polish.

### 5.1 Business rules in code

| # | Priority | File:Line | What is hardcoded | Fix — DB/config target |
|---|---|---|---|---|
| H-01 | **P0** | `application.properties:564-571` | `carrier.shipper.*` platform default = `Multiship Warehouse / Bengaluru IN` — used as fallback for `fromCountry` (`CarrierServiceImpl:1231, 1446`) → misclassifies any US→US shipment as cross-border when sender omitted | Move to `tenant_settings` (V73) keyed per-tenant; a fresh tenant with no shipper defaults should refuse instead of falling back to IN |
| H-02 | **P0** | `NewShipmentPage.tsx:137-141` | `defaultSender()` = `MultiShip Fulfillment / 350 5th Ave NYC` hardcoded in FE | Feed from `tenant_settings.default_ship_from` or from the client's default warehouse when clientCode is picked |
| H-03 | **P0** | `NdsShipmentLookupService.java:56` | `DEFAULT_NOTIFY_EMAIL = "support@thbred.com"` | Already partially in `tenant_settings.nds.fallback_notify_email` per commit `ab8e42d9`. Confirm all callers use the tenant lookup |
| H-04 | **P0** | `NdsPhoneNormalizer.java:18` | `DEFAULT_PHONE = "+1 616 772 3513"` | Same pattern — `tenant_settings.nds.fallback_phone` (extend V73) |
| H-05 | **P1** | `NewShipmentPage.tsx:110` | `CURRENCIES = ['USD','EUR','GBP','CAD','INR','AUD','SGD','JPY','CNY','AED']` — 10 codes only | Extend `tenant_settings.currency_allowlist` or use ISO-4217 catalog seed table |
| H-06 | **P1** | `NewShipmentPage.tsx:97,128` | `KNOWN_CARRIERS = ['UPS','FEDEX','USPS','DHL']`; `CARRIER_LABEL` map missing DHL | Read from `CarrierAccountRef` distinct `carrierCode` values or a `carriers` seed table |
| H-07 | **P1** | `NewShipmentPage.tsx:75-95` | `canon()` alias table: ERP codes P80/F77/L01, service-code prefix heuristics (`INTERNATIONAL_`, `OVERNIGHT`, `WORLDWIDE`, `NEXT_DAY_AIR`, `SMART_POST`, `GROUND_HOME`, /^\d{2}$/=UPS) | Move to `client_shipvia_code_map` or per-carrier service_code → carrier lookup; the /^\d{2}$/ = UPS heuristic is dangerous |
| H-08 | **P1** | `FedExConnector.java:181-207` | `serviceMatrix(originCountry)` — 3-branch switch over `US/PR`, `DE/GB/FR/NL/IT/ES/PL/BE`, default (rest of world) with hardcoded `ServiceOffering` lists | Live-services API (already exists `fetchLiveServices` line 140) should be primary; matrix should only be a bootstrap fallback logged as WARN |
| H-09 | **P1** | `NewShipmentPage.tsx:3271-3277` | FedEx `Label Type` dropdown: `PDF/PNG/ZPLII/EPL2/DPL` | `carrier_label_format` seed table per carrier |
| H-10 | **P1** | `NewShipmentPage.tsx:3285-3294` | FedEx `Label Size` dropdown: `PAPER_4X6/…/STOCK_4X9_LEADING_DOC_TAB` (10 values) | Same seed table + `stock_type` column |
| H-11 | **P1** | `NewShipmentPage.tsx:3307-3312` | FedEx Pickup Type: `USE_SCHEDULED_PICKUP / REGULAR_PICKUP / REQUEST_COURIER / DROP_BOX / BUSINESS_SERVICE_CENTER / STATION` | `carrier_pickup_type` seed table (FedEx-only, but future-proof) |
| H-12 | **P1** | `NewShipmentPage.tsx:3326-3346` | Per-carrier label-image-format lists (UPS: GIF/PDF/PNG/ZPL/EPL; DHL: PDF/ZPL; USPS: PNG/PDF/GIF/JPG) | Same `carrier_label_format` seed table |
| H-13 | **P1** | `customsOptions.ts:8-17` | `SHIPPING_PURPOSES` — 8 values SALE/GIFT/SAMPLE/REPAIR_AND_RETURN/DOCUMENTS/MERCHANDISE/PERSONAL_USE/RETURN | Move to `reason_for_export` seed table; per-corridor visibility already in `ExportDeclarationPolicy` (docs/plans/) |
| H-14 | **P1** | `customsOptions.ts:40-60` | `CLEARANCE_BY_CARRIER` — UPS: SENDER/RECEIVER/THIRD_PARTY; FedEx: SENDER/RECIPIENT/THIRD_PARTY; USPS: DDU/DDP; DHL: DAP/DDP/EXW | `carrier_clearance_option` seed table keyed on (carrier, value, label) |
| H-15 | **P1** | `customsOptions.ts:96-100` | `FTR_EXEMPTIONS` — 3 values (§30.37(a)/§30.37(h)/§30.36) | Statutory — flag with `REGULATORY_REFERENCE` javadoc per feedback doc; keep hardcoded but ensure the ref is present |
| H-16 | **P1** | `customsOptions.ts:114` | `EEI_THRESHOLD_USD = 2500` (mirror of backend) | Statutory ceiling — keep hardcoded, add regulatory ref |
| H-17 | **P1** | `CarrierServiceImpl.java:1559,1562` | `firstNonBlank(profile.incoterms, "DAP")` and `... "SALE"` — hardcoded ultimate fallback incoterms/reason | `tenant_settings.customs.default_incoterms` + `default_reason_for_export`; or per-corridor via `ExportDeclarationPolicy` |
| H-18 | **P1** | `CarrierServiceImpl.java:1402, 3701` | `firstNonBlank(connector.getConfiguration().defaultServiceType(), "GROUND")` — string literal `"GROUND"` fallback in 2 spots | Connector `getConfiguration()` must always return non-null defaultServiceType; delete the `"GROUND"` fallback (would surface a bug) |
| H-19 | **P1** | `CarrierServiceImpl.java:1440` | `"YOUR_PACKAGING"` fallback for package type when preset null | Same pattern — connector default must be non-null, delete the literal |
| H-20 | **P1** | `FedExConnector.java:2247-2249` | FedEx label defaults `"PDF"` / `"PAPER_4X6"` inline | `carrier_account_ref.label_image_type_default` / `label_stock_type_default` — likely already exists; verify the connector consults it |
| H-21 | **P1** | `UpsConnector.java:2373` | UPS label default `"GIF"` inline | `carrier_account_ref.label_image_format_default` — verify column + fetch |
| H-22 | **P1** | `NewShipmentPage.tsx:154` | FE `US_TERRITORY_CODES = new Set(['PR','VI','GU','AS','MP','UM'])` | Regulatory — the codes are ISO fixed. Keep hardcoded but grep for the same list elsewhere; consolidate with `UsTerritoryNormalizer.US_TERRITORY_CODES` on backend |
| H-23 | **P2** | `NdsShipmentOracleWriter.java:251` | `"UNKNOWN"` fallback for CARRIER (NOT NULL) column | Prefer to fail loudly than write UNKNOWN — but at least gate on p.carrierCode() != null earlier |
| H-24 | **P2** | `NdsShipmentOracleWriter.java:172-174` | `"REN -"` literal (with trailing space) for return `ORDER_NO` prefix | Preserve — matches ShipX wire byte-for-byte on purpose; document constant instead |
| H-25 | **P1** | `CarrierServiceImpl.java:3386,3408-3410` | `effectiveDutyPayer` DDP→SENDER, else RECIPIENT; `normalizeDutyPayer` case-list SENDER/SHIPPER/DDP/RECIPIENT/RECEIVER/CONSIGNEE/DAP/DDU/EXW/THIRD_PARTY | Same seed-table target as H-14; the mapping is business config, not code |
| H-26 | **P2** | `NewShipmentPage.tsx:97` (implicit) | Only 4 carrier codes in enum; STAMPS aliased to USPS | `carriers` seed table + `alias_of` column |
| H-27 | **P2** | `CarrierServiceImpl.java:1791` | `firstNonBlank(req.getClientCode(), "MANUAL")` — writes literal `"MANUAL"` into `Order.custNo` when no client picked | Nullable custNo column or dedicated `ad_hoc=true` flag; a real client `MANUAL` (if it existed) would collide |
| H-28 | **P0** | `NewShipmentPage.tsx:81` | `if (['UPS', 'FEDEX', 'USPS', 'DHL'].includes(v)) return v` — closed carrier vocabulary in canonicaliser | Same fix as H-06 — the FE + BE both need to accept a Postgres-driven carrier list |
| H-29 | **P1** | `NdsShipmentOracleWriter.java:246-283` | Column max-widths clamped inline (50, 20, 255) — matches Oracle DDL captured 2026-09-28 | Move DDL widths into a config or constant class with a `# captured YYYY-MM-DD` note; today it's a comment-only pin |

### 5.2 Summary counts

- **P0**: 5 (`H-01, H-02, H-03, H-04, H-28`) — data-integrity / cross-tenant / cross-region wrong defaults
- **P1**: 18
- **P2**: 4

---

## 6. NDS touchpoints

### 6.1 Read path (prefill)

FE: `NewShipmentPage.tsx:282-299, 2865+` → `ndsShipmentService.lookup(scan, clientCode)` (`ndsShipmentService.ts:135-143`) → `GET /manual-shipment/nds-lookup` → `NdsShipmentLookupController` → `NdsShipmentLookupService` → external-systems framework `NdsOracleConnector` (`service/externalsystems/connectors/NdsOracleConnector.java`, PR #731). The connector reads NDS via `NdsTemplates.forClient(clientCode)` for CLIENT-schema tables + `.production()` for PRODUCTION-schema (`NdsShipmentOracleWriter:56-63, 161-167`).

- NDS reads happen for **CLIPPER item detail**, **OEHEAD header**, **OE_TRACKING**, **notify block** — all triggered by scan.
- Prefill result populates `client`, `recipient`, `ship method` (with `mappedServiceId` via `ClientShipviaCodeMap`/`ShipViaMapping`), `packages` (multi-container batch), `notify` (send/copy email), `international items`.
- Multi-container: `.Y<batchId>` returns N packages via `NdsShipmentPrefill.packages` array; FE places each order as a chip in `ndsQueue`.

### 6.2 Writeback on ship

Kicked from framework, not inline in `generateManualLabel`. `NdsShipmentOracleWriter.writeShipment(WritebackPayload)` runs after the label success:

| NDS Table | Action | Condition | File:Line |
|---|---|---|---|
| CLIPPER | UPDATE `TRACKING_NUMBER`, `FREIGHT_AMOUNT` per composite key | outbound only (`!isReturn`), `pkg.orderNos` non-empty | 83-138 |
| OE_TRACKING | UPDATE via V89 map — kept from pre-DDL-verified shape | `oetSets` non-empty | 141-158 |
| OE_TRACKING (USPS proc) | `PROC_OE.INSERT_OE_TRACKING(source="CLIPPER", code="450")` | CLIPPER update = 0 rows AND USPS carrier | 116-136 |
| TB_MANUAL_SHIPMENT (M or R) | INSERT | tracking present; **skip R when FedEx return with no note** | 170-187 |
| TB_MANUAL_SHIPMENT (Q) | INSERT (audit for note) | `p.note()` non-blank — **G-07: fires on M shipments too, ShipX only did on Q flow** | 189-196 |
| OEHEAD | UPDATE `SHIPVIA_CD` | outbound only AND `p.stdReplacementErpCode` non-blank | 202-230 |

### 6.3 Void writeback

`NdsShipmentOracleWriter.clearShipment` (line 292-384):
- CLIPPER: null out TRACKING_NUMBER + FREIGHT_AMOUNT when `hasTracking || hasFreight`
- OE_TRACKING: null out same fields via `clearingSetsForOeTracking`
- TB_MANUAL_SHIPMENT: `UPDATE ... SET VOID_YN='Y' WHERE TRACKING=:tracking` when `hasTracking` — same match-on-tracking-only pattern as ShipX (`G-06` parity, `G-17` risk)
- DTC surface has its own path (line 307-316)

### 6.4 Hardcoded config in NDS connector

- `NdsOracleConnector` (PR #731) — no hardcoded connection string; reads from `external_system_connection` table (V77/V78/V91). PROD/DEV env split via `use_dev` flag.
- Tenant→schema mapping — `NdsTemplates.forClient(clientCode)` — verify this uses a lookup vs client_code, not a hardcoded map.
- CLIPPER + OE_TRACKING SQL is inline (`NdsShipmentOracleWriter:96-114, 141-157`) — column names + composite key structure hardcoded. If NDS DDL changes, silent breakage.
- `TB_MANUAL_SHIPMENT` INSERT column order pinned to 2026-09-28 capture (`NdsShipmentOracleWriter:243-283`) — see H-29.

### 6.5 What's NOT yet FE-managed on the manual lifecycle

| # | Missing FE surface | Description |
|---|---|---|
| N-01 | Browse pending manual shipments | No FE list of open manual-flow orders that need labeling — only `.X`/`.Y` scan or `/orders/new` blank form |
| N-02 | Browse `TB_MANUAL_SHIPMENT` rows | No SELECT on NDS's own manual list — same gap as ShipX. Open Q: is there a business need? |
| N-03 | Manual writeback-status dashboard | No page shows "we wrote to NDS for this order and got Ack.Status = ..." — details live in `audit_log` only |
| N-04 | Manual-void from `/orders/new` | Void is on order-detail page, not the manual entry surface (parity with ShipX, but confirm) |
| N-05 | Retry a bulk-import ERROR from `/orders/new` | `?fixOrder={n}` route works for MANUAL source; verify it also works for BULK/API source and doesn't reclassify (already handled at line 1964, but audit for edge cases) |
| N-06 | Multi-container split confirmation UX before ship | Multi-container scan populates queue chips, but there's no "review all N shipments then submit" pattern — each is a manual submit |

---

## 7. Prioritized fix plan

Legend: LoC est = combined FE+BE where applicable. Risk = ship-blocking regression risk.

| # | Priority | Item | LoC est | DB migrations | Risk | Rationale |
|---|---|---|---|---|---|---|
| F-01 | **P0 / HIGH** | Fix G-07 duplicate `Q` row on outbound-with-note. Ship-only `Q` on Quick-Ship/BackOrder flows (which don't exist yet in shioai) | ~15 | none | LOW | Currently every outbound manual with a note writes 2 rows to `TB_MANUAL_SHIPMENT` (M + Q); NDS reports could double-count |
| F-02 | **P0** | H-01: move `carrier.shipper.*` platform default to `tenant_settings`; fail loudly when a tenant with no ship-from has no sender + no warehouse | ~120 | V100 `tenant_settings_seed_ship_from` | MED | Current IN-country default silently misclassifies US-domestic as intl; ships bad customs data |
| F-03 | **P0** | H-02: FE `defaultSender()` reads from `tenant_settings` via a hook / `useShipperDefault(clientCode)` | ~60 | (shared with F-02) | LOW | Same class of issue on the FE — 350 5th Ave NYC never applies to a real tenant |
| F-04 | **P0** | H-03/H-04: consolidate `NdsShipmentLookupService.DEFAULT_NOTIFY_EMAIL` + `NdsPhoneNormalizer.DEFAULT_PHONE` into `tenant_settings.nds.*` fallbacks. Partially done (H-03 per commit `ab8e42d9`). | ~40 | already V73 — just seed values | LOW | Extend the existing NDS-fallback pattern |
| F-05 | **P0** | H-28 + H-06: FE + BE stop treating `KNOWN_CARRIERS` as a closed set. FE fetches distinct `carrierCode` from `/api/v1/account-refs`; BE `canon()` alias table moves into DB `carrier_alias` seed | ~150 | V101 `carrier_alias` (source, target, priority) | MED | Adding a new carrier today = code change in >30 spots |
| F-06 | **HIGH** | G-16: build NDS Manual List picker page (`/manual-shipments`) if operations confirms need. Reads `TB_MANUAL_SHIPMENT` via new `NdsOracleConnector.readManualList(tenant, status, dateRange)`; filters M/R/Q; row actions = "Load into `/orders/new` as fixOrder", "Void", "Reprint". | ~600 | none (read-only) | MED | Depends on operator answer. If unbuilt, ShipX-migrated operators will miss "the queue is my worklist" |
| F-07 | **MED** | G-12/G-13/G-14: port carrier-specific retry rules (FedEx blank-3rd-party, UPS 120412/120413, USPS drop-DC) into per-connector `handleRefusalRetry(ex, req, token)` hook | ~200 | none | MED | Real-world hit rate on legacy path; may be USPS-only concern now that most refusals surface as validator errors upstream |
| F-08 | **MED** | H-05: seed `currency` from ISO-4217 list, filter per-tenant via `tenant_settings.currency_allowlist` | ~40 | V102 `iso_currency` seed + `tenant_settings.currency_allowlist` array column | LOW | 10 currencies today; the AED addition was manual |
| F-09 | **MED** | H-09/H-10/H-12/H-11: seed `carrier_label_format(carrier, code, label, is_stock_type)` + `carrier_pickup_type(carrier, code, label)`; FE reads from `shippingConfigService.labelFormats(carrier)` | ~180 | V103 both tables + FedEx/UPS/USPS/DHL seed | LOW | 4 hardcoded dropdowns become 1 shared React component |
| F-10 | **MED** | H-13/H-14: seed `reason_for_export` + `carrier_clearance_option(carrier, code, label)`; retire `customsOptions.ts` per-carrier map. `ExportDeclarationPolicy` already knows per-corridor visibility | ~150 | V104 both tables | MED | Adding a new incoterm currently needs 2 file edits + a redeploy |
| F-11 | **MED** | H-17: move `"DAP"` / `"SALE"` ultimate fallback into `tenant_settings.customs.default_*` (already partially per `ClientCustomsProfile`) | ~30 | V105 columns | LOW | Small but ships a legally-meaningful default to real invoices |
| F-12 | **MED** | H-08: FedExConnector `serviceMatrix` becomes bootstrap-fallback only; live `fetchLiveServices` becomes primary with a WARN log when it fails | ~40 | none | MED | Adding a new corridor to FedEx today needs a hardcode; but code changes to remove a fallback carry regression risk |
| F-13 | **MED** | H-18/H-19: delete `"GROUND"` and `"YOUR_PACKAGING"` literal fallbacks in `CarrierServiceImpl:1402, 1440, 3701`; enforce non-null connector `configuration()` at bean init | ~40 + 3 connector patches | none | MED | Would surface latent bugs when a connector forgets to set `defaultServiceType` |
| F-14 | **LOW** | H-20/H-21: verify `CarrierAccountRef` has label-format-default columns and connectors consult them (FedEx `PDF`/`PAPER_4X6`, UPS `GIF`); wire the fallback chain request > account > connector-default | ~60 | possibly V106 columns if missing | LOW | Per-account defaults enable per-customer paperwork prefs |
| F-15 | **LOW** | H-07: move `canon()` heuristics (service-code prefix → carrier) into `ship_via_mapping` reverse lookup; keep the ERP-alias P80/F77/L01 in `carrier_alias` seed (F-05) | ~40 | (shared with F-05) | MED | Guards against a new UPS numeric service code introducing new UPS aliases |
| F-16 | **LOW** | N-03: writeback-status dashboard reading `audit_log` filtered on `LABEL_GENERATED` + NDS ack detail; adds a "NDS status" column to `/orders` list | ~200 | possibly V107 `writeback_ack` (order_no, ack_status, detail, at) if audit_log is too noisy | LOW | Diagnoses NDS silent drops without hitting `audit_log` filters |
| F-17 | **LOW** | H-23: fail loudly on `p.carrierCode()==null` at writeback boundary instead of writing `"UNKNOWN"` into NDS | ~10 | none | LOW | Cleaner audit — no phantom `UNKNOWN` rows |
| F-18 | **LOW** | H-27: introduce `Order.adHoc` boolean vs writing `"MANUAL"` literal `custNo`; migration backfills existing rows | ~40 | V108 `orders.ad_hoc` + backfill | LOW | Small; only bites when a real client code `MANUAL` is created |
| F-19 | **LOW** | H-22: consolidate FE + BE `US_TERRITORY_CODES` behind one source (BE exposed via `/config` endpoint), delete FE Set literal | ~30 | none | LOW | Statutory list — drift risk is small; nice-to-have |
| F-20 | **LOW** | H-29: replace inline width constants in `NdsShipmentOracleWriter` INSERT with `TbManualShipmentColumns` constant class; add build-time regen instructions | ~40 | none | LOW | Doc-only guardrail; the widths shouldn't shrink but drift over time is possible |
| F-21 | **LOW** | G-03: audit USPS return path — does `StampsConnector`/`UspsDirectConnector` actually generate USPS returns, or does `return_scope` need to force-disable USPS? | ~40 | none | LOW | ShipX called out USPS as unsupported; verify our story |
| F-22 | **LOW** | G-04: confirm no-email hard-fail on returns is the desired policy (ShipX default = `support@thbred.com`); document decision in `docs/audits/returns-no-email-policy.md` | doc-only | none | LOW | Just needs signoff |
| F-23 | **MED** | G-11: cross-border detection must respect the platform shipper country, but should not silently fall back to `IN` — see F-02 | (shared with F-02) | (shared) | (shared) | Same fix as F-02 addresses this |
| F-24 | **INFO** | Documentation: pin the `TB_MANUAL_SHIPMENT` DDL snapshot to a versioned SQL comment file so future column drift causes a build warning | ~30 | none | LOW | Reduces H-29 surprise |

**Total est: ~2,100 LoC**. Two Postgres migrations (V100-V108 range) plus optional writeback-ack table.

---

## Open questions

1. **NDS Manual List picker** — should shioai build one (F-06)? The
   ShipX docs (`12c-nds-manual-list.md:1-10`) say the "list" everyone
   uses lives inside NDS/Thoroughbred; ShipX itself never SELECTed the
   table. Is a shioai-side picker in scope, or is the existing
   `.X`/`.Y` scan flow (`NewShipmentPage.tsx:3035-3110`) sufficient?
2. **Q-row semantics** (G-07, F-01) — did the original NDS-side
   reporting expect a `Q` row on *every* Manual Ship with a note, or
   only on Quick Ship / BackOrder? Doc says the latter
   (`12c-nds-manual-list.md` step 1 table). Confirm with ops before F-01.
3. **Ship-from platform default** (H-01) — should a tenant with no
   configured shipper hard-fail (safest) or silently fall back? Current
   IN-Bengaluru is worst-of-both.
4. **USPS returns** (G-03) — supported now or blocked at `return_scope`?
5. **Retry-with-3rd-party-blank** (G-12/G-13) — is this still real-world
   behavior post-carrier-audit, or fully replaced by validator layer?
6. **`carrier.shipper.*` currency** — `application.properties` has no
   `carrier.shipper.currency-code`; is that intentional (currency lives
   on the client/tenant) or a gap?
7. **STAMPS canonicalisation** (`NewShipmentPage.tsx:82` STAMPS→USPS) —
   is `STAMPS_COM` supposed to be a distinct carrier code (per §
   `project_stamps_com_audit`) or aliased to `USPS` forever?
8. **NDS writeback fallback state** — when `NdsOracleConnector` fails
   mid-writeback (Oracle unreachable), what's the retry story? Audit
   log records it, but there's no reconciliation job that re-runs the
   NDS write.
9. **`fixOrder={n}` scope** — does regenerate work for BULK/API-source
   orders as well as MANUAL? Code at `CarrierServiceImpl:1964` preserves
   source, but E2E path needs verification.
10. **Multi-container batch confirm** (N-06) — should a `.Y<batchId>`
    scan open a "review all N shipments" wizard, or is the current chip
    strip the intended UX?

---

*Report generated 2026-09-29. Read-only investigation, no code changes.*
