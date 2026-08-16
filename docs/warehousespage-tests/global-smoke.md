# WarehousesPage · Global actions + role gating — manual smoke checklist

Sprint 51 page-tests. Corresponds to the automated coverage in
`multiship-react/src/components/WarehousesPage.global.test.tsx`.

Scope: toolbar-level "Add Warehouse" action, loading / empty / populated
states, and role-based visibility of the row-actions kebab (Delete is
admin-only). Filters + sort + pagination are exercised in sibling slice
suites owned by the other three parallel test agents.

---

## Preconditions

- App running against a backend that returns at least 2 seeded
  warehouses (one PLATFORM, one CLIENT-owned).
- Three test accounts on hand, one per role: `ADMIN`, `USER`, `TENANT`.
- Browser DevTools Network panel open — some assertions inspect the
  outbound request query string.

---

## Positive — ADMIN

1. Log in as ADMIN. Navigate to `/settings/warehouses`.
2. Verify while the list is loading, the placeholder
   "Loading warehouses…" is visible.
3. After load, the table renders one row per seeded warehouse. The
   caption below the toolbar reads "Showing N of N warehouses".
4. Click **Add Warehouse** (top-right of the toolbar).
   - EXPECT: `WarehouseEditorModal` opens in CREATE mode. The code and
     name inputs are empty. `ownerType` defaults to `PLATFORM`.
   - Close the modal (X button or Escape).
5. Type `zz-no-such-warehouse` into the search box, wait ~500 ms for the
   debounce.
   - EXPECT: table body shows "No warehouses match the current
     filters." (not the placeholder "Loading warehouses…").
6. Clear the search box.
   - EXPECT: rows return.
7. If the tenant is brand new with zero warehouses (or you soft-delete
   every row), the table area shows "No warehouses registered yet —
   add your first ship-from location." with the **Add Warehouse** button
   still visible in the toolbar.
8. On a **PLATFORM**-owned row, click the row-actions kebab (⋮).
   - EXPECT: menu contains **Attach clients**, **Edit**, and **Delete**.
9. On a **CLIENT**-owned row, click the row-actions kebab (⋮).
   - EXPECT: menu contains **Edit** and **Delete**, but NOT
     **Attach clients** (client-owned warehouses are not attachable —
     see `canAttach` in `WarehousesPage.tsx`).

---

## Positive — USER

10. Log out and back in as USER. Navigate to `/settings/warehouses`.
11. EXPECT (documented behavior): the **Add Warehouse** button IS still
    rendered in the toolbar — the frontend does not gate this. The
    backend rejects unauthorized POST `/warehouses` calls with 403.
12. Click the row-actions kebab (⋮) on any PLATFORM row.
    - EXPECT: menu contains **Attach clients** and **Edit**.
    - EXPECT: menu does NOT contain **Delete**.
13. Click **Edit** → the `WarehouseEditorModal` opens pre-filled with
    the row's current values.

---

## Positive — TENANT

14. Log out and back in as TENANT (a customer login mapped to a single
    client_code by username).
15. Navigate to `/settings/warehouses` (if the sidebar surfaces it —
    TENANT only shows `orders` per `getNavKeysForRole`; if you land on
    `/orders` you may need to type the URL manually).
16. EXPECT: the table shows only warehouses scoped to the tenant —
    PLATFORM warehouses their client is attached to + any CLIENT-owned
    ones they own. No other tenants' private warehouses leak in. The
    caption confirms the count.
17. EXPECT: the row-actions kebab (⋮) shows Attach clients (if
    platform-owned) + Edit only — no Delete. Same gate as USER.
18. EXPECT: **Add Warehouse** button is still rendered (no FE gate).
    A tenant POST is 403 at the backend.

---

## Negative

19. Simulate a `listWarehouses` failure at page load (block the initial
    request in DevTools).
    - EXPECT: error toast "Failed to load warehouses." (via
      `notify.apiError`); the loading placeholder resolves to the empty
      state.
20. Simulate a network failure on toggle-active (right-side switch on a
    row): throttle to "offline" and click the toggle.
    - EXPECT: an error toast surfaces (via `notify.apiError`) with the
      fallback message "Failed to update the warehouse."; the row's
      switch snaps back to its prior state on next refetch.
21. Log in as USER, click **Add Warehouse**, fill out the form, submit
    → backend returns 403. This confirms the FE gate is intentionally
    cosmetic (there is none) but the backend has our back.

---

## Role visibility matrix (documented behavior — 2026-08-16)

| Element | ADMIN | USER | TENANT |
|---|---|---|---|
| Add Warehouse toolbar button | shown | shown (no FE gate; backend rejects) | shown (no FE gate; backend rejects) |
| Filters toggle (owner + status) | shown | shown | shown |
| Row-actions kebab: **Attach clients** (PLATFORM row only) | shown | shown | shown |
| Row-actions kebab: **Attach clients** (CLIENT-owned row) | hidden (canAttach guard) | hidden | hidden |
| Row-actions kebab: **Edit** | shown | shown | shown |
| Row-actions kebab: **Delete** | shown | **hidden** | **hidden** |
| Row status toggle (ActiveToggle) | shown + enabled | shown + enabled (backend rejects if unauthorized) | shown + enabled (backend rejects) |
| Table rows | all warehouses | all warehouses | tenant-scoped only |

Notes:
- `admin = normalizeRole(role) === 'ADMIN'` in `WarehousesPage.tsx`
  (line 34). Only the row-level **Delete** menuitem in `RowActionsMenu`
  reads that flag (line 526).
- **Attach clients** menuitem is gated by `ownerType === 'PLATFORM'`
  (see `canAttach` on line 272), independent of role.
- TENANT scoping is enforced on the backend — the frontend passes
  `search`, `ownerType`, `active` filters through unmodified.
