# /settings/carriers — Per-client attach + client-default smoke

Owner slice: **client-attach**. Sibling slices (drawer, verify, delete, filters,
row actions, empty state) are covered by other agents' PRs — do **not** repeat
their steps here.

## Preconditions

- Logged in as an ADMIN user.
- At least two active clients in the client book (e.g. `ACME`, `WIDGETS`).
- At least three carrier accounts in the account book:
  1. one **platform** account (customerNo blank).
  2. one **client** account attached to `ACME` that is `complete && active` but
     **not** currently the client default.
  3. one **client** account attached to `WIDGETS` that already **is** the client
     default (renders with the filled amber star).

## Steps

1. Open `/settings/carriers`. The health-strip cards render, the account table
   lists all three accounts, and the caption reads
   `Showing 3 of 3 accounts`.
2. Click the toolbar **Filters** button. The popover opens and the **Client**
   dropdown is populated with `ACME — <name>` and `WIDGETS — <name>` (plus
   `All clients`).
3. Pick `ACME`. The popover stays open, and the table caption drops to
   `Showing 1 of 3 accounts`. The single visible row is the ACME account.
4. Click the **Done** button; the popover closes and the filter chip count on
   the button reads `1`.
5. Open the popover again and switch **Client** back to `All clients`. Caption
   returns to `Showing 3 of 3 accounts`.
6. In the popover, flip **Client default** to `Default`. Only the WIDGETS row
   (filled star) is visible. Set it back to `Any`.
7. On the ACME row, click the small **empty** star icon in the Carrier cell
   (`aria-label` starts with `Make this ACME's default account`). A success
   toast appears: `<accountNumber> is now ACME's default account.`
8. The row's star flips to the filled amber `Default account for ACME` icon,
   and the star button no longer offers to make it default (already default).
9. Refresh the page (or click the layout **Refresh** button in the settings
   header). The star state persists across reload.

## Negative

- Attempt step 7 on a client row that is `!active` or `!complete`. The star
  button is **not rendered** (the cell shows either an empty spacer or the
  faded-grey "was default — inactive" star for previously-default rows). No
  network call fires.
- Force `POST /carrier-accounts/:id/client-default` to reject (500). A red
  error toast surfaces (`Failed to set the client default.`), the star does
  **not** flip, and the row stays in its previous state.
- Force `GET /clients` to reject (500). The account list still loads; the
  **Client** dropdown in the filter popover contains only the `All clients`
  option. No blocking error banner appears (log-only failure).

## Cross-cutting

- **ADMIN** sees the star button, the Client filter dropdown, and the Default
  filter dropdown.
- **USER / TENANT** roles are backend-scoped: they may not see other clients'
  accounts at all. This UI slice does not gate the star button by role — the
  backend `POST /carrier-accounts/:id/client-default` is the enforcement point.

## Notes for testers

- The star button is the **only** UX shape for "mark this account as the
  client's default" — there is no multi-client picker modal in the current
  build. The `setClientDefault(accountId)` API takes the account's *linked*
  `customerNo` implicitly; there is no explicit `clientCode` argument.
- Platform rows (`customerNo` blank) render a **spacer**, never a star.
