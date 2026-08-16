# Client wizard — Ship From step manual smoke checklist

Sprint 53 wizard-tests — companion to
`multiship-react/src/components/ClientEditorPage.shipFrom.test.tsx`.
The unit tests cover the picker + preview card + WarehouseEditorModal
verification flow under jsdom. This checklist is what QA (or the dev on
smoke duty) walks through in a real browser before signing off any sprint
that touches the client wizard's Ship From step.

The wizard is loaded at:
- **Create mode**: `/settings/clients/new`
- **Edit mode**: `/settings/clients/{code}` (e.g. `/settings/clients/ACME`)

---

## Environment

- **Browsers to cover**: Chromium ≥ 124, Firefox ≥ 128, Safari 17+.
- **Viewports**: 1280 × 800 (desktop) + 375 × 667 (mobile — the address
  grid in WarehouseEditorModal stacks to 1 column below `sm`).
- **Backend**: any signed-in session against a dev backend with
  `/api/v1/warehouses` + `/api/v1/addresses/validate/carrier` reachable.
  Reserve a fresh warehouse code ahead of time for the create-mode flow
  (e.g. `QASMOKEWH1`).
- **Seed data**: at least one PLATFORM warehouse (`platform_seed` fine)
  and at least one CLIENT-owned warehouse on the chosen client for the
  edit-mode Edit-button coverage.
- **Local storage**: clear `clientEditorDraft:*` keys before starting a
  fresh run.

---

## SF-1. Picker renders + PLATFORM optgroup

Start at `/settings/clients/{code}` on any existing client and click the
Ship From tab in the wizard rail.

- [ ] Panel header reads **"Ship From — pick a warehouse"** with a
      short helper paragraph beneath it.
- [ ] **Add warehouse** button is visible in the top-right of the panel.
- [ ] Warehouse select is present and marked required (asterisk on the
      label).
- [ ] Dropdown opens; **Platform warehouses** optgroup lists every
      active PLATFORM warehouse, each row formatted as
      `CODE — Name · COUNTRY`.
- [ ] In **edit mode**, a second **Client-owned** optgroup shows any
      CLIENT-owned warehouses whose `ownerClientCode` matches the
      current client.

## SF-2. Selecting a warehouse populates form.shipFrom + preview card

Still on the Ship From step. Ensure no default warehouse is
pre-attached (edit a fresh client with no shipping history, or detach
its default from Settings → Warehouses first).

- [ ] Pick a warehouse from the dropdown.
- [ ] Preview card renders below the picker with the format:
      `CODE · Name` + an owner-type pill (**PLATFORM** blue, or
      **CLIENT** brown) on the right of the title row.
- [ ] The card's address block prints the warehouse's `line1`,
      `line2` (if any), `city, state, zip` and `country`; if the
      warehouse has a phone it prints on the next line.
- [ ] The Wizard footer's **Next** button becomes enabled (assuming
      Identity + Return are already valid).
- [ ] Changing selection to a different warehouse swaps every value in
      the preview card + updates the form state.

## SF-3. Edit button visibility (CLIENT-owned only)

- [ ] Pick a **CLIENT-owned** warehouse from the picker. An **Edit**
      button (pencil icon) appears in the top-right of the preview
      card. Clicking it opens `WarehouseEditorModal` pre-populated
      with that warehouse's values.
- [ ] Pick a **PLATFORM** warehouse. The Edit button is **not** shown
      (PLATFORM rows are shared and must be edited from
      Settings → Warehouses by an admin).

## SF-4. Empty state (no warehouses in the system)

Set up: a tenant with zero warehouses (a fresh dev DB, or manually
soft-delete every warehouse row).

- [ ] Picker's only option reads **"No warehouses — add one first"**.
- [ ] Below the picker, a dashed-border empty-state card says
      **"No warehouses in the system yet. Click Add warehouse above
      to create one."**.
- [ ] Wizard footer's **Next** button is disabled — the panel is
      incomplete because no warehouse is picked.

## SF-5. Add warehouse — modal opens + basic fields

Click **Add warehouse** in the Ship From panel.

- [ ] Right-side drawer modal opens with the header
      **"Add warehouse"**.
- [ ] Identity section shows Code + Name inputs; Code auto-uppercases
      as you type.
- [ ] Owner section shows two radios: **Platform (attachable to any
      client)** + **Client (private to one client)**. Switching to
      CLIENT reveals an owner-client dropdown.
- [ ] Address section shows line1 / line2 / city / state / zip /
      country / phone inputs. Country defaults to `US`.

## SF-6. Verify address — button gating

In the modal, keep the address fields empty.

- [ ] **Verify (UPS)** button is disabled and its tooltip reads
      **"Fill line 1, city, postal code and country to verify."**.
- [ ] Fill only `line1` — button stays disabled.
- [ ] Fill `city` — still disabled (missing zip).
- [ ] Fill `zip` — button flips to enabled (country is already `US`).
- [ ] Clear `line1` — button reverts to disabled.

## SF-7. Verify address — EXACT match

- [ ] Fill valid `line1` / `city` / `state` / `zip` for a known-good
      address (e.g. `1 Infinite Loop / Cupertino / CA / 95014 / US`).
- [ ] Click **Verify (UPS)**. Result panel renders in green with the
      pill reading **EXACT**.
- [ ] The card shows the carrier code + a friendly message from the
      carrier ("Address looks good." or similar).
- [ ] No **Use suggestion** button (nothing to correct).
- [ ] Dismiss button in the top-right closes the panel.

## SF-8. Verify address — CORRECTED + Use suggestion

Use an address the carrier is likely to correct (e.g. lower-case city
or a mis-typed zip like `95015` for Cupertino).

- [ ] Click **Verify (UPS)**. Result panel renders in amber with the
      pill reading **CORRECTED**.
- [ ] The card shows the carrier's suggested address beneath a
      "Suggested by UPS" heading, plus **Use suggestion** and
      **Keep mine** buttons.
- [ ] Click **Use suggestion**. Every populated field in the form
      updates from the suggestion (line1, city, state, zip typically
      change), a success toast fires, and the result panel closes.

## SF-9. Verify address — NOT_FOUND

Use an obviously-fake address (e.g. `404 Nowhere Ln / Nowhereville /
ZZ / 99999 / US`).

- [ ] Click **Verify (UPS)**. Result panel renders in **rose** with
      the pill reading **NOT_FOUND** and the carrier's message
      beneath it.
- [ ] No **Use suggestion** button (nothing suggested).

## SF-10. Stale verify result clears on edit

- [ ] Verify a valid address so an EXACT (or CORRECTED) panel shows.
- [ ] Edit any address field (add a character to line1, change city,
      etc.). The result panel disappears immediately — no need to
      re-Verify to hide it.

## SF-11. Add warehouse — create + auto-attach

- [ ] From the modal (owner = PLATFORM), fill every required field
      then click **Create warehouse**.
- [ ] Modal advances to the "Attach clients" step. Skip / Done both
      close the modal.
- [ ] Ship From picker now lists the new warehouse; it is
      auto-selected as this client's Ship From on Save.
- [ ] Save the client. Reopen it; the picker still shows the same
      warehouse as the default.

## SF-12. Edit CLIENT-owned warehouse from picker preview

- [ ] With a CLIENT-owned warehouse picked, click **Edit** on the
      preview card. Modal opens in edit mode with the header
      **"Edit warehouse {CODE}"**.
- [ ] `Code` input is read-only (grey background) — the warehouse
      code is immutable post-create.
- [ ] Change the address (e.g. update the phone), click **Save
      changes**. The modal closes, the preview card in the Ship From
      panel refreshes with the new value, and no page navigation
      happens.

## SF-13. Hidden-attached hint (edit mode)

Set up: attach 2+ warehouses to the client via
Settings → Warehouses, mark one as default.

- [ ] Reopen the client in the wizard, land on the Ship From step.
- [ ] Picker shows only the currently-default warehouse.
- [ ] Below the header, a grey hint reads **"(N warehouse(s)
      already attached to this client is/are hidden — detach from
      Settings → Warehouses to see them here.)"** with the correct
      count.

## SF-14. Cancel + close behaviour

- [ ] Open the Add warehouse modal, type into a couple of fields,
      click **Cancel**. Modal closes; no warehouse is created; the
      Ship From picker is unchanged.
- [ ] Reopen the modal. Click the **X** in the top-right — same
      behaviour as Cancel.
- [ ] Reopen the modal. Click the darkened backdrop outside the
      panel — same behaviour as Cancel.
