# WarehousesPage — Row Actions + Editor manual smoke

Sprint 54 test-agent slice for `/settings/warehouses` row actions and the
`WarehouseEditorModal` internals (including the Sprint 52 Verify Address
flow).

Automated coverage lives in
`multiship-react/src/components/WarehousesPage.rowActions.test.tsx`
(19 tests — row actions positive/negative, modal internals
positive/negative, Verify Address).

This checklist is the human-in-the-loop follow-up: run against a staging
DB whenever the row-action code path, the editor modal or the
address-validation flow changes.

## Prep

1. Log in as an **ADMIN** account.
2. Navigate to **Settings → Warehouses**.
3. Seed at least three warehouses:
   - `PLAT1` — PLATFORM-owned, ACTIVE, US address.
   - `PLAT2` — PLATFORM-owned, ACTIVE, US address (used for busyId).
   - `CLI1`  — CLIENT-owned (`ownerClientCode=ACME`), ACTIVE.

## Kebab menu

- [ ] Row kebab on the `PLAT1` row opens a menu of 3 items for ADMIN:
      **Attach clients**, **Edit**, **Delete**.
- [ ] Row kebab on the `CLI1` row opens a menu of 2 items for ADMIN:
      **Edit**, **Delete** — **Attach clients is hidden** because the
      warehouse is client-owned.
- [ ] Log out; log back in as a **USER** role account.
- [ ] Row kebab on `PLAT1` now shows only **Attach clients** + **Edit**
      (Delete hidden for non-admins). Log back in as ADMIN.

## Add warehouse (create mode)

- [ ] Click **Add Warehouse** in the table toolbar.
- [ ] Editor drawer opens titled **Add warehouse**.
- [ ] All fields empty; the **Code** field is editable.
- [ ] Country defaults to `US`. Active checkbox checked.
- [ ] Click **Create warehouse** with the form empty. An error toast fires
      ("Fix the highlighted fields before saving.") and required-field
      errors surface inline.
- [ ] Fill: Code `NEWWH`, Name `New warehouse`, Line 1, City, State, Postal,
      Country `US`, Phone (10 digits).
- [ ] Click **Create warehouse** — succeeds; editor pivots to the
      **Attach clients** step (PLATFORM path).
- [ ] Click **Finish attach step** (or Skip) — modal closes and the table
      refetches with the new row visible.

## Add warehouse — CLIENT owner

- [ ] Reopen the editor via **Add Warehouse**.
- [ ] Switch the Owner radios to **Client**.
- [ ] The "Owner client" picker appears; Save is disabled until a client is
      chosen.
- [ ] Fill everything, pick a client, click **Create warehouse**.
- [ ] Success toast fires; editor closes **without** the attach step
      (CLIENT-owned warehouses skip the follow-up).

## Edit action

- [ ] Kebab → **Edit** on `PLAT1`.
- [ ] Editor drawer titled **Edit warehouse PLAT1** opens.
- [ ] Code field is read-only (greyed out).
- [ ] Address fields hydrated from the row.
- [ ] Change the Name; click **Save changes**. Success toast fires; drawer
      closes; table refetches.

## Cancel + backdrop

- [ ] Reopen the editor via Edit; click **Cancel**. Drawer closes without
      any network call.
- [ ] Reopen the editor; click the darkened area outside the drawer
      (backdrop). Drawer closes.
- [ ] Reopen the editor; click the **X** in the header. Drawer closes.

## Verify address (Sprint 52 PR #188)

- [ ] Kebab → **Edit** on `PLAT1`.
- [ ] The Verify button reads **Verify (UPS)** and is enabled while
      line1/city/zip/country are populated.
- [ ] Blank the Postal code — Verify button becomes disabled with a
      hint tooltip explaining why.
- [ ] Restore Postal code; click **Verify (UPS)**.
- [ ] Result panel renders below the address:
      - Green EXACT badge for a clean address; no suggestion panel.
      - Amber CORRECTED / AMBIGUOUS badge with a suggested address and a
        **Use suggestion** button.
      - Grey NOT_SUPPORTED badge for carriers with no address-validation
        connector.
      - Red NOT_FOUND / ERROR badge with the carrier's message.
- [ ] Click **Use suggestion**: form fields populate from the suggestion;
      success toast; panel dismisses. Save the warehouse to persist.
- [ ] Edit any address field after a verify — the badge auto-dismisses so
      it never lies about stale data.

## Toggle active

- [ ] Click the row toggle on `PLAT1` — success toast:
      `PLAT1 is now inactive.` Row status flips; table refetches.
- [ ] Click the toggle again — flips back to Active with a success toast.

## Delete action

- [ ] Kebab → **Delete** on `PLAT2`.
- [ ] Confirm dialog appears titled **Delete warehouse**; body reads
      `Delete warehouse PLAT2?`. Danger (red) confirm button.
- [ ] Click **Cancel** — row still present; no toast.
- [ ] Kebab → Delete → **Confirm**. Success toast: `Warehouse PLAT2 deleted.`
      Row disappears; table refetches.
- [ ] Attach `PLAT1` to two clients (via Attach clients modal); Delete it.
      The confirm body should read
      `Delete warehouse PLAT1? It is currently attached to 2 clients — they will be unlinked.`

## Concurrent-click guard (`busyId`)

- [ ] Throttle the network (DevTools → Slow 3G).
- [ ] Click a row toggle. Every row toggle disables while the request is in
      flight (component uses a single busy-any guard).
- [ ] Double-click the same toggle rapidly — only one toggle-active request
      is fired (verify in DevTools Network tab).
- [ ] After the request settles, all toggles re-enable.

## Attach clients (row action, PLATFORM only)

- [ ] Kebab → **Attach clients** on `PLAT1`. Attach clients modal opens.
- [ ] Pick one or more clients, mark a default, click Attach & finish.
- [ ] Table refetches; the row's attached-count column reflects the new count.

## Regression watch-list

- Any change to `handleToggleActive`, `handleDelete`, `RowActionsMenu`,
  or the `WarehouseEditorModal` submit / verify handlers should re-run the
  automated tests **and** the manual create/edit/verify flows above.
- If `notify.confirm` or `addressValidationService.validate` signatures
  change, the automated-test assertions on payload shape / dialog options
  will drift — update both the test and this doc.
