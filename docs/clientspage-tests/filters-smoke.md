# ClientsPage — Filters slice manual smoke

Companion to `multiship-react/src/components/ClientsPage.filters.test.tsx`.
Automated tests cover the mocked-network paths; this checklist is the
in-browser smoke that also validates the visual chrome and real backend
wiring at `/settings/clients`.

## Preconditions

- Log in as an ADMIN with at least 3 clients seeded (need at least one
  ACTIVE + one INACTIVE + one with a UPS carrier account to exercise
  every filter).
- Navigate to `/settings/clients`.

## Baseline chrome

- [ ] Search input placeholder reads "Search code, name, city…".
- [ ] Filters button (funnel icon) sits to the right of Search. Badge is
      absent when no filters are applied.
- [ ] Clicking Filters opens a popover dialog `aria-label="Filter clients"`
      containing 3 column-contains inputs + 3 dropdowns (Status /
      Carrier / Orders) + a Done button.

## Search + debounce

- [ ] Typing a query in the toolbar Search fires a single network call
      ~350ms after the last keystroke (verify in DevTools Network:
      `/clients?...search=...`).
- [ ] Clearing the input fires a follow-up request WITHOUT a `search`
      query param.

## Dropdown filters

- [ ] Status → Active: request carries `status=ACTIVE`, table narrows.
- [ ] Status → Inactive: request carries `status=INACTIVE`.
- [ ] Carrier → Has UPS: request carries `carrier=UPS`.
- [ ] Orders → With orders: request carries `hasOrders=YES`; With orders
      → No orders switches to `hasOrders=NO`.

## Column filters (debounced)

- [ ] Code-contains input → request carries `code=<value>` ~350ms after
      last keystroke.
- [ ] Name-contains input → request carries `name=<value>`.
- [ ] City-contains input → request carries `city=<value>`.

## Panel toggle + Clear

- [ ] Filters badge appears (integer count) once any filter is set.
- [ ] Clicking Filters again toggles the panel closed.
- [ ] Escape closes the panel.
- [ ] Clicking outside the panel closes it (verify with a click on
      the empty toolbar area or a table row).
- [ ] Clear button surfaces only when at least one filter is set;
      clicking it wipes every dropdown + column input AND fires a
      clean refetch (no filter query params).

## Cross-cutting

- [ ] Navigate to page 2 of the table, then change any filter — the
      table snaps back to page 1 (verify by watching the pagination
      indicator + the network request's `page=0`).
- [ ] Log in as a USER (non-admin): the entire filter chrome renders
      identically. There is no role gating on filter controls, only on
      row Delete + the Add Client button (owned by another slice).
- [ ] Log in as a TENANT: same as USER — filters visible.

## Known caveats

- On a genuinely empty result set, changing any filter while the panel
  is open causes ClientsPage to temporarily replace the entire table +
  toolbar with "Loading clients…" (see `ClientsPage.tsx` L407:
  `loading && !clients.length`). The panel remounts after the fetch
  settles, but the interaction feels "the panel closed itself". This is
  a UX quirk to file if it hurts real operators — not a bug the filters
  slice can fix in isolation.
