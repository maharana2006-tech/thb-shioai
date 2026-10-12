# NDS Integration — Audit & Frontend Spec

**Repo:** thb-shioai (dev @ 2026-09-29)
**Author:** Read-only audit; no code changes
**Scope:** all NDS Oracle wiring (framework, connector, writer, prefill, DTC pull, admin UI); FE gaps to reach 100% DB-driven ops
**Bounds of investigation:** shioai backend + `multiship-react` FE. ShipX handover docs (`D:/GIT-REPOS/ShipX/docs/...`) were **inaccessible from the audit sandbox** — findings on doc-anchored contracts (Bulk-Mailer skip, R-row policy, cutoff rules, etc.) are cited via shioai's inline `ShipX_NDS_Orders_and_Tracking.docx §X` back-references, since the shioai code embeds the doc paragraph refs verbatim in comments. See "Open questions" for what still needs first-party doc verification.

---

## 1. Overview

NDS = Thoroughbred NDS, an Oracle WMS. shioai talks to NDS through the S1 **External Systems framework** (`external_system_connection`/`_secret`/`_client_login_override` — V77 tables; V78 seeds one `nds-default` row of `system_type=NDS_ORACLE`). One connector implementation: `NdsOracleConnector` (S2). Two logical login profiles: **PRODUCTION** (cross-client operational login) and **CLIENT** (per-tenant, `username=clientCode` default with per-row override). Hikari pools cached per (connection, profile) and per (connection, clientCode); JDBC URL built on the fly by `NdsJdbcUrlBuilder`.

Four consumer paths:

1. **Prefill** (`.X` container / `.Y` batch scan) — `NdsShipmentLookupService` + `Repository` → PRODUCTION for scan-to-owner, CLIENT for per-order details. Read-only.
2. **DTC pull** ("Fetch from NDS" on `/d2c/history`) — `DtcService` reads `TB_SHIPX_DTC_UVW` via PRODUCTION login, imports one ImportBatch of `source=DTC`.
3. **Legacy DTC sync** — `OracleDtcSyncService` + `OracleDtcOrderRepositoryImpl` — parallel JPA path over the same view, gated by `multiship.oracle.enabled`. All three `@Scheduled` cron triggers currently **commented out** in `DtcTimedBackgroundService`; only the manual entry point is live.
4. **Writeback** (post-label-generate + post-void) — `ExternalSystemWritebackDispatcher` (async) → `NdsOracleConnector.writeShipment` / `.clearShipment` → `NdsShipmentOracleWriter` → three tables: `CLIPPER` (CLIENT schema, composite key), `OE_TRACKING` (CLIENT, via named-column UPDATE **or** `PROC_OE.INSERT_OE_TRACKING` for DTC/USPS-fallback), `TB_MANUAL_SHIPMENT` (PRODUCTION, INSERT-per-label). Optional `OEHEAD.SHIPVIA_CD` update for STD-resolved orders.

Admin surface: `POST /api/v1/admin/external-systems/*` (`ExternalSystemsAdminController`) + FE `/settings/external-systems` (`ExternalSystemsPage.tsx`), with a per-tenant fallbacks card `NdsFallbacksSection.tsx` on `/settings/system`. Environment split: V91 `(name, environment)` with `use_dev` toggle on the PROD row; V90 source/channel writeback gates; V89 field-flag matrix.

---

## 2. PART A — Audit findings

Severity legend: **BLOCKER** (behavior is silently wrong / cannot be operated without ops SQL), **HIGH** (config-value is code-literal / DB migration required to tune), **MED** (works but hides tunable behind property/env), **LOW** (comment / naming / cosmetic).

### 2.1 Connection details

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| A1 | Host/port/service/user/pool params fully DB-driven per row. Confirmed clean. | `V78__external_systems_seed_nds_default.sql:38` (initial seed) + `NdsOracleConfig.java:22-64` (Jackson shape) | — | none |
| A2 | Production **password** stored encrypted in `external_system_secret` under key `productionPassword`; AES-GCM via `CryptoService`. Missing = `SECRET_UNAVAILABLE`. | `NdsOracleConnector.java:70,272-277` | — | none |
| A3 | Client-code login default rule (`username=clientCode + password=clientCode`) is a code literal; override table exists. Correct per NDS convention but the "convention" itself is not DB-configurable — e.g. a new tenant whose Oracle login is `username=clientCode + password=<fixed>` still needs an override row per tenant. | `NdsOracleConnector.java:303-304` | LOW | optional: add `defaultClientPasswordSource` field on `NdsOracleConfig` (`clientCode`, `constant:XXX`, `none`); still keep override table as trump card |
| A4 | Well-known connection name `nds-default` is a compile-time constant, referenced in ≥5 places (`NdsTemplates.NDS_CONNECTION`, `OracleDataSourceConfig.NDS_CONNECTION_NAME`, `ExternalSystemWritebackDispatcher.DEFAULT_CONNECTION_NAME`, `DtcService.isConfigured`, and each callsite). Two servers side-by-side needs a code change even though the pool cache is keyed to allow it. | `NdsTemplates.java:41`, `OracleDataSourceConfig.java:62`, `ExternalSystemWritebackDispatcher.java:58` | MED | route by tenant setting or per-flow property; keep `nds-default` as fallback only |
| A5 | `spring.profiles.active` gates the DTC view via `application-prod.properties:55` and `application-dev.properties:34`. This is orthogonal to V91 env-split — a prod-profile boot pointing at a `nds-default` row whose `use_dev=true` gets DEV Oracle + PROD view. | `application-prod.properties:55`, `application-dev.properties:34`, `OracleDtcConfig.java:19` | MED | move `oracle.dtc.view-name` into the `NdsOracleConfig` JSON blob (per-connection, per-env), delete the property |

### 2.2 SQL queries

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| B1 | **All** NDS SQL is inline Java string constants. No `nds_query` table, no external `.sql` resources. Ops cannot inspect/edit the queries without a redeploy. Blast radius: 8 distinct queries (5 in `NdsShipmentLookupRepository`, 2 in `DtcService`+`OracleDtcOrderRepositoryImpl`, 1 in each of {CLIPPER, OE_TRACKING, TB_MANUAL_SHIPMENT, OEHEAD, OE_SHIP_CONTAINER} in the writer). | `NdsShipmentLookupRepository.java:54,106,170,203,220,248,296,360`, `NdsShipmentOracleWriter.java:97,147,213,275,447,461,511,540`, `DtcService.java:126`, `OracleDtcOrderRepositoryImpl.java:57,82,110` | HIGH | Phase 1: extract SQL to Freemarker/Mustache `.sql` templates under `resources/nds-sql/` keyed by connector `system_type`. Phase 2 (real): `nds_query` table with `(connection_id, name, sql_text, checksum)` + FE editor. Templates on disk fine for MVP; DB-editable is the target once the shape stabilises. |
| B2 | The DTC view name IS a property (`oracle.dtc.view-name`) but string-interpolated into SQL after a regex whitelist (`[A-Za-z0-9_]+`). Guard is present. **However**, the same regex is applied differently between paths: `DtcService.fetchViewRows` validates, `OracleDtcOrderRepositoryImpl.findAllPendingDtcOrders` does not — it directly concatenates `viewName` from the config bean. | `DtcService.java:123` (validates), `OracleDtcOrderRepositoryImpl.java:57` (does not) | HIGH | Push the whitelist check into `OracleDtcConfig` at bind time (throw on invalid) so every consumer is safe by default. |
| B3 | `PROC_OE.INSERT_OE_TRACKING` parameter names (`P_ORDER_NO` etc.) are **inferred** — comment tags this with a `ponytail:` note. First prod failure with "invalid identifier" indicates ops must edit the code. | `NdsShipmentOracleWriter.java:576-599` | HIGH | Store the proc name + parameter list per (connection, proc alias) in a new `nds_stored_proc` table; connector reads at call time. Better UX than "ops edits code + redeploys". |
| B4 | `TB_MANUAL_SHIPMENT` INSERT columns hard-coded in Java (`TENANT_ID, CARRIER, SHIP_SERVICE, ...`) — 20 columns. If NDS adds/renames one, code change + redeploy. | `NdsShipmentOracleWriter.java:275-283` | MED | Same fix as B1 — SQL templates + column mapping table. |

### 2.3 Column mappings (NDS → shioai DTO)

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| C1 | All read-side column mappings are inline `rs.getString("SHIP_NAME")` calls. The comment on `findOrderHeader` explicitly notes **7 fields deferred** with TBD column names (`SHIP_TO_EMAIL, SHIPPED_FLAG, HOLD_FLAG, CUST_PO, DEPARTMENT, INCOTERMS, CURRENCY_CD`). Editing requires code change. | `NdsShipmentLookupRepository.java:106-149` | HIGH | New `nds_column_mapping` table `(connection_id, table_or_view, projection_alias, oracle_column_name, sql_type)`; repository builds `SELECT` from this at query time. Frees the TBD fields to be filled in by ops. |
| C2 | Write-side mappings for `TB_MANUAL_SHIPMENT` (~15 payload → column pairs) hardcoded in `insertTbManualShipment`. Same story for `CLIPPER` and `OE_TRACKING`. | `NdsShipmentOracleWriter.java:246-283, 391-406` | HIGH | Same table as C1 (extended with `payload_field` column); writer builds SET-clause from mapping. |
| C3 | `OracleDtcOrderRepositoryImpl.toOrders` uses reflection + `@Column` annotation matching on `OracleDtcOrder` model class to map `Tuple` → entity. So SELECT * behavior is coupled to the Java `@Entity` — adding a column to `OracleDtcOrder.java` = redeploy. | `OracleDtcOrderRepositoryImpl.java:57, 175-ish` (reflection loop) | MED | Same DB-driven mapping OR at minimum expose the `@Column` names via a health endpoint so ops sees what the app expects. |

### 2.4 Business rules

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| D1 | "Bulk-Mailer parcels never write to NDS" is enforced by **a data seed** (V94 flips `writeback_source_bulk=false` on `nds-default`) — good, editable via FE. | `V94__nds_default_bulk_writeback_off.sql:19` | — | none |
| D2 | FedEx-return R-row skip when `note` is blank is a **code literal**: `if (isReturn && "FEDEX".equalsIgnoreCase(p.carrierCode()) && note blank) skipRRow=true`. Doc §7 anchor. UPS gets the opposite treatment (always writes). Any new carrier joins UPS's side silently. | `NdsShipmentOracleWriter.java:177-180` | MED | New `nds_carrier_return_policy` table `(carrier_code, return_r_row_requires_note BOOLEAN)`, default TRUE (matches UPS), seed FEDEX=TRUE, ships-with-ceiling. |
| D3 | USPS carrier match for the "CLIPPER-not-found → PROC_OE fallback" is a **switch of 3 codes**: `USPS`, `USPS_DIRECT`, `STAMPS_COM`. A new USPS variant is silently excluded. | `NdsShipmentOracleWriter.java:602-608` | MED | `carrier` table gets `is_usps_family BOOLEAN` (or reuse existing carrier metadata if present); switch reads from carrier row. |
| D4 | DTC pull "which rows to import" filter is hardcoded WHERE clause: `BATCH_ID != 0 AND TOTE_NUMBER IS NOT NULL AND ORDER_SUFFIX = 0`. New tenant with different eligibility = ops sees `TOTE_NUMBER` in a bug ticket, files a redeploy. | `DtcService.java:131-134`, `OracleDtcOrderRepositoryImpl.java:58-60,84-87` | HIGH | Same as B1 — SQL template or "extraFilter" JSON on config blob (e.g. `{"pullWhere":"o.BATCH_ID != 0 AND ..."}` — connector concatenates with a bind-parameter contract). |
| D5 | "International = country != US OR US-territory" — the territory set (`PR VI GU AS MP UM`) is a Java `Set.of(...)`. New territory (unlikely but) needs redeploy. | `NdsShipmentLookupService.java:53` | LOW | keep — canonical list of ISO codes; ceiling accepted. |
| D6 | X4 policy `nds.on_missing_weight` is **tenant-scoped via `tenant_settings`** with FE editor at `NdsFallbacksSection.tsx`. Correct pattern — good exemplar. | `NdsShipmentLookupService.java:64, 379` + `NdsFallbacksSection.tsx:24` | — | none — keep as reference for D4 refactor |
| D7 | Hold detection is DB-driven via `client_shipvia_code_map.is_hold` (V100) — good, no more hardcoded `"HLD"`. | `V100__client_shipvia_code_map_is_hold.sql` | — | none |
| D8 | STD resolver ERP-code writeback: whether to fire depends on `p.stdReplacementErpCode != null` — the resolver decision itself (see `StdShipMethodResolver`) sits outside the audit. But the **UPDATE guard** `SHIPVIA_CD IS NULL OR SHIPVIA_CD <> :shipvia` (V96 read-before-write) is inline. Reasonable defensive guard, not tunable behavior. | `NdsShipmentOracleWriter.java:213-218` | — | none |
| D9 | Prefill fallback keys (`nds.fallback_phone`, `nds.fallback_notify_email`, `nds.default_weight_lb`) live in `tenant_settings` — FE covers, good. Platform default constants `DEFAULT_NOTIFY_EMAIL="support@thbred.com"`, `NdsPhoneNormalizer.DEFAULT_PHONE="+1 616 772 3513"` are code literals though. | `NdsShipmentLookupService.java:56`, `NdsPhoneNormalizer.java` (referenced) | LOW | promote platform defaults to `tenant_settings` global scope or a new `platform_settings` row; low impact today because per-tenant overrides work |

### 2.5 Retry / timeout / poll interval

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| E1 | Hikari pool timeouts (`poolConnectionTimeoutMs=10000`, `poolIdleTimeoutMs=600000`, `poolMaxLifetimeMs=1800000`) live in `NdsOracleConfig` JSON — good, per-connection editable. | `NdsOracleConfig.java:56-58` + V78 seed line 38 | — | none |
| E2 | `SELECT 1 FROM DUAL` health-query timeout hardcoded to 5s. | `NdsOracleConnector.java:151` | LOW | promote to `NdsOracleConfig.healthQueryTimeoutSeconds` (default 5) |
| E3 | Writeback dispatcher has **no retry / backoff**. Every dispatch is fire-and-forget async; a `WritebackAck.failed` from the connector is only WARN-logged and dropped. | `ExternalSystemWritebackDispatcher.java:112, 166` | HIGH | Persist failed writebacks to a new `external_system_writeback_journal` table with `(payload_json, attempts, last_error, next_retry_at)`; scheduled sweeper retries with exponential backoff up to N attempts. FE surface to inspect and manually retry. **This is the biggest observability gap in the audit.** |
| E4 | DTC sync scheduler crons are **all commented out** (`DtcTimedBackgroundService.java:37,53,69,85`). Only manual sync is reachable. Comment says "Consider Quartz". So today: the whole background DTC pull is dormant unless ops calls the manual endpoint. | `DtcTimedBackgroundService.java:37-95` | HIGH | Either (a) uncomment + wire — needs the FE tuning story from E5, or (b) delete the class + rely on `DtcService.pullShippable` fired from the FE page. **Decide.** |
| E5 | If (a) chosen: the cron strings are Java literals with no admin toggle. | same file | HIGH (blocked on E4) | new `nds_sync_schedule` table `(connection_id, window_name, cron_expression, active)` OR reuse the `tenant_settings` pattern with keys `nds.sync.peak_cron` etc. FE editor. |
| E6 | Oracle SQL timeout for the legacy DTC sync path: comment in `OracleDtcSyncService:53` says "300s timeout" but no actual `setQueryTimeout` is set on the JPA query. | `OracleDtcSyncService.java:53` (comment vs code) | MED | Add `hibernate.jdbc.timeout` to `OracleDataSourceConfig:105-110` **or** set via `Query.setHint("jakarta.persistence.query.timeout", ...)`. Then delete the misleading comment. |
| E7 | Client pool idle-evict threshold: comment says `NdsOracleConfig.getClientPoolEvictMinutes()` (default 30 min) but the actual eviction code ignores it — uses `ds.getIdleTimeout() + 60_000` instead. Config field is dead. | `NdsOracleConnector.java:230-234` vs `NdsOracleConfig.java:64` | LOW | Either wire the config field into `evictIdleClientPools` OR delete `clientPoolEvictMinutes` from the config. |

### 2.6 Environment routing (PROD/DEV)

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| F1 | V91 env-split shipped and functional; `use_dev` on PROD row flips resolver, FE has env chip + toggle on `/settings/external-systems`. | `V91__external_system_connection_dev_env.sql`, `ExternalSystemsPage.tsx:161-175, 560-590` | — | none |
| F2 | Application-property Oracle-view routing (`oracle.dtc.view-name`) does NOT participate in V91 — it's Spring-profile based (A5 above). PROD row + `use_dev=true` + `spring.profiles.active=prod` reads PROD view. | `application-prod.properties:55` | MED | See A5 fix — fold view name into `NdsOracleConfig`. |
| F3 | `multiship.oracle.enabled=${ORACLE_ENABLED:true}` gates the JPA path (`OracleDataSourceConfig` + `DtcTimedBackgroundService` + `OracleDtcSyncService` + `OracleDtcOrderRepositoryImpl`). Environment variable, not DB. Cannot be flipped without a container restart. | `application.properties:553` | MED | Add `multiship.oracle.enabled` as a `platform_settings` KV OR — given `DtcService` (the new path) already handles "no active connection" gracefully — deprecate the flag entirely and delete the four `@ConditionalOnProperty` guards. |

### 2.7 Writeback payload construction

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| G1 | Field set is FIXED in `WritebackPayload` record (tracking, shipDate, status, carrier, service, freight + shipTo block + package list). Six V89 flags gate each. No way to add a 7th field without code + DB migration. | `WritebackPayload.java` (referenced), `V89__external_system_writeback_flags.sql`, `NdsShipmentOracleWriter.java:52-241` | MED | acceptable ceiling: payload is a stable contract. If new field needed, propose `nds_writeback_extra_field(connection_id, field_key, oracle_column)` with generic JSON on the payload. **Skip until concrete need appears.** |
| G2 | Which OE_TRACKING columns to update on generate is hardcoded (`tracking_no, ship_date, ship_status, carrier_cd, service_cd, freight_amt`) — comment says "kept from V89 pending CLIENT-schema DDL verification". So the whole OE_TRACKING mapping is a placeholder. | `NdsShipmentOracleWriter.java:397-406` | HIGH | Same DB-driven mapping table as C1/C2. **This one is TBD-with-a-timer** — code will diverge from reality the day ops confirms real columns. |
| G3 | Status literal `"VOIDED"` on clear + `"SHIPPED"` on generate + `errorMode "M"`/`"R"`/`"Q"` for TB_MANUAL_SHIPMENT are code literals. | `NdsShipmentOracleWriter.java:171,177,189,413`; probe uses `"SHIPPED"` in `ExternalSystemsAdminController.java:263` | LOW | leave — matches NDS convention; unlikely to change. |
| G4 | Order-no prefix `"REN -"` for return rows (doc §7). | `NdsShipmentOracleWriter.java:173` | LOW | leave |
| G5 | `PROC_OE.INSERT_OE_TRACKING` source values `"BATCHSHIP"` (DTC) and `"CLIPPER"` (USPS fallback) + code `"450"` are literals. Doc §6, §7. | `NdsShipmentOracleWriter.java:127-128, 505` | LOW | leave with doc citation; already commented |
| G6 | Writer's SELECT-schema (i.e. per-tenant `.SCHEMA.CLIPPER`) is IMPLICIT — code uses unqualified `CLIPPER`, `OEHEAD`, `OE_SHIP_CONTAINER`, `OE_TRACKING` and relies on the CLIENT login's default schema. Correct today but a change on the Oracle side ("current_schema = SHIPX_MAIN") silently breaks writes. | `NdsShipmentOracleWriter.java:97, 213, 447-461, 511, 540` | MED | Add `sessionInitSql` on `NdsOracleConfig` (e.g. `ALTER SESSION SET CURRENT_SCHEMA = &1`) applied via Hikari `setConnectionInitSql`; defaults to null (current behaviour). |

### 2.8 Cancel cascade & LABEL_GENERATED audit parity

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| H1 | `clearShipment` symmetry (same six V89 flags gate both directions) is enforced by the dispatcher threading them into `WritebackClearRequest`. Confirmed clean. | `ExternalSystemWritebackDispatcher.java:151-162`, `NdsShipmentOracleWriter.java:292-373` | — | none |
| H2 | DTC clear path (`dtcClearShipment`) deletes `OE_TRACKING WHERE TRACK_CD='BATCHSHIP'` — the `'BATCHSHIP'` value is a hardcoded literal (mirror of G5 write side). Same doc-anchored constant. | `NdsShipmentOracleWriter.java:452` | LOW | leave, cite doc |
| H3 | The clear path for TB_MANUAL_SHIPMENT uses only `TRACKING` as the lookup key. If two label generates happened on the same tracking number (edge case, shouldn't but…), both rows are marked voided. Not a bug per doc but no per-tenant scope. | `NdsShipmentOracleWriter.java:365-368` | LOW | add `TENANT_ID = :tenant` to the WHERE; belt-and-braces |
| H4 | Writeback probe endpoint (`POST /admin/external-systems/{id}/writeback-probe`) supports `mode=GENERATE|CLEAR` + `source` + `channel` + `clientCode` + `orderNo`. No FE surface exists yet — admins fire from curl. | `ExternalSystemsAdminController.java:240-280` | HIGH (UX) | add "Probe" button in edit drawer, next to "Test connection". Spec below. |

### 2.9 Dead / near-dead code

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| Z1 | `NoopNdsShipmentWriteback.java` exists as a lazy-init fallback. Referenced through `ObjectProvider`, so silently activated when `NdsShipmentOracleWriter` bean fails to construct. Every writeback then returns "nds-writer bean unavailable — writeback disabled in this context". **Silent behavior degradation.** | `NoopNdsShipmentWriteback.java`, `NdsOracleConnector.java:166-170` | HIGH | Add a startup ERROR log + a `/actuator/health` sub-check that flags "nds writer unavailable" so this doesn't rot unnoticed. |
| Z2 | `DtcTimedBackgroundService` — all `@Scheduled` methods commented out (E4). Dead as of today; class exists to be manually invoked. | `DtcTimedBackgroundService.java` | MED | see E4 — decide keep-and-uncomment or delete |
| Z3 | `NdsOracleConfig.clientPoolEvictMinutes` — dead field (E7). | `NdsOracleConfig.java:64` | LOW | delete |

---

## 3. PART B — Frontend spec (proposed pages / sections)

Route base: `/settings/*` (SettingsLayout). All ADMIN-role-gated. All DB-driven — no page below reads a Spring property.

### 3.1 NDS connection config — GAP: mostly covered

**Route:** `/settings/external-systems` (exists)
**Purpose:** CRUD `external_system_connection` rows + secrets + client-login overrides + writeback flag matrix + env split.
**Current coverage:** Details tab, Writeback tab (V89 + V90 flags), Secrets tab, Client overrides tab, env chip + `use_dev` toggle. **Test connection** and **health check** exist.
**Gaps:**
- No **Probe writeback** UI (backend endpoint at `writeback-probe` since PR ref in memory; controller confirmed live @ `ExternalSystemsAdminController.java:240`). Add a "Probe" sub-panel in Writeback tab with inputs {mode: GENERATE|CLEAR, source, channel, clientCode, orderNo} and a "Fire" button; response goes to a small activity log at the bottom of the drawer.
- No **tenant-routing** management. Backend supports `GET/PUT/DELETE /{id}/tenant-routings/{tenantCode}` (line 183-222) — no FE. Add a **Routings** sub-tab: table of tenants currently routed to this connection + add/remove.
- No **health-history** view. `/{id}/health` is one-shot. Add a light sparkline of last-24h checks — persist to a new table (see 3.7 below) or just LRU-cache in-memory for the first pass.
- `configJson` is a raw text editor. Fine for the "system_type=REST" case, but for NDS_ORACLE, render a **structured form** (host / port / serviceName / serverMode / productionUsername / clientCodePattern / pool params) — keeps JSON as source of truth, editor is a friendlier alternative.

**Backing tables:** `external_system_connection`, `external_system_secret`, `external_system_client_login_override`, `tenant_settings` (for `writebackConnection` routing).
**New endpoints:** none for the routing gap (already there); optional `GET /{id}/health/history` if persisted.
**Migration risk:** LOW. All additions are read-side or use existing endpoints.

### 3.2 NDS query registry — NEW PAGE

**Route:** `/settings/nds/queries`
**Purpose:** view / edit the SQL queries the app fires against NDS (audit finding B1, C1). MVP scope = **read-only viewer with copy button + syntax highlight**; edit is Phase 2.
**Fields / controls:**
- Table: `query_name` | `system_type` | `login_profile` (PRODUCTION/CLIENT) | `last_updated` | `preview` (first 80 chars)
- Detail drawer: full SQL with syntax highlight, param names (parsed from `:name` tokens), "Copy" button.
- Phase 2: edit textarea + "Validate" (Oracle EXPLAIN PLAN via a new probe endpoint, similar to `writeback-probe`).
**Backing tables (NEW):**
```sql
CREATE TABLE nds_query (
  id BIGSERIAL PRIMARY KEY,
  connection_id BIGINT NOT NULL REFERENCES external_system_connection(id),
  name VARCHAR(80) NOT NULL,          -- e.g. 'findContainerOwner'
  login_profile VARCHAR(20) NOT NULL, -- 'PRODUCTION' or 'CLIENT'
  sql_text TEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(200),
  CONSTRAINT uk_nds_query_conn_name UNIQUE (connection_id, name)
);
```
Seed the eight existing queries via a V102-ish migration; repository looks up by name (Postgres side, not Oracle) and falls through to its Java literal if the row is missing (transparent rollout).
**New endpoints:**
- `GET /api/v1/admin/nds/queries?connectionId=` — list
- `PUT /api/v1/admin/nds/queries/{id}` — edit (Phase 2, ADMIN only, log every change in `audit_log`)
- `POST /api/v1/admin/nds/queries/{id}/explain` — Phase 2 probe
**Migration risk:** MED. Phase 1 (viewer only) is zero-risk. Phase 2 requires a feature flag (`multiship.nds.queries.editable`) because a malformed query breaks label generation for every tenant on the connection. Never make an SQL edit live without a "validate + dry-run" gate.

### 3.3 NDS field-mapping editor — NEW PAGE

**Route:** `/settings/nds/field-mappings`
**Purpose:** view / edit the NDS column → shioai field mapping used by both prefill reads (C1) and writebacks (C2). Backfills the 7 TBD OEHEAD fields.
**Fields / controls:**
- Filter: `connection` dropdown, `table` dropdown (OEHEAD / OE_SHIP_CONTAINER / CLIPPER / OE_TRACKING / TB_MANUAL_SHIPMENT / OE_INTL_ITEMS / OE_SEND_TO / SHIPVIA / TB_SHIP_CONTAINER / TB_BILLABLE_CONTAINERS).
- Table: `payload_field` (shioai side) | `oracle_column` | `sql_type` | `direction` (READ / WRITE / BOTH) | `nullable` | `edit`
- Add-row button; delete-row; save-all.
**Backing tables (NEW):**
```sql
CREATE TABLE nds_column_mapping (
  id BIGSERIAL PRIMARY KEY,
  connection_id BIGINT NOT NULL REFERENCES external_system_connection(id),
  table_name VARCHAR(60) NOT NULL,       -- e.g. 'OEHEAD'
  payload_field VARCHAR(80) NOT NULL,    -- e.g. 'shipToEmail'
  oracle_column VARCHAR(60) NOT NULL,    -- e.g. 'SHIP_TO_EMAIL'
  sql_type VARCHAR(20) NOT NULL DEFAULT 'VARCHAR',
  direction VARCHAR(10) NOT NULL DEFAULT 'BOTH',
  is_nullable BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(200),
  CONSTRAINT uk_nds_mapping UNIQUE (connection_id, table_name, payload_field)
);
```
Seed all current mappings from the code (~40 rows). Repository builds SELECT list from the seeded rows.
**New endpoints:**
- `GET /api/v1/admin/nds/mappings?connectionId=&table=`
- `PUT /api/v1/admin/nds/mappings/{id}`
- `POST /api/v1/admin/nds/mappings` — new row (rare; TBD fields)
- `DELETE /api/v1/admin/nds/mappings/{id}`
**Migration risk:** HIGH. Same as B2 — any mistake breaks reads. Ship behind `multiship.nds.mappings.editable=false` initially; enforce a "dry-run" preview against a PROBE row before saving.

### 3.4 NDS import rules — NEW PAGE

**Route:** `/settings/nds/import-rules`
**Purpose:** view / edit "which NDS rows to pull" (D4).
**Fields / controls:**
- Per-connection form: `pull_view_name` (was `oracle.dtc.view-name`) | `pull_where_clause` (edit box, validated) | `pull_order_by` | `batch_size_limit` | `dedupe_by_content_hash BOOLEAN` (already exists but hidden).
- Preview button: run the SELECT COUNT(*) against Oracle with the proposed WHERE, show row count.
**Backing tables:** extend `external_system_connection.config_json` — the `NdsOracleConfig` shape gains four fields; ONE migration:
```sql
-- V103 conceptual; JSON schema addition, no ALTER needed if config_json is TEXT
-- Application-side: NdsOracleConfig gets pullViewName, pullWhereClause, etc.
```
Alternative: a dedicated table if `config_json` grows unwieldy — decide at implementation time.
**New endpoints:**
- Existing `PUT /admin/external-systems/{id}` (updates `configJson`) — covers write.
- `POST /admin/external-systems/{id}/import-rules/preview` — new, runs COUNT(*).
**Migration risk:** MED. Breaks the fetch if malformed. Guard: reject on save any WHERE that references tables other than `o.` (single-alias); reject any `;` (statement terminator). Feature-flag `multiship.nds.import-rules.editable` for staged rollout.

### 3.5 NDS writeback rules — GAP: mostly covered

**Route:** `/settings/external-systems` → Writeback tab (exists)
**Purpose:** which shioai fields push back on generate/void; which sources/channels enable dispatch.
**Current coverage:** 6 field flags (V89) + 5 source flags (V90+V93) + 2 channel flags (V90) all present. `nds-default` seed of `writeback_source_bulk=false` matches doc.
**Gaps:**
- No **carrier-scoped policy** (D2: FedEx-return-no-note skips R row). Add table below the flag matrix: `carrier_code | return_r_row_requires_note`, editable, seed `FEDEX=TRUE` + `UPS=FALSE` from current code. Store in new table:
```sql
CREATE TABLE nds_carrier_return_policy (
  id BIGSERIAL PRIMARY KEY,
  carrier_code VARCHAR(30) NOT NULL UNIQUE,
  return_r_row_requires_note BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(200)
);
```
- No **STD-resolver preview**. Would help ops see "for this client + this SHIPVIA code, we'd write X" without shipping. Add "Resolve preview" form (clientCode + shipviaCd) → runs `StdShipMethodResolver` → shows `stdReplacementErpCode`. Backend endpoint: `POST /admin/external-systems/std-resolver-preview`.
- **Writeback journal / retry UI** (E3): new sub-tab "Journal" listing recent failed dispatches with retry button. Requires the journal table below (3.9).

**Backing tables:** existing `external_system_connection` writeback columns + new `nds_carrier_return_policy` + (new, 3.9) `external_system_writeback_journal`.
**New endpoints:** `GET/PUT/DELETE /admin/nds/carrier-return-policy`, `POST /admin/external-systems/std-resolver-preview`, `GET/POST /admin/writeback-journal`.
**Migration risk:** LOW. Additive.

### 3.6 NDS retry / schedule / poll tuning — NEW PAGE (or section)

**Route:** `/settings/nds/schedule` (or a section under `/settings/external-systems` per-connection)
**Purpose:** address E3 + E4 + E5.
**Fields / controls:**
- **Sync schedule (E4/E5):** three windows (peak / normal / off-hours) with editable cron expressions + active toggles + timezone dropdown. Preview: "next 5 runs" using a cron parser.
- **DTC sync trigger button** (already reachable via `manualSyncDtcOrders` — surface it here).
- **Writeback retry policy (E3):** max attempts, initial backoff seconds, backoff multiplier, dead-letter threshold. Default: 5 attempts / 30s / 2x / 5-min DLQ.
- **Health-query timeout (E2):** seconds.
- **Session init SQL (G6):** optional textarea for `ALTER SESSION SET CURRENT_SCHEMA = ...`.
**Backing tables (NEW):**
```sql
CREATE TABLE nds_sync_schedule (
  id BIGSERIAL PRIMARY KEY,
  connection_id BIGINT NOT NULL REFERENCES external_system_connection(id),
  window_name VARCHAR(20) NOT NULL,       -- 'PEAK' / 'NORMAL' / 'OFF' / 'ETD_DAILY'
  cron_expression VARCHAR(80) NOT NULL,
  timezone VARCHAR(40) NOT NULL DEFAULT 'UTC',
  active BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT uk_nds_schedule UNIQUE (connection_id, window_name)
);

CREATE TABLE nds_retry_policy (
  connection_id BIGINT PRIMARY KEY REFERENCES external_system_connection(id) ON DELETE CASCADE,
  max_attempts INT NOT NULL DEFAULT 5,
  initial_backoff_seconds INT NOT NULL DEFAULT 30,
  backoff_multiplier NUMERIC(4,2) NOT NULL DEFAULT 2.0,
  dlq_after_minutes INT NOT NULL DEFAULT 5,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(200)
);
```
Also extend `NdsOracleConfig` JSON with `healthQueryTimeoutSeconds` + `sessionInitSql`.
**New endpoints:**
- `GET/PUT /api/v1/admin/nds/schedule?connectionId=`
- `GET/PUT /api/v1/admin/nds/retry-policy?connectionId=`
- Existing `PUT /admin/external-systems/{id}` handles the NdsOracleConfig extensions.
**Migration risk:** HIGH. Bad cron = missed syncs, bad retry = infinite loop. Ship with cron-string validation (Spring's `CronExpression.parse`) + max-attempts hard cap at 20 on the server side.

### 3.7 NDS environment toggle — COVERED

**Route:** `/settings/external-systems` → New Connection or Edit drawer
**Purpose:** V91 env-split; `use_dev` on PROD row.
**Current coverage:** FE `MainTab` renders env dropdown + Use DEV checkbox at `ExternalSystemsPage.tsx:560-590`; the row list shows env chip + arrow-DEV marker at `:161-175`.
**Gap:** none for the DB flag. Related audit item A5 (Spring-profile DTC view name) needs the A5 fix (fold into config JSON) BEFORE this can be a single-source-of-truth toggle. Today an admin can flip `use_dev=true` and still hit the PROD Oracle view because the view name is Spring-profile-based. **This is a subtle footgun documented nowhere.**

### 3.8 Manual test / writeback probe UI — NEW SUB-PANEL

**Route:** `/settings/external-systems` → edit drawer → Writeback tab → new "Probe" section (per H4).
**Purpose:** exercise `POST /admin/external-systems/{id}/writeback-probe` from the UI. Ops today has to curl.
**Fields / controls:**
- `mode`: radio GENERATE / CLEAR
- `source`: select MANUAL / BULK / API / WMS / DTC
- `channel`: select D2C / B2B
- `clientCode`: text input (default "PROBE")
- `orderNo`: number input (default 999999999)
- "Fire" button → shows returned message + a "tail backend log" hint.
**Backing tables:** none new.
**New endpoints:** none — endpoint exists at `ExternalSystemsAdminController.java:240`.
**Migration risk:** LOW. Read-only from the DB perspective. Only side effect is a log line + async dispatch attempt.

### 3.9 Writeback journal & manual retry — NEW PAGE

**Route:** `/settings/nds/journal`
**Purpose:** address E3 — retryable, inspectable trail of every writeback attempt (success + failure). Today `WritebackAck.failed` is dropped to a WARN log with no ops recovery path.
**Fields / controls:**
- Filter: date range, connection, status (OK/SKIPPED/FAILED/DEAD-LETTERED), source, channel, clientCode, orderNo.
- Table: `id | ts | connection | orderNo | clientCode | mode (GEN/CLEAR) | attempts | status | last_error (truncated)`.
- Row click → detail panel with full payload JSON + full error + per-attempt timestamps.
- Row action: **Retry now** (fires the dispatcher again with a fresh attempt).
- Bulk action: **Retry all failed** (careful — surface a confirm).
**Backing tables (NEW):**
```sql
CREATE TABLE external_system_writeback_journal (
  id BIGSERIAL PRIMARY KEY,
  connection_id BIGINT NOT NULL REFERENCES external_system_connection(id),
  ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  mode VARCHAR(10) NOT NULL,            -- GENERATE / CLEAR
  source VARCHAR(20),
  channel VARCHAR(20),
  client_code VARCHAR(64),
  order_no BIGINT,
  tracking_number VARCHAR(64),
  payload_json TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,          -- OK / SKIPPED / FAILED / DEAD_LETTERED
  attempts INT NOT NULL DEFAULT 1,
  last_error TEXT,
  next_retry_at TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_journal_status_next_retry ON external_system_writeback_journal (status, next_retry_at)
    WHERE status = 'FAILED';
CREATE INDEX idx_journal_client_orderno ON external_system_writeback_journal (client_code, order_no);
```
Plus a scheduled sweeper (see 3.6 retry policy) to fire retries.
**New endpoints:**
- `GET /api/v1/admin/writeback-journal?filters...` (paginated)
- `POST /api/v1/admin/writeback-journal/{id}/retry`
- `POST /api/v1/admin/writeback-journal/retry-failed` (bulk)
**Migration risk:** HIGH (rollout). Journalling every dispatch = extra Postgres row per label. At peak 10k labels/day that's fine; sizing note in migration comment. Must be ADMIN-only for retry; audit-log every retry click.

### 3.10 NDS fallbacks — COVERED

**Route:** `/settings/system` → NDS Fallbacks section (exists)
**Purpose:** per-tenant `nds.fallback_phone`, `nds.fallback_notify_email`, `nds.default_weight_lb`, `nds.on_missing_weight`.
**Current coverage:** full — `NdsFallbacksSection.tsx`.
**Gap:** none for functionality. Cosmetic: consider moving under `/settings/nds/` alongside the other NDS pages for discoverability.

### 3.11 NDS carriers / policy — NEW SECTION

**Route:** `/settings/nds/carriers`
**Purpose:** D2 (FedEx-return-no-note-skip) + D3 (USPS-family) — both are code literals today.
**Fields / controls:** table `carrier_code | is_usps_family | return_r_row_requires_note | notes | updated_at`.
**Backing tables (NEW):** `nds_carrier_return_policy` (from 3.5) extended with `is_usps_family`.
**New endpoints:** standard CRUD.
**Migration risk:** LOW.

---

## 4. Combined prioritised plan

Priority: P0 (must have for safe ops), P1 (near-term), P2 (nice), P3 (deferrable ceiling).
LoC estimates are Java + TS + SQL combined, rough order of magnitude.

| Prio | Item | Type | Est LoC | Migrations | Risk | Rationale |
|------|------|------|---------|------------|------|-----------|
| **P0** | E3+3.9 writeback journal + retry + FE | NEW | 1200 | V-new: `external_system_writeback_journal` | HIGH | biggest ops blind spot — failed writebacks silently WARN-log and vanish |
| **P0** | E4 decide DtcTimedBackgroundService keep/kill | FIX | ~50 (delete) or ~150 (uncomment+wire) | none | MED | dormant scheduler pretends to be alive; either commit or delete |
| **P0** | Z1 startup ERROR + health probe when `NdsShipmentOracleWriter` bean unavailable | FIX | ~30 | none | LOW | silent Noop degradation |
| **P0** | H4+3.8 writeback probe FE (backend exists) | NEW | 200 | none | LOW | endpoint is there; ops shouldn't need curl |
| **P1** | A5+F2 fold `oracle.dtc.view-name` into `NdsOracleConfig` JSON | FIX | 100 | none (config-only) | MED | env split gets a footgun that no doc warns about |
| **P1** | 3.1 tenant-routing UI (endpoints exist) | NEW | 250 | none | LOW | routing today invisible; only tenant_settings table shows it |
| **P1** | B2 push view-name whitelist into `OracleDtcConfig` bind | FIX | ~20 | none | HIGH (defence-in-depth) | one path unvalidated; SQL-injection defence gap |
| **P1** | B3+3.2 nds_query registry Phase 1 (viewer) | NEW | 400 | V-new: `nds_query` + seed | MED | audit visibility of the eight SQL literals |
| **P1** | 3.6 nds_sync_schedule + retry_policy tables + FE | NEW | 700 | V-new: `nds_sync_schedule`, `nds_retry_policy` | HIGH | precondition for E5 and E3 retry-behaviour tuning |
| **P1** | E7 wire or delete `clientPoolEvictMinutes` | FIX | ~20 | none | LOW | dead config field |
| **P1** | E6 set JPA query timeout to 300s (matches comment) | FIX | ~10 | none | LOW | comment lies about behaviour |
| **P1** | H3 add TENANT_ID guard to TB_MANUAL_SHIPMENT void UPDATE | FIX | ~5 | none | LOW | belt-and-braces |
| **P2** | C1+C2+3.3 nds_column_mapping table + editor | NEW | 1200 | V-new: `nds_column_mapping` + seed ~40 rows | HIGH | ops can fill the 7 TBD OEHEAD fields |
| **P2** | G2 OE_TRACKING mapping — depends on C1 landing | FIX | (in C1) | (in C1) | HIGH | code has TBD placeholder mapping |
| **P2** | D4+3.4 pull WHERE editable | NEW | 400 | config JSON extension | MED | today ops can't change eligibility without redeploy |
| **P2** | D2+D3+3.11 nds_carrier_return_policy | NEW | 400 | V-new: table + seed | LOW | eliminates two Java literals |
| **P2** | B3+3.2 nds_query registry Phase 2 (editor + EXPLAIN) | NEW | 500 | none | HIGH | FE editor for the queries; needs feature flag |
| **P2** | E2 promote `SELECT 1 FROM DUAL` timeout to config | FIX | ~10 | none | LOW | cosmetic tuning knob |
| **P2** | G6 `sessionInitSql` on NdsOracleConfig | FIX | 30 | none | LOW | future-proofing schema change |
| **P3** | A3 defaultClientPasswordSource | FIX | 60 | none | LOW | only if a tenant appears whose default rule diverges |
| **P3** | A4 route by tenant setting (not compile-time) | FIX | 80 | none | LOW | speculative "two NDS servers" case |
| **P3** | 3.1 health-history sparkline | NEW | 400 | V-new: `external_system_health_snapshot` | LOW | eye-candy; log-scraping works today |
| **P3** | D9 promote platform defaults to KV | FIX | 40 | none | LOW | per-tenant overrides work; hardcoded fallback is fine |

**Total P0 + P1 estimate:** ~3000 LoC, 4 new tables, 2 config-JSON extensions. Roughly 2.5-3 sprint-weeks of focussed work.

---

## 5. Open questions

1. **ShipX handover docs (part-A.md, appendix-C.md, combined.md) were inaccessible from the audit sandbox.** All doc §N citations in this report are transcluded from shioai's inline comments (which reference `ShipX_NDS_Orders_and_Tracking.docx §N` verbatim). Recommend a follow-up pass with direct doc access to verify:
   - Doc §6 DTC generate flow (BATCHSHIP source + code 450)
   - Doc §7 R/Q row policy per carrier
   - Doc §8 Bulk Mailer skip
   - Doc §10 zero-weight → 1 lb rule
   - Doc §1 test-system-never-writes contract
2. **E4 — keep or delete `DtcTimedBackgroundService`?** Every `@Scheduled` inside is commented out. Comment says "Consider Quartz". Do you want the scheduled DTC pull back, or is manual + FE-triggered enough now that `DtcService` exists? Answer determines the shape of 3.6.
3. **PROC_OE.INSERT_OE_TRACKING parameter names.** The `ponytail:` comment at `NdsShipmentOracleWriter.java:576-577` says the names are inferred from Oracle convention. Has ops verified in prod yet? If not, expect first-live failure to be an "invalid identifier" ORA-error.
4. **Client-schema DDL for OE_TRACKING.** Comment at `NdsShipmentOracleWriter.java:141` says "kept from V89 pending CLIENT-schema DDL verification". Which columns actually exist in OE_TRACKING on the client login? Blocks G2 fix.
5. **`OEHEAD.HOLD_FLAG`, `SHIPPED_FLAG`, `CUST_PO`, `DEPARTMENT`, `INCOTERMS`, `CURRENCY_CD`, `SHIP_TO_EMAIL` — real column names on OEHEAD?** All 7 are `null` placeholders today (`NdsShipmentLookupRepository.java:141-149`). Prefill quality depends on filling these.
6. **Retention on writeback journal.** How long should we keep FAILED / DEAD_LETTERED rows? 90 days? Aligns with `retention.*` properties?
7. **Do tenants ever need per-tenant retry policy?** Table above assumes per-connection (matches V89/V90 pattern). If a specific tenant needs "no retry, fail fast" — decide before shipping 3.6.
8. **Env-split + view-name interaction (F2).** Do we treat `oracle.dtc.view-name` as a per-env property (Spring-profile), or fold it into V91 semantics? Today they're independent, which is a footgun.
9. **`NdsOracleConfig.serverMode = "SHARED"` — has anyone ever set it?** Field exists, code passes it through (`NdsJdbcUrlBuilder.build`) but no NDS deployment uses SHARED per the config comment. Consider deleting.
10. **`ExternalSystemsPage.tsx` config JSON textarea vs structured form.** UX preference: JSON everywhere (works for any future connector) vs custom form per system_type. First-party opinion needed.

---

*End of report.*
