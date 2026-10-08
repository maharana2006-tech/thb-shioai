# Stamps.com SERA v1 parity audit (2026-10-08)

Deep-dive of the `StampsConnector` SERA REST paths and `StampsSeraOAuthService` /
`StampsSeraOAuthController` authorization flow against the full
`developer.stamps.com/rest-api/reference/serav1.html` specification. SWSIM
code paths are deliberately out of scope — the live flavour is SERA
(`carrier.stamps.api-flavor=SERA`, `application.properties:507`) and this
audit grades SERA parity only.

Scope confirmed with the operator: full endpoint parity, including rates,
tracking, pickups, address validation, international labels, end-of-day
closure, and the full OAuth 2.0 authorization contract.

## TL;DR

- **Authorization is solid.** 3-legged OAuth matches the spec exactly;
  state-signing + refresh-token rotation handling + single-flighted cache
  are each a notch stronger than the spec requires.
- **5 of 20 SERA endpoints are wired** (labels, void, reprint, manifests,
  balance, add-funds, plus OAuth). Missing: rates, tracking, pickups,
  address validation. These are the ones you flagged.
- **The highest-signal gap** is error-code parsing — SERA returns a
  numeric `error_code` (`800000`–`899999`) that we never read. Retriable
  errors look identical to permanent errors; a 429 becomes an exception
  the caller surfaces as a timeout. **Severity: MAJOR.**
- **Idempotency on POST /labels is non-deterministic** — a crash between
  label purchase and `generated_order_no` persist retries with a fresh
  UUID and double-charges the account. V44 (`carrier_label_ref`)
  closes most of the window, but not all of it. **Severity: MAJOR.**
- **International labels miss `sender_info`** (ITN / EEI / license number).
  Fine for < USD 2,500 non-controlled; breaks regulation for US exports
  above that threshold. **Severity: MAJOR for US outbound.**
- No OIDC / `id_token` usage, no PKCE, no revocation endpoint, no
  rates-driven `is_customs_required` pre-check — each one is a MINOR.

Proposed fix track: 8 stacked PRs, ~1900 LoC, mirrors the shape of the
2026-09-17 S-track and the 2026-09-16 G-track.

---

## 1. Authorization — OAuth 2.0 contract

### 1.1 Spec contract

| Field | Required by spec | Our value |
|---|---|---|
| `response_type` | `code` (fixed) | ✓ `code` |
| `client_id` | application-specific | ✓ from `carrier_account_ref.client_id` |
| `redirect_uri` | pre-registered | ✓ `carrier.stamps.sera-redirect-uri` |
| `scope` | `offline_access` required for refresh_token | ✓ `offline_access` (overridable) |
| `state` | not in spec | ✓ HMAC-SHA256-signed `accountId:ts:nonce` (10-min TTL) |
| PKCE | not in spec | ✗ not implemented |

Token endpoint — spec says `POST /oauth/token` with `application/json` body
(unusual; most OAuth 2 servers want form-urlencoded). Our implementation
sends JSON (`StampsSeraOAuthService.java:321-331`) — matches.

Client authentication — spec uses `client_secret_post`
(credentials in body, not HTTP Basic). We match.

### 1.2 What's implemented

| Component | File | Status |
|---|---|---|
| Authorize-URL builder | `StampsSeraOAuthService.buildAuthorizeUrl` (`:130`) | ✓ |
| Signed-state generation | `StampsSeraOAuthService.signState` (`:382`) | ✓ HMAC-SHA256 |
| State verification | `StampsSeraOAuthService.verifyState` (`:156`) | ✓ TTL + constant-time compare |
| Code → token exchange | `StampsSeraOAuthService.exchangeCode` (`:191`) | ✓ |
| Refresh-token exchange | `StampsSeraOAuthService.refreshToken` (`:217`) | ✓ |
| Access-token cache | `tokenCache` (`StampsSeraOAuthService.java:100`) | ✓ Single-flighted via `compute()` |
| Browser callback | `StampsSeraOAuthController.callback` (`:122`) | ✓ |
| Refresh-token persistence | `CarrierAccountRef.stampsRefreshToken` (V47) | ✓ Encrypted-at-rest |
| ThreadLocal push-and-clear | `StampsSeraAuthContext` (`:40`) | ✓ try-with-resources |
| Status endpoint | `StampsSeraOAuthController.status` (`:176`) | ✓ |
| Error / `error_description` callback | `StampsSeraOAuthController.callback` (`:128-131`) | ✓ |

### 1.3 Findings — authorization

| ID | Severity | Finding |
|---|---|---|
| **T-A1** | MINOR | **No PKCE.** The spec treats this as a confidential-client flow (`client_secret` required), so PKCE is defense-in-depth only. Would protect against auth-code interception if the redirect-URI whitelist is ever misconfigured. ~30 LoC: generate `code_verifier` + `code_challenge` on `buildAuthorizeUrl`, pass `code_verifier` on `exchangeCode`. |
| **T-A2** | MINOR | **No revocation / disconnect endpoint.** The spec provides no `/revoke` endpoint, so an operator who wants to invalidate a leaked refresh_token has to delete the row by hand. Add a `DELETE /api/v1/carrier-accounts/stamps-sera/authorize/{accountId}` that nulls `stamps_refresh_token` + unverifies the account. Cheap, high UX value. |
| **T-A3** | MINOR | **`id_token` is discarded.** `postToken` reads `access_token`, `refresh_token`, `expires_in` but ignores `id_token` (`StampsSeraOAuthService.java:332-334`). Spec doesn't specify OIDC claim semantics, so this is fine today — but if we ever want to attribute SERA calls to a Stamps user identity, the `sub` claim is in there. Pure MINOR. |
| **T-A4** | MINOR | **State signing key doubles as encryption key by default.** `carrier.stamps.sera-state-signing-key:${secrets.encryption-key:}` (`StampsSeraOAuthService.java:69-70`) — convenient for dev, but production operators should set a distinct key per the comment on line 68. Enforce in `application-prod.properties` or fail fast at startup. |
| **T-A5** | MINOR | **Access-token cache is per-JVM.** On a multi-instance deployment (if we ever cluster the backend), 24 workers × N instances all simultaneously hit `/oauth/token` on cold start. Single-flighting via `tokenCache.compute()` only scopes to one JVM. Moving the cache to Redis (we already have it for rate-limiter) would cross the JVM boundary. Likely not needed today. |
| **T-A6** | NICE | **Nonce width is 64-bit via `java.util.Random`** (`StampsSeraOAuthService.java:384`). HMAC signing makes brute-force irrelevant, but `SecureRandom` is a one-word change and best-practice. |
| **T-A7** | NICE | **Redirect-URI default is `http://localhost:8080`** (`application.properties:521`). Flag at startup if `env=prod` + URL is localhost. |

### 1.4 Authorization — what the spec DOESN'T say and we got right anyway

- **State signing** — spec is silent on `state`. We HMAC it, so a leaked
  redirect-URI entry can't be CSRF-replayed against another account.
- **Constant-time state comparison** — `MessageDigest.isEqual`
  (`StampsSeraOAuthService.java:419-421`).
- **State TTL** — 10 minutes, bounds the replay window of a leaked state.
- **Refresh-token rotation** — SERA MAY rotate on refresh; we
  persist the rotated value back and the hash-keyed cache naturally
  misses once per rotation (correct behaviour per
  `StampsSeraOAuthService.java:87-93`).
- **Failure not cached** — `tokenCache.compute()` returns null on failure
  so a transient 503 doesn't poison the cache
  (`StampsSeraOAuthService.java:263-269`).

**Verdict:** authorization is in better shape than any other part of this
integration. All findings here are polish; none block.

---

## 2. Endpoint parity matrix

| SERA endpoint | Wired? | Code path | Notes |
|---|---|---|---|
| `POST /authorize` + `POST /oauth/token` | ✓ | `StampsSeraOAuthService` | §1 |
| `POST /v1/rates` | **✗** | — | Still SWSIM `GetRates` |
| `POST /v1/labels` | ✓ | `StampsConnector.createShipmentSera` (`:915`) | §3.1 |
| `PUT /v1/labels/{label_id}/void` | ✓ | `voidShipmentSera` (`:1803`) | §3.2 |
| `GET /v1/labels/{label_id}` | ✓ | `reprintLabelSera` (`:2110`) | §3.3 |
| `POST /v1/addresses/validate` | **✗** | — | Still SWSIM `CleanseAddress` |
| `POST /v1/addresses/parse` | **✗** | — | Not needed per operator |
| `POST /v1/manifests` | ✓ | `closeOutDaySera` (`:2630`) | §3.4 |
| `POST /v1/pickups` | **✗** | — | Returns `NOT_SUPPORTED` today |
| `DELETE /v1/pickups/{pickup_id}` | **✗** | — | — |
| `GET /v1/tracking` | **✗** | — | Still SWSIM `TrackShipment` |
| `POST /v1/container-labels` | **✗** | — | Out of scope (per operator) |
| `GET /v1/carrier-services` | **✗** | — | Out of scope (per operator) |
| `POST /v1/carriers/{carrier}` | **✗** | — | Out of scope (per operator) |
| `DELETE /v1/carriers/{carrier}` | **✗** | — | Out of scope (per operator) |
| `GET /v1/account` | **✗** | — | Out of scope (per operator) |
| `GET /v1/balance` | ✓ | `getAccountBalanceSera` (`:1993`) | §3.5 |
| `POST /v1/balance/add-funds` | ✓ | `addFundsSera` (`:1954`), `StampsTopupService` | §3.5 |
| `GET /v1/url` | **✗** | — | Not needed (we create labels inline) |
| `GET` / `PUT /v1/security_questions` | **✗** | — | Replaced by 3-legged OAuth |

**Score: 7/20 wired.** Of the 13 unwired, 5 are in-scope (rates, tracking,
pickups × 2, address validation), 8 are not needed.

---

## 3. Per-endpoint deep dive (wired)

### 3.1 `POST /v1/labels` — createShipmentSera

**File:** `StampsConnector.java:915-978`
**Request shape (built in `buildSeraLabelBody`):**
`from_address`, `to_address`, `service_type`, `package` (weight+dims),
`delivery_confirmation_type`, `insurance`, `customs` (intl only),
`ship_date`, `is_return_label`, `label_options`, `_x_piece_context`
(diagnostic, not spec).

**Idempotency-Key:** `UUID.randomUUID().toString()` per piece (`:946`).
**Multi-package:** one API call per piece, aggregated via
`aggregateStampsShipmentResults()`. Rollback on partial failure: calls
`voidShipment` for all succeeded pieces before throwing (correct money-
safety behaviour; `StampsConnector.java:800-805`).

#### Findings

| ID | Severity | Finding |
|---|---|---|
| **T-L1** | **MAJOR** | **Non-deterministic Idempotency-Key.** A JVM crash between the SERA response arriving and `generated_order_no` being persisted means the next retry sends a fresh UUID and SERA treats it as a brand-new label. V44 (`carrier_label_ref`) now persists the SERA `label_id` after `aggregateStampsShipmentResults`, closing most of the window — but not the window between the SERA 200 and the row commit. Fix: use `IdempotencyKeys.forStampsOrder(orderNo) + ":pkg:" + pieceIndex`. Already have the helper from S3 PR #673. |
| **T-L2** | MINOR | **`_x_piece_context` field** sent to SERA is non-standard — SERA may reject it in a future API version. It exists for troubleshooting; move to an HTTP header (`X-Piece-Context`) so it survives schema tightening. |
| **T-L3** | NICE | **`is_test_label: true` is never set.** SERA's spec shows a `is_test_label` boolean that produces a "VOID"-stamped label at no cost. Useful for CI integration tests against staging; we currently rely on the staging env's play-money. |

### 3.2 `PUT /v1/labels/{label_id}/void` — voidShipmentSera

**File:** `StampsConnector.java:1803-1862`
**Request:** empty body, path param is the `label_id` UUID (resolved from
`label_package.carrier_label_ref` via `labelPackageRepository`).
**Idempotency-Key:** none sent (void is naturally idempotent per spec).
**404 handling:** treated as `ALREADY_VOIDED` success (correct).

#### Findings

| ID | Severity | Finding |
|---|---|---|
| **T-V1** | MINOR | **Pre-V44 labels can't be voided via SERA.** `label_package.carrier_label_ref` is populated only for labels created post-V44. Legacy labels fall through to a message "Labels created before SERA support shipped can only be reprinted from the persisted label_url" — but there's no equivalent fallback for void. Acceptable scar if the backlog has been flushed; worth an explicit check at startup. |
| **T-V2** | MINOR | **No idempotency key on void.** A fast double-click issues two `PUT /void` calls; the second gets 404 (already-voided) and we treat it as success — functionally fine, audit log shows two void events. Low value fix. |

### 3.3 `GET /v1/labels/{label_id}` — reprintLabelSera

**File:** `StampsConnector.java:2110-2160`
**Query params:** `label_size`, `label_format`, `label_output_type=base64`
(hardcoded).

#### Findings

| ID | Severity | Finding |
|---|---|---|
| **T-R1** | MINOR | **`label_output_type` hardcoded to `base64`.** Spec permits `url` (signed, short-lived URL) which is more efficient for large PDF reprints and avoids a round-trip through our heap. Current base64 behaviour matches the SWSIM shape so the caller doesn't branch; parametrise it so bulk-reprint can pick `url`. |

### 3.4 `POST /v1/manifests` — closeOutDaySera

**File:** `StampsConnector.java:2630-2675`
**Request:** prefers `label_ids` (when every tracking resolves a
`carrier_label_ref`); falls back to `carrier` + `ship_date` for pre-V44
labels. Plus `label_format`, `print_instructions`.
**Idempotency-Key:** `UUID.randomUUID()` per manifest.

#### Findings

| ID | Severity | Finding |
|---|---|---|
| **T-M1** | MINOR | **Non-deterministic Idempotency-Key** on manifest POST. Replaying the same closeOutDay for the same (accountId, ship_date) within 24h creates a duplicate manifest instead of dedup-hitting. Operator can live with it (duplicate manifests are a minor annoyance, not a billing event), but easy fix: key off `stamps-manifest-{accountId}-{yyyyMMdd}`. |

### 3.5 `GET /v1/balance` + `POST /v1/balance/add-funds` — balance + top-up

**Files:** `StampsConnector.java:1993-2024`, `:1954-1991`,
`StampsTopupService.java` (polls every 30 min, triggers top-up when
balance ≤ threshold).

#### Findings

| ID | Severity | Finding |
|---|---|---|
| **T-B1** | NICE | **Add-funds Idempotency-Key is `(accountId, hour-bucket)`** — good enough that two concurrent pollers in the same hour will dedup. If we ever reduce the polling interval under 1h, revisit the bucket size. |
| **T-B2** | NICE | **`max_balance_amount_allowed` is read but never surfaced to the UI.** New SERA accounts default to USD 500 cap; `/settings/carriers > Stamps account` could show that cap next to the current balance so operators understand why a top-up is being clamped. |

---

## 4. Cross-cutting findings

### 4.1 Error-code handling — **the biggest gap**

| ID | Severity | Finding |
|---|---|---|
| **T-E1** | **MAJOR** | **SERA `error_code` is never parsed.** `extractSeraError` (`StampsConnector.java:1399-1419`) reads `detail`, `message`, `error`, or `errors[0].message` — none of these is where SERA puts the code. The spec defines a numeric `error_code` + `error_message` pair with code families: `800000` validation, `800001`-`800002` idempotency, `800010` label, `800100`-`800106` pickup, `800200` manifest, `899999` generic. Impact: a retriable 429 looks identical to a permanent 800010 (invalid label_id) looks identical to a validation error. Caller treats everything as "generic HTTP failure, surface to operator." Fix: parse `error_code`, map to an enum, make retry classification code-aware. |
| **T-E2** | MAJOR | **No HTTP 429 handling.** SERA rate-limits token requests separately from ship requests. The token cache (`§1.2`) prevents the token-request flood, but shipment POST bursts aren't rate-shaped. On 429 we throw a `CarrierConnectionException` and the caller surfaces as a timeout — no `Retry-After` honoured, no backoff. For bulk-worker paths this is money-safe (worker catches, marks row FAILED, moves on) but visibility-poor. |

### 4.2 International / customs

Catalog found in `buildSeraCustoms` (`StampsConnector.java:1118-1227`):
- `contents_type` mapped from `reasonForExport` (SALE → merchandise, GIFT → gift,
  SAMPLE → sample, RETURN → returned_goods, DOCUMENTS → documents, REPAIR → other) ✓
- `customs_items[]` populated from `intl.commodities` with
  `item_description`, `quantity`, `unit_value`, `item_weight`,
  `harmonized_tariff_code`, `country_of_origin`, `sku` ✓
- `non_delivery_option` hardcoded to `return_to_sender`

| ID | Severity | Finding |
|---|---|---|
| **T-C1** | **MAJOR** | **`sender_info` is never populated.** The spec `customs.sender_info` block carries `license_number`, `certificate_number`, `invoice_number` — these are the fields that go on the EEI (Electronic Export Information) / FTR filing for US exports > USD 2,500 or any shipment with an export-controlled commodity. We already thread the ITN through `ExportDeclarationPolicy` for the intl-export-declaration track (`project_intl_export_declaration_track`), so wiring it into `buildSeraCustoms` is a two-line change. Shipping a US outbound high-value label without ITN today risks a Customs rejection and a regulatory fine. |
| **T-C2** | MINOR | **`non_delivery_option` is not operator-configurable.** Spec allows `treat_as_abandoned` (useful for low-value gifts where return shipping exceeds item value); we hardcode `return_to_sender`. Add a per-shipment picker on the intl form. |
| **T-C3** | MINOR | **`recipient_info.tax_id`** (recipient IOSS / VAT / CPF — EU, UK, Brazil post-ICS2 requirement) is in the spec but not populated. Already captured in `IntlShipmentBlockDTO.importerTaxId` per the intl-export-declaration track; thread it through. |
| **T-C4** | MINOR | **No pre-check of `is_customs_required`.** The rates response (`§5.1`) tells us whether a corridor needs customs before we POST /labels. Without a rates call, we rely on `request.getIntl().isReadyForCarrier()` which is an app-side heuristic. If an operator ships to a corridor our heuristic misses, SERA 400s at label-create time instead of warning at rate-shop time. Blocked by `§5.1` (rates not wired). |

### 4.3 Observability

| ID | Severity | Finding |
|---|---|---|
| **T-O1** | MINOR | Carrier API log interceptor (`[[carrier-api-log]]`) catches every SERA HTTP call for free — verified at `StampsConnector.java` HttpClients usage. Nothing to do here; call out that this is already working. |
| **T-O2** | NICE | **No metric on token-cache hit rate.** A warm cache means Auctane's token endpoint is barely touched; a cold restart means 24 workers thunder. Expose `stamps_sera_token_cache_hits_total` / `_misses_total` via Prometheus; piggyback on the perf-audit's dashboard. |

---

## 5. Per-endpoint gaps (not wired, in-scope)

### 5.1 `POST /v1/rates` — SERA rate shopping

**Why it matters:** today's rate-shop still uses SWSIM SOAP. Every bulk
batch pays a SOAP overhead on every quote. Also blocks T-C4 (no
`is_customs_required` pre-check).

**Request:** `from_address`, `to_address`, `service_type` (optional,
narrows the response), `package` (type + weight + dims), `ship_date`,
`is_return_label`, `advanced_options`, `insurance`, `customs`.

**Response:** array of rate quotes, each with `carrier`, `service_type`,
`packaging_type`, `estimated_delivery_days`, `is_guaranteed_service`,
`trackable`, `is_customs_required`, `shipment_cost.total_amount`,
`cost_details[]`.

**Hook point:** `StampsConnector.getRates` dispatcher. Add
`getRatesSera(request, token, env)` branch when `isSeraFlavor()`.

### 5.2 `GET /v1/tracking` — SERA tracking

**Why it matters:** SERA tracking returns `tracking_events[]` with
`occurred_at`, `event_description`, `location`, `signed_by` — richer
than SWSIM's event list. Would let us retire the per-tenant USPS web-
scraping fallback in `TrackingService`.

**Query:** `carrier=stamps_usps`, `tracking_number={number}`.
**Response:** `tracking_number`, `status_code` (enum: pre_transit,
in_transit, out_for_delivery, delivered, exception, returned),
`estimated_delivery_date`, `destination`, `tracking_events[]`.

**Hook point:** `StampsConnector.trackShipment` dispatcher.

### 5.3 `POST /v1/pickups` + `DELETE /v1/pickups/{pickup_id}` — pickups

**Why it matters:** `PickupController` currently returns `NOT_SUPPORTED`
for Stamps. Daily-pickup-eligible tenants have to call USPS directly.

**Request (POST):** `label_ids` (or `pickup_address` + `carrier`),
`pickup_window.{start_at,end_at}`, `pickup_instructions`,
`pickup_address` (if not deriving from labels).

**Error codes to handle:** 800100 (not supported by carrier), 800101
(labels span carriers), 800102 (ineligible), 800103 (Sunday USPS),
800104 (holiday), 800105 (must be next-business-day), 800106
(address mismatch). The pre-flight validator should reject these
client-side where possible (e.g. Sunday + USPS = reject before POST).

**Hook point:** `StampsConnector.schedulePickup` dispatcher.

### 5.4 `POST /v1/addresses/validate` — SERA address validation

**Why it matters:** replaces SWSIM `CleanseAddress`
(`StampsConnector.java:2272-2314`), which is on the deprecation path.
SERA validation returns `candidate_addresses[]` (correction suggestions
when the input doesn't exact-match) which SWSIM doesn't.

**Request:** array of address objects (batches up to N — spec doesn't
say the cap).

**Response:** per-address `original_address`, `matched_address`,
`candidate_addresses[]`, `is_po_box`, `is_apo_fpo`, `validation_results.
{result_code, result_description}`.

**Hook point:** `StampsConnector.validateAddress` dispatcher.

---

## 6. Fix track proposal — "T-track" (SERA parity + hardening)

8 stacked PRs, ~1900 LoC, same pattern as the S-track (2026-09-17) and
G-track (2026-09-16). Order chosen so each PR unblocks the next.

| PR | Scope | LoC | Risk | Fixes |
|---|---|---|---|---|
| **PR-T1** | **SERA error-code parser.** New `StampsSeraErrorCode` enum (800000, 800001, 800002, 800010, 800100-800106, 800200, 899999 + families). Rewrite `extractSeraError` to parse `error_code` + `error_message`. Classify codes for retry (429 + 503 → retry with `Retry-After`; 800002 → log + alert; 800010 → IllegalArgumentException; 800100-800106 → user-friendly pickup messages; 899999 → generic). | ~250 | LOW | T-E1, T-E2 |
| **PR-T2** | **Deterministic Idempotency-Keys.** `IdempotencyKeys.forStampsLabel(orderNo, pieceIndex)` + `.forStampsManifest(accountId, shipDate)`. Replace `UUID.randomUUID()` in `createShipmentSera` + `closeOutDaySera`. Replay simulation test. | ~200 | MEDIUM | T-L1, T-M1 |
| **PR-T3** | **SERA rates.** `getRatesSera` + dispatcher branch. Returns `RateQuoteResult` list with `is_customs_required` propagated. Pre-commit warning banner on intl flows when customs required + commodities missing. | ~400 | MEDIUM | §5.1, T-C4 |
| **PR-T4** | **SERA tracking.** `trackShipmentSera` + dispatcher branch. Map `status_code` + `tracking_events[]` into our `TrackingResult`. Backfill `TrackingService` to prefer SERA when flavour=SERA. | ~300 | MEDIUM | §5.2 |
| **PR-T5** | **SERA pickups.** `schedulePickupSera` + `cancelPickupSera` + dispatcher. Client-side guards for 800103/800104/800105 before POST. FE: enable the pickup form for Stamps accounts. | ~350 | MEDIUM | §5.3 |
| **PR-T6** | **SERA address validation.** `validateAddressSera` + dispatcher. Surface `candidate_addresses[]` on the FE address-form as "Did you mean?" chips. | ~250 | LOW | §5.4 |
| **PR-T7** | **Intl customs — sender_info + recipient_info.** Thread ITN / license_number / invoice_number / recipient tax_id through `buildSeraCustoms`. Reuse `ExportDeclarationPolicy` outputs from the intl-export-declaration track. Operator-configurable `non_delivery_option`. | ~200 | MEDIUM | T-C1, T-C2, T-C3 |
| **PR-T8** | **Auth polish + ops.** PKCE, `DELETE /authorize/{accountId}` disconnect endpoint, `SecureRandom` for state nonce, startup guard for localhost redirect-URI in prod, Prometheus token-cache metric, UI surface for `max_balance_amount_allowed`. | ~250 | LOW | T-A1-T-A7, T-B2, T-O2 |

**Dispatch strategy** (mirror of S-track):
1. **T1 alone** — error-code parser underpins every subsequent PR's error
   handling. Clean CI before anything else lands.
2. **T2 + T7 in parallel** — idempotency-key is label-write-side,
   sender_info is label-build-side, no overlap in `createShipmentSera`.
3. **T3 + T4 + T5 + T6 in parallel** — four independent dispatchers; land
   together once T1 is in.
4. **T8 last** — auth polish is low-risk and doesn't block anything.

Total: ~1900 LoC. 3-4 days agent-parallelised, 2-3 weeks solo. Same
cadence as the G-track and S-track.

---

## 7. Multi-package shipments (MPS)

SERA has no shipment-level envelope — `POST /v1/labels` creates ONE
label per call. Multi-piece is implemented client-side as a loop of N
POSTs, which the connector already does (`StampsConnector.java:932-976`).

### 7.1 How MPS works today

| Concern | Implementation | File:line |
|---|---|---|
| Package iteration | `request.effectivePackages()` loop | `:932-934` |
| Idempotency per piece | `UUID.randomUUID()` per POST | `:946` |
| Partial-failure rollback | `rollbackSuccessfulPiecesSera` voids each succeeded `label_id` via `PUT /labels/{id}/void` | `:990-1025` |
| Diagnostic marker | Non-spec `_x_piece_context: "{i}/{N}"` field in body | `:1149-1151` |
| Aggregation | `aggregateStampsShipmentResults` merges per-piece results | `:977` |
| Lead / master tracking | Not implemented — each piece's tracking stored independently | — |
| Per-piece weight / dims | `pkg.getWeight()` / `getLength()` / … per `PackageDetailDTO` | `:1086-1096` |

### 7.2 Findings — MPS

| ID | Severity | Finding |
|---|---|---|
| **T-MPS1** | **MAJOR** | **Insurance is applied to every piece at the full `insuredValue`.** `request.getInsuredValue()` is a single scalar on the shipment; `buildSeraCreateLabelBody` emits it on every piece's `insurance.insured_value.amount` (`:1105-1114`). A 3-piece shipment with declared value USD 1,000 pays SERA insurance on USD 3,000 (3 × full premium). Fix: split evenly across pieces (`insuredValue ÷ N`, round last piece to absorb rounding), or let the operator declare per-piece values. |
| **T-MPS2** | **MAJOR** | **Customs block duplicated on every piece.** For intl MPS, the full `customs_items[]` is attached to EVERY POST (`:1117-1118`). CBP sees each label declare the full commodity list — a 3-piece shipment of (socks × 10 + shoes × 2) becomes "3 shipments each of socks × 10 + shoes × 2" to Customs. Under-declaration is a fine; over-declaration is confused Customs. USPS CN22/CN23 forms are per-package by physical requirement, but the commodity list on each should be WHAT'S IN THAT PACKAGE, not the full shipment manifest. Fix: thread per-piece commodities (`PackageDetailDTO.commodities` or a piece-index map on `IntlShipmentBlockDTO`). |
| **T-MPS3** | MINOR | **No "lead piece" tracking number persisted.** USPS traditionally designates piece 1 as the lead for MPS (same shape FedEx/UPS use). We store each piece in `label_package` independently; the FE has to pick which one to show the customer. A `label_package.is_lead_piece` flag + a derived `lead_tracking_number` on `order_tracking` would stabilise the UX. |
| **T-MPS4** | MINOR | **`contents_description` is piece-1's first commodity for every piece** (`:1197-1201`). On a mixed MPS the second piece's label declares the first piece's goods. Fix: use the per-piece commodity description once T-MPS2 lands. |
| **T-MPS5** | MINOR | **Partial-failure visibility.** `rollbackSuccessfulPiecesSera` logs per-piece void attempts at WARN level, but the operator sees only the thrown exception for the piece that failed. The rollback itself can fail silently (e.g. SERA 503 during void) — leaving label-printed-but-no-tracking-row orphans. Fix: emit a `MPS_PARTIAL_ROLLBACK` audit event with the full piece list + per-piece rollback status, plus a dashboard alert if any rollback void itself failed. |
| **T-MPS6** | MINOR | **Non-spec `_x_piece_context` field** (`:1149-1151`) — same concern flagged in `§3.1` T-L2. SERA may schema-tighten and reject unknown fields; move to header. |
| **T-MPS7** | NICE | **`service_type` consistency not enforced.** The connector sends `request.getServiceType()` on every piece — if a caller somehow varied service per piece, we'd silently accept the first. SERA has no shipment-level service lock; validate at the DTO level. |

### 7.3 What's RIGHT about current MPS

- Per-piece POSTs with per-piece weight/dims — matches SERA's single-piece envelope.
- Compensating-transaction rollback is implemented and tested (`StampsMpsRollbackTest`).
- Per-piece `label_id` stored in `label_package.carrier_label_ref` (V44) so later void/reprint works per piece.
- `StampsRateShopMultiPackageTest` and `StampsMultiPackageTest` exist.

---

## 8. Multi line items — customs commodities

Each intl shipment carries a list of `CustomsCommodityDTO` (description,
quantity, unitValue, unitWeight, hsCode, countryOfOrigin, sku). SERA's
`customs.customs_items[]` has 1:1 field parity to these.

### 8.1 How line items are built today

See `buildSeraCustoms` (`StampsConnector.java:1190-1228`). Fields emitted
per item:

| Our field | SERA field | Notes |
|---|---|---|
| `description` | `item_description` | Free-form; no truncation guard |
| `quantity` (default 1) | `quantity` | ✓ |
| `unitValue` + `customsCurrency` | `unit_value.{amount,currency}` | Currency lowercased |
| `unitWeight` + `intl.weightUnit` | `item_weight` + `weight_unit` | Unit normalised |
| `hsCode` | `harmonized_tariff_code` | Pass-through, no format normalisation |
| `countryOfOrigin` | `country_of_origin` | Pass-through, no ISO normalisation |
| `sku` | `sku` | ✓ |

### 8.2 Findings — line items

| ID | Severity | Finding |
|---|---|---|
| **T-LI1** | **MAJOR** | **HS code not normalised.** Operators paste HS codes in two shapes — dotted (`6109.10.0012`) and digits-only (`6109100012`). Spec says `harmonized_tariff_code` with no format guidance; SERA likely strips dots but has been seen to reject mixed `6109.10.0012` where USPS expects 10 digits. Strip non-digits, pad or truncate to 10, before sending. One utility function. |
| **T-LI2** | **MAJOR** | **`country_of_origin` not normalised to ISO 3166 alpha-2.** Our DTO accepts whatever the operator typed (seen: `"USA"`, `"United States"`, `"us"`, `"U.S.A."`). SERA's spec explicitly says alpha-2 for `country_code` in addresses; `country_of_origin` is undocumented but USPS CN22/CN23 require alpha-2. Rejection by SERA is 400 with `error_code=800000`. Normalise via `CountryEntity.alpha2` lookup. |
| **T-LI3** | **MAJOR** | **No field-length guards.** USPS CN22 form caps `item_description` at ~50 chars; CN23 at ~255. Spec silent on SERA limits. A 500-char description silently truncates on SERA's side (or is rejected). Enforce DTO-level `@Size(max=255)` and warn operator pre-commit. |
| **T-LI4** | MAJOR | **`item_weight × quantity` not reconciled against `package.weight`.** If declared contents weight > package weight, USPS Customs flags for inspection; if way under (<10%), SERA may 400. Spec doesn't require matching but regulation does. Add a cross-check in `buildSeraCustoms` with a 20% tolerance and a WARN log. |
| **T-LI5** | MINOR | **`unit_value` currency hardcoded fallback to `"usd"`** (`:1214`). If `intl.customsCurrency` is blank, we silently claim USD. Should throw — currency on customs is regulatory, not best-guess. |
| **T-LI6** | MINOR | **No per-item cap enforcement.** USPS has physical-form limits (CN22 fits ~5 items; CN23 extends). SERA accepts arbitrary counts in `customs_items[]` but downstream CBP filing (ACE / AES) has a 50-item cap per shipment. 100-item shipments silently truncate on CBP side. Enforce `@Size(max=50)` on `commodities`. |
| **T-LI7** | MINOR | **No ECCN / license-code field.** SERA spec includes `sender_info.certificate_number` and `sender_info.license_number` — the slot for ECCN / export-control codes — but we don't thread them. Blocks EEI filings for licensable goods. Related to T-C1. |
| **T-LI8** | NICE | **No commodity-level `restricted` flag.** Even if we're not filing EEI, surfacing a per-item restriction warning in the FE improves operator trust. |

### 8.3 Line-item flow for MPS

See §7.2 T-MPS2 — the current implementation attaches the full
`customs_items[]` to every piece of an MPS, which is a MAJOR regulatory
problem independent of the individual field findings above. Fix order:
land T-MPS2 first (per-piece commodity split), then the field-level
normalisation PRs (T-LI1 / T-LI2 / T-LI3) apply at a well-defined
emission point.

---

## 9. US territories — domestic vs international

Six ISO codes under `UsTerritoryNormalizer`: `PR`, `VI`, `GU`, `AS`,
`MP`, `UM`. The normaliser is DB-driven via V111 + `country` table +
`CountryPlatformService` swap at startup. Three military codes
(`AA`, `AE`, `AP`) are NOT included — they're state codes under
country=US, not ISO country codes.

### 9.1 USPS / SERA treatment

- **PR / VI / GU / MP / AS / UM** — USPS treats as **domestic**. ZIP
  ranges are valid USPS ZIPs. Pricing is domestic. **No customs form
  required by USPS** for mail class to these destinations.
  - SERA wire: `country_code=US, state_province=PR` is correct.
- **APO / FPO / DPO** (`state=AA|AE|AP`, ZIP 09xxx-34xxx) — USPS treats
  as **domestic**. Military mail routes through USPS to overseas APO
  depots. **CN22 customs form IS required** for parcels (crosses
  foreign customs borders even though delivery is on-base).
  - SERA wire: `country_code=US, state_province=AA` with USPS ZIP.
- **Outbound from a US territory** (e.g. shipper in PR → recipient in
  Canada) — the territory is the ORIGIN; USPS still treats this as a
  domestic-origin intl shipment but SERA's `from_address.country_code`
  may need to be `PR` for correct routing. Spec is silent; behaviour
  undocumented.
- **Mainland US → US-territory, truly intl** — doesn't happen under
  USPS semantics. The only inter-territory transfers that are intl
  from USPS's view are PR/VI/GU/MP/AS → non-US destinations.

### 9.2 How territories flow through the SERA connector today

`createShipmentSera` → `buildSeraCreateLabelBody` → `buildSeraAddress`:
- Country code passed VERBATIM from `request.getRecipientCountryCode()`
  (`StampsConnector.java:1068`). **No call to `UsTerritoryNormalizer`**
  from any SERA path.
- Shipper country same (`:1060`).
- Territory normalisation IS called from `CarrierServiceImpl.java:2074-2081`
  / `:2176-2179` / `:3462-3465` and from the FedEx and UPS connectors
  — just not from SERA.
- `UsFtr30_37Policy.java:70-72` DOES normalise for FTR purposes — this
  is correct and should not change (territory → US for export-rules
  check); but a parallel normaliser for the SERA wire would differ in
  intent.

### 9.3 Findings — territories

| ID | Severity | Finding |
|---|---|---|
| **T-TR1** | **MAJOR** | **No guard against intl customs block on US-territory destinations.** `IntlShipmentBlockDTO.isReadyForCarrier()` returns true when `international=true` + commodities + currency + incoterms (`IntlShipmentBlockDTO.java:230-234`). It does NOT check whether the destination is a US territory. If an operator (or the FE) flags a US→PR shipment as international (because PR often requires customs for FedEx/UPS and the operator muscle-memories "PR = customs"), `buildSeraCustoms` emits a customs block for a USPS-domestic lane. SERA / USPS rejects at label-create time with a confusing error. Fix: in `buildSeraCreateLabelBody`, guard the customs emit with `!UsTerritoryNormalizer.isUsTerritory(recipientCountry, recipientState) && !isMilitaryState(recipientState)` — a US-territory or APO/FPO destination silently drops the customs block (unless the operator has an explicit override). |
| **T-TR2** | **MAJOR** | **APO / FPO / DPO handling missing.** `state=AA|AE|AP` with ZIP 09xxx-34xxx IS domestic USPS (correct not to normalise country) but DOES need a CN22 customs form if the parcel is not FCL (First-Class Letter). Current code never emits customs for APO addresses (always domestic treatment). USPS will reject the label at acceptance. Fix: add `UsTerritoryNormalizer.isMilitaryState(state)` → when true + non-FCL service, force a customs block even though `intl.international == false`. Separately: ensure the operator-facing form shows "APO/FPO parcel — a customs declaration is required" above the commodities table. |
| **T-TR3** | MINOR | **Territory service allowlist not checked for SERA.** `UsTerritoryNormalizer.isServiceAllowedForTerritory` has entries for UPS and FedEx but not USPS/STAMPS — correctly, because USPS serves every territory with the same services. But there's no explicit assertion of this; a future territory added to the DB with a per-carrier allowlist constraint would silently skip SERA. Add an explicit USPS=`*` wildcard entry so the audit log says "USPS allows all services for PR" instead of "no allowlist found, falling back to legacy denylist". |
| **T-TR4** | MINOR | **Outbound-from-territory shipper country.** If `shipper.state=PR, country=US` and recipient is Canada, is this: (a) a US-origin intl shipment (SERA sees `from_address.country=US, state=PR`), or (b) a PR-origin intl shipment (SERA sees `from_address.country=PR`)? The spec is silent; USPS acceptance rules treat PR as a domestic-origin. We send (a) by not normalising. Flag for Stamps.com developer support confirmation — the fix is a one-line call to `normalizeCountryCode(shipperCountry, shipperState)` on `from_address` IF their support says the territory code is preferred. |
| **T-TR5** | MINOR | **`ZIP+4` not enforced for territory ZIPs.** PR/VI/GU/MP/AS have USPS ZIPs that always carry a `+4` extension in USPS systems. `request.getRecipientPostalCode()` passes through unchecked. SERA may accept 5-digit without extension; USPS may reject at acceptance. Add a `@Pattern` for territory ZIPs that requires 5+4. |
| **T-TR6** | NICE | **No per-territory service hint on the FE.** When operator picks a PR destination, the FE could show "USPS serves PR as domestic; no customs needed" — defuses the T-TR1 anti-pattern that leads to incorrect intl flagging. |

### 9.4 Territory × MPS × line items cross-matrix

| Lane | Customs block? | CN form | Service allowlist | Idempotency scope |
|---|---|---|---|---|
| US → US (CONUS) | Never | None | USPS: all | per-piece |
| US → PR / VI / GU / MP / AS / UM | **Never** (T-TR1) | None | USPS: all | per-piece |
| US → APO / FPO / DPO (parcel) | **Yes** (T-TR2) | CN22 or PS 2976 | USPS: parcel-eligible only | per-piece |
| US → non-US intl | Yes | CN22 / CN23 per piece (T-MPS2) | USPS intl services only | per-piece |
| PR → US (CONUS) | Never | None | USPS: all | per-piece |
| PR → non-US intl | Yes | CN22 / CN23 | USPS intl services only | per-piece |

The second row is where T-TR1 bites: today the connector will happily
send a customs block if `isReadyForCarrier()` is true, regardless of
whether the destination is a US territory.

---

## 10. Fix track additions (T9-T11)

Appending to the T-track from §6. Three more PRs covering MPS, line
items, and territories.

| PR | Scope | LoC | Risk | Fixes |
|---|---|---|---|---|
| **PR-T9** | **MPS correctness.** Insurance split across pieces (`insuredValue ÷ N` with last-piece rounding absorption). Per-piece `customs_items[]` wiring: new `PackageDetailDTO.commodityRefs: List<Integer>` indexing into `IntlShipmentBlockDTO.commodities`, plus FE on `/orders/new` intl form to let operator assign commodities to pieces. Lead-piece flag + `order_tracking.lead_tracking_number`. MPS_PARTIAL_ROLLBACK audit event + dashboard alert if a rollback void itself failed. Non-spec `_x_piece_context` moved to `X-Piece-Context` header. | ~500 | MEDIUM | T-MPS1-T-MPS6 |
| **PR-T10** | **Line-item normalisation + guards.** `CustomsCommodityNormaliser` util: HS-code strip-non-digits + 10-digit coerce, `country_of_origin` → alpha-2 via `CountryEntity` lookup, DTO-level `@Size(max=255)` on description + `@Size(max=50)` on `commodities`, item-weight × qty vs package-weight ±20% WARN. Fail-fast when `customsCurrency` blank (no silent USD fallback). | ~300 | LOW | T-LI1-T-LI6 |
| **PR-T11** | **US territory + military handling.** `UsTerritoryNormalizer.isMilitaryState(state)` returning true for AA/AE/AP. Guard the customs emit in `buildSeraCreateLabelBody` with `!isUsTerritory && !isMilitaryState` for USPS lanes. APO/FPO parcel-service detection → force customs even when `intl=false`. USPS `*` wildcard in `ALLOWLIST`. ZIP+4 regex guard for territory ZIPs. FE copy on `/orders/new` recipient block clarifying domestic treatment. | ~350 | MEDIUM | T-TR1-T-TR5 |

### Updated dispatch strategy

1. **T1 alone** — error-code parser.
2. **T2 + T7 + T10 in parallel** — write-side keys, intl sender_info,
   line-item normalisation (all touch `buildSeraCustoms` / adjacent;
   coordinate at merge, not during development).
3. **T3 + T4 + T5 + T6 + T11 in parallel** — new endpoints + territory
   guards; independent.
4. **T9 last of substance** — MPS fix depends on the per-piece customs
   split which depends on T10's clean emission layer.
5. **T8 last overall** — auth polish.

Total updated: **11 PRs / ~3,000 LoC.** 4-5 days agent-parallelised.

---

## 11. Non-goals

- **SWSIM paths.** Deliberately out of scope per operator; this audit
  grades SERA parity only. SWSIM deprecation is a separate track.
- **Container labels / carrier-services / account-mgmt / security-questions
  endpoints** — operator confirmed not needed.
- **Full OIDC conformance.** Spec doesn't document OIDC semantics; our
  OAuth 2.0 implementation is sufficient. If `id_token` claim usage ever
  becomes a requirement, revisit as a separate micro-track.
- **Cross-JVM token cache.** T-A5 would move the cache to Redis for
  multi-instance deployments; defer until we actually cluster the
  backend.
- **SERA v2 / future API versions.** Audit scoped to v1 as published.

---

## 12. Related memory

- [[stamps-com-audit]] — S-track (2026-09-17) that preceded this; adjacent scope
- [[usps-direct-integration]] — G-track reference pattern
- [[intl-export-declaration-track]] — ITN + ExportDeclarationPolicy source for PR-T7
- [[carrier-api-log]] — observability already in place for every SERA call
- [[writeback-journal]] — pattern for durable retry on T-E2
- [[idempotency-keys]] (`IdempotencyKeys.forStampsOrder`) — reuse source for T2
- [[connector-boundary-guard-pattern]] — error-shape precedent for T-E1
- [[widen-via-dto-field-not-new-arg]] — pattern for T-C1 sender_info threading
