# WarehousesPage — Filters slice manual smoke

Companion to `multiship-react/src/components/WarehousesPage.filters.test.tsx`.
Automated tests cover the mocked-network paths; this checklist is the
in-browser smoke that also validates the visual chrome and real backend
wiring at `/settings/warehouses`.

## Preconditions

- Log in as an ADMIN with at least 3 warehouses seeded (need at least one
  PLATFORM-owned + one CLIENT-owned + one INACTIVE to exercise every
  filter option).
- Navigate to `/settings/warehouses`.

## Baseline chrome

- [ ] Search input placeholder reads "Search code, name, city…".
- [ ] Filters button (funnel icon) sits to the right of Search. Badge is
      absent when no filters are applied.
- [ ] Clicking Filters opens a popover dialog
      `aria-label="Filter warehouses"` containing the Owner + Status
      dropdowns plus a Done button. (Unlike ClientsPage, there are NO
      per-column contains inputs — Search is the only free-text filter.)

## Search + debounce

- [ ] Typing a query in the toolbar Search fires a single network call
      ~350ms after the last keystroke (verify in DevTools Network:
      `/warehouses?...search=...`).
- [ ] Clearing the input fires a follow-up request WITHOUT a `search`
      query param.
- [ ] Trailing/leading whitespace is trimmed before the request lands
      (see WarehousesPage.tsx L58 + warehouseService.ts L75).

## Dropdown filters

- [ ] Owner → Platform-owned: request carries `ownerType=PLATFORM`, table
      narrows to platform warehouses.
- [ ] Owner → Client-owned: request carries `ownerType=CLIENT`.
- [ ] Owner → Any owner: request omits `ownerType`.
- [ ] Status → Active: request carries `active=YES`.
- [ ] Status → Inactive: request carries `active=NO`.
- [ ] Status → All statuses: request omits `active`.

## Panel toggle + Clear

- [ ] Filters badge appears (integer count) once any dropdown is set
      (search text does NOT count toward the badge — only Owner + Status).
- [ ] Clicking Filters again toggles the panel closed.
- [ ] Escape closes the panel.
- [ ] Clicking outside the panel closes it (verify with a click on
      the empty toolbar area or a table row).
- [ ] Clear button surfaces only when at least one dropdown is set;
      clicking it wipes Owner + Status AND fires a clean refetch (no
      `ownerType` / `active` query params). Note: Clear does NOT wipe
      the toolbar Search input — that stays independent.

## Cross-cutting

- [ ] Navigate to page 2 of the table, then change any filter — the
      table snaps back to page 1 (verify by watching the pagination
      indicator + the network request's `page=0`).
- [ ] Log in as a USER (non-admin): the entire filter chrome renders
      identically. There is no role gating on filter controls, only on
      row Delete + the Add Warehouse button (owned by another slice).
- [ ] Log in as a TENANT: same as USER — filters visible.

## Known caveats

- On a genuinely empty result set, changing any filter while the panel
  is open causes WarehousesPage to temporarily replace the entire table +
  toolbar with "Loading warehouses…" (see `WarehousesPage.tsx` L294:
  `loading && !rows.length`). The panel remounts after the fetch settles,
  but the interaction feels "the panel closed itself". This is a UX
  quirk to file if it hurts real operators — not a bug the filters slice
  can fix in isolation.
- The Status dropdown values are `YES` / `NO` (strings), not `Y` / `N` or
  booleans. WarehousesPage forwards them verbatim to the service; the
  backend must accept `active=YES|NO`.
