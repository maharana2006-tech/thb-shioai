# Auth + Setup — shioai vs ShipX (§5 + §6)

Read-only gap analysis. Compares legacy ShipX handover doctrine
(`ShipX/docs/handover/source/combined.md` §5 lines 354–609 and §6 lines
610–1378, plus deepdive/appendix-B) to the current shioai codebase.

- **Audit date:** 2026-09-30
- **shioai branch:** `dev` @ `093d7731`
- **ShipX source:** 14-Sep-2026 backup + handover
- **Method:** read files + grep; role-gates verified against `SecurityConfig.java` per memory `feedback_verify_role_gates_against_securityconfig`.

---

## 1. Overview

**§5 (Signing in, users and roles).** ShipX is a small session-cookie
ASP.NET MVC app with three hardcoded roles (Shipper / Admin / Super
Admin), AES-reversible passwords, a captcha, and menus that hide links
per role. shioai is a stateless-JWT Spring app with three roles
(USER / ADMIN / TENANT — plus an API-key ROLE_API for external calls),
BCrypt hashes, per-user tokenVersion revocation, invite-based signup,
email verification, admin user-management page, brute-force lockout and
public-endpoint rate limits. shioai is materially stronger everywhere
except: the role vocabulary is still a `varchar` column (no `roles`
table), permissions are hardcoded `@PreAuthorize`, and no captcha is
wired in.

**§6 (Setup: clients, carriers and printers).** ShipX keeps every
setup surface (carrier, service, package, client, importer, printer,
user, employee, ship-station, fund) as its own `SPX_tbl*` table with
one screen and one `spx_pro*` procedure each. shioai has richer entities
per surface but replaces two ShipX concepts with different mechanisms:
(a) the carrier list is not a DB table — carriers are Spring `@Component`
connectors (5 of them: FedEx, UPS, Stamps, USPS-Direct, DHL); and
(b) per-client shipping behaviour (STD ship-via, ShipmentType API/FSM,
MeterNo, FedExMGR/DDP-DAP, IsDTC) is spread across several dedicated
tables (`client_shipping_policy`, `invoice_copies`, `client_allowed_service`,
`carrier_account_ref` with per-client rows), which is cleaner but doesn't
1:1 cover the ShipX `Client STD Code`, Ship Manager FSM path, or the
"IsTBAc / ship on Thoroughbred's account" concept.

---

## 2. ShipX §5 behaviour

| Concern | ShipX | Ref |
|---|---|---|
| User table | `SPX_tblloginCredential` joined to `SPX_tbluserrole` | combined.md L5 |
| Role table | `SPX_tbluserrole` (3 rows: 1 Shipper, 2 Admin, 3 Super Admin) | combined.md L69, L392 |
| Role check on menu | text compare in `_Layout.cshtml` and `ModelHelperObj.cs:L8-L10` | combined.md L140-L143 |
| Session storage | in-proc (ShipX) / `SessionCache` PG table (ShipXSync) | combined.md L33 |
| Session timeout | 8h ShipX / 4h+12h ShipXSync | combined.md L33 |
| Password (ShipX) | AES-reversible; edit form decrypts + shows | combined.md L16, L38, L46 |
| Password (Sync) | Argon2id; upgrades AES on first login | combined.md L17 |
| Captcha | picture captcha (commented out in ShipX; enforced in Sync) | combined.md L58, L59 |
| Rate limit | none in ShipX; 5/15min in Sync | combined.md L60 |
| Lockout | none | combined.md L47 |
| Sign-in query | `SELECT * FROM SPX_tblloginCredential JOIN SPX_tbluserrole WHERE UserId='<typed>' AND Status=true` — user id concatenated (SQLi in ShipX) | combined.md L91-L96, L221 |
| Create user | ADMIN + Super Admin; picks role + default label + invoice printer | combined.md L38, L156-L184 |
| Delete user | soft-flip via `spx_UpdateStatus 'User'` | combined.md L211 |
| Known bugs (from doc) | session fixation, SQLi at login, reversible passwords, no lockout, Sync-locks-out-ShipX, inactive role still works, name==userId, decrypt-into-form, error-message-as-password on empty | combined.md L44-L52, L220-L232 |

---

## 3. shioai §5 counterpart

| Concern | shioai | file:line |
|---|---|---|
| User table | `users` (JPA entity, Hibernate ddl-auto=update) | `backend/.../model/User.java:15` |
| Role | free-form `varchar(100)` column on `users`; **no `roles` table** | `model/User.java:38-39` |
| Role validation | enforced only via `Set.of("USER","TENANT","ADMIN")` | `service/AdminUserService.java:213` |
| API-key role | `ROLE_API` (from `ApiKeyAuthenticationFilter`), separate auth path | `config/SecurityConfig.java:212` |
| Auth strategy | stateless JWT in httpOnly cookie + CSRF double-submit | `config/SecurityConfig.java:98-155` |
| Password hash | BCrypt | `config/SecurityConfig.java:45-47` |
| Session | none (`SessionCreationPolicy.STATELESS`) | `config/SecurityConfig.java:143-155` |
| Token expiry | 24h (`jwt.expiration=86400000`) | `application.properties:272` |
| Token revocation | `token_version` bump + Redis `jti` blacklist | `model/User.java:75-77`, `service/TokenRevocationService.java` |
| Captcha | interface only (`CaptchaVerifier`), default is no-op | `service/captcha/CaptchaVerifier.java:12-17` |
| Rate limit (public auth) | `PublicAuthRateLimiter` (60/hr per IP on invite-preview + verify-email); `SignupRateLimiter` per-email+IP; `AuthFailureLimiter` per (IP, username) 429 lockout | `controller/AuthController.java:37-39, 120`, `service/AuthServiceImpl.java:69, 82` |
| Lockout | `AuthFailureLimiter.isLocked(ip, username)` before bcrypt | `service/AuthServiceImpl.java:434-443` |
| Timing side channel | dummy bcrypt compare on unknown username | `service/AuthServiceImpl.java:92-94, 447+` |
| Sign-in query | JPA `userRepository.findByUsername(username)` — parameterised | `service/AuthServiceImpl.java:445` |
| Create user (public signup) | disabled by default; requires clientCode + email verification | `controller/AuthController.java:52-56`, `service/AuthServiceImpl.java:121-219` |
| Create user (invited) | admin mints token; invitee sets password + username; role from invite | `controller/AdminUserInviteController.java:56-84`, `service/AuthServiceImpl.java:224-297` |
| Admin user page | list / assign clientCode / deactivate / reactivate / change role / audit — `ADMIN` only | `controller/AdminUserController.java:39, 68-146` |
| Last-admin quorum | refuses deactivate/demote if `activeAdmins <= 1` | `service/AdminUserService.java:266-273` |
| FE roles | `type UserRole = 'ADMIN' \| 'USER' \| 'TENANT'`; defence-in-depth guards | `multiship-react/src/utils/roles.ts:3-13`; `routes/AppRoutes.tsx:141, 181` |

**Verified role gates on the admin surface** (via `SecurityConfig.java` +
`@PreAuthorize`, per memory feedback):
- `/api/v1/admin/users/**` — class-level `@PreAuthorize("hasRole('ADMIN')")` at `AdminUserController.java:39`
- `/api/v1/admin/user-invites/**` — same at `AdminUserInviteController.java:37`
- `/api/v1/admin/external-systems/**` — same at `ExternalSystemsAdminController.java:39`
- `/api/v1/carriers/connect|disconnect` — `hasRole('ADMIN')` in chain at `SecurityConfig.java:209`
- `/api/v1/api-keys/**` — `hasRole('ADMIN')` at `SecurityConfig.java:210`
- All other class-level `@PreAuthorize("hasAnyRole('ADMIN','USER')")` for tenant-scoped reads; `+ @accessScope.canAccessTenant(auth,#clientCode)` on tenant-scoped writes (e.g. `ClientController.java:72,89,104,114,133`)

---

## 4. Gaps (§5)

| # | Gap | Direction | Impact |
|---|---|---|---|
| 5-A | No `roles` / `role_permission` table. Role is a `varchar` on `users`, allowed set is a hardcoded `Set.of("USER","TENANT","ADMIN")` at `AdminUserService.java:213`, and every route decides authority via `@PreAuthorize` string literals. Cannot add a role without a code change. | shioai < ShipX | ShipX has a table but the same "role names are hard-coded" bug (combined.md L224). shioai inherits the same limitation and adds nothing. |
| 5-B | No captcha wiring. `CaptchaVerifier` interface exists (`service/captcha/CaptchaVerifier.java:12`) but only a no-op impl. ShipX/Sync ship a real one. | shioai < ShipX-Sync | If `signup.public-enabled=true` is ever flipped, signup is captcha-free. |
| 5-C | No forgot-password / self-serve reset flow surfaced. `PasswordService` writes and mails a reset (`mailSender.send` at `service/PasswordService.java:122`) but there is no controller endpoint that mints the reset token — grep for `/reset-password`, `/forgot-password` returned no controller matches. | shioai == ShipX (neither has one) | Users need admin help to reset. |
| 5-D | No user preference for default label/invoice printer bound to the user (ShipX `SPX_tblShipXUser`). shioai has `printer_assignment` keyed by client+doctype (`model/PrinterAssignment`), not by user. | shioai != ShipX (different model) | ShipX users choose their own default printer on the shipping screen; shioai always resolves the client's assignment. |
| 5-E | No login-audit page — `AuthServiceImpl` calls `auditService.log` (optional bean at line 51) but no admin surface displays login/failed-login events distinct from the generic `audit_log` page. | shioai partial | ShipX has none either but the doc treats it as a known gap. |
| 5-F | Public signup requires the invited role list (`Set.of("USER","TENANT")` at `AdminUserInviteController.java:42`) — ADMIN can't be invited, only seeded, and there is no documented ADMIN-bootstrap path in the codebase (grep found none). | shioai partial | First-time-installation friction. |
| 5-G | No 2FA. FE role-change modal notes "should require 2FA-style confirmation" (`AdminUserController.java:111-114`) but nothing is enforced backend-side. | shioai == ShipX | |

---

## 5. Hardcoded findings (§5)

| # | File:line | What's hardcoded | Fix |
|---|---|---|---|
| H5-1 | `service/AdminUserService.java:213` | Role allow-list `Set.of("USER","TENANT","ADMIN")` — the only definition of "what roles exist" in the codebase. | Extract to a `Role` enum or a `roles` seed table read once at startup. Priority: LOW (change is small, but so is the current bug). |
| H5-2 | `controller/AdminUserInviteController.java:42` | `INVITABLE_ROLES = Set.of("USER","TENANT")` duplicates 5-1 with a smaller set. | Fold into 5-1 with a `boolean invitable` column/enum flag. |
| H5-3 | `application.properties:271` | `jwt.secret=${JWT_SECRET:KmJwSootlyGN...}` — default fallback is a committed key. Comment at L267 says "no hardcoded default" but there is one. | Drop the default; fail-fast when `JWT_SECRET` is unset in prod profile. |
| H5-4 | `service/AuthServiceImpl.java:213-215` + `service/UserInviteService.java:79-84` + `service/PasswordService.java:122` | Email subject + body strings concatenated inline; no template mechanism. | Small `email_template` table or Thymeleaf `.html` per template. |
| H5-5 | `service/mail/LoggingMailSender.java:22-24` | Default `MailSender` bean only logs — deploys that forget to swap it silently drop email. | Fail-fast in `prod` profile if only `LoggingMailSender` is registered, OR ship a `SmtpMailSender` in the box behind an `@ConditionalOnProperty`. |
| H5-6 | `service/captcha/CaptchaVerifier.java` (interface only, no impl grep-visible calling it) | The verifier is defined but nothing calls `verify(...)` — captcha is dead code. Grep for `captchaVerifier\.verify\|CaptchaVerifier\b` in signup path returned no invocation. | Wire it into `AuthServiceImpl.registerUser` OR delete the interface until it is. |
| H5-7 | `config/SecurityConfig.java:140` | `"/api/v1/dtc/**"` is `permitAll()` — public "for testing". Comment says as much. | Gate behind `signup.public-enabled`-style flag OR require `hasRole('ADMIN')`. |
| H5-8 | `config/SecurityConfig.java:299-308` | `CORS_ALLOWED_ORIGIN_PATTERNS` defaults include `http://192.168.*:*`, `http://10.*:*`, `http://172.*:*` for backwards compatibility. Comment already flags the prod risk. | Ship an empty default; deploys must opt-in. |

---

## 6. ShipX §6 behaviour

| Surface | Table | Key columns / fields | Ref |
|---|---|---|---|
| Carriers | `SPX_tblShipCarrier` | CarrierName (must be exactly FedEx/UPS/USPS), DefaultAccountNo, ApiKey1/2/3, Status, Mode (Test/Live) | combined.md L272-L288 |
| Services | `SPX_tblCarrierService` | ServiceName (wire code), ServiceCode (NDS ship-via), FSMServiceCode | combined.md L294-L308 |
| Packages | `SPX_tblCarrierPacking` | Name, FSM code, MaxWeight, height, width, price (height↔length swap bug) | combined.md L299-L301 |
| Service ↔ package | `SPX_tblservicePack` | many-to-many; Update deletes all + reinserts | combined.md L586-L588 |
| Ship-via hints | `SPX_tblShipVia` | per-carrier US / non-US default | combined.md L317-L326 |
| Clients | `SPX_tblClient` | ClientCode (=NDS TENANT_ID), ship-from block, ComInvCopy, ShipmentType (API/FSM), MeterNo, STDCode, FedExMGR (DDP/DAP), IsDTC | combined.md L331-L345 |
| Client ↔ carrier | `SPX_tblClientCarrier` | (client, carrier, account_no, default); saving Client REPLACES entire grid | combined.md L343-L358 |
| Importer | `SPX_tblimporter` + `SPX_tblimporterAcc` | broker + IOR per (client, country); Super Admin only | combined.md L361-L367 |
| Printers | `SPX_tblPrinter` | AliasName, PntType (LABEL / PACK_INV), PntMode (Network/Station), PntIP, PntIdentifier (share name), CupsPrinterName | combined.md L370-L385 |
| Users | `SPX_tblloginCredential` + `SPX_tblShipXUser` (default LABEL + PACK_INV printer per user) | combined.md L38, L389 |
| Employees | `SPX_tblemployee` (picker code ↔ user) | URL-only screen; PickerId prints as EmpID | combined.md L397 |
| Ship stations | `SPX_tblshipstation` + `SPX_tblclientshipstation` | URL-only, unused when shipping | combined.md L399 |
| Funds (USPS postage) | `SPX_tblFund` + `SPX_tblFundLogs` | per-client; **owner-confirmed unused — amounts are hardcoded in three places** | combined.md L405-L406 |

Known bugs in §6: box height↔length column swap; carrier grid save is
non-atomic (delete then re-insert per row); no NDS-check on ClientCode;
STDCode inner-join can produce blank/wrong Edit page; USPS carrier id 31
is hardcoded in code and must not be renumbered; carrier keys shown in
plain text on list screens.

---

## 7. shioai §6 counterpart

| Surface | shioai entity / table | file:line |
|---|---|---|
| Carriers | **NOT a DB table.** 5 `@Component` connectors: FedEx, UPS, Stamps.com, USPS_DIRECT, DHL | `service/carriers/*Connector.java` (see grep result) |
| Carrier accounts | `carrier_account_ref` — accountNumber, carrierCode, clientId + encrypted clientSecret (AES-GCM via `EncryptedStringConverter`), environment SANDBOX/PRODUCTION, per-customer `customerNo`, `isDefault`, `clientDefault`, verified/lastVerifiedAt, per-account label stock, currency, pickup type, third-party billing, USPS SERA refresh, USPS Direct CRID/MID | `model/CarrierAccountRef.java` (329 LoC) |
| Services (catalog) | `shipping_service` — per-carrier service catalog; `shipping_service_mapping` maps client → carrier's own service code | `model/ShippingService.java`; `/settings/shipping-catalog` FE |
| Packages | `carrier_package_catalog` + service linkage | `model/CarrierPackageCatalog.java` |
| Clients | `clients` — clientCode, name, embedded shipFrom + returnAddress, defaultCurrency, defaultWeightUnit, defaultDimUnit, timezone, defaultOriginCountry, defaultPaperSize | `model/Client.java:40-154` |
| Client ↔ carrier | via `carrier_account_ref.customerNo` FK — no separate join table | `model/CarrierAccountRef.java:70-76` |
| Per-client rate policy | `client_shipping_policy` — CHEAPEST/FASTEST/FIXED + dispatch cutoff | `model/ClientShippingPolicy.java` |
| Per-client allowed services | `client_allowed_service` | `controller/ClientAllowedServiceController.java` |
| Per-client destination rules | `client_destination_rule` | `model/ClientDestinationRule.java` |
| Per-client customs profile | `client_customs_profile` | `controller/ClientCustomsProfileController.java` |
| Per-client billing markup | `client_billing_markup` | `controller/ClientBillingMarkupController.java` |
| Per-client code map | `client_code_map` | `controller/ClientCodeMapController.java` |
| Warehouses / Ship-from | `warehouse` + `client_warehouse` (M2M with per-client default) | `model/ClientWarehouse.java`, `controller/ClientWarehouseController.java` |
| Importer / Broker | ClientEditor tab wraps `importer` records | FE `ClientEditorSteps/ImporterBrokerStep.tsx` |
| Printers | `printer` — name, host, port, queuePath, connection (RAW_9100/IPP), format (ZPL/PDF), paper (LABEL_4X6/A4/LETTER); no PntMode enum lock — connection field takes place | `model/Printer.java:21-72` |
| Printer tags | `printer_tag` M2M — replaces "printer types" enum | (per memory PR #700) |
| Printer assignments | `printer_assignment` (client, docType) → printer; NULL client = default | `controller/PrinterController.java:122-144` |
| Invoice copy count | `invoice_copies` (client, carrier, copies 1–20) — the ShipX `ComInvCopy` column, generalised | `model/InvoiceCopies.java` |
| Users ↔ printer | not a per-user default; assignments are (client, docType) → printer | `PrinterAssignment` |
| Employees | none | — |
| Ship stations | none | — |
| Fund history | none (USPS postage handled by USPS_DIRECT provider via `carrier_account_ref.usps_direct_*`) | `model/CarrierAccountRef.java:283-311` |
| External systems | `external_system_connection` + `external_system_secret` + `external_system_client_login_override` + PROD/DEV env split (V91) | `model/ExternalSystemConnection.java`, memory `reference_external_system_env_split` |
| Tenant-wide KV | `tenant_settings` (per-tenant preference store, V77 framework) | `model/TenantSetting.java` |

---

## 8. Gaps (§6)

| # | Gap | Direction | Impact |
|---|---|---|---|
| 6-A | **Carriers are not DB rows.** ShipX has one `SPX_tblShipCarrier` row per carrier so an admin can add / disable / set live-vs-test / edit API keys. shioai has 5 hardcoded `@Component` connectors — enabling one requires deploying code. `carrier_account_ref` stores per-account credentials but not the "this carrier is enabled for this org" toggle. | shioai < ShipX | Cannot onboard a new carrier without a code deploy. Cannot disable a carrier org-wide without ripping it out. |
| 6-B | **No `Mode` (Test/Live) flag on the carrier itself.** shioai has per-account `environment='SANDBOX'\|'PRODUCTION'`, which is finer but doesn't answer "is this carrier globally live in this deploy". ShipX's `Mode` also gates NDS writeback (combined.md L282); shioai has no equivalent kill-switch. | shioai < ShipX | An operator flipping one account to PROD can't be batched with "yes, this carrier is production-blessed org-wide". |
| 6-C | **No per-client `STD ship-via` override.** ShipX `SPX_tblClient.STDCode` replaces the ship method "STD" on orders. shioai's `client_shipping_policy` has CHEAPEST/FASTEST/FIXED (`ClientShippingPolicy.java:37-52`), which is a different model. The "here's my client's standard method — use it when the order says STD" semantic isn't preserved. | shioai != ShipX | Any legacy order with `SHIPVIA_CD='STD'` cannot resolve without a code map. |
| 6-D | **No `ShipmentType = FSM` (FedEx Ship Manager file-drop) path.** shioai always goes API. If any client is still on FSM, this is a hard block. Grep for FSM: 0 hits in `backend/`. | shioai < ShipX | Non-issue if all clients are API-only; blocker otherwise. |
| 6-E | **No `IsTBAc / TBClientCode` "ship on Thoroughbred's account" concept.** ShipX allows Quick Ship to substitute client `THB` for another client's container. shioai has per-client carrier accounts + `clientDefault` but no "ship this order under a different client's carrier account" flow. | shioai < ShipX | Feature gap for Thoroughbred's own operator workflow. |
| 6-F | **Terms of Sale (DDP/DAP) is not a per-client field on `Client`.** ShipX `FedExMGR` toggle lives on the client row. shioai puts it per-`carrier_account_ref.clearanceOption`. Fine as long as the FE respects it. Confirmed FE has per-account UI (`CarrierConnections.tsx`); the client-level toggle isn't user-facing. | shioai != ShipX | Cleaner shioa model; document the migration for anyone importing from ShipX. |
| 6-G | **Package height↔length column swap** was a ShipX bug; verify shioai's `CarrierPackageCatalog` is not seeded from ShipX DB with the swap intact. Not audited today. | data-migration risk | If anyone loads packages from `SPX_tblCarrierPacking` via the raw `Height` column, they get 0s. |
| 6-H | **No importer-account grid** (`SPX_tblimporterAcc`). shioai has an importer/broker step on ClientEditor but I did not see a per-carrier-account grid keyed by importer id. Needs deeper audit. | shioai partial | International shipments may be missing IOR-per-carrier-account. |
| 6-I | **No `SPX_tblShipVia` default-hint table.** ShipX uses it to emit "Please use R02 ShipVia" hints in error messages. shioai's error messages are inline strings. | shioai < ShipX | Minor UX. |
| 6-J | **No user-scoped default printer.** ShipX users pick their own default LABEL + PACK_INV printer at Manage User. shioai routes printers by (client, docType). Not obviously worse, but users can't have preferences. | shioai != ShipX | UX difference; acceptable. |
| 6-K | **No `Manage Fund` — no per-client USPS postage top-up management.** ShipX doc admits it's broken (values hardcoded in three places, combined.md L405-L406). shioai USPS_DIRECT integration is per-account (`carrier_account_ref.uspsDirect*`), doesn't cover a top-up ledger. | both broken | ShipX's is already known broken; shioai simply skips it. |
| 6-L | **External systems: shioai richer.** ShipX has NDS Oracle logins buried in code; shioai has full `/settings/external-systems` admin page with PROD/DEV env split. | shioai > ShipX | |
| 6-M | **Warehouses: shioai richer.** ShipX has no warehouse concept — origin = `SPX_tblClient` ship-from block. shioai has M2M + default + G3 nearest-warehouse selector. | shioai > ShipX | |
| 6-N | **Printers: shioai richer** (auto-detect + probe + tags + queue depth + test history + IPP Get-Jobs — memory `project_printer_autodetect` + `project_printer_page_redesign`, 25 PRs). | shioai > ShipX | |

---

## 9. Hardcoded findings (§6)

| # | File:line | What's hardcoded | Fix |
|---|---|---|---|
| H6-1 | `application.properties:564-571` (`carrier.shipper.*`) | `Multiship Warehouse` / `Bengaluru` / `KA` / `560001` / `IN` platform-default shipper block. Read as fallback at `CarrierServiceImpl.java:3660-3668` and `ZplLabelService.java:70-76` when neither the warehouse nor the client ship-from populate a field. **Same pattern the memory H-01 finding already flagged for manual-shipment.** | Delete the properties + fallback; require the resolver to fail loud when no warehouse is picked, per Tier 1-A pattern. If a platform default is truly desired, source it from `tenant_settings` (V77) so ops own it, not the deployer. |
| H6-2 | `service/CarrierServiceImpl.java:2401-2404` | `case "FEDEX" -> "FedEx"; case "UPS" -> "UPS"; case "USPS", "USPS_DIRECT", "STAMPS_COM" -> "USPS"; case "DHL" -> "DHL"` — carrier display-name switch. | Move to a `carrier` enum (or minimal `carrier(carrier_code, display_name)` seed table if 6-A is done). |
| H6-3 | `service/CarrierServiceImpl.java:3303-3305` | `case "UPS" -> "P80"; case "FEDEX" -> "F77"; case "USPS" -> "L01";` — legacy default ship-via code per carrier. | Fold into 6-I above (`shipping_service_default_hint` table). |
| H6-4 | `service/CarrierServiceImpl.java:3115-3125` | `"USPS".equalsIgnoreCase(canonicalCarrierCode)` special-case dispatch between Stamps.com and USPS_DIRECT connectors. | Keep the switch but eliminate 6-A first — after that, the choice becomes a DB-driven per-account `usps_provider` column already read from `system_setting.USPS_PROVIDER`. |
| H6-5 | `service/carriers/*Connector.java` (5 files, each `@Component`) | Each carrier is a Java class registered at boot. Adding a carrier = deploy. | Convert to a `carriers` table (carrier_code, display_name, enabled, mode) + connector registry that looks up by carrier_code at runtime. Only the connector logic stays code; the enable/disable/mode is DB. |
| H6-6 | `model/User.java:174-179` | `switch (preferredCarrier.toUpperCase()) { case "P80","UPS" -> "UPS"; case "F77","FEDEX" -> "FedEx"; case "L01","USPS" -> "USPS"; }` inside the `User` entity. | Delete the helper — `User.preferredCarrier` is legacy from before per-account resolution. Grep for `getCarrierDisplayName` callers before removal. |
| H6-7 | `AuthServiceImpl.java:212-215` (verify-email link) + `UserInviteService.java:78-84` (invite link) | Email body strings hardcoded inline. | See H5-4. |
| H6-8 | `PrinterController.java:100-119` | Probe port hardcoded `631` (IPP default) when caller supplies none. Reasonable default; no fix needed. | — |
| H6-9 | `application.properties:271` fallback `jwt.secret=KmJwSootlyGN...` | See H5-3. | — |

---

## 10. Prioritized fix-plan

Ordered highest first. LoC is a rough estimate; migrations count Flyway
`V*.sql` files needed.

| Rank | Item | Est. LoC | Migrations | Risk | Rationale |
|---|---|---|---|---|---|
| P0 | **H6-1** — remove `carrier.shipper.*` Bengaluru platform default. | ~40 (delete props + delete `shipper.*` fallback branches in `CarrierServiceImpl` + `ZplLabelService`) | 0 | LOW (fail-loud is safer than a wrong-country label). | Same pattern the memory H-01 finding fixed elsewhere; still leaks into the wire on the label-generation path today. |
| P0 | **H5-3** — drop committed JWT-secret fallback. | ~5 (change `${JWT_SECRET:...}` → `${JWT_SECRET}`, fail-fast on missing) | 0 | LOW — every env already sets it. | Reversal of the very fix comment at `application.properties:267`. |
| P0 | **H5-7** — gate `/api/v1/dtc/**` behind a role or flag. | ~5 (add `.hasAnyRole('ADMIN')`) | 0 | LOW (endpoint currently anonymous). | Comment already says "public for testing" — remove before prod. |
| P1 | **6-A + H6-5** — carriers as DB rows. Introduce `carriers(carrier_code, display_name, enabled, mode, notes)` + registry looks up connector bean by `carrier_code`. | ~150 | 1 (`V102`) | MED — every carrier-code touchpoint (grep `"FEDEX"\|"UPS"\|"USPS"\|"DHL"`) must respect enable/mode. | Unlocks admin-driven onboarding and per-org enable. |
| P1 | **6-D** — decide whether FSM is dead. If yes, document; if no, ship it. | doc-only or large | 0 or many | HIGH if needed | Blocker for any FSM-tenant migration. |
| P2 | **5-A + H5-1 / H5-2** — collapse role vocab into an enum or `roles` seed table. | ~60 | 0 (or 1 seed) | LOW | Cheap, kills two dupes. |
| P2 | **H6-2 / H6-3** — carrier-code switch statements. | ~30 | 0 (or fold into P1 seed) | LOW | Delete-only after P1. |
| P2 | **H5-5** — fail-fast when only `LoggingMailSender` in prod profile. | ~15 | 0 | LOW | Silent email drop today. |
| P3 | **H5-4 + H6-7** — email-template mechanism. | ~120 | 1 (`email_template` table) | LOW | Enables i18n + per-tenant branding. |
| P3 | **H5-6** — wire (or delete) `CaptchaVerifier`. | ~30 or delete | 0 | LOW | Dead code today. |
| P3 | **6-I** — `shipping_service_default_hint` table (replaces ShipX `SPX_tblShipVia`). | ~80 | 1 | LOW | UX only. |
| P3 | **5-D / 6-J** — per-user default printer preference. | ~60 | 1 | LOW | UX parity with ShipX Manage User. |
| P4 | **6-C** — decide STD-code override strategy (per-client `stdShipVia` column, or `client_code_map` extension). | ~30 | 1 | LOW | Only if any ShipX-migrated order enters shioai with `SHIPVIA_CD='STD'`. |
| P4 | **6-E** — IsTBAc "ship on Thoroughbred's account" flow. | ~200 | 1 | MED | Only if the operator team still relies on it. |
| P4 | **5-C** — self-serve password reset endpoint (backend logic already exists in `PasswordService.java:122`). | ~40 (controller + FE) | 0 | LOW | |
| P5 | **H6-6** — `User.getCarrierDisplayName` cleanup after 6-A. | ~10 delete | 0 | LOW | |
| P5 | **6-B** — per-carrier org-wide "live" toggle (subsumed by 6-A's `mode` column). | 0 extra | 0 | LOW | Delivered with P1. |
| P5 | **5-B** — wire real captcha if `signup.public-enabled` ever flips. | ~80 | 0 | LOW | Only needed pre-prod-open. |

---

## 11. Open questions

1. **Is public signup ever intended to be enabled?** `signup.public-enabled=false` is the default. If never, then H5-6 (captcha) is dead-code we can delete rather than wire.
2. **Is any tenant still on FedEx Ship Manager (FSM file drop, `C:\batch`)?** If no, 6-D and the FSM columns become non-goals; if yes, this is a P0 blocker for that tenant.
3. **Should `carrier_account_ref` unique index change on the 6-A migration?** Today `(account_number, carrier_code, customer_no)` per V82 partial indexes. Adding a `carriers` row with FK might collide.
4. **Does anyone rely on the `User.preferred_carrier` / `carrier_client_id` / `carrier_client_secret` set of columns on `users`?** Memory suggests they are pre-Sprint-45 legacy but they are still encrypted and still on the entity (`model/User.java:104-139`). Grep for readers to confirm dead.
5. **Are the ShipX importer accounts (per-country, per-carrier IOR) fully covered by shioai's importer-broker step, or is `SPX_tblimporterAcc`-equivalent missing?** 6-H needs a deeper audit.
6. **Do we want a `Manage Fund`-equivalent?** ShipX admits its own is broken. shioai's USPS_DIRECT provider could source a per-account balance from USPS APIs directly, which would make a ledger unnecessary. Confirm before scoping.
7. **How is a NEW org's first ADMIN account bootstrapped?** No `/api/v1/auth/signup` role=ADMIN path; no seed script found. Assume manual DB insert. Document explicitly.

---

*End of report.*
