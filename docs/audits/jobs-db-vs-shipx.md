# ShipX §19–§20 vs shioai — Jobs, Alerts, Monitoring, Database

**Sandbox probe:** `D:/GIT-REPOS/thb-shioai/docs/audits/probe-jobs-db.txt` write succeeded.
**Read-only investigation.** No shioai code touched.

---

## 1. Overview

### ShipX §19 — Background jobs, alerts and monitoring
Two builds share one Postgres DB:
- **ShipX** (legacy) runs one live job: `DTCErroredBackgroundService` (retries USPS DTC errors on a fixed weekday window). Others commented out.
- **ShipXSync** (rewrite) runs the full DTC pipeline: `MigrationRunner` at start, `DTCTimedBackgroundService` on a hard-coded window schedule with an advisory-lock leader election, `ShipXNotificationListenerService` (Postgres `LISTEN/NOTIFY` on `shipx_insufficient_funds`), `DtcErroredSweeperService` every 15 min, plus a nested FedEx-ETD 21:00 job and a nested DTC-CI invoice-regen sweep.
- Alerts fire from an **external notifier** (source not handed over) that listens on the same signal and uses rules stored in `SPX_ShipXAlertPattern`, logging results into `SPX_NotificationHistory`. The **listener + notifier both fire on the same signal** — one retries, one emails.
- Monitoring: `/health/live`, `/health/ready` (4 checks: postgres / dtc-heartbeat / migrations / oracle), `/metrics` (Prometheus, IP-allowlisted), `/csp-report`. Log files are day-rotated with a 100 MB roll and MDC `X-Correlation-Id`.
- **Source refs:** `docs/handover/source/combined.md:6271-6651`, `docs/handover/source/deepdive/19-history-search.md:1-202`, `docs/handover/source/deepdive/20-jobs-db.md:1-133`.

### ShipX §20 — Schema
- Single Postgres DB `Shipx2`. Snapshot from 14 Sep 2026 backup: **56 tables, 47 procs/functions, 26 FKs (9 not-validated)**.
- Six functional groups: Clients/Carriers, Users/Printers, Shipments (591 006 rows), DTC, Bulk Mailer, Alerts, ISM.
- Reference tables are **DB rows, not code enums**: `SPX_tblShipVia`, `SPX_tblAddressErr` (10 address-error codes), `SPX_tblCreateShipmentAPIErrors` (537 rows), `spx_tblcountry` (248 rows), `SPX_ShipXAlertPattern` (9 rules).
- Multi-tenancy = **`ClientCode` string column** on shipment tables. No RLS. No `tenant_id`.
- **Source refs:** `combined.md:6652-6958`, `appendix-B.md:1-2385` (49 SPs/views/triggers), `appendix-C.md:1-71` (Oracle NDS interop table).

### shioai — where we stand
- Spring Boot 3 / JPA / Flyway. **99 Flyway migrations** (V1–V101, V99 present, V102+ absent). **83 `@Entity` classes**.
- Hibernate `ddl-auto=update` still authoritative alongside Flyway (V1 baseline is empty).
- `/actuator/health`, `/actuator/prometheus` exposed (`application.properties:216`). Micrometer + K8s liveness/readiness probes wired.
- Six live `@Scheduled` beans + one bean whose `@Scheduled` annotations are all **commented out** (`DtcTimedBackgroundService`).
- Alerts = one **in-memory ring buffer** (`UspsFallbackAlertService`, 50 entries) + log lines. No `SmtpMailSender` in prod — the default `MailSender` bean is `LoggingMailSender` which just INFO-logs. No `NOTIFY/LISTEN` on Postgres. No SP-based signal.
- Multi-tenancy = `client_code` / `tenant_code` (`cust_no` on orders, `tenant_code` on the USPS queue), no dedicated `tenants` table, no RLS.

---

## 2. ShipX §19 background jobs inventory

| Job | Build | Purpose | Schedule | DB tables touched |
|---|---|---|---|---|
| `DTCErroredBackgroundService` | ShipX | Retry today's USPS DTC errors; top up Stamps balance; re-attempt label | Weekdays 07:00–10:59, every 30 min (hard-coded) | `SPX_tblDTCShipmentlogs`, `SPX_tblFundLogs` |
| `MigrationRunner` | ShipXSync | Apply new `V*.sql` files on start-up, take advisory-lock 4831174, checksum-guard, index-validity check, two schema-assertion checks | Once at startup | `schema_migrations`, `SPX_tblDTCShipmentlogs` (assert), `spx_prodtcshipment` (assert) |
| `DTCTimedBackgroundService` | ShipXSync | Read DTC orders from NDS + create labels (up to 500 per tick, 5 workers) | Weekdays 06–12 /5m, 12–22 /1m, 22–06 /30m; wknd /5m | `SPX_tblDtcBatch`, `SPX_tblDTCShipmentlogs`, `DTCTaskInfo` |
| FedEx-ETD (nested in above) | ShipXSync | Upload FedEx ETD customs docs daily | 21:00 server time | `SPX_tblEndOfDay`, `SPX_tblDTCCommercialInvoice` |
| `ShipXNotificationListenerService` | ShipXSync | Listen on Postgres `NOTIFY shipx_insufficient_funds`, retry error rows | Always listening; reconnect 5s | `SPX_tblDTCShipmentlogs` (via trigger) |
| `DtcErroredSweeperService` | ShipXSync | Retry errored totes older than 15 min, up to 100 per run | Every 15 min | `SPX_tblDTCShipmentlogs` |
| DTC invoice-regen (nested) | ShipXSync | Re-create missing DTC CI PDFs for last 24h | Every 15 min (same tick) | `SPX_tblDTCCommercialInvoice` |
| **External** `ShipX ISM Worker` | External (`TB-FEDEX2019`) | Verify + regenerate international commercial invoices, email report | Scheduled `CI_SCAN` at 23:45 EST | `SPX_ISM_*` (10 tables) |
| **External** Alert notifier | Unknown host | Listen `shipx_insufficient_funds`; email operators using rules | Always listening | `SPX_ShipXAlertPattern`, `SPX_NotificationHistory` |
| **External** FedEx Ship Manager | Separate server | `.in` → `.OUT` file exchange | File-driven | `C:\batch` (filesystem only) |
| **External** Pick-pack tablet | 192.168.3.65 / picklist.thbred.com | Receives label results | On-demand | (its own) |

**Ref:** `combined.md:6281-6321` (both-builds table), `L6323-L6335` (external programs).

---

## 3. shioai jobs inventory

| Bean | File:line | Purpose | Schedule | DB tables |
|---|---|---|---|---|
| `SchedulerConfig` (bean only) | `backend/src/main/java/com/multiship/backend/config/SchedulerConfig.java:40-48` | Bounds `taskScheduler` to 4 threads (P1 audit fix) | n/a | n/a |
| `UspsLabelQueueProcessor.tick()` | `backend/src/main/java/com/multiship/backend/service/carriers/usps/queue/UspsLabelQueueProcessor.java:160` | USPS_DIRECT persistent-queue drain, fair-scheduled | `${usps.direct.queue.tick-interval-ms:65000}` (65 s default, **config-driven**) | `usps_label_queue` |
| `ImportGenerationWorker.poll()` | `backend/src/main/java/com/multiship/backend/service/ImportGenerationWorker.java:71` | Claim + run queued import-generation jobs via `SELECT FOR UPDATE SKIP LOCKED` | `${import.generation.poll-ms:1000}` | `import_generation_job`, `import_batch` |
| `ImportGenerationWorker.heartbeat()` | `.../ImportGenerationWorker.java:124` | Worker liveness for the running-job rows | `${import.generation.heartbeat-ms:15000}` | `import_generation_job` |
| `ImportGenerationWorker.requeueStale()` | `.../ImportGenerationWorker.java:135` | Requeue jobs whose worker died | `${import.generation.requeue-check-ms:30000}` | `import_generation_job` |
| `ScheduledReportRunner.tick()` | `backend/src/main/java/com/multiship/backend/service/ScheduledReportRunner.java:53` | Advance `nextRunAt` and deliver eligible schedules (dashboard / webhook; EMAIL rejected) | `fixedDelay=60_000L` (hard-coded) | `scheduled_report`, `generated_report` |
| `ApiKeyExpiryWarner.warnExpiring()` | `backend/src/main/java/com/multiship/backend/service/ApiKeyExpiryWarner.java:30` | WARN-log keys expiring in 7d / 24h; no email wired | `cron="0 7 2 * * *"` (hard-coded UTC) | `api_key` |
| `BlobRetentionScheduler.runNightlyRetention()` | `backend/src/main/java/com/multiship/backend/service/retention/BlobRetentionScheduler.java:61` | Two-phase (null-blob → delete-row) on `webhook_delivery` / `audit_log` / `generated_report` / `import_batch` | `cron="0 15 3 * * *"` | 4 tables above + `import_batch_row` |
| `ImportStagingCleanup.purgeExpiredUploads()` | `backend/src/main/java/com/multiship/backend/service/retention/ImportStagingCleanup.java:23` | Purge staged uploads past retention | `cron="0 45 3 * * *"` | `import_staging_upload` |
| `PrinterScanAgentMetrics.refresh()` | `backend/src/main/java/com/multiship/backend/metrics/PrinterScanAgentMetrics.java:79` | Refresh scan-agent last-seen gauge for Prometheus | `fixedRate=REFRESH_MS, initialDelay=30_000L` | `printer_scan_agent` |
| `DtcTimedBackgroundService.*` | `backend/src/main/java/com/multiship/backend/service/oracle/DtcTimedBackgroundService.java:37,53,69,85` | **DEAD CODE** — all 4 `@Scheduled` annotations commented out; only `manualSyncDtcOrders(tenantId)` invocable | (none) | `dtc_orders` (via `OracleDtcSyncService`) |

**Live bean count: 6 (+ 1 config bean, + 1 commented-out).**
Also worth naming (not `@Scheduled` themselves but scheduler-adjacent):
- `WebhookAsyncConfig` — bounded async executor for outbound webhooks.
- `UspsLabelQueueFairScheduler` — pure state, invoked from the tick.
- `NdsOracleConnector.evictIdleClientPools()` — has a Javadoc saying "wire this to `@Scheduled` in S4"; currently not wired (`.../NdsOracleConnector.java:218`).

---

## 4. Job coverage matrix (ShipX → shioai)

| ShipX job | Ported? | shioai equivalent | Notes |
|---|---|---|---|
| `DTCErroredBackgroundService` (Stamps top-up + retry) | **Partial** | `UspsLabelQueueProcessor` handles retries via `retry_count`; **Stamps balance top-up is NOT implemented** | Memory doc `project_stamps_com_audit.md` shipped a SERA-auth context but not the fund-top-up loop |
| `MigrationRunner` (schema check, advisory lock, drift refuse) | **Yes (Flyway)** | Flyway with `baseline-on-migrate=true`; V1 is empty | Flyway itself provides lock + checksum. shioai does NOT do the two extra schema-assertion checks (arg count, column type) — Hibernate `ddl-auto=update` supplants them |
| `DTCTimedBackgroundService` (window-based tick) | **DEAD** | `DtcTimedBackgroundService` bean exists but all `@Scheduled` commented out (`:37, :53, :69, :85`) | Only `manualSyncDtcOrders` remains. `ImportGenerationWorker` fills a different niche |
| FedEx-ETD 21:00 nested job | **No** | (none) | `syncFedexEtdDaily()` at `.java:86` has a body of `// TODO: Implement FedEx ETD upload logic` |
| `ShipXNotificationListenerService` (Postgres LISTEN/NOTIFY) | **No** | (none) | shioai has no LISTEN/NOTIFY consumers. Grep found only comments |
| `DtcErroredSweeperService` (15-min errored retry) | **Partial** | Retry lives in `UspsLabelQueueProcessor` (single carrier). No cross-carrier errored sweeper | |
| DTC invoice-regen sweep | **No** | (none) | Invoices are generated inline in `CustomsServiceImpl`; no re-scanner |
| **External** Alert notifier (email on DB signal) | **No** | Only `UspsFallbackAlertService` (in-memory ring buffer, USPS/Stamps only). `LoggingMailSender` logs and drops. `ScheduledReportRunner.EMAIL` deliberately throws `IllegalStateException` (`:151-153`) — feature disabled | Memory doc says PR-G3b landed the ring buffer for the dashboard; no email/Slack/SMTP wiring anywhere |
| **External** ISM worker (nightly CI regen) | **No** | (none) | International commercial invoices generated inline; no post-hoc verifier |

---

## 5. Gaps (§19)

### G-JOB-1 (HIGH) — DTC pull-from-NDS timer is dead
`DtcTimedBackgroundService` has 4 `@Scheduled` methods, **all commented out** (`.../oracle/DtcTimedBackgroundService.java:37, 53, 69, 85`). The class is behind `@ConditionalOnProperty(multiship.oracle.enabled=true)`, meaning production ships without a running timer. ShipX depends on this loop to move NDS DTC totes into the local shipping queue; shioai currently only reacts to on-demand `manualSyncDtcOrders` calls.

### G-JOB-2 (HIGH) — No DB-signal listener, no email pipe
ShipX's alert-plus-retry design has two consumers on `NOTIFY shipx_insufficient_funds`: one retries, one emails. shioai has **neither**. Any Postgres-driven event fan-out has to be manually invoked from a controller. Combined with `LoggingMailSender` shipping to prod (`service/mail/LoggingMailSender.java:22`), no operator-visible error notifications leave the process.

### G-JOB-3 (HIGH) — Alert channel is in-memory only
`UspsFallbackAlertService` keeps 50 alerts in a `Deque` (`service/carriers/usps/queue/UspsFallbackAlertService.java:44`). Restart wipes them. There is no ledger of "what alerts have we ever raised" and no way to route them to a channel other than the admin dashboard poll. Compare to ShipX's `SPX_NotificationHistory` (208 rows across 4+ months in the sample) which is the durable audit trail.

### G-JOB-4 (MED) — Job schedules are code-hardcoded
Every `@Scheduled` uses a hard-coded cron or `fixedDelayString` from application properties. There is no `job_schedule` table, no admin FE to change cadence, no start/stop/pause toggle. ShipXSync's `DtcSchedule:*` config is at least *hot-reloadable from appsettings*; shioai needs a restart to change `cron="0 7 2 * * *"`.

### G-JOB-5 (MED) — No FedEx ETD nightly upload
`syncFedexEtdDaily()` is a `// TODO`. FedEx electronic trade documents for international DTC are not shipped nightly. ShipX operators uploaded them at 21:00; shioai has no equivalent.

### G-JOB-6 (LOW) — No health-check for downstream Oracle / carriers
ShipX exposes `/health/ready` with four gated checks (postgres, dtc-heartbeat, migrations, oracle). shioai exposes `/actuator/health` with Spring's defaults + `probes.enabled=true`. There's no custom `HealthIndicator` for the Oracle NDS pool, no `HealthIndicator` for the USPS queue backlog, no `dtc-heartbeat` equivalent. Only `ExternalSystemHealthIndicator` exists — it covers the generic connector framework, not the operational SLOs.

### G-JOB-7 (LOW) — No DTC advisory-lock leader election
ShipXSync uses `pg_try_advisory_lock(4831175)` so only one replica runs the DTC pipeline. shioai's `ImportGenerationWorker` uses `SELECT FOR UPDATE SKIP LOCKED` (row-level, safe across replicas) — that's fine. But if the commented-out DTC timer is revived, it needs advisory-lock leader election because the current shape sync-alls-tenants inside the tick.

### G-JOB-8 (LOW) — No metrics-scrape IP allowlist
ShipXSync's `/metrics` requires the caller to be local or in `MetricsScrapeAllowedIPs`. shioai exposes `/actuator/prometheus` (`application.properties:216`) with no IP allowlist. In dev this is fine; production K8s should scope this via Ingress / NetworkPolicy, but if the pod is reachable, the metrics are readable.

---

## 6. Hardcoded findings (§19)

| # | Finding | File:line | Fix |
|---|---|---|---|
| H-1 | `ApiKeyExpiryWarner` cron `"0 7 2 * * *"` and window "24h / 7d" are constants in code | `service/ApiKeyExpiryWarner.java:30, 33-34` | Move cron + windows to `application.properties` or a `job_schedule` row keyed by `job_name='api-key-expiry'` |
| H-2 | `BlobRetentionScheduler` cron `"0 15 3 * * *"` hardcoded | `service/retention/BlobRetentionScheduler.java:61` | Same as H-1; the retention *windows* are already in `RetentionProperties`, only the cron is baked |
| H-3 | `ImportStagingCleanup` cron `"0 45 3 * * *"` hardcoded | `service/retention/ImportStagingCleanup.java:23` | Same |
| H-4 | `ScheduledReportRunner` interval `fixedDelay=60_000L` hardcoded | `service/ScheduledReportRunner.java:53` | Move to property |
| H-5 | `WEBHOOK_MAX_ATTEMPTS = 3` and `webhookBackoffsMs = {0, 2000, 10000}` are code constants | `service/ScheduledReportRunner.java:39, 41` | Extract to properties so ops can tune backoff without a rebuild |
| H-6 | `UspsFallbackAlertService` max entries is `${usps.direct.queue.fallback-alerts.max:50}` but the *fallback* is `50` in code | `service/carriers/usps/queue/UspsFallbackAlertService.java:47, 50` | Fine as-is; document the property in the ops runbook |
| H-7 | `DtcTimedBackgroundService` all four crons are code-hardcoded (`"0 */5 12-21 * * MON-FRI"` etc.) inside the annotation | `service/oracle/DtcTimedBackgroundService.java:37, 53, 69, 85` | If you revive them, promote to `${dtc.schedule.peak-cron:0 */5 12-21 * * MON-FRI}` so they're hot-tweakable |
| H-8 | Alert recipient list is nowhere — `LoggingMailSender` INFO-logs everything | `service/mail/LoggingMailSender.java:22`, `service/PasswordService.java:116-122` | Introduce `SmtpMailSender` bean AND move recipient lists to a per-tenant table (`tenant_settings.setting_key='opsAlertRecipients'` OR a dedicated `alert_recipient` table) |
| H-9 | Alert templates: subjects like `"Verify your email"`, `"You're invited to shioai"` are inline strings | `service/AuthServiceImpl.java:212`, `service/UserInviteService.java:78` | Move to a `notification_template` table with markdown / handlebars body + subject; loaded on first use |
| H-10 | Alert channels: only "email" via `MailSender`. No Slack, no PagerDuty, no webhook | (n/a — feature absent) | Introduce a channel-adapter SPI so each `notification_template` can target `EMAIL` \| `SLACK` \| `WEBHOOK`, keyed off a tenant-level `notification_channel` table |
| H-11 | Retry policy per job: only `ScheduledReportRunner` retries the webhook. Every other job either retries (`ImportGenerationWorker.requeueStale`) or does not (`ApiKeyExpiryWarner`, `BlobRetentionScheduler`) | (spread across files) | Standardise a `RetryPolicy` bean keyed by job name; store in `job_config(job_name, max_attempts, backoff_ms)` |
| H-12 | Retention windows have a `RetentionProperties` bean (good) but per-tenant override is not possible — a customer that wants 2-year audit vs 90-day audit has to fork the config | `service/retention/BlobRetentionScheduler.java:52`, `config/RetentionProperties.java` (not read here) | Add `retention_override(tenant_code, table_name, blob_days, row_days)` and merge over the bean at run time |
| H-13 | Job dashboard: no controller under `controller/JobDashboard*` — grep returned zero hits | (n/a) | Introduce `JobDashboardController` that exposes `GET /admin/jobs` listing every `@Scheduled` bean, last run, next run, error, and a `POST /admin/jobs/{name}/run-now` trigger |
| H-14 | `/actuator/prometheus` has no IP allowlist | `application.properties:216` | Add a filter or ingress rule allowing only the prometheus scraper. Alternative: `management.server.port` on a private interface |
| H-15 | `CarrierErrorMessages.humanize` is a switch statement with hardcoded carrier names and hardcoded English error phrases | `util/CarrierErrorMessages.java:28-73` | Move keyed error phrases to `carrier_error_pattern(carrier, match_pattern, humanized_text, i18n_key)` — mirrors ShipX's `SPX_tblCreateShipmentAPIErrors` (537 rows) |

---

## 7. ShipX §20 schema inventory

### Groups (from `combined.md:6658-6693`)

| Group | Tables (rows in 14-Sep-2026 snapshot) | Purpose |
|---|---|---|
| Clients & carriers | `SPX_tblClient` (54), `SPX_tblShipCarrier` (4), `SPX_tblCarrierService` (72), `SPX_tblCarrierPacking` (15), `SPX_tblservicePack` (131), `SPX_tblClientCarrier` (82), `SPX_tblimporter` (6), `SPX_tblimporterAcc` (1) | Client master + carrier config + service/pack compatibility + importers |
| Users & printers | `SPX_tblloginCredential` (9), `SPX_tbluserrole` (3), `SPX_tblShipXUser` (9), `SPX_tblemployee` (15), `SPX_tblPrinter` (25), `SPX_tblshipstation` (4), `SPX_tblusershipstation` (12), `SPX_tblclientshipstation` (13) | Users, printer assignments, ship-station links |
| Shipments | `SPX_tblShipmentDetails` (591 006), `SPX_tblReturnDetails` (35), `SPX_tblCommercialInvoice` (27), `SPX_tblrequestlog` (14 936) | Every quick/BackOrder/API/manual label; returns; CI lines; picklist API requests |
| DTC | `SPX_tblDtcBatch` (8 443), `SPX_tblDTCShipmentlogs` (5 787), `SPX_tblDTCCommercialInvoice` (97) | DTC tote list + attempts + customs |
| Bulk mailer | `SPX_tblbulkmailerBatch` (201), `SPX_tblexcelupload` (3 394) | Uploaded files + rows |
| USPS funds | `SPX_tblFund` (4, unused), `SPX_tblFundLogs` (96) | Stamps top-up history |
| Logs | `SPX_Shipment_API_Log` (2 047), `spx_tblshipxrequestlog` (302), `SPX_tblReadError` (32), `SPX_tblEndOfDay` (1) | Raw carrier req/rep, audit, error dump, ETD confirms |
| Alerts | `SPX_ShipXAlertPattern` (9), `SPX_NotificationHistory` (208) | Rules + sent |
| ISM | 10 `SPX_ISM_*` tables | External ISM worker set-up + runs + results |
| ShipXSync-only | `schema_migrations`, `SessionCache`, `DTCTaskInfo` | Flyway-equiv, sessions, DTC-leader row |
| Lookups | `SPX_tbluserrole`, `SPX_tblShipVia`, `SPX_tblAddressErr`, `SPX_tblCreateShipmentAPIErrors` (537), `spx_tblcountry` (248) | Reference data in DB |
| Unused leftovers | `SPX_tblCommercialInvoice_backup_20260515`, `v_max_batch_id`, `SPX_tblOeheadAddress`, `spx_addresshistorylog`, `SPX_tblDtcBatchTesting` | Prod cruft |

### SPs & views (`appendix-B.md:58-2775`, 49 items)
`spx_pro<thing>` = CRUD SPs (Insert/Update/Delete branch); `spx_update<thing>` = status flip. 40 of 46 SPs commit their own tx and always report "-1 rows" back to the caller, meaning ShipX's app-side "0 rows = failed" checks never fire. Trigger `trg_shipx_insufficient_funds` fires on ANY error message (not just "insufficient funds") — the reason both listener + notifier react.

### NDS (Oracle) tables ShipX reads/writes (`appendix-C.md:11-71`)
`CLIPPER`, `ITEM_MASTER`, `OEDETL`, `OEHEAD`, `OE_SEND_TO`, `OE_SHIP_CONTAINER`, `OE_TRACKING`, `PROC_OE.INSERT_OE_TRACKING`, `SHIPVIA`, `TB_BILLABLE_CONTAINERS`, `TB_CLIPPER_ITEM_DETL`, `TB_MANUAL_SHIPMENT`, `TB_SHIPX_DTC_ITEMS_VW`, `TB_SHIPX_DTC_NEW_DEV`, `TB_SHIPX_DTC_UVW`, `TB_SHIPX_DTC_UVW_TEST`, `TB_SHIPX_WORLDSHIP_VW`, `TB_SHIP_CONTAINER`, `production.SHIPX_SPECIAL_ACCOUNTS`, `production.SHIPX_DEV_SPECIAL_ACCOUNTS`. Two login modes: **Production** (shared) and **Client** (per-client username = client code).

---

## 8. shioai schema inventory

### Migrations: 99 files V1–V101 (V99 present; V102 absent). V1 empty (baseline).

### Entity → table mapping (83 `@Entity` classes)

**Auth / users** — `users`, `password_reset_tokens`, `user_admin_audit`, `user_invites`, `signup_attempts`, `api_key`
**Clients & carriers** — `clients`, `carrier_config`, `carrier_account_ref`, `carrier_shipping_limit`, `carrier_package_catalog`, `carrier_webhook_events`, `ship_vias`, `shipvia_service_mapping`, `shipping_service`, `service_package`, `client_shipvia_code_map`, `client_service_code_map`, `client_package_code_map`, `client_dest_country_map`, `client_allowed_service`, `client_allowed_package`, `client_allowed_service_destination`, `client_allowed_service_warehouse`, `client_destination_rule`, `client_shipping_policy`, `client_billing_markup`, `client_output_destination`, `client_customs_profile`, `client_warehouse`, `customs_profile_country`, `warehouse`
**Orders / shipments** — `label_batch` (Order entity), `order_lines`, `order_carrier_details`, `order_label_tracking` (OrderTracking entity), `order_customs`, `order_customs_item`, `order_raw_codes`, `order_custom_field_values`, `shipment`, `shipment_batch`, `shipment_group`, `shipment_document`, `label_package`, `saved_recipients`, `invoice_copies`
**DTC** — `dtc_orders` (V75 + V82/V83/V84/V101 expansions)
**Rate-shop / routing** — `rate_shop_history`, `routing_rule`
**Import pipeline** — `import_batch`, `import_batch_row`, `import_staging_upload`, `import_staging_row`, `import_generation_job`
**Bulk labels** — `bulk_label_jobs`
**USPS-direct** — `usps_direct_subscription`, `usps_label_queue`
**Printers** — `printer`, `printer_assignment`, `printer_tags`, `printer_scan_agent`, `printer_discovered`, `printer_test_history`, `document_print_event`
**External systems** — `external_system_connection`, `external_system_secret`, `external_system_client_login_override`, `external_webhook_subscription`, `external_webhook_delivery`
**Cutoff / holiday** — `cutoff_rule`, `holiday`
**Reference** — `country_currency`, `ship_method_rule_package`, `ship_method_rule_warehouse`
**Reporting** — `scheduled_report`, `generated_report`
**Templates & fields** — `label_templates`, `package_preset`, `custom_field_definitions`
**Audit** — `audit_log`
**System** — `system_settings`, `tenant_settings`

**Roughly 78 tables** (+3–4 join-table-only migrations without a dedicated entity).

---

## 9. Table mapping matrix (ShipX → shioai)

| ShipX table | shioai equivalent | Semantic drift | Missing columns | Extra columns |
|---|---|---|---|---|
| `SPX_tblClient` | `clients` (`model/Client.java:35`) | ShipX keys client by CODE, shioai adds an `IDENTITY` BIGINT `id` plus keeps `client_code` UNIQUE. shioai splits ship-from and return address into `@Embedded` (`Client.java:63-100`) — ShipX kept them flat | Ship-station links (see below) | STATUS_ACTIVE/INACTIVE lifecycle, `client_output_destination` join, richer address, `hasBillingMarkup` computed |
| `SPX_tblShipCarrier` (4 rows) | `carrier_config` (`carrier_config`) + `carrier_account_ref` | ShipX has one `Mode` column per carrier row; shioai splits per-env + per-account into `carrier_account_ref` with `mode` + `use_dev` (V91) | — | 10+ per-account settings (`fedex_label_specification`, `label_image_format`, `pickup_type`, `label_stock_size`, `stamps_refresh_token`, `return_scope`, `label_ imaging_format`, `currency`, `client_scope`) |
| `SPX_tblCarrierService` (72) | `shipping_service` + `shipvia_service_mapping` | ShipX one row per (carrier, service). shioai splits into carrier catalog + ERP-shipvia mapping | — | `is_express`, `branded_packaging_flag` |
| `SPX_tblCarrierPacking` (15) + `SPX_tblservicePack` (131) | `carrier_package_catalog` + `service_package` + `client_package_code_map` | Compatibility table V29 seeded, V40 authoritative, V42 UPS re-audit, V92 FedEx 10kg/25kg removed. Denser matrix than ShipX. Also has V29's compatibility seeder | — | `authoritative_matrix` versioning |
| `SPX_tblClientCarrier` (82) | `carrier_account_ref` with per-client link via V85 (`carrier_account_ref_per_client.sql`) | ShipX links (client, carrier) pair once; shioai supports per-client account overrides via `client_scope` | — | Same as row above |
| `SPX_tblimporter` (6) / `SPX_tblimporterAcc` (1) | `client_customs_profile`, `customs_profile_country` | shioai's model is per-country customs profile; ShipX's importer is a per-client-per-country IOR row. Roughly equivalent for the "who's IOR" question | Stored FDA / EORI / CAGE codes with no dedicated column | Multi-country per profile, HS-code overrides |
| `SPX_tblloginCredential` + `SPX_tbluserrole` | `users` (single table, roles via `role` column) | ShipX has separate role table (3 rows: Shipper/Admin/Super Admin); shioai stores role as a string on `users` | — | Sprint 50 tier 0.5 additions: `token_version` (V9), `email_verified`, `email_verified_at`, invite tokens |
| `SPX_tblShipXUser` (per-user printer prefs) | (folded into `printer_assignment`) | shioai unified users↔printers via the new registry | ShipX's `LabelPntId` / `InvoicePntId` are two columns on the user; shioai models as `printer_assignment(user_id, printer_id, purpose)` | Assignment types, tags |
| `SPX_tblemployee` | (none) | shioai has no employee-number concept | Employee master | — |
| `SPX_tblPrinter` (25) | `printer` (V65) + `printer_tags` (V71) + `printer_discovered` (V67) + `printer_scan_agent` (V67) + `printer_test_history` (V72) | Radically richer — Sprint 3+ printer redesign track (memory doc `project_printer_page_redesign.md`) | — | Tags, discovery, per-attempt test history, side-panel schema |
| `SPX_tblshipstation` / `usershipstation` / `clientshipstation` | (**absent**) | Ship-station concept discarded per handover ("no working Ship Station screen in either build" — `19-history-search.md:79`) | Entire ship-station taxonomy | — |
| `SPX_tblShipmentDetails` (591k) | `order_label_tracking` + `label_package` + `label_batch` (Order) | shioai splits into batch header (`label_batch`) + per-tracking (`order_label_tracking`) + per-package (`label_package`). ShipX kept one wide row. `TRACKING` (ShipX) = `tracking_number` (shioai) per memory doc `feedback_wire_field_three_spots.md` W01 | `ContainerId`, `ContainerNo` (piece-in-MPS ordinal) — shioai uses `label_package.piece_number` implicitly | Wide DTC support, `carrier_label_ref`, `label_history` (V49) |
| `SPX_tblReturnDetails` (35) | (absent as separate table) | shioai treats returns as regular `order_label_tracking` rows with `is_return=true` (implicit) | Explicit RETURN table | — |
| `SPX_tblCommercialInvoice` (27) | `order_customs` + `order_customs_item` | Split into header + items | — | FTR exemption + AES citation (V37), export-declaration ref (V38), duties-paid-by (V48), dim-unit (V23) |
| `SPX_tblrequestlog` (14 936) — B2B API requests | `carrier_webhook_events` (partial) + `audit_log` (partial) | Not a direct match; shioai has no dedicated picklist-API-request log. Requests are traced via `X-Correlation-Id` MDC + logs | Dedicated picklist API log | Structured audit_log with category / order_no / actor |
| `SPX_tblDtcBatch` (8 443) | `dtc_orders` (V75) | shioai renamed and expanded (V82-V84, V101 surrogate id, V83 composite key) | `IsManual` (M/B/D) provenance tag | Surrogate id, richer columns |
| `SPX_tblDTCShipmentlogs` (5 787) | (folded into `order_label_tracking` — DTC channel) | shioai unifies B2B + DTC tracking rows via `order_channel` column (V35, V36 check, V45 backfill) | Separate DTC log | Unified schema, better DTC provenance via `dtc_orders` foreign |
| `SPX_tblDTCCommercialInvoice` (97) | `order_customs` (channel-agnostic) | Same unification | — | — |
| `SPX_tblbulkmailerBatch` + `SPX_tblexcelupload` | `import_batch` (V6+ many additions) + `import_batch_row` + `import_staging_upload` + `import_staging_row` | shioai has a first-class import pipeline with staging + generation-job (V57) + progress + cancel-requested (V64) + label-counts (V81) + slug (V86) | — | Job queue (`import_generation_job`), staging phase, per-row carrier note (V98) |
| `SPX_tblFund` (4, unused) / `SPX_tblFundLogs` (96) | (**absent**) | shioai has no Stamps balance-tracking table | Both | — |
| `SPX_Shipment_API_Log` (2 047, jsonb) | `carrier_webhook_events` (inbound) + no explicit outbound log; outbound is only in the process logs | shioai treats outbound carrier requests as tracing spans, not durable rows | Durable outbound `carrier_api_log(request_ts, carrier, endpoint, request_json, response_json, http_status)` | — |
| `spx_tblshipxrequestlog` (setup change audit) | `audit_log` (V2 baseline + V16 client_code + V66 index) | shioai's audit is richer (category, entity_type, entity_id, actor, changes JSON blob) | — | 7 indexes vs 1 (`AuditLog.java:31-37`) |
| `SPX_tblReadError` (32) | (**absent**) | No error dump table | Error snapshot / debug dump | — |
| `SPX_tblEndOfDay` (1) | (**absent**) | No FedEx-ETD confirmation ledger | ETD confirmation | — |
| `SPX_ShipXAlertPattern` (9) | (**absent**) | No alert-rules table. Alerts are inline log lines or the ring buffer | Alert rule DSL / channel / recipient | — |
| `SPX_NotificationHistory` (208) | (**absent**) | No sent-alert history | Sent-alert ledger | — |
| 10 `SPX_ISM_*` (ISM worker) | (**absent**) | ISM worker itself doesn't exist in shioai | ISM worker + tables | — |
| `SPX_tblShipVia` | `ship_vias` (`model/ShipVia.java`) + `shipvia_service_mapping` | Direct port; extended with mapping | — | Mapping to shioai `shipping_service` |
| `SPX_tblAddressErr` (10 codes) | (**absent**) | Address error codes are inline in connector code | Address error dictionary | — |
| `SPX_tblCreateShipmentAPIErrors` (537 rows!) | (**absent**) — humanization is in `CarrierErrorMessages.humanize` code | Rule dictionary keyed by pattern → humanized text | Whole 537-row table | — |
| `spx_tblcountry` (248) | `country_currency` (V23) + `CountryRegions` static (**hardcoded in Java**) | shioai has DB currency mapping but region taxonomy is static in `util/CountryRegions.java:17-36` and `CustomsTerritories.java`. FE ships duplicate data in `multiship-react/src/utils/countries.ts` (mirrored per file docstring) | 248-row country reference (name, iso2, iso3, phone code, currency) | Currency mapping (partial) |
| `schema_migrations` | `flyway_schema_history` | Flyway ships its own | — | Full Flyway metadata |
| `SessionCache` | (absent — JWT-based auth via httpOnly cookie, no server-side session store) | Sprint 50 Q1 removed server-side sessions | — | — |
| `DTCTaskInfo` (DTC leader) | (absent — see G-JOB-7) | Not applicable since DTC timer is dead | — | — |

---

## 10. Reference-data tables (hardcoded in code that should live in DB)

| # | Reference data | Current location | Rows | Ship as | Priority |
|---|---|---|---|---|---|
| R-1 | Carrier error humanization patterns | `util/CarrierErrorMessages.java:28-73` (hardcoded switch + regex) + memory doc says 537 rows in ShipX | ~537 | `carrier_error_pattern(id, carrier, match_regex, humanized_text, i18n_key, priority)` | HIGH |
| R-2 | Country → region taxonomy | `util/CountryRegions.java:17-36` (7 regions, ~200 codes inline) — plus **duplicate** at `multiship-react/src/utils/countries.ts` | ~200 | Extend `country_currency` with `region_code` column + separate `region(code, name)` lookup | HIGH |
| R-3 | Customs territories | `util/CustomsTerritories.java` (referenced from `CountryRegions.java:12`) | ~50 | `customs_territory(code, parent_country_cd, name)` | MED |
| R-4 | Alert / notification templates (subjects + bodies) | Inline strings in `AuthServiceImpl.java:212`, `UserInviteService.java:78`, `PasswordService.java:122` | ~5 today | `notification_template(key, channel, subject, body_template, i18n_key)` — Handlebars/Mustache expansion | HIGH |
| R-5 | Alert channels / recipients | Nowhere — `LoggingMailSender` INFO-logs everything | 0 today | `notification_channel(id, type=EMAIL\|SLACK\|WEBHOOK, config_json)` + `notification_subscription(tenant, event, channel_id)` | HIGH |
| R-6 | Enum-in-code lookups: `Frequency`, `Dataset`, `DeliveryType`, `Status` (webhook), `EventType` (webhook), `ActionType`, `FieldType`, `FilterType`, `SourceType`, USPS `Status` | 11 `public enum` under `model/` (see grep count) | 30-50 values total | Keep code enums for **behaviour dispatch** but back with `enum_option(enum_name, value, label_en, active, sort_order)` for FE dropdowns — avoids re-deploy for label wording | MED |
| R-7 | Region-of-world address regex / postal patterns | Not currently in code; not in DB | 0 | Would be a DB table if we ever validate — YAGNI today | LOW |
| R-8 | Carrier ship-via defaults (`R02`, `F69`, `U11`, `U54`, `P80` in ShipX `SPX_tblShipVia`) | `config/ShippingConfigSeeder.java:38-40` (hardcoded UPS 03, FEDEX_GROUND, USPS PRIORITY) | 3 today | Extend `ship_vias` table with `carrier` + `default_service_code` columns + admin FE to manage | LOW |
| R-9 | HS-code directory | `service/hs/HsCodeDirectory.java` (glob-listed earlier; not read here — likely static data file) | Unknown | Confirm; if in code, promote to a `hs_code(code, description, section, chapter)` table | LOW |
| R-10 | Address-error carrier code map (`SPX_tblAddressErr` 10 rows) | Inline in connector code (not centralized) | ~10 | `address_error_code(carrier, code, is_address_error, humanized)` | MED |

---

## 11. Prioritized fix-plan

| # | Priority | Item | Est. LoC | Migrations | Risk | Rationale |
|---|---|---|---|---|---|---|
| P1 | **HIGH** | Wire `SmtpMailSender` + move email templates to `notification_template` table | +250 / -40 | V102 (notification_template + notification_channel + notification_subscription) | LOW — swap-in bean via `@ConditionalOnMissingBean` | H-8/H-9/H-10, R-4/R-5. Prod today mails nothing. Zero-cost to ship since interfaces exist |
| P2 | **HIGH** | Persist USPS/Stamps fallback alerts to `alert_history` + expose `NotificationChannel` SPI (email/webhook/Slack); keep the ring buffer as a hot-cache | +180 / -20 | V103 (`alert_history`) | LOW | G-JOB-2/G-JOB-3. Restart shouldn't lose ops visibility |
| P3 | **HIGH** | Extract `CarrierErrorMessages` to a `carrier_error_pattern` table seeded from ShipX's 537 rows | +90 / -100 | V104 (`carrier_error_pattern` + seed) | MED — must not regress the tested humanize matrix | H-15, R-1. Adding a new carrier error today needs a rebuild |
| P4 | **HIGH** | Introduce `JobDashboardController` (`GET /admin/jobs`, `POST /admin/jobs/{name}/run-now`, `POST /admin/jobs/{name}/pause`) + `job_config` table | +350 (controller + service + FE) | V105 (`job_config(job_name, cron, enabled, last_run_at, last_status, failure_details)`) | MED — needs a `SchedulingConfigurer` refactor so cron is DB-driven | H-13, G-JOB-4. Ops has zero visibility today; a hard-coded cron requires a redeploy to tune |
| P5 | **HIGH** | Move `CountryRegions` (200 codes) + `CustomsTerritories` to DB; delete FE duplicate | -300 code / +80 seed + service | V106 (extend `country_currency` with `region_code`; drop the static maps) | LOW — one-shot data move | R-2/R-3, memory note says FE + BE ship the same list |
| P6 | **HIGH** | Decide DTC-timer future: either delete `DtcTimedBackgroundService` (dead code) or revive with property-driven cron + advisory-lock leader election | -110 (delete) OR +150 (revive) | none if delete; V107 (`dtc_pipeline_lock` if revive) | LOW if delete; MED if revive | G-JOB-1, H-7. Committed dead code with 4 crons hides the fact that DTC pulls aren't happening |
| P7 | **MED** | Standardise retry policy: `RetryPolicy` bean per `job_name` in `job_config` | +140 / -40 | V105 same as P4 | LOW | H-11. Two jobs retry, three don't; inconsistent story |
| P8 | **MED** | Health indicators: add `OracleNdsHealthIndicator`, `UspsQueueDepthHealthIndicator`, `DtcHeartbeatHealthIndicator` | +200 | none | LOW | G-JOB-6. Actuator health today only says "postgres OK" |
| P9 | **MED** | Address `application.properties:216` scrape allowlist (network policy OR filter) | +20 (filter) OR ops-only | none | LOW | G-JOB-8 |
| P10 | **MED** | Add `carrier_api_log(id, carrier, endpoint, request_json, response_json, http_status, correlation_id, tenant_code, order_no, created_at)` durable outbound-request log | +180 / -0 | V108 | MED (blob volume — pair with 30-day retention in P11) | ShipX has `SPX_Shipment_API_Log` (2 047 rows / 4 months); shioai has no equivalent — troubleshooting "did FedEx get X?" today needs raw grep in log files |
| P11 | **MED** | Extend `RetentionProperties` with per-tenant override table | +120 | V109 (`retention_override`) | LOW | H-12 |
| P12 | **MED** | Add `alert_pattern` DSL table (mirror of `SPX_ShipXAlertPattern` — regex on log message → channel + template) | +200 | V110 | MED — need to hook a log-appender that queries this on WARN/ERROR | G-JOB-2 (structured) |
| P13 | **LOW** | Introduce `TenantEntity` + true `tenant_id` FK on `label_batch` / `order_label_tracking` — today `client_code` doubles as tenant discriminator + business code | +400 refactor + N tables migrated | V111+ (multi-migration split) | HIGH — every service currently keys on `client_code` | Long-term; sprint 51 partially covers via `tenant_settings`. Not urgent today |
| P14 | **LOW** | Delete unused Java enums from FE dropdowns; ship shared `enum_option` view instead | +100 / -50 | V112 | LOW | R-6 |
| P15 | **LOW** | Add `SPX_tblAddressErr` equivalent: `address_error_code(carrier, code, humanized, is_address_error)` | +80 | V113 | LOW | R-10 |
| P16 | **LOW** | FedEx ETD nightly-upload job — either implement or delete `syncFedexEtdDaily` stub | +200 impl OR -20 delete | none | MED (needs FedEx cert) | G-JOB-5, H-7 |

---

## 12. Open questions

1. **Is `DtcTimedBackgroundService` intended to be revived?** Memory doc `feedback_component_ctor_overload_needs_autowired.md` implies active Oracle work but the 4 crons remain commented since baseline. If it's dead, deleting -110 LoC beats leaving landmines.
2. **Does `NdsOracleConnector.evictIdleClientPools` need scheduling?** Javadoc at `.../NdsOracleConnector.java:218-220` says "Not scheduled by default — callers wire this to a Spring `@Scheduled` in S4 when the connector goes live." Was S4 shipped? If yes, this pool never evicts; if no, deferring further is safe.
3. **Is anyone reading `/actuator/prometheus` today?** If no scraper exists, the endpoint is ship-lag and can be gated (env-flag flip) rather than IP-allowlisted.
4. **What's the split between "customer wants an SMTP alert" vs "internal ops wants Slack"?** P1 and P2 assume both; if only one channel is wanted today, ship that adapter and stub the other.
5. **Is `SPX_tblCreateShipmentAPIErrors` (537 rows) worth porting wholesale?** Or should shioai keep a smaller curated subset in `CarrierErrorMessages` and only DB-back the patterns that fire in production? Suggest: seed from ShipX dump, prune anything with 0 hits after 90 days.
6. **How does shioai model `SPX_tblShipmentDetails.ContainerId + ContainerNo`?** ShipX uses "one tote may have multiple pieces". shioai splits `label_package` per piece — is there any surface still assuming "one row per shipment"? Grep on `label_batch.number` and `label_package.piece_number` suggests it does but hasn't been re-audited since MPS work.
7. **Do we want to keep Hibernate `ddl-auto=update` as authoritative alongside Flyway?** ShipX's `MigrationRunner` is refuse-to-start-on-drift; shioai is silently-auto-migrate. The V1 baseline comment (`V1__baseline.sql:12-14`) says "A future sprint will do a proper pg_dump …". Every migration since carries that risk.
8. **`SPX_tblFund` was unused in ShipX too** — do we need a Stamps-balance model at all in shioai, or is that entirely delegated to the Stamps SERA integration?

---

*Investigation: read-only. shioai codebase touched only via Grep/Read.*
*Report path: `D:/GIT-REPOS/thb-shioai/docs/audits/jobs-db-vs-shipx.md`*
