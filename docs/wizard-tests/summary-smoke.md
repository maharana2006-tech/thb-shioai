# Wizard Summary step + Submit cascade — QA smoke checklist

This checklist covers the manual verification path for the create-mode
Summary step of the Client wizard (`/settings/clients/new`) and its
Submit cascade (client create + carrier upserts + mapping saves + customs
profile save + warehouse attach + post-create navigate).

Automated coverage lives in
`multiship-react/src/components/ClientEditorPage.summary.test.tsx`
(18 tests: positive + negative + cross-cutting; runtime ~3s).

Route: navigate to `Settings -> Clients -> + New client`.
Prereq: at least one PLATFORM warehouse exists.

---

## Happy path

- [ ] **1. Reach Summary via wizard nav.** Fill Identity (unique code + name),
      pick a Ship From warehouse, keep return-same-as-shipFrom checked,
      add one carrier account draft (UPS SANDBOX, filled credentials, set
      as client default), add one mapping draft (shipvia P80 -> any
      shipping service), leave importer/broker unchecked. Advance through
      each step's Next button — the Summary tab pill highlights when
      landed.

- [ ] **2. Six SummaryCards render.** Identity, Ship From, Return address,
      Carrier accounts, Shipping mappings, Importer / Broker — in that
      order, each with title + status pill.

- [ ] **3. Ready pills (green).** Every completed section shows a green
      `READY` pill. The importer card shows an amber `SKIPPED` pill when
      `filled=false`.

- [ ] **4. Card body content.**
    - Identity card lists the client code (monospace, bold) + name.
    - Ship From card shows the picked warehouse label + street/city.
    - Carriers card title includes the staged count (`Carrier accounts (1)`),
      body lists carrier + account number + `(default)` badge.
    - Mapping card title includes the staged count
      (`Shipping mappings (1)`), body shows `P80 -> service #<id>`.

- [ ] **5. Submit button enabled.** Bottom-right shows
      `Submit — create client` (dark-brown fill). No tooltip; button
      clickable.

- [ ] **6. Cascade fires on click.** Watch the network tab:
    1. `POST /clients` — payload has `clientCode`
       uppercased + trimmed, `returnAddress` omitted (since return-same
       is checked).
    2. `POST /accounts` — one per carrier draft.
    3. `POST /shipping-config/rules` — one per mapping draft.
    4. `POST /clients/{code}/warehouses` (attach + default) — matches the
       picked warehouse code.
    5. `POST /clients/{code}/customs-profiles` — SKIPPED when importer
       draft `filled=false`.
    6. Redirect to `/settings/clients/{code}` with the Carriers tab
       pre-selected.

- [ ] **7. Fix / Edit buttons.** Each card's `Edit` button jumps back to
      that step; the Summary panel disappears and that step's inputs
      render pre-filled from the draft.

- [ ] **8. localStorage draft cleared.** After a successful create,
      `localStorage.getItem('clientEditorDraft:<user>')` returns `null`.

---

## Failure scenarios

- [ ] **F1. Missing carrier drafts.** Delete all carrier drafts and jump
      to Summary. Carriers card renders red `NEEDS FIX` with a bulleted
      blocker: `Add at least one carrier account.` Submit button becomes
      disabled; hover shows a tooltip listing every blocker.

- [ ] **F2. Missing mapping drafts.** Same as F1, but for mapping.

- [ ] **F3. Invalid identity.** Return to Identity, clear the name field,
      blur. Return to Summary — Identity card shows red `NEEDS FIX`.
      Submit disabled.

- [ ] **F4. Importer BUSINESS invalid.** Open the Importer step, tick
      "Fill importer/broker", pick BUSINESS, but leave name/country/
      address/city/postcode blank. Return to Summary — Importer card
      shows red `NEEDS FIX` (not amber). Blocker text mentions BUSINESS
      requirements. Submit disabled.

- [ ] **F5. Duplicate client code.** Enter a code that already exists in
      the DB. Complete every step, click Submit. `POST /clients`
      returns 409 with `errorCode: CLIENT_CODE_TAKEN`. A red toast
      surfaces the message `Client code <CODE> is already registered.`
      No downstream calls fire (no carrier / mapping / customs saves).
      Wizard stays on Summary (draft preserved).

- [ ] **F6. Best-effort carrier failure.** Prime one of the carrier
      account drafts with a known-bad credential (bad OAuth secret).
      Submit succeeds at the client level; a red toast lists the failed
      carriers with the message `Some carrier accounts failed to save.
      Add them from Carriers step.` The wizard STILL redirects to
      the created client's Carriers tab.

---

## Regression checks

- [ ] **R1. Refresh at Summary.** Reload the page mid-Summary — the draft
      restores silently (no "resume?" prompt). Every card shows the same
      status pills as before refresh.

- [ ] **R2. Two-operator isolation.** Sign in as OpA, start a create
      draft, log out. Sign in as OpB — `/settings/clients/new` should
      start clean. Sign back in as OpA — the in-progress draft is
      restored.

- [ ] **R3. Payload normalization.** POST body has uppercase client code,
      trimmed name/email/phone, `returnAddress` omitted when
      `returnSameAsShipFrom=true`.
