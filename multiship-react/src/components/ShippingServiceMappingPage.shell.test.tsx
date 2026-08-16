import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ShippingServiceMappingPage · shell + global.
 *
 * Scope (this slice only):
 *   - Mount calls the 5-way load: catalog + listClients + listWarehouses +
 *     listPresets + accountRefService.listAccounts.
 *   - Loading placeholder while catalog is in-flight AND no rules yet.
 *   - Error path: catalog rejection → notify.apiError('Failed to load the
 *     mapping catalog.') AND the loading placeholder clears.
 *   - Empty state: zero rules from catalog renders the empty AdvancedDataTable
 *     (no ghost rows).
 *   - Role parity: page mounts + renders identically for ADMIN / USER / TENANT
 *     (page does not import useAppSession at the shell level; children like
 *     PackagesPage may gate their own actions).
 *
 * Sibling slices test filters, rules-list rendering, add/edit modals, delete
 * and packages drawer.
 *
 * Anti-fallback: every service mocked; fail-loud globalThis.fetch spy.
 */

// ---------- Service mocks (hoisted) ----------

const catalogMock = vi.fn()
const listPresetsMock = vi.fn()

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: (...args: unknown[]) => catalogMock(...args),
    listPresets: (...args: unknown[]) => listPresetsMock(...args),
    saveRule: vi.fn(),
    deleteRule: vi.fn(),
    syncServices: vi.fn(),
    syncPackages: vi.fn(),
    setServiceEnabled: vi.fn(),
    setServicePackages: vi.fn(),
    savePreset: vi.fn(),
    setDefaultPreset: vi.fn(),
    deletePreset: vi.fn(),
  },
  fitAgainstService: () => ({ status: 'FITS', reason: '' }),
  limitsOf: () => ({ maxWeightLb: 150, maxLengthIn: 108, maxLengthGirthIn: 165, surchargeLengthGirthIn: 130 }),
  dimWeightOf: () => null,
  oversizeOf: () => null,
}))

const listClientsMock = vi.fn()
vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
    getClient: vi.fn(),
    createClient: vi.fn(),
    updateClient: vi.fn(),
  },
}))

const listWarehousesMock = vi.fn()
vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...args: unknown[]) => listWarehousesMock(...args),
    getWarehouse: vi.fn(),
    createWarehouse: vi.fn(),
    updateWarehouse: vi.fn(),
  },
}))

const listAccountsMock = vi.fn()
vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: (...args: unknown[]) => listAccountsMock(...args),
    upsertAccount: vi.fn(),
    verifyAccount: vi.fn(),
    toggleActive: vi.fn(),
    deleteAccount: vi.fn(),
    verifyCredentials: vi.fn(),
    getPlatformCredentials: vi.fn(),
    setClientDefault: vi.fn(),
  },
}))

const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

let mockRole: 'ADMIN' | 'USER' | 'TENANT' = 'ADMIN'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops', role: mockRole, connectedCarriers: [], hasConnectedCarrier: false,
  }),
  clearAuthSession: vi.fn(),
  storeAuthSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(),
  syncCarrierSession: vi.fn(),
}))

vi.mock('../api/apiClient', () => ({
  isAbortError: () => false,
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// Stub heavy child components so the shell test doesn't exercise them.
vi.mock('./workspace/ZoneEditorModal', () => ({
  default: () => null,
}))
vi.mock('./modals/RulePackagesDrawer', () => ({
  default: () => null,
}))

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[catalogMock, listPresetsMock, listClientsMock, listWarehousesMock,
    listAccountsMock, notifyApiErrorMock].forEach((m) => m.mockReset())
  // Sane defaults so tests don't have to individually stub the "quiet" services.
  listPresetsMock.mockResolvedValue([])
  listClientsMock.mockResolvedValue({ data: { content: [] } })
  listWarehousesMock.mockResolvedValue({ data: { content: [] } })
  listAccountsMock.mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

// ---------- Helpers ----------

const emptyCatalog = () => ({
  services: [], rules: [], links: [], rulePackages: [], ruleWarehouses: [],
  originCountries: [],
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ShippingServiceMappingPage')
  return mod.default
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter>
      <Routes>
        <Route element={<Outlet context={{ registerRefresh: vi.fn() }} />}>
          <Route path="*" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// ===================== Mount + service calls =====================

describe('ShippingServiceMappingPage — mount + service calls', () => {
  it('calls all 5 services in parallel on mount', async () => {
    catalogMock.mockResolvedValue(emptyCatalog())

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(catalogMock).toHaveBeenCalledTimes(1))
    expect(listPresetsMock).toHaveBeenCalledTimes(1)
    expect(listClientsMock).toHaveBeenCalledTimes(1)
    expect(listWarehousesMock).toHaveBeenCalledTimes(1)
    expect(listAccountsMock).toHaveBeenCalledTimes(1)
  })

  it('listClients is paged with size 200; listWarehouses with size 500', async () => {
    // Pin the page-size args — the UI needs the full list up-front for pickers.
    catalogMock.mockResolvedValue(emptyCatalog())

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listClientsMock).toHaveBeenCalledWith({ size: 200 }))
    expect(listWarehousesMock).toHaveBeenCalledWith({ size: 500 })
  })
})

// ===================== Loading placeholder =====================

describe('ShippingServiceMappingPage — loading placeholder', () => {
  it('renders "Loading mappings…" while catalog is in-flight AND rules are empty', async () => {
    // Never-resolving catalog keeps loading=true.
    catalogMock.mockReturnValue(new Promise(() => {}))

    const Page = await loadPage()
    renderPage(Page)

    // Placeholder rendered before any data flows.
    expect(await screen.findByText(/Loading mappings…/i)).toBeInTheDocument()
  })
})

// ===================== Error path =====================

describe('ShippingServiceMappingPage — error path', () => {
  it('catalog rejection → notify.apiError("Failed to load the mapping catalog.")', async () => {
    catalogMock.mockRejectedValue(new Error('boom'))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(
        expect.any(Error),
        'Failed to load the mapping catalog.',
      ),
    )
    // Loading placeholder clears even on error.
    await waitFor(() =>
      expect(screen.queryByText(/Loading mappings…/i)).not.toBeInTheDocument(),
    )
  })
})

// ===================== Empty state =====================

describe('ShippingServiceMappingPage — empty state', () => {
  it('zero rules → AdvancedDataTable renders with empty body (no ghost rows)', async () => {
    catalogMock.mockResolvedValue(emptyCatalog())

    const Page = await loadPage()
    renderPage(Page)

    // Loading placeholder gone.
    await waitFor(() =>
      expect(screen.queryByText(/Loading mappings…/i)).not.toBeInTheDocument(),
    )
    // Search box is part of the AdvancedDataTable wrapper — its placeholder pins that the table rendered.
    expect(screen.getByPlaceholderText(/Search ship via, client, carrier, country/i))
      .toBeInTheDocument()
  })
})

// ===================== Role parity =====================

describe('ShippingServiceMappingPage — role parity (no shell-level gate)', () => {
  it.each(['ADMIN', 'USER', 'TENANT'] as const)(
    '%s sees the mapping table (page does not import useAppSession for gating)',
    async (role) => {
      mockRole = role
      catalogMock.mockResolvedValue(emptyCatalog())

      const Page = await loadPage()
      renderPage(Page)

      await waitFor(() =>
        expect(screen.getByPlaceholderText(/Search ship via, client, carrier, country/i))
          .toBeInTheDocument(),
      )
      // Filters toggle button present for every role (backend is the trust boundary).
      expect(screen.getByRole('button', { name: /Filters/i })).toBeInTheDocument()
    },
  )
})
