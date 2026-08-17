# Client wizard — Return address step manual smoke checklist

Sprint 53 wizard-tests — companion to
`multiship-react/src/components/ClientEditorPage.return.test.tsx`.
The unit tests cover the "Same as Ship From" toggle, address-block
show/hide, validator messages, edit-mode hydration and the outbound
payload shape under jsdom. This checklist is what QA (or the dev on
smoke duty) walks through in a real browser before signing off any
sprint that touches the Return address step.

The wizard is loaded at:
- **Create mode**: `/settings/clients/new` — walk through Identity +
  Ship From first (or land here mid-wizard from a persisted draft).
- **Edit mode**: `/settings/clients/{code}` — click the "Return
  address" tab in the wizard step rail.

---

## Environment

- **Browsers to cover**: Chromium ≥ 124, Firefox ≥ 128, Safari 17+.
- **Viewports**: 1280 × 800 (desktop) + 375 × 667 (mobile, since the
  3-column AddressGrid collapses to 1 column below `sm`).
- **Backend**: any signed-in session against a dev backend with
  `/api/v1/clients` reachable. Reserved a fresh unused client code
  ahead of time for the create-mode flow (e.g. `QARETURN1`).
- **Local storage**: clear `clientEditorDraft:*` keys before starting
  a fresh run (DevTools → Application → Storage → Local Storage).

---

## R-1. Create mode — same-as-ship-from default

Start at `/settings/clients/new`. Fill Identity + Ship From with valid
values, then click Next until you're on step 3 "Return address".

- [ ] Wizard step rail shows "Return address" as step 3 of 7 (active,
      dark filled).
- [ ] Panel header reads "Return address" with subtitle "Where
      undeliverable parcels come back to.".
- [ ] Top-right of the panel carries a checkbox labelled
      "Same as Ship From". It is checked by default.
- [ ] With the checkbox checked, the address grid is NOT visible —
      only a dashed banner "Returns use the Ship From address." is
      shown.
- [ ] Next button in the wizard footer is enabled (no address fields
      required in same-as-ship-from mode).

## R-2. Create mode — un-check reveals editable grid

Still on step 3 from R-1.

- [ ] Un-check the "Same as Ship From" checkbox.
- [ ] The dashed collapsed banner disappears.
- [ ] The 3-column AddressGrid renders with all fields empty except
      Country (pre-filled `US`):
      Attention/company, Street address, Suite/unit, City, State, Zip,
      Country, Phone.
- [ ] Placeholders visible: `Warehouse / contact name`,
      `123 Industrial Blvd`, `Suite 400`, `Chicago`, `IL`, `60601`,
      `+1 555-123-4567`.
- [ ] Country field is a searchable combobox — clicking it opens a
      country list with search input.
- [ ] Next button is now disabled — required fields are empty.
- [ ] Re-check the checkbox — the grid disappears and Next re-enables.

## R-3. Create mode — negative validation (same=false)

Uncheck "Same as Ship From" so the grid is visible.

- [ ] Click into Attention/company, click away. Below the input a
      red error appears: "Contact / company is required.".
- [ ] Type `A` (single character). Error changes to "Contact /
      company must be at least 2 characters.".
- [ ] Type `Return Desk`. Error clears.
- [ ] Click into Street address, click away. "Street address is
      required." shows. Type `500 Return Rd`. Error clears.
- [ ] Click into City, away. "City is required." shows. Type
      `Boston`. Error clears.
- [ ] Click into State, away. "State / region is required." shows.
      Type `MA`. Error clears.
- [ ] Click into Zip, type `BADZIP`, click away. Error
      "Postal code doesn't match the US format." shows (country is
      preset US).
- [ ] Fix Zip to `02110`. Error clears.
- [ ] Type into Phone `not-a-phone!!`. Error "Enter a valid phone
      (digits, spaces, +, -, (), . only).". Fix to `+1 555-123-4567`.
      Error clears.
- [ ] Country combobox: search for a non-existing country like `zzz`.
      Dropdown shows "No match".
- [ ] Country combobox: pick "United Kingdom (GB)". Country field
      now stores `GB`. Zip `02110` (US format) now surfaces
      "Postal code doesn't match the GB format." — validator
      re-runs on country change.
- [ ] Fix Zip to `SW1A 1AA` (valid GB format). Error clears.
- [ ] Change country back to US. Fix Zip to `02110`. All errors
      clear.
- [ ] Next button is now enabled.

## R-4. Create mode — country upper-case + trim on save

Complete every step with a valid Return address (same=false, all
fields filled). Click Submit.

- [ ] Open DevTools → Network. Locate `POST /api/v1/clients` (or the
      per-step save, depending on the wizard's persistence model).
- [ ] Inspect the payload:
      - `returnSameAsShipFrom`: `false`.
      - `returnAddress.country`: exact upper-case `US` (never lower
        or mixed).
      - Every string field in `returnAddress` is trimmed (no leading
        or trailing whitespace).
- [ ] Repeat with the "Same as Ship From" checkbox checked. Payload
      now carries `returnSameAsShipFrom: true` and
      `returnAddress` is either `undefined` (omitted) or `null` —
      backend must accept both.

## R-5. Edit mode — hydration

Open an existing client — `/settings/clients/{code}`.

- [ ] Wait for the loading spinner. Click the "Return address" tab
      in the step rail.
- [ ] For a client persisted with `returnSameAsShipFrom = true`:
      the checkbox is checked; the collapsed banner is visible.
- [ ] For a client persisted with `returnSameAsShipFrom = false` +
      a real return address: the checkbox is unchecked, and every
      field (Attention, Street, Suite, City, State, Zip, Country,
      Phone) shows the persisted value.
- [ ] Click the "Save changes" button in the header. Modal / toast
      confirms the save. Network shows `PUT /api/v1/clients/{code}`
      with the payload unchanged.

## R-6. Edit mode — toggle flip persistence

Still in edit mode from R-5, on the Return address tab.

- [ ] Un-check the checkbox on a client that started with same=true.
      The address grid appears with any previously stored return
      fields (or empty defaults if none).
- [ ] Fill in every required field. Click "Save changes".
- [ ] Reload the page. Return step now hydrates with same=false and
      the just-saved values.
- [ ] Re-check the checkbox. Grid disappears. Click "Save changes".
- [ ] Reload. Same=true is now persisted; the grid is hidden.

## R-7. Cross-cutting — step rail state

- [ ] In create mode after successfully populating the Return step,
      the step-3 pill in the rail is green + carries a check mark
      (done). Same=true counts as valid — no address needed.
- [ ] In create mode after unchecking + leaving a required field
      empty, then moving to a later step, the step-3 pill goes rose
      (invalid). Return to step 3 — errors visible on the touched
      fields; the step-3 pill returns to blue (active).
- [ ] The "Save changes" header button is ONLY visible in edit mode
      on the Identity / Ship From / Return tabs — not on Carriers,
      Mapping, Importer/Broker, or Summary tabs.

## R-8. Regressions to watch

- [ ] After typing lower-case into any address free-text field
      (name, city, state, phone), the value is preserved as typed —
      only `country` uppercases. Zip preserves case as typed (GB
      postcodes are traditionally upper-case but the backend accepts
      either).
- [ ] Country combobox: opening the dropdown, tabbing away
      (no selection) closes it without changing the value.
- [ ] Toggle flip mid-wizard: check → uncheck → check preserves the
      draft address in state (the fields re-appear with the same
      values when unchecked again).
- [ ] After the wizard is created (Submit succeeds), navigating to
      the edit URL for that new client and jumping straight to the
      Return tab shows the persisted state (either the collapsed
      banner or the hydrated fields), not the create-mode default.
- [ ] The Country field's cap ("cap set by ...") hint only appears
      once at least one carrier account is staged (create mode) or
      persisted (edit mode). With zero carriers, no per-carrier cap
      text shows.
