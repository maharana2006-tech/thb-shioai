import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 52 — inline "⚠" amber badge next to the client code column
 * whenever hasBillingMarkup === false. Undefined (pre-Sprint-52 stale
 * cache) is treated as OK to avoid false alarms on old responses.
 * Same predicate as ClientMarkupTab banner, ClientEditorPage step-nav,
 * and the create-toast nudge — all four surfaces agree.
 */

const listClients = vi.fn()

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClients(...args),
    exportClientsCsv: vi.fn(),
    cascadePreview: vi.fn(),
    toggleActive: vi.fn(),
    deleteClient: vi.fn(),
    getClient: vi.fn(),
    createClient: vi.fn(),
    updateClient: vi.fn(),
    listClientAccounts: vi.fn().mockResolvedValue([]),
  },
}))

const sessionValue = { username: 'admin', role: 'ADMIN' as string }
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => sessionValue,
  clearAuthSession: vi.fn(),
  storeAuthSession: vi.fn(),
  syncCarrierSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(),
}))

vi.mock('../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    apiError: vi.fn(),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

vi.mock('./modals/CustomsProfileModal', () => ({ default: () => null }))

const seedClient = (code: string, hasBillingMarkup: boolean | undefined) => ({
  id: code === 'ACME' ? 1 : code === 'THB000' ? 2 : 3,
  clientCode: code,
  name: `${code} Co`,
  email: null,
  phone: null,
  status: 'ACTIVE',
  shipFrom: null,
  returnAddress: null,
  returnSameAsShipFrom: true,
  defaultCurrency: null,
  defaultWeightUnit: null,
  defaultDimUnit: null,
  timezone: null,
  defaultOriginCountry: null,
  createdAt: null,
  updatedAt: null,
  carrierAccounts: [],
  orderCount: 0,
  hasBillingMarkup,
})

const pageOf = (clients: ReturnType<typeof seedClient>[]) => ({
  data: {
    content: clients,
    pageNumber: 0,
    pageSize: 25,
    totalElements: clients.length,
    totalPages: 1,
  },
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ClientsPage')
  return mod.default
}

function OutletShell() {
  return <Outlet context={{ registerRefresh: () => {} }} />
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/clients']}>
      <Routes>
        <Route element={<OutletShell />}>
          <Route path="/settings/clients" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  listClients.mockReset()
})

afterEach(() => {
  cleanup()
})

describe('ClientsPage — markup-missing badge (Sprint 52)', () => {
  it('renders the amber ⚠ badge next to a client with hasBillingMarkup=false', async () => {
    listClients.mockResolvedValue(pageOf([seedClient('THB000', false)]))

    const Page = await loadPage()
    renderPage(Page)

    // Title carries the actionable production-error message so admins
    // hovering know exactly why the badge is there + where to fix it.
    const badge = await waitFor(() => screen.getByTestId('client-needs-markup-THB000'))
    expect(badge.getAttribute('title')).toContain('no billing markup saved')
    expect(badge.getAttribute('title')).toContain('Billing markup')
  })

  it('does NOT render the badge for a client with hasBillingMarkup=true', async () => {
    listClients.mockResolvedValue(pageOf([seedClient('ACME', true)]))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listClients).toHaveBeenCalled())
    // Give the row a tick to render.
    await new Promise((r) => setTimeout(r, 0))
    expect(screen.queryByTestId('client-needs-markup-ACME')).toBeNull()
  })

  it('does NOT render the badge when hasBillingMarkup is undefined (stale pre-Sprint-52 response)', async () => {
    // Older cached responses won't carry the field. Absent-vs-false is a
    // meaningful distinction — we treat missing as "unknown, don't alarm"
    // rather than "missing, alarm". Avoids a wave of false-positive amber
    // badges the first time the FE deploys against an old cache.
    listClients.mockResolvedValue(pageOf([seedClient('LEGACY', undefined)]))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(listClients).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 0))
    expect(screen.queryByTestId('client-needs-markup-LEGACY')).toBeNull()
  })
})
