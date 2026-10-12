# /orders/new vs ShipX BackOrder Quick Ship — gap & fix plan

_Read-only audit, 2026-09-29._

Sources:
- ShipX narrative: `D:/GIT-REPOS/ShipX/docs/handover/source/part-B.md` §7-§8 (L180-L334)
- ShipX deep-dive: `D:/GIT-REPOS/ShipX/docs/handover/source/deepdive/08d-backorder.md` (L1-L120)
- ShipX pick-list API (same server-path as BackOrder): `deepdive/09-picklist-api.md` (L1-L80)
- shioai FE: `D:/GIT-REPOS/thb-shioai/multiship-react/src/components/NewShipmentPage.tsx`
- shioai NDS prefill service: `backend/src/main/java/com/multiship/backend/service/ndsshipment/`
- shioai NDS writeback: `backend/src/main/java/com/multiship/backend/service/ndsshipment/NdsShipmentOracleWriter.java`

---

## 1. Overview

ShipX **BackOrder Quick Ship** is a single-page bulk operation. The user
pastes a comma-separated list of Container IDs (`.X…`) and batch IDs
(`.Y…`), picks a label printer + invoice printer, hits Create. The server
loops the list, resolves order details from NDS per-ID, chooses the
carrier/service/box **server-side** from Postgres master data (not the
browser), calls the carrier, writes CLIPPER.TRACK/FREIGHT back to NDS, and
for STD-marked orders overwrites OEHEAD.SHIPVIA_CD with the actually-used
method. It's the same code path the pick-list tablet API uses; only the
UI wrapper differs.

Regular Quick Ship, by contrast, is per-container: scan one `.X`, review,
click Create. Carrier + keys + mode come from the **browser** (hidden
fields), not the server.

Shioai's `/orders/new` is a Quick-Ship-style single-shipment form.
Multi-container scanning exists via a FE-only queue (chips), but each
queued shipment must be swapped in and submitted individually. There is
no server-side bulk-ship endpoint and no per-scan STD/HLD/container-owner
resolution loop like `TenantDetails`/`ProcessBackOrderXShipments`.

---

## 2. ShipX BackOrder behavior (extracted facts)

### 2.1 Where the work happens

| Concern | Where | Cite |
|---|---|---|
| Split the comma-separated input | Browser POST → `ShipmentController.BackOrderShipment` splits on `,`, trims, keeps `.X*`, strips `.X` prefix | 08d L7 (`ShipmentController.cs:L569-L734`) |
| Resolve container→tenant | Server, one bulk Oracle query (`TenantDetails`) with bound `IN(:p0,:p1,…)` | 08d L11-L15 (`DALShiplist.cs:L2256-L2291`) |
| Loop rows returned (not the typed list) → per-container ship | Server, `ProcessBackOrderXShipments` per row | 08d L17 (`BALShiplist.cs:L5180-L5571`) |
| Read order details, carrier/service/box | Server, Postgres master data — **nothing from the browser** | 08d L36-L53 |
| Multi-container UPS grouping | Server calls `GetMultiContainerByOrder`, ships one multi-package UPS request per order | 08d L64-L74 |
| Multi-container FedEx / USPS | **No grouping** — each `.X` gets its own single-package label | 08d L60, L110 |
| Writeback per-package | `SetTrackingQ` → `CLIPPER.TRACK/FREIGHT` | 08d L78-L87 |
| STD → real method writeback | `updateSTD` → `OEHEAD.SHIPVIA_CD` (only on STD orders) | 08d L91-L100 |
| Save row per package | Postgres `spx_proshipmentdtls` (BackOrder-flavor stored proc) | part-B L317 |

### 2.2 Business rules

- **`.X` = direct container**, **`.Y` = billable batch** (same as Quick Ship). Any token that isn't `.X…` or `.Y…` is dropped silently (part-B L288, 08d L110).
- **No HLD gate**, **no Saturday-delivery gate** on BackOrder — the "check with the owner before relying on BackOrder for held orders" warning is in the spec, not in code (part-B L294-L295).
- **STD replacement** — for `.X`, `STD` shipvia is replaced by `SPX_tblClient.STDCode`; the successful-ship handler then UPDATEs `OEHEAD.SHIPVIA_CD` to that same ERP code (part-B L292, 08d L28-L34, L91-L100). `.Y` batches do **not** replace STD, so an STD batch just fails.
- **Weight 0 → 1 lb** to the carrier, but the raw 0 is still used to pick the box (08d L114-L115).
- **Both printers required**, else the whole request rejects (part-B L289, 08d L26).
- **Phone default** `+1 616 772 3513` (Thoroughbred main) when NDS phone is blank or <10 chars.
- **Missing email default** `support@thbred.com` (part-B L243).
- **Special char strip** on SHIP_NAME/ATTN and SHIP_ADDR1 (part-B L242).
- **Wrong client** — chosen client must own the container or be THB, else reject (part-B L244).
- **Third-party account refused** — prompt "proceed with default account?" on yes, ship on client's own (part-B L245).
- **Label footprint** — FedEx/UPS get employee number, order number, container number printed on label (part-B L246).

### 2.3 NDS touchpoints (BackOrder-specific)

| Purpose | Login | Table / SP | Cite |
|---|---|---|---|
| Bulk container → tenant | Production | `TB_SHIP_CONTAINER` (IN-list, bound params) | 08d L11-L15, part-B L301 |
| Bulk batch → tenant | Production | `TB_BILLABLE_CONTAINERS` | part-B L302 |
| Order / recipient / weights | Client | `OEHEAD ⋈ OE_SEND_TO ⋈ TB_SHIP_CONTAINER ⋈ OE_SHIP_CONTAINER` | part-B L253, L303 |
| All containers on order (UPS only) | Client | `TB_SHIP_CONTAINER WHERE ORDER_NO+ORDER_SUFFIX+TENANT_ID` | part-B L304, 08d L64-L70 |
| Tracking + freight | Client | `CLIPPER` UPDATE (per container) | part-B L305 |
| USPS + no container record | Client | `PROC_OE.INSERT_OE_TRACKING`, marker `'CLIPPER'`/`'450'` | part-B L306 |
| STD → real method | Client | `OEHEAD` UPDATE (per order) | part-B L307 |
| Postgres save | — | `spx_proshipmentdtls` (proc) | part-B L317 |

### 2.4 Server-picked carrier/service/box (BackOrder + pick-list only)

part-B L202-L209:

1. Read `OEHEAD.SHIPVIA_CD`.
2. `HLD` stops (pick-list only); `STD` → `Client.STDCode`.
3. Postgres join `SPX_tblShipCarrier ⋈ CarrierService ⋈ servicePack ⋈ CarrierPacking ⋈ ClientCarrier`, `Carrier.Status=T` AND `Service.Status=T` AND `MaxWeight > weight` AND `ServiceCode=<shipvia>` AND `ClientCode=<client>`, ORDER BY `MaxWeight ASC LIMIT 1`. Smallest box wins.
4. No row → hard fail with "ShipVia Code not found. Please use <hint> ShipVia".

### 2.5 Documented ShipX bugs (do NOT reproduce)

08d L107-L119:

- Unknown container dropped silently (loop uses `TenantDetails` result rows, not the typed list).
- UPS ships each `.X` as a full-order shipment → typing two containers of the same UPS order = double-ship.
- FedEx multi-container orders **not** grouped (unlike UPS).
- `tbOrder_Suffix` always null → international shipments throw after label + CLIPPER + Postgres save committed.
- `BALShiplist.CarrierOBJ` is instance state and leaks `Shipmentid` / `ClipperMSG` / `FreightCost` between containers in the request (linking unrelated shipments under one master).
- `updateSTD` result ignored; catch calls `Commit()` after `Rollback()`.
- Freight double-counted on UPS multi-package (total posted per package).
- Values pasted into SQL (all but `TenantDetails` are string-concat) — SQL injection surface.

---

## 3. shioai counterpart at /orders/new

### 3.1 What exists today

- **Single scan input** on the `Shipment` section — `NewShipmentPage.tsx:3035-3056`. Accepts comma- or whitespace-separated tokens, bare tokens default to `.X` (`:1388`).
- **Parallel per-token lookup** — `onScanSubmit` (`:1382-1443`) calls `ndsShipmentService.lookup(token, clientCode)` in `Promise.allSettled`, one HTTP hit per token.
- **Backend endpoint is single-scan** — `NdsShipmentLookupController.lookup(scan, clientCode)` on `GET /api/v1/manual-shipment/nds-lookup` — one scan per call (`NdsShipmentLookupController.java:53-56`).
- **FE-only queue** — successful prefills dedup by orderNo and populate a `ndsQueue` chip strip (`NewShipmentPage.tsx:1426-1439`, chip UI `:3057-3120`). First result loads into the form; the rest queue as clickable chips (activate = `applyNdsPrefill`, `:1446-1454`; remove = `:1458-1474`).
- **Submit one at a time** — form submit ships the currently-active shipment; on success the just-shipped chip is auto-removed and the next is loaded in place (`:2643-2653`).
- **STD writeback** — wired end-to-end. Lookup service resolves `stdReplacementErpCode` (`NdsShipmentLookupService.java:281,310`); FE round-trips it on the submit payload (`NewShipmentPage.tsx:2409`); backend writer runs `UPDATE OEHEAD SET SHIPVIA_CD=… WHERE …` on outbound + DTC (`NdsShipmentOracleWriter.java:202-230, 532-556`).
- **CLIPPER writeback** — same writer covers `CLIPPER.TRACK/FREIGHT` per container.
- **HLD gate** — enforced at lookup (returns `BLOCKED` status, `NdsShipmentLookupService.java:287-289`). Extended beyond `HLD` literal to per-client `client_shipvia_code_map.is_hold` (`:464-470`).
- **Phone default** — tenant-configurable via `tenant_settings.nds.fallback_phone`; platform default `+1 616 772 3513` (`NdsPhoneNormalizer.java:18`).
- **Notify-email default** — tenant-configurable via `tenant_settings.nds.fallback_notify_email`; platform default `support@thbred.com` (`NdsShipmentLookupService.java:56, 63`).
- **Special-char strip** — `NdsAddressSanitizer` on SHIP_NAME/ATTN/ADDR1.
- **Wrong-client gate** — `expectedClientCode` param on lookup mismatches → 422 (`NdsShipmentLookupService.java:98`).
- **Weight 0 → 1 lb** — DTC path in `DtcService.java:157-159` cites the same rule; the on-order path uses tenant-configured `nds.default_weight_lb`.
- **Multi-container prefill** — the same `.X` scan also returns siblings via `packages[]` (see `NdsShipmentPrefill.Package`), so one lookup can expose several packages of one order.

### 3.2 What does not exist

- **No server-side "ship all these `.X`s in one request" endpoint.** The pick-list API path from ShipX has no shioai analog. `NewShipmentPage.tsx` submits one label per user click.
- **No bulk container→tenant resolver.** The FE fans out N HTTP calls (`Promise.allSettled` on N tokens) instead of one server call with `IN (:list)`. Fine for 2-5 scans, wasteful for 50.
- **No per-scan STD/HLD/error report at the request level.** Each token succeeds/fails independently; the aggregated FE banner shows counts but no per-token log row like ShipX's `SPX_tblrequestlog` (part-B L317 shape, populated for pick-list per `deepdive/09-picklist-api.md:38-49`).
- **No .Y-only path exercised as "bulk billable batch" from `/orders/new` UI.** The parser accepts `.Y`; the code path likely returns a multi-order batch as a single prefill (`NdsShipmentPrefill.Scope.BATCH`), but there's no confirmed .Y integration test and the FE bar hints only mention `.Y<batchId>`.
- **No FedEx/UPS employee-number / order-number / container-number on-label imprint** in the manual-ship flow (`project_label_po_dept.md` covers PO/DEPT, but not the ShipX per-carrier physical-label imprints).
- **No third-party-account "proceed with default account?" prompt** on rejection.
- **No pick-list API compatibility layer** — the ShipX-era tablets can't post orders to shioai; the "Both" build column in part-B L190 assumed pick-list API is available on both builds. WMS is the shioai replacement; part-B calls DTC "Both" too, so this may be intentional.

Grep on the whole shioai tree for `backorder|BackOrder|back_order` returns only WMS partial-ship field names and a DTC comment citing the ShipX doc — no dedicated code path.

---

## 4. Gaps (feature-by-feature)

| # | ShipX BackOrder feature | shioai state | Gap |
|---|---|---|---|
| G1 | Paste N `.X`/`.Y` tokens, one Create click ships all | FE queue but N clicks | **MAJOR** — no bulk ship |
| G2 | Server-side container→tenant IN-list resolver | FE fans out N lookup HTTPs | MEDIUM — perf + no atomic "did tenant match" report |
| G3 | Server-side carrier/service/box selection (not from browser) | Browser posts serviceId / accountId / packagePresetId | **MEDIUM** — BackOrder's whole point is the operator doesn't pick these; shioai always does |
| G4 | STD → real ship method writeback | Wired (`NdsShipmentOracleWriter:202-230`) | none |
| G5 | CLIPPER.TRACK/FREIGHT writeback | Wired | none |
| G6 | HLD gate | Wired, extended to per-client flag | **BETTER** than ShipX BackOrder (which had none) |
| G7 | Saturday-delivery gate | No FSD/USD gate anywhere | none needed (ShipX version was buggy anyway; part-B L240) |
| G8 | Phone default 10-15 chars, tenant fallback | Wired | none |
| G9 | Notify-email default `support@thbred.com`, tenant fallback | Wired | none |
| G10 | SHIP_NAME/ATTN/ADDR1 special-char strip | `NdsAddressSanitizer` | none |
| G11 | Wrong-client rejection | `expectedClientCode` mismatch → 422 | none |
| G12 | Third-party-account fallback prompt | Not implemented | LOW — rarely triggered |
| G13 | Employee no. / order no. / container no. imprint on FedEx/UPS labels | Not implemented (label PO/DEPT already there, see `project_label_po_dept.md`) | LOW |
| G14 | UPS multi-container = one shipment, tracking-per-package | Standard MPS path likely handles this; unverified for the `.X`-triggered case | LOW-MED — verify with a `.X` scan against a UPS multi-container order |
| G15 | Weight 0 → 1 lb, tenant-configurable fallback | Wired for DTC + prefill via `nds.default_weight_lb` | none |
| G16 | Per-request log row (`SPX_tblrequestlog`) with batch id, printers, all containers, all orders | No equivalent for `/orders/new` bulk | LOW — not needed unless building the pick-list API |
| G17 | Silent-drop of tokens that don't start `.X`/`.Y` | FE defaults bare tokens to `.X` (`:1388`) — different but explicit | none (behavior diverges intentionally, arguably safer) |
| G18 | Pick-list tablet API (`POST /Api/Shipment`) | No shioai analog | Out of scope — WMS is the replacement |

**Not gaps — shioai improvements over ShipX BackOrder**

- Per-carrier IT-tested boundary guards (see `feedback_connector_boundary_guard_pattern.md`).
- STD writeback is idempotent (only fires when `SHIPVIA_CD IS NULL OR <> :new`, `NdsShipmentOracleWriter.java:217`).
- HLD gate is present and extensible (per-client map).
- All Oracle writes use named binds via `NamedParameterJdbcTemplate` — no SQL concat.
- STD ERP code round-trips via FE payload, not held in server-side session/instance state (no `CarrierOBJ` leakage bug).
- Return-mode flip guards STD writeback (`:202` `!isReturn` check).

---

## 5. Hardcoded findings (that would matter if bulk-ship ships)

None currently BackOrder-relevant. The two ShipX-era platform defaults
that could be pinned to a per-tenant setting already **are**:

- `NdsPhoneNormalizer.java:18` — `+1 616 772 3513` is only the fallback when `tenant_settings.nds.fallback_phone` is null. Correct.
- `NdsShipmentLookupService.java:56` — `support@thbred.com` is only the fallback when `tenant_settings.nds.fallback_notify_email` is null. Correct.

For a future bulk-ship endpoint, watch for:

- Per-carrier max-batch size (UPS/FedEx REST accept N shipments per request differently) — do NOT hardcode; put in `application.properties` or per-carrier `CarrierConfig`.
- Bulk-request timeout — don't inherit the single-shipment 30s; a 50-container UPS shop can take minutes.
- `BATCHSHIP` OE_TRACKING marker (`NdsShipmentOracleWriter.java:452,505`) is already a constant and matches ShipX DTC (part-B doc §6). Reuse for bulk `.X` too.

---

## 6. NDS touchpoints — bulk-ship additions

If BackOrder-equivalent ships, one net-new NDS read:

- **Bulk container → tenant resolver.** ShipX's `TenantDetails` (08d L11-L15) — single bound `SELECT Tenant_ID, CONTAINER_ID FROM TB_SHIP_CONTAINER WHERE CONTAINER_ID IN (:list)`. Not yet in shioai. Add to `NdsShipmentLookupRepository` alongside the existing single-container query.

Writeback stays as-is — CLIPPER, OEHEAD (STD), OE_TRACKING (BATCHSHIP) are all wired.

---

## 7. Prioritized fix-plan

Bulk-ship is genuinely absent. Plan is greenfield-in-phases; each phase
ships behind a per-tenant flag (`tenant_settings.orders_new.bulk_ship_enabled`).

| P | Item | LoC est | DB migrations | Risk | Rationale |
|---|---|---|---|---|---|
| **P0** | Confirm `.Y` batch path works end-to-end from `/orders/new`. Add IT if missing. | ~40 test-only | none | LOW | Cheapest win — parser + service already accept BATCH; verify NdsShipmentLookupService round-trip and FE prefill for `.Y`. If broken, tiny fix. |
| **P0** | Verify UPS multi-container behavior when `.X` scan expands to N packages. IT plus doc note. | ~50 test-only | none | LOW | Existing MPS + `NdsShipmentPrefill.Package[]` chain probably works; needs a `.X`-triggered IT to confirm no double-ship (the ShipX bug 08d L109). |
| **P1** | Add `POST /api/v1/manual-shipment/nds-lookup:batch` accepting `{ tokens: string[], clientCode? }`. Backend does one bulk `IN(:list)` container→tenant read, then per-container detail reads (parallelizable server-side). Returns `NdsShipmentPrefill[]` + per-token error map. Replace the FE fan-out. | ~180 BE + ~40 FE swap | none | LOW-MED | Removes N HTTP round-trips; adds server-side visibility into whole-request success rate. Same rules, just batched. |
| **P2** | Add "Ship all queued" button to the queue chip strip. Client-side loop: for each queued prefill, POST the existing single-shipment endpoint sequentially (or `Promise.all` if the operator opts in). Show per-chip status pill (pending / ok / failed with message). | ~200 FE only | none | MED | Keeps ShipX's paste-and-go UX at the FE level without a new bulk-ship BE endpoint. Sequential mode = safest (matches ShipX's per-order try/catch, `deepdive/09-picklist-api.md:10-11`). No STD/CLIPPER/HLD behavior change. |
| **P3** | Add a `bulk_ship_batches` table + per-run row summarizing N tokens, printers, aggregated statuses, timings. Backfills a shioai-native version of ShipX's `SPX_tblrequestlog`. Surfaced in Orders History as "Batch #123". | ~300 BE + ~100 FE + V?? migration | 1 migration (batch header table) | MED | Traceability for large runs. Not strictly needed if per-shipment audit_log rows already suffice; punt if audit_log query suffices. |
| **P4** | Server-side "trust the DB, not the browser" bulk-ship endpoint. Given `.X`/`.Y` list + printer + client, server resolves carrier/service/box from `service_package`/`ClientAllowedService` etc. (as ShipX BackOrder + pick-list do, part-B L202-L209). Operator does not pick anything. Feature-flagged. | ~500 BE + ~150 FE + tests | none | **HIGH** | The purest BackOrder equivalent. High risk: today's flow lets the operator override carrier + account + package; a "server picks everything" mode surfaces every gap in service/package mapping data. Ship only after real user demand — many shioai clients likely prefer the pick-a-service UX. |
| **P5** | Pick-list tablet HTTP API (POST list-of-orders, respond 202 immediately, ship in background, POST-back error). | ~800 BE + tablet client work | 1 request-log table | HIGH | Out of scope unless a specific tablet integration is requested — WMS covers this use case today. |

**Recommended minimum**: P0 + P0 + P2. That gives paste-and-ship UX
without a new BE endpoint and without changing carrier selection semantics.
`P1` is a nice perf win but only matters if operators routinely paste >10 tokens.

---

## 8. Open questions

1. **Do operators actually want to paste 50 containers into `/orders/new`?** ShipX BackOrder was a bulk warehouse tool; shioai's queue chip UI suggests the design already anticipated 2-10, not 50. Confirm the target scale before P1/P4.
2. **`.Y` batch on `/orders/new` — is anyone using it today?** Grep shows `.Y` in the FE placeholder and parser but no IT explicitly exercising the batch path through submit. If it's dead, delete the placeholder text; if live, add coverage before P2.
3. **STD replacement on `.Y` batches** — ShipX explicitly does NOT apply STD replacement to `.Y` (part-B L292). shioai's `stdReplacementErpCode` comes from the prefill regardless of scope; needs confirmation that `.Y` prefill either (a) never carries a non-null `stdReplacementErpCode`, or (b) the writer's WHERE-clause per-order match makes this safe.
4. **UPS multi-container writeback** — for one `.X` scan that expands to a 5-container UPS order, does the current MPS submit path correctly UPDATE CLIPPER per container? Or does it only write for the scanned container? (ShipX BackOrder does per-container writeback for UPS, 08d L64-L74.)
5. **Third-party account "use default?" prompt** — is this still a real customer request in 2026, or has it been superseded by the account-selection UX? If dead, skip G12.
6. **Employee-number-on-label imprint** — customer requirement or ShipX-era vestige? PR #543 already threaded PO/DEPT into carrier ref slots (`project_label_po_dept.md`); confirm this covers the operator's need before opening G13.
7. **Pick-list tablet integration** — is anyone still using picklist.thbred.com against shioai? If yes, P5; if no, drop the whole "bulk-ship endpoint" family.
