import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'

/**
 * Sprint 53 page-tests — ShippingCatalogPage · tab-shell + URL sync.
 *
 * Scope (this slice only):
 *   - Renders 2 tab pills (Shipping services / Packages), role=tablist,
 *     aria-selected toggles.
 *   - Default tab = Services when `?tab` absent.
 *   - `?tab=packages` on load starts on Packages.
 *   - Clicking Packages pill sets URL `?tab=packages` (replace mode).
 *   - Clicking Services pill flips URL back to `?tab=services`.
 *   - Only one child mounts at a time (unmount handles Refresh handoff).
 *   - Role parity — ADMIN / USER / TENANT render identically (no per-shell gate).
 *
 * Child components (ShippingServicesPage, PackagesPage) are stubbed so this
 * slice never exercises their internals — sibling agents cover those.
 */

// ---------- Mock both children BEFORE importing the page ----------

vi.mock('./ShippingServicesPage', () => ({
  default: () => <div data-testid="services-tab-stub">SERVICES-STUB</div>,
}))

vi.mock('./PackagesPage', () => ({
  default: () => <div data-testid="packages-tab-stub">PACKAGES-STUB</div>,
}))

// useAppSession — the shell doesn't consume it today, but stub anyway so
// any indirect import from utils/shared components resolves cleanly.
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

// ---------- Fail-loud fetch spy (anti-fallback) ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  mockRole = 'ADMIN'
})

// ---------- Helpers ----------

/**
 * Location probe — lets a test read the current search-string after
 * a URL sync. Rendered inside the same MemoryRouter as the SUT.
 */
function LocationProbe({ testId = 'loc' }: { testId?: string }) {
  const loc = useLocation()
  return <span data-testid={testId}>{loc.search}</span>
}

async function loadPage() {
  const mod = await import('./ShippingCatalogPage')
  return mod.default
}

function renderWithRouter(Page: React.ComponentType, initialEntry = '/settings/shipping-catalog') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route
          path="/settings/shipping-catalog"
          element={
            <>
              <Page />
              <LocationProbe />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  )
}

// ===================== Positive shell =====================

describe('ShippingCatalogPage — tab pills', () => {
  it('renders both tab pills with role=tab under a role=tablist', async () => {
    const Page = await loadPage()
    renderWithRouter(Page)

    expect(screen.getByRole('tablist', { name: /shipping catalog tabs/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /shipping services/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /packages/i })).toBeInTheDocument()
  })

  it('defaults to Services tab when no ?tab query param is present', async () => {
    const Page = await loadPage()
    renderWithRouter(Page)

    const servicesTab = screen.getByRole('tab', { name: /shipping services/i })
    const packagesTab = screen.getByRole('tab', { name: /packages/i })
    expect(servicesTab).toHaveAttribute('aria-selected', 'true')
    expect(packagesTab).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByTestId('services-tab-stub')).toBeInTheDocument()
    expect(screen.queryByTestId('packages-tab-stub')).not.toBeInTheDocument()
  })

  it('starts on Packages tab when ?tab=packages is in the URL on load', async () => {
    const Page = await loadPage()
    renderWithRouter(Page, '/settings/shipping-catalog?tab=packages')

    expect(screen.getByRole('tab', { name: /packages/i })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: /shipping services/i })).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByTestId('packages-tab-stub')).toBeInTheDocument()
    expect(screen.queryByTestId('services-tab-stub')).not.toBeInTheDocument()
  })
})

// ===================== URL sync =====================

describe('ShippingCatalogPage — URL sync', () => {
  it('clicking the Packages pill flips URL to ?tab=packages and swaps the child', async () => {
    const Page = await loadPage()
    renderWithRouter(Page)

    await act(async () => {
      await userEvent.click(screen.getByRole('tab', { name: /packages/i }))
    })

    expect(screen.getByRole('tab', { name: /packages/i })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('packages-tab-stub')).toBeInTheDocument()
    expect(screen.queryByTestId('services-tab-stub')).not.toBeInTheDocument()
    expect(screen.getByTestId('loc').textContent).toContain('tab=packages')
  })

  it('clicking Services after Packages flips URL back to ?tab=services', async () => {
    const Page = await loadPage()
    renderWithRouter(Page, '/settings/shipping-catalog?tab=packages')

    await act(async () => {
      await userEvent.click(screen.getByRole('tab', { name: /shipping services/i }))
    })

    expect(screen.getByRole('tab', { name: /shipping services/i })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('services-tab-stub')).toBeInTheDocument()
    expect(screen.queryByTestId('packages-tab-stub')).not.toBeInTheDocument()
    expect(screen.getByTestId('loc').textContent).toContain('tab=services')
  })

  it('an unknown ?tab value falls back to Services (only "packages" is honored)', async () => {
    const Page = await loadPage()
    renderWithRouter(Page, '/settings/shipping-catalog?tab=bogus')

    // The shell's initialTab picks 'packages' only when the value is exactly
    // 'packages'; anything else falls through to 'services'.
    expect(screen.getByRole('tab', { name: /shipping services/i })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('services-tab-stub')).toBeInTheDocument()
  })
})

// ===================== Exclusive mount =====================

describe('ShippingCatalogPage — exclusive child mount', () => {
  it('mounts only ONE child at a time (never both)', async () => {
    const Page = await loadPage()
    renderWithRouter(Page)

    expect(screen.getAllByTestId(/tab-stub$/).length).toBe(1)

    await act(async () => {
      await userEvent.click(screen.getByRole('tab', { name: /packages/i }))
    })

    expect(screen.getAllByTestId(/tab-stub$/).length).toBe(1)
  })

  it('preserves the panel container aria-linkage (tab controls its panel)', async () => {
    const Page = await loadPage()
    renderWithRouter(Page)

    const servicesTab = screen.getByRole('tab', { name: /shipping services/i })
    expect(servicesTab).toHaveAttribute('aria-controls', 'shipping-catalog-panel-services')
    const panel = document.getElementById('shipping-catalog-panel-services')
    expect(panel).not.toBeNull()
    expect(panel?.getAttribute('role')).toBe('tabpanel')
  })
})

// ===================== Role parity =====================

describe('ShippingCatalogPage — role parity (no per-shell gate)', () => {
  it.each(['ADMIN', 'USER', 'TENANT'] as const)(
    '%s sees both tab pills (shell has no role gate; children handle their own)',
    async (role) => {
      mockRole = role
      const Page = await loadPage()
      renderWithRouter(Page)

      expect(screen.getByRole('tab', { name: /shipping services/i })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: /packages/i })).toBeInTheDocument()
      // Services tab is active for every role (default) — the shell does not
      // change tab visibility or default based on role.
      expect(screen.getByRole('tab', { name: /shipping services/i }))
        .toHaveAttribute('aria-selected', 'true')
    },
  )
})
