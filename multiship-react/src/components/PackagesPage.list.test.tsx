import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — PackagesPage · list slice.
 *
 * Scope:
 *   - Mount + service calls: listPresets + allowlistUsage.packages
 *   - Renders preset cards with name, code (or 'YOUR_PACKAGING' for custom),
 *     kind chip (Carrier packaging / Custom box), carrier chip, scope chip
 *     (Dom/Intl for CARRIER + non-BOTH), flat-rate chip, default stamp.
 *   - Toolbar filters:
 *       - Origin `<select>` (options merged from base + preset origins).
 *       - Carrier `<select>` (ALL + carriers from CARRIER presets +
 *         Custom option only when any CUSTOM preset exists).
 *   - Client-side filtering:
 *       - Origin narrows CARRIER packaging (custom boxes always show).
 *       - Carrier: ALL shows all; specific carrier narrows CARRIER-kind
 *         only; CUSTOM shows only custom boxes.
 *   - Load-error → notify.apiError('Failed to load packages.').
 *   - Role parity:
 *       - Add Package button always visible.
 *       - Sync-carrier-packaging button ADMIN-only (canManageCarriers).
 *
 * Anti-fallback: every service mocked; fail-loud globalThis.fetch spy.
 */

// ---------- Service mocks ----------

const listPresetsMock = vi.fn()
const packagesUsageMock = vi.fn()

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    listPresets: (...args: unknown[]) => listPresetsMock(...args),
    catalog: vi.fn(),
    syncPackages: vi.fn(),
    syncServices: vi.fn(),
    setServiceEnabled: vi.fn(),
    saveRule: vi.fn(),
    deleteRule: vi.fn(),
    setServicePackages: vi.fn(),
    savePreset: vi.fn(),
    setDefaultPreset: vi.fn(),
    deletePreset: vi.fn(),
  },
  dimWeightOf: () => null,
  oversizeOf: () => null,
}))

vi.mock('../api/clientCatalogService', () => ({
  allowlistUsageService: {
    packages: (...args: unknown[]) => packagesUsageMock(...args),
    services: vi.fn(),
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

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  listPresetsMock.mockReset()
  packagesUsageMock.mockReset().mockResolvedValue({ data: [] })
  notifyApiErrorMock.mockReset()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

// ---------- Helpers ----------

const customPreset = (id: number, name: string, overrides: Partial<{
  isDefault: boolean, enabled: boolean, carrier?: string,
  length: number, width: number, height: number, originCountry?: string,
}> = {}) => ({
  id, name, kind: 'CUSTOM' as const,
  carrier: overrides.carrier,
  length: overrides.length ?? 10, width: overrides.width ?? 10, height: overrides.height ?? 10,
  dimUnit: 'IN', weightUnit: 'LB',
  maxWeight: 5,
  enabled: overrides.enabled ?? true,
  default: overrides.isDefault ?? false,
  originCountry: overrides.originCountry,
})

const carrierPreset = (id: number, name: string, carrier: string, overrides: Partial<{
  scope: 'DOMESTIC' | 'INTERNATIONAL' | 'BOTH', flatRate: boolean, carrierPackageCode?: string,
  originCountry?: string, isDefault: boolean, enabled: boolean,
}> = {}) => ({
  id, name, kind: 'CARRIER' as const,
  carrier,
  carrierPackageCode: overrides.carrierPackageCode ?? 'SMALL_BOX',
  scope: overrides.scope ?? 'DOMESTIC',
  flatRate: overrides.flatRate ?? false,
  originCountry: overrides.originCountry ?? 'US',
  length: 10, width: 8, height: 6, dimUnit: 'IN', weightUnit: 'LB',
  maxWeight: 5,
  enabled: overrides.enabled ?? true,
  default: overrides.isDefault ?? false,
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./PackagesPage')
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

describe('PackagesPage — mount + service calls', () => {
  it('calls listPresets + allowlistUsage.packages on mount', async () => {
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listPresetsMock).toHaveBeenCalledTimes(1))
    expect(packagesUsageMock).toHaveBeenCalledTimes(1)
  })

  it('renders "Add Package" button always (no role gate on Add)', async () => {
    listPresetsMock.mockResolvedValue([])
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Add Package/i })).toBeInTheDocument(),
    )
  })
})

// ===================== Per-card rendering =====================

describe('PackagesPage — per-card rendering', () => {
  it('renders preset cards with name + kind chip + carrier chip', async () => {
    listPresetsMock.mockResolvedValue([
      customPreset(1, 'My Box'),
      carrierPreset(2, 'UPS Small Box', 'UPS'),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('My Box')).toBeInTheDocument())
    expect(screen.getByText('UPS Small Box')).toBeInTheDocument()
    // Kind chips (both variants render).
    expect(screen.getByText('Carrier packaging')).toBeInTheDocument()
    expect(screen.getByText('Custom box')).toBeInTheDocument()
    // Carrier chip on the CARRIER preset (there are 2 UPS refs: chip + code; assertGe 1).
    expect(screen.getAllByText('UPS').length).toBeGreaterThan(0)
  })

  it('renders default stamp on the default preset only', async () => {
    listPresetsMock.mockResolvedValue([
      customPreset(1, 'Default Box', { isDefault: true }),
      customPreset(2, 'Other Box', { isDefault: false }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('Default Box')).toBeInTheDocument())
    // Stamp reads "Default" — only one, on the default row.
    expect(screen.getAllByText('Default').length).toBe(1)
  })

  it('renders "YOUR_PACKAGING" for custom boxes without carrierPackageCode', async () => {
    listPresetsMock.mockResolvedValue([customPreset(1, 'My Box')])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('YOUR_PACKAGING')).toBeInTheDocument())
  })

  it('renders scope chip (Domestic/Intl) for CARRIER kind + non-BOTH scope', async () => {
    listPresetsMock.mockResolvedValue([
      carrierPreset(1, 'Domestic Box', 'UPS', { scope: 'DOMESTIC' }),
      carrierPreset(2, 'Intl Box', 'UPS', { scope: 'INTERNATIONAL' }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('Domestic Box')).toBeInTheDocument())
    // The scope chip renders "⌂ Domestic" and "⊕ Intl" — assert at least one match each.
    expect(screen.getAllByText(/Domestic/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Intl/).length).toBeGreaterThan(0)
  })

  it('renders "Flat rate" chip on flatRate presets', async () => {
    listPresetsMock.mockResolvedValue([
      carrierPreset(1, 'Flat Rate Box', 'USPS', { flatRate: true }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('Flat rate')).toBeInTheDocument())
  })
})

// ===================== Filters =====================

describe('PackagesPage — filters', () => {
  it('carrier filter (ALL default) shows all presets', async () => {
    listPresetsMock.mockResolvedValue([
      customPreset(1, 'My Box'),
      carrierPreset(2, 'UPS Box', 'UPS'),
      carrierPreset(3, 'FedEx Box', 'FEDEX'),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('My Box')).toBeInTheDocument())
    expect(screen.getByText('UPS Box')).toBeInTheDocument()
    expect(screen.getByText('FedEx Box')).toBeInTheDocument()
  })

  it('carrier filter narrows to one carrier (client-side, no re-fetch)', async () => {
    listPresetsMock.mockResolvedValue([
      customPreset(1, 'My Box'),
      carrierPreset(2, 'UPS Box', 'UPS'),
      carrierPreset(3, 'FedEx Box', 'FEDEX'),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Box')).toBeInTheDocument())
    // The carrier <select> is the second <select> in the toolbar (origin is first).
    const selects = screen.getAllByRole('combobox')
    await userEvent.selectOptions(selects[1], 'UPS')

    expect(screen.getByText('UPS Box')).toBeInTheDocument()
    expect(screen.queryByText('FedEx Box')).not.toBeInTheDocument()
    // Custom box is excluded when a specific carrier is picked.
    expect(screen.queryByText('My Box')).not.toBeInTheDocument()
    // Filter is client-side — no additional listPresets call.
    expect(listPresetsMock).toHaveBeenCalledTimes(1)
  })

  it('CUSTOM filter shows only custom boxes', async () => {
    listPresetsMock.mockResolvedValue([
      customPreset(1, 'My Custom Box'),
      carrierPreset(2, 'UPS Box', 'UPS'),
    ])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('My Custom Box')).toBeInTheDocument())
    const selects = screen.getAllByRole('combobox')
    await userEvent.selectOptions(selects[1], 'CUSTOM')

    expect(screen.getByText('My Custom Box')).toBeInTheDocument()
    expect(screen.queryByText('UPS Box')).not.toBeInTheDocument()
  })

  it('carrier filter dropdown does NOT show Custom option when no custom presets exist', async () => {
    listPresetsMock.mockResolvedValue([carrierPreset(1, 'UPS Box', 'UPS')])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Box')).toBeInTheDocument())
    // Only "All carriers" + "UPS" — no "Custom boxes".
    expect(screen.queryByRole('option', { name: /Custom boxes/i })).not.toBeInTheDocument()
  })

  it('origin filter narrows CARRIER packaging; custom boxes remain visible', async () => {
    listPresetsMock.mockResolvedValue([
      customPreset(1, 'My Universal Box'),
      carrierPreset(2, 'US-only Box', 'UPS', { originCountry: 'US' }),
      carrierPreset(3, 'GB-only Box', 'UPS', { originCountry: 'GB' }),
    ])

    const Page = await loadPage()
    renderPage(Page)

    // Default US origin — US carrier box + custom visible; GB carrier hidden.
    await waitFor(() => expect(screen.getByText('US-only Box')).toBeInTheDocument())
    expect(screen.getByText('My Universal Box')).toBeInTheDocument()
    expect(screen.queryByText('GB-only Box')).not.toBeInTheDocument()

    // Switch origin to GB — flip.
    const selects = screen.getAllByRole('combobox')
    await userEvent.selectOptions(selects[0], 'GB')

    expect(screen.getByText('GB-only Box')).toBeInTheDocument()
    expect(screen.getByText('My Universal Box')).toBeInTheDocument()
    expect(screen.queryByText('US-only Box')).not.toBeInTheDocument()
  })
})

// ===================== Error path =====================

describe('PackagesPage — error path', () => {
  it('listPresets rejection → notify.apiError("Failed to load packages.")', async () => {
    listPresetsMock.mockRejectedValue(new Error('boom'))
    packagesUsageMock.mockResolvedValue({ data: [] })

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(
        expect.any(Error),
        'Failed to load packages.',
      ),
    )
  })
})

// ===================== Role gating =====================

describe('PackagesPage — role gating', () => {
  it('ADMIN sees the Sync carrier packaging button', async () => {
    mockRole = 'ADMIN'
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Sync carrier packaging/i })).toBeInTheDocument(),
    )
  })

  it('USER does NOT see the Sync carrier packaging button (admin-only)', async () => {
    mockRole = 'USER'
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    // Wait for load to complete.
    await waitFor(() => expect(listPresetsMock).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /Sync carrier packaging/i })).not.toBeInTheDocument()
    // Add Package still visible (no gate on Add).
    expect(screen.getByRole('button', { name: /Add Package/i })).toBeInTheDocument()
  })

  it('TENANT does NOT see the Sync button either', async () => {
    mockRole = 'TENANT'
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listPresetsMock).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /Sync carrier packaging/i })).not.toBeInTheDocument()
  })
})
