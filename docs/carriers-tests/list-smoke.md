# /settings/carriers — List + tiles + filters slice manual smoke

Companion to `multiship-react/src/components/CarrierConnections.list.test.tsx`.
Automated tests cover the mocked-network paths; this checklist is the
in-browser smoke that also validates the visual chrome and real backend
wiring at `/settings/carriers`.

## Preconditions

- Log in as an ADMIN with at least 4 carrier accounts seeded (need at
  least one PLATFORM + one CLIENT-owned and one row per CarrierCode
  UPS / FEDEX / USPS / DHL so every carrier chip is exercised).
- One of the rows must have `verified=true`, one `verified=false`, one
  `verified=null` (never checked), and one `active=false`, to exercise
  every status chip variant.
- Navigate to `/settings/carriers`.

## Baseline chrome

- [ ] Health strip renders 4 tiles: Ready to ship, Unverified, Platform
      accounts, Client accounts. The Ready tile format is
      `<ready>/<total>` (e.g. `3/4`).
- [ ] Toolbar row has:
  - Search input with placeholder `Search account #, name, client…`.
  - Filters button (funnel icon). Badge is absent when no filters set.
  - Columns menu button (Columns).
  - Add Account button (primary dark background).
- [ ] Table headers are: Carrier, Account name, Type, Environment,
      Purpose, Clearance, Status, Usage, Actions.
- [ ] Table caption below the body reads
      `Showing <visible> of <total> account(s)`.

## Per-row carrier tile

- [ ] Every row shows a rounded logo badge (via `CarrierLogo`) plus the
      carrier's human name (`formatCarrierName` — e.g. FEDEX → FedEx)
      and the account number below it.
- [ ] The star icon at the left of the row:
  - Renders as a solid amber star when `clientDefault && active` and
      the row is a client account.
  - Renders as a muted grey star when `clientDefault && !active`.
  - Renders as a clickable outline star (to promote) for complete +
      active client accounts that are NOT currently default.
  - Renders as an empty spacer on platform-owned accounts.

## Status chip per row

- [ ] `complete=false` → amber `Incomplete` chip.
- [ ] `active=false` → grey `Inactive` chip (takes precedence over
      verification state).
- [ ] `verified=true` → emerald `Verified` chip.
- [ ] `verified=false` → rose `Check failed` chip.
- [ ] `verified=null` → amber `⚠ Unverified` chip.
- [ ] The Active toggle switch in the Status column flips the account
      via `accountRefService.toggleActive` and fires a success toast
      that reads "Account activated." / "Account deactivated." — the
      table refetches immediately after.

## Search

- [ ] Typing in the toolbar Search narrows the table in real time (no
      network call — filtering is done client-side over the loaded
      account book).
- [ ] The caption count updates to match the visible rows.
- [ ] Leading/trailing whitespace does not break the match — the
      component lower-cases + trims the query before comparing against
      `accountNumber + accountName + customerNo + carrierCode`.
- [ ] No-match state renders the message
      `No accounts match the current filters.` in the empty table body.

## Filters popover

- [ ] Filters button toggles a dialog labelled `Filter accounts` with
      seven controls: Account type, Carrier, Client, Status,
      Environment, Verification, Client default.
- [ ] Carrier options are UPS / FedEx / USPS (DHL missing from the
      popover options — the drawer still supports DHL for creation,
      but the filter list stops at USPS. File a follow-up if operators
      request a DHL filter.)
- [ ] Setting Carrier=UPS narrows visible rows to UPS-only. Repeats
      for FedEx and USPS.
- [ ] Setting Status=Active shows only rows with `active=true`;
      Status=Inactive shows only `active=false`.
- [ ] Setting Verification=Verified shows only `verified=true` rows;
      Verification=Unverified shows the failed + never-checked rows.
- [ ] Filters button badge shows the integer count of currently-set
      dropdowns (search text does NOT count toward the badge).
- [ ] Clear button surfaces only when at least one dropdown is set;
      clicking it wipes every dropdown and repaints the full table.
      Search input is left as-is.
- [ ] Escape closes the popover.
- [ ] Clicking outside the popover closes it.

## Error paths

- [ ] Temporarily stop the backend and reload — the page fires
      `notify.apiError` with `Failed to load the account book.` and the
      table renders the empty-state message.
- [ ] The secondary `listClients` call for the Client filter dropdown
      is allowed to fail silently — it logs to the console but does
      NOT surface a toast (Sprint 51 FE-L3 decision).

## Cross-cutting

- [ ] Log in as USER (non-admin): entire list + filter chrome renders
      identically. `accountRefService.listAccounts` is called with the
      same shape (no per-role query parameter — the backend filters by
      the caller's session).
- [ ] Log in as TENANT: same as USER. The list only contains accounts
      belonging to the tenant's scope; the frontend does not do any
      additional filtering.
- [ ] Add Account drawer opens with 4 carrier tiles (UPS / FedEx /
      USPS / DHL Express) in a `radiogroup` — DHL is included here
      even though the filter popover lists only UPS / FedEx / USPS.

## Known caveats

- The Filters popover Carrier select omits DHL as an option. If DHL
  accounts are seeded they are still visible in the table (there's no
  active filter) but users cannot narrow to DHL-only without a code
  change. File an issue if operators need it.
- The health-strip counters compute against the raw `accounts` array,
  NOT the filtered `visibleAccounts`. If a filter is applied the tiles
  still show global totals — verify this matches product intent.
