# CarrierConnections · Global + role gating — manual smoke checklist

Sprint 53 page-tests. Corresponds to the automated coverage in
`multiship-react/src/components/CarrierConnections.global.test.tsx`.

Scope: toolbar-level actions (Add Account), page-level chrome
(health-strip cards), loading/empty/error states, and role-based
visibility of the toolbar. Filters + row-actions + drawer flow are
exercised in sibling slice suites (coordinate with the Add / Filters
/ Row-actions agents).

---

## Preconditions

- App running against a backend that returns at least 2 seeded carrier
  accounts across UPS / FedEx / USPS.
- Three test accounts on hand, one per role: `ADMIN`, `USER`, `TENANT`.
- Browser DevTools Network panel open — some assertions inspect the
  outbound `/api/v1/carriers/account-book` calls.

---

## Positive — ADMIN

1. Log in as ADMIN. Navigate to `/settings/carriers`.
2. Verify while the list is loading, the placeholder
   "Loading the account book…" is visible under the health-strip cards.
3. Verify the health-strip renders 4 cards along the top:
   `Ready to ship`, `Unverified`, `Platform accounts`, `Client accounts`.
   `Ready to ship` reads `<active+complete>/<total>`.
4. After load, the AdvancedDataTable renders one row per seeded account.
   The caption below the search reads "Showing N of N accounts".
5. Click **Add Account** (top-right of the AdvancedDataTable toolbar).
   - EXPECT: right-side drawer slides in, titled "Add carrier account".
   - Close the drawer (click the X or the backdrop).
6. Delete every account (or point at a fresh tenant with none).
   - EXPECT: the table body shows
     "No accounts saved yet — add your first carrier account." with the
     **Add Account** button still visible in the toolbar.

---

## Positive — USER

7. Log out and back in as USER. Navigate to `/settings/carriers`.
8. EXPECT (documented behavior): the **Add Account** button IS still
   rendered in the toolbar — the frontend does not gate this. The
   backend rejects unauthorized POSTs.
9. EXPECT: the health-strip cards render exactly as for ADMIN.
10. EXPECT: opening the drawer is possible; attempting to save an
    account returns a 403 which surfaces via `notify.apiError`.

---

## Positive — TENANT

11. Log out and back in as TENANT (a customer login mapped to a single
    client_code by username).
12. Navigate to `/settings/carriers` (if the sidebar surfaces it —
    TENANT only shows `orders` per `getNavKeysForRole`; if you land on
    `/orders` you may need to type the URL manually).
13. EXPECT: the table shows only the tenant's own carrier accounts —
    no other tenants leak in. The "Showing K of K accounts" caption
    confirms the backend scoping.
14. EXPECT: the toolbar renders **Add Account** (documented — no FE
    gate). Attempting to add returns 403 from the backend.

---

## Negative

15. Simulate a `listAccounts` failure at page load (block the initial
    `GET /api/v1/carriers/account-book` request in DevTools).
    - EXPECT: persistent error toast "Failed to load the account book."
      (via `notify.apiError`).
    - EXPECT: the loading placeholder resolves to the empty state
      "No accounts saved yet — add your first carrier account."
16. Log in as USER, `POST /api/v1/carriers/account-book` directly from
    the browser console → backend returns 403. This confirms the FE
    gate is intentionally cosmetic (there is none) but the backend has
    our back.

---

## Role visibility matrix (documented behavior — 2026-08-16)

Note: `CarrierConnections.tsx` does **not** consume `useAppSession` at
all. Every toolbar and row control is rendered regardless of role.
Authorization is enforced by the backend. If a future FE-level gate is
added, update the matrix here and flip the corresponding assertions in
the automated suite.

| Element | ADMIN | USER | TENANT |
|---|---|---|---|
| Health-strip cards (Ready / Unverified / Platform / Client) | shown | shown | shown |
| AdvancedDataTable toolbar: search input | shown | shown | shown |
| AdvancedDataTable toolbar: Filters button | shown | shown | shown |
| AdvancedDataTable toolbar: Columns button | shown | shown | shown |
| AdvancedDataTable toolbar: Export button | shown | shown | shown (data scoped by backend) |
| AdvancedDataTable toolbar: **Add Account** button | shown | shown (no FE gate; backend rejects) | shown (no FE gate; backend rejects) |
| Empty-state message when 0 rows | shown | shown | shown (tenant with 0) |
| Row-actions kebab (⋮): Verify / Edit / Delete | shown | shown (backend rejects on click) | shown (backend rejects on click) |
| Row status toggle (ActiveToggle) | shown + enabled | shown + enabled (backend rejects if unauthorized) | shown + enabled (backend rejects) |
| Client-default star (per-row) | shown | shown | shown |
| Table rows | all accounts | all accounts the JWT can see | tenant-scoped only |

---

## Test-coverage cross-ref

Automated (`CarrierConnections.global.test.tsx`, 10 tests):
- Positive: loading placeholder, empty state, populated rows,
  Add-Account opens drawer, health-strip totals reflect data.
- Role gating: ADMIN toolbar visible, USER toolbar visible (documented
  no-FE-gate behavior), TENANT sees only scoped rows.
- Negative: `listAccounts` rejection → `notify.apiError`; error path
  falls through to empty state (no ghost rows).
