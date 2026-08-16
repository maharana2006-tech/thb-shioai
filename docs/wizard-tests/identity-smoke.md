# Client wizard — Identity step manual smoke checklist

Sprint 53 wizard-tests — companion to
`multiship-react/src/components/ClientEditorPage.identity.test.tsx`.
The unit tests cover the field validators, state transitions, and the
combobox behaviour under jsdom. This checklist is what QA (or the dev on
smoke duty) walks through in a real browser before signing off any sprint
that touches the client wizard's Identity step.

The wizard is loaded at:
- **Create mode**: `/settings/clients/new`
- **Edit mode**: `/settings/clients/{code}` (e.g. `/settings/clients/ACME`)

---

## Environment

- **Browsers to cover**: Chromium ≥ 124, Firefox ≥ 128, Safari 17+.
- **Viewports**: 1280 × 800 (desktop) + 375 × 667 (mobile, since the
  Defaults panel stacks to 1 column below `sm`).
- **Backend**: any signed-in session against a dev backend with
  `/api/v1/clients` reachable. Reserved a fresh unused client code
  ahead of time for the create-mode flow (e.g. `QASMOKE1`).
- **Local storage**: clear `clientEditorDraft:*` keys before starting
  a fresh run (DevTools → Application → Storage → Local Storage).

---

## I-1. Create mode — positive path

Start at `/settings/clients/new`.

- [ ] Wizard chrome renders with Identity as step 1 of 7.
- [ ] All four identity inputs are visible with placeholders:
      `MA1885`, `Modern Art Fabrics`, `contact@client.com`,
      `+1 555-123-4567`.
- [ ] Below the identity grid, the Defaults panel shows the header
      "Defaults" and 5 controls (Currency, Weight unit, Dimension unit,
      Origin country, Timezone).
- [ ] Type `qasmoke1` into Client code — value auto-uppercases to
      `QASMOKE1` on every keystroke.
- [ ] Move focus off the code field. Below the input, the hint
      transitions from "Checking availability..." to the default
      "Letters, digits, ..." hint (within ~1s of the debounce).
- [ ] Type any client name (e.g. `QA Smoke 1 Co`).
- [ ] Leave email and phone blank.
- [ ] Next button is enabled.
- [ ] Click Next — advances to Ship From step. Back returns to
      Identity with all values still populated.

## I-2. Create mode — Defaults panel

Still on the Identity step from I-1.

- [ ] Click each of the four native selects (Currency, Weight unit,
      Dimension unit, Origin country) — dropdown pops native browser
      chrome, options are visible, picking commits the value.
- [ ] Currency dropdown carries: USD, EUR, GBP, CAD, AUD, INR, JPY,
      CNY, MXN, AED (10 options + "— none —" first).
- [ ] Weight unit dropdown: LB, KG, OZ, G.
- [ ] Dimension unit dropdown: IN, CM, MM.
- [ ] Origin country dropdown: US, CA, MX, GB, DE, FR, IN, CN, AU, JP,
      AE, SG (each with "ISO — Full name" text).
- [ ] Selecting a value updates the field; refreshing the page (after
      the draft-persist debounce settles, ~1s) restores the choice.

## I-3. Timezone combobox

- [ ] Click the Timezone input. Placeholder is
      "Search e.g. New_York". Dropdown opens beneath, listing IANA
      zones alphabetically with a UTC offset on the right (e.g.
      "America/New_York   UTC-05:00" / -04:00 depending on DST).
- [ ] Type "New_York" — list filters to just America/New_York.
- [ ] Type "berlin" (lowercase) — list filters to Europe/Berlin
      (search is case-insensitive).
- [ ] Type "yorkyork" — no results; "No timezone matches "yorkyork"."
      empty-state div is visible under the input.
- [ ] Clear the query. Press ArrowDown — list opens; ArrowDown
      cycles through options; the highlighted row scrolls into view.
- [ ] Press Enter on a highlighted row — input commits to that zone
      and dropdown closes.
- [ ] Open the dropdown, press Escape — dropdown closes without
      committing.
- [ ] Open the dropdown, click on the ClientName input outside the
      combobox — dropdown closes (outside-click handler).
- [ ] Click the Detect-from-browser button (crosshair icon next to
      the input, `aria-label="Detect timezone from browser"`) —
      input fills with the browser's resolved IANA zone
      (e.g. `America/Los_Angeles`, `Asia/Kolkata`).

## I-4. Create mode — negative validation

Reset to `/settings/clients/new` (clear draft first).

- [ ] Click into Client code, then click away without typing. Below
      the input a red error appears: "Client code is required.".
      Next button in the wizard footer is disabled.
- [ ] Type `X` (a single character). Error changes to "Client code
      must be at least 2 characters.".
- [ ] Type `AB!` (invalid character). Error changes to "Only
      letters, digits, '-' and '_' are allowed (no spaces).".
- [ ] Clear and type a code you know exists in the DB (from another
      recent smoke, e.g. `QASMOKE1` if it was just committed). After
      ~1s the error becomes "Client code QASMOKE1 is already
      registered.". Next button stays disabled.
- [ ] Click into Client name, click away. "Name is required." shows.
- [ ] Type `Yo`. Error clears (2 chars minimum met).
- [ ] Type into Email `not-an-email`. Error "Enter a valid email
      address (name@domain).".
- [ ] Fix to `qa@example.com`. Error clears.
- [ ] Type into Phone `123`. Error "Phone needs at least 7 digits.".
- [ ] Fix to `+1 555-000-1234`. Error clears.
- [ ] Type into Phone `abc`. Error "Enter a valid phone (digits,
      spaces, +, -, (), . only).".

## I-5. Edit mode

Open an existing client — `/settings/clients/{existing-code}`
(pick one from the Clients list). Identity step is the default landing.

- [ ] Wait for the loading spinner. Then all four identity fields
      hydrate from the server: `clientCode`, `name`, `email`, `phone`
      are populated.
- [ ] Client code input carries `readonly` (grey / opacity-70 in the
      design). Try to type — nothing accepted.
- [ ] Every Defaults control shows the stored value (or "— none —" if
      the DB has NULL). For a client with all defaults set, verify
      each of the 5 selects/comboboxes shows the persisted choice.
- [ ] Identity is directly clickable from the step rail at the top
      (edit mode is free-navigate, not linear).
- [ ] Change the name to something new + click any other step in the
      rail. Return to Identity — new name is preserved in state.

## I-6. Draft persistence

Reset to `/settings/clients/new` (clear draft).

- [ ] Type Client code `DRAFT1` + Client name `Draft Co`.
- [ ] Wait ~1s for the draft-persist debounce.
- [ ] Reload the browser tab (F5).
- [ ] After the wizard remounts, Client code shows `DRAFT1` and
      Client name shows `Draft Co`. The wizard silently restored the
      draft — no prompt is shown.
- [ ] Navigate to Ship From, then back to Clients list, then back to
      `/settings/clients/new` — the draft is still restored (draft
      key is namespaced per operator via `multiship_user`).

## I-7. Cross-cutting — payload shape

- [ ] From I-1's successful create walk, open DevTools → Network.
      Complete every wizard step and click Submit.
- [ ] Inspect the `POST /api/v1/clients` payload. Verify:
      - `clientCode` is upper-cased + trimmed.
      - `name` is trimmed (no leading/trailing whitespace).
      - `email` / `phone` are trimmed empty strings or valid values.
      - `defaultCurrency` / `defaultWeightUnit` / `defaultDimUnit` /
        `defaultOriginCountry` are upper-cased and OMITTED (undefined)
        rather than empty-string when the operator didn't pick.
      - `timezone` is sent as-is (IANA is case-sensitive; the picker
        commits canonical values).
- [ ] Backend responds 200. Toast "Client {code} created." fires.

## I-8. Regressions to watch

- [ ] After typing a code and moving focus off, no console errors or
      React warnings fire in DevTools. (Live-check race conditions
      historically surfaced here.)
- [ ] With the Timezone combobox open, tabbing away closes it (the
      inputRef.blur() in commit() shouldn't strand it open).
- [ ] After committing a Timezone pick, clicking the input again
      reopens the dropdown with the empty query — the last commit
      shouldn't hang around as a filter.
- [ ] In edit mode, changing Defaults and clicking away from the page
      (e.g. to the Clients list) doesn't lose the edits silently —
      either persist happens or the operator gets a confirm prompt.
      (Current behaviour: edits require an explicit save on a later
      step; verify the design's intent still matches.)
