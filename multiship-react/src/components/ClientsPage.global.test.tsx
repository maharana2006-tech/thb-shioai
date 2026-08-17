import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import { Provider } from 'react-redux'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import type { ComponentType } from 'react'
import carrierReducer from '../store/carrierSlice'
import orderReducer from '../store/orderSlice'

/**
 * Sprint 53 page-tests — ClientsPage · Global actions + role gating.
 *
 * <p>Scope (this slice only):
 *   - Toolbar Add-Client button navigates to /settings/clients/new
 *   - Export button in the AdvancedDataTable toolbar → clientService.exportClientsCsv
 *   - Loading, populated, and empty states
 *   - Role gating summary: ADMIN vs USER vs TENANT — visible/hidden matrix.
 *
 * <p>All network-touching services are fully mocked. useAppSession is stubbed
 * so we can flip the effective role per-test without poking localStorage.
 * useNavigate is spied on for the toolbar Add-Client button.
 */

// ---------- Service mocks (hoisted before the component is imported) ----------

const listClientsMock = vi.fn()
const exportClientsCsvMock = vi.fn()
const cascadePreviewMock = vi.fn()
const toggleActiveMock = vi.fn()
const deleteClientMock = vi.fn()

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
    exportClientsCsv: (...args: unknown[]) => exportClientsCsvMock(...args),
    cascadePreview: (...args: unknown[]) => cascadePreviewMock(...args),
    toggleActive: (...args: unknown[]) => toggleActiveMock(...args),
    deleteClient: (...args: unknown[]) => deleteClientMock(...args),
    getClient: vi.fn(),
    createClient: vi.fn(),
    updateClient: vi.fn(),
    listClientAccounts: vi.fn().mockResolvedValue([]),
  },
}))

// Notify — stub the whole surface so we can assert apiError fires on export failures.
const notifyErrorMock = vi.fn()
const notifySuccessMock = vi.fn()
const notifyApiErrorMock = vi.fn()
const notifyInfoMock = vi.fn()
const notifyConfirmMock = vi.fn().mockResolvedValue(true)
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccessMock(...args),
    error: (...args: unknown[]) => notifyErrorMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    info: (...args: unknown[]) => notifyInfoMock(...args),
    confirm: (...args: unknown[]) => notifyConfirmMock(...args),
  },
}))

// useAppSession — re-mockable role so a single mock covers ADMIN / USER / TENANT
// variants without reloading the module tree per test.
let mockRole: 'ADMIN' | 'USER' | 'TENANT' = 'ADMIN'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops',
    role: mockRole,
    connectedCarriers: [],
    hasConnectedCarrier: false,
  }),
  clearAuthSession: vi.fn(),
  storeAuthSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(),
  syncCarrierSession: vi.fn(),
}))

// useNavigate spy — assert the Add-Client button routes to /settings/clients/new.
const navigateMock = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

// Suppress the CustomsProfileModal from pulling extra services — not exercised in this slice.
vi.mock('./modals/CustomsProfileModal', () => ({
  default: () => null,
}))

// ---------- Helpers ----------

/**
 * Dynamic import so vi.mock calls above land before ClientsPage evaluates its
 * top-level imports (clientService, useAppSession, notify).
 */
async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ClientsPage')
  return mod.default
}

function renderPage(Page: ComponentType) {
  const rootReducer = combineReducers({
    carriers: carrierReducer,
    orders: orderReducer,
  })
  const store = configureStore({
    reducer: rootReducer,
    middleware: (getDefault) => getDefault({ serializableCheck: false }),
  })

  // The page uses useOutletContext<SettingsOutletContext> for registerRefresh.
  // Wrap the route in a parent Outlet-providing route that supplies the context.
  const registerRefresh = vi.fn()

  return {
    registerRefresh,
    ...render(
      <Provider store={store}>
        <MemoryRouter initialEntries={['/settings/clients']}>
          <Routes>
            <Route element={<Outlet context={{ registerRefresh }} />}>
              <Route path="/settings/clients" element={<Page />} />
            </Route>
            <Route path="/settings/clients/new" element={<div>NEW_CLIENT_PAGE</div>} />
          </Routes>
        </MemoryRouter>
      </Provider>,
    ),
  }
}

/** Canonical page-response builder. */
function pageResponse(content: unknown[] = [], totalElements = content.length) {
  return {
    data: {
      content,
      pageNumber: 0,
      pageSize: 25,
      totalElements,
      totalPages: Math.max(Math.ceil(totalElements / 25), 1),
    },
  }
}

function sampleClient(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: 1,
    clientCode: 'ACME',
    name: 'Acme Widgets',
    email: 'ops@acme.io',
    phone: '+1 555',
    status: 'ACTIVE',
    shipFrom: { city: 'Chicago', state: 'IL', country: 'US' },
    returnAddress: null,
    returnSameAsShipFrom: true,
    defaultCurrency: 'USD',
    defaultWeightUnit: 'LB',
    defaultDimUnit: 'IN',
    timezone: 'America/New_York',
    defaultOriginCountry: 'US',
    createdAt: '2026-08-01T00:00:00',
    updatedAt: '2026-08-01T00:00:00',
    carrierAccounts: [],
    orderCount: 3,
    ...over,
  }
}

beforeEach(() => {
  listClientsMock.mockReset().mockResolvedValue(pageResponse([]))
  exportClientsCsvMock.mockReset().mockResolvedValue(undefined)
  cascadePreviewMock.mockReset()
  toggleActiveMock.mockReset()
  deleteClientMock.mockReset()
  notifyErrorMock.mockReset()
  notifySuccessMock.mockReset()
  notifyApiErrorMock.mockReset()
  notifyInfoMock.mockReset()
  notifyConfirmMock.mockReset().mockResolvedValue(true)
  navigateMock.mockReset()
  mockRole = 'ADMIN'
})

afterEach(() => {
  cleanup()
})

// ============ Positive cases ============

describe('ClientsPage · Global actions · positive', () => {
  it('renders the loading placeholder while the initial listClients is pending', async () => {
    // Never resolve — we assert the loading text on first paint.
    listClientsMock.mockReturnValueOnce(new Promise(() => {}))
    const Page = await loadPage()
    renderPage(Page)

    // "Loading clients…" uses a horizontal-ellipsis; regex is robust to that.
    expect(screen.getByText(/Loading clients/i)).toBeTruthy()
  }, 15000)

  it('renders table rows after listClients resolves with content', async () => {
    listClientsMock.mockResolvedValue(pageResponse([sampleClient(), sampleClient({
      id: 2,
      clientCode: 'BETA',
      name: 'Beta Co',
    })], 2))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      expect(screen.getByText('ACME')).toBeTruthy()
      expect(screen.getByText('BETA')).toBeTruthy()
    })
    // "Showing X of Y clients" caption reflects data — appears twice
    // (header caption + pagination footer), so allow multiples.
    expect(screen.getAllByText(/Showing 2 of 2/).length).toBeGreaterThan(0)
  })

  it('renders an empty-state message when the list is empty (no filters)', async () => {
    listClientsMock.mockResolvedValue(pageResponse([]))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      expect(screen.getByText(/No clients registered yet/i)).toBeTruthy()
    })
  })

  it('Add-Client button navigates to /settings/clients/new (ADMIN)', async () => {
    listClientsMock.mockResolvedValue(pageResponse([sampleClient()], 1))
    const Page = await loadPage()
    renderPage(Page)

    // Wait for table to finish loading so the toolbar renders.
    await waitFor(() => expect(screen.getByText('ACME')).toBeTruthy())

    const user = userEvent.setup()
    const addBtn = screen.getByRole('button', { name: /Add Client/i })
    await user.click(addBtn)

    expect(navigateMock).toHaveBeenCalledWith('/settings/clients/new')
  })

  it('Export → CSV button calls clientService.exportClientsCsv with current filter state', async () => {
    listClientsMock.mockResolvedValue(pageResponse([sampleClient()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME')).toBeTruthy())

    const user = userEvent.setup()
    // AdvancedDataTable renders a top-level "Export" toggle that opens a menu.
    const exportToggle = screen.getByRole('button', { name: /^Export$/i })
    await user.click(exportToggle)

    // Menu opens with "CSV — current view".
    const csvItem = await screen.findByRole('button', { name: /CSV .* current view/i })
    await user.click(csvItem)

    await waitFor(() => expect(exportClientsCsvMock).toHaveBeenCalledTimes(1))
    // First call: with the default (unfiltered) params — sortBy=code, ASC.
    const args = exportClientsCsvMock.mock.calls[0][0]
    expect(args).toMatchObject({ sortBy: 'code', sortDirection: 'ASC' })
    // No filters set means these keys are undefined (component passes ||undefined).
    expect(args.status).toBeUndefined()
    expect(args.carrier).toBeUndefined()
    expect(args.hasOrders).toBeUndefined()
  })

  it('empty state shows even when a search is applied (no results ≠ loading)', async () => {
    // First call: initial load returns one row so the table (not the loading
    // placeholder) is on-screen. Subsequent calls (after debounced search)
    // return zero — the empty-state message swaps in.
    listClientsMock
      .mockResolvedValueOnce(pageResponse([sampleClient()], 1))
      .mockResolvedValue(pageResponse([], 0))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME')).toBeTruthy())

    // Type into the toolbar search — AdvancedDataTable owns it via
    // props.search.onChange. The 350ms debounce means we need waitFor.
    const user = userEvent.setup()
    const searchInput = screen.getByPlaceholderText(/Search code, name, city/i)
    await user.type(searchInput, 'zzzzz')

    await waitFor(() => {
      // "No clients match the current filters." variant (search is active).
      expect(screen.getByText(/No clients match the current filters/i)).toBeTruthy()
    }, { timeout: 3000 })
  }, 15000)
})

// ============ Role-gating matrix ============

describe('ClientsPage · role gating', () => {
  /**
   * All three roles currently render the same top-of-page toolbar: the Add-Client
   * button and the Export menu are NOT gated on role at the ClientsPage level —
   * the backend rejects unauthorized calls. What DOES vary per role is the
   * per-row actions kebab (Delete is admin-only). We assert the visible-toolbar
   * matrix here and cover the delete-visibility difference via the RowActionsMenu
   * (only admin renders the "Delete" menuitem — verified in another test).
   */

  it('ADMIN: Add-Client + Export both visible in the toolbar', async () => {
    mockRole = 'ADMIN'
    listClientsMock.mockResolvedValue(pageResponse([sampleClient()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME')).toBeTruthy())
    expect(screen.getByRole('button', { name: /Add Client/i })).toBeTruthy()
    expect(screen.getByRole('button', { name: /^Export$/i })).toBeTruthy()
  })

  it('USER: Add-Client + Export still render (frontend does not gate the toolbar)', async () => {
    mockRole = 'USER'
    listClientsMock.mockResolvedValue(pageResponse([sampleClient()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME')).toBeTruthy())
    // Document the ACTUAL behavior: no role gate on Add-Client at the toolbar.
    // Backend rejects unauthorized POSTs. If we later gate this in the FE, flip
    // this expectation to `.toBeNull()`.
    expect(screen.getByRole('button', { name: /Add Client/i })).toBeTruthy()
    expect(screen.getByRole('button', { name: /^Export$/i })).toBeTruthy()
  })

  it('TENANT: sees only the rows the backend returns (frontend just renders)', async () => {
    mockRole = 'TENANT'
    // Mock a scoped response — a single tenant-owned row.
    listClientsMock.mockResolvedValue(pageResponse([sampleClient({
      clientCode: 'ARHDEV',
      name: 'ARH Development',
    })], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ARHDEV')).toBeTruthy())
    // No other tenants leak in.
    expect(screen.queryByText('ACME')).toBeNull()
    // "Showing 1 of 1 client" appears in the caption + pagination footer.
    expect(screen.getAllByText(/Showing 1 of 1/).length).toBeGreaterThan(0)
  })

  it('per-row kebab: ADMIN sees Delete; USER does not', async () => {
    mockRole = 'ADMIN'
    listClientsMock.mockResolvedValue(pageResponse([sampleClient()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME')).toBeTruthy())

    const user = userEvent.setup()
    const kebab = screen.getByRole('button', { name: /Row actions/i })
    await user.click(kebab)

    // PortalMenu renders into document.body — findByRole scans the whole DOM.
    await waitFor(() => {
      expect(screen.getByRole('menuitem', { name: /Delete/i })).toBeTruthy()
      expect(screen.getByRole('menuitem', { name: /Edit/i })).toBeTruthy()
      expect(screen.getByRole('menuitem', { name: /Importer/i })).toBeTruthy()
    })
  })

  it('per-row kebab: USER sees Edit + Importer but NOT Delete', async () => {
    mockRole = 'USER'
    listClientsMock.mockResolvedValue(pageResponse([sampleClient()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME')).toBeTruthy())

    const user = userEvent.setup()
    const kebab = screen.getByRole('button', { name: /Row actions/i })
    await user.click(kebab)

    await waitFor(() => {
      expect(screen.getByRole('menuitem', { name: /Edit/i })).toBeTruthy()
      expect(screen.getByRole('menuitem', { name: /Importer/i })).toBeTruthy()
    })
    // Delete is admin-gated in RowActionsMenu.
    expect(screen.queryByRole('menuitem', { name: /Delete/i })).toBeNull()
  })
})

// ============ Negative cases ============

describe('ClientsPage · Global actions · negative', () => {
  it('exportClientsCsv rejection surfaces via notify.apiError', async () => {
    listClientsMock.mockResolvedValue(pageResponse([sampleClient()], 1))
    exportClientsCsvMock.mockRejectedValue(new Error('boom'))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME')).toBeTruthy())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /^Export$/i }))
    const csvItem = await screen.findByRole('button', { name: /CSV .* current view/i })
    await user.click(csvItem)

    await waitFor(() => expect(notifyApiErrorMock).toHaveBeenCalledTimes(1))
    // Second arg is the friendly-fallback message.
    expect(notifyApiErrorMock.mock.calls[0][1]).toMatch(/Failed to export/i)
  })

  it('listClients rejection fires notify.apiError with the "Failed to load" fallback', async () => {
    listClientsMock.mockRejectedValue(new Error('backend down'))
    const Page = await loadPage()
    await act(async () => {
      renderPage(Page)
    })

    await waitFor(() => expect(notifyApiErrorMock).toHaveBeenCalledTimes(1))
    expect(notifyApiErrorMock.mock.calls[0][1]).toMatch(/Failed to load clients/i)
  })
})
