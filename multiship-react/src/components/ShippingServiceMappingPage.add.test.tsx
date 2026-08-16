import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ShippingServiceMappingPage · add-rule slice.
 *
 * Scope (this slice only):
 *   - Clicking the toolbar "Add" button opens the inline add-strip.
 *   - "New mapping" badge visible in the strip.
 *   - Save button is DISABLED until both shipviaCd and a service are picked.
 *   - Valid save → shippingConfigService.saveRule called with the correct
 *     payload shape (destType=ANY when destCodes empty; clientCode nulled
 *     when 'Any client' picked).
 *   - Save success → notify.success('Mapping added.') + refetch (catalog
 *     called a second time).
 *   - Save rejection → notify.apiError + strip stays open.
 *   - Close (X) button collapses the strip; state resets.
 *
 * Sibling slices cover edit (inline cell editors), delete, packages drawer.
 */

// ---------- Service mocks ----------

const catalogMock = vi.fn()
const listPresetsMock = vi.fn()
const listClientsMock = vi.fn()
const listWarehousesMock = vi.fn()
const listAccountsMock = vi.fn()
const saveRuleMock = vi.fn()

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: (...args: unknown[]) => catalogMock(...args),
    listPresets: (...args: unknown[]) => listPresetsMock(...args),
    saveRule: (...args: unknown[]) => saveRuleMock(...args),
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

const notifyErrorMock = vi.fn()
const notifySuccessMock = vi.fn()
const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    error: (...args: unknown[]) => notifyErrorMock(...args),
    success: (...args: unknown[]) => notifySuccessMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    info: vi.fn(),
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
  scope: 'BOTH' as const, originCountry: 'US',
  source: 'CARRIER_API', syncedAt: new Date().toISOString(), enabled,
})

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[catalogMock, listPresetsMock, listClientsMock, listWarehousesMock,
    listAccountsMock, saveRuleMock, notifyErrorMock, notifySuccessMock,
    notifyApiErrorMock].forEach((m) => m.mockReset())
  listPresetsMock.mockResolvedValue([])
  listClientsMock.mockResolvedValue({ data: { content: [] } })
  listWarehousesMock.mockResolvedValue({ data: { content: [] } })
  listAccountsMock.mockResolvedValue([])
  // Sane catalog with a single UPS Ground service so add-strip has a pickable option.
  catalogMock.mockResolvedValue({
    services: [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
    rules: [], links: [], rulePackages: [], ruleWarehouses: [],
    originCountries: ['US'],
  })
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

async function openAddStrip() {
  await act(async () => {
    await userEvent.click(screen.getByRole('button', { name: /^Add$/ }))
  })
  await waitFor(() =>
    expect(screen.getByText(/New mapping/i)).toBeInTheDocument(),
  )
}

// ===================== Open / close =====================

describe('ShippingServiceMappingPage — add-strip toggle', () => {
  it('clicking "Add" opens the inline add-strip; "New mapping" badge visible', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())

    await openAddStrip()
    expect(screen.getByLabelText(/Order Ship Via/i)).toBeInTheDocument()
  })

  it('clicking Close (X) collapses the strip and clears the draft', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())
    await openAddStrip()

    const shipviaInput = screen.getByLabelText(/Order Ship Via/i)
    await userEvent.type(shipviaInput, 'DRAFT')

    // Close button (the X — aria-label "Close").
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Close$/i }))
    })

    // Strip collapsed → New-mapping badge gone.
    await waitFor(() => expect(screen.queryByText(/New mapping/i)).not.toBeInTheDocument())

    // Re-open → draft is blank (reset).
    await openAddStrip()
    expect(screen.getByLabelText(/Order Ship Via/i)).toHaveValue('')
  })
})

// ===================== Save-button gating =====================

describe('ShippingServiceMappingPage — Save button gating', () => {
  it('Save is DISABLED when shipviaCd is blank', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())
    await openAddStrip()

    // Pick a service but leave shipviaCd blank.
    await userEvent.selectOptions(screen.getByLabelText(/Carrier Ship Via/i), '1')

    // Save button (visible in the strip) is disabled.
    const saveBtn = screen.getByRole('button', { name: /^Save$/i })
    expect(saveBtn).toBeDisabled()
  })

  it('Save is DISABLED when no service is picked', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())
    await openAddStrip()

    await userEvent.type(screen.getByLabelText(/Order Ship Via/i), 'NEW')

    const saveBtn = screen.getByRole('button', { name: /^Save$/i })
    expect(saveBtn).toBeDisabled()
  })

  it('Save is ENABLED once shipviaCd and service are both set', async () => {
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())
    await openAddStrip()

    await userEvent.type(screen.getByLabelText(/Order Ship Via/i), 'NEW')
    await userEvent.selectOptions(screen.getByLabelText(/Carrier Ship Via/i), '1')

    expect(screen.getByRole('button', { name: /^Save$/i })).toBeEnabled()
  })
})

// ===================== Save happy + payload =====================

describe('ShippingServiceMappingPage — save payload', () => {
  it('valid save calls saveRule with destType=ANY when no dest codes are picked', async () => {
    saveRuleMock.mockResolvedValue({})
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())
    await openAddStrip()

    await userEvent.type(screen.getByLabelText(/Order Ship Via/i), 'gnd')
    await userEvent.selectOptions(screen.getByLabelText(/Carrier Ship Via/i), '1')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))
    })

    // Payload: shipviaCd uppercased (input auto-uppercases), clientCode null
    // (default), destType ANY, destValue null, serviceId numeric.
    expect(saveRuleMock).toHaveBeenCalledWith(expect.objectContaining({
      shipviaCd: 'GND',
      clientCode: null,
      destType: 'ANY',
      destValue: null,
      serviceId: 1,
      warehouseIds: [],
      allowedPresetIds: [],
    }))
  })

  it('success → notify.success("Mapping added.") + refetch + strip closes', async () => {
    saveRuleMock.mockResolvedValue({})
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())
    await openAddStrip()

    await userEvent.type(screen.getByLabelText(/Order Ship Via/i), 'gnd')
    await userEvent.selectOptions(screen.getByLabelText(/Carrier Ship Via/i), '1')
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))
    })

    await waitFor(() => expect(notifySuccessMock).toHaveBeenCalledWith('Mapping added.'))
    // Refetch: catalog called twice (mount + post-save).
    await waitFor(() => expect(catalogMock).toHaveBeenCalledTimes(2))
    // Strip closed after successful save.
    await waitFor(() => expect(screen.queryByText(/New mapping/i)).not.toBeInTheDocument())
  })

  it('save rejection → notify.apiError + strip stays open', async () => {
    saveRuleMock.mockRejectedValue(new Error('boom'))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())
    await openAddStrip()

    await userEvent.type(screen.getByLabelText(/Order Ship Via/i), 'gnd')
    await userEvent.selectOptions(screen.getByLabelText(/Carrier Ship Via/i), '1')
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to save the mapping.'),
    )
    // Strip stays open (badge still visible).
    expect(screen.getByText(/New mapping/i)).toBeInTheDocument()
  })
})
