# multiship — QA retest report, 2026-10-18-run3

Third run of the day ("retest all"): every bug from the afternoon regression (R2-BUG-001 … R2-BUG-021) was re-executed on the build running at 16:00 local, the six fixes confirmed in run 2 were regression-checked, run-1 BUG-005 was re-checked, and the operator-role tests that were blocked in run 2 were completed with an invited USER account. Same local test stack (`http://localhost:5173`, React UI + Spring Boot API, PostgreSQL, Oracle NDS untouched), FedEx / UPS / USPS sandboxes. All QA data is under client **MKL246** with the prefix `QA-1018c-`; QA-1017-CL01 and THB000 were read-only.

## 1. Verdict

**Still not ready to release — the build is unchanged for 20 of the 21 run-2 bugs, and the operator role cannot see its own orders.** Of the 21 run-2 bugs, 1 is fixed (R2-BUG-018, reversed date range now flagged), 20 are still open (2 of them with cosmetic changes around the same behaviour), 0 could not be retested. All six run-1 fixes still hold. Run-1 BUG-005 (carrierCode/account mismatch) is still open. 2 new bugs were found in the role tests: 1 High, 1 Medium. 32 retest/role cases ran: 9 passed, 23 failed.

What blocks the release (unchanged from run 2, plus one new):

1. **R3-BUG-001 (High, new)** — a USER (day-to-day operator) gets 403 on every order-list call; the Shipment & Label grid is empty for the role that works it.
1. **R2-BUG-001 (High)** — External API void marks the shipment VOIDED without cancelling the label at the carrier — Void response now returns carrierVoided:false with a note that carrier-side cancellation 'is not wired in this environment' - disclosed, not fixed.
1. **R2-BUG-002 (High)** — Schedule a pickup fails for every carrier: FedEx request lacks the package location, UPS pickup API answers 401, USPS/SERA request omits the contact name
1. **R2-BUG-003 (High)** — Close whole day fails with a server 500 (no carrier call) and the open-labels query still fails for today
1. **R2-BUG-004 (High)** — Per-box declared values are ignored on multi-box shipments (run-1 BUG-007 still present)
1. **R2-BUG-005 (High)** — Duplicate label purchases are accepted on the internal purchase endpoint and on the External API (run-1 BUG-006, now confirmed on the integration path)

Still true from run 2 and not re-tested on purpose: NDS/WMS/D2C (nds-default inactive, left alone), close-out of earlier days (not retried), Add Funds submit (validation re-checked only; no new top-up).

## 2. Build, scope and assumptions

| Item | Value |
|---|---|
| app | multiship (multiship-react UI, Spring Boot backend, PostgreSQL app DB, Oracle NDS) |
| base_url | http://localhost:5173 |
| env | Local dev test stack; carriers on sandbox hosts |
| run_date | 2026-10-18 |
| run | 3 (retest of every run-2 bug plus the run-1 fixes, and the operator role tests) |
| tester | Claude (Cowork) driving Claude in Chrome |
| session_role | ADMIN session; operator role tested with an invited USER account qa1018cop (MKL246) because the existing 'smoke' user's password is not known to QA; admin restored with the password supplied in chat (not recorded) |
| write_client | MKL246 only |
| qa_prefix | QA-1018c- |
| report | Markdown + Excel |
| read_only_clients | QA-1017-CL01, THB000 |
| build under test | the stack running at 15:45–16:25 local on 2026-10-18 (UI at :5173, API behind the Vite proxy); no build id is exposed by the app |
| retest rule | a bug is 'Fixed' only when the run-2 steps pass twice; 'Still open' when they fail twice; partial changes are noted but do not change the verdict |

Safety rules followed: no card or bank numbers typed; nothing sent to network printers (labels checked through View Label); no customer email sent (the invite and reset flows that would email were used only with example.com addresses and the on-screen link); `nds-default` never touched; close-out of other days not retried; the admin password supplied in chat was used once to sign back in and is recorded nowhere; the External API key and the operator password lived only in the browser session and were revoked / cleared. No files were downloaded (exports and templates were read in-page).

## 3. Retest matrix — run-2 bugs on the current build

| Run-2 bug | Sev | Area | Title | Run-3 verdict | Retest case | What happened |
|---|---|---|---|---|---|---|
| R2-BUG-001 | High | External API | External API void marks the shipment VOIDED without cancelling the label at the carrier | **Still open (changed)** | R3-RETEST-R2-BUG-001 | STILL OPEN (2/2 carriers). Key QA-1018c-key (id 3, MKL246, TEST, 5 scopes) issued via the UI; POST /external/shipments created 934335 (UPS P80, 1ZXXXXXXXXXXXXXXXX, 11.44) and 934336 (FedEx F77, 794893822513, 22.26). POST /external/shipments/{id}/void answered 200 'Shipment voided.' and set order+label VOIDED for both, but the Carrier API Log holds only the ship call (ids 2717 UPS, 2718 FedEx) - no cancel/void call. New on this build (not recorded in run 2): the void response now carries carrierVoided:false and the note 'Shipment voided on the platform. A carrier-side label cancellation is not wired in this environment; ensure the label is not used.' - the behaviour is unchanged, only disclosed. Key revoked afterwards; revoked token -> 401. |
| R2-BUG-002 | High | Close out / pickups | Schedule a pickup fails for every carrier: FedEx request lacks the package location, UPS pickup API answers 401, USPS/SERA request omits the contact name | **Still open** | R3-RETEST-R2-BUG-002 | STILL OPEN (3/3 carriers). POST /pickups: FedEx -> carrier error PACKAGELOCATION.INVALID (request body still has no packageLocation); UPS -> 401 code 250002 from the UPS pickup API; USPS/SERA -> error 4522242 contact name missing (request has no name field). Same three failures as run 2. |
| R2-BUG-003 | High | Close out / pickups | Close whole day fails with a server 500 (no carrier call) and the open-labels query still fails for today | **Still open** | R3-RETEST-R2-BUG-003 | STILL OPEN. GET /manifests/open?carrierCode=FEDEX&closeDate=2026-10-18 -> 500; same for UPS -> 500; POST /manifests/close-day -> 500 with no carrier call in the Carrier API Log. Unchanged from run 2 (close-out of other days not retried, per the earlier decision). |
| R2-BUG-004 | High | Data integrity | Per-box declared values are ignored on multi-box shipments (run-1 BUG-007 still present) | **Still open** | R3-RETEST-R2-BUG-004 | STILL OPEN (2/2). Two-box purchases with packages declared 50 / 150: 934329 (UPS) and 934330 (FedEx) - the carrier requests in the Carrier API Log again carry 100 / 100 (total split evenly) instead of 50 / 150. |
| R2-BUG-005 | High | Data integrity | Duplicate label purchases are accepted on the internal purchase endpoint and on the External API (run-1 BUG-006, now confirmed on the integration path) | **Still open** | R3-RETEST-R2-BUG-005 | STILL OPEN (2/2 paths). POST /orders/manual-label twice with reference QA-1018c-D001 -> 934331 and 934332 both 200 with labels; POST /external/shipments twice with reference QA-1018c-EXT1 -> 934335 and 934337 both 201. No idempotency key, no refusal, no warning on either path. |
| R2-BUG-006 | Medium | Bulk Mailer | Bulk import template and Errors (CSV) use carrier service codes the importer rejects (run-1 BUG-014 still present) | **Still open (changed)** | R3-RETEST-R2-BUG-006 | STILL OPEN (2/2). The template downloaded from /orders/import/template-client.csv still carries SHIPVIA_CD FEDEX_GROUND; uploaded for MKL246 (upload 24) it fails with "Service 'FEDEX_GROUND' is not mapped for MKL246 - it is a FEDEX service code, but a file import must use a Shipping Service Mapping rule". Errors (CSV) (QA-1018c-template-errors.csv) is again written in the internal layout (orderRef, clientCode, ... serviceType, errors) and re-uploading it (upload 25) fails on the same rule. Mitigation seen on this build: the validate step shows a 'Ship via codes for MKL246' panel listing the valid codes (F77 FedEx Ground, P80 UPS Ground), and a 'Save all, including errors' button - the template itself is still wrong. |
| R2-BUG-007 | Medium | Data integrity | Carrier API Log rows from bulk label runs carry no order number (run-1 BUG-015 still present) | **Still open** | R3-RETEST-R2-BUG-007 | STILL OPEN (2/2). Import #13 (QA-1018c-bulk2.csv, label batch 34): Generate labels created 934339 (FedEx 794893822638) and 934338 (UPS) in about 30 s, both GENERATED. Carrier API Log rows 2720 (UPS) and 2721 (FedEx) have orderNo null; GET /admin/carrier-api-log?orderNo=934338 and ?orderNo=934339 return nothing. |
| R2-BUG-008 | Medium | Settings | A custom field marked 'Required on new shipments' is not enforced | **Still open** | R3-RETEST-R2-BUG-008 | STILL OPEN (2/2). Definition qa_1018c_dept 'QA-1018c Department' (TEXT, Required, Active, position 110) saved for MKL246 and shown on the New Shipment form as 'QA-1018C DEPARTMENT *'. With the field left empty, Validate reported 'All checks passed - ready to generate the label' and Generate bought FedEx Ground label 794893824160 (order 934340, customFields {}); the UI purchase request carried no customFields at all. POST /shipments/validate without custom fields also answers overall PASS. Definition deleted afterwards ('Definition deleted'). |
| R2-BUG-009 | Medium | Validation | Server accepts unusable phone numbers, bad emails and over-long fields that the form rejects (run-1 BUG-003/BUG-004 still present, also on the External API) | **Still open** | R3-RETEST-R2-BUG-009 | STILL OPEN (2/2). POST /shipments/validate with recipient.phone 'abc', email 'not-an-email', 120-char name, 300-char address and 300-char reference still passes (overall PASS, no local errors); the form rejects the same values client-side only. |
| R2-BUG-010 | Medium | Settings | Deleting a warehouse that is attached to clients and used by orders needs only a single confirm | **Still open** | R3-RETEST-R2-BUG-010 | STILL OPEN (2/2 dialog opens, cancelled both times). Settings > Warehouses > 3PL-EAST (Platform, attached to 3 clients, origin of the MKL246 orders) > Row actions > Delete opens 'Delete warehouse 3PL-EAST? It is currently attached to 3 clients - they will be unlinked.' with Cancel / Delete - one click away, no type-to-confirm, no mention of the orders that reference it, no block while attached. Cancelled; the warehouse is intact (2 of 2 warehouses listed, Active). |
| R2-BUG-011 | Medium | Validation | No sanctions/embargo check of its own: shipments to CU, KP, IR are only stopped by the carriers (USPS validate passes for Iran) | **Still open** | R3-RETEST-R2-BUG-011 | STILL OPEN (2/2). USPS validate to Iran (IR) answers overall WARN with no sanctions/embargo error; the app relies on the carrier to refuse. |
| R2-BUG-012 | Medium | Orders grid | Tab badges and Failed queue miss legacy FAILED-label orders; All badge 8 short of the list (run-1 BUG-013 still present) | **Still open** | R3-RETEST-R2-BUG-012 | STILL OPEN (2/2). /orders/stats buckets sum to 34,004 while the unfiltered list total is 34,014 (10 orders in no bucket, was 8 in run 2 - the gap grows with new voided/failed rows); legacy order 900001 (label FAILED) still missing from the Error filter. |
| R2-BUG-013 | Medium | Orders grid | Export CSV writes only the current page of a filtered result set | **Still open** | R3-RETEST-R2-BUG-013 | STILL OPEN (2/2). Search QA-1018c matches 11 orders (API totalElements 11). Export at 10/page -> orders-2026-10-18T10-27-47-669Z.csv with 10 data rows; after switching to 100/page -> 11 rows. Export still writes the current page only, with no warning or 'current page' label. |
| R2-BUG-014 | Low | Label output | Label page preview shows the platform default ship-from (Bengaluru) instead of the order's sender (run-1 BUG-001 still present) | **Still open** | R3-RETEST-R2-BUG-014 | STILL OPEN (2/2). View Label for 934333 (UPS) and 934339 (FedEx) still renders SHIP FROM 'Multiship Warehouse, 1 Warehouse Way, Bengaluru, KA 560001' while the order details dialog and the carrier requests (Carrier API Log 2708 UPS Shipper 'MKL', 2721 FedEx shipper 'Central Warehouse', both 8191 Logistics Dr, Zeeland MI 49464) carry the real sender. Preview only; the carrier label is correct. |
| R2-BUG-015 | Low | Void / reissue | USPS void fails with a raw non-unique-result error when the sandbox tracking number is shared (run-1 BUG-010 still present); UPS void rejection returns HTTP 200 | **Still open** | R3-RETEST-R2-BUG-015 | STILL OPEN (2/2). UPS void of a sandbox label -> HTTP 200 with message 'rejected ... No shipment found within the allowed void period' (success code for a failure); USPS void -> 502 'Query did not return a unique result' because sandbox labels share tracking 9400111969000940000011. |
| R2-BUG-016 | Low | Void / reissue | Reissuing a voided label warns that the reference was 'already shipped' on the same order | **Still open** | R3-RETEST-R2-BUG-016 | STILL OPEN (2/2). Void of 934339 (FedEx 794893822638) from the grid: dialog 'Void order #934339? This cancels the label at the carrier - it cannot be undone', toast 'Order 934339: 1 batch(es) voided', Carrier API Log 2722 apis-sandbox.fedex.com/ship/v1/shipments/cancel (internal void still cancels at the carrier). Reissue label opened the form pre-filled ('Reissuing label for order #934339'); Validate shows 'Reference QA-1018c-BM-1 already shipped on order #934339 (2026-10-18). Make sure this isn't a second shipment of the same order.' - the warning names the order being reissued, and calls a voided label 'shipped'. Regenerate worked: new tracking 794893823895, page shows 'Previous label: 794893822638 (voided 4:01:48 PM, reissued 4:02:52 PM)'. Side observations: the reissue form of a bulk-imported order has empty, required Length/Width/Height (bulk rows carry no dimensions) so Validate is blocked until they are typed; the Ship from dropdown again reads 'Manual sender' for a warehouse-origin order; the preview again shows the Bengaluru ship-from (R2-BUG-014). |
| R2-BUG-017 | Low | Returns | 'Return of #' does not load the original recipient into the Return from block | **Still open** | R3-RETEST-R2-BUG-017 | STILL OPEN (2/2). New return, client MKL246, 'Return of #' 934333 (scripted) and 934338 (typed, Tab out): the 'Return from - customer' block stays empty (Full name, Address, City, Phone, Email all blank) and no request for the order is made (no GET /orders/9343xx in the resource timeline). The block must be typed by hand. New on this build: a banner 'Customer email required for return labels ... UPS rejects the label with 9120145' appears once a client is chosen. |
| R2-BUG-018 | Low | Orders grid | Reversed date range shows an empty grid without a message (run-1 BUG-020 still present) | **Fixed** | R3-RETEST-R2-BUG-018 | FIXED on this build (2/2). Setting From 18-10-2026 / To 01-10-2026 (and again 12-10 / 05-10 after Clear) shows an inline badge 'From date is after To date' beside the date pickers and 'Nothing matches the current filters.' in the grid. Residual: the API itself still answers GET /orders?createdFrom=2026-10-18&createdTo=2026-10-01 with 200 and an empty page rather than a 400 (minor, API hygiene). |
| R2-BUG-019 | Low | Security | Issue API key dialog defaults to All clients, LIVE and all 7 scopes | **Still open** | R3-RETEST-R2-BUG-019 | STILL OPEN (2/2 dialog opens). Settings > API keys > Issue API key still opens with All clients, LIVE and 7/7 scopes preselected (screenshot). One client / TEST / 5 scopes had to be chosen by hand for the QA key. |
| R2-BUG-020 | Medium | Options | Shipment Check quote omits signature and declared-value surcharges (run-1 BUG-008 still present) | **Still open** | R3-RETEST-R2-BUG-020 | STILL OPEN (2/2). UPS Ground (service 03) with ADULT signature on order 934333: the Validate quote is 11.33 (plain Ground rate) while the UPS ship response (Carrier API Log 2708, PackageServiceOptions.DeliveryConfirmation DCISType 3, DeclaredValue 80) bills TotalCharges 20.79 - the signature and declared-value surcharges are missing from the Shipment Check quote. |
| R2-BUG-021 | Low | Options | Insured value is never sent to the carrier (run-1 BUG-009 still present) | **Still open** | R3-RETEST-R2-BUG-021 | STILL OPEN (2/2). UPS Ground purchase with options.insuredValue 500 (order 934334): the UPS ship request in the Carrier API Log carries no InsuredValue/insurance element (only the declared-value element), so no insurance is bought; label cost equals the uninsured rate. |

Totals: Fixed 1 · Still open 20 (of which changed around the edges 2) · Cannot retest 0.

### 3b. Run-1 fixes — regression check

| Run-1 bug | Title | Fix confirmed in run 2 | Run-3 check | Case |
|---|---|---|---|---|
| BUG-002 | Reason of export defaults to 'Sample' on every international shipment because every MKL246/THB000 carrier account carries shippingPurpose=SAMPLE; the form copies the account default when the field is empty | yes | **Fix holds** | R3-FIX-BUG-002 |
| BUG-011 | Return form pre-fills the customer ('Return from') block with the merchant's own address and phone, and 'Return of #' does not load the original recipient | yes | **Fix holds** | R3-FIX-BUG-011 |
| BUG-012 | 'Shipment vs return' filter is silently broken: it does nothing on its own, then stays applied invisibly and survives 'Clear all' | yes | **Fix holds** | R3-FIX-BUG-012 |
| BUG-016 | 'Generate labels' on an import purchases labels for every row with a single click and no confirmation | yes | **Fix holds** | R3-FIX-BUG-016 |
| BUG-018 | Add funds: the Add button stays enabled on an account that is not authorized with Stamps.com, and any amount is allowed when the balance is unknown | yes | **Fix holds** | R3-FIX-BUG-018 |
| BUG-019 | Add funds rejects the exact maximum amount it advertises (9841.47) with a wrong 'dollars and cents' message | yes | **Fix holds** | R3-FIX-BUG-018 |
| BUG-005 | Label purchase trusts the request's carrierCode over the selected account, so a mismatch calls the wrong carrier and saves an ERROR order | no (still open in run 2) | **Still open (changed)** | R3-RETEST-BUG-005 |

Run-1 bugs that live on under a run-2 id: BUG-001 → R2-BUG-014, BUG-003 → R2-BUG-009, BUG-004 → R2-BUG-009, BUG-006 → R2-BUG-005, BUG-007 → R2-BUG-004, BUG-008 → R2-BUG-020, BUG-009 → R2-BUG-021, BUG-010 → R2-BUG-015, BUG-013 → R2-BUG-012, BUG-014 → R2-BUG-006, BUG-015 → R2-BUG-007, BUG-017 → R2-BUG-003, BUG-020 → R2-BUG-018 — all still open except BUG-020 (→ R2-BUG-018, fixed); see the matrix.

## 4. New bugs found in run 3 (reproduced at least twice)

### R3-BUG-001 — High — Operator (USER) role cannot list orders: every GET /api/v1/orders list call answers 403, so the Shipment + Label grid is empty for the role that works it

*Area:* Security / roles · *Cases:* R3-ROLE-002 · *Reproduced:* 2/2 page loads + Refresh, 6/6 API variants (user qa1018cop, deactivated after the test) · *Status:* Open

Steps:

1. Invite a USER for MKL246 (Settings > Users > Invite user), accept the invite, sign in.
2. Open Shipment + Label (/orders) or call GET /api/v1/orders?page=0&size=10 (with or without customer=MKL246, search=, orderNo=).

**Expected:** The operator sees the orders of the own client.

**Actual:** 403 FORBIDDEN 'You do not have permission to perform this action.' on every list variant; the grid shows 'No orders yet.' and an error toast while the tab badges (queue-stats) show 549 / 144 / 405. Single-order GET, label, validate, purchase and void work for the same user, so the role is only missing the list permission.

**Impact:** The day-to-day operator role is unusable for its main screen; operators cannot find, reprint, void or re-issue labels without an order number.

Evidence: `R3-ROLE-002_operator_orders_grid_403_no_orders.jpg`

Environment: UI + API, USER role, MKL246

### R3-BUG-002 — Medium — As an operator, a validate request for another client (or an unknown client) fails with a 500 instead of a clean 403/400

*Area:* Security / roles · *Cases:* R3-ROLE-004 · *Reproduced:* 3/3 (THB000 twice, QA-1017-CL01, NOPE99) · *Status:* Open

Steps:

1. Sign in as a USER of MKL246.
2. POST /api/v1/shipments/validate with the normal UPS Ground body but clientCode THB000 (also QA-1017-CL01 with account 14, and NOPE99).

**Expected:** 403 (or 400 for the unknown client) with a message; the same body as admin returns 200 with a validation error.

**Actual:** 500 INTERNAL_ERROR 'Something went wrong on the server. Please try again in a moment' with a reference id. No data is returned (isolation holds), but the tenant check throws instead of refusing.

**Impact:** Noisy server errors for a routine permission refusal; error logs fill with what should be a 403; a client-side bug that sends the wrong clientCode is hard to diagnose.

Environment: API, USER role

## 5. Operator role tests (the part run 2 could not do)

- **R3-ROLE-001** (PASS) Create an operator through Invite user, accept the invite, sign in — Settings > Users > Invite user (qa-1018c-op@example.com, MKL246, role USER - 'day-to-day operator for the client'; the other option is TENANT - customer-facing read-only). 'Invite emailed ... (or logged in dev)' and the raw accept link is shown; pending invite listed with Copy link / Resend / Revoke; admin action logged. Accept page: 'You've been invited to join MKL246 as USER', Full Name / Username / Password (6+ chars). Account qa1018cop created ('Account created - please log in'); sign-in OK ('Welcome back, qa1018cop'), /auth/session = USER, clientCode MKL246. Password set to a generated value kept only in the browser session (cleared afterwards).
- **R3-ROLE-002** (FAIL) Operator can work the orders grid for the own client — The Shipment + Label grid is empty for the operator: every GET /api/v1/orders list call (plain, with customer=MKL246, orderNo=, search=, page/size/sort) answers 403 FORBIDDEN 'You do not have permission to perform this action.' while the badges (queue-stats: All 549 / Failed 144 / Archive 405) load; the grid shows 'No orders yet.' and the toast 'Something went wrong - You do not have permission...' on load and on Refresh (2/2, plus 6 API variants). Direct GET /orders/934340 (own order) and /orders/934340/label work, and the operator can validate and buy (934343).
- **R3-ROLE-003** (PASS) Tenant isolation as the operator (read) — GET /orders/900034 (THB000) -> 403; /clients lists only MKL246 (total 1); /carrier-accounts returns the 3 MKL246 accounts (ids 1, 2, 7) and not the platform/QA-1017 ones; /orders/stats and /queue-stats are MKL246-scoped (555 orders); /manifests/events shows MKL246 events; Bulk Mailer import history shows the MKL246 batches; New Shipment client dropdown offers MKL246 only. Residual: GET /clients/{id} answers 403 even for the own client (id 2) while the list works (Low).
- **R3-ROLE-004** (FAIL) Tenant isolation as the operator (write) and admin-only operations — POST /shipments/validate with clientCode THB000 (and with QA-1017-CL01 + account 14, and with an unknown client NOPE99) -> 500 INTERNAL_ERROR 'Something went wrong on the server' (reference ids in server logs) instead of a 403/400 - 3/3; the same bodies as admin answer 200 with clean validation errors ('Account 746W05 belongs to client MKL246, not THB000', '3 issues must be fixed'). Admin-only operations are refused cleanly: POST /api-keys -> 403, POST /carrier-accounts/stamps-sera/add-funds/7 -> 403, GET /api-keys, /admin/carrier-api-log -> 403; /settings/users, /settings/api-keys, /settings/system, /settings/carriers, /settings/clients, /settings/carrier-api-log redirect to /dashboard; the operator's Settings menu is reduced (Clients, Warehouses, Address book, Carrier Accounts, Shipping Catalog, Pickups + End-of-Day, Importer / Broker, Templates, Custom Fields, Notifications, Logs). Operator may void own-client labels (934340 FedEx voided, 934343 UPS void 'rejected' by the sandbox). No THB000 write was attempted (read-only client).
- **R3-ROLE-005** (PASS) Sign out, sign back in as admin; test user deactivated — Clicking the avatar in the left rail logs out at once (toast 'Logged out', no confirmation); /auth/session -> 401 afterwards. Sign-in as admin with the password supplied in chat restored the ADMIN session (/auth/session admin, clientCode null). qa1018cop deactivated from Settings > Users (POST /admin/users/5/deactivate 200, row shows Deactivated); the Deactivate action uses a native browser prompt('Deactivate qa1018cop? Reason (optional):') rather than the app's styled dialogs. The browser's own password manager autofills the admin credentials on the login page (Chrome autofill, not the app - Login.tsx starts with empty state).

Summary: invite → accept → sign-in works and the role/client are pre-assigned; read isolation holds (foreign order 403, own-client-only lists and stats, client dropdown limited to MKL246); admin-only pages redirect to the dashboard and admin APIs answer 403; the operator can validate, buy and void own-client labels and use Bulk Mailer. Two defects: the order list is forbidden for the role (R3-BUG-001) and a cross-tenant validate dies with a 500 instead of a 403 (R3-BUG-002). TENANT (customer-facing read-only) was not exercised.

## 6. Test cases and results

| ID | Area | Pri | Title | Result | Bugs |
|---|---|---|---|---|---|
| R3-FIX-BUG-002 | International | P2 | Regression check of run-1 fix BUG-002: Reason for export has no silent default | PASS |  |
| R3-FIX-BUG-011 | Returns | P2 | Regression check of run-1 fix BUG-011: Return from block is not pre-filled with the wrong party | PASS |  |
| R3-FIX-BUG-012 | Orders grid | P1 | Regression check of run-1 fix BUG-012: 'Shipment vs return' filter refetches and Clear all resets | PASS |  |
| R3-FIX-BUG-016 | Bulk Mailer | P1 | Regression check of run-1 fix BUG-016: Generate labels asks for confirmation | PASS |  |
| R3-FIX-BUG-018 | Funds | P1 | Regression check of run-1 fixes BUG-018/BUG-019: Add Funds limits and unauthorized account | PASS |  |
| R3-RETEST-BUG-005 | Label purchase (API) | P3 | Retest run-1 BUG-005: carrierCode in the body disagrees with the selected account | FAIL |  |
| R3-RETEST-R2-BUG-001 | External API | P1 | Retest R2-BUG-001: External API void must cancel the label at the carrier | FAIL |  |
| R3-RETEST-R2-BUG-002 | Close out / pickups | P1 | Retest R2-BUG-002: Schedule a pickup on FedEx, UPS and USPS | FAIL |  |
| R3-RETEST-R2-BUG-003 | Close out / pickups | P1 | Retest R2-BUG-003: Close whole day and the open-labels query | FAIL |  |
| R3-RETEST-R2-BUG-004 | Data integrity | P1 | Retest R2-BUG-004: per-box declared values on multi-box shipments | FAIL |  |
| R3-RETEST-R2-BUG-005 | Data integrity | P1 | Retest R2-BUG-005: duplicate label purchases (internal endpoint and External API) | FAIL |  |
| R3-RETEST-R2-BUG-006 | Bulk Mailer | P2 | Retest R2-BUG-006: shipped template row and Errors (CSV) round trip | FAIL |  |
| R3-RETEST-R2-BUG-007 | Data integrity | P2 | Retest R2-BUG-007: Carrier API Log rows from a bulk label run carry the order number | FAIL |  |
| R3-RETEST-R2-BUG-008 | Settings | P2 | Retest R2-BUG-008: a custom field marked 'Required on new shipments' is enforced | FAIL |  |
| R3-RETEST-R2-BUG-009 | Validation | P1 | Retest R2-BUG-009: server-side contact and length validation | FAIL |  |
| R3-RETEST-R2-BUG-010 | Settings | P2 | Retest R2-BUG-010: deleting a warehouse in use needs more than a single confirm | FAIL |  |
| R3-RETEST-R2-BUG-011 | Validation | P2 | Retest R2-BUG-011: embargoed destinations (IR) pass validation | FAIL |  |
| R3-RETEST-R2-BUG-012 | Orders grid | P2 | Retest R2-BUG-012: status buckets vs list total; legacy FAILED-label orders | FAIL |  |
| R3-RETEST-R2-BUG-013 | Orders grid | P2 | Retest R2-BUG-013: Export CSV writes the whole filtered result set | FAIL |  |
| R3-RETEST-R2-BUG-014 | Label output | P3 | Retest R2-BUG-014: label page preview shows the order's sender | FAIL |  |
| R3-RETEST-R2-BUG-015 | Void / reissue | P2 | Retest R2-BUG-015: UPS void rejection and USPS non-unique void | FAIL |  |
| R3-RETEST-R2-BUG-016 | Void / reissue | P3 | Retest R2-BUG-016: reissuing a voided label warns 'already shipped' on the same order | FAIL |  |
| R3-RETEST-R2-BUG-017 | Returns | P3 | Retest R2-BUG-017: 'Return of #' loads the original recipient into the Return from block | FAIL |  |
| R3-RETEST-R2-BUG-018 | Orders grid | P3 | Retest R2-BUG-018: reversed date range | PASS |  |
| R3-RETEST-R2-BUG-019 | Security | P3 | Retest R2-BUG-019: Issue API key dialog defaults | FAIL |  |
| R3-RETEST-R2-BUG-020 | Options | P2 | Retest R2-BUG-020: Shipment Check quote omits surcharges | FAIL |  |
| R3-RETEST-R2-BUG-021 | Options | P3 | Retest R2-BUG-021: insured value never sent to the carrier | FAIL |  |
| R3-ROLE-001 | Security | P1 | Create an operator through Invite user, accept the invite, sign in | PASS |  |
| R3-ROLE-002 | Security | P1 | Operator can work the orders grid for the own client | FAIL | R3-BUG-001 |
| R3-ROLE-003 | Security | P1 | Tenant isolation as the operator (read) | PASS |  |
| R3-ROLE-004 | Security | P1 | Tenant isolation as the operator (write) and admin-only operations | FAIL | R3-BUG-002 |
| R3-ROLE-005 | Security | P1 | Sign out, sign back in as admin; test user deactivated | PASS |  |

## 7. Data-integrity notes from the retest

- Multi-box declared values: 934329 (UPS) and 934330 (FedEx) entered 50/150, carrier got 100/100 (R2-BUG-004).
- Surcharges: 934333 UPS Ground + adult signature quoted 11.33, billed 20.79 (Carrier API Log 2708; R2-BUG-020). Insurance: 934334 insuredValue 500 absent from the UPS request (log 2709; R2-BUG-021).
- External API void: 934335 (UPS), 934336 (FedEx), 934337 (UPS duplicate) are VOIDED in the app with only the ship call in the Carrier API Log (2717, 2718, 2719); the void response itself now says carrierVoided:false (R2-BUG-001). Internal void of 934339 did call apis-sandbox.fedex.com/ship/v1/shipments/cancel (log 2722) and the cancel row carries the order number — the bulk ship rows 2720/2721 do not (R2-BUG-007).
- Status model unchanged: internal void leaves orderDetails GENERATED / label VOIDED (934339 before reissue, 934340), External API void sets both VOIDED.
- Stats buckets 34,004 vs list 34,014 (ten orders in no bucket; was eight in run 2 — the gap grows with every voided/failed row); legacy 900001 still missing from the Error filter (R2-BUG-012).
- Required custom field: 934340 generated with customFields {} (R2-BUG-008). Export: 10 of 11 rows at 10/page (R2-BUG-013).
- The read-only SELECT pack from run 2 (`multiship-qa-2026-10-18-run2-db-checks.sql`) still applies; the run-3 addendum `multiship-qa-2026-10-18-run3-db-checks.sql` lists the new order numbers and the role-test rows.

## 8. Security notes

- localStorage still holds 'multiship_token', an expired JWT (sub admin, role, exp 2026-08-12) from an older auth scheme; the current session is a cookie and the token is not used (session answers 401 after logout), but logout does not clear it. Low - remove on logout / drop the key.
- Invite acceptance accepts any password of 6+ characters ('123456' enables the button); no complexity or breach check. Low - policy decision.
- Passed again: revoked External API key answers 401; admin-only APIs 403 for USER; foreign-tenant order 403 for USER; XSRF still enforced (403 without the header) — not re-run exhaustively, see run 2 for the full pass.

## 9. UX and other observations

- (observation) Return form, 'Return to - your address' block for MKL246: name/company 'MKL' and address 8191 Logistics Dr come from the client record (returnSameAsShipFrom true), but the phone is the client's contact phone +1 555 123 6789 (clients.phone) rather than the return address phone +1 616-772-3513 (shipFrom.phone). Seen in run 3 only (the run-2 return was re-typed by hand); Low - the carrier receives the contact phone instead of the warehouse phone on return labels.
- (observation) GET /me/shipper-default returns 'Multiship Warehouse, 1 Warehouse Way, Bengaluru, KA 560001, phone 0000000000' with tenantCode null; this is exactly what the label page preview prints as SHIP FROM (R2-BUG-014) - hypothesis: the preview reads the platform shipper default instead of the order's sender.
- (ux) The avatar button at the bottom of the left rail logs the user out immediately with no confirmation (toast 'Logged out'); an accidental click drops an operator mid-shipment. Low.
- (ux) Users > Deactivate uses a native browser prompt ('Deactivate <user>? Reason (optional):') while every other destructive action uses the app's styled confirm dialog; the native dialog also blocks automation and screen readers differently. Low.
- (observation) The invite dialog says 'Invite emailed ... (or logged in dev)' and shows the raw accept link as a fallback; whether an email actually left the box was not verified (Email Delivery Log not checked).
- (observation) Operator (USER) sees Clients, Warehouses, Carrier Accounts, Shipping Catalog and Custom Fields in Settings; their write actions were not exercised as USER (the role's read scope looked client-limited: /clients total 1, /carrier-accounts 3).

## 10. Open questions for product / dev

- Is the USER role meant to list orders? The grid, badges and every action assume yes; only the list permission is missing (R3-BUG-001).
- The External API void response now admits that carrier-side cancellation 'is not wired in this environment' — is that an environment flag that is on in production, or is the carrier cancel simply not implemented for the External API path (R2-BUG-001)?
- Should the Return-to block on return labels carry the return address phone (+1 616-772-3513) or the client contact phone (+1 555 123 6789)? Today it is the contact phone.
- The label page preview prints GET /me/shipper-default (Multiship Warehouse, Bengaluru) as SHIP FROM for every order — confirm the preview is meant to be a placeholder and not what operators check against (R2-BUG-014).
- The run-2 questions remain open (Puerto Rico customs, voided labels in Labels & Invoices, nds-default, order channels per client).

## 11. QA data created in run 3 (cleanup list)

Everything is under MKL246 with the `QA-1018c-` prefix (two mismatch probes carry a doubled prefix, QA-1018c-QA-1018c-MM1/MM3). QA removed what it created where that was possible: the custom field definition, the API key (revoked), the operator account (deactivated, not deleted), the discarded bulk uploads. Orders cannot be deleted through the app and are listed below.

| What | Id | Where | State | Note |
|---|---|---|---|---|
| order | 934329, 934330 | MKL246 | GENERATED (UPS 2-box, FedEx 2-box) | QA-1018c multi-box declared-value retest (R2-BUG-004) |
| order | 934331, 934332 | MKL246 | GENERATED (same reference QA-1018c-D001 twice) | duplicate purchase retest (R2-BUG-005) |
| order | 934333 | MKL246 | GENERATED (FedEx Ground, ADULT signature) | surcharge quote retest (R2-BUG-020) |
| order | 934334 | MKL246 | GENERATED (insuredValue 500) | insured value retest (R2-BUG-021) |
| order | 934335, 934336, 934337 | MKL246 | VOIDED on the platform only (no carrier cancel) - External API, refs QA-1018c-EXT1 (x2) and QA-1018c-EXT2 | R2-BUG-001 / R2-BUG-005 retest; labels never used |
| External API key | QA-1018c-key (id 3, MKL246, TEST, scopes shipments tracking rates void addresses) ...087d | Settings > API keys | REVOKED at 15:53 local (revoked token -> 401) | token kept in the browser session only and cleared; never written anywhere |
| pickup attempts | FedEx / UPS / USPS pickup requests (all rejected by the carriers) | Pickups, MKL246 | no pickup scheduled | R2-BUG-002 retest |
| bulk import | Import #13 (QA-1018c-bulk2.csv), label batch 34; uploads 24 and 25 discarded | Bulk Mailer > Import history | Complete - 2 generated | R2-BUG-007 retest |
| order | 934338, 934339 | MKL246 | GENERATED (UPS P80 / FedEx F77 from import #13), refs QA-1018c-BM-2 / QA-1018c-BM-1 | bulk label run |
| order | 934339 | MKL246 | label 794893822638 VOIDED at the carrier (FedEx cancel 2722), reissued as 794893823895 (GENERATED) | R2-BUG-016 retest |
| order | 934340 | MKL246 | GENERATED (FedEx Ground 794893824160), ref QA-1018c-CF1, customFields {} | R2-BUG-008 retest (UI) |
| custom field definition | qa_1018c_dept (MKL246) | Settings > Custom Fields | created and deleted during the retest | R2-BUG-008 |
| order | 934341, 934342 | MKL246 | ERROR (carrierCode/account mismatch probe, no label) | run-1 BUG-005 retest |
| user | qa1018cop (qa-1018c-op@example.com, USER, MKL246, id 5) | Settings > Users | Deactivated after the role tests (reason 'QA-1018c cleanup'); password kept only in the browser session and cleared | invite accepted during run 3; reactivate or delete as you prefer |
| order | 934343 | MKL246 | GENERATED (UPS Ground, bought by the operator qa1018cop), ref QA-1018c-OP1; void attempt rejected by the UPS sandbox | role test |
| order | 934340 (update) | MKL246 | label 794893824160 VOIDED by the operator during the role test | R2-BUG-008 order |

Browser: the helper keys in sessionStorage (`__qa1018b_*`, `__qa1018c_*`) were removed at the end of the run; localStorage was not changed (the stale `multiship_token` noted above was left as found).

## 12. Evidence

Screenshots referenced by the cases are in `multiship-qa-2026-10-18-run3-evidence.zip` (file names start with the case or bug id). Carrier request/response bodies are in Settings → Carrier API Log by order number (bulk runs excepted, R2-BUG-007).
