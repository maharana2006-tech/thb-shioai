# DTC + USPS Funds — ShipX §10/§11 vs shioai gap analysis

**Repo:** thb-shioai (dev @ 2026-09-30, HEAD `093d7731`)
**Author:** Read-only sub-agent audit; no code changes
**Scope:** ShipX §10 "DTC: bringing orders in and shipping them" and §11 "DTC errors, retries and USPS funds" versus the equivalent (or missing) shioai backend surface.
**Sources:**
 - `D:/GIT-REPOS/ShipX/docs/handover/source/combined.md` §10 (L2654-L3272) / §11 (L3273-L3735)
 - `D:/GIT-REPOS/ShipX/docs/handover/source/deepdive/10a-dtc-pull.md`
 - `D:/GIT-REPOS/ShipX/docs/handover/source/deepdive/10b-dtc-ship.md`
 - `D:/GIT-REPOS/ShipX/docs/handover/source/deepdive/11-dtc-errors-funds.md`
 - `D:/GIT-REPOS/ShipX/docs/handover/source/appendix-B.md` §B.1 (L58) `notify_shipx_insufficient_funds`, §B.49 (L2734) `trg_shipx_insufficient_funds`
 - `D:/GIT-REPOS/ShipX/docs/handover/source/appendix-A.md` §A-020/A-021 (L472) `DALFundsHistory`
 - `D:/GIT-REPOS/thb-shioai/docs/audits/nds-audit-and-fe-spec.md` (cross-reference)

**Sandbox probe:** `D:/GIT-REPOS/thb-shioai/docs/audits/probe-dtc.txt` — PASS.

---

## 1. Overview — DTC in ShipX + funds mechanics

**DTC = direct-to-consumer.** ShipX's DTC flow is a batched pipeline: Oracle NDS publishes pending single-parcel orders through a view; ShipX/ShipXSync pulls the whole view every N minutes (schedule varies by weekday × time-of-day band), stages one row per (client, batch, tote, day) in Postgres `SPX_tblDtcBatch`, then a 5-worker background dispatcher calls FedEx / UPS / Stamps.com for each staged tote. On success the tracking number is written back to NDS via `PROC_OE.INSERT_OE_TRACKING` (source=BATCHSHIP, code=450) plus `OE_SHIP_CONTAINER.CONTAINER_ID = tracking`, and for STD-resolved orders `OEHEAD.SHIPVIA_CD` is updated. On failure the log row keeps `Status=false, IsErrExist=true` for the retry sweeper.

**Funds mechanics** exist because Stamps.com (USPS-through-Stamps) is a prepay wallet: labels fail with "Insufficient account balance" when the pot is empty. ShipX detects that failure string in the ship-attempt code path, uses the Stamps SERA OAuth `refresh_token` to hit `GET /sera/v1/balance`, and if `amount_available < 20` USD, calls `POST /sera/v1/balance/add-funds` with a per-client hardcoded amount, then re-attempts the label once. A Postgres trigger `trg_shipx_insufficient_funds` fires an `NOTIFY shipx_insufficient_funds` on every DTC log write that has a non-empty `ErrorMessage` (misleadingly named — fires for ALL errors including "Cancelled" and success text "Label created successfully."); the ShipXSync notification-listener service consumes that channel and triggers `TryRegenerateByShipmentId`.

**shioai state at HEAD (2026-09-30):** DTC pull exists in two parallel implementations (both @Scheduled currently commented out); DTC ship path piggybacks the generic `OrderImport` → `ImportGenerationWorker` → `CarrierServiceImpl` pipeline shared by Bulk Mailer / WMS — there is **no** DTC-specific dispatcher; **no** funds retry, **no** SERA balance poll, **no** `add-funds` call, **no** insufficient-funds trigger, **no** LISTEN listener, **no** fund log table, **no** alert email path. Stamps SERA `getAccountBalance()` endpoint IS wired (`StampsConnector.java:1915`) but has no caller in production code — the balance is only reachable via ad-hoc admin verify. Stamps SOAP fault parser recognizes "insufficient postage" as a fault-string (`StampsConnector.java:3434`) but only to convert it to `IllegalStateException`, not to trigger auto top-up.

---

## 2. ShipX §10 pull behavior

### 2.1 Trigger
Three trigger sources (10a-dtc-pull.md L4-L28):

| Path | Build | Trigger | View(s) read |
|---|---|---|---|
| Automatic pipeline | ShipXSync only | `DTCTimedBackgroundService` on schedule (see 2.2) | `TB_SHIPX_DTC_UVW_TEST` + `TB_SHIPX_DTC_NEW_DEV` (concatenated, no dedup) |
| Start Sync button | ShipXSync only | HTTP POST from Super Admin's DTCSync screen | `TB_SHIPX_DTC_UVW_TEST` only |
| DTC New Manual | ShipX only | HTTP POST from user with one client + Open/Back toggle | `TB_SHIPX_DTC_UVW` with `WHERE Order_Suffix = 0` OR `> 0 AND Order_status = 'S'` |

### 2.2 Schedule (ShipXSync, combined.md L52-L64)
DB-driven from `appsettings.json` `DtcSchedule:*`; re-read every tick (no restart needed):

| Band | Value in prod | Default in code |
|---|---|---|
| Weekday off-peak (Mon-Fri 06:00-12:00) | 5 min | 20 min |
| Weekday busy (Mon-Fri 12:00-22:00) | 1 min | 5 min |
| Weekday overnight (Mon-Fri 22:00-06:00) | 30 min | 30 min |
| Weekend (Sat/Sun all day) | 5 min | 20 min |
| FedEx EOD ETD upload | 21:00 local | 21:00 |
| `DtcSchedule:BatchSize` (totes per tick) | 500 | 100 |
| `DTCPipeline:AdvisoryLockKey` | 4831175 | 4831175 |
| `FeatureManagement:DtcSync` (kill switch) | true | – |

### 2.3 Source query (10a-dtc-pull.md L54-L82)
34 columns, no date filter, no suffix filter in SQL (suffix filtered in C# afterwards). Tenants bound as `:tenant_0, :tenant_1, …` (ShipXSync) or pasted (ShipX; SQL-injection-adjacent). SPX_tblClient `IsDTC=true AND Status=true` supplies the tenant list.

### 2.4 Dedup + staging (10a-dtc-pull.md L84-L112)
`INSERT … ON CONFLICT ("TENANT_ID","BATCH_ID","TOTE_NUMBER",(("CreatedDate"::date))) DO NOTHING`. Rows tagged `IsManual='B'` (automatic), `'M'` (manual New Manual), `'D'` (external source, `IsExtSource=true`). Duplicate check is per-day (UTC), so a still-open NDS row reappears the next day.

### 2.5 Pipeline lock
`SELECT pg_try_advisory_lock(4831175)` — false = another instance already ticking, skip. `finally` block does `pg_advisory_unlock`. Ensures only one server per cluster runs the DTC dispatch.

### 2.6 Pending-tote pick (10a-dtc-pull.md L114-L134)
`WHERE "IsProcessed"=false AND "ApiStatus"=0 AND "CreatedDate"::date=@DTCDate AND (( "IsManual"='B' AND "IsExtSource"=false) OR ("IsManual"='D' AND "IsExtSource"=true))` `ORDER BY CreatedDate ASC, Id ASC LIMIT 500`.

### 2.7 Dispatch
`ProcessAllPendingAsync` writes into a bounded channel (10) and 5 workers dequeue and call `ProcessBatchbackground(tote)` (BALShiplist.cs:L4511 ShipXSync). A tote failure logs and the worker continues.

---

## 3. ShipX §10 ship behavior

Per 10b-dtc-ship.md:

### 3.1 Carrier routing (Step 2)
`ShipmentMethod(shipvia, weight, client)` — one SQL join across `SPX_tblShipCarrier` × `CarrierService` × `servicePack` × `CarrierPacking` × `ClientCarrier` × `Client`, `WHERE MaxWeight > weight AND ServiceCode = shipvia AND ClientCode = client ORDER BY MaxWeight ASC LIMIT 1` — picks the smallest packing that beats the tote weight. `Mode=false` means test account (skip all NDS writeback, still generate label). `cc.AccountNo` = Stamps refresh token for USPS.

### 3.2 STD unwrap (Step 1)
`SHIPVIA_CD='STD'` → `SELECT "STDCode" FROM SPX_tblClient WHERE ClientCode=?` picks client's default. On success, `OEHEAD.SHIPVIA_CD` is updated to the resolved value.

### 3.3 Ship-from address (Step 3)
`FedExFromAddrss(client, carrierId)` — one row per (client × carrier). Includes `ShipmentType` (FSM = FedEx file exchange), `ComInvCopy` (invoice copy count).

### 3.4 Label creation (Step 6)
Switch on `CarrierName`: FedEx → `BALFdExShipApi.CreateDTCShipment`; UPS → `UPSAPIBAL.CreateShipment(obj, true)`; USPS → `BALStamps.CreateDTCUSPSShipment`. Manual path ONLY: if USPS returns "Insufficient account balance", calls `StampAddFunds.AddFundTask` and re-attempts once.

### 3.5 International customs (Step 7)
`TB_SHIPX_DTC_ITEMS_VW` (NDS Production login) per order — HS code, description, country of origin, qty, unit value. `DTCcommercialinvoice` INSERTs into `SPX_tblDTCCommercialInvoice` (no delete-first — regenerate adds duplicate lines).

### 3.6 NDS writeback (Step 8, gated on `Mode=true`)
Three transactions per success:
 1. `BEGIN <client>.PROC_OE.INSERT_OE_TRACKING(order, suffix, SYSDATE, 'BATCHSHIP', '450', null, null, '<tracking>'); END;`
 2. `UPDATE oe_ship_container SET container_id='<tracking>' WHERE TENANT_ID=<c> AND ORDER_NO=<o> AND ORDER_SUFFIX=<s>` (no container filter — every container of the order gets the same tracking).
 3. STD-only: `UPDATE OEHEAD SET SHIPVIA_CD='<resolved>' WHERE ...`

### 3.7 Postgres status updates (Step 9)
`CALL spx_prodtcshipment(53 IN, INOUT id)` upserts into `SPX_tblDTCShipmentlogs`; on failure sets `SPX_tblDtcBatch.ApiStatus=1`. On success, `CALL spx_updatedtcshipment(...)` sets the batch row `IsProcessed=true, ApiStatus=0`. Automatic path also calls `UpdateDtcShipmentErrorStatus(false, true)` to flip `IsErrored=true` on success (yes, backwards-named). Every insert or `ErrorMessage`-touching update fires `trg_shipx_insufficient_funds` (§8 below).

---

## 4. shioai DTC pull counterpart

### 4.1 What exists (all three enumerated)

| Surface | shioai file:line | Wire | Status |
|---|---|---|---|
| `DtcService.pullShippable` (S1-framework path) | `backend/src/main/java/com/multiship/backend/service/dtc/DtcService.java:75-116` | Reads `TB_SHIPX_DTC_UVW` via `NdsTemplates.production()` — SPI-driven, DB-configured Oracle connection via V77 external_system framework. Filters `WHERE BATCH_ID != 0 AND TOTE_NUMBER IS NOT NULL AND ORDER_SUFFIX = 0` in SQL. Persists one row per `OrderImportRowDTO` into `import_batch` + `import_batch_row` with `source='DTC'`. | **Live** — HTTP entry at `POST /api/v1/d2c/pull` (`DtcController.java:88-101`). |
| `OracleDtcSyncService.syncPendingDtcOrders` (legacy path) | `backend/src/main/java/com/multiship/backend/service/oracle/OracleDtcSyncService.java:46-74` | Uses classic Spring `@Transactional("oracleTransactionManager")` datasource (needs `multiship.oracle.enabled=true`). Persists into `dtc_orders` table (V83 unique key on `batch_id, tote_number, tenant_id`). | **Live** — HTTP entry at `POST /api/v1/dtc/sync/oracle` (`OracleDtcSyncController.java:40`). |
| `DtcTimedBackgroundService` scheduled crons | `backend/src/main/java/com/multiship/backend/service/oracle/DtcTimedBackgroundService.java:37-95` | Peak (5 min), Normal (20 min), Off-hours (30 min), FedEx ETD (21:00) — all wired to `OracleDtcSyncService.syncPendingDtcOrders("")`. | **DORMANT** — all four `@Scheduled` annotations commented out at lines 37, 53, 69, 85. Only `manualSyncDtcOrders(tenantId)` (L102) is reachable, and only from unit tests (no HTTP controller wires it). |

### 4.2 What the recent commit did
`git show 8bdd0134` ("Fetch dtc order from local"): +79 LoC on `DtcOrderController.java` (paged list of the `dtc_orders` table already populated by `OracleDtcSyncService`), +25 LoC on `DtcOrderRepository` (JPQL search + distinct tenants), +81 LoC on `dtcService.ts`, +227 LoC `DtcOrdersPage.tsx`, +10 LoC `AppRoutes.tsx`. This is a **read-only browser** of the legacy `dtc_orders` snapshot — no pull, no ship, no writeback. Adds a "Sync from Oracle" button (`DtcOrdersPage.tsx:18`) that calls `POST /dtc/sync/oracle`. Purely FE surface work on the parallel path.

### 4.3 What's missing from pull-side (vs ShipX §10)
Enumerated as gaps in §6.

---

## 5. shioai DTC ship counterpart

**There is no DTC-specific ship dispatcher in shioai.** DTC batches created by `DtcService.pullShippable` land in `import_batch` (source='DTC'), and the shared `ImportGenerationWorker` (`backend/src/main/java/com/multiship/backend/service/ImportGenerationWorker.java:36-98`) picks queued generation jobs (`SELECT … FOR UPDATE SKIP LOCKED` on `import_generation_job`, V57), runs them on a 4-thread pool, and delegates to `OrderImportService` → per-row `CarrierServiceImpl.generateLabel` — the identical code path Bulk Mailer, WMS-pulled orders, and API orders take. Fairness across tenants + carrier rate-limit live inside the generation run, not per-batch.

Writeback: post-label success fires `ExternalSystemWritebackDispatcher` → `NdsOracleConnector.writeShipment` → `NdsShipmentOracleWriter` (V90 source/channel gates decide which of CLIPPER / OE_TRACKING / TB_MANUAL_SHIPMENT are populated per tenant). For DTC-flagged orders, `PROC_OE.INSERT_OE_TRACKING` is called with source="BATCHSHIP" + code="450" mirroring ShipX (`NdsShipmentOracleWriter.java:127-128, 505`; nds-audit G5).

Practical consequence: DTC totes have **no batch-level dispatcher, no 5-worker semaphore, no pipeline advisory lock, no 500-per-tick BatchSize cap.** They rely on the generic ImportGenerationJob queue + rate-limiter to control throughput.

---

## 6. Gaps (§10) — feature-by-feature

Severity: **BLOCKER** (behaviour silently broken), **HIGH** (contract not met, needs code + migration), **MED** (works but hides tunable), **LOW** (cosmetic).

| # | Feature (ShipX) | shioai state | Severity | Impact |
|---|---|---|---|---|
| G1 | Scheduled DTC pull (5 windowed cron bands) | All four `@Scheduled` annotations commented out (`DtcTimedBackgroundService.java:37, 53, 69, 85`). Only manual pull. | **HIGH** | If ops forgets to press "Fetch from NDS" on `/d2c/history`, nothing pulls. No SLA. |
| G2 | `FeatureManagement:DtcSync` pause flag | No equivalent kill switch. `multiship.oracle.enabled` is coarser (gates the whole legacy path). | MED | Can't pause pulls without redeploy or table edit. |
| G3 | `pg_try_advisory_lock(4831175)` cluster-wide dedup | No advisory lock on DtcService. `ImportGenerationJob` uses `SELECT … FOR UPDATE SKIP LOCKED` per-job (fine) but the **pull-into-import_batch step** is unguarded. Content-hash dedup (`DtcService.java:186-193`) catches the immediate re-pull, but a two-node race still creates two `import_batch` rows before hash is checked. | MED | Duplicate batches on multi-node cluster during simultaneous fetches. |
| G4 | `TB_SHIPX_DTC_NEW_DEV` second view + concat | Not read. Only the one view. | LOW (unverified need) | ShipX-side devs unsure if `_NEW_DEV` is prod-live too. Answer via NDS ops. |
| G5 | Suffix policy: `ORDER_SUFFIX=0` OR (`ORDER_SUFFIX>0 AND ORDER_STATUS='S'`) for back orders | `DtcService.java:134` hardcodes `COALESCE(o.ORDER_SUFFIX, 0) = 0`. `OracleDtcOrderRepositoryImpl.java:58-60` (unread) similarly. No back-order path. | HIGH | Back-order labels never generated by shioai. |
| G6 | `IsManual` distinction (M / B / D) | shioai `import_batch.source` stores the string `'DTC'`, no distinction between "auto pipeline" / "manual sync" / "external feed". `DtcOrder` entity (`DtcOrder.java`) also has no `IsManual` column. | MED | Cannot filter admin views by pull origin; loses ShipX's operational lens. |
| G7 | `SPX_tblDtcBatch` `IsExtSource` (external feed, D rows) | No equivalent. shioai never had a D-row writer. | LOW | Deferred until an external feed exists. |
| G8 | Per-tick `BatchSize` limit (500) | No equivalent. `DtcService.pullShippable` pulls the entire view every call. | MED | 10k-order view = 10k rows/batch. Pull throughput and downstream generation queue can spike. |
| G9 | Per-tote 5-worker semaphore + bounded channel | ImportGenerationWorker `max-concurrent-jobs` default 4 (property `import.generation.max-concurrent-jobs`). This is jobs, not totes — one job runs a whole import_batch serially in a single thread (or via carrier rate-limiter fairness). | MED | Per-tenant throughput lower than ShipX's parallel-per-tote design for large batches. |
| G10 | UTC day-boundary pending pick (`CreatedDate::date = today`) | shioai doesn't retry across days — a batch just sits in `import_batch` until generated; there's no per-day carryover concept. | LOW | Different lifecycle, not necessarily worse. |
| G11 | `ClientSTD` unwrap (`SHIPVIA_CD='STD'` → lookup client default) | Unclear — search for STD resolution in shioai returned no `SPX_tblClient.STDCode` equivalent. Client-carrier defaults live in `CarrierAccountRef`. | HIGH | If NDS emits `SHIPVIA_CD='STD'` (as DTC docs describe), the row today either fails validation or picks the wrong carrier. |
| G12 | `WHERE MaxWeight > weight ORDER BY MaxWeight ASC LIMIT 1` packing pick | shioai uses `Packaging` + `ServicePackage` per-carrier compat matrix from Sprint 52 (project_sprint52_packaging_compat). Behaviour differs — no smallest-that-fits rule; picks a service default. | MED | Behavior parity uncertain; needs test coverage. |
| G13 | Weekend/weekday different intervals | Not modelled. `DtcTimedBackgroundService.java:37-53` has separate cron patterns but they're all commented out. | LOW | Solvable when G1 is fixed. |

---

## 7. Hardcoded findings (§10)

| # | File:line | Value | Fix |
|---|---|---|---|
| H1 | `DtcTimedBackgroundService.java:37, 53, 69, 85` | Cron patterns `0 */5 12-21 * * MON-FRI` etc. hardcoded in `@Scheduled` annotation strings AND commented out. | Move to `application.properties` (`multiship.dtc.cron.peak=...`) so ops can adjust without redeploy — mirrors ShipX's `appsettings.json` `DtcSchedule:*` re-read-every-tick pattern. |
| H2 | `DtcService.java:126-135` | 24-column `SELECT` list + `WHERE BATCH_ID != 0 AND TOTE_NUMBER IS NOT NULL AND TRIM(o.TOTE_NUMBER) != '' AND COALESCE(o.ORDER_SUFFIX, 0) = 0` hardcoded. | Externalize per nds-audit B1 recommendation (`nds_query` table or `resources/nds-sql/` templates). |
| H3 | `DtcService.java:161` | Zero-weight fallback to `BigDecimal.ONE` (1 lb). | Comment cites doc §10; leave but note that ShipX 10a doesn't do this — this is shioai's own G8 (BackOrder Quick Ship) rule. Verify with Thoroughbred it's intentional for DTC too. |
| H4 | `OracleDtcSyncService.java:53` (comment) | "with 300s timeout" but no actual `setQueryTimeout` set on the JPA query — comment lies. | Add `hibernate.jdbc.timeout` or `Query.setHint("jakarta.persistence.query.timeout", 300000)`; delete misleading comment. Also flagged by nds-audit E6. |
| H5 | `OracleDtcOrderRepositoryImpl.java:57-60, 84-87` (per nds-audit B2) | `viewName` string-interpolated without the same whitelist `DtcService` applies. | Push whitelist into `OracleDtcConfig` at bind time. |
| H6 | `DtcController.java:88` | `@PreAuthorize("hasRole('ADMIN')")` — role literal, not backed by a DTC-specific permission. | LOW. Consistent with rest of admin surface. |
| H7 | `application-prod.properties:55` + `application-dev.properties:34` | `oracle.dtc.view-name` chosen by Spring profile, orthogonal to V91 env-split. `use_dev=true` on PROD row + `spring.profiles.active=prod` = DEV Oracle + PROD view. | Fold into `NdsOracleConfig` JSON per nds-audit A5/F2. |
| H8 | `OracleDtcSyncController.java:39, 70` | `@PreAuthorize("permitAll()")` on `/dtc/sync/oracle` and `/dtc/pending-count`. | **HIGH.** Should be at least `hasRole('ADMIN')`. This is a public endpoint that fires Oracle traffic. |
| H9 | `DtcTimedBackgroundService.java:24` | `@ConditionalOnProperty("multiship.oracle.enabled")` — coarse gate. | MED. Consider replacing with a runtime feature-flag row so ops can pause without a restart. |
| H10 | Advisory lock key | Not present in shioai; ShipX uses hardcoded `4831175` (`DTCPipeline:AdvisoryLockKey`). | LOW. If G1 lands, pick a distinct key and put in `application.properties`. |

---

## 8. ShipX §11 error handling

### 8.1 Error state model (11-dtc-errors-funds.md L2-L18)
An errored tote = row in `SPX_tblDTCShipmentlogs` with `Status=false` and `IsErrExist=true`. Four retry paths:

| Path | Build | Frequency | Filter | Retry limit |
|---|---|---|---|---|
| **Regenerate** button | Both | User-driven | Selected batch, errored rows | Manual — no cap |
| `DTCErroredBackgroundService` | ShipX only | Every 30 min, **07:00-10:59 Mon-Fri only** | Today's UTC-date + `ErrorMessage LIKE '%Insufficient account balance%'` | **Once** — clears `IsErrExist=false` after retry, so a second fail is orphaned |
| `DtcErroredSweeperService` | ShipXSync only | Every 15 min all day | `TransactionDate < now() - 15 min`, any error, LIMIT 100 | **Unlimited** — `IsErrExist` stays true, so a bad address retries every 15 min forever |
| Notification listener | ShipXSync only | Instant (LISTEN pg_notify) | Semaphore of 4 concurrent | None — self-feeds until success, semaphore full, or feature flag flipped |

### 8.2 Retry SQL (11-dtc-errors-funds.md L36-L94)
```sql
-- ShipXSync sweeper (L34-L44)
SELECT "ShipmentId" FROM public."SPX_tblDTCShipmentlogs"
WHERE "Status" = false AND "IsErrExist" = true AND ("IsManual")::text = '0'
  AND "TransactionDate" < (NOW() AT TIME ZONE 'UTC') - make_interval(mins => 15)
ORDER BY "TransactionDate" LIMIT 100;
```
Reads log row via `DTCShipdetails` (37 columns), reruns carrier via `ProcessAutoBatch`, on success sets `IsLabelGenerated=true, IsErrExist=false, Status=true, TrackingID, ZPLCommand, ErrorMessage='Label created successfully.'`, on fail flips `IsErrExist=true` again and re-inserts via `spx_prodtcshipment(...'Insert')`.

### 8.3 Orphan guard
`TryRegenerateByShipmentId` returns `"ORPHAN_TRACKING:<no>"` if `TrackingID` is not empty — stops a second buy on a row that already got its label but errored downstream (BALShiplist.cs:L3625-L3634 ShipXSync). Only present on sweeper + listener path; Regenerate button lacks it.

### 8.4 Missing-invoice sweeper (11-dtc-errors-funds.md L102-L118)
`DtcErroredSweeperService.SweepMissingInvoicesAsync` — every tick, looks for successful non-US FedEx/UPS DTC rows whose PDF is missing under `<SHIPX_DTC_INVOICE_DIR>\<client>\<MM-dd-yyyy>\<tracking>.pdf` for the last 24 h and re-runs `RegenerateInvoiceOnly`. Fixes a filesystem-hole silently.

### 8.5 `trg_shipx_insufficient_funds` (appendix B.1, B.49)
```sql
CREATE TRIGGER trg_shipx_insufficient_funds
AFTER INSERT OR UPDATE OF "ErrorMessage" ON public."SPX_tblDTCShipmentlogs"
FOR EACH ROW EXECUTE FUNCTION public.notify_shipx_insufficient_funds();

-- function body
IF NEW."ErrorMessage" IS NOT NULL AND length(trim(NEW."ErrorMessage")) > 0 THEN
  PERFORM pg_notify('shipx_insufficient_funds',
    json_build_object('RuleCode','SHIPX_ALERT','ShipmentId', NEW."ShipmentId")::text);
END IF;
```
**Misnamed.** Fires for ANY non-empty `ErrorMessage`: "Cancelled", "Error" from `sp_insertintobatch`, even "Label created successfully." Any consumer must self-filter.

### 8.6 Listener (11-dtc-errors-funds.md L124-L146)
`ShipXNotificationListenerService`:
 - Feature flag `NotificationListener` polled every 30s while off.
 - Dedicated Postgres connection outside the pool, `LISTEN shipx_insufficient_funds`.
 - `SemaphoreSlim(4)` for parallel handlers; on overflow, message dropped (log warning only — recovery relies on sweeper).
 - `HandleInsufficientFundsAsync` calls `TryRegenerateByShipmentId` — no filtering on `Status`, `IsErrExist`, `IsManual`, or error text.
 - Dropped messages during disconnect (5s reconnect loop) are lost.
 - Feeds itself: a failed regenerate INSERTs a new log row → trigger fires → listener regenerates → repeat.

---

## 9. shioai error handling

### 9.1 What exists
| Surface | shioai file:line | Behaviour |
|---|---|---|
| Import batch failure state | `OrderImportServiceImpl.java` — `status="FAILED"` on `import_batch`; per-row `generatedStatus="FAILED"` on `import_batch_row`; startup housekeeper (L850-L900) flips stale `IN_PROGRESS` batches to `FAILED` with a diagnostic message. | Errors surface on the batch view (`/d2c/history` / `/bulk-mailer/history`). |
| Job-level retry via ImportGenerationJob | `ImportGenerationWorker.java:70-98` — `SELECT … FOR UPDATE SKIP LOCKED`, heartbeat every N s, stale-heartbeat job re-queued and picked up (rerun re-syncs rows so labelled ones aren't re-billed). | Server-crash resilience only. NOT per-row retry — a row's `FAILED` state is terminal until user retries manually. |
| Carrier rate-limit retry | `CarrierOutboundRateLimiter.java` — per-tenant token bucket, holds requests when carrier says 429. | Only for throughput, not for insufficient-funds. |
| Void-failure event | `events/VoidFailedEvent.java` — publishes to Redis event bus. | Not connected to any auto-retry consumer. |

### 9.2 What does NOT exist
- **No `DtcErroredBackgroundService` equivalent.** Grep for `@Scheduled` + `dtc|error|sweep` = zero matches. No sweeper on `import_batch_row.generatedStatus='FAILED'`.
- **No `trg_shipx_insufficient_funds` migration.** Grep across `backend/src/main/resources/db/migration/` for `shipx_insufficient_funds|notify_shipx|trg_shipx|SPX_tblFundLogs|SPX_tblFund` = zero files.
- **No `LISTEN` listener.** No `LISTEN` / `NOTIFY` primitives in the Java code. Event bus is Redis-based (`RedisEventConfig.java`).
- **No orphan-tracking guard.** If a `FAILED` row somehow re-runs and its previous attempt DID buy a label, no equivalent to `TryRegenerateByShipmentId`'s `ORPHAN_TRACKING:` return.
- **No missing-invoice sweeper.** No filesystem scan for `<tracking>.pdf`.
- **No per-error-type routing.** The manual-only `ProcessBatch` funds retry (BALShiplist.cs:L3890-L3898) has no shioai counterpart.

### 9.3 What IS caught
- Stamps SOAP fault handler (`StampsConnector.java:3447-3459`) throws `IllegalStateException` on missing `TrackingNumber`, so an MPS batch rollback triggers. Not the same as auto-retry, but at least the row is marked failed rather than showing a null-tracking "success".
- `StampsCreateIndiciumFaultTest.java` verifies the fault-string detection.

---

## 10. ShipX §11 USPS funds

### 10.1 Balance poll (11-dtc-errors-funds.md L160-L188)
`StampAddFunds.AddFundTask(shipvia, weight, client)`:
1. `DALShiplist.ShipmentMethod` → row supplies `ApiKey1` (Stamps client_id), `ApiKey2` (client_secret), `AccountNo` (per-client refresh_token).
2. `POST https://signin.stampsendicia.com/oauth/token` with `grant_type=refresh_token`.
3. `GET https://api.stampsendicia.com/sera/v1/balance` — compares `amount_available` with **20 USD** (hardcoded threshold, StampAddFunds.cs:L56).
4. If < 20: `POST /sera/v1/balance/add-funds` with `{"amount": <n>, "currency":"usd"}` + fresh GUID as `Idempotency-Key`.
5. Log to `SPX_tblFundLogs` regardless of success (`InsertFundsHistory`, DALFundsHistory.cs:L45-L55).

### 10.2 Threshold + per-client amounts (11-dtc-errors-funds.md L173)
Amounts hardcoded in `StampAddFunds.cs:L116-L157` — twice in ShipX (DTCErroredBackgroundService.cs:L245-L286 has its own copy). `SPX_tblFund` (the "Manage Fund" screen) exists but **is never read by any top-up code path** — it's a dead admin table (10a-dtc-pull.md L228-L246, 11-dtc-errors-funds.md L204-L214).

### 10.3 Where top-up is invoked
- `ProcessBatch` (manual DTC New Manual path, BALShiplist.cs:L3895) — once, if USPS returns "Insufficient account balance".
- `ProcessAutoBatch` (Regenerate button, sweeper, listener; L3449 ShipX / L3861 ShipXSync).
- ShipX `DTCErroredBackgroundService` (L133-L137) — only Mon-Fri 07:00-10:59, funds errors only.
- **The automatic first-attempt (`ProcessBatchbackground`) never tops up** — deliberate: it errors, then the sweeper (or listener, in ShipXSync) retries with top-up.

### 10.4 Alert emails (§11 main text, "insufficient-funds signal and alert e-mails" at L3357)
The "[Ship-X] Funds Issue Alert" e-mails are sent by a **program that also listens on `shipx_insufficient_funds`** — but that program is **not in either codebase** (11-dtc-errors-funds.md L207 "UNVERIFIED: … It is not in either codebase.") Ops has no observability of who runs it, template location, or recipient list.

### 10.5 Manage Fund screen (10a-dtc-pull.md L228-L246)
`SPX_tblFund` — one row per (client × currency) with `Amount` and `Isactive`. FE only. Not consumed by any top-up code path.

---

## 11. shioai funds

### 11.1 What exists
| Surface | shioai file:line | Behaviour |
|---|---|---|
| SERA balance endpoint | `StampsConnector.java:1915-2010` — `getAccountBalance(accessToken, environment)` + `getAccountBalanceSera` + `parseSeraBalanceResponse`. Talks `GET /sera/v1/balance`, returns `BalanceResult(carrier, available, maxBalance, currency, status, message, raw)`. | Wired to `CarrierConnector.BalanceResult` (`CarrierConnector.java:538-566`). **No caller in production** — grep for `getAccountBalance(` returns only the connector definition + the account-verify (test) path. |
| SERA OAuth auto-push | `StampsSeraAuthContext.java` (PR-S1, memory doc `project_stamps_com_audit.md`) — pushes the account's `refresh_token` onto `StampsConnector.SERA_REFRESH_TOKEN` ThreadLocal before every Stamps call, clears on close. | Correct SERA auth plumbing. But nothing consumes the balance from it. |
| Fault-string detection | `StampsConnector.java:3434, 3447-3459` — SWSIM `<faultstring>` containing "insufficient postage" (SOAP path only, not SERA REST) is upgraded to `IllegalStateException` → caller marks the label attempt failed. | Symptom detection only. No response. |
| SERA-fail ring buffer + admin dashboard | Memory doc `project_usps_direct_integration.md` mentions PR-G3b fallback-alerts ring buffer for USPS_DIRECT, reused by STAMPS_COM PR-S4. | Different flavor: this is for OAuth auth failures, not fund balance. |

### 11.2 What does NOT exist
- **No balance poll.** No `@Scheduled` on Stamps `getAccountBalance`. No caller anywhere in `main/`.
- **No auto top-up.** No `POST /sera/v1/balance/add-funds` code path. No idempotency-key generator for it. `StampsConnector` has zero mention of add-funds.
- **No `add-funds` insufficient-funds retry.** The `ProcessBatch` manual-once-retry has no shioai equivalent.
- **No `SPX_tblFundLogs` / `fund_logs` migration.** No `SPX_tblFund` / `fund` table.
- **No `notify_shipx_insufficient_funds` function or trigger.** No migration touches this.
- **No alert-email path for low funds.** `MailSender` (`service/mail/MailSender.java:17`) exists as SPI, default `LoggingMailSender` INFO-logs. `SmtpMailSender` optional. No template/recipient list for "funds low".
- **No `SPX_tblFund` "Manage Fund" FE screen.** No per-client top-up-amount admin UI. No threshold-per-tenant setting.
- **No Manage Fund equivalent in `tenant_settings` KV** (which is where per-tenant channel prefs live per `project_tenant_channel_gate.md`).

### 11.3 What differs
- The Sprint 52 track (memory `project_sprint52_markup_warnings.md`) added `MARKUP_REQUIRED_FOR_CLIENT` admin-UX for markup-missing warnings — precedent + template for a similar "USPS-BALANCE-LOW" admin warning card.

---

## 12. Gaps (§11) — feature-by-feature

| # | Feature (ShipX) | shioai state | Severity | Impact |
|---|---|---|---|---|
| F1 | Auto retry on `IsErrExist=true` rows every N minutes | No sweeper on `import_batch_row.generatedStatus='FAILED'`. | **HIGH** | Every carrier hiccup requires a user to re-click Generate. Batches sit "stuck". |
| F2 | Retry policy DB-driven (interval, retry limit, filters) | Not present — no retry policy at all. | HIGH | Behaviour cannot be tuned per-tenant. |
| F3 | `pg_notify` on any DTC log error → auto-regenerate | No LISTEN listener, no trigger, no consumer. | HIGH | No instant recovery from a fixable error (e.g. address correction). |
| F4 | Balance poll + threshold check + auto top-up | Balance endpoint wired but unused. No add-funds implementation. | **BLOCKER** for USPS-heavy tenants — labels fail with "insufficient postage" and the ops team gets no signal until a shipper complains. |
| F5 | Insufficient-funds alert emails | No email path; `MailSender` default is a logger. | HIGH | Ops has no signal at all; ShipX at least has an external "[Ship-X] Funds Issue Alert" emailer program (out-of-band). |
| F6 | Per-tenant top-up amount ("Manage Fund" `SPX_tblFund`) | Table and admin UI both missing. | HIGH | Even if F4 lands, amount must be a global constant unless we build the tenant knob. |
| F7 | Fund purchase log (`SPX_tblFundLogs`) | Not present. | MED | ShipX has audit trail for every add-funds call (success + failure both). shioai has no billing-audit for USPS postage top-ups. |
| F8 | Manual "add funds" screen | Not present (no `SPX_tblFund` FE). | MED | Ops cannot pre-fund an account through shioai. |
| F9 | `SPX_tblFundLogs` funds-history read screen | Not present. | LOW | Reporting gap. |
| F10 | Orphan-tracking guard | Not present. | HIGH | If F1 lands without this guard, a retry that already has tracking will double-buy. |
| F11 | Missing-invoice sweeper (non-US FedEx/UPS PDF re-generate) | Not present. | MED | Blob-retention scheduler exists (`BlobRetentionScheduler.java`), but for the opposite direction (delete). No re-generate-if-missing sweep. |
| F12 | Failure-row observability (backwards-named `IsErrored=true` on success) | shioai's naming is cleaner (`generatedStatus='GENERATED'` / `'FAILED'`) — not a gap. | LOW (positive) | shioai avoids the ShipX-legacy footgun. |
| F13 | Feature flag to pause the retry sweeper / listener | N/A (no sweeper / listener). | – | Fold into F1's design. |
| F14 | Concurrent-retry semaphore (SemaphoreSlim(4)) | ImportGenerationWorker `maxConcurrent=4` default. Same shape. | LOW | Parity available; needs verification that DTC retries route through the same worker. |
| F15 | Health-check age (`TransactionDate < now() - make_interval(mins=>15)`) | Not present; no equivalent freshness check for DTC batches. | LOW | Can piggyback on `ImportBatch.updatedAt`. |

---

## 13. Hardcoded findings (§11)

| # | File:line | Value | Fix |
|---|---|---|---|
| I1 | *(missing)* — no balance threshold constant anywhere | ShipX has `< 20 USD` hardcoded twice (StampAddFunds.cs:L56, DTCErroredBackgroundService.cs:L245-L286). shioai has no threshold at all. | When F4 lands, put threshold in `tenant_settings` KV under key `stamps.low_balance_threshold_usd` per-tenant with a `carrier_config` fallback. |
| I2 | *(missing)* — no add-funds amount table | ShipX code-literal, `SPX_tblFund` unread. | New table `stamps_topup_policy(carrier_account_ref_id, currency, amount_usd, enabled, updated_at)` — one row per Stamps account. |
| I3 | *(missing)* — no idempotency-key generator for `POST /sera/v1/balance/add-funds` | ShipX uses `new GUID()` per attempt (StampAddFunds.cs:L160) — enables double-buy under concurrent retries. | Use `IdempotencyKeys.forStampsTopup(accountId, day, hourBucket)` per memory `project_stamps_com_audit.md` naming convention. |
| I4 | `StampsConnector.java:1915-1926` (SWSIM path) | Message "USPS via Stamps.com (SWSIM): balance query not wired on this connector. Check the Stamps.com dashboard for account balance, or flip carrier.stamps.api-flavor=SERA for a live balance endpoint." | Behaviour is fine (documented degradation). Just note that when F4 lands, SWSIM tenants can't participate — force-migrate to SERA or accept per-tenant partial coverage. |
| I5 | `StampsConnector.java:1943` | `accessToken.contains("-local-")` — magic fallback-token substring. | Move to a `StampsConstants.LOCAL_FALLBACK_MARKER` symbol; add a comment linking to `buildFallbackToken()`. |
| I6 | *(missing)* — no email template store | ShipX out-of-band emailer has hardcoded template. | Build on the `notification` table pattern already used by NDS "email nightly" (per commit `ab8e42d9` — return-email fallback via `tenant_settings.nds.fallback_notify_email`). |
| I7 | *(missing)* — no error-classifier for `insufficient postage` → route to top-up flow | ShipX greps for the string in every retry path. | Add `StampsErrorClassifier.isInsufficientFunds(String faultOrMessage)` — single-source-of-truth for the string match. |
| I8 | Stamps SERA `/balance` `Currency` normalization | `StampsConnector.java:1976-1980` uppercases "usd" → "USD". Fine. | – |
| I9 | `SPX_tblFund` never-read table | ShipX has an admin FE that writes to a table no code reads. | Do NOT port. Build the new `stamps_topup_policy` table + FE together so this footgun doesn't repeat. |
| I10 | `DtcErroredBackgroundService` Mon-Fri 07:00-10:59 window | Hardcoded time range (`DTCErroredBackgroundService.cs:L46-L55`). | If shioa builds an equivalent (F1), put the window in `tenant_settings` `stamps.retry_window_cron` per-tenant (default `* * * * *` = all day). |

---

## 14. Prioritized fix-plan

Ordered by (risk × impact / effort). Cost estimates are +LoC + migration count.

| Priority | Item | LoC | Migrations | Risk | Rationale |
|---|---|---|---|---|---|
| **P0** | **H8** — fix `@PreAuthorize("permitAll()")` on `OracleDtcSyncController` (`/dtc/sync/oracle`, `/dtc/pending-count`) → `hasRole('ADMIN')`. | +2 | 0 | LOW | Public write endpoint that fires Oracle traffic. Every other DTC controller uses ADMIN. Trivial fix, big correctness/security win. |
| **P0** | **G1** — decide the DTC pull scheduler story: either (a) uncomment `DtcTimedBackgroundService` @Scheduled crons and add a `multiship.dtc.cron.*` property block + `FeatureManagement:DtcSync`-equivalent kill switch, or (b) delete `DtcTimedBackgroundService.java` entirely and document `POST /d2c/pull` as the only pull entry. **Decide before shipping F1.** | +40 (a) or −112 (b) | 0 | MED | Today the class is dead-code-with-scheduling-scaffold. Either wire it or delete it. Pick one. |
| **P1** | **F4** — auto top-up flow: caller wraps `StampsConnector.createShipment` in a try; on `IllegalStateException` with `isInsufficientFunds(msg)`, call `getAccountBalance` → if `< threshold`, `POST /sera/v1/balance/add-funds` with idempotency key → retry once. | +180 | 1 (`stamps_topup_policy`) | HIGH | Prepay wallet DOES run empty in ops. Currently silent failure. |
| **P1** | **F1** — sweeper for `import_batch_row.generatedStatus='FAILED'` older than 15 min (default), per-row retry via existing `ImportGenerationJob` queue. Feature-flagged. | +120 | 1 (add `retry_count`, `next_retry_at`, `last_error_class` to `import_batch_row`) | MED | Recovers from carrier hiccups without user re-click. Uses existing worker infrastructure — no new dispatcher. |
| **P1** | **F5** — `MailSender.send(tenantId, "STAMPS_LOW_FUNDS", {balance, threshold, account})` when balance drops below threshold. Template + recipient from `tenant_settings.stamps.low_balance_alert_email` (mirror `tenant_settings.nds.fallback_notify_email` from commit `ab8e42d9`). | +80 | 0 (extends existing KV table) | LOW | Wins ops observability. `LoggingMailSender` default = safe test-mode. |
| **P2** | **F10** — orphan-tracking guard: before retry, if `import_batch_row.tracking_number IS NOT NULL`, skip and mark `SKIPPED_HAS_TRACKING`. Must ship WITH F1 or before it. | +30 | 0 | LOW | One-line predicate at the top of the retry runner. Protects against double-charge. |
| **P2** | **F3** — Postgres `NOTIFY` bridge: trigger on `import_batch_row` state changes fires `pg_notify('shioai_import_row_state', json)`; a dedicated LISTEN thread on a non-pool connection consumes and dispatches immediate retry (with semaphore(4)). ALTERNATIVE: skip and rely on F1's 15-min sweeper — cheaper, no listener lifecycle to manage. | 0 (skip) or +200 (build) | 0 or 1 | HIGH (if built) | Cheapest option: **skip**. F1's sweeper covers the recovery case; instant regenerate is nice-to-have. ShipXSync's listener is a known self-feeding footgun (11-dtc-errors-funds.md L245-L253) — do not port the anti-pattern. |
| **P2** | **G5** — back-order pull path: add `pullBackOrders(tenantId)` reading `WHERE ORDER_SUFFIX > 0 AND ORDER_STATUS='S'`. | +40 | 0 | LOW | Feature-parity with ShipX New Manual Back Orders. Small SQL variant of `DtcService.fetchViewRows`. |
| **P2** | **G11** — STD ship-via resolution: on-import, if `shipViaCode='STD'`, look up `Client.stdShipViaCode` (probably new column) and rewrite. Write back to NDS `OEHEAD.SHIPVIA_CD` post-label success (already partly done for other paths). | +60 | 1 (`ALTER TABLE client ADD std_ship_via_code`) | LOW | Silent misroute today for `STD` orders. |
| **P3** | **H2** — externalize DTC SQL to `resources/nds-sql/dtc-pull.sql.mustache` per nds-audit B1 fix. | +30 | 0 | LOW | Ops-editability. |
| **P3** | **H7** — fold `oracle.dtc.view-name` into `NdsOracleConfig` JSON (per-connection, per-env) per nds-audit A5/F2 — closes the `use_dev=true` + `prod-profile` footgun. | +20 | 1 | LOW | Correctness footgun no ops doc warns about. |
| **P3** | **G3** — advisory-lock the D2C pull step. Reuse `pg_try_advisory_lock` around `DtcService.pullShippable`. | +15 | 0 | LOW | Multi-node race prevention. |
| **P3** | **F6** — per-tenant top-up amount (built as `stamps_topup_policy` in P1 F4). Admin UI. | +150 FE + +40 BE | 0 (already added in F4) | LOW | Complete the tenant-tunable story. |
| **P3** | **F11** — missing-invoice sweeper. Depends on ExternalSystemWritebackDispatcher exposing PDF paths. | +80 | 0 | MED | Nice-to-have. Only fixes filesystem-hole; DB is source of truth. |
| **P4** | **G4** — `TB_SHIPX_DTC_NEW_DEV` second view — ONLY if NDS ops confirms it's live in prod. | +25 | 0 | LOW | Ambiguous need. Confirm first (see open questions). |
| **P4** | **G8** — 500-per-tick `BatchSize` cap on pulls. Add `spring.dtc.pull.batch-size` property; wrap SQL with `FETCH FIRST N ROWS ONLY`. | +8 | 0 | LOW | Only matters at scale. Current bottleneck is the generation worker, not the pull. |
| **P4** | **G6** — persist `IsManual` distinction (`AUTO|MANUAL|EXTERNAL`) on `import_batch` for DTC-sourced batches. | +30 | 1 (add `import_batch.dtc_source_kind`) | LOW | Operational lens; cosmetic today. |

---

## 15. Open questions

1. **Scheduler decision (G1).** Uncomment + wire the four `@Scheduled` methods, or delete `DtcTimedBackgroundService` entirely? The nds-audit's E4 flagged the same question. Blocks F1's design.
2. **Which Oracle view is prod-live?** `TB_SHIPX_DTC_UVW`, `TB_SHIPX_DTC_UVW_TEST`, `TB_SHIPX_DTC_NEW_DEV`, `TB_SHIPX_DTC_NEW`? ShipX docs already note the confusion (combined.md L23-L24). Answer determines G4 fate.
3. **Stamps SERA vs SWSIM tenants.** Any tenant still on SWSIM? SWSIM has no balance endpoint (`StampsConnector.java:1919-1926`) — F4 does not cover them. Force-migrate to SERA, accept partial coverage, or wire SWSIM `GetAccountInfo.PostageBalance` (`StampsConnector.java:1906-1907` says it's exposed but unwired)?
4. **Alert email channel.** ShipX has an out-of-band `[Ship-X] Funds Issue Alert` emailer program that's not in either codebase. Does ops want it re-implemented in shioai, or is a Slack webhook / PagerDuty preferred?
5. **Top-up amount policy.** Fixed per-tenant, sliding based on 7-day burn rate, or manual approval (queue a "pending top-up" for ops)? Behaviour choice, not code.
6. **Threshold currency.** ShipX threshold is 20 USD hardcoded. Multi-currency? SERA response includes `currency` — do we allow per-currency thresholds?
7. **Existing `dtc_orders` table + `DtcOrder` entity.** With the new `DtcService.pullShippable` path (S1-framework) writing to `import_batch`/`import_batch_row`, is the legacy `OracleDtcSyncService` → `dtc_orders` table + the just-shipped commit `8bdd0134` FE viewer meant to stay long-term, or is `dtc_orders` a soon-to-be-retired parallel path? Two paths + two tables reading the same view is a maintenance smell.
8. **Retry limit.** ShipXSync's sweeper retries forever on `IsErrExist=true`; ShipX's caps at once. F1 default? Suggest 5 attempts with exponential backoff, then `PERMANENT_FAILED` with an alert.
9. **Pipeline advisory lock key.** If G1 (a) chosen, pick a distinct key from ShipX's 4831175 (which is hardcoded in ShipX's `appsettings.json`; can't collide, but pick a clean namespace).
10. **`multiship.oracle.enabled` vs `DtcService` gating.** `DtcService` uses `externalSystemConfig.findByName("nds-default").filter(isActive)` (`DtcService.java:69-73`). `DtcTimedBackgroundService` uses `@ConditionalOnProperty("multiship.oracle.enabled")`. Two independent gates for related surfaces — pick one or document why both.

---

## Appendix — investigation trail (files touched)

**shioai (read-only):**
 - `backend/src/main/java/com/multiship/backend/controller/DtcController.java` (103 LoC)
 - `backend/src/main/java/com/multiship/backend/controller/DtcOrderController.java` (79 LoC — from commit `8bdd0134`)
 - `backend/src/main/java/com/multiship/backend/controller/OracleDtcSyncController.java` (91 LoC)
 - `backend/src/main/java/com/multiship/backend/service/dtc/DtcService.java` (288 LoC)
 - `backend/src/main/java/com/multiship/backend/service/oracle/DtcTimedBackgroundService.java` (112 LoC — all @Scheduled commented)
 - `backend/src/main/java/com/multiship/backend/service/oracle/OracleDtcSyncService.java` (230 LoC)
 - `backend/src/main/java/com/multiship/backend/model/DtcOrder.java` (161 LoC)
 - `backend/src/main/java/com/multiship/backend/repository/DtcOrderRepository.java` (94 LoC — 25 new from `8bdd0134`)
 - `backend/src/main/java/com/multiship/backend/service/carriers/StampsConnector.java:1900-2010, 3420-3500` (SERA balance + fault parser)
 - `backend/src/main/java/com/multiship/backend/service/carriers/StampsSeraAuthContext.java` (auth push)
 - `backend/src/main/java/com/multiship/backend/service/ImportGenerationWorker.java:1-98` (queue worker)
 - `backend/src/main/java/com/multiship/backend/config/SchedulerConfig.java` (task scheduler pool)
 - `backend/src/main/java/com/multiship/backend/service/mail/{MailSender,LoggingMailSender}.java` (mail SPI)
 - `backend/src/main/java/com/multiship/backend/service/OrderImportServiceImpl.java` (FAILED-state grep)
 - `multiship-react/src/api/dtcService.ts` (106 LoC — new from `8bdd0134`)
 - `multiship-react/src/components/DtcOrdersPage.tsx` (227 LoC — new from `8bdd0134`)
 - `docs/audits/nds-audit-and-fe-spec.md` (cross-references A5, B1, B2, D4, E4, E6, F2, G5, H2)
 - `backend/src/main/resources/db/migration/` (grepped for `SPX_tblFund|shipx_insufficient|notify_shipx|trg_shipx` → **zero hits**)

**ShipX docs:**
 - `combined.md` §10 L2654-L3272, §11 L3273-L3735
 - `deepdive/10a-dtc-pull.md` (full)
 - `deepdive/10b-dtc-ship.md` (full)
 - `deepdive/11-dtc-errors-funds.md` (full)
 - `appendix-A.md` §A-020 / §A-021 `DALFundsHistory` (L472-L520)
 - `appendix-B.md` §B.1 `notify_shipx_insufficient_funds` (L58-L100), §B.49 `trg_shipx_insufficient_funds` (L2734-L2742)

**Git:**
 - `git show 8bdd0134 --stat` — 5 files, +418 LoC (FE-only + read repo query).
