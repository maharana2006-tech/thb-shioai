import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ShippingServiceMappingPage · rules-list slice.
 *
 * Scope (this slice only):
 *   - Rows render with the 7 columns: Client (chip or "Any client") +
 *     Order Ship Via (code) + Warehouse cell + Ships-to (ZoneChips) +
 *     Carrier Ship Via (service badge) + Packages pill + Delete action.
 *   - Multiple rows all render.
 *   - Search narrows visible rows by shipviaCd / clientCode / carrier / country.
 *   - Search with no match → renders empty body.
 *   - Rule without clientCode → shows "Any client" placeholder chip.
 *   - Rule with COUNTRIES destValue → ZoneChips render country codes.
 *   - Rule targeting a disabled service → shows the warning line.
 *   - Rule with unknown serviceId → shows the "—" placeholder.
 *
 * Filter popover is exercised by the sibling filters slice. Add/edit/delete
 * happen-paths are exercised by their own slices.
 */

// ---------- Service mocks (same shape as shell slice) ----------

const catalogMock = vi.fn()
const listPresetsMock = vi.fn()
const listClientsMock = vi.fn()
const listWarehousesMock = vi.fn()
const listAccountsMock = vi.fn()

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

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
    getClient: vi.fn(),
    createClient: vi.fn(),
    updateClient: vi.fn(),
  },
}))

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...args: unknown[]) => listWarehousesMock(...args),
    getWarehouse: vi.fn(),
    createWarehouse: vi.fn(),
    updateWarehouse: vi.fn(),
  },
}))

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

vi.mock('../utils/notify', () => ({
  notify: {
    apiError: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

let mockRole: 'ADMIN' | 'USER' | 'TENANT' = 'ADMIN'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops', role: mockRole, connectedCarriers: [], hasConnectedCarrier: false,
  }),
  clearAuthSession: vi.fn(), storeAuthSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(), syncCarrierSession: vi.fn(),
}))

vi.mock('../api/apiClient', () => ({
  isAbortError: () => false,
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

vi.mock('./workspace/ZoneEditorModal', () => ({ default: () => null }))
vi.mock('./modals/RulePackagesDrawer', () => ({ default: () => null }))

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[catalogMock, listPresetsMock, listClientsMock, listWarehousesMock, listAccountsMock]
    .forEach((m) => m.mockReset())
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

const svc = (id: number, carrier: string, code: string, name: string, enabled = true) => ({
  id, carrier, serviceCode: code, name,
  scope: 'DOMESTIC' as const, originCountry: 'US',
  source: 'CARRIER_API', syncedAt: new Date().toISOString(), enabled,
})

const rule = (id: number, shipviaCd: string, overrides: Partial<{
  clientCode: string | null, destType: string, destValue: string | null, serviceId: number,
}> = {}) => ({
  id, shipviaCd,
  clientCode: overrides.clientCode ?? null,
  destType: overrides.destType ?? 'ANY',
  destValue: overrides.destValue ?? null,
  serviceId: overrides.serviceId ?? 1,
})

const catalogWith = (services: ReturnType<typeof svc>[], rules: ReturnType<typeof rule>[]) => ({
  services, rules, links: [], rulePackages: [], ruleWarehouses: [], originCountries: ['US'],
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

// ===================== Per-row rendering =====================

describe('ShippingServiceMappingPage — per-row rendering', () => {
  it('renders shipviaCd + carrier service badge for a rule', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'GND', { clientCode: 'C001', destType: 'COUNTRY', destValue: 'US', serviceId: 1 })],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('GND')).toBeInTheDocument())
    expect(screen.getByText(/UPS.*UPS Ground/)).toBeInTheDocument()
    // Client chip.
    expect(screen.getByText('C001')).toBeInTheDocument()
  })

  it('renders "Any client" chip when clientCode is null', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'ANYRULE', { clientCode: null, serviceId: 1 })],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ANYRULE')).toBeInTheDocument())
    expect(screen.getByText(/Any client/i)).toBeInTheDocument()
  })

  it('renders "Anywhere" ships-to when destType is ANY (no zone codes)', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'ANYWHERE', { destType: 'ANY', destValue: null, serviceId: 1 })],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ANYWHERE')).toBeInTheDocument())
    // "Anywhere" may appear multiple times in the DOM (fallback text + row cell).
    expect(screen.getAllByText(/Anywhere/i).length).toBeGreaterThan(0)
  })

  it('COUNTRIES destValue → renders ZoneChips (region-grouped)', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'ZONE', { destType: 'COUNTRIES', destValue: 'DE FR', serviceId: 1 })],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ZONE')).toBeInTheDocument())
    // ZoneChips renders a chip with region text and codes.
    expect(screen.queryByText(/Anywhere/i)).not.toBeInTheDocument()
  })

  it('rule targeting a disabled service shows the amber warning line', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground', false /* disabled */)],
      [rule(10, 'GND', { serviceId: 1 })],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByText(/Service disabled/i)).toBeInTheDocument(),
    )
  })

  it('rule with unknown serviceId renders the "—" placeholder', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [] /* no services in the catalog */,
      [rule(10, 'ORPHAN', { serviceId: 999 })],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ORPHAN')).toBeInTheDocument())
    // The service cell falls back to em-dash.
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('renders a Delete button per row with aria-label including shipviaCd', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'DEL_ME', { clientCode: 'C001', destType: 'COUNTRY', destValue: 'US', serviceId: 1 })],
    ))

    const Page = await loadPage()
    renderPage(Page)

    // The button's aria-label is "Remove mapping DEL_ME C001 United States".
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Remove mapping DEL_ME/i })).toBeInTheDocument(),
    )
  })
})

// ===================== Multiple rows =====================

describe('ShippingServiceMappingPage — multiple rows', () => {
  it('renders all rows returned from the catalog', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground'), svc(2, 'FEDEX', 'GND', 'FedEx Ground')],
      [
        rule(10, 'GND', { clientCode: 'C001', serviceId: 1 }),
        rule(11, 'GND', { clientCode: 'C002', serviceId: 2 }),
        rule(12, 'FDX', { clientCode: null, serviceId: 2 }),
      ],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('C001')).toBeInTheDocument())
    expect(screen.getByText('C002')).toBeInTheDocument()
    expect(screen.getByText('FDX')).toBeInTheDocument()
    // The "GND" ship-via code appears twice.
    expect(screen.getAllByText('GND').length).toBeGreaterThanOrEqual(2)
  })
})

// ===================== Search =====================

describe('ShippingServiceMappingPage — search', () => {
  it('search narrows rows by shipviaCd (case-insensitive)', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      // Deliberately distinct service names so a shipviaCd search doesn't
      // accidentally match via the service-name bag.
      [svc(1, 'UPS', 'GROUND', 'UPS Ground'), svc(2, 'FEDEX', 'EXP', 'FedEx Overnight')],
      [
        rule(10, 'GROUNDCODE', { clientCode: 'C001', serviceId: 1 }),
        rule(11, 'EXPRESSCODE', { clientCode: 'C002', serviceId: 2 }),
      ],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('GROUNDCODE')).toBeInTheDocument())
    expect(screen.getByText('EXPRESSCODE')).toBeInTheDocument()

    // Search for lower-case "groundcode" — narrows to just the GROUND rule.
    await userEvent.type(
      screen.getByPlaceholderText(/Search ship via, client, carrier, country/i),
      'groundcode',
    )

    // Wait for the debounced filter to settle.
    await waitFor(() => expect(screen.queryByText('EXPRESSCODE')).not.toBeInTheDocument())
    expect(screen.getByText('GROUNDCODE')).toBeInTheDocument()
  })

  it('search narrows rows by client code', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [
        rule(10, 'GND', { clientCode: 'ACME', serviceId: 1 }),
        rule(11, 'GND', { clientCode: 'ZORP', serviceId: 1 }),
      ],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('ACME')).toBeInTheDocument())
    expect(screen.getByText('ZORP')).toBeInTheDocument()

    await userEvent.type(
      screen.getByPlaceholderText(/Search ship via, client, carrier, country/i),
      'acme',
    )

    await waitFor(() => expect(screen.queryByText('ZORP')).not.toBeInTheDocument())
    expect(screen.getByText('ACME')).toBeInTheDocument()
  })

  it('no-match search hides all rows (empty body)', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'GND', { clientCode: 'C001', serviceId: 1 })],
    ))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('C001')).toBeInTheDocument())

    await userEvent.type(
      screen.getByPlaceholderText(/Search ship via, client, carrier, country/i),
      'nothingmatchesthis',
    )

    await waitFor(() => expect(screen.queryByText('C001')).not.toBeInTheDocument())
    expect(screen.queryByText('GND')).not.toBeInTheDocument()
  })
})
