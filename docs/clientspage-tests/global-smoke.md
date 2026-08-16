# ClientsPage · Global actions + role gating — manual smoke checklist

Sprint 53 page-tests. Corresponds to the automated coverage in
`multiship-react/src/components/ClientsPage.global.test.tsx`.

Scope: toolbar-level actions (Add Client / Export) and role-based
visibility of the row-actions kebab. Filters + column sort + pagination
are exercised in sibling slice suites.

---

## Preconditions

- App running against a backend that returns at least 2 seeded clients.
- Three test accounts on hand, one per role: `ADMIN`, `USER`, `TENANT`.
- Browser DevTools Network panel open — some assertions inspect the
  outbound request query string.

---

## Positive — ADMIN

1. Log in as ADMIN. Navigate to `/settings/clients`.
2. Verify while the list is loading, the placeholder "Loading clients…"
   is visible.
3. After load, the table renders one row per seeded client. The caption
   below the search reads "Showing N of N clients".
4. Click **Add Client** (top-right of the toolbar).
   - EXPECT: browser navigates to `/settings/clients/new`.
   - Click browser Back to return.
5. Click **Export** (toolbar). A one-item dropdown appears with
   "CSV — current view".
6. Click "CSV — current view".
   - EXPECT: browser downloads `clients-<timestamp>.csv`.
   - EXPECT (Network tab): request URL is
     `/api/v1/clients/export.csv?sortBy=code&sortDirection=ASC` (no
     filter params because none are applied).
7. Type `zz-no-such-client` into the search box, wait ~500 ms for the
   debounce.
   - EXPECT: table body shows "No clients match the current filters."
     (not the placeholder "Loading clients…").
8. Clear the search box.
   - EXPECT: rows return.
9. If the tenant is brand new with zero clients (or you delete every
   row), the toolbar area shows "No clients registered yet — add your
   first client." with the **Add Client** button still visible in the
   toolbar.

---

## Positive — USER

10. Log out and back in as USER. Navigate to `/settings/clients`.
11. EXPECT (documented behavior): the **Add Client** button IS still
    rendered in the toolbar — the frontend does not gate this. The
    backend rejects unauthorized POSTs.
12. EXPECT: the **Export** button is visible + functional (same as
    ADMIN).
13. Click the row-actions kebab (⋮) on any row.
    - EXPECT: menu contains **Importer / Broker** and **Edit**.
    - EXPECT: menu does NOT contain **Delete**.
14. Click **Edit** → routes to `/settings/clients/<code>`.

---

## Positive — TENANT

15. Log out and back in as TENANT (a customer login mapped to a single
    client_code by username).
16. Navigate to `/settings/clients` (if the sidebar surfaces it — TENANT
    only shows `orders` per `getNavKeysForRole`; if you land on
    `/orders` you may need to type the URL manually).
17. EXPECT: the table shows only the tenant's own client_code — no
    other tenants leak in. The "Showing 1 of 1 client" caption confirms
    the backend scoping.
18. EXPECT: the row-actions kebab (⋮) shows Importer / Broker + Edit
    only (no Delete). Same as USER.
19. Click **Export** → the resulting CSV contains only the tenant's
    scoped rows (backend enforces).

---

## Negative

20. Simulate a network failure on the export endpoint (throttle to
    "offline" in DevTools, then click Export → CSV).
    - EXPECT: a persistent error toast surfaces (via `notify.apiError`)
      with the fallback message "Failed to export clients."
21. Simulate a `listClients` failure at page load (block the initial
    request in DevTools).
    - EXPECT: error toast "Failed to load clients." (via
      `notify.apiError`); the loading placeholder resolves to the empty
      state.
22. Log in as USER, `POST /api/v1/clients` directly from the browser
    console → backend returns 403. This confirms the FE gate is
    intentionally cosmetic (there is none) but the backend has our
    back.

---

## Role visibility matrix (documented behavior — 2026-08-16)

| Element | ADMIN | USER | TENANT |
|---|---|---|---|
| Add Client toolbar button | shown | shown (no FE gate; backend rejects) | shown (no FE gate; backend rejects) |
| Export toolbar button | shown | shown | shown (data scoped by backend) |
| Row-actions kebab: Importer / Broker | shown | shown | shown |
| Row-actions kebab: Edit | shown | shown | shown |
| Row-actions kebab: Delete | shown | hidden | hidden |
| Row status toggle (ActiveToggle) | shown + enabled | shown + enabled (backend rejects if unauthorized) | shown + enabled (backend rejects) |
| Table rows | all clients | all clients | tenant-scoped only |
