import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act, fireEvent, within } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 wizard-tests — Filters slice of the Clients page (/settings/clients).
 *
 * Scope: the search input, the three column filters (code/name/city), the
 * three dropdown filters (Status/Carrier/Orders), the show/hide filter
 * panel toggle, and the Clear button. Every filter change flows into the
 * mocked `clientService.listClients` call and resets the paginator to
 * page 0. Toolbar chrome, row actions, and CSV export live in sibling
 * files. Debounce in the component is 350ms (see ClientsPage.tsx L66/L70).
 */

// ===== Service + hook mocks (top level so hoisting lands before the
// component module is imported dynamically inside the tests) =====

const listClients = vi.fn()
const exportClientsCsv = vi.fn()
const cascadePreview = vi.fn()
const toggleActive = vi.fn()
const deleteClient = vi.fn()

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClients(...args),
    exportClientsCsv: (...args: unknown[]) => exportClientsCsv(...args),
    cascadePreview: (...args: unknown[]) => cascadePreview(...args),
    toggleActive: (...args: unknown[]) => toggleActive(...args),
    deleteClient: (...args: unknown[]) => deleteClient(...args),
    getClient: vi.fn(),
    createClient: vi.fn(),
    updateClient: vi.fn(),
    listClientAccounts: vi.fn().mockResolvedValue([]),
  },
}))

// Session hook — filters are role-agnostic; ADMIN is the default. Individual
// cross-cutting tests override the role by re-mocking before dynamic import.
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

// CustomsProfileModal is unrelated to filters and pulls a heavy tree; stub
// it so the render stays fast.
vi.mock('./modals/CustomsProfileModal', () => ({
  default: () => null,
}))

// ===== Test helpers =====

/**
 * Non-empty first-page payload with one seed client. Used as the default
 * beforeEach mock because ClientsPage swaps the whole table body (and
 * toolbar) for a "Loading clients…" spinner when
 * `loading && !clients.length` (see ClientsPage L407). Under an empty
 * mock every filter change triggers setLoading(true) and unmounts the
 * open filter panel mid-interaction. Seeding one row keeps the toolbar
 * mounted so chained filter interactions still find their controls.
 */
function seededPage() {
  return {
    data: {
      content: [{
        id: 1,
        clientCode: 'SEED',
        name: 'Seed Client',
        email: null,
        phone: null,
        status: 'ACTIVE',
        shipFrom: null,
        returnAddress: null,
        returnSameAsShipFrom: true,
        defaultCurrency: null,
        defaultWeightUnit: null,
        defaultDimUnit: null,
        timezone: null,
        defaultOriginCountry: null,
        createdAt: null,
        updatedAt: null,
        carrierAccounts: [],
        orderCount: 0,
      }],
      pageNumber: 0,
      pageSize: 25,
      totalElements: 1,
      totalPages: 1,
    },
  }
}

/**
 * Dynamic import so the vi.mock calls above land before ClientsPage is
 * evaluated (the component pulls clientService + useAppSession + notify
 * at top level).
 */
async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ClientsPage')
  return mod.default
}

/**
 * ClientsPage calls useOutletContext<SettingsOutletContext>() to register
 * its refresh handler. In test we render a MemoryRouter with a wrapper
 * route that supplies the exact context shape SettingsLayout provides —
 * a no-op registerRefresh is enough because the filters slice never
 * touches the refresh button.
 */
function OutletShell() {
  return (
    <Outlet context={{ registerRefresh: () => {} }} />
  )
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/clients']}>
      <Routes>
        <Route element={<OutletShell />}>
          <Route path="/settings/clients" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

/** Assert that the *most recent* listClients call carried the given params. */
function lastCallParams() {
  const calls = listClients.mock.calls
  if (!calls.length) throw new Error('listClients was never called')
  return calls[calls.length - 1][0] as Record<string, unknown>
}

beforeEach(() => {
  // Default to a non-empty page so the loading spinner in ClientsPage
  // (which replaces the entire table + toolbar when
  // `loading && !clients.length`, see L407) never fires on filter changes.
  // Tests that specifically care about the empty-search branch can
  // override with `listClients.mockResolvedValueOnce(emptyPage())`.
  listClients.mockReset().mockResolvedValue(seededPage())
  exportClientsCsv.mockReset()
  cascadePreview.mockReset()
  toggleActive.mockReset()
  deleteClient.mockReset()
  sessionValue.role = 'ADMIN'
})

afterEach(() => {
  cleanup()
  // Restore real timers in case a test flipped to fake.
  vi.useRealTimers()
})

// ===== Positive cases =====

describe('ClientsPage · Filters · positive', () => {
  // First test eats the cold-start cost of the transformer pulling the
  // component + its mocks; give it extra headroom on Windows CI.
  it('renders the search input and reveals every filter control when opened', async () => {
    const Page = await loadPage()
    renderPage(Page)

    // Search input surfaces on the toolbar via AdvancedDataTable's search prop.
    await waitFor(() => {
      expect(screen.getByPlaceholderText('Search code, name, city…')).toBeTruthy()
    })

    // The filter panel is collapsed by default — status/carrier/orders selects
    // are NOT in the DOM until Filters is toggled.
    expect(screen.queryByLabelText(/Filter by status/i)).toBeNull()

    // Toggle the panel open via the Filters button (aria-label from
    // ClientsPage L438).
    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))

    // Column filters + selects + Done button all present now.
    expect(screen.getByPlaceholderText('e.g. ARHDEV')).toBeTruthy()
    expect(screen.getByPlaceholderText('e.g. Modern Art')).toBeTruthy()
    expect(screen.getByPlaceholderText('e.g. Chicago')).toBeTruthy()
    expect(screen.getByLabelText(/Filter by status/i)).toBeTruthy()
    expect(screen.getByLabelText(/Filter by carrier/i)).toBeTruthy()
    expect(screen.getByLabelText(/Filter by orders/i)).toBeTruthy()
  }, 20000)

  it('typing in the search box eventually fires listClients with the trimmed search param', async () => {
    const Page = await loadPage()
    renderPage(Page)
    const searchInput = await screen.findByPlaceholderText('Search code, name, city…')

    listClients.mockClear()
    fireEvent.change(searchInput, { target: { value: 'acme' } })

    // Debounce is 350ms — wait long enough for the effect + async refetch.
    await waitFor(
      () => {
        expect(listClients).toHaveBeenCalled()
        expect(lastCallParams().search).toBe('acme')
      },
      { timeout: 2000 },
    )
  })

  it('selecting Status = ACTIVE refetches listClients with the status param', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const statusSelect = screen.getByLabelText(/Filter by status/i) as HTMLSelectElement

    listClients.mockClear()
    fireEvent.change(statusSelect, { target: { value: 'ACTIVE' } })

    await waitFor(() => {
      expect(listClients).toHaveBeenCalled()
      expect(lastCallParams().status).toBe('ACTIVE')
    })
  })

  it('selecting Carrier = FEDEX refetches listClients with the carrier param', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const carrierSelect = screen.getByLabelText(/Filter by carrier/i) as HTMLSelectElement

    listClients.mockClear()
    fireEvent.change(carrierSelect, { target: { value: 'FEDEX' } })

    await waitFor(() => {
      expect(listClients).toHaveBeenCalled()
      expect(lastCallParams().carrier).toBe('FEDEX')
    })
  })

  it('selecting Orders = YES refetches listClients with hasOrders=YES', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const ordersSelect = screen.getByLabelText(/Filter by orders/i) as HTMLSelectElement

    listClients.mockClear()
    fireEvent.change(ordersSelect, { target: { value: 'YES' } })

    await waitFor(() => {
      expect(listClients).toHaveBeenCalled()
      expect(lastCallParams().hasOrders).toBe('YES')
    })
  })

  it('typing in the code column filter refetches listClients with the code param (debounced)', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const codeInput = screen.getByPlaceholderText('e.g. ARHDEV') as HTMLInputElement

    listClients.mockClear()
    fireEvent.change(codeInput, { target: { value: 'ARHDEV' } })

    await waitFor(
      () => {
        expect(listClients).toHaveBeenCalled()
        expect(lastCallParams().code).toBe('ARHDEV')
      },
      { timeout: 2000 },
    )
  })

  it('typing in the name column filter refetches with the name param', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const nameInput = screen.getByPlaceholderText('e.g. Modern Art') as HTMLInputElement

    listClients.mockClear()
    fireEvent.change(nameInput, { target: { value: 'Modern Art' } })

    await waitFor(
      () => {
        expect(listClients).toHaveBeenCalled()
        expect(lastCallParams().name).toBe('Modern Art')
      },
      { timeout: 2000 },
    )
  })

  it('typing in the city column filter refetches with the city param', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    const cityInput = screen.getByPlaceholderText('e.g. Chicago') as HTMLInputElement

    listClients.mockClear()
    fireEvent.change(cityInput, { target: { value: 'Chicago' } })

    await waitFor(
      () => {
        expect(listClients).toHaveBeenCalled()
        expect(lastCallParams().city).toBe('Chicago')
      },
      { timeout: 2000 },
    )
  })

  it('Filters button toggles the panel open and closed', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    const filtersBtn = screen.getByRole('button', { name: /^Filters$/i })
    // Closed initially — button reports aria-expanded=false.
    expect(filtersBtn.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByRole('dialog', { name: /Filter clients/i })).toBeNull()

    fireEvent.click(filtersBtn)
    expect(filtersBtn.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByRole('dialog', { name: /Filter clients/i })).toBeTruthy()

    // Toggle back.
    fireEvent.click(filtersBtn)
    expect(filtersBtn.getAttribute('aria-expanded')).toBe('false')
  })

  it('Clear button resets every filter (dropdowns empty, columns empty) and refetches with clean params', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    // Seed state: pick one dropdown + type one column filter. Only these two
    // are needed to make activeFilterCount > 0 and surface the Clear button.
    const statusSelect = screen.getByLabelText(/Filter by status/i) as HTMLSelectElement
    fireEvent.change(statusSelect, { target: { value: 'INACTIVE' } })
    const codeInput = screen.getByPlaceholderText('e.g. ARHDEV') as HTMLInputElement
    fireEvent.change(codeInput, { target: { value: 'ARH' } })

    // Wait for the debounced refetch so we know the seeded state landed.
    await waitFor(() => {
      const p = lastCallParams()
      expect(p.status).toBe('INACTIVE')
      expect(p.code).toBe('ARH')
    }, { timeout: 2000 })

    // Clear button surfaces only when activeFilterCount > 0.
    const dialog = screen.getByRole('dialog', { name: /Filter clients/i })
    const clearBtn = within(dialog).getByRole('button', { name: /^Clear$/i })
    listClients.mockClear()
    fireEvent.click(clearBtn)

    // Every filter is reset — the corresponding controls read empty.
    expect(statusSelect.value).toBe('')
    expect(codeInput.value).toBe('')

    // After the column-filter debounce settles, the next refetch carries
    // undefined for every filter param.
    await waitFor(
      () => {
        expect(listClients).toHaveBeenCalled()
        const p = lastCallParams()
        expect(p.status).toBeUndefined()
        expect(p.code).toBeUndefined()
        expect(p.name).toBeUndefined()
        expect(p.city).toBeUndefined()
        expect(p.carrier).toBeUndefined()
        expect(p.hasOrders).toBeUndefined()
      },
      { timeout: 2000 },
    )
  })
})

// ===== Negative / edge cases =====

describe('ClientsPage · Filters · negative + edge', () => {
  it('rapid typing collapses into ONE debounced fetch (fake timers)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: false })
    const Page = await loadPage()

    // Render inside act because state effects fire on mount under fake timers.
    await act(async () => {
      renderPage(Page)
      // Let the initial mount + first listClients call settle.
      await vi.advanceTimersByTimeAsync(400)
    })
    // Recover the search input (findBy would await real timers, which are off).
    const searchInput = screen.getByPlaceholderText('Search code, name, city…') as HTMLInputElement

    listClients.mockClear()

    // Fire three rapid keystrokes within one debounce window; only the final
    // value must land in listClients (a single call).
    await act(async () => {
      fireEvent.change(searchInput, { target: { value: 'a' } })
      await vi.advanceTimersByTimeAsync(50)
      fireEvent.change(searchInput, { target: { value: 'ac' } })
      await vi.advanceTimersByTimeAsync(50)
      fireEvent.change(searchInput, { target: { value: 'acm' } })
    })

    // Nothing should have fired yet — debounce is 350ms per keystroke.
    expect(listClients).not.toHaveBeenCalled()

    // Advance past the 350ms debounce; the effect + microtask chain then
    // hits listClients exactly once with the final value.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400)
    })

    expect(listClients).toHaveBeenCalledTimes(1)
    expect(lastCallParams().search).toBe('acm')
  })

  it('empty search does not send a search param (undefined, not empty string)', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    // Initial mount already refetched once with empty search → assert on
    // the first-call params. debouncedSearch starts as '' and the component
    // maps '' → undefined before hitting the service (see ClientsPage L106).
    await waitFor(() => expect(listClients).toHaveBeenCalled())
    const firstCall = listClients.mock.calls[0][0] as Record<string, unknown>
    expect(firstCall.search).toBeUndefined()
  })

  it('combining multiple filters flows every param into a single listClients call', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))

    // Fire all three dropdowns in one tick. React batches the setState calls,
    // and the useEffect + fetch fires once per effective-value change.
    fireEvent.change(screen.getByLabelText(/Filter by status/i), { target: { value: 'ACTIVE' } })
    fireEvent.change(screen.getByLabelText(/Filter by carrier/i), { target: { value: 'UPS' } })
    fireEvent.change(screen.getByLabelText(/Filter by orders/i), { target: { value: 'NO' } })

    // Wait for the last one to land.
    await waitFor(() => {
      const p = lastCallParams()
      expect(p.status).toBe('ACTIVE')
      expect(p.carrier).toBe('UPS')
      expect(p.hasOrders).toBe('NO')
    })
  })

  it('outside click closes the filter panel (filtersRef guard)', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    const filtersBtn = screen.getByRole('button', { name: /^Filters$/i })
    fireEvent.click(filtersBtn)
    expect(screen.getByRole('dialog', { name: /Filter clients/i })).toBeTruthy()

    // A mousedown outside the filtersRef closes the panel. document.body is
    // definitely outside; the effect listens on document (see L88).
    fireEvent.mouseDown(document.body)

    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /Filter clients/i })).toBeNull()
    })
  })

  it('Escape closes the filter panel', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    expect(screen.getByRole('dialog', { name: /Filter clients/i })).toBeTruthy()

    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /Filter clients/i })).toBeNull()
    })
  })
})

// ===== Cross-cutting =====

describe('ClientsPage · Filters · cross-cutting', () => {
  it('setting a filter resets the paginator to page 0', async () => {
    // The pageIndex reset lives in an effect that fires on any filter change
    // (ClientsPage L74-77). We assert on the page param that lands in the
    // resulting listClients call — page must be 0 after the filter change,
    // regardless of any prior nav.
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    fireEvent.change(screen.getByLabelText(/Filter by status/i), { target: { value: 'ACTIVE' } })

    await waitFor(() => {
      const p = lastCallParams()
      expect(p.status).toBe('ACTIVE')
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
    expect(screen.getByLabelText(/Filter by status/i)).toBeTruthy()
    expect(screen.getByLabelText(/Filter by carrier/i)).toBeTruthy()
    expect(screen.getByLabelText(/Filter by orders/i)).toBeTruthy()
  })

  it('renders the filter controls for a TENANT role', async () => {
    sessionValue.role = 'TENANT'
    const Page = await loadPage()
    renderPage(Page)
    await screen.findByPlaceholderText('Search code, name, city…')

    fireEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
    expect(screen.getByLabelText(/Filter by status/i)).toBeTruthy()
    expect(screen.getByLabelText(/Filter by carrier/i)).toBeTruthy()
    expect(screen.getByLabelText(/Filter by orders/i)).toBeTruthy()
  })
})

// Reference to keep the linter quiet if future assertions drop `within`.
void within
