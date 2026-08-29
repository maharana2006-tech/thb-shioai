import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — ShippingServicesPage · list slice.
 *
 * Scope (this slice only):
 *   - Mounts + calls shippingConfigService.catalog + listPresets +
 *     allowlistUsageService.services on load.
 *   - Renders 3 carrier tiles (UPS / FEDEX / USPS) — always, unconditional.
 *   - Per-row: service name + service code + scope chip (Dom/Intl/Both) +
 *     enabled/disabled switch + package-count pill.
 *   - Health-strip cards show `enabled/total` per origin + total link count.
 *   - Origin filter narrows visible services (client-side; no re-fetch).
 *   - Un-synced origin → per-carrier empty tile with sync CTA.
 *   - Load-error path → notify.apiError("Failed to load the catalog.").
 *   - Role parity — ADMIN / USER / TENANT render identically (no useAppSession
 *     import on this page).
 *
 * TODO: `page-tests-bug` — the page has NO top-level empty state; even
 * with zero synced services the three per-carrier tiles always render.
 * File a follow-up issue and pin current behavior here.
 * See #222 for the missing top-level empty state.
 *
 * Anti-fallback: every carrier-adjacent service is mocked and a fetch
 * spy fails the run loudly on any un-mocked outbound request.
 */

// ---------- Service mocks (hoisted before the component is imported) ----------

const catalogMock = vi.fn()
const listPresetsMock = vi.fn()

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: (...args: unknown[]) => catalogMock(...args),
    listPresets: (...args: unknown[]) => listPresetsMock(...args),
    // Unused-in-this-slice methods still need to be present for the module namespace.
    syncServices: vi.fn(),
    setServiceEnabled: vi.fn(),
    saveRule: vi.fn(),
    deleteRule: vi.fn(),
    setServicePackages: vi.fn(),
    savePreset: vi.fn(),
    setDefaultPreset: vi.fn(),
    deletePreset: vi.fn(),
    syncPackages: vi.fn(),
  },
  // fitAgainstService / limitsOf are pure helpers used by the modal; keep
  // real impls (they don't touch the network).
  fitAgainstService: () => ({ status: 'FITS', reason: '' }),
  limitsOf: () => null,
}))

const servicesUsageMock = vi.fn()
vi.mock('../api/clientCatalogService', () => ({
  allowlistUsageService: {
    services: (...args: unknown[]) => servicesUsageMock(...args),
    packages: vi.fn(),
  },
}))

const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

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

vi.mock('../api/apiClient', () => ({
  isAbortError: () => false,
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn(),
  },
}))

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  catalogMock.mockReset()
  listPresetsMock.mockReset()
  servicesUsageMock.mockReset().mockResolvedValue({ data: [] })
  notifyApiErrorMock.mockReset()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

// ---------- Helpers ----------

const svc = (
  id: number,
  carrier: string,
  code: string,
  overrides: Partial<{
    name: string
    enabled: boolean
    scope: 'DOMESTIC' | 'INTERNATIONAL' | 'BOTH'
    originCountry: string
    source: string
    syncedAt: string
    brandedPackagingAllowed: boolean
  }> = {},
) => ({
  id,
  carrier,
  serviceCode: code,
  name: overrides.name ?? `${carrier} ${code}`,
  scope: overrides.scope ?? 'DOMESTIC',
  originCountry: overrides.originCountry ?? 'US',
  source: overrides.source ?? 'CARRIER_API',
  syncedAt: overrides.syncedAt ?? new Date().toISOString(),
  enabled: overrides.enabled ?? true,
  brandedPackagingAllowed: overrides.brandedPackagingAllowed ?? true,
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ShippingServicesPage')
  return mod.default
}

/** Renders the page inside a MemoryRouter + Outlet that supplies the
 *  SettingsOutletContext the page reads via useOutletContext. */
function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter>
      <Routes>
        <Route
          element={<Outlet context={{ registerRefresh: vi.fn() }} />}
        >
          <Route path="*" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// ===================== Positive: mount + service calls =====================

describe('ShippingServicesPage — mount + service calls', () => {
  it('calls catalog + listPresets + allowlistUsage.services on mount', async () => {
    catalogMock.mockResolvedValue({
      services: [], links: [], rules: [], rulePackages: [],
      ruleWarehouses: [], originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(catalogMock).toHaveBeenCalledTimes(1))
    expect(listPresetsMock).toHaveBeenCalledTimes(1)
    expect(servicesUsageMock).toHaveBeenCalledTimes(1)
  })

  it('renders 3 carrier tiles unconditionally (UPS + FEDEX + USPS)', async () => {
    catalogMock.mockResolvedValue({
      services: [], links: [], rules: [], rulePackages: [],
      ruleWarehouses: [], originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      // Each tile header reads "<CARRIER> · from US" — text-transform: uppercase.
      expect(screen.getByText(/UPS · from US/i)).toBeInTheDocument()
      expect(screen.getByText(/FEDEX · from US/i)).toBeInTheDocument()
      expect(screen.getByText(/USPS · from US/i)).toBeInTheDocument()
    })
  })
})

// ===================== Positive: per-row rendering =====================

describe('ShippingServicesPage — per-row rendering', () => {
  it('renders service name, code, scope chip, and package-count pill', async () => {
    catalogMock.mockResolvedValue({
      services: [
        svc(1, 'UPS', 'GROUND', { name: 'UPS Ground', enabled: true, scope: 'DOMESTIC' }),
        svc(2, 'UPS', 'NEXT_DAY_AIR', { name: 'UPS Next Day Air', enabled: false, scope: 'BOTH' }),
      ],
      links: [{ serviceId: 1, presetId: 100 }],
      rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
    expect(screen.getByText('UPS Next Day Air')).toBeInTheDocument()
    expect(screen.getByText('GROUND')).toBeInTheDocument()
    expect(screen.getByText('NEXT_DAY_AIR')).toBeInTheDocument()
    // Scope chips — Dom on GROUND (DOMESTIC), Both on NEXT_DAY_AIR (BOTH).
    expect(screen.getByText('Dom')).toBeInTheDocument()
    expect(screen.getByText('Both')).toBeInTheDocument()
    // Enable switch + aria-checked
    const switches = screen.getAllByRole('switch')
    expect(switches.some((s) => s.getAttribute('aria-checked') === 'true')).toBe(true)
    expect(switches.some((s) => s.getAttribute('aria-checked') === 'false')).toBe(true)
  })

  it('renders the enabled/total tally on the header banner', async () => {
    catalogMock.mockResolvedValue({
      services: [
        svc(1, 'UPS', 'GROUND', { enabled: true }),
        svc(2, 'UPS', 'NEXT_DAY_AIR', { enabled: false }),
        svc(3, 'UPS', 'EXPRESS', { enabled: true }),
      ],
      links: [], rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('2/3 ON')).toBeInTheDocument())
  })
})

// ===================== Positive: health strip =====================

describe('ShippingServicesPage — health strip', () => {
  it('shows enabled/total across all carriers + total link count for the origin', async () => {
    catalogMock.mockResolvedValue({
      services: [
        svc(1, 'UPS', 'GROUND', { enabled: true }),
        svc(2, 'FEDEX', 'GROUND', { enabled: false }),
      ],
      links: [{ serviceId: 1, presetId: 100 }, { serviceId: 2, presetId: 101 }],
      rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      expect(screen.getByText(/Services from US/i)).toBeInTheDocument()
      // 1 enabled / 2 total.
      expect(screen.getByText('1/2')).toBeInTheDocument()
      // 2 links across the two services.
      expect(screen.getByText('2')).toBeInTheDocument()
    })
  })
})

// ===================== Origin filter =====================

describe('ShippingServicesPage — origin filter', () => {
  it('changing origin narrows visible services (client-side, no re-fetch)', async () => {
    const usSvc = svc(1, 'UPS', 'GROUND', { originCountry: 'US', name: 'UPS Ground (US)' })
    const gbSvc = svc(2, 'UPS', 'EXPRESS', { originCountry: 'GB', name: 'UPS Express (GB)' })
    catalogMock.mockResolvedValue({
      services: [usSvc, gbSvc],
      links: [], rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US', 'GB'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    // Default origin US — only US service visible.
    await waitFor(() => expect(screen.getByText('UPS Ground (US)')).toBeInTheDocument())
    expect(screen.queryByText('UPS Express (GB)')).not.toBeInTheDocument()

    // Switch origin to GB — client-side filter flips.
    await userEvent.selectOptions(screen.getByRole('combobox'), 'GB')

    expect(screen.getByText('UPS Express (GB)')).toBeInTheDocument()
    expect(screen.queryByText('UPS Ground (US)')).not.toBeInTheDocument()
    // No new catalog() call — filter is purely client-side.
    expect(catalogMock).toHaveBeenCalledTimes(1)
  })

  it('un-synced origin renders per-carrier empty tiles with sync CTA', async () => {
    catalogMock.mockResolvedValue({
      services: [], links: [], rules: [], rulePackages: [],
      ruleWarehouses: [], originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      expect(screen.getByText(/No UPS services from United States yet\./i)).toBeInTheDocument()
      expect(screen.getByText(/No FEDEX services from United States yet\./i)).toBeInTheDocument()
      expect(screen.getByText(/No USPS services from United States yet\./i)).toBeInTheDocument()
    })
    // "Sync from carrier" CTAs — one per empty tile.
    const syncCtas = screen.getAllByRole('button', { name: /^Sync from carrier$/i })
    expect(syncCtas.length).toBe(3)
  })
})

// ===================== Empty state (pin current bug) =====================

describe('ShippingServicesPage — empty state (behavior pinned)', () => {
  it('has NO top-level empty state — always renders 3 per-carrier tiles even with zero services', async () => {
    // TODO: `page-tests-bug` — the page has no cross-carrier empty state.
    // Even when the entire catalog is empty, three per-carrier tiles render
    // (with their own per-tile empty messages). Confusing when the operator
    // just wants to see "no catalog yet, sync a carrier" up-top. Follow-up
    // issue tracks a proper unified empty state; this test pins the current
    // behavior so the follow-up fix is easy to gate.
    catalogMock.mockResolvedValue({
      services: [], links: [], rules: [], rulePackages: [],
      ruleWarehouses: [], originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/UPS · from US/i)).toBeInTheDocument())

    // NO cross-carrier "No services synced yet" copy — pinned behavior.
    expect(screen.queryByText(/no services synced yet/i)).not.toBeInTheDocument()
    // Only per-tile empty messages instead.
    expect(screen.getByText(/No UPS services from United States yet\./i)).toBeInTheDocument()
  })
})

// ===================== Error path =====================

describe('ShippingServicesPage — error path', () => {
  it('catalog rejection → notify.apiError("Failed to load the catalog.") and page still renders', async () => {
    catalogMock.mockRejectedValue(new Error('boom'))
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(
        expect.any(Error),
        'Failed to load the catalog.',
      ),
    )
    // Empty per-carrier tiles still render (no ghost rows).
    expect(screen.getByText(/UPS · from US/i)).toBeInTheDocument()
  })
})

// ===================== Role parity =====================

describe('ShippingServicesPage — role parity (no page-level gate)', () => {
  it.each(['ADMIN', 'USER', 'TENANT'] as const)(
    '%s sees identical list rendering (page does not import useAppSession)',
    async (role) => {
      mockRole = role
      catalogMock.mockResolvedValue({
        services: [svc(1, 'UPS', 'GROUND', { name: 'UPS Ground' })],
        links: [], rules: [], rulePackages: [], ruleWarehouses: [],
        originCountries: ['US'],
      })
      listPresetsMock.mockResolvedValue([])

      const Page = await loadPage()
      renderPage(Page)

      await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
      // Per-row switch is rendered for every role (backend is the trust boundary).
      const switches = screen.getAllByRole('switch')
      expect(switches.length).toBeGreaterThan(0)
    },
  )
})

// ===================== Sprint 52 PR 3 — unlinked-package warnings =====================

describe('ShippingServicesPage — unlinked-package warning polish', () => {
  it('renders amber "⚠ 0" badge on an ENABLED service with zero linked packages', async () => {
    catalogMock.mockResolvedValue({
      services: [svc(1, 'UPS', '03', { name: 'UPS Ground', enabled: true })],
      links: [], // zero links for service 1
      rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
    const badge = screen.getByTestId('pkg-count-1')
    // Warning glyph + explicit "0" (not the neutral "+" used for disabled rows).
    expect(badge.textContent).toContain('⚠ 0')
    // Amber palette classes drive the visual; check one anchor.
    expect(badge.className).toContain('amber')
    // Title is the actionable message — names the exact production error
    // the admin will see in prod if they don't act.
    expect(badge.getAttribute('title')).toContain('SERVICE_HAS_NO_LINKED_PACKAGES')
  })

  it('renders neutral "+" badge on a DISABLED service with zero links (no warning)', async () => {
    catalogMock.mockResolvedValue({
      services: [svc(1, 'UPS', '03', { name: 'UPS Ground', enabled: false })],
      links: [],
      rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText('UPS Ground')).toBeInTheDocument())
    const badge = screen.getByTestId('pkg-count-1')
    // Disabled → no warning; neutral "+" glyph as before.
    expect(badge.textContent?.trim()).toBe('+')
    expect(badge.className).not.toContain('amber')
  })

  it('renders emerald "N" badge on an enabled service that IS linked (no warning)', async () => {
    catalogMock.mockResolvedValue({
      services: [svc(1, 'FEDEX', 'FEDEX_2_DAY', { enabled: true })],
      links: [
        { serviceId: 1, presetId: 100 },
        { serviceId: 1, presetId: 101 },
      ],
      rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    const badge = await waitFor(() => screen.getByTestId('pkg-count-1'))
    expect(badge.textContent).toContain('2')
    expect(badge.className).toContain('emerald')
    expect(badge.className).not.toContain('amber')
  })

  it('renders "⚠ N need packages" chip on the carrier header when any enabled service is unlinked', async () => {
    catalogMock.mockResolvedValue({
      services: [
        svc(1, 'FEDEX', 'FEDEX_GROUND', { enabled: true }), // needs
        svc(2, 'FEDEX', 'FEDEX_2_DAY', { enabled: true }),  // linked
        svc(3, 'FEDEX', 'FEDEX_HD', { enabled: false }),    // disabled, excluded
      ],
      links: [{ serviceId: 2, presetId: 100 }],
      rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    const headerChip = await waitFor(() => screen.getByTestId('carrier-needs-packages-FEDEX'))
    // Only service 1 counts (2 has a link, 3 is disabled).
    expect(headerChip.textContent).toContain('1')
    expect(headerChip.textContent).toContain('need packages')
    // UPS and USPS don't render the chip at all — no enabled unlinked services.
    expect(screen.queryByTestId('carrier-needs-packages-UPS')).toBeNull()
    expect(screen.queryByTestId('carrier-needs-packages-USPS')).toBeNull()
  })

  it('does NOT render the carrier header chip when every enabled service has ≥1 link', async () => {
    catalogMock.mockResolvedValue({
      services: [svc(1, 'UPS', '03', { enabled: true })],
      links: [{ serviceId: 1, presetId: 100 }],
      rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/UPS · from US/i)).toBeInTheDocument())
    expect(screen.queryByTestId('carrier-needs-packages-UPS')).toBeNull()
  })
})

// ===================== Sprint 52 PR X — brandedPackagingAllowed=false =====================

describe('ShippingServicesPage — CUSTOM-only badge (Sprint 52 PR X)', () => {
  it('renders grey "CUSTOM" badge for services with brandedPackagingAllowed=false', async () => {
    // FEDEX_GROUND is Ground-family — V30 sets branded_packaging_allowed=
    // false. Pre-PR-X the row showed the amber "⚠ 0" warning because it
    // had zero linked packages; that's misleading (nothing to fix). Now
    // it shows a neutral "CUSTOM" badge.
    catalogMock.mockResolvedValue({
      services: [svc(1, 'FEDEX', 'FEDEX_GROUND', { enabled: true, brandedPackagingAllowed: false })],
      links: [], // empty pool by design for Ground-family
      rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    const badge = await waitFor(() => screen.getByTestId('pkg-count-1'))
    expect(badge.textContent).toContain('CUSTOM')
    // Not the amber-warning palette.
    expect(badge.className).not.toContain('amber')
    // Title hints at the "by design" nature so admins don't misread it as unconfigured.
    expect(badge.getAttribute('title')).toContain('CUSTOM-packaging-only')
  })

  it('CUSTOM-only services are NOT counted in the carrier "need packages" chip', async () => {
    // Even though FEDEX_GROUND has zero links + is enabled, it must not
    // inflate the aggregate warning count — its empty state is by design.
    catalogMock.mockResolvedValue({
      services: [
        svc(1, 'FEDEX', 'FEDEX_GROUND', { enabled: true, brandedPackagingAllowed: false }),
        svc(2, 'FEDEX', 'FEDEX_2_DAY', { enabled: true }), // this one WOULD trigger the chip
      ],
      links: [], // both zero
      rules: [], rulePackages: [], ruleWarehouses: [],
      originCountries: ['US'],
    })
    listPresetsMock.mockResolvedValue([])

    const Page = await loadPage()
    renderPage(Page)

    const headerChip = await waitFor(() => screen.getByTestId('carrier-needs-packages-FEDEX'))
    // Only service 2 (FEDEX_2_DAY) counts — Ground is excluded by the flag.
    expect(headerChip.textContent).toContain('1')
    expect(headerChip.textContent).not.toContain('2 ')
  })
})
