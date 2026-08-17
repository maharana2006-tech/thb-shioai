# ClientsPage — Row Actions manual smoke

Sprint 54 test-agent slice for `/settings/clients` row actions.
Covers the per-row kebab menu (Edit / Importer-Broker / Delete), the inline
ACTIVE ↔ INACTIVE toggle switch, the deactivate cascade preview + confirm, and
the `busyId` concurrent-click guard.

Automated coverage lives in
`multiship-react/src/components/ClientsPage.rowActions.test.tsx` (13 tests).
This checklist is the human-in-the-loop follow-up: run against a staging DB
whenever the row-action code path or its dependencies change.

## Prep

1. Log in as an **ADMIN** account.
2. Navigate to **Settings → Clients**.
3. Seed at least three clients:
   - `ACME` — ACTIVE, at least one carrier account attached, 0 pending orders.
   - `BUSY` — ACTIVE, with **≥1 pending order** (needed for the blocker test).
   - `BETA` — INACTIVE.

## Kebab menu

- [ ] Row kebab (three dots) opens a menu of 3 items for ADMIN:
      **Importer / Broker**, **Edit**, **Delete**.
- [ ] Log out, log back in as a **USER** role account.
- [ ] Row kebab now shows **only 2 items**: Importer / Broker + Edit
      (Delete is hidden for non-admins).
- [ ] Log back in as ADMIN for the remaining steps.

## Edit action

- [ ] Kebab → **Edit** on the `ACME` row.
- [ ] URL becomes `/settings/clients/ACME` and the client editor loads.
- [ ] Back button returns to the clients list without a full page reload.

## Importer / Broker action

- [ ] Kebab → **Importer / Broker** on the `ACME` row.
- [ ] CustomsProfile modal opens with the client code locked to `ACME`.
- [ ] Close the modal via the X button; the list is still visible + interactive.

## Toggle active — deactivate ACTIVE → INACTIVE (happy path)

- [ ] Click the row-status toggle on `ACME` (an ACTIVE client with 0 pending
      orders).
- [ ] A confirm dialog appears titled **Deactivate ACME?** and lists the
      cascade counts:
      - `N carrier account(s)`
      - `N client-owned warehouse(s)`
      - `N warehouse attachment(s)`
- [ ] Click **Cancel** → dialog closes; row status stays ACTIVE; no toast.
- [ ] Click the toggle again → confirm dialog reappears; click **Deactivate**.
- [ ] Success toast fires: `ACME is now INACTIVE.`
- [ ] Row status flips to INACTIVE and the toggle color changes.
- [ ] Table refetches automatically (no manual refresh needed).

## Toggle active — deactivate ACTIVE with pending orders (BLOCKER)

- [ ] Click the row-status toggle on `BUSY` (ACTIVE, ≥1 pending order).
- [ ] An **error** toast fires:
      `BUSY has N pending order(s). Complete or void them before deactivating.`
- [ ] **No confirm dialog** appears; **no** deactivation happens.
- [ ] Row status stays ACTIVE.

## Toggle active — reactivate INACTIVE → ACTIVE

- [ ] Click the row-status toggle on `BETA` (INACTIVE).
- [ ] No cascade preview / confirm dialog — action is instant.
- [ ] Success toast fires: `BETA is now ACTIVE.`
- [ ] Row status flips to ACTIVE.
- [ ] Table refetches.

## Delete action

- [ ] Kebab → **Delete** on `BETA`.
- [ ] Confirm dialog appears: **Delete client** / *Delete client BETA?*
- [ ] Click **Cancel** → row still present; no toast.
- [ ] Kebab → **Delete** → confirm.
- [ ] Success toast: `Client BETA deleted.`
- [ ] Row disappears; table refetches.
- [ ] (Optional server-side check) Deleting a client that has orders shows the
      `CLIENT_HAS_ORDERS` friendly error toast — replicate by seeding a
      client with orders and trying to delete it.

## Concurrent-click guard (`busyId`)

- [ ] Throttle the network (DevTools → Slow 3G) or use a debugger break in
      `handleToggleActive`.
- [ ] Click a row toggle → the toggle button visibly dims/disables while the
      request is in flight.
- [ ] Double-click the same toggle rapidly → only **one** toggle-active
      request is fired (verify in the DevTools Network tab).
- [ ] After the request settles, the toggle re-enables.

## Export button (regression guard, adjacent)

- [ ] The toolbar `Export` button still triggers a CSV download after any of
      the above actions — proves state didn't corrupt the export handler.

## Regression watch-list

- Any change to `handleToggleActive`, `handleDelete`, or `RowActionsMenu` in
  `ClientsPage.tsx` should re-run the automated tests **and** the manual
  cascade + delete confirm flows above.
- If `notify.confirm` signature changes, the cascade summary text assertion
  in the automated test will drift — update both the test and this doc.
