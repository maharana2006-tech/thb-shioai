# WarehousesPage — Sorting + Pagination · Manual Smoke

Companion doc to `multiship-react/src/components/WarehousesPage.sortPage.test.tsx`.
Run these steps against a preview build with ~60 real warehouses seeded so that
pagination has multiple pages to walk.

Precondition: signed-in operator with role ADMIN or USER on a tenant that
owns at least 30 warehouses. Rebuild with `npm run build && npm run preview`
or point staging at your branch.

## 1. Default sort on first load

1. Navigate to `/settings/warehouses`.
2. Open DevTools → Network → filter by `warehouses`.
3. Refresh the page.

Expected — first `GET /api/v1/warehouses?...` query string carries:

- `sortBy=code`
- `sortDirection=ASC`
- `page=0`
- `size=25`

Rows are ordered A→Z by warehouse code.

## 2. Toggle sort direction on the same column

1. Click the **Code** header.

Expected — new fetch fires with `sortBy=code&sortDirection=DESC`; visible
rows re-order Z→A; the header chevron flips from up to down.

2. Click **Code** again.

Expected — direction returns to `ASC` (or clears to no-sort → next fetch
falls back to default `code, ASC`; either is acceptable).

## 3. Switch sort column

1. Click the **Name** header.

Expected — fetch fires with `sortBy=name&sortDirection=ASC`; the Code
header's chevron greys out; rows re-order A→Z by name.

Note: the Country, Attached, Status and Owner columns are non-sortable
(`enableSorting: false` in the column defs). Clicking their headers should
not fire a new list request and should not draw a sort chevron.

## 4. Prev/Next pagination

1. In the toolbar you should see the indicator `1 / N` where N ≥ 2 (bump the
   seed count if not).
2. Click the right chevron **›**.

Expected — new fetch with `page=1`; indicator updates to `2 / N`; the
Prev button `‹` becomes enabled.

3. Click the left chevron **‹**.

Expected — new fetch with `page=0`; indicator back to `1 / N`; Prev button
disables again.

4. Verify Next `›` disables on the last page (walk forward until N/N).

## 5. Rows-per-page

1. Change the "25 / page" dropdown in the toolbar to `50 / page`.

Expected — fetch fires with `size=50` **and** `page=0` (the effect resets
paging on `pageSize` change).

2. Repeat with 10 and 100 to confirm the same behaviour.

## 6. Filter → page reset

1. Advance to page 2 using **›**.
2. Type `wh` into the toolbar search box.

Expected — after the 350ms debounce a fetch fires with
`search=wh&page=0`; the page indicator resets to `1 / X` for the new
filtered result set.

3. Open the Filters popover and pick `Owner = Platform-owned`.

Expected — fetch fires with `ownerType=PLATFORM&page=0`; the indicator
snaps back to `1 / X`.

4. Repeat step 3 with `Status = Active` (sends `active=YES`) to confirm
   both filters also trigger the page-reset effect.

## 7. Zero-results view

1. Type a nonsense search (`zzzzzz`).

Expected — after debounce the page indicator reads `1 / 1`, the caption
reads `Showing 0 of 0 warehouses`, the body shows
`No warehouses match the current filters.`, and the Code/Name headers
remain visible and sortable.

## 8. Role parity (spot check)

1. Log in as a plain USER on the same tenant.
2. Repeat steps 1, 4 (walk pages), 5 (page size).

Expected — sort headers, pagination cluster, and page-size picker all
present and functional identically to ADMIN. Row-level Delete stays hidden
in the row kebab menu, but table controls do not.

3. Repeat with a TENANT role to confirm parity across all three roles.

## Regressions to watch

- `pageIndex` must reset to 0 on **any** filter/sort/page-size change; if
  Next→Next→"change page size" leaves the indicator on page 3, the effect
  in WarehousesPage.tsx that watches
  `[debouncedSearch, ownerFilter, activeFilter, sorting, pageSize]` has
  drifted.
- The `Math.max(totalPages ?? 1, 1)` clamp keeps the indicator at `1 / 1`
  for empty results; a bare `0 / 0` indicator means the clamp was dropped.
- Server-side sort/pagination is opt-in via `manualSorting` +
  `manualPagination` on AdvancedDataTable. If clicking Next just re-slices
  the current page instead of firing a new fetch, one of those props was
  dropped.
- The Country / Attached / Status / Owner columns intentionally opt out of
  sorting (`enableSorting: false`). If clicking their headers starts firing
  new list requests, one of those flags was removed.
