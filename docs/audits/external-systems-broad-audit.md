# External-Systems Framework — Broad Audit & FE Spec

**Repo:** thb-shioai (dev @ 2026-09-30)
**Author:** Read-only audit; no code changes
**Scope:** the S1 external-systems framework itself (SPI, registry, secrets, dispatcher, admin API) + REST_JSON stub + tenant-routing + kill-switch + observability. Cross-connector, not NDS-specific.
**Extends:** [`nds-audit-and-fe-spec.md`](./nds-audit-and-fe-spec.md) — this report re-uses that document's section IDs. Anything cited as `[nds-audit §X.Y]` is defined there and NOT re-explained here.

---

## 1. Overview

Yesterday's report covered every code path anchored on `NDS_ORACLE` — from V78 seed to `NdsShipmentOracleWriter`. What it left unexamined is the framework itself: the SPI contract at `ExternalSystemConnector.java`, the auto-discovery registry at `ExternalSystemRegistry.java`, the second-connector proof (`RestExternalConnector`), and the cross-cutting concerns that ANY connector inherits — secrets, dispatcher, tenant routing, retry (or the lack of it), kill-switch, and observability.

This extension makes the framework-side findings first-class citizens and specs the FE surfaces that don't exist because the ops surface was scoped as "NDS admin", not "external-systems admin".

### What's new here vs yesterday

| Yesterday covered | This report adds |
|-------------------|------------------|
| NDS connector, writer, prefill, DTC pull | Framework SPI + registry auto-discovery |
| `NdsOracleConfig` shape | `RestExternalConfig` shape + audit of what's actually wired |
| Secrets for NDS (`productionPassword`) | Cipher pattern across ALL connectors + plaintext-fallback semantics |
| `ExternalSystemWritebackDispatcher` retry gap (E3) | Root-cause: it's a framework gap, not NDS-specific |
| NDS FE tabs (Details, Writeback, Secrets, Overrides) | Connector-registry UI, kill-switch, dispatch-journal, multi-tenant view |
| Tenant-routing endpoints exist, no FE | Same finding + generic-across-connectors framing |

---

## 2. Doc-access status

Yesterday's audit was blocked from `D:/GIT-REPOS/ShipX/docs/`. **Today's probe result:** `ls D:/GIT-REPOS/ShipX/docs/handover/source/` returned `Permission to use Bash has been denied`. Same result — same sandbox rule. All framework findings below come from shioai code + Flyway migrations + FE source. External-system contracts NOT anchored to shioa's own code (e.g. any "how SAP wants webhooks") remain unverifiable from this sandbox.

---

## 3. Framework audit

Severity legend (same as [nds-audit §2]): **BLOCKER** / **HIGH** / **MED** / **LOW**.

### 3.1 Connector SPI (`ExternalSystemConnector.java`)

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| K1 | **Adding a new connector type IS a Java code change**, not a data-only registration. The SPI is a Java interface with generic `<C, H>` parameters + a `@Component`-scanned bean. There is no way to declare a new `system_type` at runtime without shipping a `@Component`. This is fine for the "one connector per protocol family" case but rules out an ops-driven "add a new REST endpoint" experience via FE. | `ExternalSystemConnector.java:57`, `ExternalSystemRegistry.java:44-59` | MED (design ceiling) | Accept the ceiling for typed connectors. For the "generic REST integration" case, the correct pattern is: ONE `REST_JSON` connector (already exists) + ops adds a new `external_system_connection` row pointing at it. Document this in the FE Add-Connection page (currently the dropdown just shows `system_type` — user has no signal that they're picking a *shape* not a *service*). |
| K2 | `systemType()` is case-normalised (upper) at registry-build; `system_type` column is case-sensitive text. Duplicate-connector detection is by upper-cased key. Correct. | `ExternalSystemRegistry.java:52,237` | — | none |
| K3 | Duplicate `systemType()` → registry silently keeps whichever bean was iterated last, WARN-logged. Test-only classes with the same `systemType()` at classpath scope would silently shadow prod. | `ExternalSystemRegistry.java:53-57` | LOW | change WARN → ERROR + startup failure; ambiguous connector wiring is a config bug, not a runtime warning. |
| K4 | `configType()` returns `Class<C>`; Jackson deserialises via `objectMapper.readValue(row.getConfigJson(), connector.configType())`. There is no per-connector schema validation beyond Jackson's "shape parses" — a REST connector with `baseUrl=null` gets caught in the connector, not the framework. Consistent but shifts validation surface to every connector author. | `ExternalSystemRegistry.java:267-279` + `RestExternalConnector.java:207-221` | LOW | add optional `validateConfig(C cfg)` default method on the SPI, called by the registry after parse. Reduces per-connector boilerplate. |
| K5 | `onConfigChanged` default is a **no-op** — a connector that caches pools (like `NdsOracleConnector`) MUST override, but the SPI doesn't force this. A future connector that caches expensive handles and forgets to override will silently serve stale config after every admin PUT. | `ExternalSystemConnector.java:105`, `ExternalSystemRegistry.java:222-233` | MED | make `onConfigChanged` abstract on any connector that caches; or expose a `Set<String> cachedConnections()` method the registry checks post-update to force-invalidate. |
| K6 | `writeShipment` + `clearShipment` defaults return `SKIPPED` — safe fallback for connectors that don't do writeback (e.g. an SFTP integration only for inbound). | `ExternalSystemConnector.java:124,137` | — | none |

### 3.2 Registry / auto-discovery

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| L1 | Startup audit at `init()` iterates `config.listActive()` and WARN-logs rows whose `system_type` has no matching connector. Good hygiene. But this WARN doesn't propagate to `/actuator/health` — a mis-configured row shows up in logs only. Ops watching health won't see it. | `ExternalSystemRegistry.java:61-73` | MED | thread orphaned-row count into `ExternalSystemHealthIndicator`; DOWN with details when any active row has no connector. |
| L2 | `connect()` at `:107` catches every non-`ExternalSystemException`, wraps in `Kind.OTHER`. Loses type — e.g. a JDBC `SQLRecoverableException` becomes a plain string. Callers can't dispatch by cause. | `ExternalSystemRegistry.java:106-118` | LOW | pass the original throwable's class-name into the `ExternalSystemException` details; keep the wrap. |
| L3 | `writeShipment` / `clearShipment` (registry) also catch-all any exception and downgrade to `WritebackAck.failed`. Symmetric to yesterday's [nds-audit §2.5 E3] — the framework itself has no retry, no journal, no back-pressure. Failed acks are logged in `logAck` at WARN and lost. | `ExternalSystemRegistry.java:186-193, 214-221` + `ExternalSystemWritebackDispatcher.java:245-255` | HIGH | see §3.9 below — **journal + retry belongs at framework layer, not per-connector**. NDS finding E3 was correct; this is where the fix should land. |
| L4 | `healthCheckAll()` iterates every active connection sequentially. Ten NDS + one REST connection with a 30s TCP timeout blocks the actuator for 5.5 min in the worst case. | `ExternalSystemRegistry.java:157-163` | MED | parallelise via a bounded stream / the shared `taskExecutor`. Cap per-connection health-check timeout at 5s. |

### 3.3 Secrets

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| M1 | **All external-system secrets ARE encrypted at rest using the same AES-256-GCM cipher.** `ExternalSystemConfigService.putSecret` → `crypto.encrypt` (line 155); `putClientOverride` → same (line 219-221). Format matches `WebhookSecretCipher` (both use `CryptoService`). Confirmed uniform. | `ExternalSystemConfigService.java:155, 220`; `CryptoService.java:73-92` | — | none |
| M2 | **Plaintext-fallback when `SECRETS_ENCRYPTION_KEY` is unset.** `crypto.isAvailable()==false` → the service stores raw plaintext + logs `encrypted=false`. Comment says "NOT recommended for production" — but there's no runtime enforcement. A prod install that never sets the env var silently stores API keys + Oracle passwords + client-override passwords as plaintext. | `ExternalSystemConfigService.java:130-135, 154-155, 197-199, 219-221` | **HIGH** | add a `SPRING_PROFILES_ACTIVE=prod` guard: if profile==prod and `!crypto.isAvailable()`, refuse to boot. Also: fail-fast at `putSecret` in prod (throw `IllegalStateException`) — silent plaintext writes hidden behind an `INFO` log line are a compliance-audit landmine. |
| M3 | Secret keys are per-connector convention (`productionPassword`, `apiKey`, `oauthClientSecret`), enforced only by connectors reading them. FE Secrets tab hard-codes `productionPassword` (line 917 of `ExternalSystemsPage.tsx`) — admin editing a REST connector sees "productionPassword" as the default key name. Confusing. | `ExternalSystemsPage.tsx:913-973` + `RestExternalConnector.java:60-61` | MED | expose `secretKeys()` on the SPI (`Set<String>`); FE reads via `/connectors` list and per-type renders known-key inputs (see §6.1 spec). |
| M4 | `deleteSecret` never checks if the connector's `onConfigChanged` should fire. Direct delete → next `connect()` fails with `SECRET_UNAVAILABLE`, no invalidation of cached handles. FE controller does call `registry.reload()` in `putSecret` (line 131 of controller), so the practical impact is small — but the service layer alone doesn't trigger reload. | `ExternalSystemConfigService.java:172-180` | LOW | make `deleteSecret` return the connectionId → let caller invalidate; or fire the reload inside the service (probably cleaner). |
| M5 | Encryption happens per-write; there's no key-rotation path. Rotating `SECRETS_ENCRYPTION_KEY` decrypts none of the existing rows. | `CryptoService.java` (no rotation code) | MED | out of scope for this audit — flagged for a dedicated key-rotation sprint. Tag: `ponytail: single-key MVP; add rotate-and-rewrap when compliance requires`. |

### 3.4 Retry policy

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| N1 | **No retry at any layer.** Confirmed by grep: no `Retryable`, no `RetryTemplate`, no backoff logic in `service/externalsystems/`. Dispatcher swallows exceptions at `ExternalSystemWritebackDispatcher.java:108-113,165-168`. Registry catches at `:186-193, 214-221`. Every one is a permanent drop. | `ExternalSystemWritebackDispatcher.java:108`, `ExternalSystemRegistry.java:186` | **HIGH** | same fix as [nds-audit §2.5 E3] — journal + sweeper. Restate priority: this is FRAMEWORK-scoped, not per-connector. Any future connector (SAP, SFTP) inherits the same silent-drop problem. |
| N2 | Async executor is the shared `taskExecutor` (AsyncConfig.java) — bounded 4/32/200 with CallerRuns rejection. Under bulk-label saturation, writebacks execute inline on the label thread — LATENCY problem, not correctness. Ops can't tell writeback-caused label slowness from carrier slowness. | `AsyncConfig.java:42-45`, dispatcher `@Async` at `ExternalSystemWritebackDispatcher.java:72, 120` | LOW | add executor metrics + queue-depth log line; or split writeback onto its own executor so saturation is observable in Actuator. |

### 3.5 Rate limiting

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| O1 | **No per-external-system rate limiter.** shioai has `CarrierOutboundRateLimiter` for carrier APIs but nothing similar for external systems. A bulk of 5000 labels fires 5000 writebacks; NDS Oracle has no OpenAPI-esque throttle, but SAP or REST vendors typically do. | (absence — grep across `service/externalsystems/` returns zero rate-limit code) | MED | add a Guava/Bucket4j token bucket per connection in `RestExternalConfig.rateLimitPerMinute` (default null = unlimited). Enforced by a wrapper around `writeShipment`. |
| O2 | JDBC pool sizing (`poolMaxSize=5` on nds-default seed) is the only backpressure — the REST connector has none. | `RestExternalConfig.java` — no pool field | LOW | rely on the Spring `RestClient`'s implicit connection pool; document ceiling. Add finding to §5. |

### 3.6 Kill switch

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| P1 | **`active` boolean on `external_system_connection` IS a kill switch.** Setting `active=false` via the FE (checkbox on Details tab) causes registry to return `SKIPPED` on all writebacks + `UNKNOWN` on health. But: **no separate "pause" that keeps the row logically-active but stops dispatch** — flipping active also hides the row from users navigating around. | `ExternalSystemRegistry.java:178, 206`, `ExternalSystemConnection.java:46` | LOW | keep `active` as-is; add optional `pause_writeback_until TIMESTAMP` for temporary throttling without hiding the row. Or reuse `writebackSourceManual/Bulk/Api/Wms/Dtc = false` for the same effect — no code change needed. Documentation win. |
| P2 | No admin audit trail of who toggled active / paused. `updated_by` is set, but only latest value — no history. | `ExternalSystemConnection.java:60` | LOW | write a row to `audit_log` on every save; already the pattern for other admin-managed tables. |

### 3.7 Observability

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| Q1 | **No dispatch journal.** Confirmed via grep — no `writeback_journal`, no `external_system_dispatch_log` tables. Every writeback attempt is a log line only (dispatcher `logAck` at `:245-255`). Ops can't answer "why did this order not appear in NDS?" without SSH + grep. | `ExternalSystemWritebackDispatcher.java:245-255` (only sink) | **HIGH** | see §6.4 spec — new table + FE view. This is the biggest observability gap and it was flagged in [nds-audit §2.5 E3] as an NDS finding; correct scope is FRAMEWORK. |
| Q2 | Health indicator aggregates but doesn't persist snapshots — no history sparkline (nds-audit §3.1 gap generalises). | `ExternalSystemHealthIndicator.java` | LOW | optional `external_system_health_snapshot` table + LRU. Same P3 priority as [nds-audit §4]. |
| Q3 | `test-connection` + `writeback-probe` are dial-once-and-forget. No probe history. Ops running a probe today can't compare to last week's probe. | `ExternalSystemsAdminController.java:240-319` | LOW | if the journal (Q1) is built, probes go through the same dispatcher and land in the same table with `source='PROBE'`. Free feature. |

### 3.8 Multi-connector / multi-tenant

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| R1 | **Yes, shioai can run two `NDS_ORACLE` connections simultaneously** — the framework is name-keyed, not type-keyed. Two rows `nds-us-east` + `nds-eu-west` both of `system_type=NDS_ORACLE` both dial through `NdsOracleConnector`, which caches DataSources per-`connectionName`. Confirmed at `NdsOracleConnector` (per yesterday A4 finding). | `ExternalSystemRegistry.java:107`, `NdsOracleConnector` caching (per [nds-audit §2.1 A4]) | — | none — framework does what it says |
| R2 | **BUT** — `ExternalSystemWritebackDispatcher.DEFAULT_CONNECTION_NAME = "nds-default"` (line 58) is a compile-time constant. A tenant without a per-tenant `writebackConnection` setting always falls back to `nds-default`. In a two-NDS-server world, half your tenants writing to the wrong server. | `ExternalSystemWritebackDispatcher.java:58, 188` | MED | make the default configurable via a `platform_settings` KV or a boolean `is_default_writeback_target` column on `external_system_connection` (only one row has it). Yesterday's [A4] mentioned the same for the connection name; framework-scoped fix. |
| R3 | Tenant→connection routing endpoints exist (`GET/PUT/DELETE /{id}/tenant-routings/{tenantCode}`, controller lines 178-222) — **no FE**. NDS connection editor renders `RoutedTenantsSection` (line 804) for the CURRENT connection only; there's no "for tenant X, which connection?" reverse view. See spec §6.2. | `ExternalSystemsPage.tsx:804-911` | MED (UX) | add a per-tenant view under `/settings/tenants/{code}/external-systems` OR extend the current section with search. |
| R4 | Tenant setting `writebackConnection` is one-value. A tenant that wants "write labels to NDS AND to SAP" needs a code change (fan-out at dispatcher). Real requirement or speculative? Deferred to open questions. | `ExternalSystemWritebackDispatcher.java:180` | LOW | defer until requested |

---

## 4. REST_JSON stub audit

The stub proves the framework generalises beyond JDBC. Its shape reveals which things are DB-driven vs still hardcoded.

| # | Finding | Location | Severity | Fix |
|---|---------|----------|----------|-----|
| S1 | **DB-driven:** `baseUrl`, `oauthTokenUrl`, `apiKeyHeader` (default `X-API-Key`), `healthCheckPath` (default `/health`), `writebackPath` (default `/shipments`), `writebackClearPath` (default `/shipments/{tracking}`). All admin-editable via config JSON. Good. | `RestExternalConfig.java:26-79` | — | none |
| S2 | **DEAD config fields:** `readTimeoutSeconds` (line 55, default 10) and `healthCheckTimeoutSeconds` (line 61, default 3) are declared on the config but the connector uses `HttpClients.newBuilder()` which reads its own static defaults (5s connect / 30s read at `HttpClients.java:31-32`). Admin editing these values on `/settings/external-systems` sees no effect. | `RestExternalConfig.java:55,61` vs `RestExternalConnector.java:225` | **HIGH** | either wire the config into a per-call timeout override, OR delete the two dead fields. Silent-no-effect config knobs are worse than missing ones. |
| S3 | **Hardcoded fallbacks in code**, not config: `writebackPath` default `/shipments` at connector line 120; `writebackClearPath` default `/shipments/{tracking}` at connector line 144. These duplicate the config-level defaults — if a config leaves the field blank AND the field's Jackson default doesn't fire (e.g. Jackson pushed `null`), the connector's hardcoded fallback kicks in. Sane belt-and-braces but no code comment explaining the duplication. | `RestExternalConnector.java:119-120, 143-144` | LOW | add a `ponytail:` comment explaining the two-level default; consider a single source of truth via `Optional.ofNullable(...).orElse(RestExternalConfig.DEFAULT_WRITEBACK_PATH)`. |
| S4 | **OAuth mode is a stub — no token acquisition.** `buildClient` logs a WARN and builds an unauthenticated client when `oauthTokenUrl` is set. A user configuring OAuth via the FE gets a `RestClient` that will 401 on every call. `healthCheck` will surface DOWN quickly, but nothing at admin-time flags "OAuth not implemented". | `RestExternalConnector.java:226-240` | HIGH | either (a) implement client-credentials via a `WebClient` filter, or (b) surface a config-validation error at parse time when `oauthTokenUrl != null && System.getProperty('rest.oauth.enabled') != 'true'`. **(b) is 5 LoC and stops accidental prod misuse.** |
| S5 | **`writeShipment` body shape (`toBody`) hardcoded to fixed field set** — same finding as [nds-audit §2.7 G1] but for REST. Adding a payload field means editing `toBody` at line 176. | `RestExternalConnector.java:176-192` | LOW | acceptable ceiling for a stub; document in FE that REST_JSON is a fixed-shape adapter. |
| S6 | **No per-request auth header when OAuth mode active** — see S4. Health-check hides the misconfig because the endpoint might be public. FE should refuse to save an OAuth config with the stub in place. | `RestExternalConnector.java:238-240` | MED | UI-level guard: `/connectors` response could add `supportsOauth: false` for REST_JSON; FE greys out the oauth fields. |
| S7 | **DELETE vs POST clear-path convention** is inferred from `{tracking}` placeholder presence (line 146). A vendor whose DELETE endpoint uses `{orderNo}` instead of `{tracking}` silently POSTs instead. | `RestExternalConnector.java:143-165` | LOW | expand template vocabulary: `{tracking}` OR `{orderNo}` OR `{clientCode}`; or add an explicit `clearMethod` config field (`DELETE`/`POST`, default DELETE if placeholder present). |
| S8 | **No retry / no rate-limit** — inherits from framework (N1, O1). | (delegated) | HIGH | fixed by framework-level solution |

---

## 5. Cross-cutting hardcoded findings

Consolidated list of "should be DB-driven, isn't" across framework + REST stub.

| # | Finding | File:line | Fix |
|---|---------|-----------|-----|
| X1 | `nds-default` connection name compile-time constant used by dispatcher fallback | `ExternalSystemWritebackDispatcher.java:58` | boolean column `is_default_writeback_target` (see R2) |
| X2 | Global HTTP timeouts 5s/30s for ALL REST connections (`RestExternalConfig.readTimeoutSeconds` is dead) | `HttpClients.java:31-32` + `RestExternalConfig.java:55,61` | wire config → per-connection factory (see S2) |
| X3 | `X-API-Key` default header name for REST | `RestExternalConnector.java:243` | already DB-overridable via `apiKeyHeader` — no fix, comment only |
| X4 | `/health` default health path for REST | `RestExternalConfig.java:49` | DB-overridable — no fix |
| X5 | `productionPassword` hardcoded secret-key placeholder in FE | `ExternalSystemsPage.tsx:917` | expose known-keys per connector via `/connectors` (see M3 / §6.1) |
| X6 | Executor pool 4/32/200 shared across ALL @Async (including writeback) | `AsyncConfig.java:44` | not framework-specific; N2 asks for a metric only, not a split |
| X7 | Health-check-all is sequential | `ExternalSystemRegistry.java:157-163` | L4 |
| X8 | `SECRETS_ENCRYPTION_KEY` unset → silent plaintext fallback | `ExternalSystemConfigService.java:130-135, 154-155` | M2 |
| X9 | `writebackConnection` tenant-setting key hardcoded, not documented in FE | `ExternalSystemWritebackDispatcher.java:55` | expose in FE tenant-routing UI (R3 / §6.2) |
| X10 | OAuth mode stub returns unauthenticated client | `RestExternalConnector.java:238-240` | S4 |

---

## 6. FE spec extensions

Extends [nds-audit §3] with generic-across-connector pages. Route base `/settings/external-systems` unless noted.

### 6.1 Connector-registry / typed config forms — NEW section

**Purpose:** replace the raw JSON textarea with a per-`system_type` structured form (extension of [nds-audit §3.1 last bullet]).
**Where:** existing edit drawer, Details tab.
**Behaviour:**
- FE fetches `/connectors` (endpoint exists, returns `systemType + configType`) at page load.
- For each `configType`, render a per-type form component that maps to the fields on that config class. Two components ship today: `NdsOracleConfigForm` + `RestExternalConfigForm`.
- Below the form: collapsible "Raw JSON" editor (current behaviour, still authoritative).
- On save, structured form serialises to JSON; if raw JSON is edited, structured form re-parses.

**Backend needs:**
- Extend `/connectors` response to include a JSON-Schema-like descriptor of the config shape:
  ```java
  Map.of("systemType", c.systemType(),
         "configType", c.configType().getSimpleName(),
         "fields", extractFieldMeta(c.configType()),
         "secretKeys", c.secretKeys())  // new SPI method
  ```
- OR ship the descriptor as a static JSON blob per connector class (`ndsOracleConfig.schema.json` in resources).

**Backing tables:** none. **Migration risk:** LOW. Additive.

### 6.2 Tenant-routing management (across all connections) — NEW page

**Route:** `/settings/tenant-routings` (or a per-tenant view under `/settings/tenants/{code}/external-systems`)
**Purpose:** R3 — reverse view of "for each tenant, which connection?".
**Fields / controls:**
- Table: `tenantCode | routedConnection | fallbackConnection (nds-default) | updatedAt | actions`.
- Filter: connection dropdown; show unrouted tenants toggle.
- Row action: change routing (dropdown of active connections) / clear.
- Bulk action: assign N tenants to a connection.

**Backend needs:** already covered. `GET /admin/external-systems/{id}/tenant-routings` per-connection. Add ONE new endpoint:
- `GET /api/v1/admin/tenant-routings?tenantCode=` — returns `{tenantCode, connectionName}` for all tenants OR filter to one.

**Backing tables:** existing `tenant_settings` (key `writebackConnection`).
**Migration risk:** LOW.

### 6.3 Writeback probe UI (across all system types) — NEW sub-panel

Same as [nds-audit §3.8] but framed as framework-generic rather than NDS-specific — the endpoint at `ExternalSystemsAdminController.java:240-280` is not NDS-specific and probes any connection. FE spec unchanged; **add mention** that for REST connections, probe fires a synthetic POST (not an Oracle write).

### 6.4 Dispatch journal UI — NEW page (framework-scoped)

**Route:** `/settings/external-systems/journal`
**Purpose:** Q1 — inspectable, retryable trail of every writeback attempt. Extends [nds-audit §3.9] which framed this as NDS-only. Correct scope: framework.

**Fields / controls:** as in [nds-audit §3.9], with columns re-labelled for cross-connector clarity:
- Filter: date range, connection (dropdown of all), status, source, channel, clientCode, orderNo, `system_type`.
- Table: `id | ts | connection | system_type | orderNo | clientCode | mode | attempts | status | last_error`.
- Detail: full payload JSON + full error + per-attempt log.
- Actions: retry row, retry all failed, mark dead-lettered.

**Backing table:** as in [nds-audit §3.9 CREATE TABLE] — **but rename `nds_*` prefix if used**. The `external_system_writeback_journal` name already used there is correctly framework-scoped. Add index on `system_type` for the filter.
```sql
-- Additional index over the base V-migration
CREATE INDEX idx_journal_system_type ON external_system_writeback_journal (ts DESC, system_type);
```
**Backend endpoints:** as [nds-audit §3.9]; add filter param `systemType`.
**Migration risk:** HIGH — journalling every writeback is a per-label row. See prior sizing note.

### 6.5 Kill switch / pause UI — GAP: mostly covered by `active` boolean

**Route:** existing edit drawer, Details tab.
**Current coverage:** `active` checkbox toggles kill switch (`ExternalSystemConnection.active`).
**Gap:** no "pause dispatch but keep row visible" — see P1. Cheap add:
- Add `pause_writeback_until TIMESTAMP` column (new migration).
- FE Writeback tab: "Pause writeback until [datetime picker]" + "Resume now" button.
- Dispatcher check at `dispatchOnGenerate` / `dispatchOnClear`: skip if `pause_writeback_until > now`.
**Backing table:** add column to `external_system_connection`.
**Migration risk:** LOW.

### 6.6 Multi-tenant multi-connector view — GAP: no page

**Route:** `/settings/external-systems` — new "Matrix" tab at top level (peer of table view).
**Purpose:** visualise which tenants route to which connections. See R1+R3.
**Layout:** matrix — rows = tenants (paginated), cols = active connections, cell = check when tenant is routed to that connection (via `writebackConnection` setting).
**Backing:** GET across all tenant-routings — one new bulk endpoint:
- `GET /api/v1/admin/tenant-routings/matrix` — returns `List<{tenantCode, connectionName}>`.

**Backing tables:** none new. **Migration risk:** LOW.

### 6.7 Connector-specific secret-key hints — GAP

**Route:** edit drawer, Secrets tab.
**Purpose:** M3 — replace hardcoded `productionPassword` default with a per-`system_type` list.
**Behaviour:**
- For `NDS_ORACLE`: pre-fill `productionPassword`, disable free-form.
- For `REST_JSON`: dropdown of `apiKey` / `oauthClientSecret`, based on `oauthTokenUrl` presence in config.
- For unknown types: free-form (current behaviour).
**Backend needs:** `secretKeys()` on SPI (M3) + expose via `/connectors`.
**Backing:** none. **Migration risk:** LOW.

---

## 7. Prioritised fix-plan (net-new only)

Existing plan in [nds-audit §4] holds. Below adds ONLY the framework-scoped items that were framed as NDS-only OR that yesterday didn't cover.

Priority: P0 (must-have for safe multi-connector ops), P1 (near-term), P2 (nice), P3 (deferrable).
LoC = Java + TS + SQL combined, order-of-magnitude.

| Prio | Item | Type | Est LoC | Migrations | Risk | Rationale |
|------|------|------|---------|------------|------|-----------|
| **P0** | M2 fail-fast when `SECRETS_ENCRYPTION_KEY` unset in prod | FIX | 15 | none | LOW | plaintext-at-rest for API keys in prod is a compliance landmine; today only a WARN log |
| **P0** | Q1 + 6.4 dispatch journal as **framework** feature (not NDS-scoped) | NEW | 1200 | V-new: `external_system_writeback_journal` | HIGH | supersedes [nds-audit §4 E3+3.9] — correct scope is FRAMEWORK; NDS gets it for free |
| **P0** | S2 REST timeouts wired OR fields deleted | FIX | 30 | none | LOW | admin edits `readTimeoutSeconds` today = no effect; silent no-op is worst kind of dead code |
| **P0** | S4 OAuth-mode config-validation refusal | FIX | 10 | none | LOW | prevents accidental REST-OAuth misconfig in prod |
| **P1** | L1 orphaned-row count in `/actuator/health` | FIX | 40 | none | LOW | connector-less rows only WARN-log today |
| **P1** | L4 parallelise `healthCheckAll` + per-connection timeout cap | FIX | 60 | none | LOW | 10 connections × 30s TCP = 5-min actuator hang |
| **P1** | R2 + X1 default-writeback-target as data (column or KV), not compile-time constant | FIX | 100 | V-new: bool column OR `platform_settings` KV | MED | precondition for two-NDS-server multi-region ops |
| **P1** | 6.2 tenant-routings-matrix FE page + one bulk endpoint | NEW | 300 | none | LOW | endpoints per-connection exist; adding matrix is FE + one aggregator |
| **P1** | 6.1 connector-registry-driven structured forms (NDS + REST first) | NEW | 500 | none | LOW | replaces raw-JSON UX; drops maintenance for the JSON-schema drift |
| **P1** | K5 force `onConfigChanged` for caching connectors | FIX | 30 | none | LOW | future connector-writer footgun |
| **P2** | 6.5 pause-writeback-until column + FE | NEW | 100 | V-new: column | LOW | operational pause without hiding rows |
| **P2** | 6.7 per-type secret-key hints on FE | NEW | 60 | none | LOW | UX polish; ties to M3 |
| **P2** | O1 + framework-level rate limiter per connection | NEW | 200 | V-new: `rate_limit_per_minute` column | MED | speculative until a REST vendor complains; premature by ponytail rules — defer |
| **P2** | 6.6 matrix tab (visual grid) | NEW | 250 | none | LOW | nice, requires 6.2 |
| **P2** | S7 REST clear-method explicit config field | FIX | 50 | none | LOW | 1-line vendor override |
| **P3** | M5 secret key-rotation | NEW | 400 | V-new: `secret_key_version` column | HIGH | out of scope; compliance-driven |
| **P3** | R4 fan-out routing (one tenant → multiple connections) | NEW | 300 | table redesign | HIGH | speculative; defer |
| **P3** | N2 dedicated executor pool for writeback | FIX | 40 | none | LOW | measure first; today's shared pool works |
| **P3** | P2 audit_log entries for admin edits | FIX | 80 | none | LOW | existing pattern; add if compliance asks |

**Total P0 estimate:** ~1250 LoC, 1 new table. Roughly 1 sprint-week.
**Total P0 + P1:** ~2400 LoC. Roughly 2 sprint-weeks (parallelisable — journal + FE work independent of parallel-health + registry-form work).

---

## 8. Open questions

1. **`SECRETS_ENCRYPTION_KEY` in prod — is it actually set on every install?** M2 is critical only if there's any install running today without it. Confirm via ops.
2. **Multi-connector demand.** Yesterday's R1 confirms the framework CAN run two NDS servers. Is there an active tenant/region asking for this? Answer determines whether R2 (X1) is P1 or P3.
3. **REST_JSON in production yet?** [PR #734] shipped the stub; no evidence anywhere it's used. If no live consumer, S2/S4/S7 findings are pre-hardening — good to fix, no urgency.
4. **Retry policy per-tenant vs per-connection.** Nds-audit §3.6 asked this at connector level; framework-level too — should the journal's `next_retry_at` be computed from a per-connection policy, per-tenant, or global? Current absence of any policy is unanimous.
5. **Structured-form or raw JSON as canonical?** §6.1 suggests both; ops preference (developer-audience vs sales-eng-audience) settles the UX.
6. **Journal retention.** Yesterday asked 90 days; framework scope may want longer for compliance (regulatory ref: audit-trail retention varies by industry). Get compliance-officer answer before shipping.
7. **`healthCheck` parallelism concurrency cap.** L4 asks to parallelise; how many concurrent JDBC handshakes are acceptable? Default 8?
8. **Kill switch vs pause — is `active=false` semantics enough?** P1 argues for a `pause_writeback_until`; may be over-engineering if `active` toggle covers the ops case. Decide before spec ships.
9. **Cross-connector authoring standards.** K1 says adding a new typed connector needs Java. Is there a contribution-doc for how to write a `ExternalSystemConnector` implementation, with the pool-caching + `onConfigChanged` obligations? Missing means K5 will keep re-happening.
10. **REST OAuth implementation.** S4 stubs it. When a real consumer needs it, do we ship a Spring Security OAuth2 client integration or bespoke code? Preference?

---

*End of report.*
