import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 51 page-tests — WarehousesPage · Global + role gating slice.
 *
 * <p>Scope (this slice only):
 *   - Loading placeholder while the initial `listWarehouses` is pending
 *   - Populated + empty-state rendering after resolve
 *   - Toolbar "Add Warehouse" button opens the WarehouseEditorModal in
 *     CREATE mode (`setEditor({ warehouse: null })`)
 *   - Role gating matrix (ADMIN vs USER vs TENANT):
 *       - Toolbar Add Warehouse button (NOT frontend-gated; documented)
 *       - Row-actions kebab: Delete is ADMIN-only, Edit/Attach clients
 *         visible to all
 *   - TENANT: backend scoping — component just renders whatever the API
 *     returns, no client-side filter
 *   - Negative: `listWarehouses` rejection surfaces via `notify.apiError`
 *
 * <p>Every network-touching service is mocked. `useAppSession` is stubbed
 * so we can flip the effective role per-test without poking localStorage.
 * WarehouseEditorModal + AttachClientsModal are stubbed to isolate the
 * page and avoid dragging in their own service dependencies.
 */

// ---------- Service mocks (hoisted before the component is imported) ----------

const listWarehousesMock = vi.fn()
const toggleActiveMock = vi.fn()
const createWarehouseMock = vi.fn()
const updateWarehouseMock = vi.fn()
const deleteWarehouseMock = vi.fn()
const listForClientMock = vi.fn()
const attachMock = vi.fn()

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...args: unknown[]) => listWarehousesMock(...args),
    toggleActive: (...args: unknown[]) => toggleActiveMock(...args),
    createWarehouse: (...args: unknown[]) => createWarehouseMock(...args),
    updateWarehouse: (...args: unknown[]) => updateWarehouseMock(...args),
    deleteWarehouse: (...args: unknown[]) => deleteWarehouseMock(...args),
    getWarehouse: vi.fn(),
  },
  clientWarehouseService: {
    listForClient: (...args: unknown[]) => listForClientMock(...args),
    attach: (...args: unknown[]) => attachMock(...args),
    detach: vi.fn(),
    setDefault: vi.fn(),
  },
}))

// Notify — stub the whole surface so we can assert apiError fires on load failures
// AND so notify.confirm returns a real Promise (component `await`s it in delete).
const notifySuccessMock = vi.fn()
const notifyErrorMock = vi.fn()
const notifyApiErrorMock = vi.fn()
const notifyInfoMock = vi.fn()
const notifyConfirmMock = vi.fn<(msg: string, opts?: unknown) => Promise<boolean>>()

vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccessMock(...args),
    error: (...args: unknown[]) => notifyErrorMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    info: (...args: unknown[]) => notifyInfoMock(...args),
    confirm: (msg: string, opts?: unknown) => notifyConfirmMock(msg, opts),
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

// Stub the WarehouseEditorModal so that opening the create-mode editor doesn't
// drag in its own service dependencies. The stub surfaces the `warehouse` prop
// value so we can assert whether we opened in create mode (null) or edit mode.
vi.mock('./modals/WarehouseEditorModal', () => ({
  default: ({
    warehouse,
    onClose,
  }: {
    warehouse: unknown
    onClose: () => void
    onSaved: () => void
  }) => (
    <div role="dialog" aria-label="Warehouse editor modal">
      <p>EDITOR_MODE: {warehouse === null ? 'CREATE' : 'EDIT'}</p>
      <button type="button" onClick={onClose}>Close editor</button>
    </div>
  ),
}))

// AttachClientsModal similarly stubbed — this slice does not exercise it, but
// the row-actions kebab renders a menuitem that would open it.
vi.mock('./modals/AttachClientsModal', () => ({
  default: ({ onClose }: { onClose: () => void }) => (
    <div role="dialog" aria-label="Attach clients modal">
      <button type="button" onClick={onClose}>Close attach</button>
    </div>
  ),
}))

// ---------- Helpers ----------

/**
 * Dynamic import so vi.mock calls above land before WarehousesPage evaluates
 * its top-level imports (warehouseService, useAppSession, notify).
 */
async function loadPage(): Promise<ComponentType> {
  const mod = await import('./WarehousesPage')
  return mod.default
}

function renderPage(Page: ComponentType) {
  const registerRefresh = vi.fn()

  return {
    registerRefresh,
    ...render(
      <MemoryRouter initialEntries={['/settings/warehouses']}>
        <Routes>
          <Route element={<Outlet context={{ registerRefresh }} />}>
            <Route path="/settings/warehouses" element={<Page />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    ),
  }
}

/** Canonical page-response builder matching the backend's ApiResponse<Page<T>>. */
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

function sampleWarehouse(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: 1,
    code: 'WH-CHI',
    name: 'Chicago DC',
    address: { city: 'Chicago', state: 'IL', country: 'US' },
    ownerType: 'PLATFORM',
    ownerClientCode: null,
    active: true,
    attachedClientCount: 3,
    createdAt: '2026-08-01T00:00:00',
    updatedAt: '2026-08-01T00:00:00',
    ...over,
  }
}

beforeEach(() => {
  listWarehousesMock.mockReset().mockResolvedValue(pageResponse([]))
  toggleActiveMock.mockReset()
  createWarehouseMock.mockReset()
  updateWarehouseMock.mockReset()
  deleteWarehouseMock.mockReset()
  listForClientMock.mockReset().mockResolvedValue([])
  attachMock.mockReset()
  notifySuccessMock.mockReset()
  notifyErrorMock.mockReset()
  notifyApiErrorMock.mockReset()
  notifyInfoMock.mockReset()
  notifyConfirmMock.mockReset().mockResolvedValue(true)
  mockRole = 'ADMIN'
})

afterEach(() => {
  cleanup()
})

// ============ Positive cases ============

describe('WarehousesPage · Global · positive', () => {
  it('renders the loading placeholder while the initial listWarehouses is pending', async () => {
    // Never resolve — assert the loading text on first paint.
    listWarehousesMock.mockReturnValueOnce(new Promise(() => {}))
    const Page = await loadPage()
    renderPage(Page)

    // Loading text uses horizontal-ellipsis; regex tolerates that.
    expect(screen.getByText(/Loading warehouses/i)).toBeTruthy()
  }, 15000)

  it('renders table rows after listWarehouses resolves with content', async () => {
    listWarehousesMock.mockResolvedValue(
      pageResponse(
        [sampleWarehouse(), sampleWarehouse({ id: 2, code: 'WH-LAX', name: 'LAX DC' })],
        2,
      ),
    )
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      expect(screen.getByText('WH-CHI')).toBeTruthy()
      expect(screen.getByText('WH-LAX')).toBeTruthy()
    })
    // Caption "Showing N of N warehouse(s)" appears at least once (header caption).
    expect(screen.getAllByText(/Showing 2 of 2 warehouse/i).length).toBeGreaterThan(0)
  })

  it('renders the empty-state message when the list is empty (no filters applied)', async () => {
    listWarehousesMock.mockResolvedValue(pageResponse([]))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      expect(
        screen.getByText(/No warehouses registered yet/i),
      ).toBeTruthy()
    })
  })

  it('Add Warehouse button opens the editor modal in CREATE mode (warehouse=null)', async () => {
    listWarehousesMock.mockResolvedValue(pageResponse([sampleWarehouse()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('WH-CHI')).toBeTruthy())

    const user = userEvent.setup()
    const addBtn = screen.getByRole('button', { name: /Add Warehouse/i })
    await user.click(addBtn)

    // Stubbed modal surfaces the mode via its content — CREATE ≡ warehouse === null.
    expect(await screen.findByRole('dialog', { name: /Warehouse editor modal/i })).toBeTruthy()
    expect(screen.getByText(/EDITOR_MODE: CREATE/i)).toBeTruthy()
  })

  it('shows the filter-variant empty-state when a search yields zero results', async () => {
    // Initial load returns one row so the table renders (not the loading placeholder).
    // Subsequent debounced-search load returns zero.
    listWarehousesMock
      .mockResolvedValueOnce(pageResponse([sampleWarehouse()], 1))
      .mockResolvedValue(pageResponse([], 0))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('WH-CHI')).toBeTruthy())

    const user = userEvent.setup()
    const searchInput = screen.getByPlaceholderText(/Search code, name, city/i)
    await user.type(searchInput, 'zzzzz')

    await waitFor(() => {
      expect(screen.getByText(/No warehouses match the current filters/i)).toBeTruthy()
    }, { timeout: 3000 })
  }, 15000)
})

// ============ Role-gating matrix ============

describe('WarehousesPage · role gating', () => {
  /**
   * ADMIN vs USER vs TENANT: at the WarehousesPage level, the toolbar Add
   * Warehouse button is NOT frontend-gated on role — the backend rejects
   * unauthorized POSTs. What DOES vary per role is the per-row actions
   * kebab: Delete is admin-only (see RowActionsMenu at the bottom of the
   * component). TENANT sees only their scoped rows because the backend
   * filters — the component simply renders what the API returns.
   */

  it('ADMIN: Add Warehouse button is visible in the toolbar', async () => {
    mockRole = 'ADMIN'
    listWarehousesMock.mockResolvedValue(pageResponse([sampleWarehouse()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('WH-CHI')).toBeTruthy())
    expect(screen.getByRole('button', { name: /Add Warehouse/i })).toBeTruthy()
  })

  it('USER: Add Warehouse still renders (frontend does not gate the toolbar; backend rejects)', async () => {
    mockRole = 'USER'
    listWarehousesMock.mockResolvedValue(pageResponse([sampleWarehouse()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('WH-CHI')).toBeTruthy())
    // Documenting ACTUAL behavior: no FE gate on Add Warehouse. If we
    // later gate this in the FE, flip this to `queryByRole(...).toBeNull()`.
    expect(screen.getByRole('button', { name: /Add Warehouse/i })).toBeTruthy()
  })

  it('TENANT: sees only rows the backend returns (frontend just renders)', async () => {
    mockRole = 'TENANT'
    listWarehousesMock.mockResolvedValue(
      pageResponse(
        [
          sampleWarehouse({
            id: 42,
            code: 'ARHDEV-WH1',
            name: 'ARH Dev Warehouse',
            ownerType: 'CLIENT',
            ownerClientCode: 'ARHDEV',
            attachedClientCount: 1,
          }),
        ],
        1,
      ),
    )
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ARHDEV-WH1')).toBeTruthy())
    // No other tenants' warehouses leak in.
    expect(screen.queryByText('WH-CHI')).toBeNull()
    expect(screen.getAllByText(/Showing 1 of 1 warehouse/i).length).toBeGreaterThan(0)
  })

  it('per-row kebab: ADMIN sees Delete + Edit + Attach clients (platform-owned row)', async () => {
    mockRole = 'ADMIN'
    listWarehousesMock.mockResolvedValue(pageResponse([sampleWarehouse()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('WH-CHI')).toBeTruthy())

    const user = userEvent.setup()
    const kebab = screen.getByRole('button', { name: /Row actions/i })
    await user.click(kebab)

    // PortalMenu renders into document.body — findByRole scans the whole DOM.
    await waitFor(() => {
      expect(screen.getByRole('menuitem', { name: /Delete/i })).toBeTruthy()
      expect(screen.getByRole('menuitem', { name: /Edit/i })).toBeTruthy()
      expect(screen.getByRole('menuitem', { name: /Attach clients/i })).toBeTruthy()
    })
  })

  it('per-row kebab: USER sees Edit + Attach clients but NOT Delete', async () => {
    mockRole = 'USER'
    listWarehousesMock.mockResolvedValue(pageResponse([sampleWarehouse()], 1))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('WH-CHI')).toBeTruthy())

    const user = userEvent.setup()
    const kebab = screen.getByRole('button', { name: /Row actions/i })
    await user.click(kebab)

    await waitFor(() => {
      expect(screen.getByRole('menuitem', { name: /Edit/i })).toBeTruthy()
      expect(screen.getByRole('menuitem', { name: /Attach clients/i })).toBeTruthy()
    })
    // Delete is admin-gated in RowActionsMenu — confirms the FE gate exists.
    expect(screen.queryByRole('menuitem', { name: /Delete/i })).toBeNull()
  })

  it('per-row kebab: TENANT sees Edit + Attach clients but NOT Delete (same as USER)', async () => {
    mockRole = 'TENANT'
    listWarehousesMock.mockResolvedValue(
      pageResponse(
        [
          sampleWarehouse({
            code: 'ARHDEV-WH1',
            ownerType: 'PLATFORM',
            ownerClientCode: null,
          }),
        ],
        1,
      ),
    )
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ARHDEV-WH1')).toBeTruthy())

    const user = userEvent.setup()
    const kebab = screen.getByRole('button', { name: /Row actions/i })
    await user.click(kebab)

    await waitFor(() => {
      expect(screen.getByRole('menuitem', { name: /Edit/i })).toBeTruthy()
      expect(screen.getByRole('menuitem', { name: /Attach clients/i })).toBeTruthy()
    })
    expect(screen.queryByRole('menuitem', { name: /Delete/i })).toBeNull()
  })

  it('per-row kebab: CLIENT-owned warehouse hides "Attach clients" (regardless of role)', async () => {
    mockRole = 'ADMIN'
    // ownerType=CLIENT is NOT attachable in the component (see canAttach guard).
    listWarehousesMock.mockResolvedValue(
      pageResponse(
        [
          sampleWarehouse({
            code: 'ACME-WH1',
            ownerType: 'CLIENT',
            ownerClientCode: 'ACME',
          }),
        ],
        1,
      ),
    )
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME-WH1')).toBeTruthy())

    const user = userEvent.setup()
    const kebab = screen.getByRole('button', { name: /Row actions/i })
    await user.click(kebab)

    await waitFor(() => {
      expect(screen.getByRole('menuitem', { name: /Edit/i })).toBeTruthy()
      // Still visible for ADMIN.
      expect(screen.getByRole('menuitem', { name: /Delete/i })).toBeTruthy()
    })
    // No Attach-clients menuitem because ownerType != PLATFORM.
    expect(screen.queryByRole('menuitem', { name: /Attach clients/i })).toBeNull()
  })
})

// ============ Negative cases ============

describe('WarehousesPage · Global · negative', () => {
  it('listWarehouses rejection fires notify.apiError with the "Failed to load" fallback', async () => {
    listWarehousesMock.mockRejectedValue(new Error('backend down'))
    const Page = await loadPage()
    await act(async () => {
      renderPage(Page)
    })

    await waitFor(() => expect(notifyApiErrorMock).toHaveBeenCalledTimes(1))
    // The second arg is the friendly-fallback message.
    expect(notifyApiErrorMock.mock.calls[0][1]).toMatch(/Failed to load warehouses/i)
  })

  it('after a rejected initial load, the loading placeholder clears (loading=false)', async () => {
    listWarehousesMock.mockRejectedValue(new Error('backend down'))
    const Page = await loadPage()
    await act(async () => {
      renderPage(Page)
    })

    // Once the promise settles, the "Loading warehouses…" placeholder should be
    // gone — the component sets loading=false in .finally(). Because rows are
    // also empty, the AdvancedDataTable renders the empty-state row instead.
    await waitFor(() => {
      expect(screen.queryByText(/Loading warehouses/i)).toBeNull()
    })
  })
})
