# USPS_DIRECT integration — design doc

Status: DRAFT v3 (platform-owned OAuth + tenant identifiers 2026-09-15)
Author: 2026-09-15 session
Origin: synthesis of three research reports (StampsConnector patterns, USPS v3 API surface, system-settings wiring) plus locked user decisions across three rounds.

## 1. Goal + non-goals

**Goal.** Add a second USPS provider path (`USPS_DIRECT` — USPS APIs v3 REST) that coexists with the existing `StampsConnector`. This is a SaaS platform: **the platform owns the USPS Direct OAuth 2.0 API credentials** (one platform-wide OAuth app registered at developers.usps.com); **each tenant supplies only their USPS identifiers** (EPS account number + CRID + MID) so labels bill to their own USPS mailer. A global system-setting picks which provider services USPS shipments platform-wide.

**Non-goals for this release (Stage 1).**
- Retiring `StampsConnector` — it stays as the default USPS path.
- **Stamps.com partner integration** — deferred to a future release. This release keeps Stamps.com on the existing per-tenant `client_id/client_secret` model (unchanged). Migrating Stamps.com to platform-owned partner creds is Stage 2, out of scope here.
- Runtime provider failover (v3 → Web Tools on error). Fallback is **per-endpoint at compile time only**.
- Per-tenant provider choice — the toggle is truly global.
- Introducing a distinct `carrierCode='USPS_DIRECT'`. Both providers share `carrierCode='USPS'`; the connector dispatch branches on `system_setting.USPS_PROVIDER`.

## 2. Locked decisions (three rounds)

Round 1 (initial):

| # | Question | Answer |
|---|---|---|
| 1 | API family | Both — v3 REST primary, Web Tools per-endpoint fallback. |
| 2 | Fallback trigger | Per-endpoint (compile-time). |
| 3 | FE model | Global system-settings toggle. |
| 5 | Scope | Full connector: rates, labels, tracking + webhooks, address validation, void, refund, international. |
| 6 | Slicing | MVP first, then feature rollout in PR-A through PR-E. |
| 7 | Test creds | CAT/sandbox available; live-CAT tests gated by `USPS_LIVE_CAT_TESTS=true`. |

Round 2 (SaaS + BYOC refinement — now superseded):

| # | Question | Answer |
|---|---|---|
| 8-13 | Drawer UX, flip semantics, provisioning workflow | Locked; see §3 state machine. |

Round 3 (platform-owned OAuth refinement — CURRENT):

| # | Question | Answer |
|---|---|---|
| 14 | Where do USPS Direct OAuth creds live? | **Platform-owned** — `USPS_PLATFORM_CLIENT_ID` + `USPS_PLATFORM_CLIENT_SECRET` in `system_setting`, encrypted at rest. |
| 15 | What USPS identifiers does each tenant provide? | **All three**: USPS EPS account number + CRID + MID. USPS has no public "lookup CRID by account number" endpoint, so tenants supply all three. |
| 16 | Does Stamps.com also move to platform-owned creds? | **Not this release.** Stamps.com partner-integration is Stage 2 (deferred). This release keeps Stamps.com on per-tenant `client_id/client_secret` — unchanged. |
| 17 | Migration for existing Stamps.com tenants | **N/A** — Stamps.com stays as-is. |

## 3. The 3-value `USPS_PROVIDER` state machine

```
      STAMPS_COM ──(admin)──► PROVISIONING_USPS_DIRECT ──(admin)──► USPS_DIRECT
          ▲                              │
          │                              │ (admin, only if
          │                              │  readiness < 100%)
          │                              ▼
          └────────────────── (rollback anytime)
```

Rules enforced at `PUT /admin/system-settings/USPS_PROVIDER`:

- `STAMPS_COM → PROVISIONING_USPS_DIRECT` — always allowed. No pre-check.
- `PROVISIONING_USPS_DIRECT → USPS_DIRECT` — allowed **only if**:
  1. Platform creds are populated: `USPS_PLATFORM_CLIENT_ID` and `USPS_PLATFORM_CLIENT_SECRET` both non-empty in `system_setting`.
  2. Every `carrier_account_ref` with `carrier_code='USPS'` has `usps_direct_account_number`, `usps_direct_crid`, and `usps_direct_mid` populated.
  Backend returns HTTP 409 CONFLICT with a per-tenant/per-field gap list when either check fails.
- `USPS_DIRECT → PROVISIONING_USPS_DIRECT` — always allowed (rollback into a mixed state).
- `PROVISIONING_USPS_DIRECT → STAMPS_COM` — always allowed (tenants still have Stamps creds by construction).
- `STAMPS_COM → USPS_DIRECT` direct jump — rejected with 400. Admin must transit `PROVISIONING_USPS_DIRECT`.
- `USPS_DIRECT → STAMPS_COM` direct jump — same, must transit `PROVISIONING_USPS_DIRECT`.

Runtime provider (which connector actually serves calls):

- `STAMPS_COM` → `StampsConnector`
- `PROVISIONING_USPS_DIRECT` → **still `StampsConnector`** (transitional; USPS creds are being gathered but not yet used at runtime)
- `USPS_DIRECT` → `UspsDirectConnector`

Drawer field visibility on `/settings/carriers` USPS drawer:

- `STAMPS_COM` — Stamps fields only: `client_id`, `client_secret`, `account_number` (Stamps.com customer identifier). Same as today.
- `PROVISIONING_USPS_DIRECT` — Both sections visible. Stamps section marked "Active provider — currently in use". USPS Direct section (account number + CRID + MID) marked "Not yet active — please fill in for the upcoming platform switch".
- `USPS_DIRECT` — USPS Direct fields only: `usps_direct_account_number`, `usps_direct_crid`, `usps_direct_mid`. Stamps values remain in the DB for rollback but are not shown in the drawer.

## 4. Credential + identifier surfaces

### 4.1 Platform-level (in `system_setting`, encrypted at rest via `EncryptedStringConverter`)

| Key | Kind | Purpose |
|---|---|---|
| `USPS_PROVIDER` | CHOICE | `STAMPS_COM \| PROVISIONING_USPS_DIRECT \| USPS_DIRECT`. Default `STAMPS_COM`. |
| `USPS_PLATFORM_CLIENT_ID` | SECRET | USPS OAuth 2.0 Consumer Key from platform's app at developers.usps.com. |
| `USPS_PLATFORM_CLIENT_SECRET` | SECRET | USPS OAuth 2.0 Consumer Secret. |

Note: no `STAMPS_PLATFORM_*` keys this release — Stamps.com stays per-tenant.

### 4.2 Tenant-level (per-`carrier_account_ref` row, new columns via V58)

| Column | Type | Purpose |
|---|---|---|
| `usps_direct_account_number` | VARCHAR(50) | USPS EPS (Enterprise Payment System) account number. Ties CRID to a billing account. |
| `usps_direct_crid` | VARCHAR(20) | Customer Registration ID. Sent as `X-USPS-CRID` header + `senderInfo.CRID` on label calls. |
| `usps_direct_mid` | VARCHAR(20) | Mailer ID. Sent as `senderInfo.MID` on label calls + `manifestMID` on SCAN forms. |

Note: identifiers, not secrets — stored plaintext (**open question 1 in §11** confirms). Existing `client_id` / `client_secret` columns on `carrier_account_ref` remain intact — they continue to serve Stamps.com rows and other carriers (FedEx / UPS / DHL) unchanged.

## 5. Database changes

`V58__usps_direct_account_columns.sql`:

```sql
-- USPS_DIRECT integration — Add per-tenant USPS identifiers.
-- Platform-owned OAuth creds live in system_setting; only the
-- tenant's USPS identifiers live per-account. Existing client_id /
-- client_secret columns stay untouched (still serve Stamps.com rows
-- + FedEx/UPS/DHL).
ALTER TABLE carrier_account_ref
    ADD COLUMN IF NOT EXISTS usps_direct_account_number VARCHAR(50),
    ADD COLUMN IF NOT EXISTS usps_direct_crid           VARCHAR(20),
    ADD COLUMN IF NOT EXISTS usps_direct_mid            VARCHAR(20);

COMMENT ON COLUMN carrier_account_ref.usps_direct_account_number IS
  'USPS EPS (Enterprise Payment System) account number. NULL = tenant has not populated USPS Direct identifiers yet.';
COMMENT ON COLUMN carrier_account_ref.usps_direct_crid IS
  'USPS Customer Registration ID. Required on /labels/v3/label, /scan-forms/v3/scan-form, /payments/v3/payment-authorization.';
COMMENT ON COLUMN carrier_account_ref.usps_direct_mid IS
  'USPS Mailer ID. Sent as senderInfo.MID in label creation; manifestMID in SCAN forms.';
```

No down-migration needed (columns are additive-nullable). No `system_setting` migration needed (KNOWN_SETTINGS lazy-inserts).

## 6. Backend changes

### 6.1 `KNOWN_SETTINGS` entries

In `SystemSettingsController.KNOWN_SETTINGS`:

```java
new SettingSpec(
    "USPS_PROVIDER",
    "Which USPS provider handles USPS shipments platform-wide. Provisioning mode exposes both sections in the tenant drawer so tenants can pre-populate the new provider's identifiers before the flip.",
    Kind.CHOICE,
    List.of("STAMPS_COM", "PROVISIONING_USPS_DIRECT", "USPS_DIRECT"),
    "STAMPS_COM"),

new SettingSpec(
    "USPS_PLATFORM_CLIENT_ID",
    "USPS OAuth 2.0 Consumer Key from the platform's app at developers.usps.com. Used by every tenant to mint OAuth tokens for USPS Direct.",
    Kind.SECRET,
    List.of(),  // free-form secret
    null),

new SettingSpec(
    "USPS_PLATFORM_CLIENT_SECRET",
    "USPS OAuth 2.0 Consumer Secret paired with USPS_PLATFORM_CLIENT_ID.",
    Kind.SECRET,
    List.of(),
    null),
```

### 6.2 Transition-guard endpoint

In `SystemSettingsController.updateSetting`, add:

```java
if ("USPS_PROVIDER".equals(key)) {
    validateUspsProviderTransition(currentValue, newValue);
}
```

`validateUspsProviderTransition` rejects:
- Direct `STAMPS_COM ↔ USPS_DIRECT` jumps → 400 with pointer to `PROVISIONING_USPS_DIRECT`.
- `PROVISIONING_USPS_DIRECT → USPS_DIRECT` if platform creds are missing OR any tenant is missing USPS identifiers → 409 with a `UspsProviderReadinessDTO`.

### 6.3 Readiness endpoint

`GET /api/v1/admin/system-settings/USPS_PROVIDER/readiness`:

```json
{
  "currentProvider": "PROVISIONING_USPS_DIRECT",
  "targetProvider": "USPS_DIRECT",
  "platformCreds": {
    "clientIdSet": true,
    "clientSecretSet": false
  },
  "totalUspsAccounts": 12,
  "readyAccounts": 8,
  "pendingAccounts": [
    { "tenantCode": "ACME",  "accountNumber": "A123",
      "missing": ["usps_direct_crid", "usps_direct_mid"] },
    { "tenantCode": "GLOBEX", "accountNumber": "G456",
      "missing": ["usps_direct_account_number", "usps_direct_crid", "usps_direct_mid"] }
  ]
}
```

`overallReady = platformCreds.clientIdSet && platformCreds.clientSecretSet && pendingAccounts.length === 0`.

### 6.4 `UspsDirectConnector` skeleton

```java
@Component
@RequiredArgsConstructor
public class UspsDirectConnector implements CarrierConnector {

    private static final String CARRIER_CODE = "USPS";

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final UspsOAuthTokenCache tokenCache;       // platform-wide, keyed by env
    private final UspsPaymentAuthCache paymentCache;    // per-tenant, keyed by CRID+MID
    private final SystemSettingService settings;

    @Override public String getCarrierCode() { return CARRIER_CODE; }
    @Override public String getCarrierName() { return "USPS Direct"; }

    /**
     * Platform-owned OAuth: token minted from USPS_PLATFORM_CLIENT_ID /
     * USPS_PLATFORM_CLIENT_SECRET (from system_setting), NOT from any
     * per-tenant fields. The (clientId, clientSecret) params from the
     * CarrierConnector interface are IGNORED here — kept for interface
     * conformance but the values are read from system_setting.
     *
     * Fallback token 'usps-direct-local-{accountId}' returned when
     * platform creds are blank so downstream short-circuits gracefully
     * (mirrors StampsConnector's -local- convention).
     */
    @Override
    public String getAccessToken(String ignoredClientId, String ignoredClientSecret,
                                 String accountNumber, String environment) {
        String platformClientId = settings.getPlain("USPS_PLATFORM_CLIENT_ID").orElse("");
        String platformClientSecret = settings.getPlain("USPS_PLATFORM_CLIENT_SECRET").orElse("");
        if (!StringUtils.hasText(platformClientId) || !StringUtils.hasText(platformClientSecret)) {
            return "usps-direct-local-" + accountNumber;
        }
        return tokenCache.get(platformClientId, platformClientSecret, environment,
                () -> mintV3Token(platformClientId, platformClientSecret, environment));
    }

    // Downstream methods (getRates, createShipment, etc.) look up the
    // tenant's identifiers from carrier_account_ref before building the
    // request payload. See PR-A through PR-E for slice ownership.
}
```

### 6.5 Dispatch in `CarrierServiceImpl.getCarrierConnector`

Around line 2710, add the branching for USPS:

```java
if ("USPS".equalsIgnoreCase(canonical)) {
    String provider = systemSettingService.getPlain("USPS_PROVIDER")
            .orElse("STAMPS_COM");
    // PROVISIONING_USPS_DIRECT still routes to Stamps at runtime —
    // it's a transitional onboarding state, not a runtime switch.
    Class<?> target = "USPS_DIRECT".equals(provider)
        ? UspsDirectConnector.class
        : StampsConnector.class;
    return carrierConnectors.stream()
        .filter(target::isInstance).findFirst()
        .orElseThrow(...);
}
```

### 6.6 Tenant identifier resolution per call

The connector methods that build a v3 request payload read the tenant's identifiers from the `CarrierAccountRef` row:

```java
String crid = account.getUspsDirectCrid();
String mid  = account.getUspsDirectMid();
String accountNumber = account.getUspsDirectAccountNumber();

if (!StringUtils.hasText(crid) || !StringUtils.hasText(mid)
        || !StringUtils.hasText(accountNumber)) {
    throw new IllegalArgumentException(
        "USPS Direct: this tenant is missing USPS identifiers (crid/mid/account). "
        + "Fill them in /settings/carriers before rate-shopping USPS. "
        + "Order " + request.getReferenceNumber() + ".");
}
```

Boundary guard on token:

```java
if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
    throw new IllegalStateException(
        "USPS Direct is not configured platform-wide — no OAuth token "
        + "available. Set USPS_PLATFORM_CLIENT_ID / USPS_PLATFORM_CLIENT_SECRET "
        + "in /settings/system.");
}
```

### 6.7 Caches

- **OAuth token cache** — platform-wide, single entry keyed by `(clientId, environment)`. 8h TTL, refresh at 7.5h. One token serves every tenant.
- **Payment-authorization token cache** — per-tenant, keyed by `(CRID, MID, accountNumber)`. Minted lazily on first label call for that tenant. TTL follows USPS's `payment-authorization-token` response.

## 7. Frontend changes

### 7.1 `/settings/system`

Three new settings appear from `KNOWN_SETTINGS`:
- `USPS_PROVIDER` — CHOICE (radio group over 3 values).
- `USPS_PLATFORM_CLIENT_ID` — SECRET (password input, masked as `****xxxx` when set).
- `USPS_PLATFORM_CLIENT_SECRET` — SECRET.

Below the `USPS_PROVIDER` selector, a readiness panel appears **only when current setting is `PROVISIONING_USPS_DIRECT`**:
- Header showing platform-cred status (green ✓ / red ✗ per key).
- Table of per-tenant readiness pulled from `GET /admin/system-settings/USPS_PROVIDER/readiness`. Each row shows tenant code + account number + red/green + missing-field list.
- "Flip to USPS_DIRECT" button disabled unless `overallReady = true`; hover tooltip lists the gaps.

### 7.2 `/settings/carriers` USPS account drawer

Reads `USPS_PROVIDER` on mount:

- **`STAMPS_COM`** — Stamps fields only (`client_id`, `client_secret`, `account_number`). Unchanged from today.
- **`PROVISIONING_USPS_DIRECT`** — Both sections visible. Stamps section marked "Active provider — currently in use". USPS Direct section shows the three identifier fields (`usps_direct_account_number`, `usps_direct_crid`, `usps_direct_mid`) with an "Add these to prepare for the upcoming provider switch" banner. Save persists whatever was filled; validation enforces only the currently-active provider's fields (Stamps).
- **`USPS_DIRECT`** — USPS Direct section only. Guidance card: "You need your USPS EPS account number, Customer Registration ID (CRID), and Mailer ID (MID) — all from developer.usps.com. Contact your USPS account rep if you don't have these yet."

### 7.3 Onboarding wizard

Same visibility rules as the drawer. Onboarding a new tenant during `STAMPS_COM` mode asks for Stamps fields; during `USPS_DIRECT` mode asks for USPS Direct identifiers; during `PROVISIONING` mode asks for both.

## 8. Testing strategy

**Unit tests (no HTTP):**
- `UspsDirectConnectorPayloadTest` — reflection-invoked private envelope builders; JSON body shape assertions for `/prices/v3/total-rates/search` + `/labels/v3/label`, including CRID/MID from tenant row + platform token from cache.
- `UspsDirectRateShopTest` — three token tests (blank / null / `-local-`) mirroring `StampsRateShopTest`. Also: missing tenant CRID / MID / account throws with the actionable message.
- `UspsOAuthTokenCacheTest` — TTL + refresh + platform-wide single-entry (assert no per-tenant duplication).
- `UspsPaymentAuthCacheTest` — per-tenant isolation (different CRID → different cached token) + TTL + refresh.
- `UspsProviderTransitionGuardTest` — every legal + illegal transition; readiness gate exercised with platform-cred-missing AND tenant-cred-missing variants.
- `UspsProviderReadinessTest` — DTO shape; correct field-level missing list.

**Integration tests:**
- `UspsDirectIntegrationTest` — WireMock spins up `apis-tem.usps.com`, records fixtures under `src/test/resources/usps/v3/*.json`. Verifies platform Bearer + `X-Payment-Authorization-Token` + `X-USPS-CRID` headers.
- Live-CAT variant gated by `USPS_LIVE_CAT_TESTS=true`.

**Broader-glob discipline** ([[broader-test-glob-when-fix-touches-shared-state]]):

```
./mvnw test -Dtest='*Stamps*,*UspsDirect*,*RecipientCountry*,*CarrierService*,*SystemSetting*,*UspsProvider*,*UspsOAuth*,*UspsPaymentAuth*'
```

## 9. PR slicing (5 stacked PRs)

| PR | Scope | Est. LoC |
|---|---|---|
| **A (MVP)** | UspsDirectConnector skeleton + platform-OAuth token cache + payment-auth cache + `getRates` (domestic) + `createShipment` (domestic) + V58 migration + `USPS_PROVIDER` + `USPS_PLATFORM_CLIENT_ID/SECRET` KNOWN_SETTINGS + transition-guard endpoint + readiness endpoint + `/settings/system` UI + `/settings/carriers` drawer changes + full unit + IT | ~2200 |
| **B** | Tracking (v3.2 on-demand) + subscription registration + webhook parse/verify | ~600 |
| **C** | Address validation | ~400 |
| **D** | Async void model (`VOID_PENDING` + SCAN exclusion) + PS 3533 refund CSV export + admin surface | ~700 |
| **E** | International label create + customs form fields + HS-6 mandate integration | ~900 |

Each PR stacks on the prior branch. PR-A gates everything else — if it lands cleanly, B/C/D/E can dispatch in parallel to different implementation agents.

## 10. Rollback

- Provider toggle is a system-setting flip **backwards** to `PROVISIONING_USPS_DIRECT` (always allowed) then `STAMPS_COM` (allowed because every tenant still has Stamps creds by construction). Rate cache invalidates on each flip via `CarrierConfigChangedEvent`.
- V58 migration is additive-nullable; no down-migration needed. USPS Direct columns stay in the DB during a Stamps rollback so a re-flip forward can reuse them.
- `UspsDirectConnector` bean is inert until `USPS_PROVIDER=USPS_DIRECT` is set.
- Platform-owned OAuth creds can be rotated at `/settings/system` without an app restart — token cache is invalidated by the setting-change event.

## 11. Open risks + mitigations

- **60 req/hr USPS rate limit** — biggest operational risk. Applies per USPS OAuth app, which the platform owns. Every tenant shares this quota. Mitigation: request quota increase from `APISupport@usps.gov` early; bake in the existing per-tenant fair scheduler ([[project_sprint50_finding15]]) from day one; monitor headroom in `/settings/system`.
- **Web Tools sunset 2026-01-25 already happened** — the fallback story is thinner than expected. PR-D's async void design is the safest response.
- **Addresses API License Agreement (2026-08-01)** in prod may block PR-C rollout until the platform signs. Non-blocking for CAT / MVP.
- **v3.0 tracking retires 2027-07-31** — we use v3.2 from day one.
- **Provisioning phase can drag** — if tenants are slow to add USPS identifiers, the platform lingers in `PROVISIONING_USPS_DIRECT`. That's fine (runtime still uses Stamps) but admin needs the readiness table to nudge stragglers.
- **Stamps.com stays per-tenant this release** — future Stamps partner-integration track is a follow-up; not blocking.

## 12. Locked round-4 details

| # | Decision | Notes |
|---|---|---|
| 18 | Tenant identifier storage | **Plaintext.** CRID / MID / USPS account number aren't credentials — they identify a mailer but don't authorize anything without the platform's OAuth token. Consistent with existing `account_number` handling. |
| 19 | Async void UX (PR-D) | **Optimistic "cancelled"** with background reconciliation. `/orders/{n}` flips to CANCELLED immediately; nightly job reconciles against USPS eVS Refund report. If USPS rejected (e.g., label was already scanned), status flips to VOID_FAILED with an operator toast. |
| 20 | Payment-authorization token cache | **Per-tenant** — keyed by `(CRID, MID, accountNumber)`. Matches USPS' `payment-authorization` `roles[]` design so eVS billing goes to the tenant's own mailer. Costs more of the 60/hr quota than a shared token, but is the only design that bills correctly. |

## 13. Ready to dispatch

All design questions closed. Implementation begins on branch `feat/usps-direct-mvp-pr-a` (Task #4 in the task list).
