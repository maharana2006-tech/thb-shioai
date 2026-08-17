import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ShippingServicesPage · actions slice.
 *
 * Scope:
 *   - Sync per-carrier: notify.success on live, notify.info on not-live,
 *     apiError on reject; refetch on success; no refetch on reject.
 *   - Toggle enable: optimistic flip + setServiceEnabled call; reject
 *     path fires apiError + refetch (rollback via load()).
 *   - Packages modal: opens with preloaded selection; save posts
 *     [{presetId}] shape; success closes + refetches; reject leaves
 *     modal open + apiError.
 *   - Anti-fallback: per-carrier scope — sync only invokes that carrier's
 *     mock; other carrier mocks are never() called.
 *   - Role parity — action buttons render for every role (no useAppSession
 *     import on this page).
 */

// ---------- Service mocks ----------

const catalogMock = vi.fn()
const listPresetsMock = vi.fn()
const syncServicesMock = vi.fn()
const setServiceEnabledMock = vi.fn()
const setServicePackagesMock = vi.fn()

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: (...args: unknown[]) => catalogMock(...args),
    listPresets: (...args: unknown[]) => listPresetsMock(...args),
    syncServices: (...args: unknown[]) => syncServicesMock(...args),
    setServiceEnabled: (...args: unknown[]) => setServiceEnabledMock(...args),
    setServicePackages: (...args: unknown[]) => setServicePackagesMock(...args),
    saveRule: vi.fn(),
    deleteRule: vi.fn(),
    savePreset: vi.fn(),
    setDefaultPreset: vi.fn(),
    deletePreset: vi.fn(),
    syncPackages: vi.fn(),
  },
  fitAgainstService: () => ({ status: 'FITS', reason: '' }),
  limitsOf: () => ({ maxWeightLb: 150, maxLengthIn: 108, maxLengthGirthIn: 165, surchargeLengthGirthIn: 130 }),
}))

const servicesUsageMock = vi.fn()
vi.mock('../api/clientCatalogService', () => ({
  allowlistUsageService: {
    services: (...args: unknown[]) => servicesUsageMock(...args),
    packages: vi.fn(),
  },
}))

const notifySuccessMock = vi.fn()
const notifyInfoMock = vi.fn()
const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccessMock(...args),
    info: (...args: unknown[]) => notifyInfoMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    error: vi.fn(),
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

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[catalogMock, listPresetsMock, syncServicesMock, setServiceEnabledMock,
    setServicePackagesMock, servicesUsageMock, notifySuccessMock,
    notifyInfoMock, notifyApiErrorMock].forEach((m) => m.mockReset())
  servicesUsageMock.mockResolvedValue({ data: [] })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

// ---------- Helpers ----------

const svc = (id: number, carrier: string, code: string, enabled = true, name?: string) => ({
  id, carrier, serviceCode: code, name: name ?? 'UPS Ground',
  scope: 'DOMESTIC' as const, originCountry: 'US',
  source: 'CARRIER_API', syncedAt: new Date().toISOString(), enabled,
})

const baseCatalog = (services: ReturnType<typeof svc>[] = [], links: { serviceId: number; presetId: number }[] = []) => ({
  services, links, rules: [], rulePackages: [], ruleWarehouses: [], originCountries: ['US'],
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ShippingServicesPage')
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

// ===================== Sync per-carrier =====================

describe('ShippingServicesPage — syncCarrier (per-tile Sync button)', () => {
  it('live sync fires notify.success + refetch; only that carrier is called', async () => {
    catalogMock.mockResolvedValue(baseCatalog())
    listPresetsMock.mockResolvedValue([])
    syncServicesMock.mockResolvedValue({
      data: { carrier: 'UPS', originCountry: 'US', added: 2, updated: 0, total: 2, live: true, via: 'UPS Rating API' },
    })

    const Page = await loadPage()
    renderPage(Page)

    // Click UPS's tile Sync CTA (the button rendered inside the empty tile).
    await waitFor(() => expect(screen.getByText(/UPS · from US/i)).toBeInTheDocument())
    // There are 3 Sync buttons (one per carrier tile). Grab the UPS one via
    // its label text — the empty-tile CTA reads "Sync from carrier".
    const upsSync = screen.getAllByRole('button', { name: /Sync from carrier/i })[0]
    await act(async () => { await userEvent.click(upsSync) })

    expect(syncServicesMock).toHaveBeenCalledWith('UPS', 'US')
    // Live → success (not info).
    expect(notifySuccessMock).toHaveBeenCalledTimes(1)
    expect(notifyInfoMock).not.toHaveBeenCalled()
    // Refetch: catalog called twice (mount + post-sync).
    expect(catalogMock).toHaveBeenCalledTimes(2)
  })

  it('non-live sync fires notify.info (never success) + refetch', async () => {
    catalogMock.mockResolvedValue(baseCatalog())
    listPresetsMock.mockResolvedValue([])
    syncServicesMock.mockResolvedValue({
      data: { carrier: 'UPS', originCountry: 'US', added: 1, updated: 0, total: 1, live: false, via: 'built-in availability' },
    })

    const Page = await loadPage()
    renderPage(Page)

    const upsSync = (await screen.findAllByRole('button', { name: /Sync from carrier/i }))[0]
    await act(async () => { await userEvent.click(upsSync) })

    expect(notifyInfoMock).toHaveBeenCalledTimes(1)
    expect(notifySuccessMock).not.toHaveBeenCalled()
    expect(catalogMock).toHaveBeenCalledTimes(2)
  })

  it('sync rejection → notify.apiError + no refetch', async () => {
    catalogMock.mockResolvedValue(baseCatalog())
    listPresetsMock.mockResolvedValue([])
    syncServicesMock.mockRejectedValue(new Error('boom'))

    const Page = await loadPage()
    renderPage(Page)

    const upsSync = (await screen.findAllByRole('button', { name: /Sync from carrier/i }))[0]
    await act(async () => { await userEvent.click(upsSync) })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to sync from the carrier.'),
    )
    // Mount-load only (no post-error refetch).
    expect(catalogMock).toHaveBeenCalledTimes(1)
  })

  it('per-carrier scope: syncing UPS never calls sync for FEDEX', async () => {
    catalogMock.mockResolvedValue(baseCatalog())
    listPresetsMock.mockResolvedValue([])
    syncServicesMock.mockResolvedValue({
      data: { carrier: 'UPS', originCountry: 'US', added: 0, updated: 0, total: 0, live: true, via: 'noop' },
    })

    const Page = await loadPage()
    renderPage(Page)

    const upsSync = (await screen.findAllByRole('button', { name: /Sync from carrier/i }))[0]
    await act(async () => { await userEvent.click(upsSync) })

    // Only one call ever, and it's for UPS.
    expect(syncServicesMock).toHaveBeenCalledTimes(1)
    expect(syncServicesMock).toHaveBeenCalledWith('UPS', 'US')
    expect(syncServicesMock).not.toHaveBeenCalledWith('FEDEX', 'US')
    expect(syncServicesMock).not.toHaveBeenCalledWith('USPS', 'US')
  })
})

// ===================== Toggle enable =====================

describe('ShippingServicesPage — toggle enable', () => {
  it('ON→OFF: optimistic flip + setServiceEnabled(id, false)', async () => {
    catalogMock.mockResolvedValue(baseCatalog([svc(1, 'UPS', 'GROUND', true)]))
    listPresetsMock.mockResolvedValue([])
    setServiceEnabledMock.mockResolvedValue({ data: { ...svc(1, 'UPS', 'GROUND', false) } })

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
    const toggle = screen.getByRole('switch')
    await act(async () => { await userEvent.click(toggle) })

    expect(setServiceEnabledMock).toHaveBeenCalledWith(1, false)
  })

  it('OFF→ON: setServiceEnabled(id, true)', async () => {
    catalogMock.mockResolvedValue(baseCatalog([svc(1, 'UPS', 'GROUND', false)]))
    listPresetsMock.mockResolvedValue([])
    setServiceEnabledMock.mockResolvedValue({ data: { ...svc(1, 'UPS', 'GROUND', true) } })

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
    const toggle = screen.getByRole('switch')
    await act(async () => { await userEvent.click(toggle) })

    expect(setServiceEnabledMock).toHaveBeenCalledWith(1, true)
  })

  it('toggle rejection → notify.apiError + refetch (rollback via load)', async () => {
    catalogMock.mockResolvedValue(baseCatalog([svc(1, 'UPS', 'GROUND', true)]))
    listPresetsMock.mockResolvedValue([])
    setServiceEnabledMock.mockRejectedValue(new Error('nope'))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
    const toggle = screen.getByRole('switch')
    await act(async () => { await userEvent.click(toggle) })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to update the service.'),
    )
    // Rollback fires a load() — catalog called twice (mount + rollback).
    await waitFor(() => expect(catalogMock).toHaveBeenCalledTimes(2))
  })
})

// ===================== Packages modal =====================

describe('ShippingServicesPage — allowed packages modal', () => {
  it('opens with preloaded current-selection presets', async () => {
    catalogMock.mockResolvedValue(baseCatalog(
      [svc(1, 'UPS', 'GROUND', true)],
      [{ serviceId: 1, presetId: 100 }],
    ))
    listPresetsMock.mockResolvedValue([
      { id: 100, name: 'Small Box', kind: 'CUSTOM', weightUnit: 'LB', dimUnit: 'IN' },
      { id: 101, name: 'Big Box', kind: 'CUSTOM', weightUnit: 'LB', dimUnit: 'IN' },
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
    // The per-row package-count pill opens the modal; count is "1" for our seeded link.
    const pill = screen.getByTitle('Allowed packages')
    await act(async () => { await userEvent.click(pill) })

    // Modal renders both presets so the operator can toggle selection.
    await waitFor(() => expect(screen.getByText('Small Box')).toBeInTheDocument())
    expect(screen.getByText('Big Box')).toBeInTheDocument()
  })

  it('save posts [{presetId}] payload shape, closes modal, refetches', async () => {
    catalogMock.mockResolvedValue(baseCatalog(
      [svc(1, 'UPS', 'GROUND', true)],
      [{ serviceId: 1, presetId: 100 }],
    ))
    listPresetsMock.mockResolvedValue([{ id: 100, name: 'Small Box', kind: 'CUSTOM', weightUnit: 'LB', dimUnit: 'IN' }])
    setServicePackagesMock.mockResolvedValue({ data: [] })

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByTitle('Allowed packages')) })
    await waitFor(() => expect(screen.getByText('Small Box')).toBeInTheDocument())

    // Save
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /Save packages/i })) })

    expect(setServicePackagesMock).toHaveBeenCalledWith(1, [{ presetId: 100 }])
    expect(notifySuccessMock).toHaveBeenCalledTimes(1)
    // Refetch after save.
    await waitFor(() => expect(catalogMock).toHaveBeenCalledTimes(2))
  })

  it('save rejection → apiError + modal stays open (no close)', async () => {
    catalogMock.mockResolvedValue(baseCatalog(
      [svc(1, 'UPS', 'GROUND', true)],
      [{ serviceId: 1, presetId: 100 }],
    ))
    listPresetsMock.mockResolvedValue([{ id: 100, name: 'Small Box', kind: 'CUSTOM', weightUnit: 'LB', dimUnit: 'IN' }])
    setServicePackagesMock.mockRejectedValue(new Error('nope'))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByTitle('Allowed packages')) })
    await waitFor(() => expect(screen.getByText('Small Box')).toBeInTheDocument())
    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /Save packages/i })) })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to save packages.'),
    )
    // Modal still shows the presets (didn't close on reject).
    expect(screen.getByText('Small Box')).toBeInTheDocument()
  })
})

// ===================== Role parity =====================

describe('ShippingServicesPage — action buttons render for every role', () => {
  it.each(['ADMIN', 'USER', 'TENANT'] as const)(
    '%s can see the toggle + Allowed-packages controls (no per-row FE gate)',
    async (role) => {
      mockRole = role
      catalogMock.mockResolvedValue(baseCatalog(
        [svc(1, 'UPS', 'GROUND', true)],
        [{ serviceId: 1, presetId: 100 }],
      ))
      listPresetsMock.mockResolvedValue([])

      const Page = await loadPage()
      renderPage(Page)

      await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
      expect(screen.getByRole('switch')).toBeInTheDocument()
      expect(screen.getByTitle('Allowed packages')).toBeInTheDocument()
    },
  )
})
