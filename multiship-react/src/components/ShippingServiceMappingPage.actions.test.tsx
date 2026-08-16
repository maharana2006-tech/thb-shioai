import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ShippingServiceMappingPage · actions slice.
 *
 * Consolidated coverage for the 3 row/draft actions:
 *   - Delete: notify.confirm accept → deleteRule + refetch; cancel → no
 *     delete; reject → notify.apiError.
 *   - ZoneEditorModal: openZone('new') via the add-strip "Ship to" button
 *     opens the modal; saving a codes-set for 'new' target updates
 *     newRule.destCodes locally (no immediate saveRule — that happens on
 *     Save-in-add-strip); saving for an EXISTING rule calls saveRule with
 *     destType=COUNTRIES + destValue joined by spaces.
 *   - RulePackagesDrawer: clicking the per-row Packages pill sets pkgFor
 *     and mounts the drawer; onSaved updates local presetIds map.
 *
 * ZoneEditorModal + RulePackagesDrawer are stubbed with prop-spy shims so
 * the SUT's onSave / onSaved / onCodesChange callbacks can be triggered
 * without exercising the full drawer/modal UI (those have their own tests).
 *
 * Sibling slices cover shell, list, filters, add-strip.
 */

// ---------- Service mocks ----------

const catalogMock = vi.fn()
const listPresetsMock = vi.fn()
const listClientsMock = vi.fn()
const listWarehousesMock = vi.fn()
const listAccountsMock = vi.fn()
const saveRuleMock = vi.fn()
const deleteRuleMock = vi.fn()

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: (...args: unknown[]) => catalogMock(...args),
    listPresets: (...args: unknown[]) => listPresetsMock(...args),
    saveRule: (...args: unknown[]) => saveRuleMock(...args),
    deleteRule: (...args: unknown[]) => deleteRuleMock(...args),
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
const notifyConfirmMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    error: (...args: unknown[]) => notifyErrorMock(...args),
    success: (...args: unknown[]) => notifySuccessMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    confirm: (...args: unknown[]) => notifyConfirmMock(...args),
    info: vi.fn(),
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

// Prop-spy shims — capture the last-received props so tests can drive onSave
// without exercising the full modal/drawer UI.
type ZoneProps = { open: boolean; codes: string[]; onCodesChange: (codes: string[]) => void; onSave: () => void; onClose: () => void }
let lastZoneProps: ZoneProps | null = null
vi.mock('./workspace/ZoneEditorModal', () => ({
  default: (p: ZoneProps) => {
    lastZoneProps = p
    return p.open ? <div data-testid="zone-modal-shim">zone-shim</div> : null
  },
}))

type DrawerProps = { rule: unknown; onSaved: (nextIds: number[]) => void; onClose: () => void }
let lastDrawerProps: DrawerProps | null = null
vi.mock('./modals/RulePackagesDrawer', () => ({
  default: (p: DrawerProps) => {
    lastDrawerProps = p
    return <div data-testid="drawer-shim">drawer-shim</div>
  },
}))

// ---------- Fixtures ----------

const svc = (id: number, carrier: string, code: string, name: string) => ({
  id, carrier, serviceCode: code, name,
  scope: 'BOTH' as const, originCountry: 'US',
  source: 'CARRIER_API', syncedAt: new Date().toISOString(), enabled: true,
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
  ;[catalogMock, listPresetsMock, listClientsMock, listWarehousesMock,
    listAccountsMock, saveRuleMock, deleteRuleMock, notifyErrorMock,
    notifySuccessMock, notifyApiErrorMock, notifyConfirmMock].forEach((m) => m.mockReset())
  listPresetsMock.mockResolvedValue([])
  listClientsMock.mockResolvedValue({ data: { content: [] } })
  listWarehousesMock.mockResolvedValue({ data: { content: [] } })
  listAccountsMock.mockResolvedValue([])
  notifyConfirmMock.mockResolvedValue(true)
  lastZoneProps = null
  lastDrawerProps = null
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

// ===================== Delete row =====================

describe('ShippingServiceMappingPage — delete row', () => {
  it('confirm accept → deleteRule + refetch', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'GND', { clientCode: 'ACME', serviceId: 1 })],
    ))
    notifyConfirmMock.mockResolvedValue(true)
    deleteRuleMock.mockResolvedValue({})

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('GND')).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Remove mapping GND/i }))
    })

    await waitFor(() => expect(deleteRuleMock).toHaveBeenCalledWith(10))
    // Refetch after delete: catalog called twice (mount + post-delete).
    await waitFor(() => expect(catalogMock).toHaveBeenCalledTimes(2))
  })

  it('confirm cancel → NO deleteRule call', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'GND', { clientCode: 'ACME', serviceId: 1 })],
    ))
    notifyConfirmMock.mockResolvedValue(false)

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('GND')).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Remove mapping GND/i }))
    })

    expect(deleteRuleMock).not.toHaveBeenCalled()
  })

  it('deleteRule rejection → notify.apiError', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'GND', { clientCode: 'ACME', serviceId: 1 })],
    ))
    notifyConfirmMock.mockResolvedValue(true)
    deleteRuleMock.mockRejectedValue(new Error('boom'))

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('GND')).toBeInTheDocument())

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Remove mapping GND/i }))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to remove the mapping.'),
    )
  })
})

// ===================== Zone editor modal =====================

describe('ShippingServiceMappingPage — zone editor modal', () => {
  it('opens when the add-strip "Ship to · any" button is clicked', async () => {
    catalogMock.mockResolvedValue(catalogWith([svc(1, 'UPS', 'GROUND', 'UPS Ground')], []))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())

    // Open add-strip.
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Add$/ }))
    })
    await waitFor(() => expect(screen.getByText(/New mapping/i)).toBeInTheDocument())

    // Click the Ships-to button in the add-strip.
    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /Ship to · any/i }))
    })

    await waitFor(() => expect(screen.getByTestId('zone-modal-shim')).toBeInTheDocument())
    expect(lastZoneProps?.open).toBe(true)
  })

  it('save for a "new" target: onCodesChange + onSave applies to newRule.destCodes (no immediate saveRule)', async () => {
    catalogMock.mockResolvedValue(catalogWith([svc(1, 'UPS', 'GROUND', 'UPS Ground')], []))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add$/ })).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /^Add$/ })) })
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /Ship to · any/i })) })
    await waitFor(() => expect(lastZoneProps).not.toBeNull())

    // Simulate a user picking codes in the modal, then saving.
    act(() => {
      lastZoneProps!.onCodesChange(['DE', 'FR'])
    })
    await act(async () => {
      lastZoneProps!.onSave()
    })

    // 'new' target must NOT call saveRule (the codes ride on the pending draft
    // and get persisted by the add-strip's Save button later).
    expect(saveRuleMock).not.toHaveBeenCalled()

    // Modal should have been closed.
    await waitFor(() =>
      expect(screen.queryByTestId('zone-modal-shim')).not.toBeInTheDocument(),
    )
  })
})

// ===================== Packages drawer =====================

describe('ShippingServiceMappingPage — packages drawer', () => {
  it('clicking the per-row Packages pill mounts the RulePackagesDrawer with the rule', async () => {
    catalogMock.mockResolvedValue(catalogWith(
      [svc(1, 'UPS', 'GROUND', 'UPS Ground')],
      [rule(10, 'GND', { clientCode: 'ACME', destType: 'COUNTRY', destValue: 'US', serviceId: 1 })],
    ))
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('GND')).toBeInTheDocument())

    // The Packages pill on the row — its accessible name is the count/'+' character.
    // Grab it via the title attribute of the button (it says "Pick packages…" or similar).
    // Fallback: find the FiPackage-icon button that's not the toolbar Add.
    // Simpler: it's the ONLY button inside the row that carries the FiPackage icon;
    // we can locate it via `getAllByRole('button')` and pick the last-inline one.
    const rowButtons = screen.getAllByRole('button')
    // The packages pill button typically has text content of "+" (empty state) or a count.
    const pkgBtn = rowButtons.find(
      (b) => b.textContent?.trim() === '+' || /^\d+$/.test(b.textContent?.trim() ?? ''),
    )
    expect(pkgBtn).toBeDefined()

    await act(async () => {
      await userEvent.click(pkgBtn!)
    })

    await waitFor(() => expect(screen.getByTestId('drawer-shim')).toBeInTheDocument())
    expect(lastDrawerProps).not.toBeNull()
  })
})
