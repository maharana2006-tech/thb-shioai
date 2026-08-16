import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act, fireEvent, within } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 warehousespage-tests — Filters slice of /settings/warehouses.
 *
 * Scope: the toolbar Search input, the two dropdown filters (Owner + Active)
 * that live in the popover filter panel, the show/hide filter panel toggle,
 * outside-click/Escape dismissal and the Clear button. Every filter change
 * flows into `warehouseService.listWarehouses` and resets the paginator to
 * page 0. Toolbar chrome, row actions and CSV export live in sibling slices
 * (not owned by this file). Debounce in the component is 350ms
 * (see WarehousesPage.tsx L58).
 *
 * WarehousesPage does NOT have per-column filter inputs (unlike ClientsPage)
 * — the popover only exposes Owner + Status selects; the toolbar Search is
 * the only free-text filter.
 */

// ===== Service + hook mocks (top level so hoisting lands before the
// component module is imported dynamically inside the tests) =====

const listWarehouses = vi.fn()
const toggleActive = vi.fn()
const createWarehouse = vi.fn()
const updateWarehouse = vi.fn()
const deleteWarehouse = vi.fn()

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...args: unknown[]) => listWarehouses(...args),
    toggleActive: (...args: unknown[]) => toggleActive(...args),
    createWarehouse: (...args: unknown[]) => createWarehouse(...args),
    updateWarehouse: (...args: unknown[]) => updateWarehouse(...args),
    deleteWarehouse: (...args: unknown[]) => deleteWarehouse(...args),
  },
  clientWarehouseService: {
    listForClient: vi.fn().mockResolvedValue([]),
    attach: vi.fn(),
  },
}))

// Session hook — filters are role-agnostic; ADMIN is the default. The
// cross-cutting tests override the role by re-assigning `sessionValue.role`.
const sessionValue = { username: 'admin', role: 'ADMIN' as string }
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => sessionValue,
  clearAuthSession: vi.fn(),
  storeAuthSession: vi.fn(),
  syncCarrierSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(),
}))

// Notify — the filters slice never invokes toasts directly, but the load
// error path does; stub the whole surface so any accidental invocation
// doesn't blow up the test.
vi.mock('../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    apiError: vi.fn(),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// Modal children are unrelated to the filters slice and pull heavy trees;
// stub them out so render stays fast and doesn't touch other services.
vi.mock('./modals/AttachClientsModal', () => ({ default: () => null }))
vi.mock('./modals/WarehouseEditorModal', () => ({ default: () => null }))

// ===== Test helpers =====

/**
 * Non-empty first-page payload with one seed warehouse. Used as the default
 * beforeEach mock because WarehousesPage swaps the whole table body (and
 * toolbar) for a "Loading warehouses…" spinner when `loading && !rows.length`
 * (see WarehousesPage L294). Under an empty mock every filter change would
 * trigger setLoading(true) and unmount the open filter panel mid-interaction.
 * Seeding one row keeps the toolbar mounted so chained filter interactions
 * still find their controls.
 */
function seededPage() {
  return {
    data: {
      content: [{
        id: 1,
        code: 'SEED-WH',
        name: 'Seed Warehouse',
        address: { city: 'Chicago', state: 'IL', country: 'US' },
        ownerType: 'PLATFORM',
        ownerClientCode: null,
        active: true,
        attachedClientCount: 0,
        createdAt: null,
        updatedAt: null,
      }],
      pageNumber: 0,
      pageSize: 25,
      totalElements: 1,
      totalPages: 1,
    },
  }
}

/**
 * Dynamic import so the vi.mock calls above land before WarehousesPage is
 * evaluated (the component pulls warehouseService + useAppSession + notify
 * at top level).
 */
async function loadPage(): Promise<ComponentType> {
  const mod = await import('./WarehousesPage')
  return mod.default
}

/**
 * WarehousesPage calls useOutletContext<SettingsOutletContext>() to register
 * its refresh handler. In test we render a MemoryRouter with a wrapper route
 * that supplies the exact context shape SettingsLayout provides — a no-op
 * registerRefresh is enough because the filters slice never touches the
 * refresh button.
 */
function OutletShell() {
  return (
    <Outlet context={{ registerRefresh: () => {} }} />
  )
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/warehouses']}>
      <Routes>
        <Route element={<OutletShell />}>
          <Route path="/settings/warehouses" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

/** Assert that the *most recent* listWarehouses call carried the given params. */
function lastCallParams() {
  const calls = listWarehouses.mock.calls
  if (!calls.length) throw new Error('listWarehouses was never called')
  return calls[calls.length - 1][0] as Record<string, unknown>
}

beforeEach(() => {
  // Default to a non-empty page so the loading spinner in WarehousesPage
  // (which replaces the entire table + toolbar when
  // `loading && !rows.length`, see L294) never fires on filter changes.
  listWarehouses.mockReset().mockResolvedValue(seededPage())
  toggleActive.mockReset()
  createWarehouse.mockReset()
  updateWarehouse.mockReset()
  deleteWarehouse.mockReset()
  sessionValue.role = 'ADMIN'
})

afterEach(() => {
  cleanup()
  // Restore real timers in case a test flipped to fake.
  vi.useRealTimers()
})

// ===== Positive cases =====

describe('WarehousesPage · Filters · positive', () => {
  // First test eats the cold-start cost of the transformer pulling the
  // component + its mocks; give it extra headroom on Windows CI.
  it('renders the search input and reveals both filter dropdowns when opened', async () => {
    const Page = await loadPage()
    renderPage(Page)

    // Search input surfaces on the toolbar via AdvancedDataTable's search prop.
    await waitFor(() => {
      expect(screen.getByPlaceholderText('Search code, name, city…')).toBeTruthy()
    })

    // The filter panel is collapsed by default — owner/active selects are NOT
    // in the DOM until Filters is toggled.
    expect(screen.queryByLabelText(/Filter by owner type/i)).toBeNull()
    expect(screen.queryByLabelText(/Filter by active status/i)).toBeNull()

    // Toggle the panel open via the Filters button (aria-label from
    // WarehousesPage L324).
    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))

    // Both selects + Done button are all present now.
    expect(screen.getByLabelText(/Filter by owner type/i)).toBeTruthy()
    expect(screen.getByLabelText(/Filter by active status/i)).toBeTruthy()
    expect(screen.getByRole('button', { name: /^Done$/i })).toBeTruthy()
  }, 20000)

  it('typing in the search box eventually fires listWarehouses with the trimmed search param', async () => {
    const Page = await loadPage()
    renderPage(Page)
    const searchInput = await screen.findByPlaceholderText('Search code, name, city…')

    listWarehouses.mockClear()
    fireEvent.change(searchInput, { target: { value: 'chicago' } })

    // Debounce is 350ms — wait long enough for the effect + async refetch.
    await waitFor(
      () => {
        expect(listWarehouses).toHaveBeenCalled()
        expect(lastCallParams().search).toBe('chicago')
      },
      { timeout: 2000 },
    )
  })

  it('selecting Owner = PLATFORM refetches listWarehouses with ownerType=PLATFORM', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const ownerSelect = screen.getByLabelText(/Filter by owner type/i) as HTMLSelectElement

    listWarehouses.mockClear()
    fireEvent.change(ownerSelect, { target: { value: 'PLATFORM' } })

    await waitFor(() => {
      expect(listWarehouses).toHaveBeenCalled()
      expect(lastCallParams().ownerType).toBe('PLATFORM')
    })
  })

  it('selecting Owner = CLIENT refetches listWarehouses with ownerType=CLIENT', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const ownerSelect = screen.getByLabelText(/Filter by owner type/i) as HTMLSelectElement

    listWarehouses.mockClear()
    fireEvent.change(ownerSelect, { target: { value: 'CLIENT' } })

    await waitFor(() => {
      expect(listWarehouses).toHaveBeenCalled()
      expect(lastCallParams().ownerType).toBe('CLIENT')
    })
  })

  it('selecting Status = YES (Active) refetches listWarehouses with active=YES', async () => {
    // NOTE: the option values in the DOM are 'YES'/'NO' (see L359-360),
    // not 'Y'/'N'. The component forwards whatever string is selected
    // straight through as the `active` param to the service.
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const statusSelect = screen.getByLabelText(/Filter by active status/i) as HTMLSelectElement

    listWarehouses.mockClear()
    fireEvent.change(statusSelect, { target: { value: 'YES' } })

    await waitFor(() => {
      expect(listWarehouses).toHaveBeenCalled()
      expect(lastCallParams().active).toBe('YES')
    })
  })

  it('Filters button toggles the panel open and closed', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    const filtersBtn = screen.getByRole('button', { name: /^Filters$/i })
    // Closed initially — button reports aria-expanded=false.
    expect(filtersBtn.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByRole('dialog', { name: /Filter warehouses/i })).toBeNull()

    fireEvent.click(filtersBtn)
    expect(filtersBtn.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByRole('dialog', { name: /Filter warehouses/i })).toBeTruthy()

    // Toggle back.
    fireEvent.click(filtersBtn)
    expect(filtersBtn.getAttribute('aria-expanded')).toBe('false')
  })

  it('Clear button resets Owner + Status dropdowns and refetches with clean params', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    // Seed both dropdowns so activeFilterCount > 0 and the Clear button
    // surfaces in the panel footer (see WarehousesPage L365-373).
    const ownerSelect = screen.getByLabelText(/Filter by owner type/i) as HTMLSelectElement
    fireEvent.change(ownerSelect, { target: { value: 'PLATFORM' } })
    const statusSelect = screen.getByLabelText(/Filter by active status/i) as HTMLSelectElement
    fireEvent.change(statusSelect, { target: { value: 'NO' } })

    // Wait for the refetch reflecting the seeded state.
    await waitFor(() => {
      const p = lastCallParams()
      expect(p.ownerType).toBe('PLATFORM')
      expect(p.active).toBe('NO')
    }, { timeout: 2000 })

    // Clear button surfaces only when activeFilterCount > 0.
    const dialog = screen.getByRole('dialog', { name: /Filter warehouses/i })
    const clearBtn = within(dialog).getByRole('button', { name: /^Clear$/i })
    listWarehouses.mockClear()
    fireEvent.click(clearBtn)

    // Every filter is reset — the corresponding controls read empty.
    expect(ownerSelect.value).toBe('')
    expect(statusSelect.value).toBe('')

    // The next refetch carries undefined for both filter params.
    await waitFor(
      () => {
        expect(listWarehouses).toHaveBeenCalled()
        const p = lastCallParams()
        expect(p.ownerType).toBeUndefined()
        expect(p.active).toBeUndefined()
      },
      { timeout: 2000 },
    )
  })
})

// ===== Negative / edge cases =====

describe('WarehousesPage · Filters · negative + edge', () => {
  it('rapid typing collapses into ONE debounced fetch (fake timers)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: false })
    const Page = await loadPage()

    // Render inside act because state effects fire on mount under fake timers.
    await act(async () => {
      renderPage(Page)
      // Let the initial mount + first listWarehouses call settle.
      await vi.advanceTimersByTimeAsync(400)
    })
    // Recover the search input (findBy would await real timers, which are off).
    const searchInput = screen.getByPlaceholderText('Search code, name, city…') as HTMLInputElement

    listWarehouses.mockClear()

    // Fire three rapid keystrokes within one debounce window; only the final
    // value must land in listWarehouses (a single call).
    await act(async () => {
      fireEvent.change(searchInput, { target: { value: 'c' } })
      await vi.advanceTimersByTimeAsync(50)
      fireEvent.change(searchInput, { target: { value: 'ch' } })
      await vi.advanceTimersByTimeAsync(50)
      fireEvent.change(searchInput, { target: { value: 'chi' } })
    })

    // Nothing should have fired yet — debounce is 350ms per keystroke.
    expect(listWarehouses).not.toHaveBeenCalled()

    // Advance past the 350ms debounce; the effect + microtask chain then
    // hits listWarehouses exactly once with the final value.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400)
    })

    expect(listWarehouses).toHaveBeenCalledTimes(1)
    expect(lastCallParams().search).toBe('chi')
  })

  it('empty search does not send a search param (undefined, not empty string)', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    // Initial mount already refetched once with empty search. debouncedSearch
    // starts as '' and the component maps '' → undefined before hitting the
    // service (see WarehousesPage L91: `search: debouncedSearch || undefined`).
    await waitFor(() => expect(listWarehouses).toHaveBeenCalled())
    const firstCall = listWarehouses.mock.calls[0][0] as Record<string, unknown>
    expect(firstCall.search).toBeUndefined()
  })

  it('combining Owner + Status flows both params into a single listWarehouses call', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))

    // Fire both dropdowns; React batches the setState calls and the useEffect
    // + fetch fires again on each effective-value change. Wait for the final.
    fireEvent.change(screen.getByLabelText(/Filter by owner type/i), { target: { value: 'PLATFORM' } })
    fireEvent.change(screen.getByLabelText(/Filter by active status/i), { target: { value: 'YES' } })

    await waitFor(() => {
      const p = lastCallParams()
      expect(p.ownerType).toBe('PLATFORM')
      expect(p.active).toBe('YES')
    })
  })

  it('outside click closes the filter panel (filtersRef guard)', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    const filtersBtn = screen.getByRole('button', { name: /^Filters$/i })
    fireEvent.click(filtersBtn)
    expect(screen.getByRole('dialog', { name: /Filter warehouses/i })).toBeTruthy()

    // A mousedown outside the filtersRef closes the panel. document.body is
    // definitely outside; the effect listens on document (see L74).
    fireEvent.mouseDown(document.body)

    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /Filter warehouses/i })).toBeNull()
    })
  })

  it('Escape closes the filter panel', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    expect(screen.getByRole('dialog', { name: /Filter warehouses/i })).toBeTruthy()

    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /Filter warehouses/i })).toBeNull()
    })
  })
})

// ===== Cross-cutting =====

describe('WarehousesPage · Filters · cross-cutting', () => {
  it('setting a filter resets the paginator to page 0', async () => {
    // The pageIndex reset lives in an effect that fires on any filter change
    // (WarehousesPage L61-64). We assert on the page param that lands in the
    // resulting listWarehouses call — page must be 0 after the filter change.
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    fireEvent.change(screen.getByLabelText(/Filter by owner type/i), { target: { value: 'PLATFORM' } })

    await waitFor(() => {
      const p = lastCallParams()
      expect(p.ownerType).toBe('PLATFORM')
      expect(p.page).toBe(0)
    })
  })

  it('renders the filter controls for a USER role (no role gating on filters)', async () => {
    sessionValue.role = 'USER'
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    // The Filters button + panel content are identical across roles.
    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    expect(screen.getByLabelText(/Filter by owner type/i)).toBeTruthy()
    expect(screen.getByLabelText(/Filter by active status/i)).toBeTruthy()
  })

  it('renders the filter controls for a TENANT role', async () => {
    sessionValue.role = 'TENANT'
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    expect(screen.getByLabelText(/Filter by owner type/i)).toBeTruthy()
    expect(screen.getByLabelText(/Filter by active status/i)).toBeTruthy()
  })
})

// Reference to keep the linter quiet if future assertions drop `within`.
void within
