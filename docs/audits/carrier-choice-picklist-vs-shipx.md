# Carrier-choice + pick-list API — gap analysis vs ShipX §7 / §9

**Scope.** Legacy ShipX handover doc §7 (the ways a shipment is created, carrier-choice logic) and §9 (the pick-list tablet API), compared to the current shioai backend + FE. Read-only investigation, no code changes. All ShipX citations are `combined.md:<line>` unless the deep-dive file is named. All shioai citations are absolute paths.

Sandbox probe: PASSED (`D:/GIT-REPOS/thb-shioai/docs/audits/probe-carrier-picklist.txt`), so this report lands on disk.

Prior audit `docs/audits/orders-new-backorder-vs-shipx.md:160` already logged pick-list API as "Out of scope — WMS is the replacement." This report re-opens the question end-to-end: §7 is 80% covered by a very different (much cleaner) model in shioai; §9 is 0% covered and the ShipX pick-list contract is *not* a subset of any shioai endpoint.

---

## 1. Overview

**ShipX §7** describes 11 ways a shipment can start (Quick Ship, Quick Ship With Tax, Billable batch, BackOrder, Pick-list API, DTC Auto, DTC Manual, Manual, Return, Bulk Mailer, Re-ship). Carrier is resolved by matching one code — `OEHEAD.SHIPVIA_CD` — through 4 lookup paths (server-single-row, BackOrder .Y three-query, browser Quick Ship, hint). The single-row query joins carrier + service + packing + client-carrier in one SQL statement and takes the smallest box whose `MaxWeight > weight`. STD is client-scoped ("client's standard ship method"). HLD blocks. All values are pasted into SQL via string concatenation.

**ShipX §9** describes the fire-and-forget tablet API — one HTTP endpoint (`POST /Api/Shipment`), no auth in prod ShipX, returns HTTP 200 with `"submitted"` before any work happens, then processes async via `Response.OnCompleted`. Every message back to the tablet is a POST to `picklist.thbred.com` carrying a hardcoded `PHPSESSID` cookie. Companion endpoint `POST /Api/PrinterList` dumps every printer row.

**shioai** has a *catalog-first* replacement for §7 (route/service/package all DB-driven, per-client, per-warehouse, per-destination) — good architecture, 3 residual holes noted below. shioai has **zero** implementation of §9 and no equivalent tablet-integration surface (the "WMS" and "external v2" APIs are order-import flows, not the pack-time fire-and-forget label call this endpoint exists for).

---

## 2. ShipX §7 behaviour

**Legacy shipment-creation paths** (`combined.md:1381-1395`):

| Path | Where the order comes from | Writeback |
|---|---|---|
| Quick Ship / Quick Ship With Tax | NDS scanned `.X<Container>` | Container tracking/freight + optional Q line |
| Billable batch (.Y) | NDS scanned batch id | Tracking/freight on every package |
| BackOrder Quick Ship | List of `.X`/`.Y` ids | As Quick Ship; STD replaced |
| Pick-list API | Tablet POSTs order+container | As BackOrder |
| DTC Auto / DTC Manual | NDS DTC list (timer or manual) | Tracking history + STD replaced |
| Manual Ship | Typed in | New M line |
| Return Ship | Typed in | New R line |
| Bulk Mailer | Excel upload | Nothing |
| Re-ship (History) | Failed shipment re-read | As original |

**Carrier-choice logic (server paths)** — `combined.md:1401-1409`, `deepdive/07-carrier-choice.md`:

1. Read `oh.SHIPVIA_CD` from NDS `OEHEAD`.
2. If `HLD` → block ("Order is on Hold").
3. If `STD` → replace with client's `SPX_tblClient.STDCode`.
4. `DALShiplist.ShipmentMethod` (SQL at `DALShiplist.cs:L429`) — one row from a 6-table join gives: carrier, keys, Live/Test mode, service name, PackingName, client account. Filters: carrier+service active, `MaxWeight > weight`, `ServiceCode = shipvia`, `ClientCode = client`. Order-by is `MaxWeight ASC LIMIT 1` — smallest fitting box wins.
5. If no row → build hint from `SPX_tblShipVia` filtered on `Carrier` + `Country IN ('US','NONUS')` (`DALShiplist.cs:L1700`).
6. Carrier module re-reads client's carrier row (`FedExFromAddrss` / `UPSFromAddrss` — same SQL, different class) to get ship-from + real account + `ShipmentType` (FedEx API vs FSM).
7. Special-account swap for named clients (`MKL246`, `ARC805 U30`, `MHR210`) from NDS `production.SHIPX_DEV_SPECIAL_ACCOUNTS`.
8. Turn service/box into carrier codes: FedEx API sends `ServiceName`/`PackingName` as-is; FedEx FSM re-queries for `FSMServiceType`/`FSMPackType`; UPS has hard overrides ("01" under 1.1 lb, "03" → "11" for intl ground); USPS has weight-based intl overrides.

**Browser (Quick Ship) path** — `combined.md:1411-1417`:

1. Load client → return every attached carrier (`ClientCarrier`, ordered `DefaultAccount DESC`).
2. Scan container → read order → call `ShipviacodeACdetails` → fill hidden fields with `CarrierId`, `CarrierName`, `AccountNo`, `Mode`, `ApiKey1`, `ApiKey2`.
3. Draw services from `FedExServicelist` and boxes from `Packinglist`.
4. Create Shipment posts hidden-field values back — server trusts the page for carrier/mode/keys/box; only re-reads `ServiceName`. (Documented as a security note at `combined.md:1420-1423`.)

**Legacy weaknesses documented as "Things to watch"** at `combined.md:1602-1629`:
- No tie-break when two carriers offer the same shipvia code (arbitrary row wins).
- Missing status filters on packing / client-carrier rows in the joined query.
- Box-boundary inconsistency (`>` on server, `>=` in browser, ignored in BackOrder .Y).
- `FedExService` has no carrier or client filter — HLD (present on both FedEx and UPS) can resolve to the wrong carrier.
- STD-lookup bug in DTC error re-ship: swaps into a variable but passes the *un*-swapped one.
- Weight of only the first container is used for the whole shipment's box choice.
- Hint query throws on empty client-carrier grid.

---

## 3. shioai carrier-choice counterpart

shioai does not have a single `chooseCarrier` method — the equivalent lives inside `CarrierServiceImpl.buildShipmentRequest` (both overloads at `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/CarrierServiceImpl.java:3495` and `:3639`), delegating to `ShippingConfigService.resolveRoute` at `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/ShippingConfigService.java:1203`.

**Data model** (all DB-driven, all client-scoped):

| ShipX table | shioai equivalent | Notes |
|---|---|---|
| `SPX_tblShipCarrier` | `carrier_account_ref` + `carriers` (via `CarrierAccountRef`) | Live/Test replaced by per-account `environment`; keys stored per account, not per carrier |
| `SPX_tblCarrierService` | `shipping_service` (`ShippingService.java`) | With `is_enabled` flag; per-service max weight/length/girth |
| `SPX_tblservicePack` | `service_package` | M2M service ↔ preset |
| `SPX_tblCarrierPacking` | `package_preset` | Also carries `ownerType`/`ownerClientCode` for client-owned presets — a concept ShipX lacks |
| `SPX_tblClient` (STDCode) | `client_shipvia_code_map` row with `erp_code='STD'` (`ClientShipviaCodeMap.java:45-83`) and/or `shipvia_service_mapping` row with `shipvia_cd='STD'` (`ShipViaMapping.java`) | Resolver at `StdShipMethodResolver.java:52-72` |
| `SPX_tblClientCarrier` | `carrier_account_ref` client attachment | Per-account, not per (client, carrier) |
| `SPX_tblShipVia` (hint) | `ship_vias` table read via `ShipVia.java` at `CarrierServiceImpl:3266` | Still needs hint-mode wiring |

**Ship-via → service resolution — `ShippingConfigService.resolveRoute`** at `ShippingConfigService.java:1203-1224`, delegating to `resolveRuleRow` at `:1232-1274`. Specificity ranking (`:1265-1273`):
- `clientCode != null` = +8
- warehouse-restricted rule that matches this warehouse = +4
- `COUNTRIES`/`COUNTRY` dest = +2, `REGION` = +1, `ANY` = 0
- Tie-break: `id DESC` (last-written wins for equal specificity)

If no rule fires, fallback to `resolveServiceCode(carrier, shipvia, originCountry)` at `CarrierServiceImpl:3685-3688` — this is the "code is a carrier vocabulary word" branch.

**STD resolution** — `StdShipMethodResolver.java`:
- Per-client STD row in `shipvia_service_mapping` where `shipvia_cd='STD' AND client_code=<client>` → target service.
- Reverse lookup: same client's *other* mappings pointing at same service → deterministic ERP code for NDS writeback (`erpCodeForNds`).

**HLD replacement** — `ClientShipviaCodeMap.isHold` boolean (`ClientShipviaCodeMap.java:62-66`). Per client's memory line: V98 migration renumbered to V100/V101, closes the hardcoded `"HLD"` literal.

**Package resolution** — `ShippingConfigService.pickPackageForClient` at `:1366-1446` (with the F5-B intersection: service-linked ∩ client-allowed OR client-owned ∩ rule-allowed ∩ enabled ∩ fits-weight ∩ NOT OVER_MAX). Throws `PackageResolutionException` with an actionable message on empty pool — better than ShipX's silent "pick smallest".

**Billable weight** — `PackageMath.billableWeight(preset, actualWeight)` (`CarrierServiceImpl:3735`) uses max of (actual + tare, DIM), flat-rate skips DIM. ShipX has *no* dimensional-weight math — pure actual-weight comparison against `MaxWeight`.

**Carrier catalog is synced live** — no seed data (`ShippingConfigSeeder.java:14-21`). ShipX carrier grid is admin-typed rows in `SPX_tblShipCarrier`.

---

## 4. Gaps (§7)

Ranked worst-to-least, with impact:

**G-7.1 — Hint API for "ShipVia Code not found"** *(HIGH, missing)*
- ShipX: `TableterrorMessage` builds "Please use R02 ShipVia" from `SPX_tblShipVia` (`combined.md:1559-1580`). Shown on Quick Ship and posted back to the tablet on pick-list failure.
- shioai: `resolveShipVia` at `CarrierServiceImpl.java:3266-3269` throws `CarrierConnectionException("ShipVia row not found for carrier " + carrierCode)` — no per-country hint, no "try P80 instead" suggestion.
- Impact: operator gets a raw error, not a fix hint. Blocking for pick-list feature (§9) if adopted.

**G-7.2 — Special-account overrides per (client, shipvia)** *(MED, missing)*
- ShipX: `SHIPX_DEV_SPECIAL_ACCOUNTS` swap for MKL246/ARC805/MHR210 (`combined.md:1592-1595`, `deepdive/07-carrier-choice.md:87-89`).
- shioai: no equivalent table. `CarrierAccountRef` has one account per client-carrier. The MKL246-style "same client uses account A for one shipvia, account B for another" pattern requires a `(client_code, shipvia_cd) → carrier_account_ref_id` override table.
- Impact: named clients that need per-lane account swaps will need config in the app-layer (e.g., separate `CarrierAccountRef` rows disambiguated by service scope) — not a blocker, but not modelled.

**G-7.3 — Carrier-level overrides on package codes** *(LOW, partial)*
- ShipX UPS: "01" under 1.1 lb, "02" over (ARC805 U30 special); "03"→"11" for intl ground (`combined.md:1595-1598`).
- shioai: hardcoded intl-ground overrides scattered across `UpsConnector.java` (grep says `case "UPS_GROUND"` around `:1795`); no per-client override table.
- Impact: same as G-7.2 — modelled inline in code for the known FedEx/UPS/USPS-required cases only.

**G-7.4 — `carrier` narrowing on `resolveRoute` when a rule matches** *(POTENTIAL BUG, verify)*
- `resolveRoute` at `ShippingConfigService.java:1215-1218` — if the rule points at a service whose carrier does NOT match the passed-in `canonicalCarrier`, the code silently falls through to `resolveService`. Compare with ShipX path A: the rule *is* the carrier authority.
- Impact: a mapping rule mis-configured to a different carrier's service will be ignored rather than surfaced as an error. Non-blocking but worth a diagnostic log.

**G-7.5 — Documented "Live/Test mode gates writeback" flag** *(covered, different shape)*
- ShipX has `Mode` on the carrier row that gates every NDS writeback (`combined.md:1493`).
- shioai has per-account `environment` (`sandbox`|`production`) — the equivalent gate lives inside each connector's `getConfig().environment()`. Same intent, per-account instead of per-carrier — that's an improvement.

**G-7.6 — Same-code / different-carrier tie-break** *(covered)*
- ShipX limitation ("no tie-break", `combined.md:1602`) doesn't apply to shioai because `shipvia_service_mapping` rows are pre-committed by an admin to one service per (client, dest, warehouse) combo. Specificity ranking at `:1265-1273` gives deterministic ordering.

---

## 5. Hardcoded findings (§7)

**H-7.1 — Legacy shipvia code map hardcoded in `toShipViaCode`** *(MED)*
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/CarrierServiceImpl.java:3301-3308`:
  ```java
  return switch (canonicalCarrierCode.toUpperCase(Locale.ROOT)) {
      case "UPS" -> "P80";
      case "FEDEX" -> "F77";
      case "USPS" -> "L01";
      default -> canonicalCarrierCode.toUpperCase(Locale.ROOT);
  };
  ```
- Fix: this map should read from `ship_vias` table or `client_shipvia_code_map` reverse lookup. Same shape lives in `User.java:175-177` (display map) and `ZplLabelService.java:686-687` (label rendering). Three copies of the same hardcoded table = one truth source needed.

**H-7.2 — Same map duplicated in `User.java` display helper** *(MED)*
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/model/User.java:175-177`:
  ```java
  case "P80", "UPS" -> "UPS";
  case "F77", "FEDEX" -> "FedEx";
  case "L01", "USPS" -> "USPS";
  ```
- Fix: same source-of-truth as H-7.1. Should call `ShippingConfigService.canonicalCarrierFor(shipviaCd)` which already exists at `ShippingConfigService.java:223`.

**H-7.3 — `ZplLabelService` STD-family strings hardcoded** *(LOW)*
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/ZplLabelService.java:657`:
  ```java
  return new String[]{"STANDARD", "S", "STD"};
  ```
- Fix: label-vocabulary layer — pull from a small `label_shipvia_synonyms` table, or (laziest) accept this as an aesthetic label detail and leave the ceiling comment.

**H-7.4 — `ShippingConfigSeeder` seeds P80/F77/L01 → hardcoded target services** *(LOW / expected)*
- `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/config/ShippingConfigSeeder.java:38-40`:
  ```java
  map("P80", "UPS", "03");
  map("F77", "FEDEX", "FEDEX_GROUND");
  map("L01", "USPS", "PRIORITY");
  ```
- Comment at `:12-21` says this is intentional (only these three, only once, only if catalog already has the target service). NOT a bug — just document the ceiling: a client whose ERP sends `R02` or `HLD` needs an admin to add rows.

**H-7.5 — Default service type `"GROUND"` fallback** *(LOW / acceptable)*
- `CarrierServiceImpl.java:3700-3701`:
  ```java
  String serviceType = resolvedService != null ? resolvedService.getServiceCode()
          : firstNonBlank(connector.getConfiguration().defaultServiceType(), "GROUND");
  ```
- The `throws IllegalStateException` guard at `:3693-3698` short-circuits this for orders that named a ship-via — so this only fires on orders with no ship-via at all. Acceptable ceiling.

---

## 6. ShipX §9 behaviour

**Wire contract** (`combined.md:2264-2306`):

Endpoint 1 — `POST /Api/Shipment`, body is a JSON **array**:
```json
[{"PickerId":"197","USER":"User1","LABEL_PRINTER":"<name>","PACKINV_PRINTER":"<name>","ClientCode":"CAR800","ORDER_NO":"387703","ORDER_SUFFIX":"0","ContainerId":["9590981","91786901"]}]
```
Response is *always* HTTP 200 with `{"message":"Request submitted successfully","success":1}` — fire-and-forget, work runs via `Response.OnCompleted` (`combined.md:2371-2372`).

Endpoint 2 — `POST /Api/PrinterList`, empty body, returns every row of `SPX_tblPrinter` (`combined.md:2288-2290`).

**Auth** (`combined.md:2308-2320`):
- ShipX (production): **none** — no auth at all. Ships for any caller who can reach the host.
- ShipXSync: `X-Api-Key` header against `ShipmentApi:ApiKey` env var. Value not shipped in appsettings — every call refused until set.
- Cookie for tablet callback: hardcoded `PHPSESSID=tj89gvgb5tqc7ot2oltlhabubq` (`combined.md:2331`).

**Post-reply processing (12 steps at `combined.md:2374-2653`):**

1. Auth (§9 Step 0 — nothing in ShipX, X-Api-Key in ShipXSync).
2. `PrinterList` endpoint returns whole printer table with no filters.
3. `Shipment` endpoint replies "submitted", registers `Response.OnCompleted` for the real work.
4. Insert `SPX_tblrequestlog` row via `spx_prorequestlog` (batch = list of ORDER_NOs). Missing PickerId/ClientCode/ORDER_NO/ContainerId → hard-stop, no trace.
5. For each order: printers by name via UNION-ALL SELECT (row 0 = label, row 1 = invoice).
6. Read order + FIRST container from NDS `OEHEAD/TB_SHIP_CONTAINER/OE_SHIP_CONTAINER/OE_SEND_TO/SHIPVIA`.
7. Carrier choice (§7 path A): STD-swap, one-row query, HLD block.
8. All containers from `tb_ship_container` for FedEx/UPS multi-pkg; USPS ships only first.
9. Carrier-module lookups (ship-from, account, ShipmentType, meter, invoice copies, importer of record).
10. International items from `TB_CLIPPER_ITEM_DETL` + commercial invoice save.
11. Writeback to NDS `CLIPPER.TRACK` + `CLIPPER.FREIGHT` (Live mode only). STD orders update `OEHEAD.SHIPVIA_CD`. USPS also insert `proc_oe.insert_oe_tracking`.
12. Insert `SPX_tblShipmentDetails` row (via `spx_proshipmentdtls`) with `BatchID = RequestID`, `CreatedBy = PickerId`.
13. `sndrequestTotablet` POST multipart to `picklist.thbred.com/api/V1/get_shipx_order_label_response.php` if any error text. Ignored answer, no retry.
14. `Bugupdate` (`spx_logupdate`) writes concatenated errors onto request-log row.

**Documented weaknesses** (`combined.md:2622-2653`):
- `dt.Rows[0]` read *before* the row-count check — unknown container throws before validation.
- No tenant check on the tablet's `ClientCode` (used as NDS login *and* filter).
- Tracking matched to containers by row order — no ORDER BY, wrong-tracking-to-container is a real risk.
- Freight written as `'0'` for FedEx multi-pkg and USPS.
- STD writeback runs outside Mode gate for FedEx multi-pkg — Test-mode shipments still update `OEHEAD` in NDS.
- Same box/service applied to every package (chosen from first container's weight only).
- `ClipperMSG` field on `CarrierOBJ` leaks between iterations of the loop.
- `CheckDuplicate` is commented out — two POSTs of the same order ship twice.
- All values pasted into SQL via string concatenation.

---

## 7. shioai pick-list counterpart

**None.** Confirmed by:
- Grep for `pickList|picklist|pick_list|PickList` across BE + FE returns zero source-code hits (only 2 doc references in existing audit files).
- Grep for `tablet` returns 127 files but every hit is either `printer-scan-agent` (mDNS scanner for printers, not tablets), the FE printer-scan page, or unrelated `Tablet` in table docs. Nothing implements the fire-and-forget order-shipment tablet endpoint.
- Grep for `SPX_tblrequestlog|spx_prorequestlog|Requstlog|EMPprinter|PickerId|LABEL_PRINTER|PACKINV_PRINTER|Api/Shipment|ShipmentApi` across the entire repo returns only the prior audit doc.
- No controller matches `PickList` / `PickerId` / `LabelPrinter` / `Api/Shipment` (see full controller listing under `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/controller/`).

**Closest primitives that could be re-used** (not equivalents):
- `NdsShipmentLookupService.java` — the `.X<container>` / `.Y<batch>` scan already resolves container → order → recipient (see `D:/GIT-REPOS/thb-shioai/backend/src/main/java/com/multiship/backend/service/ndsshipment/NdsShipmentLookupService.java:22-77`). This solves §9 steps 4-7 already — a pick-list controller would call this then hand off to label generation.
- `NdsScanValueParser` — parses `.X<id>` / `.Y<id>` scan values.
- `PrinterController` + `Printer` model — printers are already looked up by ID / alias in shioai (memory line `project_printer_page_redesign`).
- `BulkLabelServiceImpl` — the async fire-and-forget queue pattern (`R4`/`R5` sprint) already exists — same shape as `Response.OnCompleted`, but proper job queue.
- `OrderImportServiceImpl` — the NDS-to-shioai order copy already exists for DTC; a pick-list order arriving from the tablet would first need to exist as an order in shioai (or be created on the fly from the container scan).

**Confirmed prior audit conclusion is stale.** `docs/audits/orders-new-backorder-vs-shipx.md:160` says "WMS is the replacement" — but `WmsController.java` exposes `/status`, `/batches`, `/batches/{slug}`, `/pull`. That's inbound *order* pulls (client-managed WMS asks shioai for work), not the ShipX tablet contract (pack-time tablet tells shipping platform to ship this container now). Different direction, different UX. WMS is not a pick-list replacement.

---

## 8. Gaps (§9)

**G-9.1 — POST /api/picklist/shipment endpoint** *(FULL FEATURE GAP)*
- ShipX: `ShipmentApiController.Shipment` at `ShipmentApiController.cs:L33-L61`.
- shioai: nothing. `ExternalV2Controller` shipments endpoint (`external/v2/ExternalV2Controller.java:117`) is the closest but requires full DTO from the caller — not the shorthand `(PickerId, LABEL_PRINTER, PACKINV_PRINTER, ClientCode, ORDER_NO, ORDER_SUFFIX, ContainerId[])`.

**G-9.2 — POST /api/picklist/printers endpoint** *(FEATURE GAP, low effort)*
- ShipX: dumps whole `SPX_tblPrinter` table.
- shioai: `PrinterController` exposes `/api/printers` — already lists printers. Pick-list only needs a *tablet-accessible* view of active label/invoice printers, filtered by type. The R2/R6 sprint (memory `project_printer_page_redesign`) shipped `printer_tags` — this is a natural filter.
- Fix: 1 GET endpoint, ~30 LoC, no migrations.

**G-9.3 — Async fire-and-forget with request-log persistence** *(FEATURE GAP)*
- ShipX: `Response.OnCompleted` + `SPX_tblrequestlog` (RequestID → BatchID on shipments).
- shioai: `BulkLabelServiceImpl` already has the pattern (`bulk_label_batch` table, per-order status). A pick-list endpoint should reuse this, not build a parallel queue.
- Fix: adapter that turns a `PickListRequest` into a `BulkLabelBatch` with `source='PICKLIST'` — reuses monitoring dashboards, cancel semantics, and audit logging that already exists.

**G-9.4 — Tablet callback (send-error-back)** *(FEATURE GAP)*
- ShipX: `sndrequestTotablet` POSTs multipart to `picklist.thbred.com`.
- shioai: no equivalent. Webhook infrastructure exists (`ExternalWebhookController`, `WebhookSubscriptionAdminController`) — pick-list callback fits the webhook subscription pattern (subscribe to `LABEL_FAILED` / `LABEL_GENERATED` events with a URL filter on `source='PICKLIST'`).
- Fix: don't build a bespoke sender. Ship the endpoint, event, and one seed webhook subscription; let the tablet register its callback URL.

**G-9.5 — Auth for tablet endpoints** *(SECURITY GAP if built)*
- ShipX: prod has no auth at all (`combined.md:2311`); ShipXSync has X-Api-Key.
- shioai: `ApiKeyController` + api key infra already exists. Pick-list endpoint should require an api key with scope `picklist:submit`. Don't recreate ShipX's no-auth mistake.

**G-9.6 — NDS writeback for pick-list-submitted shipments** *(PARTIAL — same as DTC)*
- ShipX: writes `CLIPPER.TRACK`, `CLIPPER.FREIGHT`, `OEHEAD.SHIPVIA_CD` (STD).
- shioai: NDS writeback framework exists per `reference_writeback_probe` (memory) — `POST /admin/external-systems/{id}/writeback-probe` runs `writeback[generate|clear]`. The `V90` gate at `external_system_writeback` controls per-system.
- Fix: pick-list-produced labels should flow through the same NDS writeback path as DTC — no new code, just make sure the `source='PICKLIST'` shipment triggers the same trigger.

**G-9.7 — Multi-container tracking-to-CLIPPER mapping** *(NEW REQUIREMENT)*
- ShipX bug: order-agnostic `SELECT container_no FROM tb_ship_container` matched by array position. **Do not replicate.** shioai's MPS split (`ShipmentSplitter`) already knows package → container mapping via `label_package.sequence_number`. Use that.

---

## 9. Hardcoded findings (§9)

**N/A** — feature does not exist in shioai, so nothing to unpin. When the endpoint is built, avoid:
- Hardcoded callback URL (ShipX's `https://picklist.thbred.com/api/V1/get_shipx_order_label_response.php`) — use webhook subscriptions.
- Hardcoded `PHPSESSID` cookie — use api-key or short-lived signed URL.
- Hardcoded "US" / "NONUS" hint-query country split — the `country_regions` table already has a real region model.

---

## 10. Prioritized fix-plan

Numbers are estimates; LoC ranges are backend Java only (FE and tests would roughly double).

### Fixes to existing §7 code

| # | Item | LoC | Migrations | Risk | Rationale |
|---|---|---|---|---|---|
| P1 | Un-hardcode P80/F77/L01 map — one truth source (`toShipViaCode`, `User.java`, `ZplLabelService.java`) | ~60 (delete + rewire) | none | LOW | H-7.1 + H-7.2 + H-7.3. Three copies today; `ShippingConfigService.canonicalCarrierFor` already exists as the target. Purely mechanical, saves the next audit. |
| P2 | Hint-mode "did you mean R02?" on `ShipVia` not-found | ~80 | 0 (existing `ship_vias` table) | LOW | G-7.1. Prerequisite for a decent pick-list operator UX. Data is already in `ship_vias`. |
| P3 | Diagnostic warn-log when `resolveRoute` rule points at a different carrier than requested | ~10 | none | LOW | G-7.4. One-liner; caught misconfig early. |
| P4 | Per (client, shipvia) account-override table + resolver hook | ~120 | V-next: `client_shipvia_account_override(client_code, shipvia_cd, carrier_account_ref_id)` | MED | G-7.2. Only ship if there's a real customer waiting. YAGNI otherwise — `CarrierAccountRef` can already be scoped per client with careful naming. |

### Greenfield pick-list feature (§9) — phased

**Phase A — MVP (~1 week)**: read-only + basic ship

| # | Item | LoC | Migrations | Risk | Rationale |
|---|---|---|---|---|---|
| A1 | `GET /api/picklist/printers` — filter to enabled + label/invoice types, api-key auth | ~40 | none | LOW | G-9.2. Reuse `PrinterRepository`. Tablets need this to render printer picker. |
| A2 | `POST /api/picklist/shipment` — accept ShipX-compatible JSON array; validate against `PickListRequest` DTO; reject missing fields with 422 | ~150 | V-next: `picklist_request(id, client_code, picker_id, user_name, order_no, order_suffix, container_ids TEXT[], label_printer, packinv_printer, submitted_at, status, error_message)` | MED | G-9.1 + G-9.3. Fire-and-forget response, work registered in shioai's existing async infrastructure. |
| A3 | `PickListDispatcher` — turn each order into a `BulkLabelBatch` with `source='PICKLIST'`, feed through existing queue | ~120 | 1 col on `bulk_label_batch`: `source VARCHAR(20)` if not present | MED | G-9.3. Do not build parallel queue. |
| A4 | Api-key scope `picklist:submit` on both endpoints | ~30 | none (existing api-key infra) | LOW | G-9.5. |

**Phase B — feature parity (~1 week)**: async result reporting

| # | Item | LoC | Migrations | Risk | Rationale |
|---|---|---|---|---|---|
| B1 | Emit `LabelGenerated` / `LabelFailed` events with `source='PICKLIST'`, `pickerId`, `containerIds` | ~30 | none | LOW | G-9.4. Existing event bus. |
| B2 | Register webhook subscription filter: `source=PICKLIST` → tablet callback URL | ~20 (mostly config) | 1 seed row | LOW | G-9.4. |
| B3 | Ensure NDS writeback fires for pick-list source (verify V90 gate + `external_system_writeback` config) | ~20 (audit only, likely no code) | none | LOW | G-9.6. Same trigger as DTC. |

**Phase C — hardening (~3 days)**: fixes to the legacy bugs so we don't inherit them

| # | Item | LoC | Migrations | Risk | Rationale |
|---|---|---|---|---|---|
| C1 | Duplicate-check on `(client_code, order_no, order_suffix, container_ids)` in the submit endpoint — reject or short-circuit | ~40 | 1 unique index on `picklist_request` | LOW | Legacy weakness "CheckDuplicate commented out" (`combined.md:2645`). Ship it right the first time. |
| C2 | Multi-container tracking-to-package mapping via `label_package.sequence_number`, NOT by list position | ~30 | none | LOW | Avoid ShipX's known wrong-tracking-to-CLIPPER bug (`combined.md:2632-2636`). |
| C3 | Tenant guard: reject when the tablet's `ClientCode` doesn't match the container's owner | ~30 | none | LOW | Close legacy "no tenant check" issue (`combined.md:2629`). |

**Total greenfield estimate: ~640 LoC BE + tests + FE tablet page (out of scope for this plan).**

---

## 11. Open questions

1. **Is the pick-list feature actually needed?** The prior audit dismissed it as "WMS replacement." If the customer's picking process now goes through their own WMS (which pulls orders via `/api/v1/wms/pull` and pushes back to the app), then the ShipX pick-list endpoint may be entirely obsolete. Ask before shipping A1-A4.
2. **Do any current customers still use `picklist.thbred.com`?** If yes, they need a migration story — the new endpoint URL, the new auth model (api-key), the new callback pattern (webhook).
3. **Should `client_shipvia_account_override` (P4) be built, or does the customer segment we're targeting only ship one carrier per shipvia code per client?** MKL246/ARC805/MHR210 were ShipX customers — carry-over unknown.
4. **What happens to `SPX_tblrequestlog` batch grouping?** ShipX joins all ORDER_NOs into a single request log row. shioai's `BulkLabelBatch` uses per-batch UUID — cleaner but not 1:1. Confirm no report or export depends on the ShipX shape.
5. **Live-mode gate on NDS writeback.** ShipX's per-carrier `Mode` gate has a known bug (FedEx multi-pkg writes to NDS in Test mode). shioai's per-account `environment` gate is cleaner but not yet wired end-to-end for pick-list-submitted labels — confirm during B3.
6. **Tablet printer discovery.** ShipX returns *every* printer (including inactive) — pick-list UI filters client-side. shioai's `PrinterController` has `printer_tags` (F-audit PR #703-#706). Should the pick-list `GET /api/picklist/printers` filter by tag (e.g. `tag=picklist-station-3`)? Nice-to-have.
7. **What does the pick-list tablet actually render?** Without seeing the tablet UI, "which fields matter" is a guess. Get a mockup or screenshot before locking A2's DTO shape.

---

## Appendix — evidence trail

### Confirmed absent in shioai
- `Grep pickList|picklist|pick_list|PickList` → 2 hits, both in prior audit doc files under `docs/audits/`.
- `Grep SPX_tblrequestlog|spx_prorequestlog|Bugupdate|Requstlog|EMPprinter|PickerId|LABEL_PRINTER|PACKINV_PRINTER|ContainerId\[0\]` → 1 hit, in prior audit `orders-new-backorder-vs-shipx.md`.
- `Grep Api/Shipment|Api/PrinterList|ProcessApiShipments` → 1 hit, same prior audit doc.
- No `PickListController` / `TabletController` / `ShipmentApiController` under `backend/src/main/java/com/multiship/backend/controller/`.

### Confirmed present in shioai
- `StdShipMethodResolver.java` — full §7 STD swap.
- `ClientShipviaCodeMap.isHold` (V98/V100/V101) — HLD replacement, not hardcoded literal.
- `ShippingConfigService.resolveRoute` at `:1203` + `resolveRuleRow` at `:1232` — full §7 path A equivalent, cleaner.
- `pickPackageForClient` at `:1366` — full package intersection engine.
- `PackageMath.billableWeight` — dimensional weight math (ShipX has none).
- `NdsShipmentLookupService` — `.X`/`.Y` scan resolver, could feed a future pick-list endpoint.
- `BulkLabelServiceImpl` — async fire-and-forget queue pattern to reuse.
- `WebhookSubscriptionAdminController` + `ExternalWebhookController` — callback infrastructure to reuse.
