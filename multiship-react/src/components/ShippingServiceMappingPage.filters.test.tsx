import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ShippingServiceMappingPage · filters slice.
 *
 * Scope (this slice only):
 *   - Filter popover opens/closes via the Filters toggle.
 *   - 4 filter selects: Order Ship Via, Client, Destination Region,
 *     Carrier Ship Via.
 *   - Each filter narrows visible rows.
 *   - Region filter excludes rules whose codes don't sit in that region
 *     (global-ANY rules stay hidden until the filter is cleared).
 *   - Filter counter chip appears on the toggle; Clear resets everything.
 *
 * Sibling slices cover shell, list rendering, add/edit modals, delete.
 */

// ---------- Service mocks ----------

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
    getClient: vi.fn(), createClient: vi.fn(), updateClient: vi.fn(),
  },
}))

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: (...args: unknown[]) => listWarehousesMock(...args),
    getWarehouse: vi.fn(), createWarehouse: vi.fn(), updateWarehouse: vi.fn(),
  },
}))

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: (...args: unknown[]) => listAccountsMock(...args),
    upsertAccount: vi.fn(), verifyAccount: vi.fn(), toggleActive: vi.fn(),
    deleteAccount: vi.fn(), verifyCredentials: vi.fn(),
    getPlatformCredentials: vi.fn(), setClientDefault: vi.fn(),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: {
    apiError: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops', role: 'ADMIN' as const,
    connectedCarriers: [], hasConnectedCarrier: false,
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

// ---------- Fixtures ----------

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

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[catalogMock, listPresetsMock, listClientsMock, listWarehousesMock, listAccountsMock]
    .forEach((m) => m.mockReset())
  listPresetsMock.mockResolvedValue([])
  listClientsMock.mockResolvedValue({ data: { content: [
    { clientCode: 'ACME', name: 'ACME Corp' },
    { clientCode: 'ZORP', name: 'Zorp Inc' },
  ] } })
  listWarehousesMock.mockResolvedValue({ data: { content: [] } })
  listAccountsMock.mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
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

async function openFilters() {
  await act(async () => {
    await userEvent.click(screen.getByRole('button', { name: /^Filters$/i }))
  })
  await waitFor(() =>
    expect(screen.getByRole('dialog', { name: /Filter mappings/i })).toBeInTheDocument(),
  )
}

// ===================== Toggle open/close =====================

describe('ShippingServiceMappingPage — filters toggle', () => {
  it('opens the popover when Filters button clicked; closes on Done click', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'GND', { serviceId: 1 })],
    ))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Filters$/i })).toBeInTheDocument())

    await openFilters()

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Done/i }))
    })
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: /Filter mappings/i })).not.toBeInTheDocument(),
    )
  })
})

// ===================== Per-filter narrowing =====================

describe('ShippingServiceMappingPage — Order Ship Via filter', () => {
  it('narrows rows to the selected shipviaCd', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [
        rule(10, 'GND', { clientCode: 'ACME', serviceId: 1 }),
        rule(11, 'EXP', { clientCode: 'ACME', serviceId: 1 }),
      ],
    ))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('GND')).toBeInTheDocument())
    expect(screen.getByText('EXP')).toBeInTheDocument()

    await openFilters()
    await userEvent.selectOptions(
      screen.getByLabelText(/Filter by order ship via/i),
      'GND',
    )

    // Row cell renders shipviaCd inside a <span>; the filter <option> also has
    // the code. Scope the assertion to spans so the option isn't a false match.
    await waitFor(() =>
      expect(screen.queryByText('EXP', { selector: 'span' })).not.toBeInTheDocument(),
    )
    expect(screen.getByText('GND', { selector: 'span' })).toBeInTheDocument()
  })
})

describe('ShippingServiceMappingPage — Client filter', () => {
  it('narrows rows to the selected client', async () => {
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

    await openFilters()
    await userEvent.selectOptions(
      screen.getByLabelText(/Filter by client/i),
      'ACME',
    )

    // The Client filter <select> has options with client codes; scope to spans.
    await waitFor(() =>
      expect(screen.queryByText('ZORP', { selector: 'span' })).not.toBeInTheDocument(),
    )
    expect(screen.getByText('ACME', { selector: 'span' })).toBeInTheDocument()
  })
})

describe('ShippingServiceMappingPage — Carrier filter', () => {
  it('narrows rows to the selected carrier', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground'), svc(2, 'FEDEX', 'GND', 'FedEx Ground')],
      [
        rule(10, 'UPSCODE', { clientCode: 'ACME', serviceId: 1 }),
        rule(11, 'FDXCODE', { clientCode: 'ACME', serviceId: 2 }),
      ],
    ))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('UPSCODE')).toBeInTheDocument())
    expect(screen.getByText('FDXCODE')).toBeInTheDocument()

    await openFilters()
    await userEvent.selectOptions(
      screen.getByLabelText(/Filter by carrier ship via/i),
      'UPS',
    )

    await waitFor(() =>
      expect(screen.queryByText('FDXCODE', { selector: 'span' })).not.toBeInTheDocument(),
    )
    expect(screen.getByText('UPSCODE', { selector: 'span' })).toBeInTheDocument()
  })
})

describe('ShippingServiceMappingPage — Region filter', () => {
  it('narrows rows to those whose destination codes sit in the selected region', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [
        rule(10, 'EU', { destType: 'COUNTRY', destValue: 'DE', serviceId: 1 }),
        rule(11, 'NA', { destType: 'COUNTRY', destValue: 'US', serviceId: 1 }),
        rule(12, 'GLOBAL', { destType: 'ANY', destValue: null, serviceId: 1 }),
      ],
    ))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('EU')).toBeInTheDocument())

    await openFilters()
    await userEvent.selectOptions(
      screen.getByLabelText(/Filter by destination region/i),
      'Europe',
    )

    // Global-ANY rules stay hidden (per code comment).
    await waitFor(() =>
      expect(screen.queryByText('GLOBAL', { selector: 'span' })).not.toBeInTheDocument(),
    )
    expect(screen.queryByText('NA', { selector: 'span' })).not.toBeInTheDocument()
    expect(screen.getByText('EU', { selector: 'span' })).toBeInTheDocument()
  })
})

// ===================== Filter counter + Clear =====================

describe('ShippingServiceMappingPage — filter counter + Clear', () => {
  it('applying 2 filters shows counter chip "2" on Filters button', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'GND', { clientCode: 'ACME', serviceId: 1 })],
    ))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('GND')).toBeInTheDocument())

    await openFilters()
    await userEvent.selectOptions(screen.getByLabelText(/Filter by order ship via/i), 'GND')
    await userEvent.selectOptions(screen.getByLabelText(/Filter by client/i), 'ACME')

    // Filter button now includes the count "2".
    const filtersBtn = screen.getByRole('button', { name: /^Filters/i })
    await waitFor(() => expect(filtersBtn.textContent).toContain('2'))
  })

  it('Clear resets all filters and hides the counter', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'GND', { clientCode: 'ACME', serviceId: 1 })],
    ))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('GND')).toBeInTheDocument())

    await openFilters()
    await userEvent.selectOptions(screen.getByLabelText(/Filter by client/i), 'ACME')

    // Clear button (visible only when filters are applied).
    await act(async () => {
      await userEvent.click(await screen.findByRole('button', { name: /Clear/i }))
    })

    // The counter should be gone from the Filters button after Clear.
    const filtersBtn = screen.getByRole('button', { name: /^Filters$/i })
    expect(filtersBtn.textContent).not.toMatch(/\b[1-9]\b/)
  })
})
