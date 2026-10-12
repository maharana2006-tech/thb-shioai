# ShipX → shioai handover audit — consolidated report

**Generated:** 2026-09-30 · **All 13 reports landed** (Round 1: 6/6 · Round 2: 7/7)

This is a roll-up of the per-chapter audits under `docs/audits/`. Read this to prioritize; drill into the per-chapter files for evidence and citations.

---

## 1. Report index

| Round | Chapter(s) | Report | Lines |
|-------|------------|--------|-------|
| 1 | §8 Quick Ship | [orders-new-quickship-vs-shipx.md](orders-new-quickship-vs-shipx.md) | 350 |
| 1 | §8 Billable batches | [orders-new-billable-vs-shipx.md](orders-new-billable-vs-shipx.md) | 328 |
| 1 | §8 BackOrder | [orders-new-backorder-vs-shipx.md](orders-new-backorder-vs-shipx.md) | 228 |
| 1 | §12 Manual Shipment | [orders-new-manualshipment-vs-shipx.md](orders-new-manualshipment-vs-shipx.md) | 630 |
| 1 | §12 Return Ship | [orders-new-returns-vs-shipx.md](orders-new-returns-vs-shipx.md) | 430 |
| 1 | NDS cross-cutting | [nds-audit-and-fe-spec.md](nds-audit-and-fe-spec.md) | 400 (sandbox blocked ShipX docs — reconstructed from shioai) |
| 2 | §5 Auth + §6 Setup | [auth-setup-vs-shipx.md](auth-setup-vs-shipx.md) | 380 |
| 2 | §7 Carrier choice + §9 Pick-list | [carrier-choice-picklist-vs-shipx.md](carrier-choice-picklist-vs-shipx.md) | 530 |
| 2 | §10 DTC + §11 Funds | [dtc-funds-vs-shipx.md](dtc-funds-vs-shipx.md) | 530 |
| 2 | §13 Bulk Mailer | [bulk-mailer-vs-shipx.md](bulk-mailer-vs-shipx.md) | 330 |
| 2 | §17 Print + §18 Cancel/Reprint | [print-cancel-vs-shipx.md](print-cancel-vs-shipx.md) | 491 |
| 2 | §19 Jobs + §20 DB schema | [jobs-db-vs-shipx.md](jobs-db-vs-shipx.md) | 316 |
| 2 | External-systems broad audit | [external-systems-broad-audit.md](external-systems-broad-audit.md) | 430 |

**~5,400 lines of audit** across 13 reports. Not planned: §1-§4 (overview/infra), §14-16 (carrier connectors, already covered by carrier-specific tracks in memory), §21-§23 (deploy/known-problems/glossary).

---

## 2. Executive summary — six patterns

Every P0/P1 finding maps to one of these.

| # | Pattern | Blast radius | Where it hits |
|---|---------|--------------|---------------|
| **T1** | **NDS writeback silently drops / targets wrong columns** | Every prod outbound label may be missing its NDS ack | QuickShip F01/F03/F05, Manual F-01, Returns F2, NDS E3, ext-sys N1/L3 |
| **T2** | **Platform defaults leak into per-tenant flows** (THB shipper, THB phone, THB email, JWT default) | Wrong branding + wrong customs + wrong-country classification for non-THB tenants | Manual F-02/F-03/F-04, QuickShip F08, Auth H2, NDS D9 |
| **T3** | **Business reference data lives in code, not DB** (carriers, services, currencies, incoterms, label formats, alias tables, country regions, error humanizers) | Every new carrier/currency/format/country = redeploy | Manual F-05/F-08/F-09/F-10, QuickShip F14-F16, Billable H1/H2/H8/H9, Auth Gap-6-A, Jobs H-1/H-2, NDS 3.11 |
| **T4** | **Batch / MPS lifecycle only half-wired** | Sibling orders in `.Y` batch never get NDS ack; void doesn't cascade | Billable G1/G2/G3, Backorder verifications |
| **T5** | **Missing FE surfaces for existing BE endpoints** | Ops has to curl or lose visibility | Returns F3/F4, NDS 3.9 (journal), NDS 3.8 (probe), NDS 3.1 (tenant-routing), Manual F-06 (NDS list picker), ext-sys §6.1/§6.6, Print PACKING_SLIP routing |
| **T6** | **Security defaults leak into every profile** | Public write endpoints, plaintext secrets, fake email sender | Auth H2 (JWT default), Auth H3 (`/api/v1/dtc/**` permitAll), DTC H8 (`POST /dtc/sync` permitAll), ext-sys M2 (plaintext-fallback secrets), Jobs alert-mail dead (LoggingMailSender) |

---

## 3. P0 fix queue — ship first

Deduplicated across all 13 reports. 27 items. Group A first (security), then B (NDS write correctness), then the rest.

### Group A — Security P0 (~250 LoC, ship in one PR)

| # | Report | Item | LoC |
|---|--------|------|-----|
| A1 | Auth H3 / DTC H8 | Remove `permitAll()` on `/api/v1/dtc/**` and `POST /dtc/sync/oracle` — currently anonymous Oracle traffic in every profile | 20 |
| A2 | Auth H2 | Drop hardcoded JWT default in `application.properties:271`; fail-fast if `JWT_SECRET` unset | 15 |
| A3 | ext-sys M2 | Fail-fast (not plaintext-fallback) when `SECRETS_ENCRYPTION_KEY` unset in prod profile — currently silently stores Oracle passwords / API keys as raw text | 30 |
| A4 | Jobs (implicit) / Auth | Wire real `MailSender` in prod profile; `LoggingMailSender` deploys silently drop every invite/verify/alert email | 40 |

### Group B — NDS write correctness (~430 LoC, small ops confirmations block 3 of these)

| # | Report | Item | LoC | Blocked by |
|---|--------|------|-----|------------|
| B1 | QuickShip F01 | Fix CLIPPER column names (`TRACK`/`FREIGHT` not `TRACKING_NUMBER`/`FREIGHT_AMOUNT`) | 40 | Ops DESCRIBE |
| B2 | QuickShip F02 | Fix intl items to read `TB_CLIPPER_ITEM_DETL` (not test-fixture `OE_INTL_ITEMS`) | 60 | — |
| B3 | QuickShip F03 | Verify OE_TRACKING columns — drop direct UPDATE or discover via `USER_TAB_COLUMNS` | 80 | Ops DESCRIBE |
| B4 | QuickShip F04 | Populate SHIPPED_FLAG + HOLD_FLAG in OEHEAD projection (block gates never fire) | 30 | — |
| B5 | QuickShip F05 | Verify PROC_OE.INSERT_OE_TRACKING param names via dry-run | 20 | Ops DESCRIBE |
| B6 | Billable G1 | Thread `batchId` + `ndsPackages[]` through FE → `ManualShipmentRequest` → writer (else `.Y` writeback never fires) | 120 | — |
| B7 | Billable G2 | Persist `label_batch.billable_batch_id` + fan cancel across siblings | 80 | — |
| B8 | Manual F-01 | Fix duplicate `Q` row on outbound-with-note (`TB_MANUAL_SHIPMENT`) | 15 | — |
| B9 | Returns F1 | USPS_DIRECT return-label branch — reject with clear error or implement (silently ships outbound today) | 40 | — |
| B10 | Returns F2 | Verify `NdsShipmentOracleWriter` void writes `VOID_YN='Y'` on 'R' rows | 20 | — |

### Group C — Shipper/tenant defaults (~340 LoC + 1-2 migrations)

| # | Report | Item | LoC |
|---|--------|------|-----|
| C1 | Manual F-02 / Auth H1 | Move `carrier.shipper.*` platform default to `tenant_settings`; fail loudly on missing | 120 |
| C2 | Manual F-03 | FE `defaultSender()` reads from `tenant_settings` via hook | 60 |
| C3 | Manual F-04 | Consolidate NDS notify email + phone defaults into `tenant_settings.nds.*` | 40 |
| C4 | Manual F-05 | FE + BE stop treating `KNOWN_CARRIERS` as closed set; introduce `carrier_alias` seed | 150 |

### Group D — Ops observability P0 (~1,530 LoC — largest single item is the journal)

| # | Report | Item | LoC |
|---|--------|------|-----|
| D1 | NDS E3 / ext-sys §6.4 (reframed as framework-scoped) | Writeback journal + retry + FE — every connector benefits, not just NDS | 1,200 |
| D2 | NDS E4 / Jobs G1 / DTC G1 | Decide DtcTimedBackgroundService: keep-and-uncomment OR delete. Currently 4 @Scheduled all commented out | 50-150 |
| D3 | NDS Z1 | Startup ERROR + health probe when `NdsShipmentOracleWriter` bean unavailable | 30 |
| D4 | NDS H4+3.8 | Writeback probe FE (BE endpoint already exists at `ExternalSystemsAdminController.java:240`) | 200 |
| D5 | DTC F4 | Auto top-up for USPS/Stamps prepay wallet (`getAccountBalance` has no caller today) | 150 |

### Group E — Small conformance checks (~90 LoC total)

| # | Report | Item | LoC |
|---|--------|------|-----|
| E1 | Backorder | Confirm `.Y` batch path works end-to-end from `/orders/new` (IT + fix if broken) | 40 |
| E2 | Backorder | Verify UPS multi-container writeback when `.X` scan expands to N (IT) | 50 |

**Totals:** 27 items, ~2,640 LoC, 1-2 migrations. Group A is 4 items, ~105 LoC — ship it Monday.

---

## 4. Data-driven-config extraction — the "nothing hardcoded" roll-up

Reference data + business rules that live in code and should become DB rows editable from FE. Reviewed across all 13 reports.

| Target table (new or extend) | Extracted from | Reports | Est LoC | Migration |
|------------------------------|----------------|---------|---------|-----------|
| `carriers(carrier_code, enabled, mode, ...)` (NEW) | 5 hardcoded `@Component` connectors — enabling a carrier = deploy | Auth Gap-6-A | 200 | V-new |
| `carrier_alias(source, target, priority)` | `canon()` heuristics + `KNOWN_CARRIERS` + ERP-alias map duplicated 3× (`CarrierServiceImpl:3301`, `User.java:175`, `ZplLabelService.java:686`) | Manual F-05, Carrier H-7.1/2/3 | 150 | V-new |
| `carrier_label_format(carrier, code, label, is_stock_type)` | FE FedEx/UPS/USPS/DHL label-format dropdowns | Manual F-09 | 180 | V-new |
| `carrier_pickup_type(carrier, code, label)` | Per-carrier pickup enum | Manual F-09 | shared | V-new |
| `reason_for_export(code, label)` | `customsOptions.ts` map | Manual F-10 | 150 | V-new |
| `carrier_clearance_option(carrier, code, label)` | Per-carrier customs vocab | Manual F-10 | shared | V-new |
| `iso_currency(code)` + `tenant_settings.currency_allowlist[]` | 10 currencies today, AED added manually | Manual F-08 | 40 | V-new |
| `country_region(country_code, region_code)` + `region(code, label)` | `CountryRegions.java:17-36` + `multiship-react/src/utils/countries.ts` (mirrored) | Jobs H-2 | 60 | V-new |
| `carrier_error_message(carrier, code, humanized_message)` | `CarrierErrorMessages.humanize` inline switch — ShipX has this as 537-row `SPX_tblCreateShipmentAPIErrors` | Jobs H-1 | 120 | V-new |
| `country.is_us_territory` | Hardcoded US-territory list (BE + FE) | QuickShip F15, Manual F-19 | 30 | ALTER |
| `carriers.family` (USPS/FedEx/UPS/DHL) | `canon()` family switch | QuickShip F16 | 40 | ALTER |
| `external_system_connection.*` marker columns (`CLIPPER`/`BATCHSHIP`/`450`/`STD`/`M`/`R`/`Q`) | QuickShip H07-H09/H18 | QuickShip F07 | 200 | V-new columns |
| `external_system_query_template(system, name, sql)` | 8 hardcoded NDS SQL literals | QuickShip F10, NDS 3.2 | 400 | V-new |
| `nds_column_mapping(system, ndsColumn, dtoField)` | 7 TBD OEHEAD fields + implicit mappings | NDS C1+C2+3.3 | 1,200 | V-new |
| `nds_sync_schedule` + `nds_retry_policy` | `@Scheduled` cron literals + hardcoded backoff | NDS 3.6, DTC H1 | 700 | V-new |
| `nds_carrier_return_policy` | Two Java literals (return-carrier eligibility) | NDS D2+D3+3.11 | 400 | V-new |
| `external_system_writeback_journal` | see §3 D1 above | NDS 3.9 / ext-sys §6.4 | (in D1) | V-new |
| `notification_template(key, subject, body, channel)` | Email subjects inline at `AuthServiceImpl.java:212`, `UserInviteService.java:78`, `PasswordService.java:122` | Jobs (implicit) | 200 | V-new |
| `notification_channel(id, kind, config)` + `notification_subscription(user_id, template_key)` | No channels or subscriptions exist today; only EMAIL is coded and it doesn't work | Jobs (implicit) | 300 | V-new |
| `alert_history(fired_at, template_key, subject, target, payload)` | `UspsFallbackAlertService.Deque` (50 in-memory, wiped on restart) | Jobs (implicit) | 80 | V-new |
| `carrier_api_log(request_id, carrier, url, req_body, resp_body, status, at)` | No durable carrier API log; ShipX has `SPX_Shipment_API_Log` (2K+ rows) | Jobs (implicit) | 250 | V-new |
| `document_type(code, label, renderer)` (or extend `label_template`) | `PrinterService.DOC_TYPES = Set.of("LABEL","COMMERCIAL_INVOICE")` closed set — blocks `PACKING_SLIP` routing | Print H17-1 | 60 | ALTER |
| `commercial_invoice` template lookup wired to `label_template` | `CommercialInvoiceServiceImpl.render:167` ignores template; all layout/colour/header/logo hardcoded | Print H17-2 | 40 | none |
| `tenant_settings.ship_from.*` | `carrier.shipper.*` in `application.properties` | Manual F-02, Auth H1 | 120 | seed |
| `tenant_settings.nds.notify_email` / `nds.default_phone` | Hardcoded `+1 616 772 3513` + `support@thbred.com` | QuickShip F08, Manual F-04, NDS D9 | 40 | seed |
| `tenant_settings.nds.return_order_no_prefix` | Hardcoded `"REN -"` prefix | Returns F7 | 30 | seed |
| `tenant_settings.customs.default_incoterms` + `default_reason` | Hardcoded `"DAP"` + `"SALE"` | Manual F-11 | 30 | seed |
| `tenant_settings.orders_new.bulk_ship_enabled` | Feature flag for backorder greenfield | Backorder P2/P4 | — | seed |
| `carriers.address_charset_whitelist_regex` | `NdsAddressSanitizer.java:24` global whitelist | Billable H2 | 40 | ALTER |
| `carriers.nds_display_name` | NDS display map | Billable H8 | 20 | ALTER |
| `carriers.uses_usps_tracking_fallback` | USPS-family guard | Billable H9 | 15 | ALTER |
| `client.is_master_account` | THB-override for wrong-client scan | QuickShip F18 | 40 | ALTER |
| `orders.ad_hoc` boolean | `"MANUAL"` literal `custNo` | Manual F-18 | 40 | ALTER |
| `role(code)` + `role_permission(role_code, permission)` | `Set.of("USER","TENANT","ADMIN")` allow-list duplicated at `AdminUserService:213` + `AdminUserInviteController:42` | Auth Gap-5-A | 120 | V-new |
| `bulk_upload_max_rows` (config OR `tenant_settings`) | `MAX_BULK_ORDERS = 500` compile-time in `BulkLabelServiceImpl.java:379`; inconsistent with other `@Value` bulk knobs | Bulk H | 10 | config |

**Ship strategy:** three themed PRs — (i) `carrier_*` reference tables + `carrier_alias` (kills the ERP-alias duplication), (ii) `notification_*` + `alert_history` + wire real MailSender (unblocks ops visibility), (iii) `nds_query` + `nds_column_mapping` + `external_system_query_template` (framework-level SQL registry).

---

## 5. Consolidated NDS view

Cross-cuts §8 QuickShip, §8 Billable, §12 Manual, §12 Returns + dedicated NDS + broadened external-systems audits. What the customer sees when they say "NDS is broken."

### 5.1 Read path (NDS → shioai)

| Concern | Status | Report |
|---------|--------|--------|
| `.X` container scan | ✅ works | QuickShip |
| `.Y` batch scan | ⚠️ FE prefill OK, writeback broken (see 5.2) | Billable G1 |
| International items | ❌ reads test-fixture `OE_INTL_ITEMS`, not real `TB_CLIPPER_ITEM_DETL` | QuickShip F02 |
| `SHIPPED_FLAG` / `HOLD_FLAG` in OEHEAD projection | ❌ hardcoded `null`, block gates never fire | QuickShip F04 |
| DTC pull from NDS | ❌ `@Scheduled` commented out; only manual triggers | DTC G1, Jobs J-1 |
| Query SQL editability from FE | ❌ 8 hardcoded Java string literals | NDS 3.2 |
| Column mapping editability from FE | ❌ 7 TBD OEHEAD fields, no editor | NDS 3.3 |
| Pull WHERE editability | ❌ hardcoded | NDS 3.4 |

### 5.2 Write path (shioai → NDS)

| Concern | Status | Report |
|---------|--------|--------|
| CLIPPER `TRACK` / `FREIGHT` columns | ❌ shioai writes `TRACKING_NUMBER` / `FREIGHT_AMOUNT` — silent fail | QuickShip F01 |
| OE_TRACKING direct UPDATE | ⚠️ unverified, "pending DDL confirm" | QuickShip F03 |
| PROC_OE.INSERT_OE_TRACKING param names | ⚠️ unverified, will 500-loop if wrong | QuickShip F05 |
| `.Y` batch CLIPPER writeback | ❌ writer path exists but never called | Billable G1 |
| Bulk cancel across batch siblings | ❌ no equivalent of `spx_updatebillablestatus` | Billable G2 |
| Duplicate `Q` row on outbound-with-note | ❌ writes M + Q; ShipX only writes Q on Quick-Ship | Manual F-01 |
| Return void `VOID_YN='Y'` on 'R' row | ⚠️ unverified | Returns F2 |
| OE_TRACKING skip for returns | ❌ shioai writes for both; ShipX explicitly doesn't | Returns F5 |
| Writeback journal / retry / DLQ | ❌ silent WARN-log then drop (framework-wide, not NDS-only) | NDS E3, ext-sys N1/L3 |

### 5.3 Config/observability path (FE ↔ external_systems)

| Concern | Status | Report |
|---------|--------|--------|
| Connection config (host/port/creds) | ✅ V91 PROD/DEV split, FE at `/settings/external-systems` | — |
| Secrets encryption | ⚠️ plaintext-fallback when `SECRETS_ENCRYPTION_KEY` unset — INFO-log only | ext-sys M2 |
| Tenant-routing management | ⚠️ BE endpoints exist (`ExternalSystemsAdminController.java:183-222`), no FE | NDS 3.1, ext-sys §6.6 |
| Writeback probe | ⚠️ BE endpoint exists (`:240`), no FE | NDS 3.8 |
| Writeback journal | ❌ no table, no FE | NDS 3.9, ext-sys §6.4 |
| Kill switch / pause | ❌ requires redeploy | ext-sys |
| REST_JSON stub timeouts | ❌ `readTimeoutSeconds` + `healthCheckTimeoutSeconds` declared but ignored | ext-sys S2 |
| Multi-connection default | ⚠️ `DEFAULT_CONNECTION_NAME = "nds-default"` compile-time — half of tenants without setting silently write to wrong server | ext-sys |
| `oracle.dtc.view-name` env split | ⚠️ Spring-profile-driven, orthogonal to V91 `use_dev` — footgun | NDS A5+F2 |

---

## 6. Open questions blocking work

Grouped from all 13 reports' "Open questions" sections. Answer these before starting P0 fixes.

### Ops DESCRIBE queries (unblock 5 P0s in one hour)

1. **CLIPPER real column names.** ShipX says `TRACK`/`FREIGHT`; shioai writes `TRACKING_NUMBER`/`FREIGHT_AMOUNT`. Run `SELECT column_name FROM ALL_TAB_COLUMNS WHERE table_name='CLIPPER'` via existing `NdsDebugController`. **Blocks B1.**
2. **OE_TRACKING columns + PROC_OE param names.** Same describe-first approach. **Blocks B3 + B5.**
3. **TB_MANUAL_SHIPMENT.BATCH_ID column.** Does prod NDS have it? **Blocks Billable G3.**

### Product / business calls

4. **NDS Manual List picker** — do ops need `/manual-shipments` browsing, or does `.X`/`.Y` scan suffice? Blocks Manual F-06.
5. **Q-row semantics per carrier** — did ShipX write Q on every note, or only on Quick-Ship/BackOrder? Blocks B8 (Manual F-01) implementation shape.
6. **Returns collapse into `/orders/new`** — deliberate or oversight? Picks between Returns F3/F4 (add surfaces) vs. accepting the collapse.
7. **`.Y` STD substitution** — bug or rule? Locks in default for Billable G5.
8. **Third-party account "use default?" prompt** — still wanted? Decides QuickShip F09 + Manual G-12.
9. **Bulk-ship target scale** — 2-10 tokens or 50+? Decides Backorder P1/P4 investment.
10. **DtcTimedBackgroundService** — keep or kill? Decides D2 direction (50 LoC delete vs 150 LoC wire).
11. **Bulk Mailer as ShipX-flavored CSV mail-mailout** — does any customer still upload the 25-column ShipX CSV? Until answered, don't build the greenfield.
12. **SHIPX_SPECIAL_ACCOUNTS overrides** (MKL246/FSM, ARC805/U30, MHR210/WorldShip) — still active customers? If yes, silent wrong-shipper today. If no, retire from data model.
13. **Pick-list tablet integration** — is anyone still using picklist.thbred.com against shioai? If yes, greenfield ~640 LoC; if no, drop the whole family.
14. **Two parallel DTC pull paths** (S1-framework `DtcService` → `import_batch` vs legacy `OracleDtcSyncService` → `dtc_orders`) — pick one; deprecate the other.

---

## 7. What's next

- **Suggested ship order:**
  - **Sprint 53 Week 1:** Group A (security P0, ~105 LoC, 1 PR)
  - **Sprint 53 Week 1:** Ops DESCRIBEs 1-3 (one hour) → unblocks B1/B3/B5/Billable-G3
  - **Sprint 53 Week 2:** Group B (NDS write correctness, ~430 LoC, 2-3 PRs)
  - **Sprint 53 Week 3:** Group C (shipper/tenant defaults, ~370 LoC, 2 PRs)
  - **Sprint 54:** D1 writeback journal alone (~1,200 LoC, own sprint slice; blocks nothing else, unblocks all future connector observability)
  - **Backlog:** Group D2-D5, all of §4 reference-data extraction, greenfield Manual F-06 / Returns surfaces / Backorder bulk-ship pending open-questions answers

- Nothing new was invented in this file. Every claim is a pointer to a per-chapter report with file:line evidence.
