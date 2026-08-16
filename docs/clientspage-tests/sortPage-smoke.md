# ClientsPage — Sorting + Pagination · Manual Smoke

Companion doc to `multiship-react/src/components/ClientsPage.sortPage.test.tsx`.
Run these steps against a preview build with ~60 real clients seeded so that
pagination has multiple pages to walk.

Precondition: signed-in operator with role ADMIN or USER on a tenant that
owns at least 30 clients. Rebuild with `npm run build && npm run preview` or
point staging at your branch.

## 1. Default sort on first load

1. Navigate to `/settings/clients`.
2. Open DevTools → Network → filter by `clients`.
3. Refresh the page.

Expected — first `GET /api/v1/clients?...` query string carries:

- `sortBy=code`
- `sortDirection=ASC`
- `page=0`
- `size=25`

Rows are ordered A→Z by client code.

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
2. Type `acm` into the toolbar search box.

Expected — after the 350ms debounce a fetch fires with
`search=acm&page=0`; the page indicator resets to `1 / X` for the new
filtered result set.

## 7. Zero-results view

1. Type a nonsense search (`zzzzzz`).

Expected — after debounce the page indicator reads `1 / 1`, the caption
reads `Showing 0 of 0 clients`, the body shows
`No clients match the current filters.`, and the Code/Name headers remain
visible and sortable.

## 8. Role parity (spot check)

1. Log in as a plain USER on the same tenant.
2. Repeat steps 1, 4 (walk pages), 5 (page size).

Expected — sort headers, pagination cluster, and page-size picker all
present and functional identically to ADMIN. Row-level Delete stays hidden,
but table controls do not.

## Regressions to watch

- `pageIndex` must reset to 0 on **any** filter/sort/page-size change; if
  Next→Next→"change page size" leaves the indicator on page 3, the effect
  on line 74 of ClientsPage.tsx has drifted.
- The `Math.max(totalPages ?? 1, 1)` clamp keeps the indicator at `1 / 1`
  for empty results; a bare `0 / 0` indicator means the clamp was dropped.
- Server-side sort/pagination is opt-in via `manualSorting` +
  `manualPagination` on AdvancedDataTable. If clicking Next just re-slices
  the current page instead of firing a new fetch, one of those props was
  dropped.
